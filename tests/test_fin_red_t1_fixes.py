"""FIN-T1 RED-FIX-WELLE — Regressionstests der 7 Red-Agent-Funds als Test-Verträge.

Je Fund (RED-01..RED-07 aus secrets-docs/FIN-RED-T1-FINDINGS.md) ≥1 Test, der
den ORIGINAL-PoC (scratch/fin_red/) als Vertrags-Läufe nachbaut: In-Process-
Relay auf 1996x-Ports, Test-Tokens/Keys, KEIN Live-Daemon. Jeder Test failt
auf dem Vor-Fix-Stand und hält den Fix afterwards fest.
"""
import json
import os
import sys
import threading
import time

import pytest

from tools.android_relay import (
    start_relay,
    stop_relay,
    is_relay_running,
    set_pairing_code,
    _auth_lock,
    _auth_blocked,
    _auth_failures,
    _chat_rate_hits,
    _chat_rate_lock,
    _RelayState,
)


@pytest.fixture(autouse=True)
def reset_relay():
    yield
    stop_relay()


@pytest.fixture(autouse=True)
def reset_auth_state():
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()
    yield
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()


@pytest.fixture(autouse=True)
def reset_chat_rate_state():
    with _chat_rate_lock:
        _chat_rate_hits.clear()
    yield
    with _chat_rate_lock:
        _chat_rate_hits.clear()


# ═══════════════════════════════════════════════════════════════════════════════
# RED-01 (P2) — Leerer/null Pairing-Code = vollständiger Auth-Bypass
# ═══════════════════════════════════════════════════════════════════════════════

class TestFinRed01EmptyPairingCode:
    """PoC red01: start_relay(pairing_code="") lief an und akzeptierte
    Phone-WS + /key_get + /chat + /crypto_hello OHNE JEGLICHES Authorization-
    Header (_constant_time_eq("", "") == True). Vertrag: JEDE Code-Quelle
    (start_relay, set_pairing_code, android_setup) verwirft leer/null/
    whitespace fail-closed; der Vergleich leer=fail ist Defense-in-depth."""

    PORT = 19961
    CODE = "RED01AB"

    def test_start_relay_rejects_empty_null_whitespace(self):
        stop_relay()  # evtl. laufenden Relay aus Fremd-Modul-Tests räumen
        for bad in ("", "   ", "\t\n", None):
            with pytest.raises(ValueError):
                start_relay(pairing_code=bad, port=self.PORT)
        assert not is_relay_running()

    def test_set_pairing_code_refuses_empty_keeps_old_code(self, caplog):
        import requests

        start_relay(pairing_code=self.CODE, port=self.PORT)
        set_pairing_code("")
        set_pairing_code("   ")
        set_pairing_code(None)
        # Verwerf-Route sichtbar im Log …
        dumped = "\n".join(r.getMessage() for r in caplog.records)
        assert "set_pairing_code: empty/invalid pairing code rejected" in dumped
        # … und der ALT-Code bleibt gültig (503 = Auth OK, kein Phone dran).
        r = requests.get(f"http://localhost:{self.PORT}/ping",
                         headers={"Authorization": f"Bearer {self.CODE}"}, timeout=5)
        assert r.status_code == 503, (
            f"Alt-Code muss nach leerer Rotation gültig bleiben, kam {r.status_code}")

    def test_android_setup_rejects_empty_code_writes_nothing(self, monkeypatch):
        from tools import android_tool as tool_mod
        import sys

        monkeypatch.setitem(sys.modules, "hermes_cli", None)
        monkeypatch.setitem(sys.modules, "agent", None)
        monkeypatch.delenv("ANDROID_BRIDGE_TOKEN", raising=False)
        monkeypatch.delenv("ANDROID_BRIDGE_URL", raising=False)

        # Token nicht gesetzt → fail-closed Config-Fehler, NICHTS wird
        # in .env oder os.environ geschrieben (Katalog-Review #2).
        result = json.loads(tool_mod.android_setup())
        assert result.get("status") == "error", (
            f"Token ohne Konfiguration muss abgewiesen werden, kam: {result}")
        assert "ANDROID_BRIDGE_TOKEN" in result["message"]

        # Whitespace-Token erreicht das Relay und wird dort fail-closed verworfen.
        monkeypatch.setenv("ANDROID_BRIDGE_URL", "http://localhost:1")
        monkeypatch.setenv("ANDROID_BRIDGE_TOKEN", "   ")
        result = json.loads(tool_mod.android_setup())
        assert result.get("status") == "error", (
            f"Whitespace-Code muss abgewiesen werden, kam: {result}")
        assert "pairing code" in result["message"]

    def test_constant_time_eq_treats_empty_as_mismatch(self):
        from tools.android_relay import _constant_time_eq

        assert not _constant_time_eq("", ""), "leer==leer DARF nie True liefern (RED-01-Kern)"
        assert not _constant_time_eq("X", "")
        assert not _constant_time_eq("", "X")
        assert _constant_time_eq("RED01AB", "RED01AB")


# ═══════════════════════════════════════════════════════════════════════════════
# RED-02 (P3) — Pairing-Rotations-TOCTOU (Kick verfehlt WS im Handshake-Fenster)
# ═══════════════════════════════════════════════════════════════════════════════

class TestFinRed02RotationToctou:
    """PoC red02: eine Rotation, die zwischen Token-Check und Registrierung
    landet (await ws.prepare = await-Punkt), wurde vom W2-1-Kick verfehlt —
    die Alt-Code-WS registrierte sich DANACH als aktive Phone-WS unter dem
    NEUEN Code (Presence-Push-Identitätsbeweis). Vertrag: Alt-Code-WS wird im
    Rennfenster geschlossen und kann NIE aktive Phone-WS werden — DOPPELT
    (zwei aufeinanderfolgende Rennfenster je Rotation)."""

    PORT = 19962
    OLD, NEW, THIRD = "RED02AA", "RED02BB", "RED02CC"

    def test_rotation_race_during_handshake_closes_slipped_ws_double(self, caplog):
        import asyncio
        import aiohttp
        from aiohttp import web as aiohttp_web
        import tools.android_relay as relay_mod

        start_relay(pairing_code=self.OLD, port=self.PORT)
        orig_prepare = aiohttp_web.WebSocketResponse.prepare
        try:
            for round_no, rotator in enumerate((self.NEW, self.THIRD), start=1):
                with relay_mod._relay_lock:
                    current = relay_mod._relay_instance.pairing_code
                assert current, f"Runde {round_no}: gültiger Code erwartet"

                # Rotation MITTEN im Handshake (nach Token-Check, vor prepare-
                # Rückkehr) — exakt das PoC-Rennfenster.
                async def hooked_prepare(ws_self, request):
                    set_pairing_code(rotator)
                    await asyncio.sleep(0.05)
                    return await orig_prepare(ws_self, request)

                aiohttp_web.WebSocketResponse.prepare = hooked_prepare
                try:

                    async def race():
                        async with aiohttp.ClientSession() as session:
                            slipped = await session.ws_connect(
                                f"ws://127.0.0.1:{self.PORT}/ws",
                                headers={"Authorization": f"Bearer {current}"})
                            return slipped, await asyncio.wait_for(
                                slipped.receive(), timeout=4)

                    slipped, first_msg = asyncio.run(race())
                finally:
                    aiohttp_web.WebSocketResponse.prepare = orig_prepare

                # Vertrag: erste "Nachricht" = CLOSE (1008), KEIN relay.state —
                # die Alt-Code-WS wurde verworfen statt registriert.
                assert first_msg.type == aiohttp.WSMsgType.CLOSE, (
                    f"Runde {round_no}: Alt-Code-WS muss im Rennfenster geschlossen "
                    f"werden, kam aber: {first_msg}")
                # … und ist NICHT die aktive Phone-WS.
                with relay_mod._relay_lock:
                    assert relay_mod._relay_instance.phone_ws is None, (
                        f"Runde {round_no}: Alt-Code-WS darf nie registriert werden")

                async def contrasts():
                    outcomes = {}
                    async with aiohttp.ClientSession() as session:
                        try:
                            ws_old = await session.ws_connect(
                                f"ws://127.0.0.1:{self.PORT}/ws",
                                headers={"Authorization": f"Bearer {current}"})
                            await ws_old.close()
                            outcomes["old_code"] = "CONNECTED"
                        except aiohttp.WSServerHandshakeError as exc:
                            outcomes["old_code"] = exc.status
                        ws_new = await session.ws_connect(
                            f"ws://127.0.0.1:{self.PORT}/ws",
                            headers={"Authorization": f"Bearer {rotator}"})
                        hello = await ws_new.receive_json(timeout=4)
                        outcomes["new_code_first_frame"] = hello.get("type")
                        await ws_new.close()
                    return outcomes

                outcomes = asyncio.run(contrasts())
                assert outcomes["old_code"] == 403, (
                    f"Runde {round_no}: Vor-Rotations-Code muss 403 liefern, "
                    f"kam {outcomes['old_code']}")
                assert outcomes["new_code_first_frame"] == "relay.state", (
                    f"Runde {round_no}: Rotations-Code muss normal verbinden")
                # Settle: Round-Handler-Reste (close-Handshake/Access-Log) dürfen
                # nicht in die nächste Runde interleaven — der Test hakt global
                # WebSocketResponse.prepare, nur EINE Verbindung darf je Runde
                # im Rennfenster stecken.
                time.sleep(0.4)

            dumped = "\n".join(r.getMessage() for r in caplog.records)
            assert dumped.count("TOCTOU window closed") == 2, (
                "Beide Rennfenster-Runden müssen die Verwurf-Warnung loggen")
        finally:
            aiohttp_web.WebSocketResponse.prepare = orig_prepare


# ═══════════════════════════════════════════════════════════════════════════════
# Gemeinsame E2E-Helfer (Muster: TestPh3CryptoE2E + scratch/fin_red/red_e2e.py)
# ═══════════════════════════════════════════════════════════════════════════════

_FIN_SYS_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             "..", "contrib", "hermes-chat-plugin")
if _FIN_SYS_PATH not in sys.path:
    sys.path.insert(0, _FIN_SYS_PATH)
sys.modules.setdefault("gateway", None)
import adapter as _fin_adapter  # noqa: E402  (contrib/hermes-chat-plugin/adapter.py)

# Fixe Agent-Identity (RFC-7748-Bob als Identity, Fixture-PreKey — TEST-Material)
IK_A_PRIV = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
IK_A_PUB = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
SPK_A_PRIV = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
SPK_A_PUB = "52a5524b0c32e8a47f239ab6ca8e42c3b9316a54cae4854ecdb5889311cc9a7e"
FIN_IDENTITY = {"identity_priv": IK_A_PRIV, "identity_pub": IK_A_PUB,
                "prekey_priv": SPK_A_PRIV, "prekey_pub": SPK_A_PUB}


def _fin_write_config(tmp_path, agents, rate=50):
    """relay-config.json mit Test-Tokens + Identity-Keys je Agent schreiben."""
    data = {"CHAT_RATE_PER_SECOND": rate}
    for agent_id, token in agents.items():
        data[f"ANDROID_AGENT_TOKEN_{agent_id.upper()}"] = token
        data[f"ANDROID_AGENT_IDENTITY_{agent_id.upper()}"] = IK_A_PUB
        data[f"ANDROID_AGENT_PREKEY_{agent_id.upper()}"] = SPK_A_PUB
        data[f"ANDROID_AGENT_FINGERPRINT_{agent_id.upper()}"] = \
            _fin_adapter.fingerprint_of_pub(bytes.fromhex(IK_A_PUB))
    cfg = tmp_path / "relay-config.json"
    cfg.write_text(json.dumps(data), encoding="utf-8")
    return str(cfg)


async def _fin_connect(session, port, code, token):
    """Phone-WS + Agent-WS verbinden; Hallo-/Presence-Frames konsumieren."""
    import aiohttp

    phone = await session.ws_connect(f"ws://127.0.0.1:{port}/ws",
                                     headers={"Authorization": f"Bearer {code}"})
    assert (await phone.receive_json(timeout=2)).get("type") == "relay.state"
    agent = await session.ws_connect(f"ws://127.0.0.1:{port}/agent_ws",
                                     headers={"Authorization": f"Bearer {token}"})
    assert (await phone.receive_json(timeout=2)).get("type") == "relay.state"
    return phone, agent


async def _fin_establish(http, phone, agent, base, code, agent_id, session_id):
    """key_get → crypto.hello → hello_ack → verifizierte E2E-Session."""
    got = await http.get(f"{base}/key_get", params={"agent_id": agent_id},
                         headers={"Authorization": f"Bearer {code}"})
    assert got.status == 200, await got.text()
    keys = await got.json()

    peer = _fin_adapter.PhoneCryptoPeer(agent_id, keys["ik_pub"], keys["spk_pub"])
    resp = await http.post(f"{base}/crypto_hello", json=peer.make_hello(session_id),
                           headers={"Authorization": f"Bearer {code}"})
    assert resp.status == 200, await resp.text()
    hello_frame = await agent.receive_json(timeout=6)
    assert hello_frame["type"] == "crypto.hello", hello_frame
    agent_session, ack = _fin_adapter.AgentCryptoSession.respond_to_hello(
        hello_frame, FIN_IDENTITY)
    await agent.send_json(ack)

    deadline = time.monotonic() + 6
    while True:
        remaining = deadline - time.monotonic()
        assert remaining > 0, "crypto.hello_ack kam nicht ans Phone"
        frame = await phone.receive_json(timeout=max(0.1, remaining))
        if frame.get("type") == "crypto.hello_ack":
            ack_frame = frame
            break
    peer.consume_ack({"session_id": session_id, "kex_pub": ack["kex_pub"],
                      "nonce": ack["nonce"], "ct": ack["ct"]})
    assert peer.fingerprint == agent_session.fingerprint
    return peer, agent_session


async def _fin_collect_frames(ws, seconds=1.2):
    """Alles lesen, was binnen `seconds` ankommt (Stille-Beweis)."""
    import asyncio

    frames = []
    deadline = time.monotonic() + seconds
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return frames
        try:
            frames.append(await ws.receive_json(timeout=max(0.05, remaining)))
        except asyncio.TimeoutError:
            return frames


# ═══════════════════════════════════════════════════════════════════════════════
# RED-03 (P3) — Klartext-Metadaten-Downgrade in aktiver E2E-Session
# ═══════════════════════════════════════════════════════════════════════════════

class TestFinRed03PlaintextMetaDowngrade:
    """PoC red03: klare chat.read-Receipt (chat.status 'read') UND klare
    chat.activity-Frames kamen bei bestätigter Session durch — der Guard
    existierte nur für chat.reply/chat_event//chat. Vertrag (F1-Parität
    3488ec5): JEDE Klartext-Frame-Art des Agenten wird während bestätigter
    E2E-Session verworfen; die .enc-Spiegel laufen weiter."""

    PORT = 19963
    CODE = "RED03AA"
    TOKEN = "red03-agent-token"

    def _setup(self, monkeypatch, tmp_path):
        monkeypatch.setenv("ANDROID_RELAY_CONFIG",
                           _fin_write_config(tmp_path, {"alpha": self.TOKEN}))
        start_relay(pairing_code=self.CODE, port=self.PORT)
        return f"http://127.0.0.1:{self.PORT}"

    def test_plaintext_chat_read_discarded_in_e2e_session(self, tmp_path, monkeypatch, caplog):
        import asyncio
        import aiohttp

        base = self._setup(monkeypatch, tmp_path)

        async def scenario():
            async with aiohttp.ClientSession() as http:
                phone, agent = await _fin_connect(http, self.PORT, self.CODE, self.TOKEN)

                # VOR der Session: Klartext-/chat legt msg_id + Status an.
                pre = await http.post(f"{base}/chat", json={
                    "agent_id": "alpha", "text": "RED-03 Setup-Nachricht"},
                    headers={"Authorization": f"Bearer {self.CODE}"})
                assert pre.status == 200, await pre.text()
                pre_body = await pre.json()
                chat_id, msg_id = pre_body["chat_id"], pre_body["msg_id"]
                await agent.receive_json(timeout=6)  # chat.send am Agenten

                peer, agent_session = await _fin_establish(
                    http, phone, agent, base, self.CODE, "alpha", "red03-sess-read-01")

                # ── ANGRIFF: KLARTEXT chat.read während aktiver enc-Session ──
                await agent.send_json({"type": "chat.read", "v": 1,
                                       "chat_id": chat_id, "msg_id": msg_id})
                frames = await _fin_collect_frames(phone)
                assert not any(f.get("type") == "chat.status" and f.get("status") == "read"
                               for f in frames), (
                    f"Klare 'gelesen'-Receipt darf in E2E-Sessions nie ankommen: {frames}")

                # ── Kontrast: chat.read.enc treibt dieselbe Status-Logik ────
                read_enc = agent_session.encrypt_frame(
                    "chat.read.enc", chat_id, {"msg_id": msg_id}, clear={"msg_id": msg_id})
                await agent.send_json(read_enc)
                parked: list = []
                deadline = time.monotonic() + 6
                mirror = None
                while mirror is None:
                    remaining = deadline - time.monotonic()
                    assert remaining > 0, "chat.read.enc-Spiegel kam nicht"
                    fr = await phone.receive_json(timeout=max(0.1, remaining))
                    if fr.get("type") == "chat.read.enc":
                        mirror = fr
                    else:
                        parked.append(fr)
                assert mirror["msg_id"] == msg_id
                assert any(f.get("type") == "chat.status" and f.get("status") == "read"
                           for f in parked), (
                    f"enc-Weg muss die PH2-Status-Logik weiter treiben: {parked}")

                await agent.close()
                await phone.close()

        asyncio.run(scenario())
        dumped = "\n".join(r.getMessage() for r in caplog.records)
        assert "Klartext-chat.read verworfen — E2E-Session aktiv" in dumped

    def test_plaintext_chat_activity_discarded_in_e2e_session(self, tmp_path, monkeypatch, caplog):
        import asyncio
        import aiohttp

        base = self._setup(monkeypatch, tmp_path)

        async def scenario():
            async with aiohttp.ClientSession() as http:
                phone, agent = await _fin_connect(http, self.PORT, self.CODE, self.TOKEN)
                peer, agent_session = await _fin_establish(
                    http, phone, agent, base, self.CODE, "alpha", "red03-sess-act-01")

                # ── ANGRIFF: KLARTEXT chat.activity während aktiver Session ─
                await agent.send_json({"type": "chat.activity", "v": 1,
                                       "chat_id": "red03-act-chat", "working": True})
                frames = await _fin_collect_frames(phone)
                assert not any(f.get("type") == "chat.activity" for f in frames), (
                    f"Klarer 'arbeitet…'-Status darf in E2E-Sessions nie ankommen: {frames}")

                # ── Kontrast: chat.activity.enc liefert den enc-Spiegel ─────
                act_enc = agent_session.encrypt_frame(
                    "chat.activity.enc", "red03-act-chat",
                    {"working": True, "started_at": 1},
                    clear={"working": True, "started_at": 1})
                await agent.send_json(act_enc)
                parked: list = []
                deadline = time.monotonic() + 6
                mirror = None
                while mirror is None:
                    remaining = deadline - time.monotonic()
                    assert remaining > 0, "chat.activity.enc-Spiegel kam nicht"
                    fr = await phone.receive_json(timeout=max(0.1, remaining))
                    if fr.get("type") == "chat.activity.enc":
                        mirror = fr
                    else:
                        parked.append(fr)
                assert mirror["working"] is True

                await agent.close()
                await phone.close()

        asyncio.run(scenario())
        dumped = "\n".join(r.getMessage() for r in caplog.records)
        assert "Klartext-chat.activity verworfen — E2E-Session aktiv" in dumped


# ═══════════════════════════════════════════════════════════════════════════════
# RED-04 (P3) — /crypto_hello Rebind UNACKED Session auf fremden Agenten
# ═══════════════════════════════════════════════════════════════════════════════

class TestFinRed04HelloRebind:
    """PoC red04: Re-Hello mit agent_id=beta auf alpha-Session = 200 und der
    Broker stand danach auf beta — alpha' legitimer hello_ack starb als
    'fremd' (Handshake dauerhaft tot, nur Pairing-Code nötig). Vertrag:
    Session-Slot je (session_id, agent_id) — fremdes agent_id = 403 + Warning,
    Original-Agent bleibt Eigentümer und kann bestätigen; gleiche-agent_id-
    Retry auf unbestätigter Session bleibt erlaubt."""

    PORT = 19964
    CODE = "RED04AA"
    TOKEN_A = "red04-alpha-token"
    TOKEN_B = "red04-beta-token"

    def test_rebind_to_foreign_agent_rejected_original_ack_survives(self, tmp_path, monkeypatch, caplog):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", _fin_write_config(
            tmp_path, {"alpha": self.TOKEN_A, "beta": self.TOKEN_B}))
        start_relay(pairing_code=self.CODE, port=self.PORT)
        base = f"http://127.0.0.1:{self.PORT}"
        headers = {"Authorization": f"Bearer {self.CODE}"}
        sid = "red04-session-0001"

        async def scenario():
            outcomes = {}
            async with aiohttp.ClientSession() as http:
                phone, agent_j = await _fin_connect(
                    http, self.PORT, self.CODE, self.TOKEN_A)

                # 1) Legitimes Phone-Hello für Agent ALPHA (unacked).
                hello1 = await http.post(f"{base}/crypto_hello", json={
                    "agent_id": "alpha", "session_id": sid,
                    "ik_pub": "aa" * 32, "ek_pub": "bb" * 32, "spk_pub": "cc" * 32},
                    headers=headers)
                assert hello1.status == 200, await hello1.text()
                got = await agent_j.receive_json(timeout=6)
                assert got["type"] == "crypto.hello"

                # 2) ANGRIFF: Re-Hello auf dieselbe Session mit agent_id=BETA.
                attack = await http.post(f"{base}/crypto_hello", json={
                    "agent_id": "beta", "session_id": sid,
                    "ik_pub": "dd" * 32, "ek_pub": "ee" * 32, "spk_pub": "ff" * 32},
                    headers=headers)
                outcomes["rebind_status"] = attack.status
                assert attack.status == 403, (
                    f"Fremdes agent_id muss 403 liefern, kam {attack.status}")

                # 3) Broker-Bindung unverändert: alpha bleibt Eigentümer.
                import tools.android_relay as relay_mod
                with relay_mod._relay_lock:
                    entry = relay_mod._relay_instance.crypto_sessions.get(sid, {})
                outcomes["bound_to"] = entry.get("agent_id")
                assert entry.get("agent_id") == "alpha", (
                    f"Session muss am Original-Agenten bleiben, kam {entry}")

                # 4) Original-Agent bestätigt — Ack muss AKZEPTIERT werden
                #    (vorher starb er als 'fremd' = Handshake dauerhaft tot).
                await agent_j.send_json({
                    "type": "crypto.hello_ack", "session_id": sid,
                    "kex_pub": "11" * 32, "nonce": "AAAAAAAAAAAAAAAA",
                    "ct": "Y2FmZWJhYmU="})
                await asyncio.sleep(0.5)
                with relay_mod._relay_lock:
                    entry2 = relay_mod._relay_instance.crypto_sessions.get(sid, {})
                outcomes["acked"] = entry2.get("acked")
                assert entry2.get("acked") is True, (
                    f"Original-hello_ack muss nach abgewiesenem Rebind durchgehen: {entry2}")

                # 5) Kontrast: gleicher Agent darf sein unbestätigtes Hello
                #    wiederholen (Retry bleibt erlaubt, hier: neue Session).
                retry = await http.post(f"{base}/crypto_hello", json={
                    "agent_id": "alpha", "session_id": "red04-session-0002",
                    "ik_pub": "aa" * 32, "ek_pub": "bb" * 32, "spk_pub": "cc" * 32},
                    headers=headers)
                outcomes["same_agent_new_session"] = retry.status
                assert retry.status == 200
                await agent_j.receive_json(timeout=6)

                await agent_j.close()
                await phone.close()
            return outcomes

        asyncio.run(scenario())
        dumped = "\n".join(r.getMessage() for r in caplog.records)
        assert "Rebind der Session" in dumped and "abgelehnt (403)" in dumped


# ═══════════════════════════════════════════════════════════════════════════════
# RED-05 (P3) — enc-Extras ohne Typ-/Finite-Check (NaN + Typ-Konfusion ans Phone)
# ═══════════════════════════════════════════════════════════════════════════════

class TestFinRed05EncExtrasTypeConfusion:
    """PoC red05: elapsed_ms=NaN (JSON-Literal) landete WÖRTLICH im Phone-Frame
    (invalides JSON für strikte Parser), elapsed_ms als Objekt und msg_id als
    Liste/Objekt wurden unverändert durchgereicht. Vertrag (Parität F-03/
    _finite_ms zum Klartext-Pfad): numerische Extras endlich, JSON-Konsum-
    Extras nur über die str/num/bool-Whitelist, msg_id in chat.read.enc
    strikt str (sonst Frame-Verwurf)."""

    PORT = 19965
    CODE = "RED05AA"
    TOKEN = "red05-agent-token"

    @staticmethod
    def _strict_loads(raw):
        def boom(const):
            raise ValueError(f"invalid JSON literal: {const}")
        return json.loads(raw, parse_constant=boom)

    def test_nan_and_type_confusion_extras_never_reach_phone(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG",
                           _fin_write_config(tmp_path, {"alpha": self.TOKEN}))
        start_relay(pairing_code=self.CODE, port=self.PORT)
        base = f"http://127.0.0.1:{self.PORT}"

        async def scenario():
            raw_frames = []
            async with aiohttp.ClientSession() as http:
                phone, agent = await _fin_connect(http, self.PORT, self.CODE, self.TOKEN)
                peer, agent_session = await _fin_establish(
                    http, phone, agent, base, self.CODE, "alpha", "red05-session-01")

                def send_mangled(frame, needle, replacement):
                    return json.dumps(frame).replace(needle, replacement)

                # (a) elapsed_ms = NaN als JSON-Literal (wörtliches NaN ans Phone).
                fa = agent_session.encrypt_frame(
                    "chat.reply.enc", "red05-chat-01", {"text": "a", "msg_id": "m1"},
                    clear={"elapsed_ms": 1})
                await agent.send_str(send_mangled(fa, '"elapsed_ms": 1', '"elapsed_ms": NaN'))

                # (b) elapsed_ms als Objekt.
                fb = agent_session.encrypt_frame(
                    "chat.reply.enc", "red05-chat-01", {"text": "a", "msg_id": "m2"},
                    clear={"elapsed_ms": 2})
                await agent.send_str(send_mangled(
                    fb, '"elapsed_ms": 2', '"elapsed_ms": {"$explode": [1, 2, 3]}'))

                # (c) msg_id als Objekt (chat.reply.enc).
                fc = agent_session.encrypt_frame(
                    "chat.reply.enc", "red05-chat-01", {"text": "a", "msg_id": "m3"})
                await agent.send_str(send_mangled(
                    fc, '"msg_id": "m3"', '"msg_id": {"nested": {"deep": true}}'))

                # (d) chat.read.enc mit msg_id als Liste.
                fd = agent_session.encrypt_frame(
                    "chat.read.enc", "red05-chat-01", {"msg_id": "m4"},
                    clear={"msg_id": "m4"})
                await agent.send_str(send_mangled(fd, '"msg_id": "m4"', '"msg_id": ["x", "y"]'))

                # (e) Kontrast: legitimer enc-Frame MITExtras kommt sauber durch.
                fe = agent_session.encrypt_frame(
                    "chat.reply.enc", "red05-chat-01", {"text": "ok", "msg_id": "m5"},
                    clear={"msg_id": "m5", "elapsed_ms": 42})
                await agent.send_json(fe)

                deadline = time.monotonic() + 3
                while time.monotonic() < deadline:
                    try:
                        raw_frames.append(await phone.receive_str(timeout=0.4))
                    except asyncio.TimeoutError:
                        break

                await agent.close()
                await phone.close()
            return raw_frames

        raw_frames = asyncio.run(scenario())

        # KEIN Frame enthält NaN, das Explode-Objekt, ein msg_id-Objekt oder
        # eine msg_id-Liste — und JEDES ankommende Frame ist strikt parsebar.
        for raw in raw_frames:
            assert "NaN" not in raw, f"NaN-Literal im Phone-Frame: {raw}"
            assert "$explode" not in raw, f"elapsed_ms-Objekt im Phone-Frame: {raw}"
            assert '"msg_id": {"nested"' not in raw, f"msg_id-Objekt im Phone-Frame: {raw}"
            assert '"msg_id": ["x"' not in raw, f"msg_id-Liste im Phone-Frame: {raw}"
            self._strict_loads(raw)  # wirft bei NaN/Infinity = Testfail
        # Der legitime Kontrast-Frame kam durch (Fix verwirft nichts Kaputtes
        # legitimer Frames).
        assert any('"msg_id": "m5"' in raw and '"elapsed_ms": 42' in raw
                   for raw in raw_frames), (
            f"Legitimer enc-Frame mit Extras muss weiter durch: {raw_frames}")


# ═══════════════════════════════════════════════════════════════════════════════
# RED-06 (P3) — chat_owner-Isolation läuft mit der Retention ab
# ═══════════════════════════════════════════════════════════════════════════════

class TestFinRed06OwnerRetentionExpiry:
    """PoC red06: chat_owner-Einträge wurden von _prune_chat_status mit der
    Spool-Retention (24 h) gelöscht — danach claimte BETA alpha' chat_id
    mit 200 + Zustellung (Cross-Agent-Injektion in die fremde Konversation).
    Vertrag (PH2-T5/W1A-2/W1C-3): Isolation DAUERHAFT — auch nach
    Retention-Ablauf (hier per Env auf <1 s komprimiert) bleibt fremde
    chat_id = 403."""

    PORT = 19966
    CODE = "RED06AA"
    TOKEN_A = "red06-alpha-token"
    TOKEN_B = "red06-beta-token"
    CHAT_ID = "red06-victim-chat-001"

    def test_owner_isolation_survives_retention_prune(self, tmp_path, monkeypatch):
        import asyncio
        import aiohttp

        monkeypatch.setenv("ANDROID_RELAY_CONFIG", _fin_write_config(
            tmp_path, {"alpha": self.TOKEN_A, "beta": self.TOKEN_B}))
        # Retention auf ~0.36 s drücken (0.0001 h) — simuliert den 24-h-Ablauf.
        monkeypatch.setenv("ANDROID_RELAY_CHAT_RETENTION_HOURS", "0.0001")
        start_relay(pairing_code=self.CODE, port=self.PORT)
        base = f"http://127.0.0.1:{self.PORT}"

        async def scenario():
            async with aiohttp.ClientSession() as http:
                phone = await http.ws_connect(
                    f"ws://127.0.0.1:{self.PORT}/ws",
                    headers={"Authorization": f"Bearer {self.CODE}"})
                assert (await phone.receive_json(timeout=2)).get("type") == "relay.state"

                # 1) ALPHA claimt die Konversation (legitimer Reply).
                r1 = await http.post(f"{base}/chat_event", json={
                    "chat_id": self.CHAT_ID, "text": "ALPHA' Nachricht"},
                    headers={"Authorization": f"Bearer {self.TOKEN_A}"})
                assert r1.status == 200, await r1.text()

                # 2) BETA vorher = 403 (Isolation greift).
                r2 = await http.post(f"{base}/chat_event", json={
                    "chat_id": self.CHAT_ID, "text": "BETA Spoof 1"},
                    headers={"Authorization": f"Bearer {self.TOKEN_B}"})
                assert r2.status == 403, (
                    f"Isolation muss vor Ablauf greifen, kam {r2.status}")

                # 3) Retention-Ablauf ABWARTEN + Prune erzwingen: ein weiterer
                #    legitimer ALPHA-Reply auf eine ANDERE chat_id lässt
                #    _prune_chat_status laufen (der alte chat_status-Eintrag
                #    muss weg sein, der OWNER muss bleiben).
                await asyncio.sleep(0.6)
                r3 = await http.post(f"{base}/chat_event", json={
                    "chat_id": "red06-other-chat-002", "text": "Prune-Trigger"},
                    headers={"Authorization": f"Bearer {self.TOKEN_A}"})
                assert r3.status == 200

                import tools.android_relay as relay_mod
                with relay_mod._relay_lock:
                    state = relay_mod._relay_instance
                    assert state.chat_owner.get(self.CHAT_ID, ("", 0))[0] == "alpha", (
                        "Owner-Eintrag darf nach Retention-Ablauf NICHT gelöscht sein")
                    assert not any(
                        entry["chat_id"] == self.CHAT_ID
                        for entry in state.chat_status.values()), (
                        "chat_status-Prune muss weiter laufen (nur Owner ist dauerhaft)")

                # 4) ANGRIFF: BETA nach Ablauf = weiterhin 403.
                r4 = await http.post(f"{base}/chat_event", json={
                    "chat_id": self.CHAT_ID, "text": "BETA Cross-Agent-Injektion NACH Ablauf"},
                    headers={"Authorization": f"Bearer {self.TOKEN_B}"})
                assert r4.status == 403, (
                    f"Isolation muss den Retention-Ablauf ÜBERLEBEN, kam {r4.status}")

                # 5) Zustellungs-Beweis: BETAs chat.event kam NIE an.
                frames = await _fin_collect_frames(phone, seconds=1.0)
                assert not any(f.get("type") == "chat.event"
                              and f.get("agent_id") == "beta"
                              and f.get("chat_id") == self.CHAT_ID for f in frames), (
                    f"BETA darf in ALPHA' chat_id nie zustellen: {frames}")
                await phone.close()

        asyncio.run(scenario())


# ═══════════════════════════════════════════════════════════════════════════════
# RED-07 (P4) — /key_publish ersetzt die Phone-Identity lautlos
# ═══════════════════════════════════════════════════════════════════════════════

class TestFinRed07KeyPublishSilentSwap:
    """PoC red07: jeder Pairing-Code-Inhaber überschrieb ik_pub/spk_pub
    beliebig oft mit 200 — kein Alert-Frame, und am Relay kam der Wechsel
    NICHT einmal ins Log (Agent warnt per TOFU-Pin nur im Log). Vertrag
    (W1A-5 'Warnung statt Blockade', minimal): JEDER Identity-Wechsel =
    EINE WARNING-Log-Zeile am Relay; Erstveröffentlichung/Idempotent-
    Re-Publish bleiben still; Key-Material erscheint NIE im Log."""

    PORT = 19967
    CODE = "RED07AA"
    TOKEN = "red07-agent-token"

    def test_identity_swap_logs_warning_per_change_never_keys(self, tmp_path, monkeypatch, caplog):
        import asyncio
        import aiohttp

        keys_path = tmp_path / "relay-keys.json"
        monkeypatch.setenv("ANDROID_RELAY_KEYS", str(keys_path))
        monkeypatch.setenv("ANDROID_RELAY_CONFIG",
                           _fin_write_config(tmp_path, {"alpha": self.TOKEN}))
        start_relay(pairing_code=self.CODE, port=self.PORT)
        base = f"http://127.0.0.1:{self.PORT}"
        headers = {"Authorization": f"Bearer {self.CODE}"}

        async def scenario():
            async with aiohttp.ClientSession() as http:
                # Erstveröffentlichung: 200, KEINE Wechsel-Warnung.
                r1 = await http.post(f"{base}/key_publish", json={
                    "device_id": "phone-red07", "ik_pub": "aa" * 32, "spk_pub": "bb" * 32},
                    headers=headers)
                assert r1.status == 200
                # Idempotenter Re-Publish (gleiche Keys): 200, KEINE Warnung.
                r1b = await http.post(f"{base}/key_publish", json={
                    "device_id": "phone-red07", "ik_pub": "aa" * 32, "spk_pub": "bb" * 32},
                    headers=headers)
                assert r1b.status == 200

                # ANGRIFF: Identity-Swap mit demselben Pairing-Code.
                r2 = await http.post(f"{base}/key_publish", json={
                    "device_id": "phone-red07", "ik_pub": "ee" * 32, "spk_pub": "ff" * 32},
                    headers=headers)
                assert r2.status == 200  # bewusst KEINE Blockade (W1A-5)
                # Swap trotz gleichem Schlüsselmaterial in ik neu = Wechsel.
                r3 = await http.post(f"{base}/key_publish", json={
                    "device_id": "phone-red07", "ik_pub": "ee" * 32, "spk_pub": "00" * 32},
                    headers=headers)
                assert r3.status == 200
                # Auch device_id-Wechsel mit denselben Keys = Identity-Wechsel.
                r4 = await http.post(f"{base}/key_publish", json={
                    "device_id": "phone-anderes", "ik_pub": "ee" * 32, "spk_pub": "00" * 32},
                    headers=headers)
                assert r4.status == 200

        asyncio.run(scenario())

        stored = json.loads(keys_path.read_text(encoding="utf-8"))["device"]
        assert stored["ik_pub"] == "ee" * 32 and stored["device_id"] == "phone-anderes"

        dumped = "\n".join(r.getMessage() for r in caplog.records)
        assert dumped.count("Phone-Identity-WECHSEL") == 3, (
            f"JEDER Wechsel braucht EINE Warnung (3 Wechsel erwartet), "
            f"log: {dumped}")
        # Audit: Key-Material NIE im Log (auch nicht als Teilmenge).
        for key_hex in ("aa" * 32, "bb" * 32, "ee" * 32, "ff" * 32, "00" * 32):
            assert key_hex not in dumped, "Key-Material im Relay-Log!"