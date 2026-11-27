# W3-02 (Fix-Welle 3) — AUTO-APPROVE-Schiene: Bridge-Code-Kopplung = Chat-Freigabe.
#
# Vertrag: Ein mit dem Bridge-Kopplungscode gepaartes Phone (Relay authetifiziert
# WS/HTTP NUR mit diesem Code) ist AUTOMATISCH chat-freigegeben je ALLE Agenten —
# der Adapter übersetzt die (via E2E-Handshake bestätigte) Phone-Identity ik_pub
# am Gateway-Rand in eine PairingStore-Freigabe der stabilen ID 'flipsi-phone-<ik8>':
# 0 Code-DMs, 0 'Unauthorized'-Hinhalten, 0 pending-request-Frames. Legacy-Klartext
# OHNE Identity bekommt KEINE Auto-Freigabe (Gateway-Pairing bleibt dort).

import contextlib

import pytest

from _fw2_common import asyncio, json, _Msg, _make_crypto_adapter, _phone_frame


class _FakePairingStore:
    """PairingStore-Doppelgänger: approvals aufzeichnen (injiziert via Factory)."""

    def __init__(self) -> None:
        self.approved: set = set()
        self.approve_calls: list = []
        self.lock_fail = False

    def is_approved(self, platform: str, user_id: str) -> bool:
        return user_id in self.approved

    def _locked(self):
        if self.lock_fail:
            raise RuntimeError("lock kaputt (Drill)")
        return contextlib.nullcontext()

    def _approve_user(self, platform: str, user_id: str,
                      user_name: str = "") -> None:
        self.approve_calls.append((platform, user_id, user_name))
        self.approved.add(user_id)


@pytest.mark.asyncio
async def test_w3_02_bridge_paired_phone_gets_auto_approved(tmp_path) -> None:
    """E2E-Nachricht einer (Bridge-Code-gepaarten) Phone-Identity → genau EINE
    Auto-Freigabe der stabilen Identity-ID; die Zustellung läuft weiter."""
    a, ws = _make_crypto_adapter(tmp_path)
    store = _FakePairingStore()
    a._pairing_store_factory = lambda: store
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()

    from test_adapter import _establish_session
    peer = await _establish_session(a, ws, session_id="w3-02-sess-0001")
    stable_expected = "flipsi-phone-" + peer.ik_pub.hex()[:8]
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "chat-w3a",
                     {"text": "Frage 1", "msg_id": "m1"}, msg_id="m1"))))
    await asyncio.sleep(0.05)
    assert len(received) == 1, "Nachricht muss trotz Auto-Approve zustellen"
    assert store.approve_calls == [
        ("flipsibridge", stable_expected, "FlipsiBridge-Phone")], \
        "Auto-Approve exakt 1× auf die STABILE Identity-ID (ik_pub-Bindung)"

    # Zweite Nachricht: bereits freigegeben → KEINE zweite Freigabe.
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "chat-w3a",
                     {"text": "Frage 2", "msg_id": "m2"}, msg_id="m2"))))
    await asyncio.sleep(0.05)
    assert len(received) == 2
    assert len(store.approve_calls) == 1, "Idempotenz: keine Doppel-Freigabe"
    await a.disconnect()
    print("OK W3-02: Phone-Identity auto-approvt (1×, ik_pub-gebunden), "
          "Zustellung unberührt")


@pytest.mark.asyncio
async def test_w3_02_new_phone_identity_after_reinstall_gets_new_approval(
        tmp_path) -> None:
    """Reinstall mit GLEICHEM Bridge-Code: neue ik_pub → neue stabile ID →
    erneut automatische Freigabe (kein manueller Eingriff, kein Code-DM)."""
    a, ws = _make_crypto_adapter(tmp_path)
    store = _FakePairingStore()
    a._pairing_store_factory = lambda: store
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()

    from test_adapter import _establish_session
    peer1 = await _establish_session(a, ws, session_id="w3-02-r1")
    stable1 = "flipsi-phone-" + peer1.ik_pub.hex()[:8]
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer1, "chat.send.enc", "chat-r1",
                     {"text": "alt", "msg_id": "m1"}, msg_id="m1"))))
    await asyncio.sleep(0.05)

    # Zweiter Peer = frisch installiertes Phone (neue Identity-Keys), neues
    # Handshake — dieselbe Bridge-Code-Kopplung (der Relay hat ihn durchgelassen).
    peer2 = await _establish_session(a, ws, session_id="w3-02-r2")
    stable2 = "flipsi-phone-" + peer2.ik_pub.hex()[:8]
    assert stable2 != stable1
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer2, "chat.send.enc", "chat-r2",
                     {"text": "neu", "msg_id": "m2"}, msg_id="m2"))))
    await asyncio.sleep(0.05)
    approved_ids = {uid for (_p, uid, _n) in store.approve_calls}
    assert approved_ids == {stable1, stable2}, \
        "JEDE Bridge-Code-gepaarte Identity bekommt die eigene Freigabe"
    await a.disconnect()
    print("OK W3-02-Reinstall: neue Identity → neue Auto-Freigabe, 0 DMs")


@pytest.mark.asyncio
async def test_w3_02_legacy_cleartext_never_auto_approved(tmp_path) -> None:
    """Legacy-Klartext ohne E2E-Identity bekommt KEINE Auto-Freigabe —
    das normale Gateway-Pairing bleibt für diesen Modus zuständig."""
    a, ws = _make_crypto_adapter(tmp_path)
    store = _FakePairingStore()
    a._pairing_store_factory = lambda: store
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    ws.inbox.append(_Msg("TEXT", json.dumps({
        "type": "chat.send", "v": 1, "agent_id": a.agent_id,
        "chat_id": "chat-legacy-w3", "msg_id": "l1", "text": "Klartext",
    })))
    await asyncio.sleep(0.05)
    assert len(received) == 1, "Zustellung unabhängig vom Auto-Approve"
    assert store.approve_calls == [], \
        "Legacy-Route (keine Identity) darf NIE auto-approvt werden"
    await a.disconnect()
    print("OK W3-02-Rand: Legacy ohne Identity = keine Auto-Freigabe")


@pytest.mark.asyncio
async def test_w3_02_store_failure_never_blocks_delivery(tmp_path) -> None:
    """Store nicht verfügbar (None) oder Lock-Fehler → KEINE Ausnahme, KEINE
    still verworfene Nachricht — die Gateway-Pairing-Schiene greift weiter."""
    a, ws = _make_crypto_adapter(tmp_path)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()

    # (a) Store = None (Skeleton/Gateway-API fehlt)
    a._pairing_store_factory = lambda: None
    from test_adapter import _establish_session
    peer = await _establish_session(a, ws, session_id="w3-02-fail")
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "chat-f1",
                     {"text": "t1", "msg_id": "m1"}, msg_id="m1"))))
    await asyncio.sleep(0.05)
    assert len(received) == 1, "None-Store darf die Zustellung nicht bremsen"

    # (b) Lock wirft → Auto-Approve scheitert kontrolliert, Zustellung bleibt.
    broken = _FakePairingStore()
    broken.lock_fail = True
    a._pairing_store_factory = lambda: broken
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "chat-f1",
                     {"text": "t2", "msg_id": "m2"}, msg_id="m2"))))
    await asyncio.sleep(0.05)
    assert len(received) == 2, "Lock-Fehler darf keine Nachricht verwerfen"
    assert broken.approve_calls == [], "gescheiterte Freigabe = kein Halb-Write"
    await a.disconnect()
    print("OK W3-02-Fail-Safe: Store-Fehler → Zustellung bleibt, keine Ausnahme")


def test_w3_02_direct_rule_no_prefix_no_approval(tmp_path) -> None:
    """Reine Regel-Prüfung: ohne Identity-Präfix (Legacy-ID) liefert die
    Auto-Approve-Regel False — schon VOR jedem Store-Kontakt."""
    a, _ws = _make_crypto_adapter(tmp_path)
    store = _FakePairingStore()
    a._pairing_store_factory = lambda: store
    assert a._ensure_phone_auto_approved("chat-irgendwas") is False
    assert store.approve_calls == [], \
        "kein Store-Kontakt für Legacy-IDs (Präfix-Gate zuerst)"
    print("OK W3-02-Regel: Präfix-Gate trennt Legacy von der Auto-Schiene")