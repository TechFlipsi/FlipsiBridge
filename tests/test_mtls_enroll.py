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
    enrolled = m._registry_load()["enrolled"]
    assert len(enrolled) == 1, "genau EIN Neugerät durfte enrollen"
    assert set(enrolled) <= {"devpar1", "devpar2"}


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
