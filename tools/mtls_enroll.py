#!/usr/bin/env python3
"""W16.3 (P41, Sir 16.11.: „Zertifikat wählen tut nichts — mTLS nachrüsten“).

Enrollment-Verband für Geräte-Zertifikate am FlipsiBridge-Relay.

Zwei Türen (P22-Design, vereinfachte Stufe 1 — ENFORCEMENT folgt in Stufe 2
mit dem nginx/NPM-Verband):
  * POST /mtls/enroll/challenge — code-gated (Bearer = Kopplungscode),
    ein Weg: Nonce (5-min-TTL, one-shot) für den Proof-of-Possession.
  * POST /mtls/enroll          — code-gated + POP: App schickt
    {device_id, csr_pem, sig_b64}; Server prüft die Signatur der Nonce
    gegen den CSR-PubKey (openssl dgst -verify) — der private Schlüssel
    bleibt EXKLUSIV im AndroidKeystore der App (non-exportable).
    Server signiert die CSR mit seiner Device-CA (RSA 3072, 400 Tage)
    und legt den Eintrag in die Device-Registry.

CODE-EINMALIGKEIT (Sir: „nur einmal die Codes gehen“): pro
Pairing-Epoch darf der Code GENAU EIN Neugerät enrollen (renewals derselben
device_id laufen als Zertifikats-Verifikationsweg OHNE Code). set_pairing_code() (= Code-Neuerzeugung) schaltet das Fenster mit der Epoch zurück.

Fail-soft: fehlende OpenSSL-Tools/Verzeichnisse = Enrollment-Off + Log,
der REST des Relays läuft unverändert weiter. Kein P25-Verband wird
berührt; Enforcement (CERT_REQUIRED am Socket) bleibt bewusst AUSSERHALB
dieser Stufe — Server akzeptiert weiter ohne mTLS, sammelt aber Geräte.
"""
from __future__ import annotations

import base64
import json
import logging
import os
import re
import secrets
import subprocess
import threading
import time
from pathlib import Path
from typing import Any, Optional, Tuple

logger = logging.getLogger("flipsibridge.mtls_enroll")

_DEFAULT_TLS_DIR = "/etc/flipsibridge/tls"


def _tls_dir() -> Path:
    """Env LAZY je Aufruf (Tests können den Pfad je Lauf umstellen)."""
    return Path(os.getenv("ANDROID_RELAY_TLS_DIR", _DEFAULT_TLS_DIR))


def _tls_paths():
    d = _tls_dir()
    return d, d / "device-ca.key", d / "device-ca.crt", d / "mtls_devices.json", d / "devices"
_CHALLENGE_TTL_S = 300.0
_CERT_DAYS = 400
_CA_DAYS = 3650
# Security-Fix-Welle 17.11.: Challenge-Store-Bound — _CHALLENGES war unbounded
# (jede unique device_id füllte den Store bis zum TTL-Ablauf); FIFO: der
# ZEITLICH älteste Eintrag verfällt zuerst.
_CHALLENGE_CAP = 10_000
# Security-Fix-Welle 17.11.: device_id-Gate (Parität android_relay.py
# _DEVICE_ID_RE, den enroll bisher NICHT nutzte) — der Wert fließt in
# cert_path (Path-Traversal) und openssl -subj (CN-Injection).
_DEVICE_ID_RE = re.compile(r"[A-Za-z0-9._-]{1,64}")

_INIT_LOCK = threading.Lock()
_INIT_STATE: dict = {"ready": False, "error": ""}
_CHALLENGES: dict[str, Tuple[float, str]] = {}  # device_id → (expires, nonce)
_STATE_LOCK = threading.Lock()
# Security-Fix-Welle 17.11. (TOCTOU): Registry-load→code-used-check→save atomar
# im Enroll-Pfad. P43 (17.11.): reset_epoch (Code-Wechsel-Pfad) teilt denselben
# Lock — der Epoch-Bump ist damit atomar ggü. einem LAUFENDEN Enrollment
# (sonst überwarfen sich Enroll- und Rotations-Save gegenseitig: Enroll-Eintrag
# verloren bzw. Epoch auf den Stand vor dem Bump zurückgesetzt).
_ENROLL_LOCK = threading.Lock()


class RegistryUnavailable(RuntimeError):
    """P43 (17.11.): Registry-Datei VORHANDEN, aber unlesbar/schema-beschädigt.

    Strikte Leser (enroll, reset_epoch) verweigern dann, statt still mit einem
    LEEREN enrolled-View weiterzuarbeiten — ein Save aus so einem View würde
    alle Geräte-Einträge STILL löschen (exakt der P43-Schaden). Eine FEHLENDE
    Datei ist keine Beschädigung: frische Registry, kein Fehler."""


# ── OpenSSL-Helfer (subprocess, timeout, fail-soft) ──────────────────────────

def _openssl(args: list, data: bytes = b"", timeout: int = 20) -> Tuple[bool, bytes, str]:
    try:
        proc = subprocess.run(
            ["openssl", *args],
            input=data, capture_output=True, timeout=timeout,
        )
        return proc.returncode == 0, proc.stdout, proc.stderr.decode("utf-8", "replace")
    except Exception as exc:  # pragma: no cover — fehlt openssl = off-Fehler
        return False, b"", str(exc)


def _ca_ready() -> bool:
    return _INIT_STATE["ready"]


# ── Init: CA anlegen/laden ───────────────────────────────────────────────────

def init() -> bool:
    """Device-CA initialisieren (one-time, thread-safe). Fail-soft: False =
    Enrollment aus, Log-Zeile, Relay läuft ungehindert weiter."""
    with _INIT_LOCK:
        if _INIT_STATE["ready"]:
            return True
        try:
            tls_dir, ca_key, ca_crt, _reg, devices_sub = _tls_paths()
            tls_dir.mkdir(parents=True, exist_ok=True)
            devices_sub.mkdir(parents=True, exist_ok=True)
            if not (ca_key.exists() and ca_crt.exists()):
                ok, _, err = _openssl(
                    ["req", "-x509", "-newkey", "rsa:3072", "-nodes",
                     "-keyout", str(ca_key), "-out", str(ca_crt),
                     "-days", str(_CA_DAYS), "-subj",
                     "/CN=FlipsiBridge Device CA/O=FlipsiBridge/C=AT"],
                    timeout=60,
                )
                if not ok:
                    raise RuntimeError(f"CA-Erzeugung fehlgeschlagen: {err.strip()[:200]}")
                try:
                    os.chmod(ca_key, 0o640)
                except OSError:
                    pass
            ok, _, err = _openssl(["x509", "-in", str(ca_crt), "-noout"], timeout=10)
            if not ok:
                raise RuntimeError(f"CA-Cert unlesbar: {err.strip()[:200]}")
            # FIX-6 (W16.3, fail-early): Schreibprobe ins devices-Verzeichnis — ein
            # read-only TLS-Dir (systemd ProtectSystem=full) macht Enrollment sonst
            # erst IM Handshake kaputt (500). Routen bleiben dann OFF mit klarer Meldung.
            probe = devices_sub / ".write-probe"
            probe.write_bytes(b"ok")
            probe.unlink(missing_ok=True)
            _registry_load()  # defekte Registry reparieren (leer + Backup)
            _INIT_STATE["ready"] = True
            logger.info("mtls_enroll: Device-CA aktiv (%s)", ca_crt)
            return True
        except Exception as exc:
            _INIT_STATE["error"] = str(exc)
            logger.warning("mtls_enroll: init fehlgeschlagen (Enrollment OFF): %s", exc)
            return False


# ── Registry (Epoch + Devices) ───────────────────────────────────────────────

def _registry_default() -> dict:
    epoch = int(os.getenv("ANDROID_MTLS_EPOCH", "1"))
    return {"epoch": epoch, "enrolled": {}}


def _registry_load(strict: bool = False) -> dict:
    """Registry laden. FEHLENDE Datei = frische Registry (kein Fehler, legacy-
    konform). P43: strict=True (Enroll-Pfad, reset_epoch) behandelt eine
    VORHANDENE, aber unlesbare/schema-beschädigte Datei als
    RegistryUnavailable statt stille mit einem leeren View weiterzuschreiben
    — nur der Nicht-strict-Weg (init-Reparatur, Gate) bleibt bei Default."""
    tls_dir, _ck, _cc, registry, _ds = _tls_paths()
    try:
        data = json.loads(registry.read_text(encoding="utf-8"))
        if not isinstance(data, dict) or "epoch" not in data:
            raise ValueError("schema")
        return data
    except Exception:
        if strict and registry.exists():
            raise RegistryUnavailable(
                f"mtls_devices.json vorhanden, aber unlesbar/schema-beschädigt: {registry}")
        return _registry_default()


def _registry_save(data: dict) -> None:
    tls_dir, _ck, _cc, registry, _ds = _tls_paths()
    tmp = registry.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, indent=1, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, registry)


def reset_epoch(new_epoch: Optional[int] = None) -> Optional[dict]:
    """Epoch umstellen (set_pairing_code ruft das nach einem Code-Wechsel —
    der Code öffnet das Einmal-Fenster wieder). Erhöhung um 1 = Default.

    P43 (17.11.): Die Rotation fasst NUR die Epoch an — 'enrolled' bleibt
    byte-stabil wie geladen: Alt-Code-Geräte behalten Eintrag + Zertifikat
    (die Blockierung läuft allein über den Epoch-Check am Gate, siehe
    _mtls_gate/android_relay.py). Zwei Schutzebenen, damit der Code-Wechsel
    die Registry NICHT leeräumen kann:
      * atomar ggü. Enrollment (_ENROLL_LOCK) — der Bump des Rotation-Threads
        kann nicht mehr mit dem Enroll-Save um einen veralteten View konkurrieren;
      * striktes Lesen: ist die Datei beschädigt, wird ABBRUCH geschrieben
        (kein Save aus einem leeren View → kein stiller Gesamtwipe); der
        Epoch-Bump holt nach, sobald die Registry wieder lesbar ist.
    Rückgabe: Registry bei Erfolg, None bei unlesbarer Registry (fail-soft)."""
    with _ENROLL_LOCK:
        with _STATE_LOCK:
            try:
                data = _registry_load(strict=True)
            except RegistryUnavailable as exc:
                logger.warning(
                    "mtls_enroll: Registry unlesbar — Epoch-Rotation abgebrochen "
                    "(KEIN Save aus leerem View, enrolled bleibt unberührt): %s", exc)
                return None
            before = int(data.get("epoch", 1))
            data["epoch"] = int(new_epoch) if new_epoch else before + 1
            # P43-SCHUTZ: bewusst KEINE Berührung von data["enrolled"].
            try:
                _registry_save(data)
            except Exception as exc:  # pragma: no cover
                logger.warning("mtls_enroll: epoch-Persist fehlgeschlagen: %s", exc)
            logger.info(
                "mtls_enroll: Epoch %s → %s (Code-Fenster neu; enrolled unverändert: %d Gerät(e))",
                before, data["epoch"], len(data.get("enrolled") or {}))
            return data


# ── Proof-of-Possession (Nonce) ─────────────────────────────────────────────

def _open_challenge(device_id: str) -> Optional[str]:
    # Security-Fix-Welle 17.11.: Format-Gate VOR Nonce-Ausgabe — nur ein
    # ASCII-Wort [A-Za-z0-9._-]{1,64} (Parität _DEVICE_ID_RE/android_relay)
    # bekommt eine Challenge; Traversal-/Injektion-IDs fallen am Handler auf 400.
    if not isinstance(device_id, str) or not _DEVICE_ID_RE.fullmatch(device_id):
        return None
    nonce = secrets.token_hex(16)
    with _STATE_LOCK:
        now = time.time()
        for did in [k for k, (exp, _n) in _CHALLENGES.items() if exp < now]:
            _CHALLENGES.pop(did, None)
        _CHALLENGES[device_id] = (now + _CHALLENGE_TTL_S, nonce)
        # Cap (FIFO): der zeitlich älteste Eintrag verfällt zuerst — ein
        # RE-Open zählt mit seiner NEUEN Ablaufzeit, nicht der Einfügeposition.
        while len(_CHALLENGES) > _CHALLENGE_CAP:
            oldest = min(_CHALLENGES, key=lambda k: _CHALLENGES[k][0])
            _CHALLENGES.pop(oldest, None)
    return nonce


def _consume_challenge(device_id: str, nonce: str) -> bool:
    with _STATE_LOCK:
        entry = _CHALLENGES.pop(device_id, None)
    if entry is None:
        return False
    expires, stored = entry
    return expires >= time.time() and secrets.compare_digest(stored, nonce or "")


# ── CSR-Verarbeitung (PubKey + POP + Signierung) ────────────────────────────

def _expected_sig_bytes(pub_pem: bytes) -> Tuple[Optional[int], bool]:
    """Signaturlänge aus dem Public Key (RSA exakt N/8, EC = Bandbasis).
    Rückgabe (len, is_rsa); (None, False) = nicht lesbar (Band-Fallback)."""
    ok, out, _ = _openssl(["pkey", "-pubin", "-noout", "-text"], data=pub_pem)
    if not ok:
        return None, False
    import re as _re
    text = out.decode("utf-8", "replace")
    m = _re.search(r"Public-Key:\s*\((\d+)\s*bit\)", text)
    if not m:
        return None, False
    bits = int(m.group(1))
    is_rsa = "RSA" in text or "Exponent" in text
    return bits // 8, is_rsa


def _csr_pubkey_verify_pop(csr_pem: bytes, nonce: str, sig_b64: str) -> Tuple[bool, str]:
    """PoPs verifizieren: Signatur der Nonce gegen den CSR-PubKey.

    Härte (E2E-Diagnose-Befund, Findevollzug): openssl toleriert überlange
    Sig-Dateien STILL (führende N Bytes gelten, Rest wird ignoriert) —
    deshalb Signaturlänge aus dem Public Key ableiten und für RSA EXAKT
    erzwingen; b64-Reste oder verkürzte Werte = Ablehnung."""
    try:
        sig = base64.b64decode(sig_b64, validate=True)
    except Exception:
        return False, "sig-nicht-base64"
    if not (48 <= len(sig) <= 512):
        return False, f"sig-laenge/{len(sig)}"
    nonce_bytes = nonce.encode("utf-8")
    is_pem = b"-----BEGIN" in csr_pem
    req_args = ["req", "-pubkey", "-noout"] + ([] if is_pem else ["-inform", "DER"])
    ok, pub, err = _openssl(req_args, data=csr_pem)
    if not ok:
        return False, f"pubkey-extract: {err.strip()[:120]}"
    pub_pem = pub
    expected, is_rsa = _expected_sig_bytes(pub_pem)
    if expected is not None and is_rsa:
        if len(sig) != expected:
            return False, f"sig-exakt/{len(sig)}!={expected}"
    elif expected is not None:
        # EC: DER (r|s) → je ≤ N/8+4 B, Mindestband 16 B
        hi = (expected + 4) * 2
        if not (16 <= len(sig) <= hi):
            return False, f"sig-band/{len(sig)}"
    ok, err = _verify_dgst_tmp(pub_pem, sig, nonce_bytes)
    if not ok:
        er = err if isinstance(err, str) else str(err)
        return False, f"pop-verify: {er.strip()[:160]}"
    return True, "PoPs ok"


def _verify_dgst_tmp(pub_pem: bytes, sig: bytes, nonce: bytes) -> Tuple[bool, str]:
    import tempfile
    with tempfile.TemporaryDirectory(dir=os.getenv("TMPDIR", "/tmp")) as d:
        pubp = os.path.join(d, "pub.pem")
        sigp = os.path.join(d, "sig.bin")
        np = os.path.join(d, "nonce.txt")
        Path(pubp).write_bytes(pub_pem)
        Path(sigp).write_bytes(sig)
        Path(np).write_bytes(nonce)
        ok, _out, err = _openssl(
            ["dgst", "-sha256", "-verify", pubp, "-signature", sigp, np])
        return ok, err


def _sign_csr(csr_pem: bytes, device_id: str) -> Optional[bytes]:
    import tempfile
    with tempfile.TemporaryDirectory(dir=os.getenv("TMPDIR", "/tmp")) as d:
        _td, ca_key, ca_crt, _rg, _ds = _tls_paths()
        csrp = os.path.join(d, "device.csr")
        outp = os.path.join(d, "device.crt")
        inform = []
        if b"-----BEGIN" not in csr_pem:
            inform = ["-inform", "DER"]
        Path(csrp).write_bytes(csr_pem)
        serial = secrets.token_hex(8)
        ok, _, err = _openssl(
            ["x509", "-req", *inform, "-in", csrp, "-CA", str(ca_crt),
             "-CAkey", str(ca_key), "-sha256", "-days", str(_CERT_DAYS),
             "-out", outp,
             "-set_serial", f"0x{serial}",
             "-subj", f"/CN=flipsibridge-{device_id}/O=FlipsiBridge Devices/C=AT"],
            timeout=30,
        )
        if not ok:
            logger.warning("mtls_enroll: CSR-Signierung fehlgeschlagen: %s", err.strip()[:200])
            return None
        return Path(outp).read_bytes()


# ── HTTP-Handler (aiohttp-Funktionen, von _serve via add_routes gekoppelt) ───

def JSON_RESPONSE(payload, status: int = 200):
    from aiohttp import web
    return web.json_response(payload, status=status)


def _relay_state():
    try:
        import android_relay as _ar  # Flat-Lauf (direkter Modul-Import)
    except ImportError:
        from tools import android_relay as _ar  # Daemon: Package-Weg
    return getattr(_ar, "_relay_instance", None)


def _ar_module():
    try:
        import android_relay as _ar
        return _ar
    except ImportError:
        from tools import android_relay as _ar
        return _ar


async def challenge_handler(request):
    state = _relay_state()
    if state is None:
        return JSON_RESPONSE({"error": "relay-not-ready"}, status=503)
    remote_ip = request.remote or "?"
    auth_header = request.headers.get("Authorization", "")
    token = auth_header.removeprefix("Bearer ").strip() if auth_header.startswith("Bearer ") else ""
    _ar = _ar_module()
    if not _ar._constant_time_eq(token, state.pairing_code):
        _ar._auth_record_failure(remote_ip)
        return JSON_RESPONSE({"error": "auth"}, status=401)
    try:
        body = await request.json()
        device_id = str(body.get("device_id", ""))
    except Exception:
        device_id = ""
    if not _ca_ready():
        return JSON_RESPONSE({"error": "mtls-off"}, status=503)
    nonce = _open_challenge(device_id)
    if nonce is None:
        return JSON_RESPONSE({"error": "bad device_id"}, status=400)
    return JSON_RESPONSE({"nonce": nonce, "ttl": _CHALLENGE_TTL_S})


async def enroll_handler(request):
    state = _relay_state()
    if state is None:
        return JSON_RESPONSE({"error": "relay-not-ready"}, status=503)
    remote_ip = request.remote or "?"
    auth_header = request.headers.get("Authorization", "")
    token = auth_header.removeprefix("Bearer ").strip() if auth_header.startswith("Bearer ") else ""
    _ar = _ar_module()
    if not _ar._constant_time_eq(token, state.pairing_code):
        _ar._auth_record_failure(remote_ip)
        return JSON_RESPONSE({"error": "auth"}, status=401)
    if not _ca_ready():
        return JSON_RESPONSE({"error": "mtls-off"}, status=503)
    try:
        body = await request.json()
        device_id = str(body.get("device_id", "")).strip()
        csr_pem = base64.b64decode(str(body.get("csr", "")), validate=True)
        sig_b64 = str(body.get("sig", ""))
        nonce = str(body.get("nonce", ""))
    except Exception:
        return JSON_RESPONSE({"error": "bad body"}, status=400)
    # Security-Fix-Welle 17.11.: device_id-Gate VOR Registry/Zertifikat —
    # der Wert fließt in cert_path (Path-Traversal) und openssl -subj
    # (CN-Injection); erlaubt ist nur ein ASCII-Wort [A-Za-z0-9._-]{1,64}
    # (Parität _open_challenge und android_relay._DEVICE_ID_RE).
    if not device_id or not _DEVICE_ID_RE.fullmatch(device_id):
        return JSON_RESPONSE({"error": "bad device_id"}, status=400)
    if len(csr_pem) > 8192:
        return JSON_RESPONSE({"error": "bad fields"}, status=400)

    # Security-Fix-Welle 17.11. (TOCTOU): Registry-load→code-used-check→save
    # atomar unter _ENROLL_LOCK — zwei parallele Enrolls sahen sonst BEIDE
    # eine leere Registry und ließen zwei Neugeräte mit EINEM Code durch.
    # Langläufer (POP-Verify, Signierung) bleiben bewusst IN der Sektion:
    # Enroll ist ein Einmal-pro-Epoch-Ereignis, die Serialisierung IST der
    # Schutz.
    with _ENROLL_LOCK:
        # P43: striktes Lesen — beschädigte Registry → 503 statt stiller
        # Wipe (der Enroll-Save aus einem Leer-View würde ALTE Einträge
        # löschen); der Admin repariert (registry_reset.py), kein Datenverlust.
        try:
            reg = _registry_load(strict=True)
        except RegistryUnavailable as exc:
            logger.error(
                "mtls_enroll: Registry unlesbar — Enrollment verweigert "
                "(kein stiller Wipe durch Leer-View-Save): %s", exc)
            return JSON_RESPONSE({"error": "registry-unavailable"}, status=503)
        enrolled = reg.setdefault("enrolled", {})
        is_new_device = device_id not in enrolled
        if is_new_device and enrolled:
            # CODE-EINMALIGKEIT: in dieser Epoch hat bereits ein Gerät enrollt
            # und das Gerät ist NEU → abgelehnt (reset_epoch via Code-Neuerzeugung).
            return JSON_RESPONSE(
                {"error": "code-used",
                 "hint": "Neues Gerät: Kopplungscode neu erzeugen (Code ist einmalig je Epoch)."},
                status=403)

        if not _consume_challenge(device_id, nonce):
            return JSON_RESPONSE({"error": "nonce-invalid"}, status=400)

        ok, reason = _csr_pubkey_verify_pop(csr_pem, nonce, sig_b64)
        if not ok:
            return JSON_RESPONSE({"error": "pop-fail", "detail": reason}, status=400)

        cert_pem = _sign_csr(csr_pem, device_id)
        if not cert_pem:
            return JSON_RESPONSE({"error": "sign-fail"}, status=500)

        tls_dir, _ck, ca_crt, _rg, devices_sub = _tls_paths()
        cert_path = devices_sub / (device_id + ".pem")
        if len(cert_pem) < 400 or b"BEGIN CERTIFICATE" not in cert_pem:
            return JSON_RESPONSE({"error": "cert-shape"}, status=500)
        # FIX-6 (W16.3): Read-only-Filesystem (z. B. systemd ProtectSystem=full) →
        # klare 500 statt rohem Traceback. Abhilfe am Host: ReadWritePaths=/etc/flipsibridge/tls
        try:
            devices_sub.mkdir(parents=True, exist_ok=True)
            cert_path.write_bytes(cert_pem)
            cert_path.chmod(0o644)
        except OSError as exc:
            logger.error(
                "mtls_enroll: TLS-Verzeichnis nicht schreibbar (%s). systemd-Hardening? "
                "ReadWritePaths auf das CA-Verzeichnis setzen (%s).", exc, tls_dir)
            return JSON_RESPONSE(
                {"error": "tls-dir-readonly",
                 "hint": "Server: TLS-Verzeichnis nicht schreibbar (systemd ProtectSystem) "
                         "— ReadWritePaths setzen."}, status=500)

        reg.setdefault("epoch", 1)
        enrolled[device_id] = {
            "cn": f"flipsibridge-{device_id}",
            "cert_path": str(cert_path.relative_to(tls_dir)),
            "ts": int(time.time()),
            "epoch_of_enroll": reg["epoch"],
            "is_new_device_epoch": bool(is_new_device),
        }
        try:
            _registry_save(reg)
        except Exception as exc:
            logger.warning("mtls_enroll: Registry-Persist fehlgeschlagen: %s", exc)
        logger.info(
            "mtls_enroll: Gerät %s enrollt (new_device=%s, epoch=%s)",
            device_id, is_new_device, reg.get("epoch"))

        ca_pem = ca_crt.read_bytes()
        return JSON_RESPONSE({
            "status": "enrolled",
            "device_id": device_id,
            "cert_pem": cert_pem.decode("ascii"),
            "ca_pem": ca_pem.decode("ascii"),
            "epoch_of_enroll": reg.get("epoch"),
        })


def add_routes(app: Any) -> bool:
    """Enrollment-Routen einhängen — nur wenn init() grün ist. Fail-soft."""
    if not init():
        return False
    from aiohttp import web
    app.router.add_post("/mtls/enroll/challenge", challenge_handler)
    app.router.add_post("/mtls/enroll", enroll_handler)
    logger.info("mtls_enroll: Routen /mtls/enroll[/challenge] aktiv")
    return True
