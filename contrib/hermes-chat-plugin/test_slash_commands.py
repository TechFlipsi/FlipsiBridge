#!/usr/bin/env python3
"""W4-B2-Tests: adapter-gesteuerte Slash-Befehle (/new /status /help).

Verträge (WELLE4-BACKEND-PLAN §B, Suite-Ziel Plugin-Ordner):
- Parser im Nach-Dekrypt/Fp-Gate-Verband, VOR der Gateway-Zustellung.
- /new = 1:1-Pass-through an den Gateway (reset_triggers + destructive-
  Confirm-Gate = Bestätigung; Gateway-Antwort = Bestätigungs-Bubble der App);
  KEINE Adapter-Bubble, KEIN crypto.session_reset (kein Ratchet-Churn).
- /status = Status-Nachricht aus Adapter-internen Quellen (ehrlich, Deutsch).
- /help = Befehlsliste (_HELP_TEXT).
- JEDE lokal verarbeitete Eingabe sendet zuerst den Read-Receipt (SIRS-03).
- JEDE andere '/…'-Eingabe = keine Befehlsfläche → normal senden
  (false-positive-Verband).
- Scope-Guard: Parser nur im FlipsiBridge-Adapter; _SLASH_COMMANDS = unver-
  änderliche frozenset-Modulkonstante; fremde agent_id erreicht den Parser nie.
Kein Live-Relay nötig (Mock-WS, Skeleton-Pfad wie test_adapter.py).
"""

import asyncio
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.modules["gateway"] = None  # Skeleton-Pfad erzwingen (Fallback-Base)

from adapter import (  # noqa: E402
    FlipsiBridgeChatAdapter, _HELP_TEXT, _SLASH_COMMANDS)

from test_adapter import (  # noqa: E402,F401
    _Msg, _establish_session, _make_crypto_adapter, _media_chunk_frame,
    _phone_frame, make_adapter)


def _deliver_frame(agent_id: str, chat_id: str, text: str, msg_id: str = "m-1") -> dict:
    """Relay-Broker-Frame im Skeleton-Format (Klartext-Pfad)."""
    return {"type": "chat.deliver", "v": 1, "agent_id": agent_id,
            "chat_id": chat_id, "msg_id": msg_id, "body": {"text": text}}


async def _feed_and_settle(a, ws, frame: dict) -> None:
    """Connect (falls nötig), Frame in die Mock-Inbox, abarbeiten + Drains."""
    if not a._connected:
        await a.connect()
    ws.inbox.append(_Msg("TEXT", json.dumps(frame)))
    await a.drain_bg_tasks()
    await asyncio.sleep(0.03)  # ungetrackte Read-Tasks (create_task) mitlaufen


@pytest.mark.asyncio
async def test_slash_status_local_only_no_gateway_push(tmp_path) -> None:
    """/status: lokal beantworted — kein message_callback-Push, Read-Receipt
    zuerst (msg_id klar), Antworttext mit Verbindung + E2E + Agent. Ohne
    Session = ehrliche 'keine aktive E2E-Session'-Zeile."""
    received: list[dict] = []
    a, ws = make_adapter({"FLIPSI_MEDIA_DIR": str(tmp_path / "media-leer")})
    a.message_callback = received.append
    # Whitespaces-Padding: exakter Start-Match nach strip (Plan §B.2-Trigger).
    await _feed_and_settle(a, ws, _deliver_frame(a.agent_id, "device-1", "  /status  "))
    # Ohne Session = ehrliche 'keine aktive E2E-Session'-Zeile.
    assert received == [], "/status darf NICHT in die Gateway-Pipeline"
    replies = [f for f in ws.sent if f["type"] == "chat.reply"]
    assert len(replies) == 1, f"genau 1 Status-Antwort, sent={len(ws.sent)}"
    text = replies[0]["text"]
    assert "Verbindung: verbunden" in text
    assert "keine aktive E2E-Session" in text, "ohne Session ehrlich anzeigen"
    assert "Agent: agent-a" in text
    reads = [f for f in ws.sent if f["type"] == "chat.read"]
    assert reads and reads[0]["msg_id"] == "m-1", "Read-Receipt zuerst (msg_id klar)"
    await a.disconnect()


@pytest.mark.asyncio
async def test_slash_status_honest_media_and_reject_lines(tmp_path) -> None:
    """/status Inhalt: Medien-Zeile zählt echte Dateien, Diagnose-Zeile zeigt
    Top-Gründe, Letzte-Aktivität-Zeile existiert (Empfangs-Stempel passiert
    auch bei Slash-Eingaben)."""
    media_dir = tmp_path / "media"
    media_dir.mkdir()
    (media_dir / "foto.jpg").write_bytes(b"x" * 33)
    a, ws = make_adapter({"FLIPSI_MEDIA_DIR": str(media_dir)})
    received: list[dict] = []
    a.message_callback = received.append
    a.rejected_frames.append({"type": "chat.x", "reason": "Versuchs-Grund"})
    await _feed_and_settle(a, ws, _deliver_frame(a.agent_id, "device-1", "/status"))

    text = next(f["text"] for f in ws.sent if f["type"] == "chat.reply")
    assert "Medien: 1 Dateien (1 KB)" in text, f"got: {text!r}"
    assert "Versuchs-Grund ×1" in text, "Top-3-Diagnose in der Antwort"
    assert "Letzte Aktivität:" in text
    await a.disconnect()


@pytest.mark.asyncio
async def test_slash_new_passes_through_to_gateway_no_session_reset() -> None:
    """/new = Session-Reset-Verband des GATEWAYS: Roh-Text 1:1 in die Pipeline
    (destructive-Confirm-Gate = Bestätigung, Gateway-Antwort = Bubble) —
    KEINE Adapter-Bubble, KEIN crypto.session_reset-Frame (Ratchet bleibt),
    Read-Receipt kommt über die normale Deliver-Kette."""
    received: list[dict] = []
    a, ws = make_adapter({})
    a.message_callback = received.append
    await _feed_and_settle(a, ws, _deliver_frame(a.agent_id, "device-1", "/new"))

    assert len(received) == 1 and received[0]["text"] == "/new", \
        "Gateway (Session-Eigentümer) bekommt das unveränderte '/new'"
    replies = [f for f in ws.sent if f["type"].startswith("chat.reply")]
    assert replies == [], "keine Adapter-Doppel-Bubble (DECISION O-5)"
    resets = [f for f in ws.sent if f["type"] == "crypto.session_reset"]
    assert resets == [], "KEIN crypto.session_reset — kein Ratchet-Churn (Abgrenzung)"
    reads = [f for f in ws.sent if f["type"] == "chat.read"]
    assert reads and reads[0]["msg_id"] == "m-1", "verarbeitet → gelesen"

    # Args-Variante: das GANZE Roh-Textevent läuft durch (Gateway-Gate).
    await _feed_and_settle(a, ws, _deliver_frame(a.agent_id, "device-1",
                                                 "/new jetzt bitte", msg_id="m-2"))
    assert [r["text"] for r in received] == ["/new", "/new jetzt bitte"]
    assert [f for f in ws.sent if f["type"].startswith("chat.reply")] == []
    assert [f for f in ws.sent if f["type"] == "crypto.session_reset"] == []
    await a.disconnect()


@pytest.mark.asyncio
async def test_slash_help_answers_with_help_text() -> None:
    """/help: Antwort == _HELP_TEXT (Modulkonstante), kein Gateway-Push,
    Read-Receipt zuerst."""
    received: list[dict] = []
    a, ws = make_adapter({})
    a.message_callback = received.append
    await _feed_and_settle(a, ws, _deliver_frame(a.agent_id, "device-1", "/help"))

    assert received == []
    reply = next(f for f in ws.sent if f["type"] == "chat.reply")
    assert reply["text"] == _HELP_TEXT
    for cmd in ("/new", "/status", "/help"):
        assert cmd in _HELP_TEXT
    assert "Adapter" in _HELP_TEXT, "Nicht-hardkodiert-Vertrag sichtbar im Text"
    reads = [f for f in ws.sent if f["type"] == "chat.read"]
    assert reads and reads[0]["msg_id"] == "m-1"
    await a.disconnect()


@pytest.mark.asyncio
async def test_slash_args_get_ignore_hint() -> None:
    """/status & /help ohne Args-Grammatik: vorhandene Args = Kurzhinweis,
    Kernantwort bleibt unverändert (_HELP_TEXT-Vertrag)."""
    a, ws = make_adapter({})
    received: list[dict] = []
    a.message_callback = received.append
    await _feed_and_settle(a, ws, _deliver_frame(a.agent_id, "device-1",
                                                 "/help alles danach"))
    received_reply = [f["text"] for f in ws.sent if f["type"] == "chat.reply"]
    assert received_reply == [
        _HELP_TEXT + "\nHinweis: Argumente nach /help werden ignoriert."]
    assert received == [], "Args machen aus /help KEINE normale Nachricht"
    await a.disconnect()


@pytest.mark.asyncio
async def test_slash_unknown_and_false_positives_are_normal_messages() -> None:
    """Unbekanntes '/...' und Nicht-Befehle = normale Nachricht (false-positive-
    Verband): Roh-Text in die Gateway-Pipeline, KEINE Adapter-Antwort."""
    received: list[dict] = []
    a, ws = make_adapter({})
    a.message_callback = received.append
    await a.connect()
    texts = ["/xyz", "status ohne slash", "foo /new bar", "/etc/hosts prüfen",
             "/", "/NEW", "/statusx", "/new-ish"]
    for index, text in enumerate(texts):
        ws.inbox.append(_Msg("TEXT", json.dumps(
            _deliver_frame(a.agent_id, "device-1", text, msg_id=f"fp-{index}"))))
        await a.drain_bg_tasks()
    await asyncio.sleep(0.03)
    got = [r["text"] for r in received]
    assert got == texts, f"alle Nicht-Befehle reines Pass-through, got {got}"
    assert [f["text"] for f in ws.sent if f["type"] == "chat.reply"] == [], \
        "keine Befehls-Antwort auf false positives"
    await a.disconnect()


@pytest.mark.asyncio
async def test_slash_media_frames_never_reach_the_parser(tmp_path) -> None:
    """Media-Zweig liegt VOR dem Text-Pfad: keine Text-Slash-Triggers in
    Media-Chunks — die Media-Nachricht (Pfad/Label) wird normal geliefert."""
    import base64
    import hashlib
    a, ws = _make_crypto_adapter(tmp_path,
                                 env_extra={"FLIPSI_MEDIA_DIR": str(tmp_path / "md")})
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    peer = await _establish_session(a, ws)
    raw = b"slash-guard" * 10
    frame = _media_chunk_frame(peer, "device-1", "med-slash-0000001", 0, 1,
                               base64.b64encode(raw).decode("ascii"),
                               sha=hashlib.sha256(raw).hexdigest(), size=len(raw))
    ws.inbox.append(_Msg("TEXT", json.dumps(frame)))
    await a.drain_bg_tasks()
    await asyncio.sleep(0.03)
    assert len(received) == 1
    assert received[0]["text"].startswith("[Foto]"), "Media = normale Nachricht"
    assert [f["text"] for f in ws.sent if f["type"].startswith("chat.reply")] == [], \
        "Media-Pfad darf keine Slash-Antwort auslösen"
    await a.disconnect()


@pytest.mark.asyncio
async def test_slash_enc_roundtrip_status_reply_via_chat_reply_enc(tmp_path) -> None:
    """/status über chat.send.enc: Antwort läuft die E2E-Kette entlang
    (chat.reply.enc, entschlüsselbar), Read-Receipt als chat.read.enc mit
    klarem msg_id; mit Session zeigt der Text die Session-Zeile ehrlich."""
    a, ws = _make_crypto_adapter(
        tmp_path, env_extra={"FLIPSI_MEDIA_DIR": str(tmp_path / "md")})
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    peer = await _establish_session(a, ws)

    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "device-1",
                     {"text": "/status", "msg_id": "ms-1"}, msg_id="ms-1"))))
    await a.drain_bg_tasks()
    await asyncio.sleep(0.03)

    assert received == [], "enc-/status bleibt ebenfalls lokal"
    enc_replies = [f for f in ws.sent if f["type"] == "chat.reply.enc"]
    assert len(enc_replies) == 1, f"1 enc-Antwort erwartet: {len(ws.sent)} Frames"
    text = peer.decrypt_frame(enc_replies[-1])["text"]
    assert "Verbindung: verbunden" in text
    sess_line = [line for line in text.splitlines() if line.startswith("E2E:")][0]
    assert "ph3-tes" in sess_line, f"Session-Kurzform in {sess_line!r}"
    assert "keine aktive E2E-Session" not in text, "Session-Zeile ehrlich positiv"
    enc_reads = [f for f in ws.sent if f["type"] == "chat.read.enc"]
    assert enc_reads and enc_reads[-1]["msg_id"] == "ms-1", "msg_id klar (PH2-Logik)"
    assert peer.decrypt_frame(enc_reads[-1])["msg_id"] == "ms-1", "Ratchet-Spiegel"
    await a.disconnect()


@pytest.mark.asyncio
async def test_slash_fingerprint_gate_sits_before_the_parser(tmp_path) -> None:
    """FP-ABGLEICH-Zeile hat Vorrang vor dem Slash-Parser (bestehender Verband
    unangetastet): 'FP-ABGLEICH: <hex>' erzeugt die FP-Antwort, KEINE
    Befehls-/Status-Antwort."""
    a, ws = _make_crypto_adapter(
        tmp_path, env_extra={"FLIPSI_MEDIA_DIR": str(tmp_path / "md")})
    received: list[dict] = []
    a.message_callback = received.append
    await a.connect()
    peer = await _establish_session(a, ws)
    fp_text = f"FP-ABGLEICH: {a._crypto_session.fingerprint}"
    ws.inbox.append(_Msg("TEXT", json.dumps(
        _phone_frame(peer, "chat.send.enc", "device-1",
                     {"text": fp_text, "msg_id": "fp-1"}, msg_id="fp-1"))))
    await a.drain_bg_tasks()
    await asyncio.sleep(0.03)
    replies = [f for f in ws.sent if f["type"] == "chat.reply.enc"]
    assert len(replies) == 1, "FP-Antwort genau 1×"
    text = peer.decrypt_frame(replies[-1])["text"]
    assert "Fingerprint-Abgleich" in text
    assert "Befehle:" not in text, "/help-Verband nicht berührt"
    assert received == []
    await a.disconnect()


@pytest.mark.asyncio
async def test_slash_namespace_guard_prevents_parser() -> None:
    """Fremde agent_id = Namespace-Verstoß: der Frame wird verworfen, der
    Parser bleibt unerreichbar (ererbte Guards), keine Antwort, kein Push."""
    a, ws = make_adapter({})
    received: list[dict] = []
    a.message_callback = received.append
    await _feed_and_settle(a, ws, _deliver_frame("agent-fremd", "device-1", "/status"))
    assert received == []
    assert not [f for f in ws.sent if f["type"].startswith("chat.reply")]
    assert any(r.get("reason") == "fremde agent_id (Namespace-Verstoß)"
               for r in a.rejected_frames), "verworfen mit Namespace-Grund"
    await a.disconnect()


def test_slash_commands_are_immutable_module_constants() -> None:
    """Scope-Guard-Pinning: _SLASH_COMMANDS = frozenset-Modulkonstante — der
    Phone-Payload kann die Liste nicht erweitern; _HELP_TEXT = Modul-String."""
    assert isinstance(_SLASH_COMMANDS, frozenset)
    assert _SLASH_COMMANDS == frozenset({"new", "status", "help"})
    assert isinstance(_HELP_TEXT, str) and _HELP_TEXT
    import adapter as module
    # Modul-Level (kein Instanz-/Payload-Weg): dieselbe Konstante.
    assert module._SLASH_COMMANDS is _SLASH_COMMANDS
    assert isinstance(module.FlipsiBridgeChatAdapter._handle_slash_command, object)


def test_slash_scope_guard_code_band_in_adapter_source() -> None:
    """SCOPE-GUARD-Verband im Quelltext: Parser NUR im FlipsiBridge-Adapter —
    kein globaler Dispatcher, Code-Band-Kommentar vorhanden (0932dba-Muster)."""
    source_path = FlipsiBridgeChatAdapter.__module__
    import adapter as module
    with open(module.__file__, "r", encoding="utf-8") as fh:
        source = fh.read()
    assert "SCOPE-GUARD (W4-B2" in source, "Code-Band-Kommentar fehlt"
    assert source.count("._handle_slash_command(") == 1, \
        "genau EIN Aufrufpunkt — kein weiterer Command-Shim"
    assert "def _handle_slash_command" in source
