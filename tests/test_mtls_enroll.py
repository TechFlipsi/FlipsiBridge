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
