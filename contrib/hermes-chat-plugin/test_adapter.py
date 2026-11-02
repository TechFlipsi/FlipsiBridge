#!/usr/bin/env python3
"""Selbst-Test des FlipsiBridge-Chat-Adapters mit Mock-WS (kein Netzwerk, kein Relay).

Deckt ab (je Skeleton-Pflicht):
- connect: Pinning-Verweigerung für Fremd-URL / Fremd-Schema / userinfo-URL
- connect: erfolgreicher Aufbau + agent.register-Envelope
- send: JSON-Envelope (type=chat.reply, route=/chat) über die Agent-WS
- receive: chat.deliver → Nachricht-Callback (Roundtrip send→receive→send)
- Agentenschutz: Tool-Call-Frames und fremde Namespaces werden ABGELEHNT
- disconnect: sauberes Schließen
"""

import asyncio
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Skeleton-Pfad erzwingen: 'gateway'-Import blockieren, damit adapter.py die
# Fallback-Base (_base_fallback.py) nutzt. In einer echten Hermes-Installation
# greift automatisch der echte BasePlatformAdapter — dieser Test prüft isoliert.
sys.modules["gateway"] = None  # None-Eintrag → ImportError im try/except des Adapters

from adapter import (  # noqa: E402
    AdapterPinningError, FlipsiBridgeChatAdapter, check_flipsibridge_requirements)

RELAY = "wss://relay.example.internal/agent_ws"


class _Msg:
    """Minimaler WS-Frame (aiohttp-ähnlich: .type mit .name, .data als Text)."""

    class _Type:
        def __init__(self, name):
            self.name = name

    def __init__(self, name, data=""):
        self.type = self._Type(name)
        self.data = data


class MockWS:
    """Zeichnet gesendete Frames auf und spielt Empfangs-Frames aus einer Queue ab.

    PH2-FIX (Jäger B5): fail_types = Envelope-Typen, deren send_str CRASHT —
    für den Test 'Sendefehler lässt den Spinner nicht stecken'.
    """

    def __init__(self):
        self.sent: list[dict] = []
        self.inbox: list[_Msg] = []
        self.closed = False
        self.fail_types: set[str] = set()

    async def send_str(self, raw: str) -> None:
        frame = json.loads(raw)
        if frame.get("type") in self.fail_types:
            raise RuntimeError(f"send boom ({frame.get('type')})")
        self.sent.append(frame)

    async def receive(self) -> _Msg:
        # Offen halten, bis disconnect() schließt (Relay hält die WS dauerhaft);
        # dabei laufend auf neue Frames prüfen.
        while not self.closed and not self.inbox:
            await asyncio.sleep(0.005)
        if self.inbox:
            return self.inbox.pop(0)
        return _Msg("CLOSE")

    async def close(self) -> None:
        self.closed = True


def make_adapter(tmp_env: dict, **overrides) -> tuple[FlipsiBridgeChatAdapter, MockWS]:
    """Adapter mit Config-Dict (Skeleton-Pfad: Fallback-Base) + Mock-WS."""
    env = {"FLIPSI_RELAY_URL": RELAY, "FLIPSI_AGENT_TOKEN": "test-token",
           "FLIPSI_AGENT_ID": "agent-a"}
    env.update(tmp_env)
    old = {k: os.environ.get(k) for k in env}
    os.environ.update(env)
    try:
        adapter = FlipsiBridgeChatAdapter(env)
    finally:
        for k, v in old.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
    ws = MockWS()
    for key, value in overrides.items():
        setattr(adapter, key, value)
    adapter._ws_factory = lambda url, token, agent_id: ws
    return adapter, ws


@pytest.mark.asyncio
async def test_pinning() -> None:
    """I3: Fremd-URL, Fremd-Schema und userinfo werden verweigert."""
    # Connect-URL weicht von der explizit gepinnten Whitelist ab → verweigert.
    a, _ = make_adapter({"FLIPSI_RELAY_URL": "wss://boese.example.net/agent_ws",
                         "FLIPSI_ALLOWED_URLS": RELAY})
    try:
        a._pin_relay_url()
        raise AssertionError("Fremd-URL nicht verweigert")
    except AdapterPinningError:
        pass

    a2, _ = make_adapter({"FLIPSI_RELAY_URL": "http://relay.example.internal/agent_ws"})
    try:
        a2._pin_relay_url()
        raise AssertionError("Klartext-Schema nicht verweigert")
    except AdapterPinningError:
        pass

    a3, _ = make_adapter({"FLIPSI_RELAY_URL": "wss://user:pass@relay.example.internal/agent_ws"})
    try:
        a3._pin_relay_url()
        raise AssertionError("userinfo-URL nicht verweigert")
    except AdapterPinningError:
        pass

    # Whitelist-Member DÜRFEN connecten; insecure nur mit explizitem Opt-in.
    a4, _ = make_adapter({"FLIPSI_ALLOWED_URLS": "wss://relay.example.internal/agent_ws,"
                                                  "wss://zweit-relay.example.internal/agent_ws"})
    assert a4._pin_relay_url() == RELAY
    a5, _ = make_adapter({"FLIPSI_RELAY_URL": "http://relay.example.internal/agent_ws",
                          "FLIPSI_ALLOW_INSECURE": "1"})
    assert a5._pin_relay_url().startswith("http://")
    print("OK Pinning: Fremd-URL/Fremd-Schema/userinfo verweigert, Whitelist erlaubt")


@pytest.mark.asyncio
async def test_connect_register() -> None:
    a, ws = make_adapter({})
    ok = await a.connect()
    assert ok is True and a.is_connected()
    reg = ws.sent[0]
    assert reg["type"] == "agent.register" and reg["agent_id"] == "agent-a"
    assert reg.get("v") == 1
    assert "test-token" not in json.dumps(ws.sent)  # Token nie im Envelope
    await a.disconnect()
    assert ws.closed and not a.is_connected()
    print("OK Connect: agent.register gesendet, Token nicht im Envelope, disconnect sauber")


@pytest.mark.asyncio
async def test_send_and_receive_roundtrip() -> None:
    a, ws = make_adapter({})
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()

    # --- send: JSON über die Agent-WS an die /chat-Route ---
    result = await a.send("device-1", "Hallo vom Agent", reply_to="m1")
    assert result.success and result.message_id
    # PH2-T11: send() erzeugt je Antwort ein chat.activity-Paar — der
    # chat.reply-Frame liegt dazwischen (typbasiert suchen, nicht per Index).
    frame = next(f for f in ws.sent if f["type"] == "chat.reply")
    assert frame["route"] == "/chat"
    assert frame["chat_id"] == "device-1" and frame["body"]["text"] == "Hallo vom Agent"
    assert frame["reply_to"] == "m1"

    # --- receive: Relay liefert eine Nutzer-Nachricht → Nachricht-Pipeline ---
    ws.inbox.append(_Msg("TEXT", json.dumps({
        "type": "chat.deliver", "v": 1, "msg_id": "m1", "agent_id": "agent-a",
        "chat_id": "device-1", "body": {"text": "Hallo, Agent!"}})))
    await asyncio.sleep(0.05)  # Frame-Loop arbeiten lassen
    assert len(received) == 1, f"erwartet 1 empfangene Nachricht, got {received}"
    assert received[0]["text"] == "Hallo, Agent!" and received[0]["chat_id"] == "device-1"

    # --- Roundtrip-Antwort auf die empfangene Nachricht ---
    reply = await a.send(received[0]["chat_id"], "Antwort", reply_to="m1")
    assert reply.success
    replies = [f for f in ws.sent if f["type"] == "chat.reply"]
    assert replies[-1]["body"]["text"] == "Antwort"

    # --- send_typing + send_image (Skeleton-Pflicht) ---
    await a.send_typing("device-1")
    img = await a.send_image("device-1", "https://cdn.example.internal/x.png", caption="Pic")
    assert img.success
    types = [f["type"] for f in ws.sent]
    assert types.count("typing") == 1 and "chat.reply" in types

    # --- get_chat_info ---
    info = a.get_chat_info("device-1")
    assert info == {"name": "FlipsiBridge-Chat device-1", "type": "dm", "chat_id": "device-1"}

    await a.disconnect()
    print("OK Roundtrip: send→/chat-Envelope, chat.deliver→Nachricht, Antwort, typing, image, chat_info")


@pytest.mark.asyncio
async def test_inbound_guards() -> None:
    """Agentenschutz: Tool-Call-Frames + fremde Namespaces werden abgelehnt."""
    a, ws = make_adapter({"FLIPSI_ALLOWED_DEVICES": "device-1"})
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()

    # Frame mit Tool-/Command-Semantik: NIEMALS ausführen, nur verwerfen.
    ws.inbox.append(_Msg("TEXT", json.dumps({"type": "tool_call", "tool": "terminal"})))
    ws.inbox.append(_Msg("TEXT", json.dumps({"type": "chat.deliver", "agent_id": "agent-b",
                                             "chat_id": "device-1", "body": {"text": "fremd"}})))
    ws.inbox.append(_Msg("TEXT", json.dumps({"type": "chat.deliver", "agent_id": "agent-a",
                                             "chat_id": "device-9", "body": {"text": "verboten"}})))
    ws.inbox.append(_Msg("TEXT", "kein json"))
    await asyncio.sleep(0.05)

    assert received == [], f"bewusst abgelehnte Frames wurden zugestellt: {received}"
    reasons = {r["reason"] for r in a.rejected_frames}
    assert "Envelope-Typ nicht erlaubt (kein Tool-Call-Pfad)" in reasons
    assert "fremde agent_id (Namespace-Verstoß)" in reasons
    assert "chat_id nicht in Geräte-Whitelist" in reasons

    # Erlaubter Frame geht durch:
    ws.inbox.append(_Msg("TEXT", json.dumps({
        "type": "chat.deliver", "v": 1, "msg_id": "m2", "agent_id": "agent-a",
        "chat_id": "device-1", "body": {"text": "guter Frame"}})))
    await asyncio.sleep(0.05)
    assert len(received) == 1 and received[0]["text"] == "guter Frame"

    await a.disconnect()
    print("OK Guards: tool_call + fremde Namespace + Fremd-Gerät + Müll-Frame abgelehnt, gut zugestellt")


@pytest.mark.asyncio
async def test_read_receipt_on_broker_message() -> None:
    """PH2-T11 (F2-S2): je empfangener Broker-Nachricht geht ein chat.read-
    Frame {chat_id, msg_id} zurück (Status=read im Broker).

    PH2-FIX (Jäger B4): read wird erst NACH der Verarbeitung bestätigt —
    beim Callback darf noch KEIN chat.read in den gesendeten Frames stehen."""
    a, ws = make_adapter({})
    received: list[dict] = []
    seen_types_at_callback: list[str] = []

    def _on_message(msg: dict) -> None:
        # Beweisfoto zum Callback-Zeitpunkt: read darf hier NOCH nicht draußen sein.
        seen_types_at_callback.extend(f["type"] for f in ws.sent)
        received.append(msg)

    a.message_callback = _on_message
    await a.connect()

    # Echter Relay-Broker-Frame: type=chat.send, Text oben (kein body).
    ws.inbox.append(_Msg("TEXT", json.dumps({
        "type": "chat.send", "v": 1, "msg_id": "broker-msg-7", "agent_id": "agent-a",
        "chat_id": "device-1", "text": "Nachricht vom Phone"})))
    await asyncio.sleep(0.05)

    assert len(received) == 1 and received[0]["text"] == "Nachricht vom Phone"
    assert "chat.read" not in seen_types_at_callback, (
        "B4-Verstoß: chat.read wurde VOR der Textverarbeitung gesendet"
    )
    reads = [f for f in ws.sent if f["type"] == "chat.read"]
    assert len(reads) == 1, f"erwartet genau 1 chat.read-Frame, got {reads}"
    assert reads[0]["chat_id"] == "device-1"
    assert reads[0]["msg_id"] == "broker-msg-7"
    assert reads[0]["agent_id"] == "agent-a"

    await a.disconnect()
    print("OK Read-Frame: chat.send → verarbeitet → DANACH chat.read {chat_id, msg_id}")


@pytest.mark.asyncio
async def test_activity_pair_and_elapsed_ms() -> None:
    """PH2-T11 (F2-S3): um die chat.reply-Antwort liegt ein chat.activity-Paar
    (working true → false) mit started_at = Empfangszeit; der Reply-Envelope
    trägt elapsed_ms (Empfang→Antwort-Send)."""
    import time as _time
    a, ws = make_adapter({})
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()

    received_at_before = int(_time.time() * 1000)
    ws.inbox.append(_Msg("TEXT", json.dumps({
        "type": "chat.send", "v": 1, "msg_id": "m9", "agent_id": "agent-a",
        "chat_id": "device-1", "text": "Frage"})))
    await asyncio.sleep(0.05)
    assert len(received) == 1

    reply = await a.send("device-1", "Antwort", reply_to="m9")
    assert reply.success
    types = [f["type"] for f in ws.sent]
    assert "chat.read" in types

    # Activity-Paar: genau EIN working=true VOR dem Reply, EIN false danach.
    acts = [f for f in ws.sent if f["type"] == "chat.activity"]
    assert [f["working"] for f in acts] == [True, False], f"Activity-Paar fehlt: {acts}"
    reply_idx = next(i for i, f in enumerate(ws.sent) if f["type"] == "chat.reply")
    act_true_idx = next(i for i, f in enumerate(ws.sent)
                        if f["type"] == "chat.activity" and f["working"] is True)
    assert act_true_idx < reply_idx, "working=true muss VOR dem chat.reply liegen"
    assert reply_idx < next(i for i, f in enumerate(ws.sent)
                            if f["type"] == "chat.activity" and f["working"] is False)
    # PH2-FIX (Jäger B4): Reihenfolge read → activity-true → reply → false —
    # der read kommt nach der Verarbeitung, aber BEVOR die Antwortarbeit
    # beginnt (sonst würde 'arbeitet' vor 'gelesen' angezeigt).
    read_idx = next(i for i, f in enumerate(ws.sent) if f["type"] == "chat.read")
    assert read_idx < act_true_idx, "chat.read muss VOR activity working=true kommen"

    # started_at = Empfangszeit (nicht Antwort-Zeit), elapsed_ms ≥ 0 messbar.
    for act in acts:
        assert act["started_at"] >= received_at_before, "started_at vor Empfang?"
        assert act["agent_id"] == "agent-a" and act["chat_id"] == "device-1"
    reply_frame = ws.sent[reply_idx]
    assert reply_frame["elapsed_ms"] >= 0 and isinstance(reply_frame["elapsed_ms"], int)

    await a.disconnect()
    print("OK Activity-Paar: true→reply→false mit started_at=Empfang + elapsed_ms im Envelope")


@pytest.mark.asyncio
async def test_send_failure_still_ends_activity() -> None:
    """PH2-FIX (Jäger B5): Schlägt der chat.reply-Send fehlt, feuert das
    finally TROTZDEM working=false — der Phone-Spinner ('arbeitet …')
    bleibt bei einem Sendefehler nicht dauerhaft stecken."""
    a, ws = make_adapter({})
    await a.connect()
    ws.fail_types = {"chat.reply"}  # nur der Reply-Send crasht

    result = await a.send("device-1", "Antwort die nicht rausgeht")
    assert not result.success, "Send mit crashender WS muss fehlschlagen"
    assert result.retryable

    acts = [f for f in ws.sent if f["type"] == "chat.activity"]
    assert [f["working"] for f in acts] == [True, False], (
        f"Activity-Paar muss auch bei Sendefehler komplett sein, got {acts}"
    )
    assert all(f["agent_id"] == "agent-a" and f["chat_id"] == "device-1" for f in acts)

    await a.disconnect()
    print("OK Sendefehler: working=false kommt im finally trotzdem (kein Stuck-Spinner)")


# ═════════════════════════════════════════════════════════════════════════════
# PH-3 (Welle 1): E2E-Crypto — Handshake, Ratchet, Replay, Fingerprint-Abgleich
# (Sirs Revision (iii)). Die Phone-Gegenstelle ist die Referenz-Implementierung
# PhoneCryptoPeer aus adapter.py (byte-exakte Vorlage für Kotlin Welle 2).
# ═════════════════════════════════════════════════════════════════════════════

def _make_crypto_adapter(tmp_path, env_extra=None):
    """Adapter mit isoliertem Identity-File (0600, nie im Home-Verzeichnis)."""
    env = {"FLIPSI_IDENTITY_FILE": str(tmp_path / "agent-identity.json")}
    if env_extra:
        env.update(env_extra)
    return make_adapter(env)


async def _establish_session(a, ws, session_id="ph3-test-session-0001"):
    """Handshake-Roundtrip: hello → Adapter → ack → Phone verifiziert."""
    from adapter import PhoneCryptoPeer
    identity = a._identity
    assert identity is not None, "Adapter muss Identity-Keys haben"
    peer = PhoneCryptoPeer(a.agent_id, identity["identity_pub"], identity["prekey_pub"])
    hello = peer.make_hello(session_id)
    a._dispatch_frame(hello)
    await a.drain_bg_tasks()
    # LETZTES Ack nehmen (bei Session-Rotates stehen mehrere in ws.sent).
    ack = next((f for f in reversed(ws.sent) if f["type"] == "crypto.hello_ack"), None)
    assert ack is not None, "hello_ack fehlt"
    assert ack["session_id"] == session_id
    peer.consume_ack(ack)
    assert peer.fingerprint == a._crypto_session.fingerprint, "Fingerprint ungleich"
    return peer


def _phone_frame(peer, frame_type, chat_id, payload, clear=None, msg_id=None):
    """Enc-Frame im Relay-Format (chat.send.enc: msg_id bleibt klar)."""
    frame = peer.encrypt_frame(frame_type, chat_id, payload, clear=clear)
    if msg_id is not None:
        frame["msg_id"] = msg_id
    return frame


@pytest.mark.asyncio
async def test_ph3_handshake_roundtrip_and_fingerprint() -> None:
    """T4: Handshake-Roundtrip hello→ack, beide Seiten leiten denselben
    Session-Key ab (Fingerprint-Gleichheit), danach E2E-Verkehr beide Wege."""
    import tempfile
    import pathlib
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    assert a._identity is not None

    peer = await _establish_session(a, ws)
    assert a._crypto_session is not None
    assert a._crypto_session.session_id == "ph3-test-session-0001"

    # Phone → Agent (verschlüsselt): Text kommt NUR im ct an und wird intern
    # entschlüsselt — der Adapter pusht Klartext in die Pipeline.
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "device-1",
                     {"text": "Geheime Frage", "msg_id": "q1"}, msg_id="q1"))))
    await asyncio.sleep(0.05)
    assert len(received) == 1 and received[0]["text"] == "Geheime Frage"

    # Agent → Phone: send() erzeugt chat.reply.enc (kein Klartext-Envelope).
    reply = await a.send("device-1", "Geheime Antwort", reply_to="q1")
    assert reply.success
    enc_reply = [f for f in ws.sent if f["type"] == "chat.reply.enc"]
    assert enc_reply, "send() muss bei aktiver Session chat.reply.enc senden"
    assert "text" not in enc_reply[-1], "Klartext-Feld im enc-Envelope!"
    assert peer.decrypt_frame(enc_reply[-1])["text"] == "Geheime Antwort"

    # read/activity sind ebenfalls enc-Varianten (Ratchet-Kette zählt JEDE).
    reads = [f for f in ws.sent if f["type"] == "chat.read.enc"]
    assert reads and reads[0]["msg_id"] == "q1"  # msg_id bleibt klar (PH2-Logik)
    assert peer.decrypt_frame(reads[0])["msg_id"] == "q1"
    acts = [f for f in ws.sent if f["type"] == "chat.activity.enc"]
    assert [f["working"] for f in acts] == [True, False]
    assert peer.decrypt_frame(acts[0])["working"] is True

    await a.disconnect()
    print("OK PH-3: Handshake-Roundtrip, Fingerprint gleich, E2E beide Richtungen, "
          "read/activity enc mit klaren Metadaten")


@pytest.mark.asyncio
async def test_ph3_fingerprint_chat_check_ok() -> None:
    """Sirs Revision (iii): 'FP-ABGLEICH: <hex>' mit RICHTIGEM Fingerprint →
    Auto-Antwort 'Fingerprint-Abgleich: OK — Code ist gleich', Session bleibt."""
    import tempfile
    import pathlib
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    peer = await _establish_session(a, ws)

    fp_msg = f"FP-ABGLEICH: {peer.fingerprint.upper()}"
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "device-1",
                     {"text": fp_msg, "msg_id": "fp1"}, msg_id="fp1"))))
    await asyncio.sleep(0.05)
    await a.drain_bg_tasks()

    # Protokoll-Nachricht: NICHT an den Agenten (Gateway) weitergereicht …
    assert received == [], f"FP-Nachricht darf nicht zustellt werden: {received}"
    # … sondern automatisch beantwortet (verschlüsselt, exakter Sir-Text).
    replies = [f for f in ws.sent if f["type"] == "chat.reply.enc"]
    assert replies, "FP-Abgleich muss auto-antworten"
    assert peer.decrypt_frame(replies[-1])["text"] == \
        "Fingerprint-Abgleich: OK — Code ist gleich"
    assert a._crypto_session is not None, "OK-Abgleich darf die Session NICHT killen"
    assert a._session_reset_pending is False
    resets = [f for f in ws.sent if f["type"] == "crypto.session_reset"]
    assert not resets
    await a.disconnect()
    print("OK PH-3: FP-ABGLEICH korrekt → OK-Antwort, Session bleibt aktiv")


@pytest.mark.asyncio
async def test_ph3_fingerprint_chat_check_mismatch_invalidates_session() -> None:
    """Sirs Revision (iii): ABWEICHENDER Fingerprint → FEHLGESCHLAGEN-Antwort +
    Session invalidiert (Warn + Reset-Flag) + crypto.session_reset ans Relay."""
    import tempfile
    import pathlib
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    peer = await _establish_session(a, ws)
    session_id = a._crypto_session.session_id

    wrong_fp = "ab" * 32  # 64 Hex, aber garantiert fremd
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "device-1",
                     {"text": f"FP-ABGLEICH: {wrong_fp}", "msg_id": "fp2"},
                     msg_id="fp2"))))
    await asyncio.sleep(0.05)
    await a.drain_bg_tasks()

    assert received == [], "FP-Nachricht darf nicht zugestellt werden"
    replies = [f for f in ws.sent if f["type"] == "chat.reply.enc"]
    assert replies and peer.decrypt_frame(replies[-1])["text"] == (
        "Fingerprint-Abgleich: FEHLGESCHLAGEN — Abweichung! "
        "Mögliches Man-in-the-Middle. Session sofort ungültig!")
    # Session ist WIRKLICH weg + Reset-Flag gesetzt + Relay informiert.
    assert a._crypto_session is None, "Abweichung muss die Session invalidieren"
    assert a._session_reset_pending is True
    resets = [f for f in ws.sent if f["type"] == "crypto.session_reset"]
    assert resets and resets[0]["session_id"] == session_id
    assert resets[0]["reason"] == "fingerprint-mismatch"
    # Danach sendet der Adapter wieder Klartext (Legacy) — keine enc-Spuren.
    ok = await a.send("device-1", "nach Reset")
    assert ok.success
    assert any(f["type"] == "chat.reply" and f["body"]["text"] == "nach Reset"
               for f in ws.sent), "nach Invalidierung muss Legacy-Pfad greifen"
    await a.disconnect()
    print("OK PH-3: FP-ABWEICHUNG → FEHLGESCHLAGEN-Antwort, Session invalidiert, "
          "Reset-Frame + Flag, danach Legacy")


@pytest.mark.asyncio
async def test_ph3_replay_blocked_with_constant_delay() -> None:
    """T4: Replay-Block — alter Frame wird verworfen UND konstant verzögert
    (≥0,25 s, kein Timing-Orakel); Duplikat kommt NIE in die Pipeline."""
    import tempfile
    import time as _time
    import pathlib
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    peer = await _establish_session(a, ws)

    frame = _phone_frame(peer, "chat.send.enc", "device-1",
                         {"text": "nur einmal", "msg_id": "r1"}, msg_id="r1")
    ws.inbox.append(_Msg("TEXT", json.dumps(frame)))
    await asyncio.sleep(0.05)
    assert len(received) == 1

    t0 = _time.monotonic()
    ws.inbox.append(_Msg("TEXT", json.dumps(frame)))  # Injection: alter Frame
    await asyncio.sleep(0.4)
    dt = _time.monotonic() - t0
    assert len(received) == 1, "Replay wurde zugestellt — Fenster gebrochen!"
    assert dt >= 0.24, f"konstante Verzögerung fehlt ({dt:.2f}s)"
    reasons = [r["reason"] for r in a.rejected_frames]
    assert any("Replay" in r for r in reasons), reasons
    await a.disconnect()
    print(f"OK PH-3: Replay verworfen + konstant verzögert ({dt:.2f}s), kein Leak")


@pytest.mark.asyncio
async def test_ph3_wrong_key_frame_dropped_without_plaintext_log(caplog) -> None:
    """T4: Wrong-Key/Manipulation — GCM-Tag scheitert → Frame verworfen, der
    Adapter crasht nicht, und in KEINEM Log steht Klartext oder Key-Material."""
    import tempfile
    import time as _time
    import base64 as _b64
    import pathlib
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    peer = await _establish_session(a, ws)

    frame = _phone_frame(peer, "chat.send.enc", "device-1",
                         {"text": "TOPSECRET-CONTENT", "msg_id": "w1"}, msg_id="w1")
    ct = bytearray(_b64.b64decode(frame["ct"]))
    ct[0] ^= 0xFF  # Tag brechen
    frame["ct"] = _b64.b64encode(bytes(ct)).decode()
    import logging
    with caplog.at_level(logging.DEBUG, logger="flipsibridge.chat"):
        t0 = _time.monotonic()
        ws.inbox.append(_Msg("TEXT", json.dumps(frame)))
        await asyncio.sleep(0.45)
        dt = _time.monotonic() - t0
    assert received == [], "Manipulierter Frame wurde zugestellt!"
    assert dt >= 0.24, "Entschlüsselungs-Fehlschlag braucht die konstante Verzögerung"
    dumped = "\n".join(r.getMessage() for r in caplog.records)
    assert "TOPSECRET-CONTENT" not in dumped, "Klartext im Log!"
    assert a._identity["identity_priv"] not in dumped, "Private Key im Log!"
    await a.disconnect()
    print("OK PH-3: Wrong-Key → Drop + Delay, kein Klartext/Key im Log")


@pytest.mark.asyncio
async def test_ph3_ratchet_rotates_every_n_and_both_sides_follow() -> None:
    """T4: Ratchet-Kette je N — mit rotate_every=3 wechselt der Ratchet-Key
    mehrfach; beide Seiten folgen (jede Frame entschlüsselbar), alternierender
    Verkehr in beide Richtungen."""
    import tempfile
    import pathlib
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp, {"FLIPSI_RATCHET_ROTATE": "3"})
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    peer = await _establish_session(a, ws)

    agent_keys = set()
    sent_before = len(ws.sent)
    for i in range(9):
        # Agent → Phone (je send() 3 enc-Frames: activity×2 + reply) — nur die
        # NEUEN Frames je Runde entschlüsseln (alte sind längst konsumiert).
        await a.send("device-1", f"agent-nachricht-{i}")
        for f in ws.sent[sent_before:]:
            peer.decrypt_frame(f)  # jede einzelne muss aufgehen
            agent_keys.add(f["hdr"]["rk"])
        sent_before = len(ws.sent)
        # Phone → Agent
        ws.inbox.append(_Msg("TEXT", json.dumps(
            _phone_frame(peer, "chat.send.enc", "device-1",
                         {"text": f"phone-{i}", "msg_id": f"p{i}"}, msg_id=f"p{i}"))))
        await asyncio.sleep(0.03)
    assert len(received) == 9, "alle Phone-Frames müssen durchgehen"
    assert len(agent_keys) >= 6, (
        f"Rotation je N=3 zu träge: {len(agent_keys)} Ratchet-Keys über 27 Frames")
    await a.disconnect()
    print(f"OK PH-3: DH-Rotation je N=3 ({len(agent_keys)} Keys, 27 enc-Frames, beide Wege)")


@pytest.mark.asyncio
async def test_ph3_identity_file_0600_and_no_key_in_logs(tmp_path, caplog) -> None:
    """T2: Identity-File wird 0600 erzeugt, bleibt stabil (gleiche Keys beim
    Reconnect) und Private Keys tauchen in KEINEM Log auf."""
    import logging
    import stat
    env = {"FLIPSI_IDENTITY_FILE": str(tmp_path / "ident" / "agent-identity.json")}
    a, ws = make_adapter(env)
    await a.connect()
    path = str(tmp_path / "ident" / "agent-identity.json")
    assert os.stat(path).st_mode & 0o777 == 0o600, "Key-File muss 0600 sein"
    ident = a._identity
    for field in ("identity_priv", "identity_pub", "prekey_priv", "prekey_pub"):
        assert isinstance(ident[field], str) and len(ident[field]) == 64

    # Reconnect lädt dieselbe Identität (Fingerprint-Stabilität).
    a2, ws2 = make_adapter(env)
    await a2.connect()
    assert a2._identity == ident, "Identity muss persistent sein"
    await a2.disconnect()

    with caplog.at_level(logging.DEBUG):
        await a.disconnect()
    dumped = "\n".join(r.getMessage() for r in caplog.records)
    assert ident["identity_priv"] not in dumped and ident["prekey_priv"] not in dumped, \
        "Private Key im Log!"
    print("OK PH-3: Identity-File 0600, persistent, Keys nie geloggt")


@pytest.mark.asyncio
async def test_ph3_legacy_mode_without_session() -> None:
    """Kompatibilität (v0.14-Migration §3.2): ohne E2E-Session läuft der
    Adapter im Klartext-Legacy-Pfad; enc-Frames ohne Session werden verworfen."""
    import tempfile
    import pathlib
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()

    # Legacy-Reply ohne Session: klassischer Klartext-Envelope.
    result = await a.send("device-1", "legacy")
    assert result.success
    assert any(f["type"] == "chat.reply" for f in ws.sent)

    # enc-Frame OHNE Session = fail-closed (kein Sturz, kein Klartext): echte
    # Session aufbauen, Frame bauen, dann die Adapter-Session wegwerfen.
    from adapter import PhoneCryptoPeer
    peer = await _establish_session(a, ws, session_id="weg-damit-000001")
    frame = _phone_frame(peer, "chat.send.enc", "device-1",
                         {"text": "ohne session", "msg_id": "x"}, msg_id="x")
    a._crypto_session = None  # simulierter Session-Verlust vor Zustellung
    ws.inbox.append(_Msg("TEXT", json.dumps(frame)))
    await asyncio.sleep(0.3)  # Delay der Fehlverarbeitung abwarten
    assert received == [], "enc ohne Session wurde zugestellt!"
    # SIRS-01 (P0): Der Session-Verlust mit kryptofähigem Adapter ist kein
    # stiller Verwurf mehr — der Reset-Frame (Neu-Handshake-Anstoß) MUSS raus;
    # der Frame selbst bleibt fail-closed unzugestellt.
    resets = [f for f in ws.sent if f["type"] == "crypto.session_reset"]
    assert resets, "SIRS-01: Session-Verlust muss crypto.session_reset senden"
    assert resets[-1]["reason"] == "agent-session-lost"
    assert any("nicht bekannt (Agent-Neustart)" in r["reason"]
               for r in a.rejected_frames)
    await a.disconnect()
    print("OK PH-3: Legacy ohne Session intakt, enc ohne Session fail-closed "
          "UND SIRS-01-Reset (kein stilles Verwerfen mehr)")


@pytest.mark.asyncio
async def test_ph3_cross_session_replay_blocked() -> None:
    """T4: Cross-Session-Replay — ein Frame mit der session_id einer ALTEN
    Session wird auch dann verworfen, wenn er kryptographisch intakt wäre."""
    import tempfile
    import pathlib
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    peer = await _establish_session(a, ws, session_id="alt-session-00000001")
    old_frame = _phone_frame(peer, "chat.send.enc", "device-1",
                             {"text": "alte session", "msg_id": "old"}, msg_id="old")

    # Neue Session (Rotate) — der alte Frame gehört zur alten session_id.
    peer2 = await _establish_session(a, ws, session_id="neu-session-00000002")
    ws.inbox.append(_Msg("TEXT", json.dumps(old_frame)))
    await asyncio.sleep(0.3)
    assert received == [], "Cross-Session-Replay wurde zugestellt!"
    assert any("session_id" in r["reason"] for r in a.rejected_frames)
    # Und der NEUE Kanal funktioniert weiter.
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer2, "chat.send.enc", "device-1",
                     {"text": "neu ok", "msg_id": "n1"}, msg_id="n1"))))
    await asyncio.sleep(0.05)
    assert len(received) == 1 and received[0]["text"] == "neu ok"
    await a.disconnect()
    print("OK PH-3: Cross-Session-Replay geblockt, neue Session unbeeindruckt")


@pytest.mark.asyncio
async def test_ph3_invalid_hello_rejected() -> None:
    """Fail-closed: kaputtes crypto.hello (Müll-Keys) wird verworfen, der
    Adapter bleibt verbunden und OHNE Session (Legacy bleibt nutzbar)."""
    import tempfile
    import pathlib
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp)
    await a.connect()
    bad = {"type": "crypto.hello", "v": 1, "session_id": "kurz",
           "agent_id": "agent-a", "ik_pub": "xx", "ek_pub": "yy", "spk_pub": "zz"}
    a._dispatch_frame(bad)
    await a.drain_bg_tasks()
    assert a._crypto_session is None, "kaputtes hello darf keine Session bauen"
    assert any(r["type"] == "crypto.hello" for r in a.rejected_frames)
    assert not any(f["type"] == "crypto.hello_ack" for f in ws.sent)
    await a.disconnect()
    print("OK PH-3: kaputtes hello verworfen, kein Ack, kein Crash")


def main() -> int:
    assert not check_flipsibridge_requirements() or True  # aiohttp optional für Mock-Pfad
    raise SystemExit(asyncio.run(_main()))


async def _main() -> int:
    await test_pinning()
    await test_connect_register()
    await test_send_and_receive_roundtrip()
    await test_inbound_guards()
    await test_read_receipt_on_broker_message()
    await test_activity_pair_and_elapsed_ms()
    await test_send_failure_still_ends_activity()
    print("\nALLE 7 LEGACY-TESTGRUPPEN GRÜN — Adapter-Skeleton roundtrip-verifiziert (Mock-WS)")
    print("PH-3-Gruppen laufen über pytest (pytest-asyncio), siehe test_adapter.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))

# ── SEC-W1-Abschluss: Adapter-Härtung (F-04-Rest, W1C-6, W1A-5, F-08, D3b) ─


@pytest.mark.asyncio
async def test_w1c6_fp_reply_mismatch_keeps_newly_established_session() -> None:
    """SEC-W1 W1C-6: Der await-Slice im _fp_reply kann einen NEUEN crypto.hello
    durchlassen — das bedingungslose `self._crypto_session = None` killte die
    frische Session still. Identity-Guard wie _send_ack: NUR die alte Session
    darf invalidiert werden."""
    import tempfile
    import pathlib
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    peer = await _establish_session(a, ws)
    old_session = a._crypto_session

    wrong_fp = "ab" * 32  # 64 Hex, garantiert fremd → _fp_reply(MISMATCH)
    orig_send = a.send

    async def rotating_send(chat_id, text, **kw):
        # WÄHREND des _fp_reply-awaits rotiert das Phone auf eine NEUE Session
        # (genau das PoC-Interleaving aus SECURITY-REPORT-W1-C).
        await _establish_session(a, ws, session_id="ph3-rotate-during-fp")
        return await orig_send(chat_id, text, **kw)

    a.send = rotating_send
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "device-1",
                     {"text": f"FP-ABGLEICH: {wrong_fp}", "msg_id": "fp-x"}, msg_id="fp-x"))))
    await asyncio.sleep(0.05)
    await a.drain_bg_tasks()

    new_session = a._crypto_session
    assert new_session is not None, "W1C-6: neue Session wurde still genullt!"
    assert new_session is not old_session, "Testaufbau: Rotation muss greifen"
    assert new_session.session_id == "ph3-rotate-during-fp"
    # Reset-Frame ging raus (Mismatch bleibt ein Reset-Ereignis) …
    resets = [f for f in ws.sent if f["type"] == "crypto.session_reset"]
    assert resets and resets[0]["session_id"] == old_session.session_id
    await a.disconnect()
    print("OK SEC-W1 W1C-6: FP-Mismatch-Invalidierung trifft NUR die alte Session")


@pytest.mark.asyncio
async def test_w1a5_tofu_pin_first_sightings_and_change_warning(caplog) -> None:
    """SEC-W1 W1A-5: relay-keys.json hatte am Agenten NULL Consumer — jeder
    X3DH-Hello galt als 'das Phone'. Jetzt: erster Handshake pint ik_pub
    (TOFU), gleiche ik_pub still ok, WECHSEL = laute Warnung."""
    import logging
    import tempfile
    import pathlib
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp)
    await a.connect()
    with caplog.at_level(logging.INFO):
        peer = await _establish_session(a, ws)
    # Pin-Datei existiert und hält die Phone-ik_pub …
    pin_path = pathlib.Path(a.phone_pin_file)
    assert pin_path.exists(), "Erster Handshake muss den TOFU-Pin schreiben"
    pin = json.loads(pin_path.read_text(encoding="utf-8"))
    assert pin["ik_pub"] == peer.ik_pub.hex()
    assert any("Phone-Identity gepinnt" in r.message for r in caplog.records)

    # … gleiche ik_pub = still OK (keine Warnung) …
    with caplog.at_level(logging.WARNING):
        a._check_phone_identity_pin({"ik_pub": peer.ik_pub.hex()})
    assert not any("PHONE-IDENTITY-WECHSEL" in r.message for r in caplog.records)

    # … WECHSEL = laute Warnung (Neu-Gerät ODER Übernahme).
    with caplog.at_level(logging.WARNING):
        a._check_phone_identity_pin({"ik_pub": "cd" * 32})
    assert any("PHONE-IDENTITY-WECHSEL" in r.message for r in caplog.records)
    await a.disconnect()
    print("OK SEC-W1 W1A-5: TOFU-Pin + Wechsel-Warnung")


@pytest.mark.asyncio
async def test_w1b_f04_nan_ts_rejected_in_decrypt() -> None:
    """SEC-W1 B F-04 (Adapter-Rest): NaN fiel durch isinstance + Vergleich
    (NaN < cutoff = False) — Freshness deaktivierbar. Jetzt isfinite-Pflicht."""
    import tempfile
    import pathlib
    from adapter import CryptoFrameError
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp)
    await a.connect()
    peer = await _establish_session(a, ws)
    frame = peer.encrypt_frame("chat.send.enc", "device-1", {"text": "x", "msg_id": "n1"})
    frame["ts"] = float("nan")
    with pytest.raises(CryptoFrameError, match="ts nicht endlich"):
        a._crypto_session.decrypt_frame(frame)
    # Inf gleichermaßen verboten …
    frame["ts"] = float("inf")
    with pytest.raises(CryptoFrameError, match="ts nicht endlich"):
        a._crypto_session.decrypt_frame(frame)
    # … und der unveränderte Frame läuft weiter durch.
    good = peer.encrypt_frame("chat.send.enc", "device-1", {"text": "ok", "msg_id": "n2"})
    assert a._crypto_session.decrypt_frame(good)["text"] == "ok"
    await a.disconnect()
    print("OK SEC-W1 B F-04: NaN/inf-ts am Adapter verworfen")


def test_w1b_f08_pinning_tautology_warning(caplog) -> None:
    """SEC-W1 B F-08: Ohne FLIPSI_ALLOWED_URLS pinnt jede URL gegen sich
    selbst — jetzt laute Warnung; mit expliziter Whitelist still."""
    import logging
    with caplog.at_level(logging.WARNING):
        make_adapter({})  # kein FLIPSI_ALLOWED_URLS → Tautologie-Warnung
    assert any("FLIPSI_ALLOWED_URLS nicht gesetzt" in r.message for r in caplog.records)
    caplog.clear()
    with caplog.at_level(logging.WARNING):
        make_adapter({"FLIPSI_ALLOWED_URLS": RELAY})
    assert not any("FLIPSI_ALLOWED_URLS nicht gesetzt" in r.message for r in caplog.records)
    print("OK SEC-W1 B F-08: Pinning-Tautologie warnt laut")


def test_w1d_d3b_fchmod_unavailable_warns(tmp_path, caplog, monkeypatch) -> None:
    """SEC-W1 D3b: silently-pass bei fehlendem fchmod ließ Bestands-Rechte
    (0644, Private Keys!) unrepariert — jetzt laute Warnung."""
    import logging
    import adapter as _adapter_mod
    from adapter import load_or_create_identity

    def _no_fchmod(fd, mode):
        raise AttributeError("fchmod unavailable (Windows path)")
    monkeypatch.setattr(_adapter_mod.os, "fchmod", _no_fchmod)
    path = str(tmp_path / "agent-identity.json")
    with caplog.at_level(logging.WARNING):
        identity = load_or_create_identity(path)
    assert identity["identity_pub"]
    assert any("fchmod nicht verfügbar" in r.message for r in caplog.records)
    print("OK SEC-W1 D3b: fchmod-Verfügbarkeits-Warnung")


# ═════════════════════════════════════════════════════════════════════════════
# SEC-W2 (Welle 2) — Adapter-Hygiene-Regressionstests (W2-3)
# ═════════════════════════════════════════════════════════════════════════════

def test_w2_rejected_frames_capped(tmp_path, monkeypatch) -> None:
    """SEC-W2 W2-3: rejected_frames ist FIFO-gecappt (deque maxlen 256) —
    vorher wuchs die Diagnose-Liste unbegrenzt (RAM im Agent-Prozess)."""
    a, _ = make_adapter({})
    big_type = "J" * 100000
    for _ in range(400):
        a._dispatch_frame({"type": big_type, "agent_id": "agent-a", "chat_id": "c1"})
    assert len(a.rejected_frames) == 256, (
        f"rejected_frames muss bei 256 gedeckelt sein, ist {len(a.rejected_frames)}")


def test_w2_rejected_type_truncated_in_log_and_diag(tmp_path, caplog) -> None:
    """SEC-W2 W2-3: Frame-Typ wird im Log UND im Diagnose-Eintrag gekürzt —
    %r eines 100k-Zeichen-Typs schrieb vorher Meter-Log-Zeilen."""
    import logging
    a, _ = make_adapter({})
    with caplog.at_level(logging.WARNING):
        a._dispatch_frame({"type": "X" * 100000, "agent_id": "agent-a", "chat_id": "c1"})
    junk = [r for r in caplog.records if "Frame-Type" in r.message]
    assert junk, "Junk-Frame muss weiter geloggt werden (nur gekürzt)"
    assert all(len(r.getMessage()) < 500 for r in junk), "Log-Zeile muss gekürzt sein"
    assert all(len(e["type"]) <= 64 for e in a.rejected_frames), (
        "Diagnose-Eintrag muss den Typ kürzen")


def test_w2_phone_pin_write_atomic_no_temp_leftovers(tmp_path) -> None:
    """SEC-W2 W2-3: Phone-Pin-Write ist atomar (mkstemp + os.replace, Parität
    zu W1 D3a) — kein O_TRUNC auf der Bestandsdatei, keine Temp-Reste."""
    import json as _json
    pin_path = str(tmp_path / "phone-pin.json")
    a, _ = make_adapter({})
    a.phone_pin_file = pin_path
    a._write_phone_pin("ab" * 32)
    first = _json.loads(open(pin_path, encoding="utf-8").read())
    assert first["ik_pub"] == "ab" * 32
    # Zweiter Write (Pin-Wechsel) ersetzt atomar den Bestand:
    a._write_phone_pin("cd" * 32)
    second = _json.loads(open(pin_path, encoding="utf-8").read())
    assert second["ik_pub"] == "cd" * 32
    # Keine Temp-Reste im Verzeichnis:
    leftovers = [f for f in os.listdir(tmp_path) if f.startswith(".phone-pin-")]
    assert not leftovers, f"Temp-Reste vom Pin-Write: {leftovers}"
    if os.name == "posix":
        mode = os.stat(pin_path).st_mode & 0o777
        assert mode == 0o600, f"Pin-Datei muss 0600 sein, ist {oct(mode)}"


def test_w2_phone_pin_write_failure_leaves_old_file(tmp_path, monkeypatch) -> None:
    """SEC-W2 W2-3: Schreib-Fehler MITTLERES Schreibens lässt den Alt-Bestand
    unangetastet (kein geleerter Pin → kein TOFU-Re-Pin nach Crash)."""
    import json as _json
    import adapter as _adapter_mod
    pin_path = str(tmp_path / "phone-pin.json")
    a, _ = make_adapter({})
    a.phone_pin_file = pin_path
    a._write_phone_pin("ab" * 32)

    real_replace = _adapter_mod.os.replace
    def _boom(src, dst):
        raise OSError("replace boom (Crash-Fenster)")
    monkeypatch.setattr(_adapter_mod.os, "replace", _boom)
    try:
        a._write_phone_pin("cd" * 32)
        raised = False
    except OSError:
        raised = True
    monkeypatch.setattr(_adapter_mod.os, "replace", real_replace)
    assert raised, "replace-Fehler muss propagieren (fail-closed sichtbar)"
    # Alt-Bestand unangetastet:
    still = _json.loads(open(pin_path, encoding="utf-8").read())
    assert still["ik_pub"] == "ab" * 32, "Alt-Pin darf bei Write-Fehler nicht leeren"
    # Temp-Datei des Fehlversuchs aufgeräumt:
    leftovers = [f for f in os.listdir(tmp_path) if f.startswith(".phone-pin-")]
    assert not leftovers, f"Temp-Reste nach Fehler: {leftovers}"


# ═════════════════════════════════════════════════════════════════════════════
# SIRS-Fix-Welle (Volltest 03.11.2026): PoC-Verträge scratch/sirs_volltest/poc/
# ═════════════════════════════════════════════════════════════════════════════

@pytest.mark.asyncio
async def test_sirs01_agent_restart_lost_session_triggers_reset_and_rehandshake() -> None:
    """SIRS-01 (P0, PoC-Vertrag SIRS-01-agent-restart-session-loss.md): Agent-
    Prozess-Restart (Identity bleibt, In-Memory-Session WEG) — das Phone
    sendet weiter chat.send.enc auf der ALTEN Session.

    ALT (red): Adapter verwarf STILL ('ohne aktive E2E-Session'), das Phone
    zeigte '✓✓ Zugestellt' + E2E-Badge weiter, der Chat war stumm tot
    (Volltest agent-file.log 08:54/08:55).
    NEU (Vertrag): SOFORT crypto.session_reset (reason='agent-session-lost',
    session_id aus dem eingehenden Frame) ans Relay → das Phone verwirft die
    SessionKeys und stößt einen Neu-Handshake an; nach dem frischen
    crypto.hello ist der Verkehr wieder E2E zustellbar."""
    import tempfile
    import pathlib
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()

    # Vorher: etablierte Session (wie vor dem Agent-Restart) …
    peer = await _establish_session(a, ws)
    old_session_id = a._crypto_session.session_id
    # … Agent-Restart: neuer Prozess, Session-Wissen WEG (Identity bleibt).
    a._crypto_session = None
    a._session_reset_pending = False

    # Phone sendet weiter auf der ALTEN Session (Volltest-Szenario).
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "device-1",
                     {"text": "nach Agent-Restart", "msg_id": "s1"}, msg_id="s1"))))
    await asyncio.sleep(0.05)
    await a.drain_bg_tasks()

    # Ohne Session KEINE Zustellung (weiterhin fail-closed) …
    assert received == [], "ohne Session darf nicht zugestellt werden"
    # … ABER kein stiller Tod: Diagnose-Eintrag + SOFORT Reset-Frame.
    assert any("nicht bekannt (Agent-Neustart)" in r["reason"]
               for r in a.rejected_frames), \
        "Verwurf muss als Agent-Neustart-Fall diagnostiziert sein"
    resets = [f for f in ws.sent if f["type"] == "crypto.session_reset"]
    assert resets, "SIRS-01: unbekannte Session MUSS crypto.session_reset senden"
    assert resets[-1]["session_id"] == old_session_id
    assert resets[-1]["reason"] == "agent-session-lost"
    assert a._session_reset_pending is True

    # Dedup: zweiter Frame auf derselben toten Session → KEIN Reset-Sturm.
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "device-1",
                     {"text": "zweite Nachricht", "msg_id": "s2"}, msg_id="s2"))))
    await asyncio.sleep(0.05)
    await a.drain_bg_tasks()
    assert len([f for f in ws.sent if f["type"] == "crypto.session_reset"]) == 1, \
        "Reset-Pending muss aufeinanderfolgende tote Frames deduplizieren"

    # Neu-Handshake (das Phone startet ihn nach dem Reset) → alles geheilt:
    # Pending-Flag weg, neuer Verkehr E2E zustellbar.
    peer2 = await _establish_session(a, ws, session_id="ph3-after-restart-0002")
    assert a._session_reset_pending is False
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer2, "chat.send.enc", "device-1",
                     {"text": "frische Session", "msg_id": "s3"}, msg_id="s3"))))
    await asyncio.sleep(0.05)
    assert received and received[-1]["text"] == "frische Session", \
        "nach Neu-Handshake muss enc-Verkehr wieder zustellbar sein"
    await a.disconnect()
    print("OK SIRS-01: unbekannte Session → session_reset(agent-session-lost) "
          "+ Dedup + Heilung per Neu-Handshake")
@pytest.mark.asyncio
async def test_sirs03_fingerprint_check_sends_read_receipt() -> None:
    """SIRS-03 (Klein, Volltest): Die FP-ABGLEICH-Auto-Antwort kam an, aber der
    Chip der eigenen Nachricht blieb bei '✓✓ Zugestellt' — der Agent
    quittierte den Abgleich nie als GELESEN (Protokoll-Pfad ohne chat.read).

    Vertrag: Der FP-Abgleich ist eine echte Lese-Verarbeitung → Read-Receipt
    (chat.read.enc bei aktiver Session) mit derselben msg_id → der Phone-Chip
    nimmt den Antwort-Zustand ('✓✓ Gelesen') an."""
    import tempfile
    import pathlib
    tmp = pathlib.Path(tempfile.mkdtemp())
    a, ws = _make_crypto_adapter(tmp)
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    peer = await _establish_session(a, ws)

    fp_msg = f"FP-ABGLEICH: {peer.fingerprint.upper()}"
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "device-1",
                     {"text": fp_msg, "msg_id": "fp-read-1"}, msg_id="fp-read-1"))))
    await asyncio.sleep(0.05)
    await a.drain_bg_tasks()

    # Antwort kam (bestehender Vertrag) …
    replies = [f for f in ws.sent if f["type"] == "chat.reply.enc"]
    assert replies, "FP-Abgleich muss auto-antworten"
    # … NEU: die eigene Nachricht wird auch als GELESEN quittiert.
    reads = [f for f in ws.sent if f["type"] == "chat.read.enc"]
    assert reads, "SIRS-03: FP-Abgleich MUSS eine Lese-Bestätigung feuern"
    assert reads[-1]["msg_id"] == "fp-read-1", "msg_id des FP-Frames quittieren"
    assert peer.decrypt_frame(reads[-1])["msg_id"] == "fp-read-1"
    assert a._crypto_session is not None, "OK-Abgleich darf die Session NICHT killen"
    await a.disconnect()
    print("OK SIRS-03: FP-ABGLEICH → Auto-Antwort + Lese-Bestätigung (Chip 'Gelesen')")
