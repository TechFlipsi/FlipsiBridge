# FIX-WELLE-2 B8 (P3, HERZ-Befund 8) — Cross-Session-Takeover ≠ stiller Verwurf.
#
# Root-Cause: Ein enc-Frame mit FREMDER session_id bei AKTIVER Session wurde
# STILL verworfen (Realtest: die Referenz-Probe hatte die Phone-Session des
# Adapters übernommen; das echte Phone sendete weiter, der Adapter warf die
# Frames stumm weg — Chip blieb '✓✓ Zugestellt', Chat tot, kein Lebenszeichen,
# KEIN crypto.session_reset, weil es die nur im unbekannt-Sessions-Fall gab).
# Fix: Der fremde Frame wirkt als Takeover-Sonde → crypto.session_reset für
# die FRAME-Session (reason='cross-session-takeover') ans Relay → das Phone
# wird informiert und handelt neu. Dedup über _session_reset_pending; Replay-
# Altlasten (Session dem Relay unbekannt) werden dort ignoriert, die aktive
# legitime Session bleibt unberührt (kein DoS-Hebel).

import json

import pytest

from _fw2_common import asyncio, _Msg, _make_crypto_adapter, _phone_frame


@pytest.mark.asyncio
async def test_fw2_b8_cross_session_takeover_triggers_reset(tmp_path) -> None:
    """Fremd-Session-Frame bei aktiver Session → Genau EIN session_reset für
    die FRAME-Session (reason=cross-session-takeover), kein Gateway-Push;
    zweiter Fremd-Frame dedupliziert; nächstes hello hebt das Flag auf und
    die Zustellung heilt."""
    from test_adapter import _establish_session
    from adapter import PhoneCryptoPeer

    a, ws = _make_crypto_adapter(tmp_path)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    peer = await _establish_session(a, ws, session_id="fw2-b8-aktive-sess")

    # Zweite, fremde Session (Takeover-Probe) mit ANDERER session_id.
    identity = a._identity
    intruder = PhoneCryptoPeer(a.agent_id, identity["identity_pub"],
                               identity["prekey_pub"])
    hello_i = intruder.make_hello("fw2-b8-intruder-sess")
    a._dispatch_frame(hello_i)
    await a.drain_bg_tasks()
    ack_i = next(f for f in reversed(ws.sent) if f["type"] == "crypto.hello_ack")
    intruder.consume_ack(ack_i)
    assert a._crypto_session.session_id == "fw2-b8-intruder-sess"

    # Das ECHTE Phone sendet weiter auf SEINER (nun fremden) Session …
    ws.sent.clear()
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "device-1",
                     {"text": "Bin ich noch da?", "msg_id": "x1"}, msg_id="x1"))))
    await asyncio.sleep(0.05)
    await a.drain_bg_tasks()

    # … Frame STILL verworfen (kein Gateway-Push) …
    assert received == [], "Fremd-Session-Frame darf nicht zustellt werden"
    # … aber mit crypto.session_reset für die FRAME-Session beantwortet.
    resets = [f for f in ws.sent if f["type"] == "crypto.session_reset"]
    assert len(resets) == 1, "Genau EIN Reset (Dedup via _session_reset_pending)"
    assert resets[0]["session_id"] == "fw2-b8-aktive-sess", \
        "Reset trifft die FRAME-Session (das echte Phone soll neu handshaken)"
    assert resets[0]["reason"] == "cross-session-takeover"
    assert any("Cross-Session" in (r.get("reason") or "")
               for r in a.rejected_frames), \
        "Diagnose-Eintrag mit Takeover-Sonde im rejected_frames"

    # Zweiter Fremd-Frame: KEIN zweiter Reset (pending-Flag steht).
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "device-1",
                     {"text": "Noch da?", "msg_id": "x2"}, msg_id="x2"))))
    await asyncio.sleep(0.05)
    await a.drain_bg_tasks()
    assert sum(1 for f in ws.sent if f["type"] == "crypto.session_reset") == 1

    # Nächstes crypto.hello hebt das pending-Flag auf (Heilungspfad frei).
    peer_healed = await _establish_session(a, ws, session_id="fw2-b8-heilung")
    assert a._session_reset_pending is False
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer_healed, "chat.send.enc", "device-1",
                     {"text": "Wieder da", "msg_id": "x3"}, msg_id="x3"))))
    await asyncio.sleep(0.05)
    assert len(received) == 1 and received[0]["text"] == "Wieder da", \
        "Nach Neu-Handshake wird die Nachricht normal zugestellt"

    await a.disconnect()
    print("OK FW2-B8: Takeover-Sonde → session_reset + Neu-Handshake, kein Stillverwurf")