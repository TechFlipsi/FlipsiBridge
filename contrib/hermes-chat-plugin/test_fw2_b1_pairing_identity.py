# FIX-WELLE-2 B1 (P1, HERZ-Befund 1) — Pairing an die Phone-IDENTITÄT binden.
#
# Root-Cause: Die Gateway-Pairing-Freigabe ('hermes pairing approve') lief je
# chat_id — die Relay-chat_id ist aber je E2E-Session NEU (App-Restart, Relay-
# Restart, Neu-Konversation) → die Freigabe verfiel bei JEDEM Session-Wechsel
# (3× manuelle Neu-Freigabe im HERZ-Realtest). Der Adapter übersetzt am
# Gateway-Rand: Hermes sieht die STABILE identity-gebundene chat_id
# 'flipsi-phone-<ik8>' (ik_pub des Phones), der Wire (Envelope + AES-GCM-AAD)
# läuft weiter über die jeweilige Relay-chat_id.

import pytest

from _fw2_common import asyncio, json, _Msg, _make_crypto_adapter, _phone_frame


@pytest.mark.asyncio
async def test_fw2_b1_stable_gateway_chat_id_across_sessions(tmp_path) -> None:
    """Zwei Sessions (Session-Wechsel) mit DEMSELBEN Phone-Key ergeben
    dieselbe Gateway-ID; der Wire (Envelope) läuft über die jeweilige
    Relay-chat_id (AAD-Parität)."""
    a, ws = _make_crypto_adapter(tmp_path)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()

    # Session 1: hello → inbound auf Relay-chat_id "chat-sess1".
    from test_adapter import _establish_session
    peer = await _establish_session(a, ws, session_id="fw2-b1-sess-0001")
    stable_expected = "flipsi-phone-" + peer.ik_pub.hex()[:8]
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "chat-sess1",
                     {"text": "Frage 1", "msg_id": "m1"}, msg_id="m1"))))
    await asyncio.sleep(0.05)
    assert len(received) == 1
    assert received[0]["chat_id"] == stable_expected, \
        "Gateway muss die STABILE Identity-ID sehen, nicht die Relay-ID"

    # Antwort des Agenten über die STABILE ID → Wire trägt chat-sess1.
    reply = await a.send(stable_expected, "Antwort 1")
    assert reply.success
    wire1 = [f for f in ws.sent if f.get("type") == "chat.reply.enc"][-1]
    assert wire1["chat_id"] == "chat-sess1"
    assert peer.decrypt_frame(wire1)["text"] == "Antwort 1"

    # Session 2 (Session-Wechsel): NEUE session_id + NEUE Relay-chat_id,
    # GLEICHER Phone-Identity-Key (das echte Phone behält seine Identity über
    # den Session-Wechsel — DERSELBE Peer handelt neu, nur neue session_id).
    ws.sent.clear()
    hello2 = peer.make_hello("fw2-b1-sess-0002")
    a._dispatch_frame(hello2)
    await a.drain_bg_tasks()
    ack2 = next(f for f in reversed(ws.sent) if f["type"] == "crypto.hello_ack")
    peer.consume_ack(ack2)
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "chat-sess2",
                     {"text": "Frage 2", "msg_id": "m2"}, msg_id="m2"))))
    await asyncio.sleep(0.05)
    assert len(received) == 2
    assert received[1]["chat_id"] == stable_expected, \
        "Session-Wechsel darf die Gateway-Freigabe-ID NICHT wechseln"
    reply2 = await a.send(stable_expected, "Antwort 2")
    assert reply2.success
    wire2 = [f for f in ws.sent if f.get("type") == "chat.reply.enc"][-1]
    assert wire2["chat_id"] == "chat-sess2", \
        "Wire/AAD muss die AKTUELLE Relay-chat_id tragen"
    assert peer.decrypt_frame(wire2)["text"] == "Antwort 2"

    await a.disconnect()
    print("OK FW2-B1: Gateway-Freigabe an Phone-Identität gebunden "
          f"({stable_expected}), Wire je Session korrekt")


@pytest.mark.asyncio
async def test_fw2_b1_legacy_route_without_identity_keeps_relay_chat_id(tmp_path) -> None:
    """Legacy-Klartext-Route (keine E2E-Session, keine Identity) — die
    Relay-chat_id läuft unverändert durch (keine umbenannte Freigabe für
    einen Modus, der keine Identity kennt)."""
    a, ws = _make_crypto_adapter(tmp_path)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    ws.inbox.append(_Msg("TEXT", json.dumps({
        "type": "chat.send", "v": 1, "agent_id": a.agent_id,
        "chat_id": "chat-legacy", "msg_id": "l1", "text": "Klartext",
    })))
    await asyncio.sleep(0.05)
    assert len(received) == 1
    assert received[0]["chat_id"] == "chat-legacy"
    await a.disconnect()
    print("OK FW2-B1-Rand: Legacy-Route ohne Identity = Relay-ID unverändert")