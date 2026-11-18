"""W17: F1 Ein-Gerät-pro-Kopplungscode + F2 Optionales Kopplungs-Passwort.

F1-Vertrag (Sir-Order):
  - ZWEITES Gerät mit demselben Kopplungscode: WS-Kopplungs-Handshake = 403
    code-in-use (Maschinen-Code im Header 'X-Bridge-Error' UND im JSON-Body;
    aiohttp-WS-Fehler transportieren den Body nicht, deshalb Header-Kanal) —
    SOLANGE die erste Verbindung lebt.
  - Reconnect DESSELBEN Geräts (stabile X-Device-Id) geht IMMER durch
    (Replace-Verhalten, kein Aussperren von Sirs eigenem Handy — HARTFEHLER).
  - Stirbt die erste Verbindung (Trennen), fällt der Lock sofort: normale
    Kopplung kehrt zurück — KEIN Dauer-Lock.

F2-Vertrag (Sir-Idee, Umfang FIX):
  - /password_set (Bearer-Auth wie üblich) speichert NUR PBKDF2-HMAC-SHA256 +
    Salt in einem 0600-File, mind. 8 Zeichen, keine Klartext-Rückgabe.
  - /password_clear entfernt den Schutz, /password_status meldet nur aktiv/inaktiv.
  - Ist der Schutz aktiv, verlangt der WS-Kopplungs-Handshake den Header
    'X-Pairing-Password': fehlend = 403 password-required, falsch = 403
    password-invalid UND in die bestehende IP-Rate-Chain gezählt; richtig =
    Kopplung läuft (F1-Slot-Lock greift daneben unverändert).
"""

import asyncio
import json
import os
import time
from pathlib import Path

import aiohttp
import pytest

from tools.android_relay import (
    start_relay,
    stop_relay,
    set_pairing_code,
    is_phone_connected,
    _RelayState,
    _parse_device_id,
    _pairing_slot_locked,
    _pairing_password_record_for,
    _pairing_password_verify,
    _load_pairing_password_record,
    _valid_pairing_password_record,
    _auth_is_blocked,
    _auth_lock,
    _auth_blocked,
    _auth_failures,
)

PORT = 19897
CODE = "SLOTLOCK"
NEUCODE = "NEUCODE123"
DEVICE_A = "phone-alpha"
DEVICE_B = "phone-beta"
DEVICE_C = "phone-gamma"
PASSWORD = "bruecken-pass"
SHORT_PASSWORD = "kurz"


class _FakeHolder:
    """Minimal-Halter für die _pairing_slot_locked-Regel (nur .closed gelesen)."""

    def __init__(self, closed=False):
        self.closed = closed


class _FakeState:
    def __init__(self, holder=None, holder_id=None):
        self.phone_ws = holder
        self.phone_device_id = holder_id


def _ws_headers(device_id=None, password=None):
    headers = {"Authorization": f"Bearer {CODE}"}
    if device_id:
        headers["X-Device-Id"] = device_id
    if password:
        headers["X-Pairing-Password"] = password
    return headers


async def _try_handshake(session, device_id=None, password=None):
    """WS-Handshake-Versuch als Info-Dict: {'connected': true} oder
    {'connected': False, 'status': int, 'bridge_error': str|None}. Der
    aiohttp-WS-Fehler trägt Status + Header, NICHT den Body — der Maschinen-
    Code läuft deshalb über 'X-Bridge-Error' (App kanal-identisch: OkHttp
    liest denselben Header aus der onFailure-Response)."""
    try:
        ws = await asyncio.wait_for(
            session.ws_connect(
                f"ws://127.0.0.1:{PORT}/ws",
                headers=_ws_headers(device_id, password),
            ),
            timeout=3,
        )
        await ws.close()
        return {"connected": True}
    except aiohttp.WSServerHandshakeError as exc:
        return {
            "connected": False,
            "status": exc.status,
            "bridge_error": (exc.headers or {}).get("X-Bridge-Error"),
        }


async def _wait_phone_gone(timeout=5.0):
    """Auf Slot-Freigabe warten (Cleanup läuft im Relay-Event-Loop nach)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not is_phone_connected():
            return True
        await asyncio.sleep(0.05)
    return False


def _wait_phone_gone_sync(timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not is_phone_connected():
            return True
        time.sleep(0.05)
    return False


def _wait_slot_gone(device_id: str, timeout=5.0) -> bool:
    """B1: Warten bis genau DIESE Geräte-Verbindung geräumt ist (andere
    Geräte-Slots dürfen weiter leben — is_phone_connected ist jetzt
    'irgendein Gerät')."""
    from tools.android_relay import _relay_instance
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = _relay_instance
        if state is None or device_id not in (state.devices or {}):
            return True
        time.sleep(0.05)
    return False


@pytest.fixture(autouse=True)
def w17_env(monkeypatch, tmp_path):
    """W17-F2: Hash-File IMMER in den tmp-Pfad umlenken — die Suite darf
    NIEMALS in den Live-State-Dir (/opt-/Plugin-Verband) schreiben. Runden
    auf 1000 — sonst zahlt die Suite je koppltem Handshake PBKDF2-200k.
    B1: Auch die Geräte-Registry lebt je Test in tmp (leere Registry = die
    W17-Regel-Semantik: erste Annahme = Selbst-Adoption, dann Tor)."""
    monkeypatch.setenv("ANDROID_RELAY_PASSWORD_FILE",
                       str(tmp_path / "pairing_password.json"))
    monkeypatch.setenv("ANDROID_RELAY_PW_ITERATIONS", "1000")
    monkeypatch.setenv("ANDROID_RELAY_DEVICE_REGISTRY",
                       str(tmp_path / "relay-devices.json"))
    monkeypatch.setenv("ANDROID_RELAY_KEYS", str(tmp_path / "relay-keys.json"))
    # B1: mTLS-Quelle der Migration ebenfalls in tmp (die W17-Regel-Semantik
    # braucht eine LEERE Start-Registry — die Live-/etc-Source darf die Suite
    # nicht einblenden).
    monkeypatch.setenv("ANDROID_RELAY_TLS_DIR", str(tmp_path / "tls"))
    yield


@pytest.fixture(autouse=True)
def w17_relay_state():
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()
    yield
    stop_relay()
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()


class TestW17SlotLockRules:
    """Reine Regel-Unit (keine Sockets): Ein-Gerät-pro-Code-Entscheidung."""

    def test_free_slot_accepts_any_newcomer(self):
        state = _FakeState(holder=None, holder_id=None)
        assert not _pairing_slot_locked(state, DEVICE_A)
        assert not _pairing_slot_locked(state, DEVICE_B)
        assert not _pairing_slot_locked(state, None)

    def test_dead_holder_is_no_lock(self):
        # W17-Vertrag: TOTE Verbindung = keine Sperre (kein Dauer-Lock).
        state = _FakeState(holder=_FakeHolder(closed=True), holder_id=DEVICE_A)
        assert not _pairing_slot_locked(state, DEVICE_B)
        assert not _pairing_slot_locked(state, DEVICE_A)

    def test_same_device_reconnect_allowed(self):
        state = _FakeState(holder=_FakeHolder(closed=False), holder_id=DEVICE_A)
        assert not _pairing_slot_locked(state, DEVICE_A)

    def test_other_device_rejected_while_holder_alive(self):
        state = _FakeState(holder=_FakeHolder(closed=False), holder_id=DEVICE_A)
        assert _pairing_slot_locked(state, DEVICE_B)

    def test_holder_without_id_stays_legacy_open(self):
        # Legacy-Parität: Inhaber OHNE Kennung + Kommer OHNE Kennung = altes
        # Replace-Verhalten (Upgrade-Welle erlaubt) — bewusst KEINE Sperrung.
        state = _FakeState(holder=_FakeHolder(closed=False), holder_id=None)
        assert not _pairing_slot_locked(state, None)
        # ... aber Kommer MIT Kennung an Legacy-Inhaber = fail-closed gesperrt.
        assert _pairing_slot_locked(state, DEVICE_B)
        # Inhaber MIT Kennung + Kommer OHNE Kennung = ebenso fail-closed.
        state2 = _FakeState(holder=_FakeHolder(closed=False), holder_id=DEVICE_A)
        assert _pairing_slot_locked(state2, None)

    def test_device_id_header_parsing(self):
        assert _parse_device_id({"X-Device-Id": DEVICE_A}) == DEVICE_A
        # Validierung: zu lang / fremde Zeichen / leer = None (keine 500er).
        assert _parse_device_id({"X-Device-Id": "x" * 65}) is None
        assert _parse_device_id({"X-Device-Id": "leer, leer"}) is None
        assert _parse_device_id({}) is None
        assert _parse_device_id(None) is None


class TestW17PasswordHashRules:
    """Reine Hash-Regel-Unit: PBKDF2-Record + konstante-Zeit-Verify."""

    def test_record_roundtrip_and_verify(self):
        record = _pairing_password_record_for(PASSWORD)
        assert _valid_pairing_password_record(record)
        # Kein Klartext im Record (nur Salt/Digest-Hex).
        assert json.dumps(record).find(PASSWORD) < 0
        state = _RelayState(pairing_code=CODE, port=PORT)
        state.pairing_pw = record
        assert _pairing_password_verify(state, PASSWORD)
        assert not _pairing_password_verify(state, "falsch")
        assert not _pairing_password_verify(state, "")
        # Tampering (Digest getauscht) = False, NIE True.
        tampered = dict(record)
        tampered["hash_hex"] = "ff" * 32
        state.pairing_pw = tampered
        assert not _pairing_password_verify(state, PASSWORD)

    def test_rejects_broken_records(self):
        state = _RelayState(pairing_code=CODE, port=PORT)
        # Kein Record → False (fail-closed).
        assert not _pairing_password_verify(state, PASSWORD)
        state.pairing_pw = {"method": "pbkdf2-sha256", "iter": "nix",
                            "salt_hex": "00", "hash_hex": "00"}
        assert not _pairing_password_verify(state, PASSWORD)
        # Form-Prüfung verwirft Müll-Record (Ladepfad).
        assert not _valid_pairing_password_record({"method": "unknowable",
                                                   "iter": 1,
                                                   "salt_hex": "00",
                                                   "hash_hex": "00"})


class TestW17LiveRelaySlotLock:
    """Live-Relay: Zweitgerät-Block + Same-Device-Reconnect + Wiederaufnahme."""

    def test_second_device_403_code_in_use_while_first_alive(self):
        start_relay(pairing_code=CODE, port=PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                first = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_A),
                )
                phone_state = await first.receive_json(timeout=2)
                assert phone_state.get("type") == "relay.state"

                # ZWEITES Gerät, derselbe Code → 403 code-in-use (B1: das Tor
                # behält den Fehlton; EINE Anmelde-Anfrage geht an das bekannte
                # Gerät = Admin der Selbst-Adoption, Rate-Chain zählt weiter).
                info = await _try_handshake(session, DEVICE_B)
                assert info["connected"] is False
                assert info["status"] == 403
                assert info["bridge_error"] == "code-in-use"
                assert is_phone_connected()  # erste Verbindung lebt weiter
                with _auth_lock:
                    assert len(_auth_failures.get("127.0.0.1", [])) >= 1, \
                        "Tor-Abweisung muss in die IP-Rate-Chain zählen"
                request_frame = await first.receive_json(timeout=2)
                assert request_frame.get("type") == "pairing.request", request_frame
                assert "device_id_masked" in request_frame and "request_id" in request_frame
                # Dedupe: dieselbe Kennung im TTL-Fenster erzeugt KEINE zweite
                # Anfrage (kein Frame-Spam auf das Admin-Gerät).
                info2 = await _try_handshake(session, DEVICE_B)
                assert info2["bridge_error"] == "code-in-use"
                with pytest.raises(asyncio.TimeoutError):
                    await first.receive_json(timeout=0.4)

                # Reconnect DESSELBEN Geräts geht IMMER durch (Replace-Muster):
                # alte WS schließt (GOING_AWAY), neue erhält relay.state.
                old_close = asyncio.create_task(first.receive())
                second = await asyncio.wait_for(
                    session.ws_connect(
                        f"ws://127.0.0.1:{PORT}/ws",
                        headers=_ws_headers(DEVICE_A),
                    ),
                    timeout=3,
                )
                old_result = await asyncio.wait_for(old_close, timeout=3)
                assert old_result.type in (
                    aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSING,
                    aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR)
                await second.receive_json(timeout=2)  # relay.state-Hallo
                await second.close()

        asyncio.run(scenario())
        assert _wait_phone_gone_sync(), "Slot sollte nach Ende frei sein"

    def test_slot_frees_after_holder_disconnect(self):
        """W17-Kern + B1: tote erste Verbindung = kein Dauer-Lock — das
        BEKANNTE Gerät kehrt ohne Tor zurück; das UNBEKANNTE Gerät läuft
        aufs Anmelde-Tor (403 + pairing.request an den lebenden Slot)."""
        start_relay(pairing_code=CODE, port=PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                first = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_A),
                )
                await first.receive_json(timeout=2)
                await first.close()
                assert await _wait_phone_gone(5.0), "Slot wurde nicht frei"
                assert _wait_phone_gone_sync()  # Registry auch im Sync-Sinn frei
                # Bekanntes Gerät: direkte Rückkehr (kein Zwangs-Kopplung).
                again = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_A),
                )
                await again.receive_json(timeout=2)
                # Unbekanntes Gerät: Tor (403) + Anfrage am Admin-Slot.
                info = await _try_handshake(session, DEVICE_B)
                assert info["connected"] is False
                assert info["status"] == 403
                assert info["bridge_error"] == "code-in-use"
                request_frame = await again.receive_json(timeout=2)
                assert request_frame.get("type") == "pairing.request"
                # Admin freigibt → der NÄCHSTE Handshake des Geräts enrollt.
                await again.send_json({
                    "type": "pairing.approve",
                    "request_id": request_frame["request_id"]})
                await asyncio.sleep(0.1)  # Frame abarbeiten lassen
                paired = await asyncio.wait_for(
                    session.ws_connect(
                        f"ws://127.0.0.1:{PORT}/ws",
                        headers=_ws_headers(DEVICE_B),
                    ),
                    timeout=3,
                )
                await paired.receive_json(timeout=2)  # relay.state
                await paired.close()
                await again.close()

        asyncio.run(scenario())

    def test_code_rotation_reopens_slot(self):
        """Code-Rotation (W2-1 Kick) räumt die Registrierung MIT: nach dem
        Wechsel koppelt ein ANDERES Gerät mit dem Neuen."""
        start_relay(pairing_code=CODE, port=PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                first = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_A),
                )
                await first.receive_json(timeout=2)
                loop = asyncio.get_running_loop()
                await loop.run_in_executor(None, set_pairing_code, NEUCODE)
                assert await _wait_phone_gone(5.0), \
                    "Rotation hat den Phone-Slot nicht geräumt"
                with pytest.raises(aiohttp.WSServerHandshakeError) as err:
                    await session.ws_connect(
                        f"ws://127.0.0.1:{PORT}/ws",
                        headers={"Authorization": f"Bearer {CODE}"},
                    )
                assert err.value.status in (401, 403)

        asyncio.run(scenario())


class TestW17LiveRelayPasswordGate:
    """F2 live: set/clear/status + WS-Gate + IP-Rate-Chain-Zählung."""

    @staticmethod
    async def _post(session, path, body, bearer=CODE):
        return await session.post(
            f"http://127.0.0.1:{PORT}{path}",
            json=body,
            headers={"Authorization": f"Bearer {bearer}"},
        )

    @staticmethod
    async def _status(session):
        return await session.get(
            f"http://127.0.0.1:{PORT}/password_status",
            headers={"Authorization": f"Bearer {CODE}"},
        )

    def test_set_status_ws_gate_clear_end_to_end(self):
        start_relay(pairing_code=CODE, port=PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                # Status vor Set: inaktiv.
                resp = await self._status(session)
                assert resp.status == 200
                assert await resp.json() == {"active": False}

                # WS ohne Schutz: keine Header nötig (Legacy-Parität).
                ws = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws", headers=_ws_headers(DEVICE_A))
                await ws.receive_json(timeout=2)
                await ws.close()
                assert await _wait_phone_gone(5.0)

                # Set: nur Hash/Status in der Antwort — kein Klartext.
                resp = await self._post(
                    session, "/password_set", {"password": PASSWORD})
                assert resp.status == 200
                data = await resp.json()
                assert data == {"ok": True, "active": True}
                body = await self._status(session)
                assert await body.json() == {"active": True}
                # Kein Klartext im File (nur Record-Felder).
                record = _load_pairing_password_record()
                assert record is not None
                assert PASSWORD not in json.dumps(record)

                # OHNE Passwort → 403 password-required.
                info = await _try_handshake(session, DEVICE_B, None)
                assert info["status"] == 403
                assert info["bridge_error"] == "password-required"

                # FALSCHES Passwort → 403 password-invalid.
                info = await _try_handshake(session, DEVICE_B, "wrong-pw-9")
                assert info["status"] == 403
                assert info["bridge_error"] == "password-invalid"

                # Fehl-Eingaben (fehlend + falsch) laufen in die bestehende
                # IP-Rate-Chain: je Fehl-Eingabe ein Eintrag für die IP.
                with _auth_lock:
                    assert len(_auth_failures.get("127.0.0.1", [])) >= 2

                # Richtiges Passwort + Inhaber-Gerät → Slot belegt (lebt).
                holder = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_A, PASSWORD),
                )
                await holder.receive_json(timeout=2)

                # Richtiges Passwort + ANDERES Gerät: Passwort-Tor = Option b —
                # das W17.2-Passwort verifiziert → DIREKT-Enroll (B1) und
                # Kopplung (kein code-in-use mehr: die Sperre ist zum Tor
                # geworden). Registry-Eintrag für B existiert danach.
                paired = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_B, PASSWORD),
                )
                await paired.receive_json(timeout=2)
                registry = json.loads(
                    Path(os.environ["ANDROID_RELAY_DEVICE_REGISTRY"]).read_text(
                        encoding="utf-8"))
                assert DEVICE_B in registry["devices"], "B wurde nicht enrollt"
                assert DEVICE_A in registry["devices"], "A war Selbst-Adoption"

                # Erster Inhaber stirbt → Wiederaufnahme: bekanntes Gerät B
                # kehrt direkt zurück (kein Dauer-Lock).
                await paired.close()
                assert _wait_slot_gone(DEVICE_B), "B-Slot wurde nicht frei"
                ws2 = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_B, PASSWORD),
                )
                await ws2.receive_json(timeout=2)
                await ws2.close()

                # Clear → Status inaktiv, WS wieder ohne Passwort kopplbar.
                resp = await self._post(session, "/password_clear", {})
                assert resp.status == 200
                assert await resp.json() == {"ok": True, "active": False}
                assert _wait_slot_gone(DEVICE_B), "B-Slot wurde nicht frei"
                # Gerät C (unbekannt, Passwort nun inaktiv) → Tor: 403 +
                # pairing.request am ADMIN-Slot (Admin-Frame → Freigabe →
                # Enroll → Kopplung). Admin-WS verbinden VOR dem Versuch,
                # sonst landet die Anfrage auf einem sterbenden Alt-Slot.
                admin = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_A, PASSWORD),
                )
                await admin.receive_json(timeout=2)
                reject = await _try_handshake(session, DEVICE_C)
                assert reject["status"] == 403
                request_frame = await admin.receive_json(timeout=2)
                assert request_frame.get("type") == "pairing.request", request_frame
                await admin.send_json({
                    "type": "pairing.approve",
                    "device_id": DEVICE_C})
                await asyncio.sleep(0.1)
                third = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_C),
                )
                await third.receive_json(timeout=2)
                await third.close()
                await admin.close()

        asyncio.run(scenario())
        stop_relay()
        # Nach Clear: kein Hash-Record mehr ladbar.
        assert _load_pairing_password_record() is None

    def test_password_min_length_and_bad_body(self):
        start_relay(pairing_code=CODE, port=PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                resp = await self._post(
                    session, "/password_set", {"password": SHORT_PASSWORD})
                assert resp.status == 400
                resp = await self._post(session, "/password_set", {"kein": "pw"})
                assert resp.status == 400
                # Nichts angelegt, Schutz bleibt inaktiv.
                assert await (await self._status(session)).json() == {"active": False}
                # Nach gültigem Set läuft die Längen-Tür weiter (Update-Pfad).
                resp = await self._post(
                    session, "/password_set", {"password": PASSWORD})
                assert resp.status == 200
                resp = await self._post(
                    session, "/password_set", {"password": SHORT_PASSWORD})
                assert resp.status == 400

        asyncio.run(scenario())

    def test_password_routes_require_bearer_and_rate_chain(self):
        start_relay(pairing_code=CODE, port=PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                resp = await session.post(
                    f"http://127.0.0.1:{PORT}/password_set",
                    json={"password": PASSWORD})
                assert resp.status == 401
                resp = await self._post(
                    session, "/password_set", {"password": PASSWORD},
                    bearer="FALSCHER")
                assert resp.status == 401
                resp = await session.get(
                    f"http://127.0.0.1:{PORT}/password_status")
                assert resp.status == 401
                assert is_phone_connected() is False

        asyncio.run(scenario())
