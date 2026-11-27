"""W21 — Anruf-Backend P1-P3 (Anruf-Konzept, §7 G11/G12).

Beweise (Frame-Verträge aus Anruf-Konzept gelesen, nicht geraten):
- Call-Signaling klein: call_invite.enc/call_end.enc (Agent→Phone) +
  call_ringing/accepted/declined/missed/ended.enc (Phone→Agent) — alles E2E
  je Geräte-Session, Inhalte NUR im ct, IDs klar (Parität chat.reply.enc).
- Fail-closed: nur BESTÄTIGTE Sessions; Phone-Signale nur vom SESSION-EIGENEN
  Gerät; Ziel-agent_id kommt IMMER aus dem Session-Broker (nie Frame-Inhalt).
- Rate-Buckets eigenständig ('callsign:*' je Agent je Richtung) — ein Call-
  Burst verdrängt den Chat-Bucket NICHT (FLIPSI-Frame-Guard-Verband).
- Adapter (P2): generisches Bridge-Tool flipsi_call_user/flipsi_call_end,
  Call-Zustandsmaschine, TTS-Chunk-Marker call_chunk_seq (§3.3), Chat-Fallback
  bei declined/missed (§3.5 — nichts geht verloren), Auto-End nach Durchsage.
- Capability „calls": approve-Gate Parität android_call.
- G12 (§7.2): Media-Kind "screengrab" über die bestehende Media-Pipeline
  (Meta-Lauf Relay+Adapter; Bild bleibt opak im ct).
"""

import asyncio
import hashlib
import json
import os
import sys
import threading
import time

import aiohttp
import pytest

from tools.android_relay import (
    start_relay,
    stop_relay,
    _validate_media_meta,
    _chat_rate_hits,
    _chat_rate_lock,
)

# Adapter-Import wie tests/test_backend_b4_multigeraete.py (Skeleton-Pfad).
_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "contrib", "hermes-chat-plugin")
if _PATH not in sys.path:
    sys.path.insert(0, _PATH)
sys.modules.setdefault("gateway", None)
import adapter as _ph3_adapter  # noqa: E402
from test_adapter import (  # noqa: E402
    make_adapter,
    _establish_session,
    _Msg,
    _phone_frame,
)

import tests.test_backend_b4_multigeraete as _b4  # noqa: E402
from tests.test_android_relay import _await_log_record  # noqa: E402

_recv_until = _b4._recv_until

AGENT_TOKEN = _b4.AGENT_TOKEN
CODE = _b4.CODE
IDENTITY = _b4.IDENTITY
PORT = 19973


@pytest.fixture(autouse=True)
def _w21_reset():
    """Relaiter Zustand je Test: Rate-Slots leeren + Relay stoppen."""
    with _chat_rate_lock:
        _chat_rate_hits.clear()
    yield
    with _chat_rate_lock:
        _chat_rate_hits.clear()
    stop_relay()


def _write_call_config(tmp_path, extra_agents=None) -> str:
    """Registry-Konfig mit Alpha (+ optional Beta) — Identity-Bindung wie B4."""
    config = {
        "ANDROID_AGENT_TOKEN_ALPHA": AGENT_TOKEN,
        "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
        "CHAT_RATE_PER_SECOND": 50,
        "ANDROID_AGENT_IDENTITY_ALPHA": _b4.IK_PUB,
        "ANDROID_AGENT_PREKEY_ALPHA": _b4.SPK_PUB,
        "ANDROID_AGENT_FINGERPRINT_ALPHA":
            _ph3_adapter.fingerprint_of_pub(bytes.fromhex(_b4.IK_PUB)),
    }
    if extra_agents:
        config.update(extra_agents)
    path = tmp_path / "relay-config.json"
    path.write_text(json.dumps(config), encoding="utf-8")
    return str(path)


def _start(tmp_path, monkeypatch, extra_agents=None, port: int = PORT) -> str:
    monkeypatch.setenv("ANDROID_RELAY_CONFIG", _write_call_config(tmp_path, extra_agents))
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_REGISTRY", _b4._write_registry(tmp_path))
    monkeypatch.delenv("ANDROID_RELAY_CALL_RATE", raising=False)
    start_relay(pairing_code=CODE, port=port)
    return f"http://127.0.0.1:{port}"


def _beta_token_config(tmp_path) -> dict:
    """Zweiter Agent (Beta) für die Namespace-Isolation des Invites — mit
    eigenem Identity-Registry-Eintrag (sonst verweigert der Relay das Hand-
    shake mit 403: je Agent MUST ein Key-Vertrag existieren)."""
    return {
        "ANDROID_AGENT_TOKEN_BETA": f"beta-token-{os.urandom(4).hex()}",
        "ANDROID_AGENT_DISPLAY_BETA": "Beta",
        "ANDROID_AGENT_IDENTITY_BETA": _b4.IK_PUB,
        "ANDROID_AGENT_PREKEY_BETA": _b4.SPK_PUB,
        "ANDROID_AGENT_FINGERPRINT_BETA":
            _ph3_adapter.fingerprint_of_pub(bytes.fromhex(_b4.IK_PUB)),
    }


# ─────────────────────────── Relay: Agent→Phone (P1) ──────────────────────────


class TestRelayCallInvite:
    def test_call_invite_roundtrip_opak(self, tmp_path, monkeypatch):
        return asyncio.run(self._roundtrip(tmp_path, monkeypatch))

    async def _roundtrip(self, tmp_path, monkeypatch) -> None:
        base = _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone_a, _phone_b, agent_ws = await _b4._connect_devices(http, base, PORT)
            peer_a, agent_session = await _b4._handshake(
                http, base, agent_ws, phone_a, _b4.DEVICE_A, "call-sess-a",
                "chat-aaaa-1111")
            assert await _enc_session_acked(http, base, "call-sess-a")
            invite = agent_session.encrypt_frame(
                "call_invite.enc", "chat-aaaa-1111",
                {"kind": "voice", "reason": "Wetter durchsagen"},
                clear={"call_id": "call-abc12345"})
            await agent_ws.send_json(invite)
            got = await _recv_until(phone_a, "call_invite.enc", timeout=6)
            assert got["ct"] == invite["ct"], "ct bleibt OPAK (Inhalt nur im ct)"
            assert got["call_id"] == "call-abc12345"
            assert got["agent_id"] == "alpha"
            assert got["session_id"] == "call-sess-a"
            assert got["hdr"] == invite["hdr"]
            # Klar-Felder sind ids-only: kein reason/kind außerhalb des ct.
            assert "reason" not in got and "kind" not in got
            await phone_a.close()

    def test_call_invite_fail_closed_without_session(self, tmp_path, monkeypatch, caplog):
        return asyncio.run(self._fail_closed(tmp_path, monkeypatch, caplog))

    async def _fail_closed(self, tmp_path, monkeypatch, caplog) -> None:
        base = _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone_a, _phone_b, agent_ws = await _b4._connect_devices(http, base, PORT)
            bogus = _b4._enc_frame("keine-session", "chat-aaaa-1111",
                                   "0" * 64, 0, "A" * 16, "AAA=",
                                   ftype="call_invite.enc")
            bogus["call_id"] = "call-nosess01"  # call_id-Form ok → Session-Gate greift
            await agent_ws.send_json(bogus)
            await asyncio.sleep(0.3)
            _await_log_record(
                caplog,
                "call_invite.enc ohne bestätigte E2E-Session — verworfen (fail-closed)")
            with pytest.raises(asyncio.TimeoutError):
                await phone_a.receive_json(timeout=0.8)
            await phone_a.close()

    def test_call_invite_foreign_chat_owner_rejected(self, tmp_path, monkeypatch, caplog):
        return asyncio.run(self._foreign_owner(tmp_path, monkeypatch, caplog))

    async def _foreign_owner(self, tmp_path, monkeypatch, caplog) -> None:
        """Namespace-Isolation: Beta darf NICHT in Alphas Konversation rufen.
        Beta braucht dazu SEINE Agent-WS (je Agent ein Token, je Agent eine
        Session) — genau die Realität, die das Gate aushält."""
        beta_cfg = _beta_token_config(tmp_path)
        beta_token = beta_cfg["ANDROID_AGENT_TOKEN_BETA"]
        base = _start(tmp_path, monkeypatch, extra_agents=beta_cfg)
        async with aiohttp.ClientSession() as http:
            phone_a, _phone_b, agent_ws = await _b4._connect_devices(http, base, PORT)
            peer_a, agent_session = await _b4._handshake(
                http, base, agent_ws, phone_a, _b4.DEVICE_A, "call-sess-a",
                "chat-aaaa-1111")
            # Beta: eigene Agent-WS (Beta-Token) + eigene Session am selben Gerät.
            beta_ws = await http.ws_connect(
                f"ws://127.0.0.1:{PORT}/agent_ws",
                headers={"Authorization": f"Bearer {beta_token}"})
            await _recv_until(phone_a, "relay.state", timeout=6)  # Presence-Push
            beta_hello = await http.post(
                f"{base}/crypto_hello",
                json={**_ph3_adapter.PhoneCryptoPeer(
                    "beta", _b4.IK_PUB, _b4.SPK_PUB).make_hello("call-sess-b"),
                    "device_id": _b4.DEVICE_A},
                headers={"Authorization": f"Bearer {CODE}"})
            assert beta_hello.status == 200
            assert (await beta_hello.json())["delivered"] is True, \
                "Beta-Hello zustellen über die Beta-Agent-WS"
            hello_frame = await beta_ws.receive_json(timeout=6)
            assert hello_frame["type"] == "crypto.hello"
            beta_session, beta_ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
                hello_frame, IDENTITY)
            await beta_ws.send_json(beta_ack)
            await _recv_until(phone_a, "crypto.hello_ack", timeout=6, parked=[])
            assert await _enc_session_acked(http, base, "call-sess-b")
            # Alpha CLAIMT chat-aaaa-1111 per chat.reply.enc.
            reply = peer_a.encrypt_frame(
                "chat.reply.enc", "chat-aaaa-1111",
                {"text": "Alphas Chat", "msg_id": "own-1"}, clear={"msg_id": "own-1"})
            await agent_ws.send_json(reply)
            parked: list = []
            await _recv_until(phone_a, "chat.event.enc", timeout=6, parked=parked)
            # Beta versucht call_invite in ALPHAS Chat → verworfen.
            beta_frame = beta_session.encrypt_frame(
                "call_invite.enc", "chat-aaaa-1111",
                {"kind": "voice", "reason": "fremd"},
                clear={"call_id": "call-fremde1"})
            await beta_ws.send_json(beta_frame)
            await asyncio.sleep(0.3)
            _await_log_record(caplog, "Namespace-Isolation")
            with pytest.raises(asyncio.TimeoutError):
                await phone_a.receive_json(timeout=0.8)
            assert parked == [] or all(
                f.get("type") != "call_invite.enc" for f in parked)
            await beta_ws.close()
            await phone_a.close()

    def test_call_invite_own_rate_bucket(self, tmp_path, monkeypatch, caplog):
        return asyncio.run(self._rate_bucket(tmp_path, monkeypatch, caplog))

    async def _rate_bucket(self, tmp_path, monkeypatch, caplog) -> None:
        import logging as _logging
        caplog.set_level(_logging.INFO, logger="android_relay")  # Relay-Thread-Logs
        base = _start(tmp_path, monkeypatch)
        monkeypatch.setenv("ANDROID_RELAY_CALL_RATE", "1")
        async with aiohttp.ClientSession() as http:
            phone_a, _phone_b, agent_ws = await _b4._connect_devices(http, base, PORT)
            peer_a, agent_session = await _b4._handshake(
                http, base, agent_ws, phone_a, _b4.DEVICE_A, "call-sess-a",
                "chat-aaaa-1111")
            for n in range(2):
                frame = agent_session.encrypt_frame(
                    "call_invite.enc", "chat-aaaa-1111",
                    {"kind": "voice", "reason": f"r{n}"},
                    clear={"call_id": f"call-rate000{n}"})
                # hdr.n frisch setzen (Replay-Fenster je Kette — gleiche Session).
                frame["hdr"]["n"] = n
                await agent_ws.send_json(frame)
            got = await _recv_until(phone_a, "call_invite.enc", timeout=6)
            assert got["call_id"] == "call-rate0000"
            # Der 2. Invite fiel in den EIGENEN Bucket (Log-Beweis); A bekam
            # genau 1 Call-Frame.
            _await_log_record(caplog, "Call-Rate-Limit (Eigener-Bucket)")
            # und verdrängt NICHT den Chat-Bucket (FLIPSI-Frame-Guard-Verband):
            reply = peer_a.encrypt_frame(
                "chat.reply.enc", "chat-aaaa-1111",
                {"text": "Antwort", "msg_id": "rep-1"}, clear={"msg_id": "rep-1"})
            await agent_ws.send_json(reply)
            got = await _recv_until(phone_a, "chat.event.enc", timeout=6)
            assert got["ct"] == reply["ct"]
            await phone_a.close()


# ─────────────────────────── Relay: Phone→Agent (P1) ──────────────────────────


class TestRelayPhoneCallSignal:
    def test_phone_call_signal_routes_opak_to_agent(self, tmp_path, monkeypatch):
        return asyncio.run(self._routes(tmp_path, monkeypatch))

    async def _routes(self, tmp_path, monkeypatch) -> None:
        base = _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone_a, _phone_b, agent_ws = await _b4._connect_devices(http, base, PORT)
            peer_a, _agent_session = await _b4._handshake(
                http, base, agent_ws, phone_a, _b4.DEVICE_A, "call-sess-a",
                "chat-aaaa-1111")
            for ftype in ("call_ringing.enc", "call_accepted.enc",
                          "call_declined.enc", "call_missed.enc",
                          "call_ended.enc"):
                frame = peer_a.encrypt_frame(
                    ftype, "chat-aaaa-1111", {}, clear={"call_id": "call-r11x2233"})
                await phone_a.send_json(frame)
                got = await _recv_until(agent_ws, ftype, timeout=6)
                assert got["ct"] == frame["ct"]
                assert got["call_id"] == "call-r11x2233"
                # Ziel-agent_id aus dem SESSION-BROKER, nicht aus dem Frame:
                assert got["agent_id"] == "alpha"
                await asyncio.sleep(0.02)
            await phone_a.close()

    def test_phone_call_signal_unknown_session_fail_closed(
            self, tmp_path, monkeypatch, caplog):
        return asyncio.run(self._unknown_session(tmp_path, monkeypatch, caplog))

    async def _unknown_session(self, tmp_path, monkeypatch, caplog) -> None:
        base = _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone_a, _phone_b, agent_ws = await _b4._connect_devices(http, base, PORT)
            peer_a, _agent_session = await _b4._handshake(
                http, base, agent_ws, phone_a, _b4.DEVICE_A, "call-sess-a",
                "chat-aaaa-1111")
            # Echte, handgeschakte Krypto-Hülle — aber die session_id behauptet
            # eine Session, die der Relay NICHT kennt (Phone-Lüge) → fail-closed.
            frame = peer_a.encrypt_frame(
                "call_accepted.enc", "chat-aaaa-1111", {},
                clear={"call_id": "call-nosess01"})
            frame["session_id"] = "gibts-nicht"
            await phone_a.send_json(frame)
            await asyncio.sleep(0.3)
            _await_log_record(
                caplog,
                "call_accepted.enc ohne bestätigte E2E-Session — verworfen (fail-closed)")
            await phone_a.close()

    def test_phone_call_signal_foreign_device_binding_fail_closed(
            self, tmp_path, monkeypatch, caplog):
        """Fremder Slot signalisiert die Session von Gerät A → fail-closed
        (Session-Broker-Bindung: Geräteeinheit + Ring gehören zusammen)."""
        return asyncio.run(self._foreign_device(tmp_path, monkeypatch, caplog))

    async def _foreign_device(self, tmp_path, monkeypatch, caplog) -> None:
        base = _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone_a, phone_b, agent_ws = await _b4._connect_devices(http, base, PORT)
            peer_a, _agent_session = await _b4._handshake(
                http, base, agent_ws, phone_a, _b4.DEVICE_A, "call-sess-a",
                "chat-aaaa-1111")
            frame = peer_a.encrypt_frame(
                "call_accepted.enc", "chat-aaaa-1111", {},
                clear={"call_id": "call-bnd0001"})
            # GeräT B sendet A's Session → Verwerfen (fail-closed).
            await phone_b.send_json(frame)
            await asyncio.sleep(0.3)
            _await_log_record(caplog, "signalisiert Fremd-Session")
            with pytest.raises(asyncio.TimeoutError):
                await agent_ws.receive_json(timeout=0.8)
            await phone_a.close()
            await phone_b.close()


# ─────────────────────────── G12 (§7.2): kind "screengrab" ────────────────────


class TestScreengrabKind:
    def test_relay_media_meta_accepts_screengrab(self):
        meta = {"media_id": "med-scrshot1", "seq": 0, "total": 1,
                "kind": "screengrab", "name": "shot.jpg", "size": 1024}
        assert _validate_media_meta(meta) is None
        # Unbekannter Kind-Wert bleibt fail-closed:
        bad = dict(meta, kind="gallery")
        assert _validate_media_meta(bad) is not None

    def test_relay_call_frames_not_media_and_kind_message_updated(self):
        from tools.android_relay import _MEDIA_KINDS
        assert "screengrab" in _MEDIA_KINDS

    def test_post_media_screengrab_roundtrip(self, tmp_path, monkeypatch):
        return asyncio.run(self._media_roundtrip(tmp_path, monkeypatch))

    async def _media_roundtrip(self, tmp_path, monkeypatch) -> None:
        base = _start(tmp_path, monkeypatch)
        async with aiohttp.ClientSession() as http:
            phone_a, _phone_b, agent_ws = await _b4._connect_devices(http, base, PORT)
            peer_a, _agent_session = await _b4._handshake(
                http, base, agent_ws, phone_a, _b4.DEVICE_A, "call-sess-a",
                "chat-aaaa-1111")
            payload = {"media_id": "med-scrshot1", "seq": 0, "total": 1,
                       "data": "aGVsbG8="}
            frame = peer_a.encrypt_frame("chat.media.enc", "chat-aaaa-1111", payload)
            body = {**frame, "agent_id": "alpha",
                    "media_id": "med-scrshot1", "seq": 0, "total": 1,
                    "kind": "screengrab", "name": "agent-shot.jpg",
                    "mime": "image/jpeg", "size": 6}
            resp = await http.post(
                f"{base}/media", json=body,
                headers={"Authorization": f"Bearer {CODE}"})
            assert resp.status == 200
            got = await _recv_until(agent_ws, "chat.media.enc", timeout=6)
            assert got["kind"] == "screengrab"  # G12-Meta läuft durch (opak ct)
            await phone_a.close()


# ─────────────────────────── Adapter (P2): Zustandsmaschine ───────────────────


def _crypto_adapter(tmp_path):
    return make_adapter({
        "FLIPSI_IDENTITY_FILE": str(tmp_path / "agent-identity.json"),
        "FLIPSI_CALL_RING_TIMEOUT": "10",
    })


async def _bind_chat(a, ws, peer, wire_chat="device-1"):
    """Session-Wire-chat_id binden (der Adapter lernt sie aus dem Echo)."""
    ws.inbox.append(_Msg("TEXT", json.dumps(_phone_frame(
        peer, "chat.send.enc", wire_chat,
        {"text": "hallo", "msg_id": "m-bind"}, msg_id="m-bind"))))
    await asyncio.sleep(0.05)
    await a.drain_bg_tasks()


class TestAdapterCallUser:
    @pytest.mark.asyncio
    async def test_accepted_happy_path_tts_chunks_auto_end(self, tmp_path):
        a, ws = _crypto_adapter(tmp_path)
        await a.connect()
        peer = await _establish_session(a, ws, session_id="call-sess-1")
        await _bind_chat(a, ws, peer)
        task = asyncio.create_task(
            a.call_user("Wetter durchsagen", say="Heute 15 Grad. Morgen Sonne."))
        await asyncio.sleep(0.05)
        invites = [f for f in ws.sent if f["type"] == "call_invite.enc"]
        assert len(invites) == 1
        invite = invites[0]
        assert _ph3_adapter._CALL_ID_RE.fullmatch(invite["call_id"])
        inner = peer.decrypt_frame(invite)
        assert inner == {"kind": "voice", "reason": "Wetter durchsagen"}, \
            "Inhalt ausschließlich im ct (§3.4)"
        cid = invite["call_id"]
        # Ring + Annahme (Phone→Agent je Session enc).
        ws.inbox.append(_Msg("TEXT", json.dumps(_phone_frame(
            peer, "call_ringing.enc", "device-1", {}, clear={"call_id": cid}))))
        await asyncio.sleep(0.05)
        ws.inbox.append(_Msg("TEXT", json.dumps(_phone_frame(
            peer, "call_accepted.enc", "device-1", {}, clear={"call_id": cid}))))
        result = await await_task(task)
        assert result["ok"] is True
        assert result["status"] == "ended", "Auto-End nach Durchsage (Cron-Rezept)"
        assert result["tts_chunks"] == 2
        assert result["chat_id"], "stabile GW-Konversation im Ergebnis"
        replies = [f for f in ws.sent if f["type"] == "chat.reply.enc"]
        spoken = [peer.decrypt_frame(f) for f in replies]
        assert [c["call_chunk_seq"] for c in spoken] == [0, 1], \
            "TTS-Chunks satzweise markiert (§3.3)"
        assert [c["text"] for c in spoken] == ["Heute 15 Grad.", "Morgen Sonne."], \
            "je Satz ein Chunk (satzweise raus vor dem Denken-Rest)"
        assert all(c["call_id"] == cid for c in spoken)
        for chunk_frame in replies:  # Klar-Feld trägt nur msg_id
            assert "text" not in chunk_frame and chunk_frame["msg_id"]
        ends = [f for f in ws.sent if f["type"] == "call_end.enc"]
        assert len(ends) == 1 and ends[0]["call_id"] == cid
        await a.disconnect()

    @pytest.mark.asyncio
    async def test_declined_falls_back_to_chat(self, tmp_path):
        """§3.5: abgewiesener Anruf — say geht als normale Nachricht raus,
        KEIN call_end, KEIN call_chunk_seq-Marker im Fallback."""
        a, ws = _crypto_adapter(tmp_path)
        await a.connect()
        peer = await _establish_session(a, ws, session_id="call-sess-1")
        await _bind_chat(a, ws, peer)
        task = asyncio.create_task(
            a.call_user("Kaffee holen", say="Ich bin gleich da."))
        await asyncio.sleep(0.05)
        invite = next(f for f in ws.sent if f["type"] == "call_invite.enc")
        ws.inbox.append(_Msg("TEXT", json.dumps(_phone_frame(
            peer, "call_declined.enc", "device-1", {},
            clear={"call_id": invite["call_id"]}))))
        result = await await_task(task)
        assert result["ok"] and result["status"] == "declined"
        assert result["fallback_sent"] is True
        reply = next(f for f in ws.sent if f["type"] == "chat.reply.enc")
        inner = peer.decrypt_frame(reply)
        assert inner["text"] == "Ich bin gleich da."
        assert "call_chunk_seq" not in inner, "Fallback ist KEIN TTS-Chunk"
        assert not [f for f in ws.sent if f["type"] == "call_end.enc"], \
            "abgewiesener Anruf wird nicht beendet"
        await a.disconnect()

    @pytest.mark.asyncio
    async def test_ring_timeout_missed_with_fallback(self, tmp_path):
        a, ws = _crypto_adapter(tmp_path)
        a.call_ring_timeout_s = 0.15  # direkt (Test-Fenster, Clamp nur via env)
        await a.connect()
        peer = await _establish_session(a, ws, session_id="call-sess-1")
        await _bind_chat(a, ws, peer)
        result = await a.call_user("Durchsage", say="Kein Antwort. Bis später.")
        assert result["ok"] and result["status"] == "missed"
        assert result["fallback_sent"] is True
        assert not [f for f in ws.sent if f["type"] == "call_end.enc"]
        await a.disconnect()

    @pytest.mark.asyncio
    async def test_fail_closed_without_known_chat(self, tmp_path):
        """KEIN invented Routing: Session ohne Wire-chat_id → kein Anruf."""
        a, ws = _crypto_adapter(tmp_path)
        await a.connect()
        await _establish_session(a, ws, session_id="call-sess-1")
        result = await a.call_user("Wetter", say="Hallo")
        assert result["ok"] is False and "fail-closed" in result["error"]
        assert not [f for f in ws.sent if f["type"] == "call_invite.enc"]
        await a.disconnect()

    @pytest.mark.asyncio
    async def test_fail_closed_without_connection(self, tmp_path):
        a, ws = _crypto_adapter(tmp_path)
        result = await a.call_user("Wetter")
        assert result["ok"] is False and "nicht verbunden" in result["error"]

    @pytest.mark.asyncio
    async def test_unknown_call_signal_fails_closed(self, tmp_path):
        """Fremder/später Ring-Signal für nie registrierte call_id → Verwurf
        + Diagnose, kein Zustands-Desaster."""
        a, ws = _crypto_adapter(tmp_path)
        await a.connect()
        peer = await _establish_session(a, ws, session_id="call-sess-1")
        before = len(a.rejected_frames)
        ws.inbox.append(_Msg("TEXT", json.dumps(_phone_frame(
            peer, "call_accepted.enc", "device-1", {},
            clear={"call_id": "call-fremd-1"}))))
        await asyncio.sleep(0.05)
        await a.drain_bg_tasks()
        assert len(a.rejected_frames) == before + 1
        assert any("kein lokaler Anruf-Auftrag" in (r.get("reason") or "")
                   for r in a.rejected_frames)
        await a.disconnect()

    @pytest.mark.asyncio
    async def test_call_map_cap_fifo(self, tmp_path):
        a, ws = _crypto_adapter(tmp_path)
        for i in range(20):
            a._call_register(f"call-cap{i:04d}", "gw", "wire")
        assert len(a._calls) == 16, "FIFO-Cap hält die Call-Map hart ≤ 16"
        assert "call-cap0000" not in a._calls and "call-cap0019" in a._calls
        await a.disconnect()

    @pytest.mark.asyncio
    async def test_tts_seq_monotonic_and_call_mode_marker(self, tmp_path):
        """call_chunk_seq bleibt monoton je Anruf — Durchsage + Call-Modus-
        Antworten teilen sich denselben Zähler; ohne offenen Anruf kein Marker."""
        a, ws = _crypto_adapter(tmp_path)
        a._call_register("call-seq00001", "flipsi-phone-x", "device-1")
        assert a._calls["call-seq00001"]["state"] == "ringing"
        assert a._call_reserve_tts_seq("call-seq00001") is None, \
            "TTS-Sequenz nur im angenommenen Call"
        a._calls["call-seq00001"]["state"] = "accepted"
        seqs = [a._call_reserve_tts_seq("call-seq00001") for _ in range(3)]
        assert seqs == [0, 1, 2]
        marker = a._call_marker_for("device-1")
        assert marker == {"call_id": "call-seq00001", "seq": 3}
        a._calls["call-seq00001"]["state"] = "ended"
        assert a._call_marker_for("device-1") is None
        await a.disconnect()


class TestAdapterCallEnd:
    @pytest.mark.asyncio
    async def test_end_after_accept_uses_bound_session(self, tmp_path):
        a, ws = _crypto_adapter(tmp_path)
        await a.connect()
        peer = await _establish_session(a, ws, session_id="call-sess-1")
        await _bind_chat(a, ws, peer)
        task = asyncio.create_task(a.call_user("Offenes Gespräch", say=None))
        await asyncio.sleep(0.05)
        invite = next(f for f in ws.sent if f["type"] == "call_invite.enc")
        ws.inbox.append(_Msg("TEXT", json.dumps(_phone_frame(
            peer, "call_accepted.enc", "device-1", {},
            clear={"call_id": invite["call_id"]}))))
        result = await await_task(task)
        assert result["status"] == "accepted" and result["tts_chunks"] == 0, \
            "say=None = offener Gesprächsanruf (Agent terminiert später)"
        end = await a.call_end(call_id=result["call_id"])
        assert end["ok"] and end["status"] == "ended"
        ends = [f for f in ws.sent if f["type"] == "call_end.enc"]
        assert len(ends) == 1 and ends[0]["call_id"] == invite["call_id"]
        await a.disconnect()

    @pytest.mark.asyncio
    async def test_send_marks_call_mode_replies_as_tts(self, tmp_path):
        """§3.3-Integrationslauf: offener angenommener Anruf → die NÄCHSTE
        Agent-Antwort über send() trägt call_chunk_seq + call_id im ct; nach
        dem Ende kein Marker mehr (Alt-Verhalten exakt)."""
        a, ws = _crypto_adapter(tmp_path)
        await a.connect()
        peer = await _establish_session(a, ws, session_id="call-sess-1")
        await _bind_chat(a, ws, peer)
        task = asyncio.create_task(a.call_user("Call-Modus", say=None))
        await asyncio.sleep(0.05)
        invite = next(f for f in ws.sent if f["type"] == "call_invite.enc")
        ws.inbox.append(_Msg("TEXT", json.dumps(_phone_frame(
            peer, "call_accepted.enc", "device-1", {},
            clear={"call_id": invite["call_id"]}))))
        result = await await_task(task)
        assert result["status"] == "accepted"
        reply = await a.send("device-1", "Im Call gesagt.")
        assert reply.success
        marked = next(f for f in ws.sent if f.get("msg_id") == reply.message_id)
        inner = peer.decrypt_frame(marked)
        assert inner["text"] == "Im Call gesagt."
        assert inner["call_chunk_seq"] == 0 and inner["call_id"] == invite["call_id"]
        # Ende → Marker-Verband zu.
        await a.call_end(call_id=result["call_id"])
        reply2 = await a.send("device-1", "Nach dem Call.")
        assert reply2.success and reply2.message_id
        inner2 = peer.decrypt_frame(next(
            f for f in ws.sent if f.get("msg_id") == reply2.message_id))
        assert "call_chunk_seq" not in inner2, "kein Marker nach Call-Ende"
        await a.disconnect()

    @pytest.mark.asyncio
    async def test_end_without_open_call_fails_closed(self, tmp_path):
        a, ws = _crypto_adapter(tmp_path)
        await a.connect()
        result = await a.call_end()
        assert result["ok"] is False and result["status"] == "none"
        await a.disconnect()

    @pytest.mark.asyncio
    async def test_end_while_ringing_fans_out(self, tmp_path):
        a, ws = _crypto_adapter(tmp_path)
        await a.connect()
        peer = await _establish_session(a, ws, session_id="call-sess-1")
        await _bind_chat(a, ws, peer)
        task = asyncio.create_task(a.call_user("Kurz-Ring", say=None))
        await asyncio.sleep(0.05)
        end = await a.call_end()
        assert end["ok"] and end["status"] == "ended"
        result = await await_task(task)
        assert result["status"] == "ended", "Ring-Abbruch wacht als 'ended' auf"
        assert not [f for f in ws.sent if f["type"] == "chat.reply.enc"], \
            "ohne say kein Fallback"
        await a.disconnect()


# ─────────────────────────── Tools (P2): generisches Bridge-Tool ──────────────


class TestCallToolGate:
    def test_capability_gate_approve_directive(self):
        gate = _ph3_adapter.flipsi_call_pre_tool_call
        for tool in ("flipsi_call_user", "flipsi_call_end"):
            directive = gate(tool)
            assert directive["action"] == "approve", "NIEMALS auto-approve"
            assert directive["rule_key"].startswith("flipsibridge:")
        assert gate("android_open_app") is None, "Fremd-Tools unberührt"

    def test_schemas_registered_shape(self):
        user = _ph3_adapter._FLIPSI_CALL_USER_SCHEMA
        end = _ph3_adapter._FLIPSI_CALL_END_SCHEMA
        assert user["name"] == "flipsi_call_user" and user["parameters"]["required"] == ["reason"]
        assert end["name"] == "flipsi_call_end" and end["parameters"]["required"] == []

    def test_register_wires_tools(self, tmp_path):
        registered = []

        class _Ctx:
            def register_platform(self, **kwargs):
                registered.append(("platform", kwargs.get("name")))

            def register_hook(self, name, callback):
                registered.append(("hook", name, callback))

            def register_tool(self, name, toolset, schema, handler, check_fn):
                registered.append(("tool", name, toolset, handler, check_fn))

        ctx = _Ctx()
        _ph3_adapter.register(ctx)
        assert ("platform", "flipsibridge") in registered
        tools = [e for e in registered if e[0] == "tool"]
        assert [t[1] for t in tools] == ["flipsi_call_user", "flipsi_call_end"]
        assert all(t[2] == "flipsibridge" for t in tools)
        assert all(callable(t[3]) and callable(t[4]) for t in tools)
        gate_pre = [e for e in registered if e[0] == "hook" and e[1] == "pre_tool_call"]
        assert any(entry[2] is _ph3_adapter.flipsi_call_pre_tool_call
                   for entry in gate_pre), \
            "Capability-Gate hängt als pre_tool_call-Hook"

    def test_dispatch_without_adapter_fails_closed(self):
        _ph3_adapter.STATUS_ADAPTERS.clear()  # Registry pinieren (Test-Isolation)
        try:
            out = json.loads(_ph3_adapter._flipsi_call_tool_dispatch(
                "call_user", {"reason": "x"}))
        finally:
            _ph3_adapter.STATUS_ADAPTERS.clear()
        assert out["ok"] is False and "kein verbundener" in out["error"]

    @pytest.mark.asyncio
    async def test_dispatch_threaded_end_to_end(self, tmp_path):
        """Sync-Brücke Gateway-Thread → Adapter-WS-Loop: flipsi_call_end beendet
        einen offenen Ring UND der wartende call_user bekommt das Ergebnis."""
        a, ws = _crypto_adapter(tmp_path)
        await a.connect()
        _ph3_adapter.STATUS_ADAPTERS.clear()   # Registry pinieren (Test-Isolation)
        _ph3_adapter.STATUS_ADAPTERS.add(a)
        a._status["loop"] = asyncio.get_running_loop()
        peer = await _establish_session(a, ws, session_id="call-sess-1")
        await _bind_chat(a, ws, peer)
        task = asyncio.create_task(a.call_user("Thread-Drill", say=None))
        await asyncio.sleep(0.05)
        results: dict = {}

        def _tool_thread():
            results["thread_ran"] = True
            results["tool_out"] = _ph3_adapter.flipsi_call_end()

        thread = threading.Thread(target=_tool_thread)
        thread.start()
        result = await await_task(task)
        thread.join(timeout=5)
        _ph3_adapter.STATUS_ADAPTERS.clear()
        assert not thread.is_alive() and results.get("thread_ran")
        tool_out = json.loads(results["tool_out"])
        assert tool_out["ok"] and tool_out["status"] == "ended"
        assert result["status"] == "ended" and not result["fallback_sent"]
        assert [f for f in ws.sent if f["type"] == "call_end.enc"]
        await a.disconnect()


# ─────────────────────────── Kleinteile ───────────────────────────────────────


async def await_task(task):
    """Task-Ergebnis holen (Aufruf-Schell in Tests)."""
    return await asyncio.wait_for(task, timeout=10)


async def _enc_session_acked(http, base, session_id) -> bool:
    """Session-Broker-Inventar (Debug-Anker für fail-closed-Beweise)."""
    import tools.android_relay as _relay
    state = _relay._relay_instance
    if state is None:
        return False
    async with state.broker_lock:
        _relay._prune_crypto_sessions(state)
        entry = state.crypto_sessions.get(session_id)
        return bool(entry and entry.get("acked"))
