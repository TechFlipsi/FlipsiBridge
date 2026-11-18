"""B4 (Multi-Gerät BACKEND-VERDRAHTUNG, Sir-Go) — Echo-Verband, Backfill-Antwort,
Revoke-Verband, last_seen/relay.state, Allowlist-Routing.

Frame-Verträge aus dem App-Code gelesen (nicht geraten):
- ChatSyncRules.kt: chat.sync.enc, Marker sync:true / sync_of = Ursprungs-msg_id.
- ChatBackfillRules.kt: chat.backfill_request {type,v,chat_id,gateway_chat,limit};
  chat.backfill.enc mit entries|messages (Array, text|message Pflicht);
  chat.status_snapshot ids-only (Cap 500), Array- UND Map-Form toleriert.
- PairingFrameRules.kt / DevicesCard (B7): relay.state devices[] (maskiert,
  online, admin, last_seen-ms), Revoke = WS-Kick 4403-Familie 'device-revoked'.
"""

import asyncio
import json
import os
import sys
import time

import aiohttp
import pytest

from tools.android_relay import start_relay, stop_relay

# Adapter-Import wie in tests/test_media_relay.py (Skeleton-Pfad, kein Gateway).
_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "contrib", "hermes-chat-plugin")
if _PATH not in sys.path:
    sys.path.insert(0, _PATH)
sys.modules.setdefault("gateway", None)
import adapter as _ph3_adapter  # noqa: E402
from test_adapter import make_adapter  # noqa: E402

import tests.test_media_relay as _media_tests  # noqa: E402

_recv_until = _media_tests._recv_until
from tests.test_android_relay import _await_log_record  # noqa: E402


# ───────────────────────────────── Fixtures/Helfer ─────────────────────────────

AGENT_TOKEN = "b4-agent-token"
CODE = "B4MULTI1"
IK_PRIV = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
IK_PUB = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
SPK_PRIV = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
SPK_PUB = "52a5524b0c32e8a47f239ab6ca8e42c3b9316a54cae4854ecdb5889311cc9a7e"

IDENTITY = {"identity_priv": IK_PRIV, "identity_pub": IK_PUB,
            "prekey_priv": SPK_PRIV, "prekey_pub": SPK_PUB}

DEVICE_A = "device-b4a"
DEVICE_B = "device-b4b"
PORT = 19971


@pytest.fixture(autouse=True)
def _b4_reset():
    from tools.android_relay import _chat_rate_hits, _chat_rate_lock
    with _chat_rate_lock:
        _chat_rate_hits.clear()  # Rate-Slots kollidieren nicht über Testgrenzen
    yield
    with _chat_rate_lock:
        _chat_rate_hits.clear()
    stop_relay()


def _write_registry(tmp_path) -> str:
    """Registry v2 vorgeseedet: A = Admin, B = bekanntes Zweitgerät."""
    path = tmp_path / "relay-devices.json"
    path.write_text(json.dumps({
        "v": 2, "ts": 0,
        "devices": {
            DEVICE_A: {"alias": "Admin", "admin": True, "epoch_of_enroll": 1},
            DEVICE_B: {"alias": "", "admin": False, "epoch_of_enroll": 1},
        },
    }), encoding="utf-8")
    return str(path)


def _write_config(tmp_path, rate: int = 50) -> str:
    path = tmp_path / "relay-config.json"
    path.write_text(json.dumps({
        "ANDROID_AGENT_TOKEN_ALPHA": AGENT_TOKEN,
        "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
        "CHAT_RATE_PER_SECOND": rate,
        "ANDROID_AGENT_IDENTITY_ALPHA": IK_PUB,
        "ANDROID_AGENT_PREKEY_ALPHA": SPK_PUB,
        "ANDROID_AGENT_FINGERPRINT_ALPHA":
            _ph3_adapter.fingerprint_of_pub(bytes.fromhex(IK_PUB)),
    }), encoding="utf-8")
    return str(path)


def _start(tmp_path, monkeypatch, rate: int = 50, port: int = PORT) -> str:
    monkeypatch.setenv("ANDROID_RELAY_CONFIG", _write_config(tmp_path, rate))
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_REGISTRY", _write_registry(tmp_path))
    start_relay(pairing_code=CODE, port=port)
    return f"http://127.0.0.1:{port}"


async def _connect_devices(http, base, port=PORT):
    """Zwei Geräte-Slots + Agent-WS (Registry-Pfad vorgeseedet)."""
    phone_a = await http.ws_connect(
        f"ws://127.0.0.1:{port}/ws",
        headers={"Authorization": f"Bearer {CODE}", "X-Device-Id": DEVICE_A})
    await _recv_until(phone_a, "relay.state", timeout=6)
    agent_ws = await http.ws_connect(
        f"ws://127.0.0.1:{port}/agent_ws",
        headers={"Authorization": f"Bearer {AGENT_TOKEN}"})
    await _recv_until(phone_a, "relay.state", timeout=6)  # Agent-Presence-Push
    phone_b = await http.ws_connect(
        f"ws://127.0.0.1:{port}/ws",
        headers={"Authorization": f"Bearer {CODE}", "X-Device-Id": DEVICE_B})
    await _recv_until(phone_b, "relay.state", timeout=6)
    return phone_a, phone_b, agent_ws


async def _handshake(http, base, agent_ws, phone_ws, device_id, session_id, chat_id):
    """E2E-Handshake je Gerät: /crypto_hello → Agent-ack → ack ans Phone."""
    peer = _ph3_adapter.PhoneCryptoPeer("alpha", IK_PUB, SPK_PUB)
    hello = peer.make_hello(session_id)
    resp = await http.post(f"{base}/crypto_hello",
                          json={**hello, "device_id": device_id},
                          headers={"Authorization": f"Bearer {CODE}"})
    assert resp.status == 200
    hello_frame = await agent_ws.receive_json(timeout=6)
    assert hello_frame["type"] == "crypto.hello"
    assert hello_frame.get("device_id") == device_id
    agent_session, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
        hello_frame, IDENTITY)
    await agent_ws.send_json(ack)
    ack_frame = await _recv_until(phone_ws, "crypto.hello_ack", timeout=6)
    peer.consume_ack(ack_frame)
    return peer, agent_session


def _enc_frame(session_id, chat_id, rk, n, nonce, ct, ftype="chat.sync.enc"):
    return {"type": ftype, "v": 1, "chat_id": chat_id, "agent_id": "alpha",
            "session_id": session_id,
            "hdr": {"rk": rk, "n": n, "pn": 0},
            "nonce": nonce, "ct": ct, "ts": int(time.time() * 1000)}


# ───────────────────────────────── Relay-Tests ─────────────────────────────────


class TestB4RelayRouting:
    """Allowlist +1: sync/backfill.enc/status_snapshot mit Session-Routing."""

    def test_allowlist_routes_new_frames_targeted(self, tmp_path, monkeypatch, caplog):
        return asyncio.run(self._test_allowlist_routes_new_frames_targeted(tmp_path, monkeypatch, caplog))

    async def _test_allowlist_routes_new_frames_targeted(self, tmp_path, monkeypatch, caplog) -> None:
        base = _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone_a, phone_b, agent_ws = await _connect_devices(http, base)
            peer_a, _ = await _handshake(http, base, agent_ws, phone_a,
                                         DEVICE_A, "b4sess-a", "chat-aaaa-1111")
            peer_b, _ = await _handshake(http, base, agent_ws, phone_b,
                                         DEVICE_B, "b4sess-b", "chat-bbbb-2222")

            # 1) chat.sync.enc je Session → NUR der Zielslot (Session-Routing).
            sync_b = peer_b.encrypt_frame(
                "chat.sync.enc", "chat-bbbb-2222",
                {"text": "hallo von A", "sync": True, "sync_of": "origin-1"},
                clear={"msg_id": "origin-1"})
            await agent_ws.send_json(sync_b)
            got = await _recv_until(phone_b, "chat.sync.enc", timeout=6)
            assert got["ct"] == sync_b["ct"]
            sync_a = peer_a.encrypt_frame(
                "chat.sync.enc", "chat-aaaa-1111",
                {"text": "hallo von B", "sync": True, "sync_of": "origin-2"},
                clear={"msg_id": "origin-2"})
            await agent_ws.send_json(sync_a)
            parked_a: list = []
            got_a = await _recv_until(phone_a, "chat.sync.enc", timeout=6,
                                      parked=parked_a)
            assert got_a["ct"] == sync_a["ct"]
            assert parked_a == []  # kein Fremd-Frame im Fenster

            # 2) chat.backfill.enc für Session-A → NUR phone_a.
            bf = peer_a.encrypt_frame(
                "chat.backfill.enc", "chat-aaaa-1111",
                {"entries": [{"text": "alt", "from_agent": False},
                             {"text": "antwort", "from_agent": True}],
                 "truncated": False})
            await agent_ws.send_json(bf)
            got = await _recv_until(phone_a, "chat.backfill.enc", timeout=6)
            assert got["ct"] == bf["ct"]

            # 3) chat.status_snapshot (KLAR, ids-only) — Array-Form, session-
            #    geroutet → NUR phone_a.
            snap = {"type": "chat.status_snapshot", "v": 1,
                    "agent_id": "alpha", "chat_id": "chat-aaaa-1111",
                    "session_id": "b4sess-a",
                    "statuses": [{"msg_id": "origin-1", "status": "delivered"}]}
            await agent_ws.send_json(snap)
            got = await _recv_until(phone_a, "chat.status_snapshot", timeout=6)
            assert got["statuses"] == [{"msg_id": "origin-1", "status": "delivered"}]

            # 4) BEIDE App-Parser-Formen passieren das Gate (Map-Form ebenso).
            snap_map = {"type": "chat.status_snapshot", "v": 1,
                        "agent_id": "alpha", "chat_id": "chat-aaaa-1111",
                        "session_id": "b4sess-a",
                        "statuses": {"msg-x": "read"}}
            await agent_ws.send_json(snap_map)
            got = await _recv_until(phone_a, "chat.status_snapshot", timeout=6)
            assert got["statuses"] == [{"msg_id": "msg-x", "status": "read"}]
            # 5) WIRKLICH kaputte Form (kein Array/kein Map) = fail-closed
            #    verworfen (Log-Beweis); unbekannter STATUS in ansonsten
            #    gültiger Zeile wird tolerant ZEILENWEISE gefiltert
            #    (Parität App-Parser isKnown).
            await agent_ws.send_json({**snap, "statuses": "kaputt"})
            _await_log_record(caplog, "chat.status_snapshot mit ungültiger statuses-Form")
            await phone_a.close()
            await phone_b.close()

    def test_enc_frame_without_ack_session_fail_closed(self, tmp_path, monkeypatch, caplog):
        return asyncio.run(self._test_enc_frame_without_ack_session_fail_closed(tmp_path, monkeypatch, caplog))

    async def _test_enc_frame_without_ack_session_fail_closed(self, tmp_path, monkeypatch, caplog) -> None:
        base = _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone_a, phone_b, agent_ws = await _connect_devices(http, base)
            await agent_ws.send_json(
                _enc_frame("nicht-aktiv", "chat-aaaa-1111", "0" * 64, 0,
                           "A" * 16, "AAA="))
            await asyncio.sleep(0.3)
            _await_log_record(caplog, "chat.sync.enc ohne bestätigte E2E-Session — verworfen (fail-closed)")
            await phone_a.close()
            await phone_b.close()

    def test_sync_own_rate_bucket_not_starving_reply(self, tmp_path, monkeypatch, caplog):
        return asyncio.run(self._test_sync_own_rate_bucket_not_starving_reply(tmp_path, monkeypatch, caplog))

    async def _test_sync_own_rate_bucket_not_starving_reply(self, tmp_path, monkeypatch, caplog) -> None:
        """Eigener Bucket agentsync: Sync-Sättigung verdrängt chat.reply.enc
        NICHT (FLIPSI-Frame-Guard-Verband); Rate-Chain wie gehabt separat."""
        import logging as _logging
        caplog.set_level(_logging.INFO, logger="android_relay")  # Relay-Thread-Logs
        base = _start(tmp_path, monkeypatch, rate=1)
        async with aiohttp.ClientSession() as http:
            phone_a, phone_b, agent_ws = await _connect_devices(http, base)
            peer_a, _ = await _handshake(http, base, agent_ws, phone_a,
                                         DEVICE_A, "b4sess-a", "chat-aaaa-1111")
            for i in range(2):
                await agent_ws.send_json(
                    _enc_frame("b4sess-a", "chat-aaaa-1111", "1" * 64, i,
                               "B" * 16, "AAA="))
            got = await _recv_until(phone_a, "chat.sync.enc", timeout=6)
            assert got["ct"] == "AAA="
            # Der 2. Sync war im EIGENEN Bucket (rate=1/s) — der Verwurf ist
            # Log-Beweis (A bekam genau 1 Sync-Frame); der reply-Bucket ist
            # UNVERÄNDERT (Separate Buckets bewiesen).
            reply = peer_a.encrypt_frame(
                "chat.reply.enc", "chat-aaaa-1111",
                {"text": "Antwort", "msg_id": "rep-1"}, clear={"msg_id": "rep-1"})
            await agent_ws.send_json(reply)
            got = await _recv_until(phone_a, "chat.event.enc", timeout=6)
            assert got["ct"] == reply["ct"]
            _await_log_record(caplog, "chat.sync.enc verworfen — Sync-Rate-Limit")
            await phone_a.close()
            await phone_b.close()

    def test_backfill_request_forwarded_to_agent_ws(self, tmp_path, monkeypatch, caplog):
        return asyncio.run(self._test_backfill_request_forwarded_to_agent_ws(tmp_path, monkeypatch, caplog))

    async def _test_backfill_request_forwarded_to_agent_ws(self, tmp_path, monkeypatch, caplog) -> None:
        base = _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone_a, phone_b, agent_ws = await _connect_devices(http, base)
            peer_a, _ = await _handshake(http, base, agent_ws, phone_a,
                                         DEVICE_A, "b4sess-a", "chat-aaaa-1111")
            await _handshake(http, base, agent_ws, phone_b,
                             DEVICE_B, "b4sess-b", "chat-bbbb-2222")
            # chat_owner setzen: /chat-enc-POST auf chat-aaaa-1111 (Klartext
            # wäre mit aktiver E2E-Session 403 — Downgrade-Schutz).
            post = peer_a.encrypt_frame(
                "chat.send.enc", "chat-aaaa-1111", {"text": "Hallo von A"})
            post_body = {k: post[k] for k in
                         ("session_id", "chat_id", "hdr", "nonce", "ct", "ts")}
            post_body["agent_id"] = "alpha"
            resp = await http.post(f"{base}/chat", json=post_body,
                                   headers={"Authorization": f"Bearer {CODE}"})
            assert resp.status == 200
            sent = await agent_ws.receive_json(timeout=6)  # chat.send.enc
            assert sent["type"] == "chat.send.enc"
            # Phone B fordert Backfill für SEINE Wire-chat_id:
            req_b = {"type": "chat.backfill_request", "v": 1,
                     "chat_id": "chat-bbbb-2222",
                     "gateway_chat": "chat-bbbb-2222", "limit": 50}
            await phone_b.send_json(req_b)
            fwd = await agent_ws.receive_json(timeout=6)
            assert fwd["type"] == "chat.backfill_request"
            assert fwd["chat_id"] == "chat-bbbb-2222"
            assert fwd["gateway_chat"] == "chat-bbbb-2222"
            assert fwd["limit"] == 50
            assert fwd["device_id"] == DEVICE_B  # Stempel fürs Session-Routing
            # Kap. 2.3-Fallback (frisches Gerät): chat-bbbb-2222 hat KEINEN
            # chat_owner (B hat nie gepostet) — der Router fällt auf den
            # Session-Broker (device-b4b → Agent über das Hello) zurück und
            # zustellt TROTZDEM.
            await phone_b.send_json({**req_b, "chat_id": "chat-bbbb-noown-1",
                                     "gateway_chat": "chat-bbbb-noown-1"})
            fwd2 = await agent_ws.receive_json(timeout=6)
            assert fwd2["type"] == "chat.backfill_request"
            assert fwd2["chat_id"] == "chat-bbbb-noown-1"
            assert fwd2["device_id"] == DEVICE_B
            # KAPUTTE Form (chat_id < 8) = fail-closed verworfen (Log-Beweis).
            await phone_b.send_json({**req_b, "chat_id": "kurz"})
            _await_log_record(caplog, "ohne gültiges chat_id — verworfen")
            await phone_a.close()
            await phone_b.close()


class TestB4Revoke:
    """device_revoke: Admin ok / Non-Admin fail-closed / 4403-Familie-Kick /
    Registry + Outbox + Session-Broker weg, Adapter-Verband benachrichtigt."""

    def test_admin_revoke_kicks_target_with_4404_and_notifies_agent(self, tmp_path, monkeypatch, caplog):
        return asyncio.run(self._test_admin_revoke_kicks_target_with_4404_and_notifies_agent(tmp_path, monkeypatch, caplog))

    async def _test_admin_revoke_kicks_target_with_4404_and_notifies_agent(self, tmp_path, monkeypatch, caplog) -> None:
        base = _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone_a, phone_b, agent_ws = await _connect_devices(http, base)
            await _handshake(http, base, agent_ws, phone_b,
                             DEVICE_B, "b4sess-b", "chat-bbbb-2222")
            import tools.android_relay as _relay
            await phone_a.send_json({"type": "device_revoke", "v": 1,
                                     "device_id": DEVICE_B})
            # WS-Kick: Familiencode 4404 + Vertrags-Reason 'device-revoked'.
            msg = await phone_b.receive(timeout=6)
            assert msg.type == aiohttp.WSMsgType.CLOSE
            assert phone_b.close_code == 4404
            assert _relay._WS_CLOSE_REASON_DEVICE_REVOKED == b"device-revoked"
            # Adapter-Verband: device.revoked am Agent-WS.
            frames = []
            for _ in range(3):
                try:
                    frames.append(await agent_ws.receive_json(timeout=2))
                except asyncio.TimeoutError:
                    break
            assert any(f.get("type") == "device.revoked"
                       and f.get("device_id") == DEVICE_B for f in frames)
            state = _relay._relay_instance
            assert DEVICE_B not in state.device_registry
            assert DEVICE_B not in state.devices
            assert not [sid for sid, e in state.crypto_sessions.items()
                        if isinstance(e, dict) and e.get("device_id") == DEVICE_B]
            reg = json.loads(open(_relay._device_registry_path(),
                                  encoding="utf-8").read())
            assert DEVICE_B not in reg["devices"]
            assert DEVICE_A in reg["devices"]
            # Rest-Gerät bekommt den frischen relay.state (Revoke-Broadcast).
            got = await _recv_until(phone_a, "relay.state", timeout=6)
            assert not [row for row in got["devices"] if not row["admin"]]
            await phone_a.close()

    def test_non_admin_revoke_refused_fail_closed(self, tmp_path, monkeypatch, caplog):
        return asyncio.run(self._test_non_admin_revoke_refused_fail_closed(tmp_path, monkeypatch, caplog))

    async def _test_non_admin_revoke_refused_fail_closed(self, tmp_path, monkeypatch, caplog) -> None:
        base = _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone_a, phone_b, agent_ws = await _connect_devices(http, base)
            await phone_b.send_json({"type": "device_revoke", "v": 1,
                                     "device_id": DEVICE_A})
            import tools.android_relay as _relay
            await asyncio.sleep(0.4)
            state = _relay._relay_instance
            assert DEVICE_A in state.device_registry
            assert DEVICE_B in state.device_registry
            _await_log_record(caplog, "NON-ADMIN Gerät")
            _await_log_record(caplog, "ABGEWIESEN")
            with pytest.raises(asyncio.TimeoutError):
                await agent_ws.receive_json(timeout=0.6)  # kein device.revoked
            await phone_a.close()
            await phone_b.close()

    def test_self_revoke_allowed_without_admin(self, tmp_path, monkeypatch, caplog):
        return asyncio.run(self._test_self_revoke_allowed_without_admin(tmp_path, monkeypatch, caplog))

    async def _test_self_revoke_allowed_without_admin(self, tmp_path, monkeypatch, caplog) -> None:
        base = _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone_a, phone_b, agent_ws = await _connect_devices(http, base)
            await phone_b.send_json({"type": "device_revoke", "v": 1})
            msg = await phone_b.receive(timeout=6)
            assert msg.type == aiohttp.WSMsgType.CLOSE
            assert phone_b.close_code == 4404
            import tools.android_relay as _relay
            state = _relay._relay_instance
            assert DEVICE_B not in state.device_registry
            assert DEVICE_A in state.device_registry  # Admin bleibt
            await phone_a.close()

    def test_revoke_via_request_id_handle(self, tmp_path, monkeypatch):
        return asyncio.run(self._test_revoke_via_request_id_handle(tmp_path, monkeypatch))

    async def _test_revoke_via_request_id_handle(self, tmp_path, monkeypatch) -> None:
        base = _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone_a, phone_b, agent_ws = await _connect_devices(http, base)
            import tools.android_relay as _relay
            state = _relay._relay_instance
            handle = _relay._device_revoke_id(state, DEVICE_B)
            await phone_a.send_json({"type": "device_revoke", "v": 1,
                                     "request_id": handle})
            msg = await phone_b.receive(timeout=6)
            assert msg.type == aiohttp.WSMsgType.CLOSE
            assert phone_b.close_code == 4404
            assert DEVICE_B not in state.device_registry
            # Handle entbunden (kein Wiederverwendungs-Rest).
            assert handle not in state.device_revoke_ids.values()
            # Handle-Handle eines FREMDEN Geräts funktioniert nicht vom
            # Non-Admin (fail-closed bleibt).
            await phone_a.close()
            await phone_b.close()


class TestB4LastSeen:
    """relay.state devices[]: Registry ∪ Slots, last_seen Unix-ms, offline
    sichtbar — Registry-Pflege (Connect + Disconnect-Stempel) persistiert."""

    def test_offline_device_visible_with_last_seen(self, tmp_path, monkeypatch, caplog):
        return asyncio.run(self._test_offline_device_visible_with_last_seen(tmp_path, monkeypatch, caplog))

    async def _test_offline_device_visible_with_last_seen(self, tmp_path, monkeypatch, caplog) -> None:
        registry_file = _write_registry(tmp_path)
        monkeypatch.setenv("ANDROID_RELAY_CONFIG", _write_config(tmp_path))
        monkeypatch.setenv("ANDROID_RELAY_DEVICE_REGISTRY", registry_file)
        start_relay(pairing_code=CODE, port=PORT)
        async with aiohttp.ClientSession() as http:
            phone_b = await http.ws_connect(
                f"ws://127.0.0.1:{PORT}/ws",
                headers={"Authorization": f"Bearer {CODE}", "X-Device-Id": DEVICE_B})
            await _recv_until(phone_b, "relay.state", timeout=6)
            await phone_b.close()
        await asyncio.sleep(0.5)  # Cleanup-Pfad stempelt last_seen + persistiert
        reg = json.loads(open(registry_file, encoding="utf-8").read())
        assert reg["devices"][DEVICE_B]["last_seen"] > 0
        # Frischer Connect: relay.state zeigt B OFFLINE mit last_seen.
        async with aiohttp.ClientSession() as http:
            phone_a = await http.ws_connect(
                f"ws://127.0.0.1:{PORT}/ws",
                headers={"Authorization": f"Bearer {CODE}", "X-Device-Id": DEVICE_A})
            state = await _recv_until(phone_a, "relay.state", timeout=6)
            rows = state["devices"]
            assert len(rows) == 2
            row_b = next(r for r in rows if r.get("admin") is False)
            assert row_b["online"] is False
            assert isinstance(row_b["last_seen"], int) and row_b["last_seen"] > 0
            assert isinstance(row_b["request_id"], str) and row_b["request_id"]
            row_a = next(r for r in rows if r.get("admin") is True)
            assert row_a["online"] is True
            assert row_a["last_seen"] > 0
            await phone_a.close()


# ───────────────────────────────── Adapter-Tests ───────────────────────────────


async def _establish_session(adapter, mock_ws, session_id, chat_id, device_id):
    """Hello-Handshake für EIN Gerät am Adapter-Skeleton."""
    peer = _ph3_adapter.PhoneCryptoPeer(
        "agent-a", IDENTITY["identity_pub"], IDENTITY["prekey_pub"])
    hello = peer.make_hello(session_id)
    adapter._connected = True  # ohne frame_loop: nur Send + Drain im Test
    adapter._ws = mock_ws
    adapter._identity = IDENTITY
    adapter._handle_crypto_hello({**hello, "device_id": device_id})
    await adapter.drain_bg_tasks()
    acks = [f for f in mock_ws.sent if f.get("type") == "crypto.hello_ack"
            and f.get("session_id") == session_id]
    assert acks, f"kein hello_ack für {session_id}"
    peer.consume_ack(acks[-1])
    sess = adapter._crypto_sessions.get(device_id)
    assert sess is not None
    sess.device_key = device_id
    sess.device_id = device_id
    sess.device_ik = peer.ik_pub.hex()
    return peer


def _dispatch_user_post(adapter, peer, chat_id, text, msg_id):
    frame = peer.encrypt_frame("chat.send.enc", chat_id, {"text": text},
                               clear={"msg_id": msg_id})
    frame["agent_id"] = "agent-a"
    adapter._dispatch_frame(frame)
    return frame


class TestB4EchoVerband:
    """Nutzer-Post von Gerät A → chat.sync.enc an ALLE ANDEREN Slots (je
    eigene Chiffre), sync_of-Kette, Dedup je msg_id."""

    def test_echo_to_neighbor_only_with_sync_of_chain(self, tmp_path):
        return asyncio.run(self._test_echo_to_neighbor_only_with_sync_of_chain(tmp_path))

    async def _test_echo_to_neighbor_only_with_sync_of_chain(self, tmp_path) -> None:
        adapter, ws = make_adapter(
            {"FLIPSI_PHONE_PIN_FILE": str(tmp_path / "pins.json")})
        adapter.message_callback = None
        peer_a = await _establish_session(adapter, ws, "b4sess-a",
                                          "chat-aaaa-1111", DEVICE_A)
        peer_b = await _establish_session(adapter, ws, "b4sess-b",
                                          "chat-bbbb-2222", DEVICE_B)
        _dispatch_user_post(adapter, peer_a, "chat-aaaa-1111", "Grüße von A",
                            "origin-77")
        await adapter.drain_bg_tasks()
        syncs = [f for f in ws.sent if f.get("type") == "chat.sync.enc"]
        assert len(syncs) == 1  # NUR das NEBENgerät B — A bekommt kein Echo
        assert syncs[0]["session_id"] == "b4sess-b"
        payload_b = peer_b.decrypt_frame(syncs[0])
        assert payload_b["text"] == "Grüße von A"
        assert payload_b["sync"] is True
        assert payload_b["sync_of"] == "origin-77"
        # JE Empfänger eigene Chiffre: die A-Session kann den B-Frame NICHT
        # öffnen (Cross-Session fail-closed).
        with pytest.raises(Exception):
            peer_a.decrypt_frame(syncs[0])

    def test_echo_dedup_same_origin_msg(self, tmp_path):
        return asyncio.run(self._test_echo_dedup_same_origin_msg(tmp_path))

    async def _test_echo_dedup_same_origin_msg(self, tmp_path) -> None:
        adapter, ws = make_adapter(
            {"FLIPSI_PHONE_PIN_FILE": str(tmp_path / "pins.json")})
        adapter.message_callback = None
        peer_a = await _establish_session(adapter, ws, "b4sess-a",
                                          "chat-aaaa-1111", DEVICE_A)
        await _establish_session(adapter, ws, "b4sess-b",
                                 "chat-bbbb-2222", DEVICE_B)
        _dispatch_user_post(adapter, peer_a, "chat-aaaa-1111", "hallo", "origin-88")
        await adapter.drain_bg_tasks()
        first = len([f for f in ws.sent if f.get("type") == "chat.sync.enc"])
        assert first == 1
        # Derselbe msg_id nochmal (Spool-Re-Flush-Pfad) → kein Re-Echo.
        _dispatch_user_post(adapter, peer_a, "chat-aaaa-1111", "hallo", "origin-88")
        await adapter.drain_bg_tasks()
        assert len([f for f in ws.sent if f.get("type") == "chat.sync.enc"]) == 1
        # Neuer msg_id → neues Echo.
        _dispatch_user_post(adapter, peer_a, "chat-aaaa-1111", "hallo 2", "origin-89")
        await adapter.drain_bg_tasks()
        assert len([f for f in ws.sent if f.get("type") == "chat.sync.enc"]) == 2

    def test_echo_skips_when_device_is_only_device(self, tmp_path):
        return asyncio.run(self._test_echo_skips_when_device_is_only_device(tmp_path))

    async def _test_echo_skips_when_device_is_only_device(self, tmp_path) -> None:
        adapter, ws = make_adapter(
            {"FLIPSI_PHONE_PIN_FILE": str(tmp_path / "pins.json")})
        adapter.message_callback = None
        peer_a = await _establish_session(adapter, ws, "b4sess-a",
                                          "chat-aaaa-1111", DEVICE_A)
        _dispatch_user_post(adapter, peer_a, "chat-aaaa-1111", "hallo", "origin-90")
        await adapter.drain_bg_tasks()
        assert len([f for f in ws.sent if f.get("type") == "chat.sync.enc"]) == 0


class TestB4Backfill:
    """backfill_request beim Adapter → Verlauf (injektierbar/prod), K=200,
    Batches à 64, Snapshot ids-only (Array-Form), Rate-Schild je Session."""

    @staticmethod
    def _history(count=250, with_status=True):
        rows = []
        for i in range(count):
            row = {"text": f"zeile-{i}", "from_agent": i % 2 == 1,
                   "msg_id": f"msg-{i}", "ts": 1700000000000 + i * 1000}
            if with_status:
                row["status"] = "read" if i % 2 == 1 else "delivered"
            rows.append(row)
        return rows

    def test_k_cap_batches_and_snapshot(self, tmp_path):
        return asyncio.run(self._test_k_cap_batches_and_snapshot(tmp_path))

    async def _test_k_cap_batches_and_snapshot(self, tmp_path) -> None:
        adapter, ws = make_adapter(
            {"FLIPSI_PHONE_PIN_FILE": str(tmp_path / "pins.json")})
        adapter.message_callback = None
        peer_b = await _establish_session(adapter, ws, "b4sess-b",
                                          "chat-bbbb-2222", DEVICE_B)
        adapter._backfill_history_fn = lambda gw: self._history(250)
        request = {"type": "chat.backfill_request", "v": 1,
                   "chat_id": "chat-bbbb-2222",
                   "gateway_chat": "chat-bbbb-2222",
                   "limit": 200, "device_id": DEVICE_B}
        adapter._dispatch_frame(request)
        await adapter.drain_bg_tasks()
        batches = [f for f in ws.sent if f.get("type") == "chat.backfill.enc"]
        assert batches, "keine Backfill-Batches"
        lens, entries, payloads = [], [], []
        for frame in batches:
            assert frame["session_id"] == "b4sess-b"
            payload = peer_b.decrypt_frame(frame)
            assert isinstance(payload["entries"], list)
            assert len(payload["entries"]) <= 64
            lens.append(len(payload["entries"]))
            entries.extend(payload["entries"])
            payloads.append(payload)
        assert lens == [64, 64, 64, 8]
        # K-Cap: die NEUESTEN 200 von 250 (msg-50..249), ASC-chronologisch.
        ids = [e["msg_id"] for e in entries]
        assert ids == [f"msg-{i}" for i in range(50, 250)]
        # KEIN Klartext auf dem Draht: der Frame trägt NUR ct (+ids-Meta).
        for frame in batches:
            assert "entries" not in frame and "text" not in frame
        # truncated-Marker am LETZTEN Batch (K-Deckel wirksam).
        assert payloads[-1]["truncated"] is True
        # Snapshot ids-only NACH den Batches, App-Parser-Form (Array).
        snaps = [f for f in ws.sent if f.get("type") == "chat.status_snapshot"]
        assert len(snaps) == 1
        assert snaps[0]["session_id"] == "b4sess-b"
        statuses = snaps[0]["statuses"]
        assert isinstance(statuses, list) and 0 < len(statuses) <= 200
        for pair in statuses:
            assert set(pair.keys()) == {"msg_id", "status"}
            assert pair["status"] in ("queued", "delivered", "read", "failed")
        assert {pair["msg_id"] for pair in statuses} <= set(ids)
        # Rate-Schild: zweiter Request sofort → KEINE weiteren Batches.
        adapter._dispatch_frame(request)
        await adapter.drain_bg_tasks()
        assert len([f for f in ws.sent
                    if f.get("type") == "chat.backfill.enc"]) == 4

    def test_no_source_honest_empty_batch(self, tmp_path):
        return asyncio.run(self._test_no_source_honest_empty_batch(tmp_path))

    async def _test_no_source_honest_empty_batch(self, tmp_path) -> None:
        adapter, ws = make_adapter(
            {"FLIPSI_PHONE_PIN_FILE": str(tmp_path / "pins.json")})
        adapter.message_callback = None
        peer_b = await _establish_session(adapter, ws, "b4sess-b",
                                          "chat-bbbb-2222", DEVICE_B)
        adapter._backfill_history_fn = lambda gw: None
        adapter._dispatch_frame({"type": "chat.backfill_request", "v": 1,
                                 "chat_id": "chat-bbbb-2222",
                                 "gateway_chat": "chat-bbbb-2222",
                                 "limit": 64, "device_id": DEVICE_B})
        await adapter.drain_bg_tasks()
        batches = [f for f in ws.sent if f.get("type") == "chat.backfill.enc"]
        assert len(batches) == 1
        payload = peer_b.decrypt_frame(batches[0])
        assert payload["entries"] == []
        assert payload["truncated"] is True  # ehrlich OHNE Verlauf zu behaupten
        assert len([f for f in ws.sent
                    if f.get("type") == "chat.status_snapshot"]) == 0

    def test_request_without_matching_session_fail_closed(self, tmp_path):
        return asyncio.run(self._test_request_without_matching_session_fail_closed(tmp_path))

    async def _test_request_without_matching_session_fail_closed(self, tmp_path) -> None:
        adapter, ws = make_adapter(
            {"FLIPSI_PHONE_PIN_FILE": str(tmp_path / "pins.json")})
        adapter.message_callback = None
        await _establish_session(adapter, ws, "b4sess-a",
                                 "chat-aaaa-1111", DEVICE_A)
        adapter._backfill_history_fn = lambda gw: []
        adapter._dispatch_frame({"type": "chat.backfill_request", "v": 1,
                                 "chat_id": "chat-qqqq-9999",
                                 "gateway_chat": "chat-qqqq-9999",
                                 "limit": 64, "device_id": DEVICE_B})
        await adapter.drain_bg_tasks()
        assert not [f for f in ws.sent if f.get("type") == "chat.backfill.enc"]

    def test_session_resolution_async(self, tmp_path):
        return asyncio.run(self._test_session_resolution_async(tmp_path))

    async def _test_session_resolution_async(self, tmp_path) -> None:
        adapter, ws = make_adapter(
            {"FLIPSI_PHONE_PIN_FILE": str(tmp_path / "pins.json")})
        adapter.message_callback = None
        await _establish_session(adapter, ws, "b4sess-a",
                                 "chat-aaaa-1111", DEVICE_A)
        await _establish_session(adapter, ws, "b4sess-b",
                                 "chat-bbbb-2222", DEVICE_B)
        sess_b = adapter._crypto_sessions[DEVICE_B]
        # device_id-Stempel des Relays gewinnt (je-GERÄT-Auflösung).
        assert adapter._session_for_backfill_request("chat-x", DEVICE_B) is sess_b
        # Fallback über die Wire-chat_id (Session-Kontext — Prod-Pfad setzt
        # sess.chat_id bei jedem Decrypt; hier simulierte Zuordnung).
        sess_b.chat_id = "chat-bbbb-2222"
        assert adapter._session_for_backfill_request("chat-bbbb-2222", "") is sess_b
        # Unbekanntes Gerät = None (fail-closed).
        assert adapter._session_for_backfill_request("chat-x", "geraet-x") is None

    def test_batches_unit_64_boundary_and_bytes_cap(self):
        entries = [{"text": f"x{i}", "from_agent": False, "msg_id": f"m{i}",
                    "ts": i} for i in range(129)]
        batches = _ph3_adapter.FlipsiBridgeChatAdapter._backfill_batches(entries)
        assert [len(b["entries"]) for b in batches] == [64, 64, 1]
        assert sum(len(b["entries"]) for b in batches) == 129
        huge = {"text": "H" * 200_000, "from_agent": False, "msg_id": "big",
                "ts": 1}
        b2 = _ph3_adapter.FlipsiBridgeChatAdapter._backfill_batches([huge])
        assert len(b2) == 1 and len(b2[0]["entries"]) == 1
        assert len(b2[0]["entries"][0]["text"]) < 200_000  # gekappt, Frame bleibt zustellbar

    def test_snapshot_statuses_ids_only_and_cap(self):
        rows = [{"msg_id": f"m{i}", "status": "read"} for i in range(600)]
        out = _ph3_adapter.FlipsiBridgeChatAdapter._backfill_snapshot_statuses(rows)
        assert len(out) == 500
        out2 = _ph3_adapter.FlipsiBridgeChatAdapter._backfill_snapshot_statuses(
            [{"msg_id": "a", "status": "fremd"}])
        assert out2 == []  # unbekannter Wert = kein erfundener Chip


class TestB4DeviceRevokedAdapter:
    """Revoke-Verband je Agent: Session-MAP-Eintrag + Pin-Eintrag kappen."""

    def test_session_and_pin_capped(self, tmp_path):
        return asyncio.run(self._test_session_and_pin_capped(tmp_path))

    async def _test_session_and_pin_capped(self, tmp_path) -> None:
        pin_file = tmp_path / "phone-pin.json"
        adapter, ws = make_adapter(
            {"FLIPSI_PHONE_PIN_FILE": str(pin_file)})
        adapter.message_callback = None
        peer_a = await _establish_session(adapter, ws, "b4sess-a",
                                          "chat-aaaa-1111", DEVICE_A)
        ik_a = adapter._crypto_sessions[DEVICE_A].device_ik
        # Erstes Gerät = TOFU-Pin (Adapter-Pfad, v2 Pins-MAP).
        pins = json.loads(pin_file.read_text(encoding="utf-8"))
        assert ik_a in (pins.get("pins") or {})
        await _establish_session(adapter, ws, "b4sess-b",
                                 "chat-bbbb-2222", DEVICE_B)
        sess_b = adapter._crypto_sessions[DEVICE_B]
        ik_b = sess_b.device_ik
        # Zweitgerät nach bestätigtem FP-Abgleich adoptiert (B3-Verband).
        adapter._write_phone_pin(ik_b, src="fp-confirm")
        pins = json.loads(pin_file.read_text(encoding="utf-8"))
        assert {ik_a, ik_b} <= set(pins.get("pins") or {})
        # Das letzte Hello = Primär-Session (B3-Alt-Verband).
        assert adapter._crypto_session is adapter._crypto_sessions[DEVICE_B]
        assert len(adapter._crypto_sessions) == 2
        adapter._dispatch_frame({"type": "device.revoked", "v": 1,
                                 "device_id": DEVICE_B})
        assert adapter._crypto_sessions.get(DEVICE_B) is None
        # B gekappt: Session weg, Pin-Eintrag weg, Primär-Verband entbunden.
        assert DEVICE_A in adapter._crypto_sessions
        pins = json.loads(pin_file.read_text(encoding="utf-8"))
        assert ik_b not in (pins.get("pins") or {})
        assert ik_a in (pins.get("pins") or {})
        assert adapter._crypto_session is None  # gekappte Primär-Session
        assert os.stat(pin_file).st_mode & 0o777 == 0o600

    def test_unknown_device_fail_soft(self, tmp_path):
        return asyncio.run(self._test_unknown_device_fail_soft(tmp_path))

    async def _test_unknown_device_fail_soft(self, tmp_path) -> None:
        adapter, ws = make_adapter(
            {"FLIPSI_PHONE_PIN_FILE": str(tmp_path / "pins.json")})
        adapter.message_callback = None
        await _establish_session(adapter, ws, "b4sess-a",
                                 "chat-aaaa-1111", DEVICE_A)
        adapter._dispatch_frame({"type": "device.revoked", "v": 1,
                                 "device_id": "geraet-unbekannt-1"})
        assert adapter._crypto_sessions.get(DEVICE_A) is not None

    def test_inbound_allowlist_b4(self):
        assert "chat.backfill_request" in _ph3_adapter._INBOUND_ENVELOPE_ALLOWLIST
        assert "device.revoked" in _ph3_adapter._INBOUND_ENVELOPE_ALLOWLIST


class TestB4ProdHistoryReader:
    """Prod-Wiring: hermes_state-Zug (read-only, flipsibridge-Quelle) — gemockt
    gegen die echte Mapping-Logik (role/content/timestamp → Verlauf-Einträge)."""

    def test_gateway_history_entries_maps_rows(self, monkeypatch):
        class FakeDB:
            def __init__(self, path, read_only=False):
                assert read_only is True

            def find_session_by_origin(self, *, platform, chat_id):
                assert platform == "flipsibridge"
                assert chat_id == "flipsi-phone-1a2b3c4d"
                return "sess-1"

            def get_messages(self, session_id, latest=True, limit=200):
                assert session_id == "sess-1" and latest is True
                return [
                    {"role": "user", "content": "Hallo Agent", "timestamp": 1700.5},
                    {"role": "assistant", "content": "Grüß dich!", "timestamp": 1701.0},
                    {"role": "session_meta", "content": None, "timestamp": 1701.2},
                    {"role": "tool", "content": "x", "timestamp": 1701.3},
                    {"role": "assistant", "content": "", "timestamp": 1701.4},
                ]

        fake = type(sys)("hermes_state")
        fake.SessionDB = FakeDB
        fake._default_db_path = lambda: "/tmp/b4-does-not-matter.db"
        monkeypatch.setitem(sys.modules, "hermes_state", fake)
        adapter, ws = make_adapter(
            {"FLIPSI_PHONE_PIN_FILE": "/tmp/b4pins.json"})
        rows = adapter._gateway_history_entries("flipsi-phone-1a2b3c4d", 200)
        assert rows is not None
        assert [r["text"] for r in rows] == ["Hallo Agent", "Grüß dich!"]
        assert rows[0]["from_agent"] is False
        assert rows[1]["from_agent"] is True
        assert abs(rows[0]["time"] - 1700.5) < 1e-9
        # Fail-soft: DB-Fehler → None (ehrlich OHNE Verlauf zu behaupten).
        class BoomDB(FakeDB):
            def get_messages(self, *a, **k):
                raise RuntimeError("db boom")

        fake.SessionDB = BoomDB
        adapter2, _ = make_adapter({"FLIPSI_PHONE_PIN_FILE": "/tmp/b4pins.json"})
        assert adapter2._gateway_history_entries("flipsi-phone-1a2b3c4d", 200) is None
