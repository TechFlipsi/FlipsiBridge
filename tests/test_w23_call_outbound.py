"""W23-10 — Ausgehender Anruf (Phone→Agent call_request.enc, Relay-Verband).

Beweise (Frame-Verband GELESEN aus tools/android_relay.py, nicht geraten):
- call_request.enc ist im Allowlist-Verband _PHONE_CALL_ENC_FRAMES (§call) und
  läuft über denselben Router mit denselben Guards wie die Call-Quittungen:
  E2E je Geräte-Session, opaker Pass-Through, Ziel-agent_id NUR aus dem
  Session-Broker (nie ein Frame-Top-Level agent_id), Rate-Cap 6/s im eigenen
  'callsig:*'-Bucket, Replay-Fenster p2a.
- Fail-closed: unbekannte Session verworfen (kein Echo); ein FREMDER Geräte-
  Slot verletzt die Session-Bindung → verworfen.
- Klar bleiben weiterhin nur IDs (chat_id/call_id/agent_id/session/hdr); die
  App-Richtung trägt das ct {direction: 'outbound-app', caller_device} — der
  Adapter liest sie E2E (Parität kind/reason beim call_invite.enc).
- App-Seite (CallRules.outboundPickup): der Agent nimmt mit dem BESTAND
  call_invite.enc derselben call_id ab → call_accepted-Ablauf unverändert
  (adapter.py-Verband bleibt am Bestand; Adapter-Lane DISPATCH: call_request.
  enc in die Phone-Frame-Sets + Voice-Loop-Start, Handoff im Wellen-Report).
"""

import asyncio
import os
import sys

import aiohttp
import pytest

from tools.android_relay import (
    start_relay,
    stop_relay,
    _PHONE_CALL_ENC_FRAMES,
    _chat_rate_hits,
    _chat_rate_lock,
)

import tests.test_backend_b4_multigeraete as _b4  # noqa: E402
from tests.test_android_relay import _recv_until  # noqa: E402
from tests.test_backend_call_signaling import (  # noqa: E402
    _start,
    _enc_session_acked,
)

PORT = 19985
CALL_ID = "call-w2310out"


@pytest.fixture(autouse=True)
def _w2310_reset():
    """Relaiter Zustand je Test: Rate-Slots leeren + Relay stoppen."""
    with _chat_rate_lock:
        _chat_rate_hits.clear()
    yield
    with _chat_rate_lock:
        _chat_rate_hits.clear()
    stop_relay()


class TestW2310OutboundRoute:
    def test_call_request_in_allowlist_verband(self):
        # Der OUTBOUND-Start hängt im selben Frame-Set wie die Quittungen —
        # der ganze Guard-Verband (Rate/Replay/Binding/Broker) erbt automatisch.
        assert "call_request.enc" in _PHONE_CALL_ENC_FRAMES

    def test_call_request_routes_opak_to_agent(self, tmp_path, monkeypatch):
        return asyncio.run(self._routes(tmp_path, monkeypatch))

    async def _routes(self, tmp_path, monkeypatch) -> None:
        base = _start(tmp_path, monkeypatch, port=PORT)
        async with aiohttp.ClientSession() as http:
            phone_a, _phone_b, agent_ws = await _b4._connect_devices(http, base, PORT)
            peer_a, _agent_session = await _b4._handshake(
                http, base, agent_ws, phone_a, _b4.DEVICE_A, "call-sess-a",
                "chat-aaaa-1111")
            assert await _enc_session_acked(http, base, "call-sess-a")
            # SIRS Tap: call_request E2E je Geräte-Session — ct trägt die
            # Richtung + das eigene Gerät, Klar bleibt ids-only.
            frame = peer_a.encrypt_frame(
                "call_request.enc", "chat-aaaa-1111",
                {"direction": "outbound-app", "caller_device": _b4.DEVICE_A},
                clear={"call_id": CALL_ID})
            await phone_a.send_json(frame)
            got = await _recv_until(agent_ws, "call_request.enc", timeout=6)
            assert got["ct"] == frame["ct"], "ct bleibt OPAK (Inhalt nur im ct)"
            assert got["call_id"] == CALL_ID
            # Ziel-agent_id aus dem SESSION-BROKER, nicht aus dem Frame:
            assert got["agent_id"] == "alpha"
            assert got["session_id"] == "call-sess-a"
            assert got["chat_id"] == "chat-aaaa-1111"
            # Klar-Felder sind ids-only: keine Richtung außerhalb des ct.
            assert "direction" not in got and "caller_device" not in got
            await phone_a.close()

    def test_call_request_without_session_fail_closed(
            self, tmp_path, monkeypatch, caplog):
        return asyncio.run(self._unknown_session(tmp_path, monkeypatch, caplog))

    async def _unknown_session(self, tmp_path, monkeypatch, caplog) -> None:
        import logging as _logging
        caplog.set_level(_logging.INFO, logger="android_relay")
        base = _start(tmp_path, monkeypatch, port=PORT)
        async with aiohttp.ClientSession() as http:
            phone_a, _phone_b, agent_ws = await _b4._connect_devices(http, base, PORT)
            peer_a, _agent_session = await _b4._handshake(
                http, base, agent_ws, phone_a, _b4.DEVICE_A, "call-sess-a",
                "chat-aaaa-1111")
            # Echte Krypto-Hülle, aber die session_id behauptet eine unbekannte
            # Session (Phone-Lüge) → fail-closed, KEIN Fan-out.
            frame = peer_a.encrypt_frame(
                "call_request.enc", "chat-aaaa-1111",
                {"direction": "outbound-app"},
                clear={"call_id": "call-nosess01"})
            frame["session_id"] = "gibts-nicht"
            await phone_a.send_json(frame)
            await asyncio.sleep(0.8)
            _await_call_log_record(
                caplog, "call_request.enc ohne bestätigte E2E-Session — verworfen")
            with pytest.raises(asyncio.TimeoutError):
                await agent_ws.receive_json(timeout=0.8)
            await phone_a.close()

    def test_call_request_foreign_device_binding_fail_closed(
            self, tmp_path, monkeypatch, caplog):
        """Fremder Slot ruft die Session von Gerät A → fail-closed (Session-
        Broker-Bindung: Rauf-Anruf gehört zum Geräte-Slot mit der Session)."""
        return asyncio.run(self._foreign_device(tmp_path, monkeypatch, caplog))

    async def _foreign_device(self, tmp_path, monkeypatch, caplog) -> None:
        import logging as _logging
        caplog.set_level(_logging.INFO, logger="android_relay")
        base = _start(tmp_path, monkeypatch, port=PORT)
        async with aiohttp.ClientSession() as http:
            phone_a, phone_b, agent_ws = await _b4._connect_devices(http, base, PORT)
            peer_a, _agent_session = await _b4._handshake(
                http, base, agent_ws, phone_a, _b4.DEVICE_A, "call-sess-a",
                "chat-aaaa-1111")
            frame = peer_a.encrypt_frame(
                "call_request.enc", "chat-aaaa-1111",
                {"direction": "outbound-app"},
                clear={"call_id": CALL_ID})
            # GeräT B sendet As Request → Verwerfen (kein Fremd-Rauf-Anruf).
            await phone_b.send_json(frame)
            await asyncio.sleep(0.8)
            _await_call_log_record(caplog, "signalisiert Fremd-Session")
            with pytest.raises(asyncio.TimeoutError):
                await agent_ws.receive_json(timeout=0.8)
            await phone_a.close()
            await phone_b.close()


def _await_call_log_record(caplog, text: str) -> None:
    """EIGENER Jäger (kein Test-Querverband): wartet bis _recv-Pause das
    Relay-Thread-Log fängt; sofortiger Fail, wenn der Beweis nie kommt."""
    for _ in range(50):
        if any(text in rec.message for rec in caplog.records):
            return
        import time
        time.sleep(0.05)
    raise AssertionError(f"Log-Beweis fehlt: {text!r}\n"
                         + "\n".join(rec.message for rec in caplog.records
                                     if rec.name == "android_relay"))
