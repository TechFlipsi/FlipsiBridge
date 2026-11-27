# FIX-WELLE-2 B2 (P3, HERZ-Befund 2) — TOFU-Pin erneuert sich nach Bestätigung.
#
# Root-Cause: Der Phone-TOFU-Pin wurde nur bei ERSTER Sichtung geschrieben —
# ein Identity-Wechsel warnte bei JEDEM Reconnect erneut (Warn-Spam /
# Alarm-Fatigue: eine echte Übernahme ging in der Flut unter), und der Pin
# erneuerte sich nie. Fix: (a) je NEUEM ik_pub genau EINE Warnung
# (_pin_warned_ik-Dedupe), (b) nach BESTÄTIGTEM FP-Abgleich pint der
# Adapter die neue Identity persistent um (_confirm_phone_pin).

import json
import pathlib

import pytest

from _fw2_common import asyncio, _Msg, _make_crypto_adapter, _phone_frame


@pytest.mark.asyncio
async def test_fw2_b2_tofu_pin_warns_once_per_key_and_confirms_on_fp(tmp_path, caplog) -> None:
    """Identity-Wechsel warnt je NEUEM Key genau EINMAL (nicht je Reconnect);
    nach BESTATENEM FP-Abgleich pint der Adapter die neue Identity persistent
    um — künftige Handshakes desselben Geräts warnen nicht mehr."""
    import logging
    from test_adapter import _establish_session
    from adapter import PhoneCryptoPeer

    a, ws = _make_crypto_adapter(tmp_path)
    await a.connect()
    peer = await _establish_session(a, ws)  # pinnt peer.ik_pub (erste Sichtung)

    # NEUES 'Phone' (anderer Identity-Key): erste Sichtung = EINE Warnung …
    identity = a._identity
    peer_b = PhoneCryptoPeer(a.agent_id, identity["identity_pub"], identity["prekey_pub"])
    caplog.clear()
    with caplog.at_level(logging.WARNING):
        a._check_phone_identity_pin({"ik_pub": peer_b.ik_pub.hex()})
    warnings_first = [r for r in caplog.records
                      if "PHONE-IDENTITY-WECHSEL erkannt" in r.message]
    assert len(warnings_first) == 1, "Erste Sichtung des neuen Keys: genau 1 Warnung"

    # … Reconnect-Wiederholung desselben Keys: KEINE weitere Warnung (Spam).
    caplog.clear()
    with caplog.at_level(logging.WARNING):
        a._check_phone_identity_pin({"ik_pub": peer_b.ik_pub.hex()})
    assert not [r for r in caplog.records
                if "PHONE-IDENTITY-WECHSEL erkannt" in r.message], \
        "Wiederholter Reconnect desselben Keys darf nicht erneut warnen"

    # Session-Wechsel auf peer_b (Handshake ersetzt die Session, Key B aktiv),
    # dann FP-ABGLEICH OK → Pin wird persistent auf B umgestellt.
    ws.inbox.clear()
    ws.sent.clear()
    hello_b = peer_b.make_hello("fw2-b2-sess-0002")
    a._dispatch_frame(hello_b)
    await a.drain_bg_tasks()
    ack_b = next(f for f in reversed(ws.sent) if f["type"] == "crypto.hello_ack")
    peer_b.consume_ack(ack_b)
    fp_msg = f"FP-ABGLEICH: {peer_b.fingerprint}"
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer_b, "chat.send.enc", "device-1",
                     {"text": fp_msg, "msg_id": "fpb"}, msg_id="fpb"))))
    await asyncio.sleep(0.05)
    await a.drain_bg_tasks()
    pin_path = pathlib.Path(a.phone_pin_file)
    pin = json.loads(pin_path.read_text(encoding="utf-8"))
    assert pin["ik_pub"] == peer_b.ik_pub.hex(), \
        "FP-OK muss den TOFU-Pin persistent auf die neue Identity umstellen"

    # Nächster Handshake mit B: KEINE Warnung mehr (B ist der gepinnte
    # Normalzustand — kein Warn-Spam für das bestätigte Gerät).
    caplog.clear()
    with caplog.at_level(logging.WARNING):
        a._check_phone_identity_pin({"ik_pub": peer_b.ik_pub.hex()})
    assert not [r for r in caplog.records
                if "PHONE-IDENTITY-WECHSEL" in r.message]
    await a.disconnect()
    print("OK FW2-B2: 1 Warnung je Key + Pin-Übernahme nach FP-Bestätigung")