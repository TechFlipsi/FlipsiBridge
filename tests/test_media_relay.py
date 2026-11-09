# MEDIA-T1-Tests: chunked Medien-/Datei-Versand im Chat über das Relay.
#
# Protokoll-Vertrag (MEDIA-T1, Sir-Feature „Medien im Chat“):
#   POST /media  {agent_id, chat_id, session_id, hdr, nonce, ct, ts,
#                 media_id, seq, total[, kind, name, mime, size bei seq=0]}
# - Fail-closed wie der /chat-enc-Zweig: KEINE bestätigte E2E-Session = 403,
#   Klartext-Media gibt es NICHT (ct fehlt → 400; text-Feld → 400).
# - Der Relay bleibt BLIND (ct opak, nur klare Metadaten).
# - Agent→Phone: chat.media.enc über die Agent-WS (opak weitergeleitet),
#   chat.media.chunk_ack als klarer Progress-Frame (light, 1:1).
# - J4: Chunking für ALLE Media — die 2-MB-WS-Grenze wird nie berührt.

import asyncio
import json
import os
import sys

import pytest

import time  # noqa: F401  (Deadline in _recv_until)

from tools.android_relay import (
    start_relay,
    stop_relay,
    _auth_blocked,
    _auth_failures,
    _auth_lock,
    _AUTH_MAX_ATTEMPTS,
    _chat_rate_hits,
    _chat_rate_lock,
)


@pytest.fixture(autouse=True)
def reset_media_relay():
    """Relay je Test sauber stoppen (Port frei + keine Session-Reste)."""
    yield
    stop_relay()


@pytest.fixture(autouse=True)
def reset_media_auth_state():
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()
    yield
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()


@pytest.fixture(autouse=True)
def reset_media_chat_rate_state():
    """Rate-Limiter je Test zurücksetzen (Fenster 'klebt' sonst über Tests)."""
    with _chat_rate_lock:
        _chat_rate_hits.clear()
    yield
    with _chat_rate_lock:
        _chat_rate_hits.clear()

# Endpunkt-Referenzen wie in test_android_relay.py (byte-exakte Vorlage
# für die Kotlin-Welle).
_PH3_SYS_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             "..", "contrib", "hermes-chat-plugin")
if _PH3_SYS_PATH not in sys.path:
    sys.path.insert(0, _PH3_SYS_PATH)
sys.modules.setdefault("gateway", None)
import adapter as _ph3_adapter  # noqa: E402  (contrib/hermes-chat-plugin/adapter.py)
from test_adapter import _Msg as _AdapterMsg  # noqa: E402  (Mock-WS-Frame)

import aiohttp  # noqa: E402


async def _recv_until(ws, frame_type, timeout=6.0, parked=None):
    """Frames lesen bis der gesuchte Typ kommt; andere in `parked` ablegen."""
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        assert remaining > 0, f"Frame {frame_type!r} kam nicht (parked: {parked})"
        frame = await ws.receive_json(timeout=max(0.1, remaining))
        if frame.get("type") == frame_type:
            return frame
        if parked is not None:
            parked.append(frame)


async def _drain_phone_state_frame(ws, expected=1):
    for _ in range(expected):
        frame = await ws.receive_json(timeout=6)
        assert frame["type"] == "relay.state"


class TestMediaBroker:
    """MEDIA-T1: /media (App→Agent) — E2E-only, chunked, fail-closed."""

    PORT = 19951
    CODE = "MEDCDE1"
    TOKEN = "media-agent-token"
    SESSION_ID = "media-e2e-session-0001"

    # Fixe Agent-Identity (gleiche Fixtures wie TestPh3CryptoE2E).
    IK_A_PRIV = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
    IK_A_PUB = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    SPK_A_PRIV = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
    SPK_A_PUB = "52a5524b0c32e8a47f239ab6ca8e42c3b9316a54cae4854ecdb5889311cc9a7e"

    @property
    def identity(self):
        return {"identity_priv": self.IK_A_PRIV, "identity_pub": self.IK_A_PUB,
                "prekey_priv": self.SPK_A_PRIV, "prekey_pub": self.SPK_A_PUB}

    def _write_config(self, tmp_path, with_keys=True):
        cfg = tmp_path / "relay-config.json"
        data = {
            "ANDROID_AGENT_TOKEN_ALPHA": self.TOKEN,
            "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
            "CHAT_RATE_PER_SECOND": 50,
        }
        if with_keys:
            data.update({
                "ANDROID_AGENT_IDENTITY_ALPHA": self.IK_A_PUB,
                "ANDROID_AGENT_PREKEY_ALPHA": self.SPK_A_PUB,
                "ANDROID_AGENT_FINGERPRINT_ALPHA":
                    _ph3_adapter.fingerprint_of_pub(bytes.fromhex(self.IK_A_PUB)),
            })
        cfg.write_text(json.dumps(data), encoding="utf-8")
        return str(cfg)

    # ------------------------------------------------------------ Helfer

    def _start(self, tmp_path, monkeypatch, with_keys=True, port=None):
        monkeypatch.setenv("ANDROID_RELAY_CONFIG",
                           self._write_config(tmp_path, with_keys=with_keys))
        start_relay(pairing_code=self.CODE, port=port or self.PORT)
        return f"http://127.0.0.1:{port or self.PORT}"

    async def _handshake(self, session, base, agent_ws, phone_ws, session_id):
        """Voller E2E-Handshake: phone hello → agent ack → ack ans Phone."""
        got = await session.get(f"{base}/key_get", params={"agent_id": "alpha"},
                                headers={"Authorization": f"Bearer {self.CODE}"})
        assert got.status == 200
        keys = await got.json()
        peer = _ph3_adapter.PhoneCryptoPeer("alpha", keys["ik_pub"], keys["spk_pub"])
        hello = peer.make_hello(session_id)
        resp = await session.post(f"{base}/crypto_hello", json=hello,
                                  headers={"Authorization": f"Bearer {self.CODE}"})
        assert resp.status == 200
        hello_frame = await agent_ws.receive_json(timeout=6)
        assert hello_frame["type"] == "crypto.hello"
        agent_session, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
            hello_frame, self.identity)
        await agent_ws.send_json(ack)
        ack_frame = await _recv_until(phone_ws, "crypto.hello_ack", timeout=6)
        peer.consume_ack(ack_frame)
        return peer, agent_session

    @staticmethod
    def _media_chunks(raw: bytes, chunk_bytes: int = 192 * 1024):
        """Roh-Daten in (seq, total, b64)-Chunks teilen + Gesamt-SHA."""
        import base64
        import hashlib
        chunks = [raw[i:i + chunk_bytes] for i in range(0, len(raw), chunk_bytes)]
        sha = hashlib.sha256(raw).hexdigest()
        return chunks, sha

    # --------------------------------------------------------- Kern-Drill

    def test_media_full_drill_enc_chunks_relay_stays_blind(self, tmp_path,
                                                           monkeypatch, caplog):
        """MED-Drill: 2-Chunk-Foto über /media — Agent empfängt beide Chunks
        mit UNVERÄNDERTEM ct + klaren Metadaten, Reassembly ergibt die
        Original-Bytes; der Relay sieht NIE Klartext (Log-Audit)."""
        import base64
        import hashlib

        base = self._start(tmp_path, monkeypatch, port=self.PORT)
        session_id = self.SESSION_ID

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                await _drain_phone_state_frame(phone)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)  # Presence

                peer, agent_session = await self._handshake(
                    session, base, agent, phone, session_id)

                # Foto (2 Chunks à max 192 KiB, > 1 Chunk bewusst).
                raw = os.urandom(250_000)
                chunks, sha = self._media_chunks(raw)
                total = len(chunks)
                media_id = "med-d3adb33f-cafe"
                chat_id = "chat-media-0001"
                name = "urlaub.jpg"
                mime = "image/jpeg"

                for seq, chunk in enumerate(chunks):
                    payload = {"media_id": media_id, "seq": seq, "total": total,
                               "data": base64.b64encode(chunk).decode("ascii")}
                    if seq == 0:
                        payload["sha256"] = sha
                    frame = peer.encrypt_frame(
                        "chat.media.enc", chat_id, payload,
                        clear={"media_id": media_id, "seq": seq, "total": total})
                    body = {"agent_id": "alpha", "chat_id": chat_id,
                            "session_id": frame["session_id"],
                            "ts": frame["ts"], "hdr": frame["hdr"],
                            "nonce": frame["nonce"], "ct": frame["ct"],
                            "media_id": media_id, "seq": seq, "total": total}
                    if seq == 0:
                        body.update({"kind": "image", "name": name,
                                     "mime": mime, "size": len(raw)})
                    resp = await session.post(
                        f"{base}/media", json=body,
                        headers={"Authorization": f"Bearer {self.CODE}"})
                    assert resp.status == 200, await resp.text()
                    r = await resp.json()
                    assert r["delivered"] is True
                    assert r["media_id"] == media_id

                # Agent: beide Chunks opak + klare Metadaten, Reassembly.
                sha_claimed = None
                reassembled = b""
                for expected_seq in range(total):
                    f = await _recv_until(agent, "chat.media.enc", timeout=6)
                    assert f["media_id"] == media_id
                    assert f["seq"] == expected_seq
                    assert f["total"] == total
                    if expected_seq == 0:
                        assert f["kind"] == "image"
                        assert f["name"] == name
                        assert f["mime"] == mime
                        assert f["size"] == len(raw)
                    decoded = agent_session.decrypt_frame(f)
                    reassembled += base64.b64decode(decoded["data"])
                    if expected_seq == 0:
                        sha_claimed = decoded.get("sha256")
                assert sha_claimed == hashlib.sha256(raw).hexdigest()
                assert reassembled == raw, "Reassembly ergibt nicht die Original-Bytes"

                # Relay-Blindheit: Roh-Daten (b64 + raw) nie im Log.
                dumped = "\n".join(r.getMessage() for r in caplog.records)
                assert base64.b64encode(raw[:192 * 1024]).decode()[:64] not in dumped, \
                    "Chunk-Klartext im Relay-Log!"
                assert raw.hex()[:64] not in dumped

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    # -------------------------------------------------------- Fail-closed

    def test_media_without_ct_rejected_400(self, tmp_path, monkeypatch):
        """Fail-closed: Klartext-Media existiert nicht — ohne ct → 400, mit
        text-Feld → 400 (Klartext-Fallback FORBIDDEN)."""
        base = self._start(tmp_path, monkeypatch, port=self.PORT + 1)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                for body in (
                    {"agent_id": "alpha", "chat_id": "chat-media-0002",
                     "media_id": "med-clear-0001", "seq": 0, "total": 1,
                     "kind": "file", "name": "a.txt", "size": 10},
                    {"agent_id": "alpha", "chat_id": "chat-media-0002",
                     "text": "klartext", "ct": "AAAA", "nonce": "AAAA",
                     "session_id": "media-e2e-session-0001",
                     "hdr": {"rk": "aa" * 32, "n": 0, "pn": 0},
                     "media_id": "med-clear-0001", "seq": 0, "total": 1},
                ):
                    resp = await session.post(
                        f"{base}/media", json=body,
                        headers={"Authorization": f"Bearer {self.CODE}"})
                    assert resp.status in (400, 401, 403), await resp.text()
                    if resp.status == 400:
                        err = (await resp.json())["error"]
                        assert "cleartext" in err or "text not allowed" in err

        asyncio.run(scenario())

    def test_media_without_session_403(self, tmp_path, monkeypatch):
        """Fail-closed: enc-Chunk OHNE bestätigte E2E-Session → 403."""
        base = self._start(tmp_path, monkeypatch, port=self.PORT + 2)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                resp = await session.post(
                    f"{base}/media",
                    json={"agent_id": "alpha", "chat_id": "chat-media-0003",
                          "session_id": "media-e2e-session-0001",
                          "ts": 1, "hdr": {"rk": "aa" * 32, "n": 0, "pn": 0},
                          "nonce": _ph3_adapter._b64e(b"\x00" * 12),
                          "ct": _ph3_adapter._b64e(b"\x01" * 32),
                          "media_id": "med-nosess-0001", "seq": 0, "total": 1,
                          "kind": "file", "name": "a.txt", "size": 10},
                    headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp.status == 403

        asyncio.run(scenario())

    def test_media_replay_rejected_429(self, tmp_path, monkeypatch):
        """Replay: identischer Chunk-Zweitversuch (gleiche hdr.n/rk) → 429."""
        base = self._start(tmp_path, monkeypatch, port=self.PORT + 3)
        session_id = self.SESSION_ID + "-replay"

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 3}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                await _drain_phone_state_frame(phone)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 3}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)
                peer, _ = await self._handshake(session, base, agent, phone,
                                               session_id)
                frame = peer.encrypt_frame(
                    "chat.media.enc", "chat-media-0004",
                    {"media_id": "med-replay-0001", "seq": 0, "total": 1,
                     "data": _ph3_adapter._b64e(b"x" * 16)},
                    clear={"media_id": "med-replay-0001", "seq": 0, "total": 1})
                body = {"agent_id": "alpha", "chat_id": "chat-media-0004",
                        "session_id": frame["session_id"], "ts": frame["ts"],
                        "hdr": frame["hdr"], "nonce": frame["nonce"],
                        "ct": frame["ct"], "media_id": "med-replay-0001",
                        "seq": 0, "total": 1, "kind": "file",
                        "name": "a.txt", "size": 16}
                resp1 = await session.post(
                    f"{base}/media", json=body,
                    headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp1.status == 200
                resp2 = await session.post(
                    f"{base}/media", json=body,
                    headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp2.status == 429, "Replay-Duplikat durchgelassen!"
                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_media_size_cap_rejected(self, tmp_path, monkeypatch):
        """Größen-Deckel: seq=0 mit size über _media_max_bytes → 400."""
        base = self._start(tmp_path, monkeypatch, port=self.PORT + 4)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                resp = await session.post(
                    f"{base}/media",
                    json={"agent_id": "alpha", "chat_id": "chat-media-0005",
                          "session_id": "media-e2e-session-0001",
                          "ts": 1, "hdr": {"rk": "aa" * 32, "n": 0, "pn": 0},
                          "nonce": _ph3_adapter._b64e(b"\x00" * 12),
                          "ct": _ph3_adapter._b64e(b"\x01" * 32),
                          "media_id": "med-toolarge-001", "seq": 0, "total": 1,
                          "kind": "video", "name": "clip.mp4",
                          "size": 50 * 1024 * 1024 + 1},
                    headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp.status == 400
                err = (await resp.json())["error"]
                assert "size" in err

        asyncio.run(scenario())

    def test_media_spooled_until_agent_connects(self, tmp_path, monkeypatch):
        """Offline-Agent: Chunk-POST → 200/queued; Agent-Connect → Flush in
        Chunk-Reihenfolge (seq 0 vor seq 1) — J4-Reihenfolge-Vertrag."""
        import base64 as _b64

        base = self._start(tmp_path, monkeypatch, port=self.PORT + 5)
        session_id = self.SESSION_ID + "-spool"

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 5}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                await _drain_phone_state_frame(phone)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 5}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)
                peer, _ = await self._handshake(session, base, agent, phone,
                                                session_id)
                await agent.close()

                # Agent offline → 2 Chunks in den Spool.
                for seq, blob in enumerate((b"a" * 10, b"b" * 10)):
                    frame = peer.encrypt_frame(
                        "chat.media.enc", "chat-media-0006",
                        {"media_id": "med-spool-0001", "seq": seq, "total": 2,
                         "data": _b64.b64encode(blob).decode("ascii")},
                        clear={"media_id": "med-spool-0001", "seq": seq,
                               "total": 2})
                    resp = await session.post(
                        f"{base}/media",
                        json={"agent_id": "alpha", "chat_id": "chat-media-0006",
                              "session_id": frame["session_id"],
                              "ts": frame["ts"], "hdr": frame["hdr"],
                              "nonce": frame["nonce"], "ct": frame["ct"],
                              "media_id": "med-spool-0001", "seq": seq,
                              "total": 2, "kind": "file", "name": "spool.bin",
                              "size": 20},
                        headers={"Authorization": f"Bearer {self.CODE}"})
                    assert resp.status == 200
                    assert (await resp.json())["queued"] is True

                # Agent verbindet → Spool-Flush in seq-Reihenfolge.
                agent2 = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 5}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                seqs = []
                for _ in range(2):
                    f = await _recv_until(agent2, "chat.media.enc", timeout=6)
                    seqs.append(f["seq"])
                assert seqs == [0, 1], f"Chunk-Reihenfolge im Flush kaputt: {seqs}"
                await agent2.close()
                await phone.close()

        asyncio.run(scenario())

    def test_media_wrong_token_401(self, tmp_path, monkeypatch):
        """Auth: falscher Pairing-Code → 401."""
        base = self._start(tmp_path, monkeypatch, port=self.PORT + 6)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                resp = await session.post(
                    f"{base}/media", json={"agent_id": "alpha"},
                    headers={"Authorization": "Bearer WRONGCODE"})
                assert resp.status == 401

        asyncio.run(scenario())

    # ------------------------------------------------ Agent→Phone-Richtung

    def test_agent_media_enc_forwarded_to_phone(self, tmp_path, monkeypatch):
        """MEDIA Agent→Phone: chat.media.enc über die Agent-WS wird opak an
        die Phone-WS durchgereicht (ct unverändert, klare Extras erhalten)."""
        import base64 as _b64

        base = self._start(tmp_path, monkeypatch, port=self.PORT + 7)
        session_id = self.SESSION_ID + "-a2p"

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 7}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                await _drain_phone_state_frame(phone)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 7}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6)
                peer, agent_session = await self._handshake(
                    session, base, agent, phone, session_id)

                blob = b"agent-image-payload" * 1000
                frame = agent_session.encrypt_frame(
                    "chat.media.enc", "chat-media-0007",
                    {"media_id": "med-a2p-0000001", "seq": 0, "total": 1,
                     "data": _b64.b64encode(blob).decode("ascii")},
                    clear={"media_id": "med-a2p-0000001", "seq": 0, "total": 1,
                           "kind": "image", "name": "agent.jpg",
                           "mime": "image/jpeg", "size": len(blob)})
                await agent.send_json(frame)

                got = await _recv_until(phone, "chat.media.enc", timeout=6)
                assert got["ct"] == frame["ct"]
                assert got["kind"] == "image"
                assert got["name"] == "agent.jpg"
                assert got["size"] == len(blob)
                decoded = peer.decrypt_frame(got)
                assert _b64.b64decode(decoded["data"]) == blob

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_chunk_ack_forwarded_to_phone(self, tmp_path, monkeypatch):
        """chat.media.chunk_ack (klar, light) wird 1:1 ans Phone gereicht —
        media_id/seq/ok bleiben erhalten (Progress-Vertrag der App-UI)."""
        base = self._start(tmp_path, monkeypatch, port=self.PORT + 8)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 8}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                await _drain_phone_state_frame(phone)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 8}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})

                await agent.send_json({
                    "type": "chat.media.chunk_ack", "v": 1,
                    "chat_id": "chat-media-0008", "agent_id": "alpha",
                    "media_id": "med-ack-00000001", "seq": 3, "ok": True})

                got = await _recv_until(phone, "chat.media.chunk_ack", timeout=6)
                assert got["media_id"] == "med-ack-00000001"
                assert got["seq"] == 3
                assert got["ok"] is True
                assert got["agent_id"] == "alpha"

                # Ungültiger ack (kein media_id) → verworfen, kein Phone-Frame.
                await agent.send_json({
                    "type": "chat.media.chunk_ack", "v": 1,
                    "chat_id": "chat-media-0008", "agent_id": "alpha",
                    "seq": 4})
                with pytest.raises(Exception):
                    await _recv_until(phone, "chat.media.chunk_ack", timeout=1.5)

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_cleartext_media_frame_discarded(self, tmp_path, monkeypatch, caplog):
        """Klartext-frame 'chat.media' (ohne .enc) steht NICHT in der Agent-
        Allowlist → verworfen; der Phone-Broker bekommt nichts."""
        base = self._start(tmp_path, monkeypatch, port=self.PORT + 9)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 9}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                await _drain_phone_state_frame(phone)
                agent = await session.ws_connect(
                    f"ws://127.0.0.1:{self.PORT + 9}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})

                await agent.send_json({
                    "type": "chat.media", "v": 1,
                    "chat_id": "chat-media-0009", "agent_id": "alpha",
                    "media_id": "med-clear-000009", "seq": 0, "total": 1,
                    "data": "aGVsbG8="})
                with pytest.raises(Exception):
                    await _recv_until(phone, "chat.media", timeout=1.5)

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

        dumped = "\n".join(r.getMessage() for r in caplog.records)
        assert "chat.media" in dumped, "Verwurf des Klartext-Media-Frames muss geloggt werden"


# ── W4-B3: Read-Ack-P3-Fix (pure-Medien-Lücke) ────────────────────────────────
# Vorher: /media erzeugte KEIN msg_id und KEINEN chat_status → der Adapter
# sendete nach Medien-Empfang chat.read.enc mit LEERER msg_id → der Relay
# verworfen fail-safe → 'Gelesen'-Chip blieb bei reinen Medien aus.
# Jetzt: Broker-msg_id je Chunk-POST (Parität /chat enc-Zweig) + queued-Status
# + msg_id in Response/Frame; Adapter führt sie im Deliver-Envelope nach — der
# EXISTIERENDE Read-Kettkomplex markiert 'read'. Frame-Typen unverändert.


class TestMediaReadAckMsgId:
    """Relay-Verband eines Media-Chunks: msg_id + Status-Kette + Read-Mark."""

    PORT = 19970
    CODE = "MEDACK01"
    TOKEN = "media-agent-token"
    SESSION_ID = "media-e2e-session-0001"
    CHAT = "chat-media-readack-1"
    MEDIA = "med-readack-000001"

    # Agent-Identity-Fixtures (1:1 TestMediaBroker).
    IK_A_PRIV = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
    IK_A_PUB = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    SPK_A_PRIV = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
    SPK_A_PUB = "52a5524b0c32e8a47f239ab6ca8e42c3b9316a54cae4854ecdb5889311cc9a7e"

    @property
    def identity(self):
        return {"identity_priv": self.IK_A_PRIV, "identity_pub": self.IK_A_PUB,
                "prekey_priv": self.SPK_A_PRIV, "prekey_pub": self.SPK_A_PUB}

    def _start(self, tmp_path, monkeypatch, port):
        cfg = tmp_path / "relay-config.json"
        cfg.write_text(json.dumps({
            "ANDROID_AGENT_TOKEN_ALPHA": self.TOKEN,
            "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
            "ANDROID_AGENT_IDENTITY_ALPHA": self.IK_A_PUB,
            "ANDROID_AGENT_PREKEY_ALPHA": self.SPK_A_PUB,
            "CHAT_RATE_PER_SECOND": 50,
        }), encoding="utf-8")
        monkeypatch.setenv("ANDROID_RELAY_CONFIG", str(cfg))
        start_relay(pairing_code=self.CODE, port=port)
        return f"http://127.0.0.1:{port}"

    async def _handshake(self, http, base, agent_ws, phone_ws, session_id):
        import tools.android_relay as _relay  # noqa: F401  (für _relay_instance)
        got = await http.get(f"{base}/key_get", params={"agent_id": "alpha"},
                             headers={"Authorization": f"Bearer {self.CODE}"})
        assert got.status == 200
        keys = await got.json()
        peer = _ph3_adapter.PhoneCryptoPeer("alpha", keys["ik_pub"], keys["spk_pub"])
        hello = peer.make_hello(session_id)
        resp = await http.post(f"{base}/crypto_hello", json=hello,
                               headers={"Authorization": f"Bearer {self.CODE}"})
        assert resp.status == 200
        hello_frame = await agent_ws.receive_json(timeout=6)
        assert hello_frame["type"] == "crypto.hello"
        agent_session, ack = _ph3_adapter.AgentCryptoSession.respond_to_hello(
            hello_frame, self.identity)
        await agent_ws.send_json(ack)
        ack_frame = await _recv_until(phone_ws, "crypto.hello_ack", timeout=6)
        peer.consume_ack(ack_frame)
        return peer, agent_session

    def _post_media_body(self, peer, chat_id, media_id, data_b64):
        """/media-Body (1 Chunk, sha256 im Chunk-0-Payload)."""
        import base64
        import hashlib
        payload = {"media_id": media_id, "seq": 0, "total": 1,
                   "data": base64.b64encode(data_b64).decode("ascii"),
                   "sha256": hashlib.sha256(data_b64).hexdigest()}
        frame = peer.encrypt_frame("chat.media.enc", chat_id, payload,
                                   clear={"media_id": media_id, "seq": 0, "total": 1,
                                          "kind": "image", "name": "foto.jpg",
                                          "mime": "image/jpeg", "size": len(data_b64)})
        return {"agent_id": "alpha", "chat_id": chat_id,
                "session_id": frame["session_id"], "ts": frame["ts"],
                "hdr": frame["hdr"], "nonce": frame["nonce"], "ct": frame["ct"],
                "media_id": media_id, "seq": 0, "total": 1, "kind": "image",
                "name": "foto.jpg", "mime": "image/jpeg", "size": len(data_b64)}

    def test_media_broker_msg_id_full_status_chain(self, tmp_path, monkeypatch):
        """/media 200-Body enthält msg_id; der geforwardete Agent-Frame trägt
        sie; chat_status zeigt queued→delivered; chat.read.enc mit GENAU dieser
        msg_id → chat_status 'read' + chat.status-Frame ans Phone."""
        import tools.android_relay as _relay_mod
        base = self._start(tmp_path, monkeypatch, self.PORT)
        session_id = self.SESSION_ID + "-readack"

        async def scenario():
            async with aiohttp.ClientSession() as http:
                phone = await http.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                parked: list[dict] = []
                await _recv_until(phone, "relay.state", timeout=6, parked=parked)
                agent = await http.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/agent_ws",
                    headers={"Authorization": f"Bearer {self.TOKEN}"})
                await _recv_until(phone, "relay.state", timeout=6, parked=parked)
                peer, agent_session = await self._handshake(
                    http, base, agent, phone, session_id)

                body = self._post_media_body(peer, self.CHAT, self.MEDIA,
                                             b"pure-media-chunk-0001")
                resp = await http.post(f"{base}/media", json=body,
                                       headers={"Authorization": f"Bearer {self.CODE}"})
                assert resp.status == 200, await resp.text()
                ack = await resp.json()
                msg_id = ack.get("msg_id")
                assert isinstance(msg_id, str) and len(msg_id) >= 8, \
                    f"/media-Response muss die Broker-msg_id tragen: {ack}"
                assert ack["media_id"] == self.MEDIA and ack["delivered"] is True

                # Agent-Frame trägt dieselbe msg_id (klare Metadaten).
                frame = await _recv_until(agent, "chat.media.enc", timeout=6)
                assert frame["msg_id"] == msg_id, "Broker-msg_id im Forward fehlt"

                # chat_status-Entry existiert und steigt auf delivered auf.
                state = _relay_mod._relay_instance
                assert state is not None
                entry = state.chat_status.get(msg_id)
                assert entry is not None, "chat_status-Entry (queued) fehlt"
                assert entry["chat_id"] == self.CHAT
                assert entry["agent_id"] == "alpha"
                assert entry["status"] in ("queued", "delivered")
                # Status-Kette ans Phone: queued (Broker-Annahme) → dann
                # delivered (Agent-WS-Send erfolgreich, W4-B3-Erbe).
                queued_status = await _recv_until(phone, "chat.status", timeout=6,
                                                  parked=parked)
                assert queued_status["msg_id"] == msg_id
                assert queued_status["status"] == "queued"
                assert queued_status["chat_id"] == self.CHAT
                delivered_status = await _recv_until(phone, "chat.status", timeout=6,
                                                     parked=parked)
                assert delivered_status["msg_id"] == msg_id
                assert delivered_status["status"] == "delivered"

                # Adapter-Verband simuliert: chat.read.enc mit GENAU dieser
                # msg_id (Adapter führt sie nach dem Deliver-Envelope nach).
                read = agent_session.encrypt_frame(
                    "chat.read.enc", self.CHAT, {"msg_id": msg_id},
                    clear={"msg_id": msg_id})
                await agent.send_json(read)
                read_ack = await _recv_until(phone, "chat.status", timeout=6,
                                             parked=parked)
                assert read_ack["status"] == "read"
                assert read_ack["msg_id"] == msg_id, \
                    f"'Gelesen' muss GENAU die Chunk-msg_id stempeln: {read_ack}"
                mirror = await _recv_until(phone, "chat.read.enc", timeout=6,
                                           parked=None)
                assert mirror["msg_id"] == msg_id, "enc-Spiegel trägt die msg_id"
                assert state.chat_status[msg_id]["status"] == "read"

                # Regression (W4-B3-Forderung): Wiederholter read (NEUE Ratchet-
                # Frames, gleiche msg_id) → kein Status-Duplikat/Downgrade
                # (Dedup _set_chat_status bleibt); der Mirror kommt erneut,
                # aber kein zweiter chat.status.
                await agent.send_json(agent_session.encrypt_frame(
                    "chat.read.enc", self.CHAT, {"msg_id": msg_id},
                    clear={"msg_id": msg_id}))
                dup_mirror = await _recv_until(phone, "chat.read.enc", timeout=6)
                assert dup_mirror["msg_id"] == msg_id
                with pytest.raises(Exception):
                    await _recv_until(phone, "chat.status", timeout=0.8)
                assert state.chat_status[msg_id]["status"] == "read"

                await agent.close()
                await phone.close()

        asyncio.run(scenario())


class TestMediaAdapterMsgId:
    """Adapter-Verband (W4-B3): Deliver-Envelope führt die Broker-msg_id nach,
    so dass der Read-Frame sie trägt; fehlende msg_id (Alt-Relay) = heutiges
    Verhalten (leere msg_id → Relay verwirft fail-safe)."""

    def test_full_assembly_read_ack_carries_msg_id(self, tmp_path):
        """Neuer Relay-Verband: der final abgeschlossene Chunk trägt seine
        Broker-msg_id — chat.read.enc bekommt genau sie (clear + Ratchet-
        Spiegel), die Pipeline-Nachricht bleibt unverändert."""
        import asyncio
        import hashlib

        from test_adapter import (  # noqa: E402  (Plugin-Test-Helfer)
            _establish_session, _make_crypto_adapter, _media_chunk_frame)

        async def scenario():
            a, ws = _make_crypto_adapter(
                tmp_path, env_extra={"FLIPSI_MEDIA_DIR": str(tmp_path / "md")})
            received: list[dict] = []
            a.message_callback = received.append
            await a.connect()
            peer = await _establish_session(a, ws)
            raw = b"adapter-msgid-1" * 20
            frame = _media_chunk_frame(
                peer, "device-1", "med-adap-msgid-1", 0, 1,
                _ph3_adapter._b64e(raw), sha=hashlib.sha256(raw).hexdigest(),
                size=len(raw))
            frame["msg_id"] = "11111111-2222-3333-4444-555555555555"
            ws.inbox.append(_AdapterMsg("TEXT", json.dumps(frame)))
            await a.drain_bg_tasks()
            await asyncio.sleep(0.05)

            assert len(received) == 1, "Pipeline-Nachricht unverändert"
            assert received[0]["text"].startswith("[Foto]")
            enc_reads = [f for f in ws.sent if f["type"] == "chat.read.enc"]
            assert enc_reads, "chat.read.enc nach Medien-Empfang muss rausgehen"
            read = enc_reads[-1]
            assert read["msg_id"] == frame["msg_id"], \
                f"Read-Frame muss die Broker-msg_id tragen: {read}"
            mirror = peer.decrypt_frame(read)
            assert mirror["msg_id"] == frame["msg_id"], "Ratchet-Spiegel trägt sie"
            assert read["type"] == "chat.read.enc"  # Frame-Typen unverändert
            await a.disconnect()

        asyncio.run(scenario())

    def test_missing_msg_id_keeps_legacy_fail_safe(self, tmp_path):
        """Alt-Relay (0.14.1-Live)-Simulation: Frame OHNE msg_id → Read-Frame
        mit LEERER msg_id (heutiges Verhalten unverändert; Relay verwirft
        fail-safe), Pipeline bleibt intakt."""
        import asyncio
        import hashlib

        from test_adapter import (  # noqa: E402  (Plugin-Test-Helfer)
            _establish_session, _make_crypto_adapter, _media_chunk_frame)

        async def scenario():
            a, ws = _make_crypto_adapter(
                tmp_path, env_extra={"FLIPSI_MEDIA_DIR": str(tmp_path / "md")})
            received: list[dict] = []
            a.message_callback = received.append
            await a.connect()
            peer = await _establish_session(a, ws)
            raw = b"legacy-shape" * 20
            frame = _media_chunk_frame(
                peer, "device-1", "med-adap-legacy-01", 0, 1,
                _ph3_adapter._b64e(raw), sha=hashlib.sha256(raw).hexdigest(),
                size=len(raw))
            assert "msg_id" not in frame, "Alt-Relay-Form ohne msg_id simulieren"
            ws.inbox.append(_AdapterMsg("TEXT", json.dumps(frame)))
            await a.drain_bg_tasks()
            await asyncio.sleep(0.05)

            assert len(received) == 1, "Deliver unverändert"
            enc_reads = [f for f in ws.sent if f["type"] == "chat.read.enc"]
            assert enc_reads and enc_reads[-1]["msg_id"] == "", \
                "Leere msg_id = exakt heutiges Verhalten (fail-safe beim Relay)"
            await a.disconnect()

        asyncio.run(scenario())