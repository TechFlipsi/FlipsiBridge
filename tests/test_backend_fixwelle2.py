# Fix-Welle 2 Backend-Lane (02.11.2026):
#   B5 — /screen_record-Antwortfenster konfigurierbar
#        (ANDROID_RELAY_SCREEN_RECORD_TIMEOUT, Default 55) — übernimmt den
#        ALPHA-Live-Hot-Patch als Repo-Feature (Herz-Test Befund 5).
#   B6 — /media bekommt einen EIGENEN Burst-Bucket (ANDROID_RELAY_MEDIA_RATE,
#        Default 20); Media-Chunk-Bursts teilen nicht mehr den 2/s-Chat-Bucket
#        (Herz-Test Befund 6: 6 Chunks <1 s → 4× 429).
#
# Der Chat-Bucket (chat:<ip>, 2/s) bleibt unangetastet — bewiesen im Drill:
# Chat-Limit greift WEITERHIN (3. Chat-Post im selben Fenster = 429), während
# ein 6er-Media-Burst ohne 429 durchgeht.

import asyncio
import base64
import json
import os
import sys

import pytest

from tools.android_relay import (
    start_relay,
    stop_relay,
    _auth_blocked,
    _auth_failures,
    _auth_lock,
    _chat_rate_hits,
    _chat_rate_lock,
    _chat_rate_per_second,
    _media_rate_per_second,
    _response_timeout_for,
    _screen_record_response_timeout,
)

# Helfer + Fixtures des MEDIA-T1-Testmoduls wiederverwenden (gleiche
# E2E-Mechanik, kein Duplikat-Handshake-Code im Repo). Import als MODUL —
# ein Klassen-Import (from … import TestMediaBroker) würde die Klasse in
# diesem Namespace binden und pytest liefe alle MEDIA-Tests ein zweites Mal.
import tests.test_media_relay as _media_tests  # noqa: E402

_drain_phone_state_frame = _media_tests._drain_phone_state_frame
_ph3_adapter = _media_tests._ph3_adapter
_recv_until = _media_tests._recv_until

import aiohttp  # noqa: E402


@pytest.fixture(autouse=True)
def reset_fixwelle2_relay():
    """Relay je Test sauber stoppen (Port frei + keine Session-Reste)."""
    yield
    stop_relay()


@pytest.fixture(autouse=True)
def reset_fixwelle2_state():
    """Auth- + Rate-Limiter-State je Test zurücksetzen."""
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()
    with _chat_rate_lock:
        _chat_rate_hits.clear()
    yield
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()
    with _chat_rate_lock:
        _chat_rate_hits.clear()


# ── B5: Antwortfenster /screen_record ────────────────────────────────────────


class TestB5ScreenRecordTimeout:
    """B5: ANDROID_RELAY_SCREEN_RECORD_TIMEOUT — Default 55 (ALPHA-Hot-Patch
    als Repo-Feature), Untergrenze 30, ungültige Werte → 55."""

    def test_default_55(self, monkeypatch):
        monkeypatch.delenv("ANDROID_RELAY_SCREEN_RECORD_TIMEOUT", raising=False)
        assert _screen_record_response_timeout() == 55.0

    def test_env_override(self, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_SCREEN_RECORD_TIMEOUT", "90")
        assert _screen_record_response_timeout() == 90.0

    def test_invalid_value_falls_back_to_55(self, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_SCREEN_RECORD_TIMEOUT", "fünfundfünfzig")
        assert _screen_record_response_timeout() == 55.0

    def test_clamped_to_standard_window(self, monkeypatch):
        # Ein Fenster KLEINER als 30 s ergäbe für eine Aufnahme keinen Sinn —
        # Untergrenze = Standard-Response-Timeout.
        monkeypatch.setenv("ANDROID_RELAY_SCREEN_RECORD_TIMEOUT", "5")
        assert _screen_record_response_timeout() == 30.0

    def test_path_routing(self, monkeypatch):
        monkeypatch.delenv("ANDROID_RELAY_SCREEN_RECORD_TIMEOUT", raising=False)
        assert _response_timeout_for("/screen_record") == 55.0
        assert _response_timeout_for("/ping") == 30.0
        assert _response_timeout_for("/screenshot") == 30.0
        assert _response_timeout_for("/mic_file") == 30.0

    def test_path_routing_env_override(self, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_SCREEN_RECORD_TIMEOUT", "61")
        assert _response_timeout_for("/screen_record") == 61.0
        assert _response_timeout_for("/tap") == 30.0

    def test_standard_window_unchanged(self):
        # B5 darf das Standard-Fenster aller anderen Routen NICHT ändern.
        assert _response_timeout_for("/screen") == 30.0


# ── B6: Media-Rate-Konfiguration ────────────────────────────────────────────


class TestB6MediaRateConfig:
    """B6: ANDROID_RELAY_MEDIA_RATE — Default 20/s, min 1, config-Datei
    (MEDIA_RATE_PER_SECOND) überschreibt env, env überschreibt Default.
    Der Chat-Bucket (ANDROID_RELAY_CHAT_RATE_PER_SEC) bleibt unberührt."""

    def test_default_20(self, monkeypatch):
        monkeypatch.delenv("ANDROID_RELAY_MEDIA_RATE", raising=False)
        assert _media_rate_per_second() == 20
        assert _media_rate_per_second(None) == 20

    def test_env_override(self, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_MEDIA_RATE", "5")
        assert _media_rate_per_second() == 5

    def test_config_wins_over_env(self, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_MEDIA_RATE", "5")
        assert _media_rate_per_second({"MEDIA_RATE_PER_SECOND": 50}) == 50

    def test_invalid_falls_back_to_20(self, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_MEDIA_RATE", "keine-zahl")
        assert _media_rate_per_second() == 20
        assert _media_rate_per_second({"MEDIA_RATE_PER_SECOND": []}) == 20

    def test_min_1(self, monkeypatch):
        monkeypatch.setenv("ANDROID_RELAY_MEDIA_RATE", "0")
        assert _media_rate_per_second() == 1

    def test_chat_bucket_untouched(self, monkeypatch):
        """Chat-Default bleibt 2/s — B6 ändert am Chat-Bucket NICHTS."""
        monkeypatch.delenv("ANDROID_RELAY_CHAT_RATE_PER_SEC", raising=False)
        assert _chat_rate_per_second() == 2
        monkeypatch.setenv("ANDROID_RELAY_CHAT_RATE_PER_SEC", "3")
        assert _chat_rate_per_second() == 3


# ── B6: Drill gegen den laufenden Relay ──────────────────────────────────────


class TestB6MediaBurstDrill:
    """Herz-Test Befund 6 als Regression: 6-Chunk-Burst über POST /media
    im selben Sekundenfenster wie AKTIVER Chat — ohne 429."""

    PORT = 19961
    CODE = "FW2B6C1"
    TOKEN = "fw2-b6-agent-token"
    SESSION_ID = "fw2-b6-e2e-session-0001"

    @property
    def identity(self):
        return {"identity_priv": _media_tests.TestMediaBroker.IK_A_PRIV,
                "identity_pub": _media_tests.TestMediaBroker.IK_A_PUB,
                "prekey_priv": _media_tests.TestMediaBroker.SPK_A_PRIV,
                "prekey_pub": _media_tests.TestMediaBroker.SPK_A_PUB}

    def _write_config(self, tmp_path):
        """Chat bewusst auf dem harten Default 2/s — wie Produktion."""
        cfg = tmp_path / "relay-config.json"
        cfg.write_text(json.dumps({
            "ANDROID_AGENT_TOKEN_ALPHA": self.TOKEN,
            "ANDROID_AGENT_DISPLAY_ALPHA": "Alpha",
            "CHAT_RATE_PER_SECOND": 2,
            "ANDROID_AGENT_IDENTITY_ALPHA": _media_tests.TestMediaBroker.IK_A_PUB,
            "ANDROID_AGENT_PREKEY_ALPHA": _media_tests.TestMediaBroker.SPK_A_PUB,
            "ANDROID_AGENT_FINGERPRINT_ALPHA":
                _ph3_adapter.fingerprint_of_pub(
                    bytes.fromhex(_media_tests.TestMediaBroker.IK_A_PUB)),
        }), encoding="utf-8")
        return str(cfg)

    def _start(self, tmp_path, monkeypatch, port):
        monkeypatch.setenv("ANDROID_RELAY_CONFIG", self._write_config(tmp_path))
        monkeypatch.delenv("ANDROID_RELAY_MEDIA_RATE", raising=False)
        monkeypatch.delenv("ANDROID_RELAY_SCREEN_RECORD_TIMEOUT", raising=False)
        start_relay(pairing_code=self.CODE, port=port)
        return f"http://127.0.0.1:{port}"

    async def _handshake(self, session, base, agent_ws, phone_ws, session_id):
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

    async def _connect(self, session, base, port):
        phone = await session.ws_connect(
            f"ws://127.0.0.1:{port}/ws",
            headers={"Authorization": f"Bearer {self.CODE}"})
        await _drain_phone_state_frame(phone)
        agent = await session.ws_connect(
            f"ws://127.0.0.1:{port}/agent_ws",
            headers={"Authorization": f"Bearer {self.TOKEN}"})
        await _recv_until(phone, "relay.state", timeout=6)
        return phone, agent

    @staticmethod
    def _media_body(peer, chat_id, media_id, seq, total, raw_len, kind="image"):
        frame = peer.encrypt_frame(
            "chat.media.enc", chat_id,
            {"media_id": media_id, "seq": seq, "total": total,
             "data": base64.b64encode(b"chunkdata-%d" % seq).decode("ascii")},
            clear={"media_id": media_id, "seq": seq, "total": total})
        body = {"agent_id": "alpha", "chat_id": chat_id,
                "session_id": frame["session_id"], "ts": frame["ts"],
                "hdr": frame["hdr"], "nonce": frame["nonce"], "ct": frame["ct"],
                "media_id": media_id, "seq": seq, "total": total}
        if seq == 0:
            body.update({"kind": kind, "name": "burst.jpg",
                         "mime": "image/jpeg", "size": raw_len})
        return body

    def test_b6_six_chunk_burst_with_active_chat(self, tmp_path, monkeypatch):
        """DRILL (Befund 6, 1:1): Chat füllt/seiner 2/s-Grenze im selben
        Fenster, 6 Media-Chunks <1 s — alle 6 kommen OHNE 429 durch."""
        port = self.PORT
        base = self._start(tmp_path, monkeypatch, port)
        chat_id = "chat-fw2-b6-0001"
        media_id = "med-fw2b6-000001"

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await self._connect(session, base, port)
                peer, _agent_session = await self._handshake(
                    session, base, agent, phone, self.SESSION_ID)

                # ── Aktiver Chat: 3 Posts <1 s → der 3. ZÄHLT 429 (2/s hält).
                chat_statuses = []
                for _ in range(3):
                    frame = peer.encrypt_frame(
                        "chat.send.enc", chat_id, {"text": "chat-burst"})
                    resp = await session.post(
                        f"{base}/chat",
                        json={"agent_id": "alpha", "chat_id": chat_id,
                              "session_id": frame["session_id"], "ts": frame["ts"],
                              "hdr": frame["hdr"], "nonce": frame["nonce"],
                              "ct": frame["ct"]},
                        headers={"Authorization": f"Bearer {self.CODE}"})
                    chat_statuses.append(resp.status)
                assert chat_statuses[:2] == [200, 200], chat_statuses
                assert chat_statuses[2] == 429, \
                    "Chat-Rate-Limit muss weiter greifen (Bucket unangetastet)"
                err = await (await session.post(
                    f"{base}/chat",
                    json={"agent_id": "alpha", "chat_id": chat_id,
                          "session_id": "x", "ts": 0, "hdr": {}, "nonce": "",
                          "ct": ""},   # nur Fehlermeldung lesen (kein n-Verbrauch)
                    headers={"Authorization": f"Bearer {self.CODE}"})).json()
                assert err["error"] == "Chat rate limit exceeded"

                # ── 6-Chunk-Burst im SELBEN Fenster: vorher 4× 429 (Befund 6),
                #    jetzt eigener Bucket → alle 200.
                media_statuses = []
                for seq in range(6):
                    body = self._media_body(peer, chat_id, media_id, seq, 6,
                                            raw_len=6 * 1024)
                    resp = await session.post(
                        f"{base}/media", json=body,
                        headers={"Authorization": f"Bearer {self.CODE}"})
                    media_statuses.append(resp.status)
                assert media_statuses == [200] * 6, media_statuses

                # Agent hat alle 6 Chunks bekommen (Zustellung, nicht nur 200).
                for expected_seq in range(6):
                    f = await _recv_until(agent, "chat.media.enc", timeout=6)
                    assert f["seq"] == expected_seq
                    assert f["media_id"] == media_id

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_b6_media_limit_is_own_429(self, tmp_path, monkeypatch):
        """Der Media-Bucket hat ein EIGENES Limit: 25 Chunks <1 s am Default
        20/s → 429 'Media rate limit exceeded' (nicht der Chat-Fehler)."""
        port = self.PORT + 1
        base = self._start(tmp_path, monkeypatch, port)
        chat_id = "chat-fw2-b6-0002"
        media_id = "med-fw2b6-000002"

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await self._connect(session, base, port)
                peer, _agent_session = await self._handshake(
                    session, base, agent, phone, self.SESSION_ID + "-b")

                statuses, errors = [], []
                for seq in range(25):
                    body = self._media_body(peer, chat_id, media_id, seq, 25,
                                            raw_len=25 * 1024)
                    resp = await session.post(
                        f"{base}/media", json=body,
                        headers={"Authorization": f"Bearer {self.CODE}"})
                    statuses.append(resp.status)
                    if resp.status == 429:
                        errors.append((await resp.json())["error"])
                assert 429 in statuses, \
                    "25 Chunks <1 s müssen am Default-20/s-Limit anschlagen"
                assert set(errors) == {"Media rate limit exceeded"}, errors

                await agent.close()
                await phone.close()

        asyncio.run(scenario())

    def test_b6_agent_media_enc_burst_not_chat_limited(self, tmp_path,
                                                        monkeypatch):
        """Agent→Phone-Parität: 6 chat.media.enc-Frames <1 s über die Agent-WS
        — mit altem Code fraß der Burst den chat_event-Bucket (2/s → 4 Frames
        still verworfen); jetzt kommt ALLES am Phone an."""
        port = self.PORT + 2
        base = self._start(tmp_path, monkeypatch, port)
        chat_id = "chat-fw2-b6-0003"
        media_id = "med-fw2b6-000003"

        async def scenario():
            async with aiohttp.ClientSession() as session:
                phone, agent = await self._connect(session, base, port)
                peer, agent_session = await self._handshake(
                    session, base, agent, phone, self.SESSION_ID + "-c")

                for seq in range(6):
                    frame = agent_session.encrypt_frame(
                        "chat.media.enc", chat_id,
                        {"media_id": media_id, "seq": seq, "total": 6,
                         "data": base64.b64encode(b"a2p-%d" % seq).decode()},
                        clear={"media_id": media_id, "seq": seq, "total": 6,
                               **({"kind": "image", "name": "burst-a2p.jpg",
                                   "mime": "image/jpeg", "size": 6}
                                  if seq == 0 else {})})
                    await agent.send_json(frame)

                for expected_seq in range(6):
                    f = await _recv_until(phone, "chat.media.enc", timeout=6)
                    assert f["seq"] == expected_seq
                    assert f["media_id"] == media_id
                    decoded = peer.decrypt_frame(f)
                    assert base64.b64decode(decoded["data"]) == b"a2p-%d" % expected_seq

                await agent.close()
                await phone.close()

        asyncio.run(scenario())