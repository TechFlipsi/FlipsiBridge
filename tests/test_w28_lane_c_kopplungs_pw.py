"""W28 Lane C: Kopplungs-Passwort NUR am Neu-Gerät-Enrollment (Umfang-Fix Besitzer).

Umfang-Fix (17.11.26): Passwort-Abfrage NUR bei Einrichtung/Kopplung —
laufende/bereits gekoppelte (BEKANNTE) Geräte bleiben unberührt. Der W17-F2-
Bestand erzwingt den Header 'X-Pairing-Password' auf JEDER WS-Verbindung;
dieser Verband pinnt die Verengung des Tors auf die NEU-Kopplung:

  - BEKANNTES Gerät reconnectet OHNE Passwort, während der Schutz aktiv ist
    (vorher: 403 password-required — ein zweites, bereits gekoppeltes Gerät
    ohne gespeichertes Passwort wurde ausgesperrt);
  - UNBEKANNTE Geräte laufen unverändert durchs Tor: fehlend = 403
    password-required, falsch = 403 password-invalid (beides IP-Rate-Chain),
    richtig = Direkt-Enroll (B1 Option b);
  - ein frisch über das Passwort enrolltes Gerät reconnectet danach OHNE
    Passwort (es ist bekannt — genau das ist der Umfang-Fix);
  - Regel-Unit: _device_gate_decide traut dem pw_verified-Argument, NICHT dem
    bloßen 'Schutz gesetzt' (fail-closed — kippt der Registry-Stand zwischen
    Tor und Entscheidung, bleibt der PW-Zweig zu).

Kein Klartext-Passwort in Assertions oder Logs: die Passwort-Konstanten sind
Test-Platzhalter, niemals echte Credentials; assertions prüfen nur Status-
Codes, Header-Masken und Registry-Schlüssel.
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
    _device_gate_decide,
    _auth_lock,
    _auth_blocked,
    _auth_failures,
)

PORT = 19914
CODE = "LANECPW28"
DEVICE_A = "phone-alpha"
DEVICE_B = "phone-beta"
PASSWORD = "bruecken-pass-28"       # Test-Platzhalter, kein echtes Credential
WRONG_PASSWORD = "falsch-aber-lang"  # Test-Platzhalter


def _ws_headers(device_id=None, password=None):
    headers = {"Authorization": f"Bearer {CODE}"}
    if device_id:
        headers["X-Device-Id"] = device_id
    if password:
        headers["X-Pairing-Password"] = password
    return headers


async def _try_handshake(session, device_id=None, password=None):
    """WS-Handshake-Versuch als Info-Dict (Kanal-Parität zum W17-Verband:
    aiohttp trägt Status + Header, der Maschinen-Code läuft über
    'X-Bridge-Error' — die App liest denselben Header)."""
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


def _wait_slot_gone(device_id: str, timeout: float = 5.0) -> bool:
    """Warten bis genau DIESE Geräte-Verbindung geräumt ist (B1-Muster)."""
    from tools.android_relay import _relay_instance
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = _relay_instance
        if state is None or device_id not in (state.devices or {}):
            return True
        time.sleep(0.05)
    return False


async def _post_json(session, path, body):
    return await session.post(
        f"http://127.0.0.1:{PORT}{path}",
        json=body,
        headers={"Authorization": f"Bearer {CODE}"},
    )


@pytest.fixture(autouse=True)
def w28_env(monkeypatch):
    """PBKDF2-Runden auf 1000 — sonst zahlt die Suite je Handshake 200k."""
    monkeypatch.setenv("ANDROID_RELAY_PW_ITERATIONS", "1000")
    yield


@pytest.fixture(autouse=True)
def w28_relay_state():
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()
    yield
    stop_relay()
    with _auth_lock:
        _auth_blocked.clear()
        _auth_failures.clear()


class TestW28UmfangFixBekannteGeraete:
    """Ende-zu-Ende: das Passwort-Tor verlangt NUR die Neu-Kopplung."""

    def test_bekannt_reconnect_ohne_pw_unbekannt_weiter_tor(self):
        start_relay(pairing_code=CODE, port=PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                # A koppelt (leere Registry → Selbst-Adoption), Schutz aus.
                first = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_A),
                )
                await first.receive_json(timeout=2)
                await first.close()

                # Schutz aktivieren (Hash-only, min 8 — Bestands-Route).
                resp = await _post_json(session, "/password_set",
                                        {"password": PASSWORD})
                assert resp.status == 200
                assert await resp.json() == {"ok": True, "active": True}

                # Umfang-Fix-Kern: A ist BEKANNT → Reconnect OHNE Passwort.
                again = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_A),
                )
                await again.receive_json(timeout=2)
                await again.close()

                # UNBEKANNTES B bleibt am Tor: fehlend = password-required …
                info = await _try_handshake(session, DEVICE_B, None)
                assert info["connected"] is False
                assert info["status"] == 403
                assert info["bridge_error"] == "password-required"
                # … falsch = password-invalid; beides in die IP-Rate-Chain.
                info = await _try_handshake(session, DEVICE_B, WRONG_PASSWORD)
                assert info["status"] == 403
                assert info["bridge_error"] == "password-invalid"
                with _auth_lock:
                    assert len(_auth_failures.get("127.0.0.1", [])) >= 2

                # Richtiges Passwort → Direkt-Enroll (B1 Option b) …
                paired = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_B, PASSWORD),
                )
                await paired.receive_json(timeout=2)
                registry = json.loads(
                    Path(os.environ["ANDROID_RELAY_DEVICE_REGISTRY"]).read_text(
                        encoding="utf-8"))
                assert DEVICE_B in registry["devices"], "B nicht enrollt"

                # … und danach ist B BEKANNT: Reconnect OHNE Passwort.
                await paired.close()
                assert _wait_slot_gone(DEVICE_B)
                back = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_B),
                )
                await back.receive_json(timeout=2)
                await back.close()

        asyncio.run(scenario())

    def test_kein_pw_gesetzt_unveraendert(self):
        """Ohne gesetzten Schutz bleibt der Bestand: bekannt verbindet,
        unbekannt läuft ins B1-Anmelde-Tor (403 code-in-use)."""
        start_relay(pairing_code=CODE, port=PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                resp = await session.get(
                    f"http://127.0.0.1:{PORT}/password_status",
                    headers={"Authorization": f"Bearer {CODE}"},
                )
                assert resp.status == 200
                assert await resp.json() == {"active": False}

                known = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_A),
                )
                await known.receive_json(timeout=2)
                await known.close()

                info = await _try_handshake(session, DEVICE_B, None)
                assert info["connected"] is False
                assert info["status"] == 403
                assert info["bridge_error"] == "code-in-use"

        asyncio.run(scenario())

    def test_fuenf_fehlversuche_sperren_den_sechsten_mit_429(self):
        """Reviewer-Auflage (P3): die Rate-Chain am PW-Tor wird VOLL gepinnt
        — nicht nur das Mitzaehlen (>= 2), sondern 5 Fehlversuche von EINER
        IP sperren den 6. Handshake mit 429, selbst mit richtigem Passwort
        (IP-Block greift am Handshake-Start, fail-closed)."""
        start_relay(pairing_code=CODE, port=PORT)

        async def scenario():
            async with aiohttp.ClientSession() as session:
                # A bekannt machen (leere Registry → Selbst-Adoption), Schutz an.
                first = await session.ws_connect(
                    f"ws://127.0.0.1:{PORT}/ws",
                    headers=_ws_headers(DEVICE_A),
                )
                await first.receive_json(timeout=2)
                await first.close()
                resp = await _post_json(session, "/password_set",
                                        {"password": PASSWORD})
                assert resp.status == 200

                # 5 Fehlversuche (falsches PW, unbekanntes Geraet), gleiche IP.
                for _ in range(5):
                    info = await _try_handshake(session, DEVICE_B,
                                                 WRONG_PASSWORD)
                    assert info["connected"] is False
                    assert info["status"] == 403

                # 6. Versuch: selbst das RICHTIGE Passwort faellt auf den
                # IP-Block → 429 (Tor bleibt dicht, keine PW-Umgehung).
                blocked = await _try_handshake(session, DEVICE_B, PASSWORD)
                assert blocked["connected"] is False
                assert blocked["status"] == 429

        asyncio.run(scenario())


class TestW28GateRegelUnit:
    """Reine Regel-Unit (keine Sockets): pw_verified ist das einzige Ticket
    für den W17.2-Zweig — NICHT der bloße `Schutz gesetzt`."""

    class _FakeState:
        def __init__(self, registry=None, pairing_pw=None):
            self.device_registry = registry or {}
            self.pairing_approvals = {}
            self.pairing_requests = {}
            self.pairing_pw = pairing_pw
            self.device_cap = 4

    def test_pw_gesetzt_allein_oeffnet_das_tor_nicht_mehr(self):
        """Fail-closed-Pin: UNBEKANNT + Schutz gesetzt + KEINE Verifikation
        (Registry-Stand kippte zwischen Tor und Entscheidung) → rejected,
        NICHT enroll (der Alt-Stand ging hier durch)."""
        state = self._FakeState(
            registry={DEVICE_A: {"admin": True}},
            pairing_pw={"v": 1, "method": "pbkdf2-sha256", "iter": 1000,
                        "salt_hex": "00" * 16, "hash_hex": "ff" * 32},
        )
        assert _device_gate_decide(state, DEVICE_B, "10.0.0.9") == (
            "rejected", DEVICE_B)

    def test_pw_verifiziert_enrollt_direkt(self):
        state = self._FakeState(
            registry={DEVICE_A: {"admin": True}},
            pairing_pw={"v": 1, "method": "pbkdf2-sha256", "iter": 1000,
                        "salt_hex": "00" * 16, "hash_hex": "ff" * 32},
        )
        assert _device_gate_decide(state, DEVICE_B, "10.0.0.9",
                                   pw_verified=True) == ("enroll", DEVICE_B)

    def test_bekannt_bleibt_ohne_passwort_accepted(self):
        state = self._FakeState(
            registry={DEVICE_A: {"admin": True}},
            pairing_pw={"v": 1, "method": "pbkdf2-sha256", "iter": 1000,
                        "salt_hex": "00" * 16, "hash_hex": "ff" * 32},
        )
        assert _device_gate_decide(state, DEVICE_A, "10.0.0.9") == (
            "accepted", DEVICE_A)

    def test_admin_freigabe_bleibt_alternative_zum_passwort(self):
        """B1-Vertrag unverändert: gültige Freigabe öffnet AUCH ohne Passwort
        (Freigabe ODER Passwort — die Freigabe ist bewusster Admin-Entschluss)."""
        state = self._FakeState(
            registry={DEVICE_A: {"admin": True}},
            pairing_pw={"v": 1, "method": "pbkdf2-sha256", "iter": 1000,
                        "salt_hex": "00" * 16, "hash_hex": "ff" * 32},
        )
        state.pairing_approvals[DEVICE_B] = {
            "expires": time.time() + 600, "used": False}
        assert _device_gate_decide(state, DEVICE_B, "10.0.0.9") == (
            "enroll", DEVICE_B)
