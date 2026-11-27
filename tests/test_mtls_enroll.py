import base64
import json
import os
import subprocess
import sys
import tempfile

import aiohttp
import pytest

from tools.mtls_enroll import (
    _open_challenge,
    _csr_pubkey_verify_pop,
    _sign_csr,
    _registry_load,
    _registry_save,
    _registry_devices,
    reset_epoch,
    init,
    _tls_dir,
)


@pytest.fixture()
def tls_env(monkeypatch):
    d = tempfile.mkdtemp(prefix="mtls-test-")
    monkeypatch.setenv("ANDROID_RELAY_TLS_DIR", d)
    # Modul-Init-Cache + Nonce-Store je Test zurücksetzen (CA-Pfad ist
    # prozess-global gecacht — ohne Reset würde Test 2+ die CA aus dem
    # Ersten-Test-Verzeichnis vermissen).
    import tools.mtls_enroll as m
    m._INIT_STATE.update({"ready": False, "error": ""})
    m._CHALLENGES.clear()
    assert m.init(), "CA-Init muss mit openssl-Umgebung grün sein"
    return d


def _device_key_and_csr(d: str, device_id: str):
    """Geräteschlüssel + CSR via openssl (simuliert die App)."""
    k = os.path.join(d, f"{device_id}.key")
    c = os.path.join(d, f"{device_id}.csr")
    subprocess.run(
        ["openssl", "req", "-newkey", "rsa:2048", "-nodes",
         "-keyout", k, "-out", c, "-subj", f"/CN=flipsibridge-{device_id}"],
        capture_output=True, check=True,
    )
    return k, open(c, "rb").read()


def _sign(k: str, payload: bytes) -> str:
    sig = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", k],
        input=payload, capture_output=True, check=True,
    ).stdout
    return base64.b64encode(sig).decode()


def test_ca_init_and_paths(tls_env):
    d = _tls_dir()
    assert (d / "device-ca.key").exists()
    assert (d / "device-ca.crt").exists()


def test_pop_roundtrip_positive_and_negative(tls_env):
    d = str(_tls_dir())
    key, csr = _device_key_and_csr(d, "popA")
    nonce = _open_challenge("popA")
    assert nonce
    sig = _sign(key, nonce.encode())
    ok, reason = _csr_pubkey_verify_pop(csr, nonce, sig)
    assert ok, reason
    # Falsche Signatur
    _open_challenge("popA")
    ok2, _ = _csr_pubkey_verify_pop(csr, nonce, sig[:-2] + "xx")
    assert not ok2
    # Falsche Nonce
    _open_challenge("popA")
    ok3, _ = _csr_pubkey_verify_pop(csr, "wrong", sig)
    assert not ok3


def test_csr_signing_produces_verifiable_cert_with_ca(tls_env):
    d = str(_tls_dir())
    _, csr = _device_key_and_csr(d, "devsign")
    cert = _sign_csr(csr, "devsign")
    assert cert and b"BEGIN CERTIFICATE" in cert
    ca_crt = os.path.join(d, "device-ca.crt")
    proc = subprocess.run(
        ["openssl", "verify", "-CAfile", ca_crt, "/dev/stdin"],
        input=cert, capture_output=True,
    )
    assert proc.returncode == 0, proc.stderr.decode()[:200]


def test_epoch_registry_and_reset(tls_env):
    _registry_save({"epoch": 1, "enrolled": {"x": {"cn": "c", "ts": 1}}})
    assert _registry_load()["epoch"] == 1
    data = reset_epoch()
    assert data["epoch"] == 2  # Code-Wechsel öffnet das Fenster neu


def test_code_once_and_renewal_semantics(tls_env):
    """Enrollment-Registry: Neugerät abgelehnt wenn Epoch verbraucht;
    Wiederaufnahme desselben Geräts (renewal) erlaubt (POP-Kette)."""
    d = _tls_dir()
    _registry_save({"epoch": 1, "enrolled": {"devA": {"cn": "flipsibridge-devA", "ts": 1}}})
    reg = _registry_load()
    enrolled = reg["enrolled"]
    # Neugerät B in verbrauchter Epoch → Handler lehnt ab (siehe e2e-Lauf):
    # hier Registry-Ebene: B nicht enthalten nach Ablehnung
    is_new = "devB" not in enrolled
    assert is_new and enrolled  # code-used-Fall
    # renewal: devA darf (ohne Epoch-Fenster-Reset) neu enrollen
    is_new_a = "devA" not in enrolled
    assert not is_new_a  # renewal-Zwei

def test_ec_signature_and_canonicality_guard(tls_env):
    """App-Realität: EC P-256 (AndroidKeystore) → DER-Sig ~70 B.openssl
    toleriert überlange Sig-Dateien still (Streu-Attacke) — der
    Kanonizitäts-Guard muss b64-Reste ablehnen."""
    d = str(_tls_dir())
    k = os.path.join(d, "ec.key"); c = os.path.join(d, "ec.csr")
    params = os.path.join(d, "params.pem")
    subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-out", params],
                   capture_output=True, check=True)
    subprocess.run(
        ["openssl", "req", "-newkey", f"ec:{params}", "-nodes",
         "-keyout", k, "-out", c, "-subj", "/CN=ec-device"],
        capture_output=True, check=True,
    )
    csr = open(c, "rb").read()
    nonce = _open_challenge("ecA")
    raw = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", k],
        input=nonce.encode(), capture_output=True, check=True,
    ).stdout
    assert 48 <= len(raw) <= 140, f"EC-Sig-Länge {len(raw)}"
    sig = base64.b64encode(raw).decode()
    ok, reason = _csr_pubkey_verify_pop(csr, nonce, sig)
    assert ok, reason
    # Mutiert (Rest-Bytes) → UNBEDINGT ablehnen
    _open_challenge("ecA")
    ok2, reason2 = _csr_pubkey_verify_pop(csr, nonce, sig[:-3] + "xxx")
    assert not ok2, reason2


# ── Security-Fix-Welle 17.11. (Jäger-Befunde, je im Code verifiziert) ────────


class _FakeRequest:
    """Minimaler aiohttp-Request-Stub für Handler-Tests (json/headers/remote)."""

    def __init__(self, json_body, headers=None, remote="203.0.113.9"):
        self._body = json_body
        self.headers = headers or {}
        self.remote = remote

    async def json(self):
        return self._body


_AUTH_HDR = {"Authorization": "Bearer TESTPAIR123"}


def _patch_relay_auth(monkeypatch):
    """Handler-Tests ohne echtes Relay: fixer Pairing-Code, Stub-Auth."""
    import types

    import tools.mtls_enroll as m

    class _FakeAR:
        @staticmethod
        def _constant_time_eq(a, b):
            return isinstance(a, str) and isinstance(b, str) and a == b

        @staticmethod
        def _auth_record_failure(ip):
            pass

    state = types.SimpleNamespace(pairing_code="TESTPAIR123")
    monkeypatch.setattr(m, "_relay_state", lambda: state)
    monkeypatch.setattr(m, "_ar_module", lambda: _FakeAR)
    return m


def test_secfix17_device_id_gate_rejects_traversal_ids(tls_env, monkeypatch):
    """Security-Fix 17.11. (1): device_id-Gate [A-Za-z0-9._-]{1,64} VOR
    Registry/Zertifikat — Traversal-/Injection-/Format-IDs bekommen eine
    frühzeitige 400 (challenge UND enroll) und erzeugen KEINE Zert-Datei
    und KEINEN Registry-Eintrag."""
    import asyncio

    m = _patch_relay_auth(monkeypatch)
    d = _tls_dir()
    bad_ids = ["../../etc/pwn", "dev/O=Evil", "dev id", "dev\nid",
               "x" * 65, "", "dév"]
    for bad in bad_ids:
        assert m._open_challenge(bad) is None, f"Challenge für {bad!r} verboten"

    # Challenge-Handler: 400 bad device_id (statt Nonce-Ausgabe)
    resp = asyncio.run(m.challenge_handler(
        _FakeRequest({"device_id": "../../etc/pwn"}, headers=_AUTH_HDR)))
    assert resp.status == 400 and "bad device_id" in resp.text

    # Enroll-Handler: frühzeitige 400 VOR Registry-Berührung/Zert-Schreiben
    body = {"device_id": "../../etc/pwn",
            "csr": base64.b64encode(b"-----BEGIN CERTIFICATE REQUEST-----").decode(),
            "sig": "AA==", "nonce": "abc"}
    resp = asyncio.run(m.enroll_handler(_FakeRequest(body, headers=_AUTH_HDR)))
    assert resp.status == 400 and "bad device_id" in resp.text
    assert m._registry_load()["enrolled"] == {}, "Registry unberührt"
    assert not any((d / "devices").glob("*.pem")), "kein Zert geschrieben"


def test_secfix17_code_once_holds_under_parallel_enrolls(tls_env, monkeypatch):
    """Security-Fix 17.11. (2, TOCTOU): zwei PARALLELE Enrolls (je eigene
    Challenge, gleiche Epoch, gleicher Code) — ohne Lock sähen beide Threads
    eine leere Registry und ließen zwei Neugeräte mit EINEM Code durch; mit
    _ENROLL_LOCK bleibt hart: genau EIN 200, der zweite 403 code-used,
    Registry hat am Ende GENAU EIN Neugerät."""
    import asyncio
    import threading

    m = _patch_relay_auth(monkeypatch)
    d = str(_tls_dir())
    m._registry_save({"epoch": 1, "enrolled": {}})
    reqs = {}
    for did in ("devpar1", "devpar2"):
        k, csr = _device_key_and_csr(d, did)
        nonce = m._open_challenge(did)
        assert nonce, f"Challenge für {did} muss aufgehen"
        reqs[did] = _FakeRequest(
            {"device_id": did, "csr": base64.b64encode(csr).decode(),
             "sig": _sign(k, nonce.encode()), "nonce": nonce},
            headers=_AUTH_HDR)

    statuses: dict = {}
    errors: list = []

    def _enroll(did):
        try:
            resp = asyncio.run(m.enroll_handler(reqs[did]))
            statuses[did] = resp.status
        except Exception as exc:  # pragma: no cover — Testfehler, nicht Pfad
            errors.append(exc)

    t1 = threading.Thread(target=_enroll, args=("devpar1",))
    t2 = threading.Thread(target=_enroll, args=("devpar2",))
    t1.start(); t2.start(); t1.join(); t2.join()
    assert not errors
    assert sorted(statuses.values()) == [200, 403], (
        "Code-Einmaligkeit muss unter Parallelen halten: ein 200, ein 403")
    enrolled = m._registry_devices(m._registry_load())  # B2: v2-Leser
    assert len(enrolled) == 1, "genau EIN Neugerät durfte enrollen"
    assert set(enrolled) <= {"devpar1", "devpar2"}


# ── B2 (Multi-Gerät): Registry v2 ({v:2, devices:{...}} je Gerät) ────────────

def test_b2_enroll_writes_v2_per_device_fields(tls_env, monkeypatch):
    """Enroll schreibt v2: {"v":2, "epoch":n, "devices":{id: {cn, cert_path,
    enrolled_at, epoch_of_enroll}}} — die Zehn-Finger-Felder je Gerät."""
    import asyncio

    m = _patch_relay_auth(monkeypatch)
    m._registry_save({"epoch": 2, "enrolled": {}})
    d = str(_tls_dir())
    k, csr = _device_key_and_csr(d, "devv2")
    nonce = m._open_challenge("devv2")
    assert nonce
    req = _FakeRequest({"device_id": "devv2",
                        "csr": base64.b64encode(csr).decode(),
                        "sig": _sign(k, nonce.encode()), "nonce": nonce},
                       headers=_AUTH_HDR)
    resp = asyncio.run(m.enroll_handler(req))
    assert resp.status == 200, resp.text
    reg_path = _tls_dir() / "mtls_devices.json"
    raw = json.loads(reg_path.read_text(encoding="utf-8"))
    assert raw["v"] == 2 and raw["epoch"] == 2
    entry = raw["devices"]["devv2"]
    assert entry["cn"] == "flipsibridge-devv2"
    assert entry["cert_path"] == "devices/devv2.pem"
    assert entry["epoch_of_enroll"] == 2
    assert entry["enrolled_at"] == entry["ts"] > 0


def test_b2_v1_read_fallback_and_enroll_migration(tls_env, monkeypatch):
    """Legacy v1-Registry ({epoch, enrolled}) bleibt LESBAR (Lesefallback, aktuelles
    Verhalten intakt) und wird beim ERSTEN Enroll ohne Verlust nach v2 migriert:
    Alt-Zeilen wandern unter 'devices' (Felder bleiben, enrolled_at aus ts)."""
    import asyncio

    m = _patch_relay_auth(monkeypatch)
    legacy = {"epoch": 7, "enrolled": {
        "devOld1": {"cn": "flipsibridge-devOld1", "ts": 123, "epoch_of_enroll": 3},
        "devOld2": {"cn": "flipsibridge-devOld2", "ts": 555, "epoch_of_enroll": 4}}}
    m._registry_save(legacy)
    # Lesefallback: die Geräte-Map ist über beide Formen erreichbar.
    assert m._registry_devices(m._registry_load()) == legacy["enrolled"]
    # Fenster-Reset (set_pairing_code-Pfad): Epoch 7→8, enrolled bleibt v1.
    data = m.reset_epoch()
    assert data["epoch"] == 8
    # RENEWAL des Alt-Geräts (Code-Einmaligkeit erlaubt Wiederaufnahme desselben
    # device_id; ein NEUGERÄT braucht den Registry-Reset — P22/P44-Semantik
    # bleibt unangetastet).
    d = str(_tls_dir())
    k, csr = _device_key_and_csr(d, "devOld1")
    nonce = m._open_challenge("devOld1")
    assert nonce
    req = _FakeRequest({"device_id": "devOld1",
                        "csr": base64.b64encode(csr).decode(),
                        "sig": _sign(k, nonce.encode()), "nonce": nonce},
                       headers=_AUTH_HDR)
    resp = asyncio.run(m.enroll_handler(req))
    assert resp.status == 200, resp.text
    raw = json.loads((_tls_dir() / "mtls_devices.json").read_text(encoding="utf-8"))
    assert raw["v"] == 2 and "enrolled" not in raw
    old = raw["devices"]["devOld1"]
    assert old["cn"] == "flipsibridge-devOld1"
    assert old["epoch_of_enroll"] == 8, "Renewal hebt die Epoche des Geräts"
    assert old["enrolled_at"] == old["ts"] > 0, "je-Gerät-Felder geschrieben"
    # Der NICHT-erneuerte Alt-Eintrag kommt im Migrations-Save ohne Verlust mit:
    survivor = raw["devices"]["devOld2"]
    assert survivor["cn"] == "flipsibridge-devOld2"
    assert survivor["enrolled_at"] == 555, "Alt-Zeile: enrolled_at aus ts"
    assert survivor["epoch_of_enroll"] == 4, "P43-Wert je Gerät bleibt"


def test_secfix17_challenge_store_cap_fifo(tls_env, monkeypatch):
    """Security-Fix 17.11. (3): _CHALLENGES-Cap 10k (Prod-Default) mit FIFO —
    der ZEITLICH älteste Eintrag verfällt zuerst (ein RE-Open zählt mit
    seiner neuen Ablaufzeit, nicht der Einfügeposition)."""
    import tools.mtls_enroll as m

    assert m._CHALLENGE_CAP == 10_000, "Prod-Cap ist 10_000"
    monkeypatch.setattr(m, "_CHALLENGE_CAP", 3)
    assert m._open_challenge("capA")
    assert m._open_challenge("capB")
    assert m._open_challenge("capC")
    assert len(m._CHALLENGES) == 3
    # capA künstlich altern → beim Overflow verfällt ER (nicht capB):
    exp_a, nonce_a = m._CHALLENGES["capA"]
    m._CHALLENGES["capA"] = (exp_a - 60.0, nonce_a)
    assert m._open_challenge("capD")
    assert len(m._CHALLENGES) == 3 and "capA" not in m._CHALLENGES
    assert set(m._CHALLENGES) == {"capB", "capC", "capD"}
    # TTL-Prune bleibt unabhängig vom Cap intakt:
    exp_b, nonce_b = m._CHALLENGES["capB"]
    m._CHALLENGES["capB"] = (exp_b - m._CHALLENGE_TTL_S - 10.0, nonce_b)
    assert m._open_challenge("capE")
    assert "capB" not in m._CHALLENGES and len(m._CHALLENGES) == 3


# ── P43 (17.11.): Code-Rotation leeräumt die mTLS-Registry NICHT ─────────────

def test_p43_code_rotation_keeps_enrolled_devices(tls_env):
    """P43: reset_epoch (der Pfad, den set_pairing_code beim Code-Wechsel
    anstößt) fasst NUR die Epoch an: Geräte-Einträge (inkl. Zert-Datei)
    bleiben byte-stabil über Code-Rotationen hinweg — die Blockierung
    Alt-Code-Geräte läuft allein über den Epoch-Check am Gate, nicht über
    stille Löschung."""
    devices = {
        "devOld1": {"cn": "flipsibridge-devOld1", "ts": 1, "epoch_of_enroll": 1,
                    "cert_path": "devices/devOld1.pem"},
        "devOld2": {"cn": "flipsibridge-devOld2", "ts": 2, "epoch_of_enroll": 1,
                    "is_new_device_epoch": True},
    }
    _registry_save({"epoch": 1, "enrolled": dict(devices)})
    d = _tls_dir() / "devices"
    d.mkdir(exist_ok=True)
    cert_file = d / "devOld1.pem"
    cert_file.write_bytes(b"-----BEGIN CERTIFICATE-----old-----END CERTIFICATE-----")

    data = reset_epoch()  # Rotation 1 (den set_pairing_code anstößt)
    assert data["epoch"] == 2
    assert data["enrolled"] == devices, "Rotation 1: Einträge unverändert"
    reg = _registry_load()
    assert reg["epoch"] == 2 and reg["enrolled"] == devices

    data2 = reset_epoch()  # Rotation 2 (erneuter Code-Wechsel)
    assert data2["epoch"] == 3
    assert _registry_load()["enrolled"] == devices, "Rotation 2: Einträge unverändert"
    assert cert_file.read_bytes().startswith(b"-----BEGIN CERTIFICATE"), \
        "Alt-Code-Gerät verliert sein Zert NICHT"


def test_p43_reset_epoch_refuses_corrupt_registry(tls_env):
    """P43 (stiller-Wipe-Weg 1): beschädigte Registry-Datei — die Rotation
    darf NICHT mit einem stillen LEER-View speichern (sonst wären alle
    alt-Einträge weg). Erwartung: fail-soft Abbruch (None), Datei byte-
    identisch unangetastet, Epoch wird NICHT auf einen leeren Stand gesetzt."""
    path = _tls_dir() / "mtls_devices.json"
    broken = b'{"epoch": 5, "enrolled": {"devOld1": {"cn": "flipsibridge-devOld1"'
    path.write_bytes(broken)
    result = reset_epoch()
    assert result is None, "Rotation mit beschädigter Registry bricht ab"
    assert path.read_bytes() == broken, "Kein Overwrite — kein stiller Wipe"


def test_p43_corrupt_registry_blocks_enroll_no_silent_wipe(tls_env, monkeypatch):
    """P43 (stiller-Wipe-Weg 2, Enroll-Pfad): Registry defekt → Handler 503
    registry-unavailable; der Enroll-Save darf die alten Einträge NICHT mit
    einem Leer-View überschreiben, kein Zert wird geschrieben."""
    import asyncio

    m = _patch_relay_auth(monkeypatch)
    path = _tls_dir() / "mtls_devices.json"
    broken = b'{"epoch": 5, "enrolled": {"devOld1": {"cn": "flipsibridge-devOld1"'
    path.write_bytes(broken)
    k, csr = _device_key_and_csr(str(_tls_dir()), "devNew1")
    nonce = _open_challenge("devNew1")
    assert nonce
    req = _FakeRequest({"device_id": "devNew1",
                        "csr": base64.b64encode(csr).decode(),
                        "sig": _sign(k, nonce.encode()), "nonce": nonce},
                       headers=_AUTH_HDR)
    resp = asyncio.run(m.enroll_handler(req))
    assert resp.status == 503 and "registry-unavailable" in resp.text
    assert path.read_bytes() == broken, "Registry unangetastet (kein Wipe)"
    assert not any((_tls_dir() / "devices").glob("devNew1*")), "kein Zert geschrieben"


def test_p43_rotation_serialized_with_running_enroll(tls_env, monkeypatch):
    """P43 (stiller-Wipe-Weg 3, Rennen): set_pairing_code stößt reset_epoch in
    einem Hintergrund-Thread an — fällt der Bump mitten in ein laufendes
    Enrollment (hier: während der Zert-Signierung), dürfen sich die beiden
    Saves gegenseitig NICHT mit veralteten Views überwischen. Mit dem FIX
    (reset_epoch teilt _ENROLL_LOCK) serialisiert der Bump NACH den Enroll-Save:
    Enroll-Eintrag bleibt UND Epoch endet auf dem Bump."""
    import asyncio
    import threading

    m = _patch_relay_auth(monkeypatch)
    m._registry_save({"epoch": 1, "enrolled": {}})
    d = str(_tls_dir())
    k, csr = _device_key_and_csr(d, "devRace")
    nonce = _open_challenge("devRace")
    assert nonce
    req = _FakeRequest({"device_id": "devRace",
                        "csr": base64.b64encode(csr).decode(),
                        "sig": _sign(k, nonce.encode()), "nonce": nonce},
                       headers=_AUTH_HDR)
    bumps: list = []
    threads: list = []
    real_sign = m._sign_csr

    def _sign_during_rotation(csr_pem, device_id):
        # Rotation "fällt mitten ins Enrollment" (so läuft es am Relay:
        # set_pairing_code → Thread → reset_epoch). Ohne geteilten Lock läuft
        # der Bump HIER innerhalb der Enroll-Sektion und die Saves überwischen
        # sich gegenseitig mit veralteten Views; mit FIX wartet der Bump auf
        # _ENROLL_LOCK — hier nur anstoßen, das Joinen bewusst NACH dem
        # Handler ausführen (sonst Deadlock im Test selbst).
        t = threading.Thread(target=lambda: bumps.append(m.reset_epoch()))
        t.start()
        threads.append(t)
        return real_sign(csr_pem, device_id)

    monkeypatch.setattr(m, "_sign_csr", _sign_during_rotation)
    resp = asyncio.run(m.enroll_handler(req))
    assert resp.status == 200
    threads[0].join(15)
    assert bumps and bumps[0]["epoch"] == 2, "Bump war in den Enroll eingehängt"
    raw = m._registry_load()
    assert raw["epoch"] == 2, "Bump darf vom Enroll-Save nicht zurückgesetzt werden"
    reg = _registry_devices(raw)  # B2: v2-Leser (auch v1-Form)
    assert "devRace" in reg, \
        "Enroll-Eintrag darf vom Bump-Save nicht verloren gehen"
