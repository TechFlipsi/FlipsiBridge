"""FlipsiBridge-Chat-Adapter (Hermes-Platform-Plugin, Skeleton).

Muster: BasePlatformAdapter-Schnittstelle aus gateway/platforms/base.py
(connect/disconnect/send/send_typing/send_image/get_chat_info) — Referenz:
ADDING_A_PLATFORM.md, Plugin-Pfad (0 Hermes-Core-Änderungen, ctx.register_platform).

Architektur (ROADMAP PH-2 Feature 2, P3):
- connect()    : verbindet sich OUTBOUND zum FlipsiBridge-Relay auf der Agent-WS-Route
                 (Default /agent_ws, via FLIPSI_WS_ROUTE anpassbar) mit Agent-Bearer-Token
                 + agent_id — wie das Handy NAT-frei, damit Agenten aus Containern/Fremd-
                 Homelabs denselben Relay nutzen.
- send()       : JSON-Envelope (type=chat.reply) an die Relay-/chat-Route über die Agent-WS.
- Empfang      : der Agent-WS-Frame-Loop pusht chat.deliver-Texte als NACHRICHT in die
                 Gateway-Messages-Pipeline (self.handle_message) — NIEMALS als Tool-Call.

Agentenschutz (ROADMAP I3, bindend):
- URL-Whitelist-Pinning: der Adapter verweigert JEDE Relay-URL außer der konfigurierten
  Whitelist (Default: genau die 1 konfigurierte URL). Kein „beliebiges Backend"-Fallback.
- Namespace-Prüfung je Inbound-Frame: chat.deliver mit fremder agent_id wird abgelehnt.
- Nur wss/https als Schema (ws/http nur mit explizitem FLIPSI_ALLOW_INSECURE=1 für
  lokale QA-Drills). Kein userinfo in der URL.
- Chat-Text ist Daten: Frames mit Kommando-/Tool-Charakter (type NICHT in der Envelope-
  Allowlist) werden ignoriert + geloggt, niemals ausgeführt.
- Der Agent-Bearer-Token wird NIE geloggt und NIE an eine Nicht-Whitelist-URL gesendet.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import math
import os
import re
import time
import uuid
from collections import deque
from typing import Any, Callable, Optional
from urllib.parse import urlsplit

try:  # Echter Hermes-Gateway-Kontext (Plugin liegt in einer Hermes-Installation)
    from gateway.platforms.base import BasePlatformAdapter, SendResult
    _UNDER_HERMES = True
except ImportError:  # Repo-Skeleton-Kontext (contrib/, Selbst-Test ohne Installation)
    from _base_fallback import BasePlatformAdapter, SendResult
    _UNDER_HERMES = False

logger = logging.getLogger("flipsibridge.chat")

# Envelope-Typen, die der Frame-Loop akzeptiert. ALLES ANDERE wird abgelehnt —
# insbesondere nie etwas mit Tool-/Command-Semantik (I3: Chat-Text ≠ Tool-Call).
# chat.send = echter Broker-Frame des Relays (PH2-T10: mit msg_id für
# Lesebestätigungen); chat.deliver = Skeleton-Namespace (kompatibel gehalten).
_INBOUND_ENVELOPE_ALLOWLIST = frozenset(
    {"chat.send", "chat.deliver", "chat.ack", "roster", "presence", "pong",
     "crypto.hello", "chat.send.enc", "chat.event.enc",
     # MEDIA-T1: chunked Media-Versand (E2E-only) — Daten NUR im Ratchet-ct.
     "chat.media.enc"}
)
_PROTOCOL_VERSION = 1

# PH-3 (Welle 1): E2E-Crypto-Kern — `cryptography` ist die EINE neue Python-
# Dependency (PH3-CRYPTO-SPEC §0/§1 (i)). Fehlt sie, läuft der Adapter im
# Legacy-Klartext-Modus (v0.14-Migration §3.2) und lehnt crypto.hello ab.
try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric.x25519 import (
        X25519PrivateKey, X25519PublicKey)
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF, HKDFExpand
    from cryptography.hazmat.primitives.serialization import (
        Encoding, PrivateFormat, PublicFormat)
    _HAS_CRYPTO = True
except ImportError:  # pragma: no cover — nur ohne cryptography-Install
    _HAS_CRYPTO = False

# ── PH-3: fb-v1-Protokoll-Parameter (Byte-exakter Vertrag: siehe
# secrets-docs/PH3-KDF-FIXTURES-v1.md — Kotlin Welle 2 implementiert DAEGEN) ──
_REPLAY_WINDOW = 100              # Counter-Fenster je Richtung
_FRAME_MAX_AGE_MS = 10 * 60 * 1000  # Frame-Altersgrenze (Replay-Fenster 100 + 10 min)
_SKIPPED_KEY_CACHE_MAX = 100      # OOO-Nachrichten-Cache (Cap = Replay-Bound)
_DECRYPT_FAIL_DELAY = 0.25        # konstante Fehlverzögerung (kein Orakel)
_SESSION_MAX_AGE_SECONDS = 24 * 3600  # 24-h-Session (danach neue crypto.hello)
_RATCHET_ROTATE_EVERY = 100       # DH-Rotation je N Nachrichten (konfigurierbar)
_HEX32_RE = re.compile(r"^[0-9a-fA-F]{64}$")
_FP_PREFIX = "FP-ABGLEICH:"
_FP_OK_TEXT = "Fingerprint-Abgleich: OK — Code ist gleich"
_FP_FAIL_TEXT = ("Fingerprint-Abgleich: FEHLGESCHLAGEN — Abweichung! "
                 "Mögliches Man-in-the-Middle. Session sofort ungültig!")
_ENC_CT_MAX_BYTES = 512 * 1024    # Relay-seitig identisches Limit (Fail-closed)
_SEEN_PEER_PUBS_MAX = 4           # Replay-Schutz: gesehene Peer-Ratchet-Pubs

# ── MEDIA-T1: chunked Medien-/Datei-Versand im Chat (E2E-only) ────────────────
# Protokoll: chat.media.enc je Chunk — klare Metadaten (media_id/seq/total,
# seq=0 zusätzlich kind/name/mime/size), Daten NUR im AES-GCM-ct (Payload:
# {media_id, seq, total, data(b64), sha256(Chunk 0)}). Der Agent bestätigt
# JE Chunk mit chat.media.chunk_ack (Progress für die Phone-UI). Parität
# zu tools/android_relay.py (MEDIA-T1) und PhoneCryptoPeer (Kotlin Welle).
_MEDIA_CHUNK_BYTES = 192 * 1024   # raw je Chunk → Frame ~350 KB (J4: < 2 MB)
_MEDIA_MAX_CHUNKS = 4096
_MEDIA_MAX_BYTES = 50 * 1024 * 1024      # Deckel je Datei (Relay prüft size)
_MEDIA_DIR_MAX_BYTES = 200 * 1024 * 1024  # Retention: Media-Dir-Gesamtbudget
_MEDIA_ASSEMBLERS_MAX = 8                 # gleichzeitige halbfertige Uploads
_MEDIA_ASSEMBLER_TTL_S = 30 * 60          # halbfertige Assemblies entsorgen
_MEDIA_KINDS = frozenset({"image", "video", "file", "voice"})

# ── W4-B2: Adapter-gesteuerte Slash-Befehle (Chat-Präfix-Parser) ──────────────
# SCOPE-GUARD (W4-B2, verbindlich): Die Befehlsautorität lebt NUR hier, im
# FlipsiBridge-Chat-Adapter — KEIN Gateway-Hook, KEIN globaler Command-Shim,
# KEIN anderer Plattform-Adapter (Konvention 0932dba). Die App behandelt
# Slash-Text als gewöhnliche Nachricht (kein UI-Spezialfall); Erkennung,
# Antwort und Scope laufen im Nach-Dekrypt-Verband des Adapters. Der Phone-
# Payload kann die Liste NICHT erweitern (_SLASH_COMMANDS = frozenset-
# Modulkonstante), und der Parser liegt bewusst nur auf dem Inbound-Pfad —
# Agent-Antworten laufen nie durch _dispatch_frame (kein Loop-Risiko).
_SLASH_COMMANDS = frozenset({"new", "status", "help"})
_HELP_TEXT = (
    "Befehle:\n"
    "/new — Agenten-Unterhaltung zurücksetzen (mit Bestätigung)\n"
    "/status — Status: E2E-Sitzung, Verbindung, Verlauf-Kurzbasis\n"
    "/help — diese Befehlsliste\n"
    "Hinweis: Befehle steuert der Adapter, nicht die App."
)


class MediaError(RuntimeError):
    """Media-Übertragung fehlgeschlagen (Struktur/Größe/Integrität)."""


class MediaAssembler:
    """Reassembly verschlüsselbarer chat.media.enc-Chunks je media_id.

    Thread-sicher per Aufrufer-Lock; add() liefert die VOLLSTÄNDIGEN Bytes
    zurück, sobald der letzte erwartete Chunk angekommen ist. Integrität:
    sha256 aus dem Chunk-0-Payload wird gegen die fertigen Bytes geprüft
    (fail-closed: Mismatch = MediaError, Assembly wird verworfen).
    """

    def __init__(self, total: int):
        if not isinstance(total, int) or total < 1 or total > _MEDIA_MAX_CHUNKS:
            raise MediaError("total ungültig")
        self.total = total
        self.chunks: list[Optional[bytes]] = [None] * total
        self.received = 0
        self.sha256: Optional[str] = None

    def add(self, seq: int, payload: dict) -> Optional[bytes]:
        """Chunk einsortieren; Rückgabe = komplette Datei beim LETZTEN Chunk."""
        if not isinstance(seq, int) or isinstance(seq, bool) \
                or seq < 0 or seq >= self.total:
            raise MediaError("seq außerhalb [0, total)")
        raw_b64 = payload.get("data")
        if not isinstance(raw_b64, str) or not raw_b64:
            raise MediaError("Chunk ohne Daten")
        try:
            data = base64.b64decode(raw_b64, validate=True)
        except Exception as exc:
            raise MediaError("Chunk nicht base64") from exc
        if len(data) > _MEDIA_CHUNK_BYTES:
            raise MediaError("Chunk über Größen-Deckel")
        if seq == 0:
            claimed = payload.get("sha256")
            if isinstance(claimed, str) and len(claimed) == 64:
                self.sha256 = claimed.lower()
        if self.chunks[seq] is None:
            self.chunks[seq] = data
            self.received += 1
        if self.received < self.total:
            return None
        blob = b"".join(c for c in self.chunks if c is not None)
        if self.sha256 is not None:
            import hashlib as _hashlib
            if _hashlib.sha256(blob).hexdigest() != self.sha256:
                raise MediaError("sha256-Integrität verletzt")
        return blob


def _media_kind_label(kind: str) -> str:
    """Anzeige-Label je Media-Art (deutsch, kein Emoji-only — A11y)."""
    return {
        "image": "Foto",
        "video": "Video",
        "file": "Datei",
        "voice": "Sprachnachricht",
    }.get(kind, "Datei")


def _format_media_bytes(size: int) -> str:
    """Byte-Größe als deutsche Anzeige (KB/MB, eine Nachkommastelle)."""
    if size >= 1024 * 1024:
        return f"{size / (1024 * 1024):.1f} MB"
    return f"{max(1, size // 1024)} KB"


class CryptoFrameError(RuntimeError):
    """Entschlüsselung/Validierung fehlgeschlagen — NIE mit Klartext-Inhalt."""


class RatchetError(CryptoFrameError):
    """Ratchet-Zustand unbenutzbar (z. B. keine Sende-Kette)."""


# ── PH-3: Primitive (RFC-exakt, gegen PH3-KDF-FIXTURES-v1 getestet) ──────────

def _b64e(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


def _b64d(text: str) -> bytes:
    try:
        return base64.b64decode(text, validate=True)
    except Exception as exc:
        raise CryptoFrameError("base64 ungültig") from exc


def hkdf_extract(salt: bytes, ikm: bytes) -> bytes:
    """RFC 5869 Extract: HMAC-SHA256(key=salt, msg=ikm)."""
    return hmac.new(salt, ikm, hashlib.sha256).digest()


def hkdf_expand(prk: bytes, info: bytes, length: int) -> bytes:
    """RFC 5869 Expand (cryptography-HKDFExpand = RFC-konform)."""
    if not _HAS_CRYPTO:
        raise CryptoFrameError("cryptography fehlt")
    return HKDFExpand(algorithm=hashes.SHA256(), length=length, info=info).derive(prk)


def hkdf_sha256(salt: bytes, ikm: bytes, info: bytes, length: int = 32) -> bytes:
    """HKDF-SHA256 = Expand(Extract(salt, ikm), info, length)."""
    return hkdf_expand(hkdf_extract(salt, ikm), info, length)


def _x25519_gen() -> tuple[bytes, bytes]:
    priv = X25519PrivateKey.generate()
    return (priv.private_bytes(Encoding.Raw, PrivateFormat.Raw,
                                serialization.NoEncryption()),
            priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw))


def _x25519_dh(priv_bytes: bytes, peer_pub_bytes: bytes) -> bytes:
    return X25519PrivateKey.from_private_bytes(priv_bytes).exchange(
        X25519PublicKey.from_public_bytes(peer_pub_bytes))


def _kdf_rk(rk: bytes, dh_out: bytes) -> tuple[bytes, bytes]:
    """KDF_RK: okm = HKDF(salt=rk, ikm=dh_out, info='fb-ratchet', 64) → (rk', ck)."""
    okm = hkdf_sha256(salt=rk, ikm=dh_out, info=b"fb-ratchet", length=64)
    return okm[:32], okm[32:64]


def _kdf_ck(ck: bytes) -> tuple[bytes, bytes]:
    """KDF_CK (Signal-exakt): → (ck', mk); mk = HMAC(ck, 0x01), ck' = HMAC(ck, 0x02)."""
    return hmac.new(ck, b"\x02", hashlib.sha256).digest(), \
        hmac.new(ck, b"\x01", hashlib.sha256).digest()


def _msg_aes_key(mk: bytes) -> bytes:
    return hkdf_sha256(salt=b"fb-msgkey", ikm=mk, info=b"fb-aes256", length=32)


def fingerprint_of_pub(pub_bytes: bytes) -> str:
    """agent_fp = HKDF-Hash über einen Identity-PubKey (Registry/Config-Wert)."""
    salt = hashlib.sha256(b"flipsi-fp").digest()
    return hkdf_sha256(salt=salt, ikm=pub_bytes, info=b"fb-agent-fp").hex()


def fingerprint_display(fp_hex: str) -> str:
    """Anzeige-Gruppierung: 8 Gruppen à 8 Hex-Zeichen (Abgleich bleibt Hex-Gleichheit)."""
    return " ".join(fp_hex[i:i + 8].upper() for i in range(0, len(fp_hex), 8))


def _fb_v1_kdf(dh1: bytes, dh2: bytes, dh3: bytes, psk: bytes, session_id: str,
               agent_id: str, ik_p: bytes, ek_p: bytes, spk_p: bytes,
               ik_a: bytes, spk_a: bytes) -> dict:
    """fb-v1-Ableitung (PH3-KDF-FIXTURES §5.2 — byte-exakt, beidseitig identisch)."""
    # SEC-W1 B F-02: Lone-Surrogates in session_id/agent_id sind UTF-8-NICHT-
    # codierbar — der UnicodeEncodeError entkam bisher JEDEM CryptoFrameError-
    # Fang (Frame-Loop-Tod). Jetzt: deterministischer CryptoFrameError.
    try:
        session_bytes = session_id.encode("utf-8")
        agent_bytes = agent_id.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise CryptoFrameError(
            "session_id/agent_id nicht UTF-8-codierbar") from exc
    transcript = hashlib.sha256(
        b"flipsi-v1" + session_bytes
        + ik_p + ek_p + spk_p + ik_a + spk_a + agent_bytes
    ).digest()
    psk_comp = hmac.new(transcript, psk, hashlib.sha256).digest()
    prk = hkdf_extract(transcript, dh1 + dh2 + dh3 + psk_comp)
    context = (ik_p + ek_p + spk_p + ik_a + spk_a
               + agent_bytes + session_bytes)
    return {
        "transcript": transcript,
        "prk": prk,
        "root0": hkdf_expand(prk, b"fb-root" + context, 32),
        "k_init": hkdf_expand(prk, b"fb-init" + context, 32),
        "session_fp": hkdf_expand(prk, b"fb-fp" + context, 32).hex(),
    }


def _enc_aad(session_id: str, chat_id: str, agent_id: str) -> bytes:
    """AAD = 'fb-v1|session_id|chat_id|agent_id' (Typ bewusst NICHT drin:
    der Relay benennt chat.reply.enc → chat.event.enc um)."""
    # SEC-W1 B F-02: Surrogate-haltige IDs warfen UnicodeEncodeError, der
    # den Frame-Loop töten konnte (kein CryptoFrameError-Fang) — jetzt
    # deterministisch als CryptoFrameError (Frame wird verworfen, Loop lebt).
    try:
        return f"fb-v1|{session_id}|{chat_id}|{agent_id}".encode("utf-8")
    except UnicodeEncodeError as exc:
        raise CryptoFrameError("session_id/chat_id nicht UTF-8-codierbar") from exc


def _log_id(value, limit: int = 64) -> str:
    """SEC-W2 W2-3: IDs/Typen für LOG-Ausgaben kürzen + \n/\r escapen
    (Parität zum Relay-Helfer _log_id, W1 B F-07)."""
    text = str(value)[:limit]
    if "\n" in text or "\r" in text:
        text = text.replace("\r", "\\r").replace("\n", "\\n")
    return text


def _json_payload(payload: dict) -> bytes:
    return json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _hex32(value) -> bytes:
    if not isinstance(value, str) or not _HEX32_RE.fullmatch(value):
        raise CryptoFrameError("PubKey kein 64-Hex")
    return bytes.fromhex(value)


# ── PH-3: VOLLES Double Ratchet (Signal-Referenz, fb-v1-Parametrisierung) ────

class DoubleRatchet:
    """Zustandsmaschine: RK, CKs/CKr, Ratchet-Pairs, Ns/Nr/PN, Skipped-Cache.

    Sender: encrypt() liefert (hdr, nonce, ct). Receiver: decrypt() mit
    Replay-Fenster (100 Counter), 10-min-Freshness prüft die Session-Schicht.
    DH-Rotation je rotate_every Nachrichten + automatisch je Peer-Ratchet-Wechsel.
    """

    def __init__(self, root_key: bytes, rotate_every: int = _RATCHET_ROTATE_EVERY):
        self.rk = root_key
        self.ck_s: Optional[bytes] = None
        self.ck_r: Optional[bytes] = None
        self.dh_priv: Optional[bytes] = None
        self.dh_pub: Optional[bytes] = None
        self.dh_r: Optional[bytes] = None
        self.ns = 0
        self.nr = 0
        self.pn = 0
        self.rotate_every = max(1, int(rotate_every))
        self.skipped: dict[tuple[str, int], bytes] = {}
        self._seen_peer_pubs: list[str] = []
        self.nonce_prefix = os.urandom(4)
        # ── Rotations-Kadenz (je N) ──────────────────────────────────────────
        # DH-Rotation geschieht (a) automatisch je Peer-Announcement (Signal-
        # Lockstep — JEDE neue Ratchet-Pub rotiert beide Ketten) und (b) je
        # Session (frische crypto.hello). Spontane Eigen-Rotation ist verboten:
        # sie bricht die RK-Parität (1-Advance passt nie auf die 2-Advance-
        # Ratchet-Schritte des Peers → GCM-Desync). rotate_every erzeugt daher
        # nur einen HINWEIS (hdr.rot=1) nach N Frames ohne DH-Schritt — das
        # Phone (Welle 2) kann darauf mit einer frischen crypto.hello antworten.
        self.sent_since_dh = 0        # gesendete Frames seit letztem Ratchet-Schritt

    # -- Init (Rollen) -------------------------------------------------------
    def init_responder(self, peer_ek_pub: bytes) -> None:
        """Agent nach X3DH: RA-Pair + Sende-Kette aus X25519(RA, EK_p)."""
        self.dh_priv, self.dh_pub = _x25519_gen()
        self.dh_r = peer_ek_pub
        self.rk, self.ck_s = _kdf_rk(self.rk, _x25519_dh(self.dh_priv, peer_ek_pub))
        self.ns = 0
        self.pn = 0
        self.nr = 0

    def init_initiator(self, own_ek_priv: bytes, own_ek_pub: bytes,
                       peer_ratchet_pub: bytes) -> None:
        """Phone nach hello_ack-Verify (Signal-Alice-Init, EIN Advance):
        RK1, CKr = KDF_RK(root0, X25519(EK, RA)) = Agent-Sendekette.
        CKs bleibt None — die erste eigene Sende-Nachricht macht den
        Alice-Sendeschritt (frisches Pair gegen RA), siehe encrypt()."""
        self.dh_priv, self.dh_pub = own_ek_priv, own_ek_pub
        self.dh_r = peer_ratchet_pub
        self.rk, self.ck_r = _kdf_rk(self.rk, _x25519_dh(own_ek_priv, peer_ratchet_pub))
        self.nr = 0
        self.ns = 0
        self.pn = 0

    # -- interne Schritte ----------------------------------------------------
    def _dh_ratchet(self, remote_pub: bytes) -> None:
        """Empfangs-Schritt (neuer Peer-Key): CKr aus ALTEN eigenen Priv × neu;
        danach neues eigenes Pair + neue Sende-Kette (Signal DHRatchet). Ein
        Ratchet-Schritt IST eine vollständige Kettens-Rotation (Lockstep)."""
        if self.dh_r is not None:
            self._track_peer_pub(self.dh_r.hex())
        self.dh_r = remote_pub
        self.rk, self.ck_r = _kdf_rk(self.rk, _x25519_dh(self.dh_priv, remote_pub))
        self.nr = 0
        self.pn = self.ns  # Länge der bisherigen Sende-Kette (für hdr.pn)
        self.dh_priv, self.dh_pub = _x25519_gen()
        self.rk, self.ck_s = _kdf_rk(self.rk, _x25519_dh(self.dh_priv, remote_pub))
        self.ns = 0
        self.nonce_prefix = os.urandom(4)
        self.sent_since_dh = 0  # Ratchet-Schritt = frische Rotation der Ketten

    def _rotate_send_chain(self) -> None:
        """Rotation: frisches Pair gegen den aktuellen Peer-Key. Aufruf NUR für
        die erste Phone-Sende-Nachricht (CKs=None, Signal-Alice-Sendeschritt)
        — Mittendrin-Spontan-Rotation ist verboten (RK-Parität, s. Klassen-
        Kommentar)."""
        if self.dh_r is None:
            raise RatchetError("kein Peer-Key — zuerst Handshake")
        self.pn = self.ns
        self.dh_priv, self.dh_pub = _x25519_gen()
        self.rk, self.ck_s = _kdf_rk(self.rk, _x25519_dh(self.dh_priv, self.dh_r))
        self.ns = 0
        self.nonce_prefix = os.urandom(4)

    def _track_peer_pub(self, pub_hex: str) -> None:
        if pub_hex not in self._seen_peer_pubs:
            self._seen_peer_pubs.append(pub_hex)
            while len(self._seen_peer_pubs) > _SEEN_PEER_PUBS_MAX:
                self._seen_peer_pubs.pop(0)

    def _skip_message_keys(self, until: int, remote_hex: str) -> None:
        """Übersprungene Message-Keys [nr, until) ableiten + cachen (Cap 100)."""
        if self.ck_r is None or until is None or until <= self.nr:
            return
        while self.nr < until:
            if len(self.skipped) >= _SKIPPED_KEY_CACHE_MAX:
                self.skipped.pop(next(iter(self.skipped)))
            self.ck_r, mk = _kdf_ck(self.ck_r)
            self.skipped[(remote_hex, self.nr)] = mk
            self.nr += 1

    # -- Chiffre -------------------------------------------------------------
    def encrypt(self, payload: bytes, aad: bytes) -> tuple[dict, bytes, bytes]:
        if self.dh_pub is None:
            raise RatchetError("kein eigenes Ratchet-Pair — zuerst Handshake")
        if self.ck_s is None:
            self._rotate_send_chain()  # erste Phone-Sende-Nachricht (Alice-Schritt)
        self.ck_s, mk = _kdf_ck(self.ck_s)
        nonce = self.nonce_prefix + self.ns.to_bytes(8, "big")
        ct = AESGCM(_msg_aes_key(mk)).encrypt(nonce, payload, aad)
        header = {"rk": self.dh_pub.hex(), "n": self.ns, "pn": self.pn}
        self.sent_since_dh += 1
        # Rotations-Hinweis je N (KEINE Krypto-Wirkung — nur Signal an das
        # Phone: 'DH-Schritt überfällig, neue crypto.hello sinnvoll').
        if self.sent_since_dh % self.rotate_every == 0:
            header["rot"] = 1
        self.ns += 1
        return header, nonce, ct

    def decrypt(self, header: dict, nonce: bytes, ct: bytes, aad: bytes) -> bytes:
        if not isinstance(header, dict):
            raise CryptoFrameError("hdr fehlt")
        remote_hex = header.get("rk")
        remote = _hex32(remote_hex)
        try:
            n = int(header.get("n"))
            pn = int(header.get("pn", 0))
        except (TypeError, ValueError) as exc:
            raise CryptoFrameError("hdr-Zähler ungültig") from exc
        if n < 0 or pn < 0:
            raise CryptoFrameError("hdr-Zähler negativ")
        if len(nonce) != 12 or nonce[4:] != n.to_bytes(8, "big"):
            raise CryptoFrameError("Nonce passt nicht zum Zähler")

        # 1) Out-of-Order aus dem Skipped-Cache (Verbrauch = Replay-Schutz).
        mk = self.skipped.pop((remote.hex(), n), None)
        if mk is not None:
            return AESGCM(_msg_aes_key(mk)).decrypt(nonce, ct, aad)

        # 2) Alter Peer-Key = Replay (kein Zustands-Konsum, gerader Drop).
        if self.dh_r is not None and remote != self.dh_r:
            if remote.hex() in self._seen_peer_pubs:
                raise CryptoFrameError("alter Ratchet-Key (Replay)")
            # PH-3 F2 (Jäger P2): pn-Cap — hdr.pn ist ANGREIFER-KONTROLLIERT
            # (bis 2³¹); ohne Cap leitet _skip_message_keys Milliarden
            # Message-Keys ab (CPU/RAM-DoS). Grenze = Empfangs-Fenster
            # (Parität: Kotlin DoubleRatchet.decrypt, gleiche Meldung).
            if pn > self.nr + _REPLAY_WINDOW:
                raise CryptoFrameError("pn über Fenster (>100)")
            self._skip_message_keys(pn, self.dh_r.hex())
            self._dh_ratchet(remote)
        if self.ck_r is None:
            raise CryptoFrameError("keine Empfangs-Kette")

        # 3) Fenster-Prüfung (100 Counter) + Ablauf der aktuellen Kette.
        if n < self.nr:
            raise CryptoFrameError("Zähler unter Fenster (Replay)")
        if n >= self.nr + _REPLAY_WINDOW:
            raise CryptoFrameError("Zähler über Fenster (>100)")
        self._skip_message_keys(n, remote.hex())
        self.ck_r, mk = _kdf_ck(self.ck_r)
        self.nr = n + 1
        return AESGCM(_msg_aes_key(mk)).decrypt(nonce, ct, aad)


# ── PH-3: Sessions (Agent-Responder + Phone-Referenz für Tests/Kotlin) ───────

class _CryptoSessionBase:
    """Gemeinsamer Frame-Überbau über dem DoubleRatchet (beide Rollen)."""

    def __init__(self, session_id: str, agent_id: str, root_key: bytes,
                 session_fp_hex: str, rotate_every: int):
        self.session_id = session_id
        self.agent_id = agent_id
        self.created = time.time()
        self.ratchet = DoubleRatchet(root_key, rotate_every=rotate_every)
        self._fp_hex = session_fp_hex

    @property
    def fingerprint(self) -> str:
        """session_fp (64 Hex) — identisch auf beiden Seiten (Abgleich-Wert)."""
        return self._fp_hex

    def expired(self) -> bool:
        return (time.time() - self.created) > _SESSION_MAX_AGE_SECONDS

    def encrypt_frame(self, frame_type: str, chat_id: str, payload: dict,
                      clear: Optional[dict] = None) -> dict:
        """chat.*.enc-Frame bauen (Klartext-Payload → AES-GCM, Metadaten klar)."""
        header, nonce, ct = self.ratchet.encrypt(
            _json_payload(payload), _enc_aad(self.session_id, chat_id, self.agent_id))
        frame = {
            "type": frame_type,
            "v": _PROTOCOL_VERSION,
            "session_id": self.session_id,
            "chat_id": chat_id,
            "agent_id": self.agent_id,
            "hdr": header,
            "nonce": _b64e(nonce),
            "ct": _b64e(ct),
            "ts": int(time.time() * 1000),
        }
        if clear:
            frame.update(clear)
        return frame

    def decrypt_frame(self, frame: dict) -> dict:
        """chat.*.enc-Frame entschlüsseln — bei JEDEM Fehlschlag konstante
        0,25 s Verzögerung + Fehler OHNE Inhalt (kein Timing-/Text-Orakel)."""
        try:
            if not isinstance(frame, dict):
                raise CryptoFrameError("Frame kein Objekt")
            if frame.get("session_id") != self.session_id:
                raise CryptoFrameError("fremde session_id")
            if self.expired():
                raise CryptoFrameError("Session abgelaufen (>24h)")
            ts = frame.get("ts")
            # PH-3 F3 (Jäger P2): ts ist PFLICHT — ein fehlender ts übersprang
            # bis jetzt die 10-min-Fenster-Prüfung (Replay-Schutz umgehbar).
            # Parität: Kotlin PhoneCryptoPeer.decryptFrame, gleiche Meldung.
            if not isinstance(ts, (int, float)) or isinstance(ts, bool):
                raise CryptoFrameError("ts fehlt (Pflicht)")
            # SEC-W1 B F-04: NaN ist ein float und fiel durch die alte
            # Prüfung (NaN < cutoff = False) — Freshness deaktivierbar.
            # Parität zur Relay-Prüfung (math.isfinite).
            if not math.isfinite(ts):
                raise CryptoFrameError("ts nicht endlich (NaN/inf)")
            if ts < int(time.time() * 1000) - _FRAME_MAX_AGE_MS:
                raise CryptoFrameError("Frame zu alt (>10 min)")
            # JÄGER J11 (P2, JAEGE-RFULL 02.11.2026): Zukunfts-Grenze fehlte —
            # SEC-W1 W1C-8 fixierte das ±10-min-Fenster nur am Relay + Phone
            # (PhoneCryptoPeer.decryptFrame), der Adapter prüfte nur die
            # Vergangenheit: ein ts aus der Zukunft (+1 h) passierte die
            # Freshness. Symmetrische Obergrenze, Parität zur Untergrenze.
            if ts > int(time.time() * 1000) + _FRAME_MAX_AGE_MS:
                raise CryptoFrameError("Frame aus der Zukunft (>10 min)")
            nonce = _b64d(str(frame.get("nonce") or ""))
            if len(nonce) != 12:
                raise CryptoFrameError("Nonce nicht 12 Byte")
            ct = _b64d(str(frame.get("ct") or ""))
            if not ct or len(ct) > _ENC_CT_MAX_BYTES:
                raise CryptoFrameError("ct leer/zu groß")
            chat_id = str(frame.get("chat_id") or "")
            pt = self.ratchet.decrypt(frame.get("hdr"), nonce, ct,
                                      _enc_aad(self.session_id, chat_id, self.agent_id))
            return json.loads(pt.decode("utf-8"))
        except json.JSONDecodeError as exc:
            time.sleep(_DECRYPT_FAIL_DELAY)
            raise CryptoFrameError("Payload kein JSON") from exc
        except InvalidTag:
            time.sleep(_DECRYPT_FAIL_DELAY)
            raise CryptoFrameError("GCM-Tag ungültig") from None
        except CryptoFrameError:
            time.sleep(_DECRYPT_FAIL_DELAY)
            raise


class AgentCryptoSession(_CryptoSessionBase):
    """Agent-Seite (X3DH-Responder): crypto.hello → hello_ack + Ratchet."""

    @classmethod
    def respond_to_hello(cls, hello: dict, identity: dict, psk: bytes = b"",
                         rotate_every: int = _RATCHET_ROTATE_EVERY
                         ) -> tuple["AgentCryptoSession", dict]:
        if not _HAS_CRYPTO:
            raise CryptoFrameError("cryptography fehlt (Legacy-Modus)")
        try:
            session_id = str(hello["session_id"])
            agent_id = str(hello["agent_id"])
        except (KeyError, TypeError) as exc:
            raise CryptoFrameError("hello unvollständig") from exc
        if not (8 <= len(session_id) <= 128) or not agent_id:
            raise CryptoFrameError("session_id/agent_id ungültig")
        ik_p = _hex32(hello.get("ik_pub"))
        ek_p = _hex32(hello.get("ek_pub"))
        spk_p = _hex32(hello.get("spk_pub"))
        ik_a_priv = _hex32(identity.get("identity_priv"))
        spk_a_priv = _hex32(identity.get("prekey_priv"))
        ik_a = _hex32(identity.get("identity_pub"))
        spk_a = _hex32(identity.get("prekey_pub"))
        dh1 = _x25519_dh(ik_a_priv, ek_p)    # X25519(EK_p, IK_a)
        dh2 = _x25519_dh(ik_a_priv, ik_p)    # X25519(IK_p, IK_a)
        dh3 = _x25519_dh(spk_a_priv, ek_p)   # X25519(EK_p, SPK_a)
        kdf = _fb_v1_kdf(dh1, dh2, dh3, psk, session_id, agent_id,
                         ik_p, ek_p, spk_p, ik_a, spk_a)
        sess = cls(session_id, agent_id, kdf["root0"], kdf["session_fp"], rotate_every)
        sess.ratchet.init_responder(ek_p)
        ack_nonce = os.urandom(12)
        ack_ct = AESGCM(kdf["k_init"]).encrypt(
            ack_nonce,
            b"flipsi-verify-v1|" + session_id.encode("utf-8"),
            f"fb-v1|{session_id}|{agent_id}".encode("utf-8"))
        ack = {
            "type": "crypto.hello_ack",
            "v": _PROTOCOL_VERSION,
            "session_id": session_id,
            "agent_id": agent_id,
            "kex_pub": sess.ratchet.dh_pub.hex(),
            "nonce": _b64e(ack_nonce),
            "ct": _b64e(ack_ct),
        }
        return sess, ack


class PhoneCryptoPeer(_CryptoSessionBase):
    """App-REFERENZ-Implementierung (Phone-Initiator) — Python-Gegenstelle
    für E2E-Tests + byte-exakte Vorlage für Kotlin Welle 2. NICHT im
    Produktiv-Adapter-Pfad (der Adapter ist ausschließlich Responder)."""

    def __init__(self, agent_id: str, agent_ik_pub_hex: str,
                 agent_spk_pub_hex: str, psk: bytes = b"",
                 rotate_every: int = _RATCHET_ROTATE_EVERY):
        if not _HAS_CRYPTO:
            raise CryptoFrameError("cryptography fehlt")
        super().__init__("", "", b"", "", rotate_every)  # Felder folgen sofort
        self.agent_id = agent_id  # überschreibt den Leer-Default der Basis
        self.agent_ik_pub = _hex32(agent_ik_pub_hex)
        self.agent_spk_pub = _hex32(agent_spk_pub_hex)
        self.psk = psk
        self.rotate_every = rotate_every
        self.ik_priv, self.ik_pub = _x25519_gen()
        self.spk_priv, self.spk_pub = _x25519_gen()
        self.ek_priv: Optional[bytes] = None
        self.ek_pub: Optional[bytes] = None
        self._pending: Optional[dict] = None

    def make_hello(self, session_id: str) -> dict:
        self.ek_priv, self.ek_pub = _x25519_gen()
        dh1 = _x25519_dh(self.ek_priv, self.agent_ik_pub)
        dh2 = _x25519_dh(self.ik_priv, self.agent_ik_pub)
        dh3 = _x25519_dh(self.ek_priv, self.agent_spk_pub)
        kdf = _fb_v1_kdf(dh1, dh2, dh3, self.psk, session_id, self.agent_id,
                         self.ik_pub, self.ek_pub, self.spk_pub,
                         self.agent_ik_pub, self.agent_spk_pub)
        self.session_id = session_id
        self.ratchet = DoubleRatchet(kdf["root0"], rotate_every=self.rotate_every)
        self._fp_hex = kdf["session_fp"]
        self._pending = kdf
        return {
            "type": "crypto.hello",
            "v": _PROTOCOL_VERSION,
            "session_id": session_id,
            "agent_id": self.agent_id,
            "ik_pub": self.ik_pub.hex(),
            "ek_pub": self.ek_pub.hex(),
            "spk_pub": self.spk_pub.hex(),
            "ts": int(time.time() * 1000),
        }

    def consume_ack(self, ack: dict) -> None:
        """hello_ack verifizieren (Wrong-Key = MITM/PSK → Session verwerfen)."""
        if self._pending is None or self.ek_priv is None or self.ek_pub is None:
            raise CryptoFrameError("consume_ack vor make_hello")
        try:
            if str(ack.get("session_id")) != self.session_id:
                raise CryptoFrameError("fremde session_id")
            kex_pub = _hex32(ack.get("kex_pub"))
            nonce = _b64d(str(ack.get("nonce") or ""))
            ct = _b64d(str(ack.get("ct") or ""))
            pt = AESGCM(self._pending["k_init"]).decrypt(
                nonce, ct,
                f"fb-v1|{self.session_id}|{self.agent_id}".encode("utf-8"))
        except InvalidTag:
            raise CryptoFrameError("hello_ack-Verify fehlgeschlagen (Wrong-Key/PSK)") from None
        if pt != b"flipsi-verify-v1|" + self.session_id.encode("utf-8"):
            raise CryptoFrameError("hello_ack-Payload falsch")
        self.ratchet.init_initiator(self.ek_priv, self.ek_pub, kex_pub)
        self.created = time.time()
        self._pending = None  # k_init nur für den Handshake — danach verwerfen


# InvalidTag: cryptography-Exceptions VOR dem Guarded-Import aliasen
if _HAS_CRYPTO:
    from cryptography.exceptions import InvalidTag  # noqa: E402
else:  # pragma: no cover
    class InvalidTag(Exception):  # type: ignore[no-redef]
        pass


# ── PH-3: Agent-Identity-Key-File (0600, Keys NIE loggen) ─────────────────────

_IDENTITY_FIELDS = ("identity_priv", "identity_pub", "prekey_priv", "prekey_pub")


def _pub_of_priv(priv_hex: str) -> str:
    return X25519PrivateKey.from_private_bytes(bytes.fromhex(priv_hex)) \
        .public_key().public_bytes(Encoding.Raw, PublicFormat.Raw).hex()


def load_or_create_identity(path: str) -> dict:
    """Identity-File laden oder erzeugen (X25519 Identity+PreKey, hex, 0600).

    Fail-closed: existiert die Datei, wird sie streichend validiert (Pubs
    passen zu Priv-Keys) — korrupte Datei = Fehler, KEIN stiller Re-Key
    (Fingerprint bleibt stabil). Private Keys werden NIE geloggt/ausgegeben.
    """
    if not _HAS_CRYPTO:
        raise CryptoFrameError("cryptography fehlt — keine Identity-Verwaltung")
    data = None
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except FileNotFoundError:
        data = None
    except (OSError, ValueError) as exc:
        raise CryptoFrameError(f"Identity-File unlesbar ({os.path.basename(path)})") from exc

    if isinstance(data, dict) and all(
        isinstance(data.get(f), str) and _HEX32_RE.fullmatch(data[f])
        for f in _IDENTITY_FIELDS
    ):
        if (_pub_of_priv(data["identity_priv"]) == data["identity_pub"]
                and _pub_of_priv(data["prekey_priv"]) == data["prekey_pub"]):
            return data
        raise CryptoFrameError("Identity-File korrupt (Pub/Priv-Mismatch)")

    if data is not None:
        raise CryptoFrameError("Identity-File korrupt (ungültige Felder)")

    ik_priv, ik_pub = _x25519_gen()
    spk_priv, spk_pub = _x25519_gen()
    fresh = {
        "v": 1,
        "identity_priv": ik_priv.hex(),
        "identity_pub": ik_pub.hex(),
        "prekey_priv": spk_priv.hex(),
        "prekey_pub": spk_pub.hex(),
        "created": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    os.makedirs(os.path.dirname(os.path.abspath(path)) or ".", exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        try:
            os.fchmod(fd, 0o600)  # POSIX-Dateirechte; Windows kennt kein fchmod
        except AttributeError:
            # SEC-W1 D3b: silently pass ließ Bestands-Dateirechte (z. B. 0644,
            # weltlesbar — die Datei enthält X25519-PRIVATE-Keys) UNREPARIERT.
            logger.warning(
                "fchmod nicht verfügbar (Windows?) — Dateirechte von %s unverändert "
                "(Bestands-ACLs prüfen; Datei enthält Private Keys)",
                os.path.basename(path))
        json.dump(fresh, fh, indent=2)
        fh.write("\n")
    return fresh



class AdapterPinningError(RuntimeError):
    """Relay-URL ist nicht in der konfigurierten Whitelist (oder ungültiges Schema)."""


def _cfg(extra: dict, env_var: str, default: str = "") -> str:
    """Adapter-Config-Wert: extra-Dict zuerst, dann Env (generisch, niemals geloggt)."""
    value = str(extra.get(env_var.lower(), "") or "").strip()
    if not value:
        value = str(os.environ.get(env_var, "") or "").strip()
    return value or default


class FlipsiBridgeChatAdapter(BasePlatformAdapter):
    """Chat-Adapter gegen den FlipsiBridge-Relay (Agent-WS-Rolle)."""

    def __init__(self, config: Any = None) -> None:
        # PlatformConfig (Hermes, .extra-Dict) oder plain dict (Skeleton/Test)
        if _UNDER_HERMES:
            # Plugin-Plattform: Enum-Member dynamisch auflösen (Plugin-Loader legt
            # „flipsibridge“-Member an; sonst None und der Loader stempelt self.platform).
            platform_arg = None
            try:
                from gateway.config import Platform
                platform_arg = Platform("flipsibridge")
            except (ImportError, ValueError):
                pass
            super().__init__(config, platform_arg)  # type: ignore[arg-type]
        else:
            super().__init__(config)
        extra = getattr(config, "extra", None) or (config if isinstance(config, dict) else {}) or {}

        # --- Adapter-Config-Keys (je Agent-Heim generisch; Token nur im .env) ---
        self.relay_url: str = _cfg(extra, "FLIPSI_RELAY_URL")
        self.agent_token: str = _cfg(extra, "FLIPSI_AGENT_TOKEN")
        self.agent_id: str = _cfg(extra, "FLIPSI_AGENT_ID", default="agent")
        self.display_name: str = _cfg(extra, "FLIPSI_DISPLAY_NAME", default=self.agent_id)
        self.ws_route: str = _cfg(extra, "FLIPSI_WS_ROUTE", default="/agent_ws")
        self.chat_route: str = _cfg(extra, "FLIPSI_CHAT_ROUTE", default="/chat")
        # Whitelist: Default = genau die 1 konfigurierte URL (I3-Pinning).
        whitelist_raw = _cfg(extra, "FLIPSI_ALLOWED_URLS", default=self.relay_url)
        self.allowed_urls: frozenset[str] = frozenset(
            u.strip().rstrip("/") for u in whitelist_raw.split(",") if u.strip())
        self.allowed_devices: frozenset[str] = frozenset(
            d.strip() for d in _cfg(extra, "FLIPSI_ALLOWED_DEVICES").split(",") if d.strip())
        self.allow_insecure: bool = _cfg(extra, "FLIPSI_ALLOW_INSECURE") == "1"

        # SEC-W1 B F-08: Pinning-Tautologie — ohne explizites FLIPSI_ALLOWED_URLS
        # defaultet die Whitelist auf die Relay-URL selbst und pinnt damit JEDE
        # (auch kompromittierte) URL gegen sich selbst. Fail-closed bleibt
        # erhalten, aber die unabhängige Vertrauenswurzel fehlt → laut warnen.
        if not _cfg(extra, "FLIPSI_ALLOWED_URLS"):
            logger.warning(
                "FlipsiBridge: FLIPSI_ALLOWED_URLS nicht gesetzt — URL-Pinning "
                "defaultet auf die Relay-URL selbst (Tautologie, keine unabhängige "
                "Vertrauenswurzel). Für Produktion die Whitelist unabhängig setzen.")

        # --- PH-3 (Welle 1): E2E-Crypto-Config (Agent = Responder) ---
        # Identity-File: X25519 Identity+PreKey, 0600, Private Keys nie geloggt.
        self.identity_file: str = _cfg(extra, "FLIPSI_IDENTITY_FILE") or os.path.join(
            os.path.expanduser("~"), ".flipsibridge", "agent-identity.json")
        # SEC-W1 W1A-5: TOFU-Pin der Phone-Identity — relay-keys.json hat am
        # Agenten KEINEN Consumer (jeder X3DH-Hello galt als 'das Phone',
        # Session-Verdrängung per Code-Leak). Erster Handshake pint ik_pub.
        self.phone_pin_file: str = _cfg(extra, "FLIPSI_PHONE_PIN_FILE") or os.path.join(
            os.path.dirname(os.path.abspath(self.identity_file)) or ".",
            "phone-pin.json")
        # --- Zusatz-Order 02.11.2026 („EIN Code für alles", PIPELINE-BUGS): ---
        # FLIPSI_PAIRING_PSK ist TOD. Der zweite Code existierte NUR hier
        # (App-Seite hat nie einen PSK übergeben — genau diese Asymmetrie
        # war Bug 1: agent-seitig k_init mit psk_comp≠konstant abgeleitet,
        # app-seitig mit psk=b"" → hello_ack-Verify still gescheitert →
        # E2E-Deadlock mit Relay-403 auf jede Klartext-Nachricht).
        # EIN Code = der Relay-Pairing-Code (App↔Relay: Bearer je Route;
        # Relay↔Agent: Agent-Token). E2E-Identity-Verifikation läuft über
        # das bestehende Fingerprint-Abgleich-Fenster (FP-ABGLEICH-Chat +
        # TOFU-Phone-Pin) — das Fenster bleibt unverändert.
        # Härtung gegen Wiedereinführung: konfigurierter PSK wird NICHT mehr
        # verwendet, sondern EINMAL laut gewarnt (Ops sieht den toten Key).
        # SCOPE-GUARD (Sir, 02.11.2026): FlipsiBridge-spezifisch — KEIN
        # Plattform-übergreifender Eingriff, andere Adapter (Discord/
        # Telegram/Signal/WhatsApp/…) bleiben unberührt.
        _configured_psk = _cfg(extra, "FLIPSI_PAIRING_PSK")
        if _configured_psk:
            logger.warning(
                "FlipsiBridge: FLIPSI_PAIRING_PSK ist ENTFERNT/TOT — E2E nutzt "
                "psk=b\"\" + Fingerprint-Abgleich; der Config-Schlüssel wird "
                "IGNORIERT. Bitte 'flipsi_pairing_psk' aus der config.yaml "
                "streichen (Zusatz-Order 02.11.2026: EIN Code = Relay-Pairing-Code).")
        try:
            self.ratchet_rotate: int = max(
                1, int(_cfg(extra, "FLIPSI_RATCHET_ROTATE", default=str(_RATCHET_ROTATE_EVERY))
                       or _RATCHET_ROTATE_EVERY))
        except ValueError:
            self.ratchet_rotate = _RATCHET_ROTATE_EVERY
        self._identity: Optional[dict] = None
        self._crypto_session: Optional[AgentCryptoSession] = None
        self._session_reset_pending: bool = False
        self._bg_tasks: list = []  # geplante Sends (Ack/FP-Antwort) — Test-Hook

        # --- Laufzeit-Zustand ---
        self._ws: Any = None
        self._frame_task: Optional[asyncio.Task] = None
        self._connected: bool = False
        self._ws_factory: Optional[Callable[[str, str, str], Any]] = None
        # Skeleton/Test-Pfad: Inbound-Texte gehen an diesen Callback, wenn der echte
        # Gateway (handle_message) fehlt.
        self.message_callback: Optional[Callable[[dict], None]] = None
        # SEC-W2 W2-3: FIFO-Cap (Parität zu den Relay-Maps W1 D2/W1C-7) —
        # die Diagnose-Liste wuchs UNBEGRENZT: ein (kompromittierter) Relay
        # oder Junk-Quelle füllte rejected_frames je Frame +1 ohne Obere Grenze
        # (RAM-Wachstum im Agent-Prozess). deque behält append/len/Index-Semantik.
        self.rejected_frames: deque = deque(maxlen=256)

        # PH2-T11 (F2-S2+S3): Empfangszeit je chat_id (Unix-ms) — Basis für
        # elapsed_ms (Empfang→Antwort) und started_at des Activity-Frames.
        # Bewusst begrenzt (FIFO-Cap): Empfangs-Zeiten sind kein Archiv.
        self._received_at: dict[str, int] = {}

        # --- MEDIA-T1: Media-Empfang/-Versand (chunked, E2E-only) ---
        # Empfangene Dateien landen im konfigurierbaren Media-Dir (0700);
        # Retention = Gesamtbudget je Dir (älteste Dateien fliegen zuerst).
        self.media_dir: str = _cfg(extra, "FLIPSI_MEDIA_DIR") or os.path.join(
            os.path.expanduser("~"), ".flipsibridge", "media")
        try:
            self.media_dir_max_bytes: int = max(
                _MEDIA_CHUNK_BYTES,
                int(_cfg(extra, "FLIPSI_MEDIA_DIR_MAX_BYTES",
                          default=str(_MEDIA_DIR_MAX_BYTES))
                    or _MEDIA_DIR_MAX_BYTES))
        except ValueError:
            self.media_dir_max_bytes = _MEDIA_DIR_MAX_BYTES
        # media_id → {"assembler", "kind", "name", "mime", "size", "ts"}
        self._media_pending: dict[str, dict] = {}
        self._media_lock = __import__("threading").Lock()

        # --- FIX-WELLE-2 B1 (P1): Gateway-Freigabe an die Phone-IDENTITÄT
        # binden statt an die Session-chat_id. Hintergrund (HERZ-Befund 1):
        # die Relay-chat_id ist je E2E-Session NEU (App-Restart, Relay-Restart,
        # Neu-Konversation) — die Gateway-Pairing-Freigabe ('hermes pairing
        # approve') lief je chat_id und verfiel bei JEDEM Session-Wechsel
        # (3× manuelle Neu-Freigabe im Realtest). Der Adapter übersetzt am
        # Gateway-Rand: Hermes sieht die STABILE, identity-gebundene ID
        # 'flipsi-phone-<ik8>' (ik_pub des Phones, TOFU-gepint / FP-geprüft),
        # auf dem Wire (Envelope + AES-GCM-AAD) läuft weiter die echte
        # Relay-chat_id — Freigabe bleibt über Session-Wechsel hinweg.
        self._gw_chat_by_relay: dict[str, str] = {}
        self._relay_chat_by_gw: dict[str, str] = {}
        self._active_phone_ik: str = ""

        # --- W3-02: Auto-Approve-Schiene — PairingStore-Injektion (Tests;
        # None = echter Gateway-Store via gateway.pairing, in-process). ---
        self._pairing_store_factory: Optional[Callable[[], Any]] = None

        # --- FIX-WELLE-2 B2 (P3): TOFU-Pin-Warnung je NEUEM Key nur EINMAL —
        # sonst warnte JEDE Reconnect-Wiederholung denselben Identity-Wechsel
        # erneut (HERZ-Befund 2: Warn-Spam, Alarm-Fatigue). Bestätigt der
        # FP-Abgleich die neue Identity, pint der Adapter sie persistent um
        # (siehe _confirm_phone_pin).
        self._pin_warned_ik: str = ""

        # --- FIX-WELLE-2 B3 (P3): Home-Kanal-Hinweis des Gateways flutete je
        # Turn die Konversation (HERZ-Befund 3). Der Adapter drosselt die
        # Zustellung: 1 Hinweis je Agent (Adapter-Instanz) pro Zeitfenster.
        self._home_notice_last_ts: float = 0.0

    # ------------------------------------------------------------------ Pinning
    def _pin_relay_url(self) -> str:
        """Whitelist-Pinning (I3): geprüfte Relay-URL oder AdapterPinningError.

        - Muss schema-genau wss:// bzw. https:// sein (ws/http nur bei
          FLIPSI_ALLOW_INSECURE=1 für lokale QA-Drills).
        - Kein userinfo (@) in der URL.
        - Muss exakt (normalisiert, ohne trailing slash) in self.allowed_urls stehen.
        """
        url = (self.relay_url or "").strip()
        if not url:
            raise AdapterPinningError("Keine Relay-URL konfiguriert (FLIPSI_RELAY_URL)")
        parsed = urlsplit(url)
        if "@" in (parsed.netloc or ""):
            raise AdapterPinningError("Relay-URL mit userinfo abgelehnt")
        scheme = (parsed.scheme or "").lower()
        if scheme not in {"wss", "https"} and not (self.allow_insecure and scheme in {"ws", "http"}):
            raise AdapterPinningError(
                f"Schema {parsed.scheme or '<leer>'!r} nicht erlaubt (nur wss/https"
                + (", ws/http nur mit FLIPSI_ALLOW_INSECURE=1)" if not self.allow_insecure else ")"))
        normalized = url.rstrip("/")
        if normalized not in self.allowed_urls:
            raise AdapterPinningError("Relay-URL nicht in der konfigurierten Whitelist — verweigert")
        return url

    # ------------------------------------------------------- Base-Schnittstelle
    async def connect(self, *, is_reconnect: bool = False) -> bool:
        """OUTBOUND-Verbindung zum Relay (Agent-WS-Route) + Frame-Loop starten."""
        url = self._pin_relay_url()  # Pinnung VOR jedem Verbindungsaufbau
        if not self.agent_token:
            logger.error("FlipsiBridge: kein Agent-Token konfiguriert — kein Connect")
            return False
        try:
            self._ws = await self._open_ws(url)
        except Exception as exc:
            logger.warning("FlipsiBridge: WS-Connect fehlgeschlagen: %s", exc)
            self._connected = False
            return False
        # Identität announce (kein Token im Envelope — Token steckt im WS-Auth-Header)
        await self._send_json({
            "type": "agent.register", "v": _PROTOCOL_VERSION,
            "agent_id": self.agent_id, "display_name": self.display_name})
        # PH-3: Identity-Keys laden/erzeugen (File 0600, Keys NIE loggen — nur
        # der Identity-Fingerprint ist öffentliche Info).
        if _HAS_CRYPTO:
            try:
                self._identity = load_or_create_identity(self.identity_file)
                logger.info("FlipsiBridge: PH-3 E2E bereit (Identity-Fingerprint %s)",
                            fingerprint_display(fingerprint_of_pub(
                                bytes.fromhex(self._identity["identity_pub"]))))
            except CryptoFrameError as exc:
                logger.error("FlipsiBridge: %s — E2E deaktiviert (Legacy-Modus)", exc)
        else:
            logger.warning(
                "FlipsiBridge: cryptography fehlt — Legacy-Klartext-Modus (PH-3 nicht verfügbar)")
        self._frame_task = asyncio.get_running_loop().create_task(self._frame_loop())
        self._connected = True
        logger.info("FlipsiBridge-Adapter verbunden (agent_id=%s)", self.agent_id)
        return True

    async def _open_ws(self, url: str) -> Any:
        """WS öffnen: injizierbare Factory (Mock-Test), sonst aiohttp ClientSession."""
        if self._ws_factory is not None:
            return self._ws_factory(url, self.agent_token, self.agent_id)
        try:
            from aiohttp import ClientSession
        except ImportError as exc:
            raise RuntimeError("aiohttp fehlt — pip install aiohttp") from exc
        session = ClientSession()
        ws = await session.ws_connect(
            url, headers={"Authorization": f"Bearer {self.agent_token}"},
            heartbeat=45.0,  # Präsenz-Heartbeat (Reuse 15-s-Relay-Erkennung, gedrosselt)
            max_msg_size=2 * 1024 * 1024)  # REL-04: 2-MB-Frame-Limit NICHT anheben
        ws._flipsi_own_session = session
        setattr(ws, "_flipsi_session", session)  # Cleanup-Referenz für disconnect()
        return ws

    async def disconnect(self) -> None:
        """Frame-Loop stoppen + WS sauber schließen."""
        self._connected = False
        if self._frame_task is not None:
            self._frame_task.cancel()
            try:
                await self._frame_task
            except (asyncio.CancelledError, Exception):
                pass
            self._frame_task = None
        if self._ws is not None:
            try:
                await self._ws.close()
            except Exception:
                pass
            session = getattr(self._ws, "_flipsi_session", None)
            if session is not None:
                try:
                    await session.close()
                except Exception:
                    pass
            self._ws = None
        logger.info("FlipsiBridge-Adapter getrennt")

    # ------------------------------------------------------ FIX-WELLE-2 B1/B3
    _STABLE_CHAT_PREFIX = "flipsi-phone-"
    _HOME_NOTICE_WINDOW_S = 24 * 3600  # 1 Hinweis je Agent pro 24-h-Fenster
    _HOME_NOTICE_MARKERS = ("No home channel is set for",
                            "kein Home-Kanal festgelegt")
    # --- W3-02: Auto-Approve-Schiene — Anzeigename des Phone-Principals im
    # PairingStore des Gateways (nur Metadaten, die Freigabe-Verwurzelung
    # läuft über die Phone-Identity ik_pub, nicht über den Namen). ---
    _AUTO_APPROVE_USER_NAME = "FlipsiBridge-Phone"

    def _pairing_store(self):
        """W3-02: PairingStore des Gateways (in-process) holen.

        Der Adapter läuft IM Gateway-Prozess — der Store ist derselbe, den
        ``hermes pairing approve`` schreibt (pro Profil getrennt). Läuft der
        Adapter außerhalb des Gateways (Skeleton/Tests) oder ist die
        Gateway-API nicht importierbar, liefert er None und die normale
        Gateway-Pairing-Schiene bleibt unberührt (fail-open nur in die
        BESTEHende Freigabe-Prüfung, nie in eine Schwäche). Über
        ``_pairing_store_factory`` injizierbar (Tests/Drills).
        """
        factory = getattr(self, "_pairing_store_factory", None)
        if factory is not None:
            return factory()
        try:
            from gateway.pairing import PairingStore  # noqa: PLC0415 (in-process)
        except ImportError:
            return None
        try:
            return PairingStore()
        except Exception:
            logger.warning("FlipsiBridge: PairingStore nicht konstruierbar",
                           exc_info=True)
            return None

    def _ensure_phone_auto_approved(self, gw_chat_id: str) -> bool:
        """W3-02 AUTO-APPROVE: Bridge-Code-gepaartes Phone ist je ALLE Agenten
        automatisch chat-freigegeben — Freigabe gebunden an ik_pub.

        Threat-Modell (SECURITY-REPORT-W3): DER eine Schlüssel ist der
        Bridge-Kopplungscode — das Relay nimmt Phone-WS/HTTP-Auth NUR mit
        genau diesem Code an (konstant-zeit verglichen, 5 Fails = 300 s
        IP-Block). Jeder Frame, der beim Adapter ankommt, hat diese Auth-
        Kette bereits durchlaufen; die Phone-Identity (ik_pub aus dem
        bestätigten E2E-Handshake) ist damit kryptografisch an die
        Bridge-Code-Kopplung gebunden. Der Adapter übersetzt das am
        Gateway-Rand in eine PairingStore-Freigabe der stabilen ID
        'flipsi-phone-<ik8>': 0 Code-DMs, 0 'Unauthorized'-Hinhalten, 0
        pending-request-Frames an die Phone-Chat. Stabil je Reinstall:
        ein neu installiertes Phone mit neuem ik_pub wird mit dem GLEICHEN
        Bridge-Code wieder gepaart → neuer Handshake → Auto-Approve der
        neuen Identity-ID. Der Fingerprint-Abgleich bleibt als
        MITM-ANZEIGE bestehen (Verifikation, keine Freigabe-Grenze).

        Grenzen (bewusst): Legacy-Klartext ohne E2E-Identity (kein
        'flipsi-phone-'-Präfix) wird NICHT auto-approvt — dort greift
        weiterhin das normale Gateway-Pairing. Ein Fehler im Auto-Approve
        (Store nicht verfügbar) fällt NIE in eine Freigabe, sondern zurück
        auf die Gateway-Prüfung.
        """
        if not gw_chat_id.startswith(self._STABLE_CHAT_PREFIX):
            return False  # Legacy/ohne Identity → normale Gateway-Pairing-Schiene
        store = self._pairing_store()
        if store is None:
            return False
        platform = getattr(getattr(self, "platform", None), "value", None) \
            or "flipsibridge"
        try:
            if store.is_approved(platform, gw_chat_id):
                return True
            with store._locked():
                # Re-Check unter Lock (Doppel-Zustellung/Race mit CLI-Approve)
                if not store.is_approved(platform, gw_chat_id):
                    store._approve_user(platform, gw_chat_id,
                                        self._AUTO_APPROVE_USER_NAME)
                    logger.info(
                        "FlipsiBridge: Phone-Identität %s AUTOMATISCH "
                        "chat-freigegeben (W3-02 Auto-Approve — Bridge-Code-"
                        "Kopplung, FP-Abgleich bleibt Anzeige)",
                        gw_chat_id)
            return True
        except Exception:
            logger.warning(
                "FlipsiBridge: Auto-Approve für %s fehlgeschlagen — Gateway-"
                "Pairing greift unverändert", gw_chat_id, exc_info=True)
            return False

    def _stable_gateway_chat_id(self, relay_chat_id: str) -> str:
        """FIX-WELLE-2 B1: Relay-chat_id → stabile, identity-gebundene
        Gateway-chat_id ('flipsi-phone-<ik8>').

        Ohne aktive E2E-Session (Legacy-Klartext) gibt es keine Identity —
        dann bleibt die Relay-chat_id unverändert (Freigabe weiter je
        Session; E2E ist die Primärroute). Die Übersetzung ist eine 1:1-
        Zuordnung je Richtung: das LATEST Mapping gewinnt (Session-Wechsel
        zeigt dem Gateway dieselbe stabile ID, dem Wire die neue Relay-ID).
        """
        ik = self._active_phone_ik
        if not ik:
            return relay_chat_id
        stable = self._STABLE_CHAT_PREFIX + ik[:8]
        self._gw_chat_by_relay[relay_chat_id] = stable
        self._relay_chat_by_gw[stable] = relay_chat_id
        return stable

    def _relay_chat_id_for(self, chat_id: str) -> str:
        """FIX-WELLE-2 B1: Gateway-chat_id → Relay-chat_id für den Wire
        (Envelope + AES-GCM-AAD). Unbekannte IDs (schon Relay-IDs, z. B.
        interne Protokoll-Antworten wie der FP-Abgleich) laufen unverändert
        durch — Namensräume 'chat-*' vs. 'flipsi-phone-*' kollidieren nicht.
        """
        return self._relay_chat_by_gw.get(chat_id, chat_id)

    def _is_home_channel_notice(self, content: str) -> bool:
        """FIX-WELLE-2 B3: erkennt den Home-Kanal-Hinweis des Gateways
        (EN- und DE-Locale-Variante; verlässlicher Marker = der /sethome-
        Befehlshinweis, den beide Varianten tragen)."""
        if "/sethome" not in content:
            return False
        return any(marker in content for marker in self._HOME_NOTICE_MARKERS)

    async def send(self, chat_id: str, content: str, reply_to: Optional[str] = None,
                   metadata: Optional[dict] = None) -> SendResult:
        """Text über die Agent-WS als JSON an die Relay-/chat-Route (type=chat.reply).

        PH2-T11 (F2-S3): unmittelbar VOR der Antwort ein chat.activity
        {working: true, started_at}, danach {working: false} — der Phone-Header
        zeigt 'arbeitet …' live. started_at = Empfangszeit der Nachricht
        (Falls unbekannt: jetzt). elapsed_ms (F2: Empfang→Antwort-Send) wird
        im Envelope mitgegeben; ein Fehlschlag der Activity-Frames bricht die
        Antwort NIEMALS (Status ≠ Inhalt).
        """
        if not self._connected or self._ws is None:
            return SendResult(success=False, error="nicht verbunden", retryable=True)
        # FIX-WELLE-2 B1: Gateway ruft mit der stabilen Identity-ID — auf
        # dem Wire (Envelope, AES-GCM-AAD, _received_at) läuft die echte
        # Relay-chat_id. Bereits vorliegende Relay-IDs laufen unverändert.
        wire_chat_id = self._relay_chat_id_for(chat_id)
        # FIX-WELLE-2 B3: Home-Kanal-Hinweis des Gateways nur 1× je Agent
        # pro Zeitfenster zustellen — sonst flutet der Hinweis JEDE Turn
        # (HERZ-Befund 3). Unterdrückung meldet success=True: der Hinweis
        # ist informativ, kein Fehlerfall.
        is_home_notice = self._is_home_channel_notice(content)
        if is_home_notice and \
                (time.time() - self._home_notice_last_ts) < self._HOME_NOTICE_WINDOW_S:
            logger.info("FlipsiBridge: Home-Kanal-Hinweis im %d-h-Fenster bereits "
                        "zugestellt — unterdrückt (FIX-WELLE-2 B3)",
                        self._HOME_NOTICE_WINDOW_S // 3600)
            return SendResult(success=True)
        msg_id = uuid.uuid4().hex[:16]
        received_ms = self._received_at.get(wire_chat_id)
        started_at = received_ms if received_ms is not None else int(time.time() * 1000)
        await self._send_activity(wire_chat_id, working=True, started_at=started_at)
        # PH-3: aktive E2E-Session → chat.reply.enc (Text NUR im AES-GCM-ct).
        session = self._active_session()
        if session is not None:
            payload = {"text": content, "msg_id": msg_id}
            if reply_to:
                payload["reply_to"] = reply_to
            envelope = session.encrypt_frame(
                "chat.reply.enc", wire_chat_id, payload, clear={"msg_id": msg_id})
        else:
            envelope = {
                "type": "chat.reply", "v": _PROTOCOL_VERSION, "msg_id": msg_id,
                "route": self.chat_route,  # Relay routet den Payload an /chat
                "agent_id": self.agent_id, "chat_id": wire_chat_id,
                "reply_to": reply_to,
                # SEC-W1 Drill-Fund P2 (vc36): Der Relay liest Top-Level
                # 'text' (_handle_agent_chat_reply — Vertrag, den auch die
                # Relay-Tests festschreiben). 'body.text' wurde als 'chat.reply
                # ohne gültiges chat_id/text' verworfen => jede Legacy-
                # Klartext-Antwort ging stillos verloren.
                "text": content,
                "body": {"text": content}}  # Alt-Kompatibilität (Leser, die body nutzen)
        if received_ms is not None:
            envelope["elapsed_ms"] = max(0, int(time.time() * 1000) - received_ms)
        if metadata:
            envelope["metadata"] = metadata
        try:
            try:
                await self._send_json(envelope)
            except Exception as exc:
                logger.warning("FlipsiBridge: send fehlgeschlagen: %s", exc)
                return SendResult(success=False, error=str(exc), retryable=True, message_id=msg_id)
            # FIX-WELLE-2 B3: erst nach ERFOLGREICHEM Zustellen zählt der
            # Hinweis als gezeigt (Sendefehler → nächster Turn darf wieder).
            if is_home_notice:
                self._home_notice_last_ts = time.time()
            return SendResult(success=True, message_id=msg_id, raw_response=envelope)
        finally:
            # PH2-FIX (Jäger B5): working=false IMMER feuern — auch wenn der
            # Reply-Send scheitert oder dazwischen ein Fehler fliegt. Sonst
            # bleibt der Phone-Header-Spinner ('arbeitet …') dauerhaft stecken.
            await self._send_activity(wire_chat_id, working=False, started_at=started_at)

    async def send_typing(self, chat_id: str) -> None:
        """Tipp-Indikator (presence-Event, gedrosselt — kein Polling)."""
        if self._connected and self._ws is not None:
            # FIX-WELLE-2 B1: stabile Gateway-ID → Relay-Wire-ID.
            await self._send_json({"type": "typing", "v": _PROTOCOL_VERSION,
                                   "agent_id": self.agent_id,
                                   "chat_id": self._relay_chat_id_for(chat_id)})

    async def send_image(self, chat_id: str, image_url: str,
                         caption: Optional[str] = None) -> SendResult:
        """Bild per URL (Skeleton; Datei-Transfer = eigener P6-Chunk-Stream)."""
        if not self._connected or self._ws is None:
            return SendResult(success=False, error="nicht verbunden", retryable=True)
        msg_id = uuid.uuid4().hex[:16]
        # FIX-WELLE-2 B1: stabile Gateway-ID → Relay-Wire-ID.
        wire_chat_id = self._relay_chat_id_for(chat_id)
        await self._send_json({
            "type": "chat.reply", "v": _PROTOCOL_VERSION, "msg_id": msg_id,
            "route": self.chat_route, "agent_id": self.agent_id,
            "chat_id": wire_chat_id,
            "body": {"image": image_url, "caption": caption or ""}})
        return SendResult(success=True, message_id=msg_id)

    def get_chat_info(self, chat_id: str) -> dict:
        """Chat-Metadaten (Slim — der Adapter kennt nur seine Chat-/Geräte-IDs)."""
        return {"name": f"FlipsiBridge-Chat {chat_id}", "type": "dm", "chat_id": chat_id}

    # ------------------------------------------------------------- Empfangs-Pfad
    # SEC-W1 B F-02: Bounded Reconnect-Backoff — endete die Frame-Loop (Relay-
    # Restart ODER ein kranker Frame), blieb der Adapter PERMANENT offline
    # (bis Gateway-Restart). Exponentiell 1s→60s, Reset je erfolgreichem
    # Reconnect; bewusstes disconnect() bricht die Schleife sauber ab.
    _RECONNECT_BACKOFF_MIN = 1.0
    _RECONNECT_BACKOFF_MAX = 60.0

    async def _frame_loop(self) -> None:
        """SEC-W1 B F-02: Frame-Loop mit Per-Frame-Isolation + Reconnect.

        EIN Frame darf die Loop NIE beenden: je Frame try/except (Dispatch
        inkl. aller Crypto-Fehler, s. _dispatch_frame). Endet der WS-Lauf
        trotzdem (Relay-Restart, Netz), verbindet der Adapter mit
        exponentiellem Backoff neu — bounded, kein Endlos-Sturm.
        """
        backoff = self._RECONNECT_BACKOFF_MIN
        while self._connected:
            try:
                await self._read_ws_frames()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.warning("FlipsiBridge: Frame-Loop endete: %s", exc)
            if not self._connected:
                break
            logger.info("FlipsiBridge: WS verloren — Reconnect in %.1fs", backoff)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, self._RECONNECT_BACKOFF_MAX)
            try:
                self._ws = await self._open_ws(self._pin_relay_url())
                await self._send_json({
                    "type": "agent.register", "v": _PROTOCOL_VERSION,
                    "agent_id": self.agent_id, "display_name": self.display_name})
                backoff = self._RECONNECT_BACKOFF_MIN
                logger.info("FlipsiBridge: Reconnect erfolgreich (Backoff zurückgesetzt)")
            except Exception as exc:
                logger.warning("FlipsiBridge: Reconnect fehlgeschlagen: %s", exc)

    async def _read_ws_frames(self) -> None:
        """Ein WS-Lauf: Frames lesen bis zum Ende; jeder Frame isoliert."""
        while self._ws is not None:
            msg = await self._ws.receive()
            kind = str(getattr(getattr(msg, "type", None), "name",
                               getattr(msg, "type", ""))).upper()
            if kind in {"CLOSE", "CLOSED", "CLOSING", "CLOSED_OK"}:
                # Relay-Close = DER Reconnect-Fall (Relay-Restart schließt
                # sauber) — Status bleibt 'verbunden', der Supervisor baut
                # mit Backoff neu auf; bewusstes disconnect() cancelt den Task.
                logger.info("FlipsiBridge: Relay hat die WS geschlossen — Reconnect geplant")
                return
            data = getattr(msg, "data", msg)
            if isinstance(data, (bytes, bytearray)):
                data = data.decode("utf-8", errors="replace")
            if isinstance(data, str):
                try:
                    payload = json.loads(data)
                except ValueError:
                    logger.warning("FlipsiBridge: ungültiger WS-Frame (kein JSON) ignoriert")
                    continue
            else:
                payload = data
            if isinstance(payload, dict):
                # SEC-W1 B F-02: Per-Frame-Isolation — ein kranker Frame
                # (AttributeError auf string-body, UnicodeEncodeError aus
                # der Crypto-Kette, …) verwirft NUR diesen Frame; die Loop
                # und damit der Adapter bleiben am Leben.
                try:
                    self._dispatch_frame(payload)
                except Exception:
                    logger.warning(
                        "FlipsiBridge: Frame-Verarbeitung fehlgeschlagen — Frame verworfen",
                        exc_info=True)

    def _dispatch_frame(self, envelope: dict) -> None:
        """EIN Frame verarbeiten: Allowlist → Namespace → Nachricht (nie Tool-Call).

        PH2-T11 (F2-S2): nach bestandenen Guards wird SOFORT ein chat.read-
        Frame bestätigt (Status=read im Broker → chat.status-Frame ans Phone)
        und die Empfangszeit für elapsed_ms notiert.
        """
        etype = str(envelope.get("type", "")).strip()
        if etype not in _INBOUND_ENVELOPE_ALLOWLIST:
            # I3: Chat-Route darf KEINE Steuerung ins Agenten-Innere tragen.
            # SEC-W2 W2-3: Typ kürzen (Log + Diagnose) — %r eines voll
            # angreiferkontrollierten Typs schrieb bis 2 MB je Frame ins Log.
            etype_short = _log_id(etype, 64) or "<leer>"
            self.rejected_frames.append({"type": etype_short,
                                         "reason": "Envelope-Typ nicht erlaubt (kein Tool-Call-Pfad)"})
            logger.warning("FlipsiBridge: Frame-Type %r abgelehnt — Chat-Text ist nur Nachricht",
                           etype_short)
            return
        if etype in {"chat.ack", "roster", "presence", "pong"}:
            logger.debug("FlipsiBridge: %s-Frame empfangen", etype)
            return
        # chat.send/chat.deliver: Namespace-Prüfung (fremde agent_id = fremdes Gerät/Backend)
        target = str(envelope.get("agent_id", "")).strip()
        if target != self.agent_id:
            self.rejected_frames.append({"type": etype, "reason": "fremde agent_id (Namespace-Verstoß)"})
            logger.warning("FlipsiBridge: %s für fremde agent_id abgelehnt", etype)
            return

        # PH-3-T2: X3DH-vereinfachter Handshake-Frame (App identity/ephe/prekey
        # → Session-Key-Ableitung beidseitig; der Relay routet nur opak).
        # Bewusst VOR der chat_id-Pflicht: hello ist Session-Protokoll, kein Chat.
        if etype == "crypto.hello":
            self._handle_crypto_hello(envelope)
            return

        chat_id = str(envelope.get("chat_id", "")).strip()
        if not chat_id:
            self.rejected_frames.append({"type": etype, "reason": "chat_id fehlt"})
            return
        if self.allowed_devices and chat_id not in self.allowed_devices:
            self.rejected_frames.append({"type": etype, "reason": "chat_id nicht in Geräte-Whitelist"})
            logger.warning("FlipsiBridge: chat_id %r nicht erlaubt — abgelehnt", chat_id)
            return

        # PH-3-T2: X3DH-vereinfachter Handshake-Frame (App identity/ephe/prekey
        # → Session-Key-Ableitung beidseitig; der Relay routet nur opak).
        if etype == "crypto.hello":
            self._handle_crypto_hello(envelope)
            return

        # MEDIA-T1: chunked Media (E2E-only) — eigener Zweig VOR dem Text-enc-
        # Pfad (Media-Payload hat kein 'text'; ack + Reassembly innen).
        if etype == "chat.media.enc":
            self._handle_media_chunk(envelope, chat_id)
            return

        # PH-3-T3: chat.*.enc — entschlüsseln (Klartext NUR intern, nie geloggt).
        if etype in ("chat.send.enc", "chat.event.enc"):
            text = self._decrypt_inbound(envelope, etype)
            if text is None:
                return
            # Sirs Revision (iii): Fingerprint-Chat-Abgleich — reine Protokoll-
            # Nachricht, geht NICHT an den Agenten (Gateway) weiter.
            if self._handle_fingerprint_check(envelope, chat_id, text):
                return
        else:
            # Relay-Broker-Frame (chat.send) trägt den Text oben, Skeleton-Namespace
            # (chat.deliver) im body — beides lesen, keines ausführen (I3).
            # SEC-W1 B F-02: body kann ein STRING sein (nur bei kompromittier-
            # tem Relay erreichbar) — .get auf str warf AttributeError, der
            # die Frame-Loop riss; jetzt: Typ-Check, Frame wird verworfen.
            body = envelope.get("body")
            if not isinstance(body, dict):
                body = {}
            text = str(body.get("text") or envelope.get("text") or "")
        # PH2-T11: Empfangszeit für elapsed_ms notieren (begrenzt, FIFO-Cap).
        self._received_at[chat_id] = int(time.time() * 1000)
        while len(self._received_at) > 256:
            self._received_at.pop(next(iter(self._received_at)))
        # W4-B2: Adapter-gesteuerter Slash-Präfix-Parser — NACH Dekrypt und
        # Fp-Gatter, VOR der Gateway-Zustellung. True = lokal behandelt (kein
        # Gateway-Push; Read-Receipt + Antwort hier); /new und alle Nicht-
        # Befehle fallen durch (normale Deliver-Pipeline).
        if self._handle_slash_command(envelope, chat_id, text or ""):
            return
        # PH2-FIX (Jäger B4): chat.read erst NACH der Verarbeitung — vorher
        # wurde der Read schon beim bloßen Frame-Empfang bestätigt (Lüge:
        # 'gelesen', bevor der Text überhaupt angekommen war).
        self._deliver_inbound(chat_id, text, envelope)

    # ------------------------------------------------------ PH-3 E2E-Kern
    def _active_session(self) -> Optional[AgentCryptoSession]:
        """Aktive E2E-Session (24 h) oder None — abgelaufene Session wird
        verworfen (Legacy bis zum nächsten crypto.hello, Relay läuft synchron)."""
        sess = self._crypto_session
        if sess is None:
            return None
        if sess.expired():
            logger.info("FlipsiBridge: E2E-Session %s abgelaufen (24 h) — Legacy bis zum neuen Handshake",
                        sess.session_id[:8])
            self._crypto_session = None
            return None
        return sess

    def _schedule(self, coro) -> None:
        """Coroutine als Hintergrund-Task (kein Loop → verwerfen, nie crashen)."""
        try:
            task = asyncio.get_running_loop().create_task(coro)
        except RuntimeError:
            coro.close()
            logger.debug("FlipsiBridge: Hintergrund-Task ohne Loop verworfen")
            return
        self._bg_tasks.append(task)

    async def drain_bg_tasks(self) -> None:
        """Helper (Tests/CLI): auf geplante Hintergrund-Sends warten."""
        while self._bg_tasks:
            await self._bg_tasks.pop(0)

    def _handle_crypto_hello(self, envelope: dict) -> None:
        """PH-3-T2: Handshake — Session-Key-Ableitung, hello_ack senden.
        Neue Session ersetzt eine alte (Session-Rotate = frischer X3DH)."""
        if not (_HAS_CRYPTO and self._identity is not None):
            self.rejected_frames.append({"type": "crypto.hello",
                                        "reason": "Adapter ohne Crypto-Fähigkeit (Legacy)"})
            logger.warning("FlipsiBridge: crypto.hello ohne Crypto-Fähigkeit ignoriert (Legacy-Modus)")
            return
        try:
            session, ack = AgentCryptoSession.respond_to_hello(
                envelope, self._identity,
                psk=b"",  # Zusatz-Order 02.11.2026: PSK-Codepfad entfernt —
                # hart b"" = PH3-KDF-Live-Default (App-Seite hat nie einen
                # psk übergeben; jeder konfigurierte Wert erzeugte die
                # Bug-1-KDF-Asymmetrie). FP-Abgleich bleibt die Verifikation.
                rotate_every=self.ratchet_rotate)
        except CryptoFrameError as exc:
            self.rejected_frames.append({"type": "crypto.hello", "reason": str(exc)})
            logger.warning("FlipsiBridge: crypto.hello ungültig — keine Session (%s)", exc)
            return
        self._crypto_session = session
        self._session_reset_pending = False
        # FIX-WELLE-2 B1: Phone-Identity-Key dieser Session merken — Basis
        # für die STABILE Gateway-chat_id ('flipsi-phone-<ik8>') und für die
        # TOFU-Pin-Aktualisierung nach bestätigtem FP-Abgleich (B2).
        self._active_phone_ik = str(envelope.get("ik_pub") or "").strip().lower()
        # SEC-W1 W1A-5: TOFU-Bindung der Phone-Identity (ik_pub-Pin) — siehe
        # _check_phone_identity_pin. Warnung bei Wechsel (kein Blockade-
        # Design: echtes Neu-Gerät vs. Übernahme klärt der FP-Abgleich).
        self._check_phone_identity_pin(envelope)
        logger.info("FlipsiBridge: E2E-Session %s etabliert — Fingerprint %s",
                    session.session_id[:8], fingerprint_display(session.fingerprint))
        self._schedule(self._send_ack(ack, session))

    async def _send_ack(self, ack: dict, session: AgentCryptoSession) -> None:
        """hello_ack senden; scheitert der Send, wird die frische Session
        wieder verworfen (kein Zustand ohne bestätigte Verbindung)."""
        try:
            await self._send_json(ack)
        except Exception as exc:
            logger.warning("FlipsiBridge: hello_ack-Send fehlgeschlagen: %s", exc)
            if self._crypto_session is session:
                self._crypto_session = None

    def _read_phone_pin(self) -> Optional[dict]:
        """FIX-WELLE-2 B2: Phone-Pin-Datei lesen (dict) — None = unlesbar.

        FileNotFoundError = {} (erste Sichtung); OSError/ValueError = None
        (Warnung + Prüfung überspringen, wie bisher)."""
        try:
            with open(self.phone_pin_file, "r", encoding="utf-8") as fh:
                loaded = json.load(fh)
            if isinstance(loaded, dict):
                return loaded
            return {}
        except FileNotFoundError:
            return {}
        except (OSError, ValueError):
            logger.warning(
                "FlipsiBridge: Phone-Pin-Datei unlesbar (%s) — TOFU-Prüfung übersprungen",
                os.path.basename(self.phone_pin_file))
            return None

    def _check_phone_identity_pin(self, hello: dict) -> None:
        """SEC-W1 W1A-5: TOFU-Bindung der Phone-Identity.

        relay-keys.json (vom Relay gepublishte Phone-Device-Keys) hat am
        Agenten NULL Consumer — `respond_to_hello` validierte nur die Hex-
        Form, jeder Paarungs-Code-Inhaber etablierte sich als 'das Phone'
        (Session-Verdrängung, FP-ABGLEICH verglich dann nur Angreifer-vs-
        Agent). Fix: Der ERSTE Handshake pint ik_pub des Phones lokal
        (0600, neben dem Identity-File); ein WECHSEL ist ein lautes
        Warn-Ereignis (Neu-Gerät ODER Übernahme — klärt der FP-Abgleich).
        Bewusst Warnung statt Blockade (echtes Gerät-Wechseln bleibt
        möglich); Datei unlesbar = Warnung + Prüfung übersprungen.

        FIX-WELLE-2 B2 (P3, HERZ-Befund 2): Der Pin erneuerte sich NIE —
        JEDE Reconnect-Wiederholung desselben Identity-Wechsels warnte
        erneut (Warn-Spam, Alarm-Fatigue: eine echte Übernahme ging in der
        Flut unter). Jetzt: (a) je NEUEM ik_pub genau EINE Warnung
        (_pin_warned_ik-Dedupe je Adapter-Lebenszeit), (b) nach BESTÄTIGTEM
        FP-Abgleich pint der Adapter die neue Identity persistent um
        (_confirm_phone_pin) — danach ist der neue Key der gepinnte
        Normalzustand, keine weitere Warnung."""
        ik_pub = str(hello.get("ik_pub") or "").strip().lower()
        if not ik_pub:
            return
        pin = self._read_phone_pin()
        if pin is None:
            return
        pinned = pin.get("ik_pub")
        if not isinstance(pinned, str) or not pinned:
            try:
                self._write_phone_pin(ik_pub)
                logger.info("FlipsiBridge: Phone-Identity gepinnt (TOFU, erste Sichtung)")
            except OSError as exc:
                logger.warning("FlipsiBridge: Phone-Pin konnte nicht gespeichert werden: %s", exc)
            return
        if pinned == ik_pub:
            return
        if ik_pub == self._pin_warned_ik:
            # B2: für DIESEN Key wurde bereits gewarnt — Wiederholungs-/
            # Reconnect-Spam unterdrücken, bis ein ANDERER Key auftaucht.
            logger.info("FlipsiBridge: PHONE-IDENTITY-WECHSEL (gepinnt %s… → neu %s…) "
                        "bereits gemeldet — Wiederholungswarnung unterdrückt (B2)",
                        pinned[:8], ik_pub[:8])
            return
        self._pin_warned_ik = ik_pub
        logger.warning(
            "FlipsiBridge: PHONE-IDENTITY-WECHSEL erkannt (gepinnt %s… → neu %s…) — "
            "neues Gerät ODER Übernahme-Versuch (weiterer Paarungs-Code-Inhaber). "
            "Fingerprint-Abgleich am NEUEN Phone prüfen!",
            pinned[:8], ik_pub[:8])

    def _confirm_phone_pin(self) -> None:
        """FIX-WELLE-2 B2: FP-Abgleich BESTANDEN = die neue Phone-Identity
        ist vom User bestätigt → Pin persistent auf den Session-Key des
        Phones umstellen. Nur wenn der aktuelle Pin wirklich abweicht; ein
        Schreibfehler bricht nichts (nächster Handshake warnt erneut)."""
        ik_pub = self._active_phone_ik
        if not ik_pub:
            return
        pin = self._read_phone_pin()
        if pin is None:
            return
        pinned = pin.get("ik_pub")
        if isinstance(pinned, str) and pinned == ik_pub:
            return
        try:
            self._write_phone_pin(ik_pub)
            self._pin_warned_ik = ""
            logger.info("FlipsiBridge: Phone-Identity nach BESTÄTIGTEM Fingerprint-"
                        "Abgleich übernommen (Pin aktualisiert: %s…) — künftige "
                        "Handshakes dieses Geräts warnen nicht mehr (B2)", ik_pub[:8])
        except OSError as exc:
            logger.warning("FlipsiBridge: Phone-Pin-Aktualisierung fehlgeschlagen: %s", exc)

    def _write_phone_pin(self, ik_pub: str) -> None:
        """Phone-ik_pub-Pin schreiben — 0600, ATOMAR (SEC-W2 W2-3).

        SEC-W2 W2-3: das alte O_TRUNC auf der Bestandsdatei konnte den
        Phone-Pin bei Absturz MITTLERES Schreibens leeren/korruptieren
        (Parität zu W1 D3a für relay-keys.json) — Folge: TOFU-Pin weg →
        nächster Handshake pint den Angreifer-Key als 'erstes Gerät'.
        Jetzt: Temp-Datei (mkstemp = 0600) + atomares os.replace; der
        Alt-Bestand bleibt bei JEDEM Fehler unangetastet."""
        payload = json.dumps(
            {"v": 1, "ik_pub": ik_pub, "pinned": time.strftime("%Y-%m-%dT%H:%M:%S%z")},
            indent=2) + "\n"
        directory = os.path.dirname(os.path.abspath(self.phone_pin_file)) or "."
        os.makedirs(directory, exist_ok=True)
        import tempfile as _tempfile
        fd, tmp_path = _tempfile.mkstemp(prefix=".phone-pin-", dir=directory)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                try:
                    os.fchmod(fh.fileno(), 0o600)
                except AttributeError:
                    pass  # mkstemp legt die Datei ohnehin 0600 an
                fh.write(payload)
            os.replace(tmp_path, self.phone_pin_file)
        except BaseException:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
            raise

    def _decrypt_inbound(self, envelope: dict, etype: str) -> Optional[str]:
        """chat.*.enc entschlüsseln → Text (None = verworfen, Grund geloggt
        OHNE Klartext). Cross-Session-Replay wird per session_id geblockt."""
        sess = self._active_session()
        if sess is None:
            # SIRS-01 (P0, Volltest 03.11.2026): Agent-Restart (Crash/Update/
            # Wartung) verliert die In-Memory-Session, das Phone sendet weiter
            # chat.send.enc auf der ALTEN Session. Bisher: STILL verworfen —
            # das Phone zeigte '✓✓ Zugestellt' + E2E-Badge weiter, der Chat
            # war tot. Fix (PoC SIRS-01): statt still verwerfen → SOFORT
            # crypto.session_reset für die fremde session_id ans Relay (der
            # Relay entfernt die Session aus seiner Registry + informiert das
            # Phone) → das Phone stößt einen Neu-Handshake an. Dedup über
            # _session_reset_pending (das nächste crypto.hello hebt es auf).
            frame_session = str(envelope.get("session_id") or "").strip()
            if frame_session:
                self.rejected_frames.append({
                    "type": etype,
                    "reason": "session dem Agenten nicht bekannt (Agent-Neustart) — "
                              "crypto.session_reset + Neu-Handshake angestoßen"})
                logger.warning(
                    "FlipsiBridge: %s mit unbekannter session_id %s — Session dem Agenten "
                    "nicht bekannt (Agent-Neustart?); crypto.session_reset ans Relay, "
                    "Neu-Handshake angestoßen", etype, _log_id(frame_session, 16))
                if not self._session_reset_pending:
                    self._session_reset_pending = True
                    self._schedule(self._send_session_reset(
                        frame_session, "agent-session-lost"))
                return None
            self.rejected_frames.append({"type": etype,
                                        "reason": "enc-Frame ohne aktive E2E-Session"})
            logger.warning("FlipsiBridge: %s ohne aktive E2E-Session verworfen", etype)
            return None
        if str(envelope.get("session_id") or "") != sess.session_id:
            # FIX-WELLE-2 B8 (P3, HERZ-Befund 8): fremde session_id bei
            # AKTIVER Session war ein STILLER Verwurf — Realtest-Szenario:
            # eine zweite Pairing-Code-Inhaber-Session (Probe/zweites Gerät)
            # hatte die Phone-Session am Adapter verdrängt; das echte Phone
            # sendete weiter auf SEINER Session, der Adapter warf die Frames
            # stumm weg (Chip blieb '✓✓ Zugestellt', Chat tot, kein Lebens-
            # zeichen). Jetzt wirkt der fremde Frame als TAKEOVER-SONDE:
            # crypto.session_reset für die FRAME-Session ans Relay (der Relay
            # entfernt sie aus der Registry und informiert das Phone → Neu-
            # Handshake) — derselbe Heilungspfad wie SIRS-01, Dedup über
            # _session_reset_pending (nächstes crypto.hello hebt es auf).
            # Replay-Altlasten (Session dem Relay schon unbekannt): Reset
            # wird dort ignoriert, die AKTIVE Session bleibt unberührt
            # (kein angreifbarer DoS-Hebel auf die legitime Session).
            frame_session = str(envelope.get("session_id") or "").strip()
            self.rejected_frames.append({
                "type": etype,
                "reason": "fremde session_id (Cross-Session-Replay/-Takeover) — "
                          "crypto.session_reset + Neu-Handshake angestoßen"})
            logger.warning(
                "FlipsiBridge: %s mit fremder session_id %s verworfen — Cross-Session-"
                "Takeover-/Replay-Sonde; crypto.session_reset ans Relay, Neu-Handshake "
                "angestoßen (FIX-WELLE-2 B8)", etype, _log_id(frame_session, 16))
            if frame_session and not self._session_reset_pending:
                self._session_reset_pending = True
                self._schedule(self._send_session_reset(
                    frame_session, "cross-session-takeover"))
            return None
        try:
            payload = sess.decrypt_frame(envelope)
        except CryptoFrameError as exc:
            self.rejected_frames.append({"type": etype, "reason": str(exc)})
            logger.warning("FlipsiBridge: %s-Entschlüsselung fehlgeschlagen (%s) — Frame verworfen",
                           etype, exc)
            return None
        return str(payload.get("text") or "")

    def _handle_fingerprint_check(self, envelope: dict, chat_id: str, text: str) -> bool:
        """Sirs Revision (iii): 'FP-ABGLEICH: <64 Hex>' → Vergleich gegen den
        EIGENEN Session-Fingerprint + automatische chat.reply-Antwort.
        True = als Protokoll-Nachricht behandelt (kein Gateway-Push)."""
        if not text.startswith(_FP_PREFIX):
            return False
        sess = self._active_session()
        if sess is None:
            logger.info("FlipsiBridge: FP-ABGLEICH ohne aktive E2E-Session — als normale Nachricht behandelt")
            return False
        claimed = text[len(_FP_PREFIX):].strip().replace(" ", "").lower()
        matches = (re.fullmatch(r"[0-9a-f]{64}", claimed) is not None
                   and hmac.compare_digest(claimed, sess.fingerprint))
        # SIRS-03 (Volltest 03.11.2026): Der FP-Abgleich wurde GELESEN und
        # automatisch BEANTWORTET — trotzdem ging nie ein chat.read für ihn
        # raus, der Phone-Chip blieb bei '✓✓ Zugestellt', obwohl die Antwort
        # bereits existierte. Der Abgleich ist eine echte Lese-Verarbeitung →
        # Read-Receipt wie bei jeder zugestellten Nachricht (Chip → 'Gelesen').
        self._send_read_receipt(chat_id, str(envelope.get("msg_id", "") or ""))
        self._schedule(self._fp_reply(chat_id, matches, sess))
        return True

    # ── W4-B2: Adapter-gesteuerte Slash-Befehle (/new /status /help) ───────────

    def _handle_slash_command(self, envelope: dict, chat_id: str, text: str) -> bool:
        """Chat-Präfix-Parser im Nach-Dekrypt-Verband (W4-B2).

        True = lokal behandelt — KEIN Gateway-Push, KEINE Zustellung:
          /status → Status-Nachricht aus Adapter-internen Quellen
                    (eigenes Antwort-Verband: Read-Receipt + chat.reply.enc
                    bei aktiver Session via send()).
          /help   → Befehlsliste (_HELP_TEXT, eigenes Antwort-Verband).
        False = nichts Befehls-artiges erkannt → normale Nachricht weiterlaufen:
          - /new wird 1:1 durchgelassen — der Gateway ist Session-Eigentümer
            (reset_triggers [" /new", "/reset"] in gateway/config.py; das
            destructive-Confirm-Gate des Gateways ist DIE Bestätigung, die
            Gateway-Antwort ist die Bestätigungs-Bubble der App — DECISION O-5,
            keine Adapter-Doppel-Bubble). KEIN crypto.session_reset: der
            Ratchet-Status bleibt (kein E2E-Side-Effect eines Chat-Befehls,
            Abgrenzung SIRS-01/B8).
          - JEDE andere '/…'-Eingabe (unbekannter Befehl, '/etc/hosts prüfen',
            'foo /new bar' etc.) ist keine Befehlsfläche → normal senden
            (false-positive-Verband; nur exakter Start-Match auf token0).
        """
        stripped = text.strip()
        if not stripped.startswith("/"):
            return False
        token0 = stripped.split()[0]
        cmd = token0[1:]
        if cmd not in _SLASH_COMMANDS:
            # Unbekanntes '/…' = normale Nachricht (false-positive-Verband).
            return False
        msg_id = str(envelope.get("msg_id", "") or "")
        if cmd == "new":
            # Session-Reset-Verband des Gateways entscheidet (inkl. Bestätigung);
            # der Adapter quittiert nichts lokal und lässt das Roh-Textevent
            # unverändert durch (Args-Grammatik = Gateway-Gate).
            logger.info(
                "FlipsiBridge: /new empfangen — 1:1 an den Gateway durchgereicht "
                "(Session-Reset mit Bestätigungs-Gate; KEIN crypto.session_reset, "
                "Ratchet-Status bleibt)")
            return False
        # /status & /help: JEDE Command-Verarbeitung bestätigt zuerst
        # (SIRS-03-Parität, Chip → blau); Args ohne Grammatik = Kurzhinweis.
        suffix = ""
        if len(stripped.split()) > 1:
            suffix = "\nHinweis: Argumente nach /%s werden ignoriert." % cmd
        self._send_read_receipt(chat_id, msg_id)
        reply = self._slash_status_report() if cmd == "status" else _HELP_TEXT
        self._schedule(self._slash_reply(chat_id, reply + suffix))
        return True

    def _slash_status_report(self) -> str:
        """W4-B2: /status — kompakte Status-Nachricht NUR aus Adapter-Quellen.

        Ehrliche Anzeige: fehlende Quelle wird verbal markiert ('keine …',
        'nicht verbunden'), kein Fake. Bewusst NICHT darin: der Phone-
        Chatverlauf (ChatHistoryStore = App-Eigentum, kein Adapter-Zugriff)
        und der Agenten-Roster (der Adapter kennt nur sich selbst).
        """
        lines: list[str] = []
        if self._connected:
            conn = "verbunden"
        else:
            conn = "NICHT verbunden"
        lines.append("Verbindung: %s — %s (%s)" % (
            conn, self.relay_url or "(keine Relay-URL konfiguriert)", self.ws_route))
        session = self._active_session()
        if session is not None:
            rest_h = max(
                0, int(_SESSION_MAX_AGE_SECONDS - (time.time() - session.created))
            ) // 3600
            lines.append("E2E: Session %s — Fingerprint %s — Restlaufzeit ~%d h (24-h-Cap)"
                         % (session.session_id[:8],
                            fingerprint_display(session.fingerprint), rest_h))
        else:
            lines.append("E2E: keine aktive E2E-Session")
        lines.append("Agent: %s (%s)" % (self.agent_id, self.display_name))
        if self._received_at:
            newest_ms = max(self._received_at.values())
            lines.append("Letzte Aktivität: %s" % time.strftime(
                "%Y-%m-%d %H:%M:%S", time.localtime(newest_ms / 1000)))
        else:
            lines.append("Letzte Aktivität: keine (noch nichts empfangen)")
        try:
            entries = [name for name in os.listdir(self.media_dir)
                       if os.path.isfile(os.path.join(self.media_dir, name))]
            used = sum(os.path.getsize(os.path.join(self.media_dir, name))
                       for name in entries)
            lines.append("Medien: %d Dateien (%s) von %s Budget" % (
                len(entries), _format_media_bytes(used),
                _format_media_bytes(self.media_dir_max_bytes)))
        except OSError:
            lines.append("Medien: Verzeichnis nicht lesbar")
        if self.rejected_frames:
            counts: dict[str, int] = {}
            for rejected in self.rejected_frames:
                reason = str(rejected.get("reason") or "?")
                counts[reason] = counts.get(reason, 0) + 1
            top = sorted(counts.items(), key=lambda item: item[1], reverse=True)[:3]
            lines.append("Verworfene Frames: " + "; ".join(
                "%s ×%d" % (reason, count) for reason, count in top))
        else:
            lines.append("Verworfene Frames: keine")
        return "\n".join(lines)

    async def _slash_reply(self, chat_id: str, text: str) -> None:
        """Antwort-Send eines Slash-Befehls (läuft über die bestehende E2E-
        Kette: chat.reply.enc bei aktiver Session); ein Sendefehler bricht
        nie den Frame-Loop (_fp_reply-Muster)."""
        try:
            await self.send(chat_id, text)
        except Exception as exc:
            logger.warning("FlipsiBridge: Slash-Antwort fehlgeschlagen: %s", exc)

    async def _send_session_reset(self, session_id: str, reason: str) -> None:
        """PH-3/SIRS-01: crypto.session_reset ans Relay — die Gegenseite
        verwirft ihre SessionKeys, der Relay entfernt die Session aus der
        Registry und das Phone stößt einen Neu-Handshake an. Ein Sendefehler
        bricht den Adapter NIE (nur Warnung — der Frame-Typ steht in der
        Relay-Allowlist, das nächste hello heilt den Zustand ohnehin)."""
        try:
            await self._send_json({"type": "crypto.session_reset",
                                   "v": _PROTOCOL_VERSION,
                                   "session_id": session_id,
                                   "agent_id": self.agent_id,
                                   "reason": reason})
        except Exception as exc:
            logger.warning("FlipsiBridge: crypto.session_reset-Send fehlgeschlagen: %s", exc)

    async def _fp_reply(self, chat_id: str, matches: bool, session) -> None:
        """Auto-Antwort auf den Fingerprint-Abgleich; bei ABWEICHUNG danach
        Session invalidieren (Warn + Reset-Flag + crypto.session_reset).

        SEC-W1 W1C-6: Identity-Guard wie _send_ack — der await-Slice beim
        Senden kann einen NEUEN crypto.hello durchlassen (Session-Rotate
        nach Mismatch); das bedingungslose `self._crypto_session = None`
        killte die FRISCHE, gültige Session still (enc-Frames fielen bis
        zum nächsten hello). Nur invalidieren, wenn NOCH dieselbe aktiv."""
        reply = _FP_OK_TEXT if matches else _FP_FAIL_TEXT
        try:
            # Antwort geht VOR der Invalidierung raus (noch über die aktive
            # Session verschlüsselt — die Gegenseite muss die Abweichung sehen).
            await self.send(chat_id, reply)
        except Exception as exc:
            logger.warning("FlipsiBridge: Fingerprint-Antwort fehlgeschlagen: %s", exc)
        if matches:
            # FIX-WELLE-2 B2: BESTANDENER FP-Abgleich = Bestätigung der
            # Phone-Identity → TOFU-Pin persistent auf diesen Key umstellen
            # (endet den Warn-Spam bei jedem Reconnect, HERZ-Befund 2).
            self._confirm_phone_pin()
            return
        session_id = session.session_id
        logger.warning("FlipsiBridge: Fingerprint-ABWEICHUNG in Session %s — Session invalidiert "
                       "(möglicher Man-in-the-Middle)", session_id[:8])
        if self._crypto_session is session:
            self._crypto_session = None
            self._session_reset_pending = True
        await self._send_session_reset(session_id, "fingerprint-mismatch")

    def _send_read_receipt(self, chat_id: str, msg_id: str) -> None:
        """PH2-T11 (F2-S2): chat.read-Frame feuern (fire-and-forget, nie brechen).

        PH-3: aktive E2E-Session → chat.read.enc (msg_id bleibt klar für die
        PH2-Status-Logik des Relays, ct = verschlüsselter Spiegel).
        """
        session = self._active_session()
        if session is not None:
            envelope = session.encrypt_frame(
                "chat.read.enc", chat_id, {"msg_id": msg_id}, clear={"msg_id": msg_id})
        else:
            envelope = {
                "type": "chat.read", "v": _PROTOCOL_VERSION,
                "agent_id": self.agent_id, "chat_id": chat_id, "msg_id": msg_id,
            }

        async def _send() -> None:
            try:
                await self._send_json(envelope)
            except Exception as exc:
                logger.warning("FlipsiBridge: chat.read-Frame fehlgeschlagen: %s", exc)

        try:
            asyncio.get_running_loop().create_task(_send())
        except RuntimeError:
            logger.debug("FlipsiBridge: chat.read ohne Loop verworfen")

    def _deliver_inbound(self, chat_id: str, text: str, envelope: dict) -> None:
        """Text ALS NACHRICHT in die Gateway-Messages-Pipeline schieben.

        PH2-FIX (Jäger B4): die chat.read-Bestätigung feuert erst DANACH —
        handle_message muss den Text wirklich verarbeitet haben, bevor der
        Status 'read' beim Relay/Phone ankommt (vorher: read beim bloßen
        Frame-Empfang = gelogen). Im Hermes-Pfad kettet die Bestätigung an
        den handle_message-Task (nach dessen Await); im Skeleton-Pfad ist
        der Callback synchron → Bestätigung direkt im Anschluss.
        """
        if not text:
            return
        # FIX-WELLE-2 B1: Dem Gateway die STABILE, identity-gebundene chat_id
        # zeigen ('flipsi-phone-<ik8>') — die Pairing-Freigabe überlebt damit
        # E2E-Session-Wechsel (App-/Relay-Restart, Neu-Handshake). Read-
        # Receipts und der Wire laufen weiter über die echte Relay-chat_id.
        gw_chat_id = self._stable_gateway_chat_id(chat_id)
        # W3-02 Auto-Approve: Bridge-Code-gepaarte Phone-Identity ist je
        # ALLE Agenten automatisch chat-freigegeben — VOR der Gateway-
        # Zustellung freigeben, damit KEIN pending-request-Frame entsteht
        # (0 Code-DMs, 0 'Unauthorized'-Hinhalten). Legacy-IDs (kein
        # Identity-Präfix) laufen unverändert durch die Gateway-Pairing-
        # Schiene.
        self._ensure_phone_auto_approved(gw_chat_id)
        if _UNDER_HERMES:
            try:  # Echter Gateway-Pfad: MessageEvent → handle_message (Session je chat_id)
                source = self.build_source(chat_id=gw_chat_id,
                                           chat_name=self.get_chat_info(gw_chat_id)["name"],
                                           chat_type="dm",
                                           user_id=str(envelope.get("device_id", gw_chat_id)),
                                           user_name=str(envelope.get("sender", "FlipsiBridge Phone")),
                                           message_id=str(envelope.get("msg_id", "")))
                from gateway.platforms.event import MessageEvent, MessageType  # noqa: F401
                event = MessageEvent(source=source, text=text,
                                     message_type=MessageType.TEXT)  # type: ignore[name-defined]
                msg_id = str(envelope.get("msg_id", "") or "")
                asyncio.get_running_loop().create_task(
                    self._handle_and_ack(event, chat_id, msg_id))
                return
            except Exception:
                logger.warning("FlipsiBridge: Gateway-Push fehlgeschlagen — Text verworfen",
                               exc_info=True)
                return
        # Skeleton/Test-Pfad: Callback statt Gateway (kein Hermes installiert).
        # FIX-WELLE-2 B1: auch hier die STABILE Identity-Sicht (der Skeleton
        # simuliert das Gateway) — read receipt unten bleibt auf der Relay-ID.
        if self.message_callback is not None:
            self.message_callback({
                "chat_id": gw_chat_id, "text": text,
                "envelope_type": str(envelope.get("type", "") or "chat.deliver"),
            })
        # PH2-FIX (Jäger B4): Text synchron verarbeitet → JETZT erst read.
        self._send_read_receipt(chat_id, str(envelope.get("msg_id", "") or ""))

    async def _handle_and_ack(self, event: Any, chat_id: str, msg_id: str) -> None:
        """PH2-FIX (Jäger B4): handle_message awaiten, DANACH erst chat.read.

        Schlägt die Verarbeitung fehl, wird KEIN read bestätigt (der Text
        wurde nicht wirklich verarbeitet — kein Status-Betrug).
        """
        try:
            await self.handle_message(event)
        except Exception:
            logger.warning("FlipsiBridge: handle_message fehlgeschlagen — kein chat.read",
                           exc_info=True)
            return
        self._send_read_receipt(chat_id, msg_id)

    async def _send_activity(self, chat_id: str, working: bool, started_at: int) -> None:
        """PH2-T11 (F2-S3): chat.activity-Frame senden — Fehler brechen nie die Antwort.

        PH-3: aktive E2E-Session → chat.activity.enc (working/started_at bleiben
        klar für die Relay-Dedup-Logik, ct = verschlüsselter Spiegel).
        """
        session = self._active_session()
        if session is not None:
            envelope = session.encrypt_frame(
                "chat.activity.enc", chat_id,
                {"working": working, "started_at": started_at},
                clear={"working": working, "started_at": started_at})
        else:
            envelope = {
                "type": "chat.activity", "v": _PROTOCOL_VERSION,
                "agent_id": self.agent_id, "chat_id": chat_id,
                "working": working, "started_at": started_at,
            }
        try:
            await self._send_json(envelope)
        except Exception as exc:
            logger.warning("FlipsiBridge: chat.activity-Frame fehlgeschlagen: %s", exc)

    # ───────────────────────────────── MEDIA-T1: Medien im Chat (E2E-only)

    def _handle_media_chunk(self, envelope: dict, chat_id: str) -> None:
        """MEDIA-T1: EIN verschlüsselter Media-Chunk (chat.media.enc).

        Kette: entschlüsseln (Ratchet) → chunk_ack an das Phone (Progress,
        klar + light) → Reassembly je media_id → bei Vollständigkeit: Datei
        ins Media-Dir schreiben, sha256 prüfen (fail-closed) und als Gateway-
        NACHRICHT liefern (Pfad + Art + Größe — Text bleibt der Chat-Weg).
        """
        sess = self._active_session()
        if sess is None or str(envelope.get("session_id") or "") != sess.session_id:
            self.rejected_frames.append({
                "type": "chat.media.enc",
                "reason": "enc-Frame ohne passende E2E-Session"})
            logger.warning(
                "FlipsiBridge: chat.media.enc ohne passende E2E-Session verworfen")
            return
        media_id = str(envelope.get("media_id") or "").strip()
        seq_raw = envelope.get("seq")
        total_raw = envelope.get("total")
        if (not media_id or not isinstance(seq_raw, int) or isinstance(seq_raw, bool)
                or not isinstance(total_raw, int) or isinstance(total_raw, bool)):
            self.rejected_frames.append({
                "type": "chat.media.enc", "reason": "Metadaten ungültig"})
            logger.warning("FlipsiBridge: chat.media.enc Metadaten ungültig — verworfen")
            return
        try:
            payload = sess.decrypt_frame(envelope)
        except CryptoFrameError as exc:
            self.rejected_frames.append({"type": "chat.media.enc",
                                         "reason": str(exc)})
            logger.warning("FlipsiBridge: chat.media.enc-Entschlüsselung "
                           "fehlgeschlagen (%s) — Frame verworfen", exc)
            self._schedule(self._send_media_ack(chat_id, media_id, seq_raw,
                                                ok=False, error="decrypt failed"))
            return
        try:
            with self._media_lock:
                blob, meta = self._media_assemble(media_id, seq_raw, total_raw,
                                                   payload, envelope)
        except MediaError as exc:
            self.rejected_frames.append({"type": "chat.media.enc",
                                         "reason": str(exc)})
            logger.warning("FlipsiBridge: Media-Assembly fehlgeschlagen (%s) "
                           "— Upload verworfen", exc)
            self._schedule(self._send_media_ack(chat_id, media_id, seq_raw,
                                                ok=False, error="assembly failed"))
            return
        # JE Chunk bestätigen (Progress) — NACH erfolgreichem Entschlüsseln,
        # nie blind beim Empfang (kein Status-Betrug).
        self._schedule(self._send_media_ack(chat_id, media_id, seq_raw, ok=True))
        if blob is None:
            return
        # Vollständig → Datei schreiben + als Nachricht in die Gateway-Pipeline.
        kind = str(meta.get("kind") or "file")
        name = str(meta.get("name") or media_id)
        size = len(blob)
        try:
            path = self._media_save(media_id, name, blob)
        except OSError as exc:
            logger.warning("FlipsiBridge: Media-Datei konnte nicht gespeichert "
                           "werden: %s", exc)
            return
        label = _media_kind_label(kind)
        text = (f"[{label}] {name} ({_format_media_bytes(size)}) — "
                f"gespeichert unter: {path}")
        # PH2-FIX (Jäger B4)-Parität: verarbeitet → read; media hat keine
        # Broker-msg_id, der chunk_ack übernimmt die Empfangs-Bestätigung.
        self._deliver_inbound(chat_id, text, {
            "type": "chat.media.enc", "media_id": media_id, "kind": kind,
            "name": name, "size": size, "path": path})

    def _media_assemble(self, media_id: str, seq: int, total: int,
                        payload: dict, envelope: dict) -> tuple[Optional[bytes], dict]:
        """Chunk einsortieren (Aufrufer hält _media_lock). (None, meta) = noch
        unvollständig, (bytes, meta) = fertig. Halbfertige Assemblies: TTL +
        Cap-Verdrängung."""
        now = time.time()
        # TTL/Cap-Sweep: abgelaufene und älteste Assemblies entsorgen.
        stale = [mid for mid, entry in self._media_pending.items()
                 if now - entry["ts"] > _MEDIA_ASSEMBLER_TTL_S]
        for mid in stale:
            del self._media_pending[mid]
        while len(self._media_pending) >= _MEDIA_ASSEMBLERS_MAX:
            oldest = min(self._media_pending, key=lambda m: self._media_pending[m]["ts"])
            if oldest == media_id:
                break
            del self._media_pending[oldest]
        entry = self._media_pending.get(media_id)
        if entry is None:
            entry = {"assembler": MediaAssembler(total),
                     "kind": envelope.get("kind"), "name": envelope.get("name"),
                     "mime": envelope.get("mime"), "size": envelope.get("size"),
                     "ts": now}
            self._media_pending[media_id] = entry
        else:
            entry["ts"] = now
        assembler: MediaAssembler = entry["assembler"]
        if assembler.total != total:
            raise MediaError("total weicht vom Chunk-0-Header ab")
        meta = {"kind": entry.get("kind"), "name": entry.get("name"),
                "mime": entry.get("mime")}
        blob = assembler.add(seq, payload)
        if blob is not None:
            del self._media_pending[media_id]
        return blob, meta

    def _media_save(self, media_id: str, name: str, blob: bytes) -> str:
        """Fertige Datei ins Media-Dir schreiben (0700-Dir, atomar via
        rename) + Retention: Budget je Dir, älteste Dateien zuerst raus."""
        safe = re.sub(r"[^A-Za-z0-9._-]", "_", str(name).strip())[:64] or "media"
        stem, dot, ext = safe.rpartition(".")
        if not dot:
            stem, ext = safe, ""
        stem = stem[:48].rstrip("._-") or "media"
        filename = f"{re.sub(r'[^A-Za-z0-9_-]', '_', media_id)[:32]}-{stem}" \
                   + (f".{ext}" if ext else "")
        directory = os.path.abspath(self.media_dir)
        os.makedirs(directory, mode=0o700, exist_ok=True)
        target = os.path.join(directory, filename)
        import tempfile as _tempfile
        fd, tmp_path = _tempfile.mkstemp(prefix=".media-", dir=directory)
        try:
            with os.fdopen(fd, "wb") as fh:
                fh.write(blob)
            os.replace(tmp_path, target)
        except BaseException:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
            raise
        self._media_prune_dir(directory)
        return target

    def _media_prune_dir(self, directory: str) -> None:
        """Retention: liegt das Dir über dem Byte-Budget, fliegen die
        ÄLTESTEN Dateien zuerst raus (nie die gerade gesicherte)."""
        try:
            entries = []
            total = 0
            for fn in os.listdir(directory):
                p = os.path.join(directory, fn)
                if os.path.isfile(p):
                    stat = os.stat(p)
                    entries.append((stat.st_mtime, stat.st_size, p))
                    total += stat.st_size
            if total <= self.media_dir_max_bytes:
                return
            entries.sort()
            for mtime, size, p in entries:
                if total <= self.media_dir_max_bytes:
                    break
                try:
                    os.unlink(p)
                    total -= size
                    logger.info("FlipsiBridge: Media-Retention hat %s entfernt",
                                os.path.basename(p))
                except OSError:
                    pass
        except OSError:
            pass

    async def _send_media_ack(self, chat_id: str, media_id: str, seq: int,
                             ok: bool, error: str = "") -> None:
        """MEDIA-T1: chunk_ack ans Phone (klar, light, fire-and-forget)."""
        frame = {"type": "chat.media.chunk_ack", "v": _PROTOCOL_VERSION,
                 "agent_id": self.agent_id, "chat_id": chat_id,
                 "media_id": media_id, "seq": seq, "ok": ok}
        if error:
            frame["error"] = error[:128]
        try:
            await self._send_json(frame)
        except Exception as exc:
            logger.warning("FlipsiBridge: chunk_ack-Send fehlgeschlagen: %s", exc)

    async def send_media(self, chat_id: str, path: str,
                        kind: Optional[str] = None,
                        caption: Optional[str] = None) -> SendResult:
        """MEDIA-T1: Datei chunked + E2E-verschlüsselt ans Phone senden.

        Fail-closed: OHNE aktive E2E-Session gibt es KEINEN Klartext-Pfad
        (SendResult-Fehler statt stiller Downgrade). Größe/Chunk-Zahl werden
        vorab geprüft (Parität zum Relay-Deckel); jeder Chunk läuft als
        chat.media.enc über die Ratchet-Sitzung.
        """
        if not self._connected or self._ws is None:
            return SendResult(success=False, error="nicht verbunden", retryable=True)
        session = self._active_session()
        if session is None:
            return SendResult(success=False,
                              error="keine aktive E2E-Session — Medienversand "
                                    "nur verschlüsselt möglich (fail-closed)",
                              retryable=False)
        # FIX-WELLE-2 B1: stabile Gateway-ID → Relay-Wire-ID (AAD muss die
        # Relay-chat_id des Phones tragen, sonst verwirft GCM den Chunk).
        chat_id = self._relay_chat_id_for(chat_id)
        try:
            size = os.path.getsize(path)
        except OSError as exc:
            return SendResult(success=False, error=str(exc), retryable=False)
        if size < 1:
            return SendResult(success=False, error="Datei leer", retryable=False)
        if size > _MEDIA_MAX_BYTES:
            return SendResult(success=False,
                              error=f"Datei zu groß (max {_MEDIA_MAX_BYTES} Bytes)",
                              retryable=False)
        name = os.path.basename(path) or "datei"
        if len(name) > 128:
            name = name[-128:]
        resolved_kind = (kind or "").strip() or (
            "image" if name.lower().endswith((".jpg", ".jpeg", ".png", ".gif", ".webp"))
            else "video" if name.lower().endswith((".mp4", ".mov", ".mkv", ".webm"))
            else "voice" if name.lower().endswith((".m4a", ".aac", ".wav", ".ogg"))
            else "file")
        if resolved_kind not in _MEDIA_KINDS:
            return SendResult(success=False, error="kind ungültig", retryable=False)
        total = max(1, -(-size // _MEDIA_CHUNK_BYTES))
        if total > _MEDIA_MAX_CHUNKS:
            return SendResult(success=False, error="zu viele Chunks", retryable=False)
        import hashlib as _hashlib
        try:
            with open(path, "rb") as fh:
                blob = fh.read()
        except OSError as exc:
            return SendResult(success=False, error=str(exc), retryable=True)
        sha = _hashlib.sha256(blob).hexdigest()
        media_id = "med-" + uuid.uuid4().hex[:24]
        # PH2-T11-Parität: working=true vor dem Upload, false danach.
        started_at = int(time.time() * 1000)
        await self._send_activity(chat_id, working=True, started_at=started_at)
        try:
            for seq in range(total):
                chunk = blob[seq * _MEDIA_CHUNK_BYTES:(seq + 1) * _MEDIA_CHUNK_BYTES]
                payload = {"media_id": media_id, "seq": seq, "total": total,
                           "data": base64.b64encode(chunk).decode("ascii")}
                if seq == 0:
                    payload["sha256"] = sha
                clear = {"media_id": media_id, "seq": seq, "total": total}
                if seq == 0:
                    clear.update({"kind": resolved_kind, "name": name,
                                  "size": size})
                frame = session.encrypt_frame("chat.media.enc", chat_id,
                                              payload, clear=clear)
                await self._send_json(frame)
            if caption:
                await self.send(chat_id, f"[{_media_kind_label(resolved_kind)}] "
                                         f"{name}: {caption}")
            return SendResult(success=True, message_id=media_id)
        except Exception as exc:
            logger.warning("FlipsiBridge: Media-Send fehlgeschlagen: %s", exc)
            return SendResult(success=False, error=str(exc), retryable=True)
        finally:
            await self._send_activity(chat_id, working=False, started_at=started_at)

    async def _send_json(self, envelope: dict) -> None:
        """JSON-Frame über die Agent-WS senden (Token bleibt im Auth-Header)."""
        await self._ws.send_str(json.dumps(envelope))

    # ------------------------------------------------------------------ Loader
    def is_connected(self) -> bool:
        """Verbindungsstatus (Skeleton-Hook für Status-Anzeigen)."""
        return self._connected


def check_flipsibridge_requirements() -> bool:
    """Abhängigkeits-Check (aiohttp für den echten WS-Betrieb)."""
    try:
        import aiohttp  # noqa: F401
        return True
    except ImportError:
        return False


def register(ctx) -> None:
    """Plugin-Eintrittspunkt (Muster: plugins/platforms/mattermost/adapter.py)."""
    ctx.register_platform(
        name="flipsibridge", label="FlipsiBridge Chat",
        adapter_factory=FlipsiBridgeChatAdapter,
        check_fn=check_flipsibridge_requirements,
        required_env=["FLIPSI_RELAY_URL", "FLIPSI_AGENT_TOKEN", "FLIPSI_AGENT_ID"],
        allowed_users_env="FLIPSI_ALLOWED_DEVICES",
        install_hint="pip install aiohttp",
        emoji="🟠")


def crypto_cli(argv: Optional[list] = None) -> int:
    """PH-3 (Spec §2.6): `python adapter.py fingerprint` — Identity-Report.

    Gibt die PUBLIZIERBAREN Werte (PubKeys + Fingerprint) für relay-config.json
    aus; Private Keys bleiben ausschließlich im Key-File (0600) und werden
    niemals ausgegeben oder geloggt.
    """
    import argparse

    parser = argparse.ArgumentParser(
        prog="flipsibridge-crypto",
        description="FlipsiBridge PH-3 Agent-Identity (fingerprint-Report)")
    parser.add_argument("command", nargs="?", default="fingerprint",
                        choices=["fingerprint"], help="Unterbefehl")
    parser.add_argument("--file", default=os.path.join(
        os.path.expanduser("~"), ".flipsibridge", "agent-identity.json"),
        help="Pfad zum Identity-Key-File (Default ~/.flipsibridge/agent-identity.json)")
    args = parser.parse_args(argv)
    identity = load_or_create_identity(args.file)
    fp = fingerprint_of_pub(bytes.fromhex(identity["identity_pub"]))
    print("FlipsiBridge Agent-Identity — für relay-config.json kopieren (<ID> groß):")
    print(f"  ANDROID_AGENT_IDENTITY_<ID>    = {identity['identity_pub']}")
    print(f"  ANDROID_AGENT_PREKEY_<ID>      = {identity['prekey_pub']}")
    print(f"  ANDROID_AGENT_FINGERPRINT_<ID> = {fp}")
    print(f"Identity-Fingerprint (Anzeige):  {fingerprint_display(fp)}")
    print(f"Key-File: {args.file} (0600 — Private Keys bleiben dort)")
    return 0


if __name__ == "__main__":
    raise SystemExit(crypto_cli())