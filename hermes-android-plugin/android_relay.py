"""
android_relay — WebSocket relay that bridges HTTP tool calls to a phone over WS.

The relay runs an aiohttp server exposing:
  - /ws          WebSocket endpoint the phone connects to (Authorization: Bearer *** header for auth)
  - /ping, /screen, /tap, /tap_text, /type, /swipe, /open_app, /press_key,
    /screenshot, /scroll, /wait, /apps, /current_app   HTTP endpoints matching
    the bridge API consumed by android_tool.py

Flow:
  1. Phone connects via WebSocket with Bearer token in Authorization header
  2. Python tool makes an HTTP request to e.g. /screen
  3. Relay wraps request as JSON command, sends over WS to phone
  4. Phone executes command, sends JSON response back over WS
  5. Relay returns the phone's response to the HTTP caller

Command JSON format:
  Relay -> Phone:  {"request_id": "uuid", "method": "GET|POST", "path": "/screen",
                    "params": {...}, "body": {...}}
  Phone -> Relay:  {"request_id": "uuid", "result": {...}, "status": 200}
"""

# TLS (W3-01): ANDROID_RELAY_TLS=1 + ANDROID_RELAY_CERT/ANDROID_RELAY_KEY (PEM-
# Dateipfade) aktivieren NATIVES TLS auf dem Relay-Socket (wss:// bzw. https://).
# =1 ohne CERT/KEY = Konfig-Fehler (fail-closed, RuntimeError beim Start);
# ANDROID_RELAY_TLS=0 erzwingt Plaintext (Interims-/QA-Kill-Switch); ohne Flag
# gilt weiterhin: CERT+KEY vorhanden = TLS an (altbewährte Aktivierung). Ein
# externer Host ohne TLS bleibt fail-closed (D5b, s. _serve).

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import math
import os
import re
import tempfile
import threading
import time
import uuid
from collections import deque
from pathlib import Path
from typing import Optional, TypeGuard

import aiohttp
from aiohttp import web

logger = logging.getLogger("android_relay")

# ── Module-level state ────────────────────────────────────────────────────────

_relay_lock = threading.Lock()
_relay_instance: Optional["_RelayState"] = None


class _RelayState:
    """Holds all mutable state for one running relay instance."""

    def __init__(self, pairing_code: str, port: int):
        self.pairing_code: str = pairing_code
        self.port: int = port
        # FIN-T1 RED-02: Rotations-Epoche — wird bei JEDEM Code-WECHSEL unter
        # _relay_lock inkrementiert. Die Phone-WS-Registrierung liest die Epoche
        # nach dem Token-Check und verifiziert sie unter phone_ws_lock erneut:
        # eine WS, die mit dem ALTEN Code authentifizierte, während die Rotation
        # zwischen Token-Check und Registrierung landete (await ws.prepare =
        # await-Punkt im Rennfenster), kann NIE die aktive Phone-WS werden.
        self.pairing_epoch: int = 0

        # asyncio loop running in the background thread
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.thread: Optional[threading.Thread] = None

        # aiohttp plumbing
        self.app: Optional[web.Application] = None
        self.runner: Optional[web.AppRunner] = None
        self.site: Optional[web.TCPSite] = None

        # The single connected phone WebSocket (or None)
        self.phone_ws: Optional[web.WebSocketResponse] = None
        # K8-Fix (Kobold): KEINE eager Lock-Erzeugung hier — __init__ läuft OHNE
        # Event-Loop; der Lock wird in _run_loop (Z. ~220) gebunden, bevor der
        # Server laust. Der Alte eager-Init war ein totes Feld (wurde ersetzt).
        self.phone_ws_lock: Optional[asyncio.Lock] = None

        # Pending requests: request_id -> asyncio.Future
        self.pending: dict[str, asyncio.Future] = {}
        self.pending_lock: Optional[asyncio.Lock] = None  # created lazily

        # Binary HTTP responses (currently microphone WAV downloads):
        # request_id -> bounded queue of ("message"|"chunk"|"error", payload).
        self.streams: dict[str, asyncio.Queue] = {}
        self.streams_lock: Optional[asyncio.Lock] = None

        # Shutdown event
        self.shutdown_event: Optional[asyncio.Event] = None

        # PH2-T1: Agent-Registry — agent_id -> {display_name, online, last_seen,
        # fingerprint}. fingerprint bleibt in der Phase-2-Basis leer (I1-Finger-
        # abdruck kommt später). Locks lazy wie phone_ws_lock (in _run_loop).
        self.agents: dict[str, dict] = {}
        self.agent_ws: dict[str, web.WebSocketResponse] = {}
        self.agent_ws_lock: Optional[asyncio.Lock] = None

        # PH2-T1: Chat-Broker — je Agent eine Inbound-Queue (App→Agent) plus
        # eine Phone-Outbox (Agent→App). Einträge: (timestamp, message-dict).
        # PH2-FIX (Jäger B2): beide Queues mit maxlen=500 (FIFO) — ein
        # Offline-Phone/Agent kann den Relay-Speicher NICHT unbegrenzt füllen
        # (älteste Frames fallen automatisch raus, Retention prune weiter).
        self.chat_inbound: dict[str, deque] = {}
        # SEC-W1 B F-01: Bytes-aware Outbox (Frames UND Bytes begrenzt).
        self.phone_outbox: deque = _ByteBoundedDeque(maxlen=_CHAT_QUEUE_MAXLEN)
        self.broker_lock: Optional[asyncio.Lock] = None

        # PH2-T10 (F2-S1): Chat-Status je msg_id — {msg_id: {chat_id, agent_id,
        # status, ts}}. Übergänge: queued → delivered (Agent-WS-Send) → read
        # (chat.read-Frame des Agenten). Je Wechsel geht ein chat.status-Frame
        # an die Phone-WS; Status-Frames sind NICHT rate-limited.
        self.chat_status: dict[str, dict] = {}

        # PH2-T10 (F2-S3): Activity-Dedup je (agent_id, chat_id) — letzter
        # working-Wert; nur ein WECHL erzeugt einen Phone-Frame (keine Flood).
        self.chat_activity: dict[tuple[str, str], bool] = {}

        # PH2-T5: Konversations-Isolation — chat_id → (owner_agent_id, ts).
        # Eine Agenten-Antwort auf eine fremde chat_id wird verworfen + geloggt.
        # FIN-T1 RED-06: Einträge DAUERHAFT (Retention-Prune fasst sie nicht
        # mehr an; RAM-Schutz = _cap_chat_owner-FIFO).
        self.chat_owner: dict[str, tuple[str, float]] = {}

        # PH-3-T2: E2E-Session-Broker — {session_id: {agent_id, ts, acked}}
        # (24-h-Gültigkeit, Fail-closed). Der Relay sieht NUR Metadaten: keine
        # Keys, kein Klartext, ct bleibt opak. Zugriff ausschließlich im
        # Event-Loop (alle Handler) → kein eigener Lock nötig.
        self.crypto_sessions: dict[str, dict] = {}
        # PH-3: Grob-Replay-Tracking je (session_id, richtung, ratchet_pub) —
        # Fenster 100 + 10-min-Freshness (Defense-in-Depth; die GCM-Prüfung
        # machen die Endpunkte, der Relay kann Inhalte by design nicht prüfen).
        # P1c: hdr.n ist nur JE RATCHET-KETTE (hdr.rk) eindeutig.
        self.crypto_replay: dict[tuple[str, str, str], dict] = {}


# ── Public API (called from sync code) ────────────────────────────────────────


def start_relay(pairing_code: str, port: int = 0) -> None:
    """Start the relay in a background thread.  No-op if already running."""
    global _relay_instance
    # FIN-T1 RED-01: leerer/null-Code = fail-closed abgewiesen (PoC: Relay mit
    # leerem Code akzeptierte Phone-WS + alle Pairing-Code-Routen OHNE Header).
    if not _valid_pairing_code(pairing_code):
        logger.error(
            "start_relay: empty/invalid pairing code — rejected (fail-closed)")
        raise ValueError("pairing code must be a non-empty string")
    if port == 0:
        port = int(os.getenv("ANDROID_RELAY_PORT", "8766"))

    with _relay_lock:
        if (
            _relay_instance is not None
            and _relay_instance.thread is not None
            and _relay_instance.thread.is_alive()
        ):
            logger.info("Relay already running on port %d", _relay_instance.port)
            return

        state = _RelayState(pairing_code, port)
        _relay_instance = state

        ready = threading.Event()
        t = threading.Thread(
            target=_run_loop, args=(state, ready), daemon=True, name="android-relay"
        )
        state.thread = t
        t.start()
        # Wait until the server is actually listening (up to 10 s)
        if not ready.wait(timeout=10):
            logger.error("Relay failed to start within 10 seconds")
            raise RuntimeError("Relay failed to start")
        logger.info("Relay started on port %d", port)


def stop_relay() -> None:
    """Gracefully stop the relay."""
    global _relay_instance
    with _relay_lock:
        state = _relay_instance
        if state is None:
            return
        _relay_instance = None

    # Signal the event loop to shut down
    if state.loop is not None and state.shutdown_event is not None:
        state.loop.call_soon_threadsafe(state.shutdown_event.set)
    if state.thread is not None:
        state.thread.join(timeout=5)
    logger.info("Relay stopped")


def is_relay_running() -> bool:
    with _relay_lock:
        s = _relay_instance
        return s is not None and s.thread is not None and s.thread.is_alive()


def is_phone_connected() -> bool:
    with _relay_lock:
        s = _relay_instance
        if s is None:
            return False
        ws = s.phone_ws
        return ws is not None and not ws.closed


def get_agent_registry() -> dict[str, dict]:
    """Snapshot der Agent-Registry (Presence für Tools/UI).

    Je Agent: display_name, online (WS verbunden), last_seen (Unix-Sekunden,
    0.0 = nie gesehen), fingerprint (Phase-2-Basis: leerer String).
    """
    with _relay_lock:
        s = _relay_instance
        if s is None:
            return {}
        return {aid: dict(entry) for aid, entry in s.agents.items()}


def _valid_pairing_code(code) -> bool:
    """FIN-T1 RED-01: Pairing-Code-Pflicht — nur nicht-leere Strings sind
    gültig (whitespace zählt als leer). JEDE Code-Quelle (start_relay,
    set_pairing_code, android_setup) verwirft darüber fail-closed: ein
    leerer/null-Code darf NIE einen Relay-Auth-Zustand erzeugen (PoC:
    start_relay(pairing_code="") lief einen vollständigen Auth-Bypass —
    Phone-WS + /key_get + /chat + /crypto_hello ohne JEGLICHES Header-feld)."""
    return isinstance(code, str) and bool(code.strip())


def _constant_time_eq(a: str, b: str) -> bool:
    """v0.11.0 (Relay-Review M1): hmac.compare_digest wirft bei non-ASCII-Strings
    einen TypeError → 500er UND der Fehlversuch wurde nicht gezählt (Rate-Limit
    umgehbar per Müll-Header). UTF-8-Bytes vergleichen, jede Ausnahme = mismatch.
    FIN-T1 RED-01: LEERE Operanden sind IMMER ein Mismatch (fail-closed) —
    hmac.compare_digest("", "") lieferte True, d.h. ein (fehlkonfigurierter)
    Relay mit leerem Code akzeptierte JEDE Anfrage ohne Authorization-Header."""
    if not a or not b:
        return False
    try:
        return hmac.compare_digest(a.encode("utf-8"), b.encode("utf-8"))
    except Exception:
        return False


def _trusted_proxy_ip() -> str:
    """LAN-IP des Reverse-Proxy — nur ihm dürfen wir XFF glauben (per env gesetzt).
    Inspektor-M1: bei DIREKTEXTERN-Contact wäre der XFF-Header selbstvorgebenbar
    (Rate-Limit-Umgehung + IP-Framing des Handys). XFF nur bei proxy-wlassigem Peer.
    Default-PEER-Modus: ohne env wird XFF NIE geglaubt (fail-closed)."""
    return os.environ.get("ANDROID_RELAY_TRUSTED_PROXY_IP", "")


def _effective_remote_ip(request: web.Request) -> str:
    """v0.11.0 (Security-Review HOCH-1): hinter dem Reverse-Proxy sehen alle
    externen Clients EINE IP — 5 Fehlversuche eines Angreifers blockten das
    legitime Handy. Der trusted Proxy hängt die echte Client-IP an die
    X-Forwarded-For-Kette an → wir nehmen den LETZTEN Eintrag (vom Proxy
    angehängt und damit nicht vom Client vorgebenbar).
    Inspektor-M1-Fix: XFF wird NUR gelesen, wenn der/socket Peer = der
    konfigurierte Proxy ist (env ANDROID_RELAY_TRUSTED_PROXY_IP). Von überall
    sonst kommt kein XFF durch — ein direkt-externer Angreifer mit selbst-
    gebauten XFF bleibt bei SEINER echten IP im Rate-Limit. Fail-closed:
    ohne Proxy-Konfiguration wird XFF niemals geglaubt."""
    peer = request.remote or "unknown"
    xff = request.headers.get("X-Forwarded-For", "")
    if not xff:
        return peer
    trusted = _trusted_proxy_ip()
    if not trusted or peer != trusted:
        # XFF von einem Nicht-Proxy = selbstvorgebbar → ignorieren.
        return peer
    client_ip = xff.split(",")[-1].strip()
    return client_ip or peer


def _sha256_file(path: str) -> str:
    """v0.11.0 (Relay-Review): SHA-256 im Chunk-Lauf statt der kompletten
    APK in den RAM zu lesen."""
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(256 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


_APK_VERSION_PATTERN = re.compile(r"(\d+)\.(\d+)\.(\d+)")


def _apk_version_key(name: str) -> tuple[int, int, int]:
    """v0.11.0 (Relay-Review): versionsbewusst sortieren — lexikalisch wäre
    '0.10.10' < '0.10.7'. Ohne Treffer (0,0,0), dann greift der mtime-Beiwert."""
    m = _APK_VERSION_PATTERN.search(os.path.basename(name))
    if not m:
        return (0, 0, 0)
    a, b, c = m.groups()
    return (int(a), int(b), int(c))


# ── PH2-T1: relay-config.json (Server-Konfig je Agent) ────────────────────────
# O4-Regel: Agent-Token + Limit-Parameter liegen serverseitig in der Config-
# Datei (Default-Pfad = Live-Ablage), nicht im Repo und nicht in der .env.

_AGENT_TOKEN_PREFIX = "ANDROID_AGENT_TOKEN_"


def _default_state_dir() -> str:
    """Laufzeit-State-Verzeichnis: innerhalb von Hermes das kanonische
    Plugin-Datenverzeichnis (<hermes home>/plugin-data/hermes-android —
    Teknium-Katalog-Review #2), außerhalb (Relay-Daemon/Tests ohne Hermes-Core)
    der /opt-Fallback für Standalone-Deploys."""
    try:
        from plugins.plugin_storage import plugin_data_dir

        return str(plugin_data_dir("hermes-android"))
    except Exception:
        return "/opt/flipsibridge"


def _agent_config_path() -> str:
    """Pfad zur Agent-Config (env-überschreibbar; Default = Plugin-Datenverzeichnis
    unter Hermes, /opt/flipsibridge standalone)."""
    return os.getenv(
        "ANDROID_RELAY_CONFIG",
        os.path.join(_default_state_dir(), "relay-config.json"))
def _load_relay_config() -> dict:
    """relay-config.json lesen (Agent-Tokens, Display-Namen, Chat-Limit).

    Fehlt oder ist kein gültiges JSON: leeres dict — dann kennt das Relay keine
    Agenten und /agent_ws sowie /chat_event weisen ab (fail-closed). Der Datei-
    Inhalt wird bewusst nicht geloggt.
    """
    try:
        with open(_agent_config_path(), "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except OSError:
        return {}
    except json.JSONDecodeError:
        logger.warning("relay-config.json is not valid JSON — agent access deactivated")
        return {}
    return data if isinstance(data, dict) else {}


def _agent_tokens(config: dict) -> dict[str, str]:
    """ANDROID_AGENT_TOKEN_<ID> -> {agent_id: token}; agent_id klein geschrieben."""
    tokens: dict[str, str] = {}
    for key, value in config.items():
        if not key.startswith(_AGENT_TOKEN_PREFIX):
            continue
        if not isinstance(value, str) or not value.strip():
            continue
        agent_id = key[len(_AGENT_TOKEN_PREFIX):].strip().lower()
        if agent_id:
            tokens[agent_id] = value
    return tokens


def _agent_display_name(config: dict, agent_id: str) -> str:
    """Display-Name aus ANDROID_AGENT_DISPLAY_<ID>, sonst die agent_id selbst."""
    value = config.get(f"ANDROID_AGENT_DISPLAY_{agent_id.upper()}")
    if isinstance(value, str) and value.strip():
        return value.strip()
    return agent_id


def _chat_rate_per_second(config: Optional[dict] = None) -> int:
    """Chat-Nachrichtenlimit je Sekunde — relay-config.json überschreibt env."""
    value = None
    if config is not None:
        value = config.get("CHAT_RATE_PER_SECOND")
    if value is None:
        value = os.getenv("ANDROID_RELAY_CHAT_RATE_PER_SEC", "2")
    try:
        return max(1, int(value))
    except (TypeError, ValueError):
        return 2


def _media_rate_per_second(config: Optional[dict] = None) -> int:
    """B6 (Herz-Test Befund 6): eigenes Chunk-Burst-Limit für /media.

    Media-Uploads sind Multi-Chunk (bis 50 MB, 192-KiB-Chunks) und dürfen
    nicht am 2/s-Chat-Limit derselben IP zehren — sonst sind 6 Chunks <1 s
    zum 429-Fall geworden (Herz-Test: 4× 429; ein 24-MB-File = 126 Chunks
    war praktisch nicht übertragbar). relay-config.json
    (MEDIA_RATE_PER_SECOND) überschreibt env ANDROID_RELAY_MEDIA_RATE; der
    Chat-Bucket (chat:<ip>, ANDROID_RELAY_CHAT_RATE_PER_SEC) bleibt davon
    unangetastet.
    """
    value = None
    if config is not None:
        value = config.get("MEDIA_RATE_PER_SECOND")
    if value is None:
        value = os.getenv("ANDROID_RELAY_MEDIA_RATE", "20")
    try:
        return max(1, int(value))
    except (TypeError, ValueError):
        return 20


def _chat_retention_seconds() -> float:
    """Spool-Retention in Sekunden (env-konfigurierbar, Default 24 h)."""
    try:
        hours = float(os.getenv("ANDROID_RELAY_CHAT_RETENTION_HOURS", "24"))
    except ValueError:
        hours = 24.0
    return max(0.0, hours) * 3600.0


# ── PH-3-T2: Key-Registry + E2E-Session-Broker ─────────────────────────────────
# Der Relay ist Zwischenliefer MIT eigener Transport-Isolation: er sieht nur
# PUBLIZIERBARE Verzeichnis-Keys (identity/prekey/fingerprint) + Metadaten
# (chat_id/msg_id/session_id/Frame-Typ). Key-Material und ct werden NIEMALS
# geloggt; der Relay entschlüsselt INHALTE nie (by design, PH3-CRYPTO-SPEC §0).
_AGENT_IDENTITY_PREFIX = "ANDROID_AGENT_IDENTITY_"
_AGENT_PREKEY_PREFIX = "ANDROID_AGENT_PREKEY_"
_AGENT_FINGERPRINT_PREFIX = "ANDROID_AGENT_FINGERPRINT_"
_HEX64_RE = re.compile(r"^[0-9a-fA-F]{64}$")
_ENC_NONCE_LEN = 12
_ENC_CT_MAX_BYTES = 512 * 1024
_REPLAY_WINDOW = 100                # Counter-Fenster je Richtung (Relay-Grobprüfung)
_FRAME_MAX_AGE_MS = 10 * 60 * 1000  # Frame-Freshness (Replay-Fenster 100 + 10 min)
# SEC-W1 B F-06: Sanity-Cap für hdr.n/pn — ein Riesen-n (PoC: 10^20) wurde als
# "neu" akzeptiert (max=n) und vergiftete danach das Fenster (legitimes n=5
# fiel auf 429 'replay' → Session-DoS). 2^32 Nachrichten je Ratchet-Kette
# sind physikalisch unerreichbar; alles darüber = Zähler-Gift → verwerfen.
_REPLAY_N_MAX = 1 << 32

# ── MEDIA-T1: chunked Medien-/Datei-Versand im Chat (E2E-only) ────────────────
# Protokoll (Sir-Feature „Medien im Chat“, abgeleitet aus J4-Empfehlung):
#   POST /media  {agent_id, chat_id, session_id, hdr, nonce, ct, ts,
#                 media_id, seq, total[, kind, name, mime, size bei seq=0]}
# - ct = E2E-Ratchet-Payload (Daten NUR verschlüsselt — Klartext-Fallback
#   ist FORBIDDEN/fail-closed wie bei jedem enc-Frame).
# - KLARE Metadaten je Frame (Relay-Validierung ohne Inhalts-Zugriff):
#   media_id (8-64 Z., [A-Za-z0-9_-]), seq/total (0 ≤ seq < total).
#   seq=0 trägt zusätzlich kind/name/mime/size (Größen-Deckel).
# - Chunk-Größe wählt das Phone (192 KiB raw → ~350 KiB Frame — J4: die
#   2-MB-WS-Grenze wird per Chunking NIE berührt, kein Einzel-Frame-Media).
_MEDIA_MAX_CHUNKS = 4096
_MEDIA_KINDS = frozenset({"image", "video", "file", "voice"})
_MEDIA_ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
_MEDIA_ID_MAX_LEN = 64
_MEDIA_NAME_MAX_LEN = 128
_MEDIA_ACK_MAX_PER_SECOND = 30  # 1 ack je Chunk — Bursts bei großen Dateien


def _media_max_bytes() -> int:
    """Maximale Größe je Media (env-konfigurierbar, Default 50 MB)."""
    default = 50 * 1024 * 1024
    try:
        return max(1024, int(os.getenv("ANDROID_RELAY_MEDIA_MAX_BYTES",
                                       str(default))))
    except ValueError:
        return default


def _media_meta_ok(value) -> bool:
    """FIN-T1 RED-05-Parität: klare Media-Extras dürfen nur str ≤128 oder
    endliche ints sein (Typ-Konvention am Endpunkt, kein NaN-Float)."""
    if isinstance(value, str):
        return len(value) <= _MEDIA_NAME_MAX_LEN
    if isinstance(value, (int, float)):
        return math.isfinite(value)
    return False


def _validate_media_meta(data: dict, require_header: bool = True) -> Optional[str]:
    """Klare Media-Metadaten prüfen (ct bleibt opak). Rückgabe = Fehlergrund
    oder None (gültig). require_header=False für chunk_ack (nur media_id/seq)."""
    media_id = data.get("media_id")
    if not isinstance(media_id, str) or not _MEDIA_ID_RE.fullmatch(media_id):
        return "media_id missing/invalid (8-64 chars, [A-Za-z0-9_-])"
    if not _utf8_encodable(media_id):
        return "media_id not UTF-8 encodable"
    seq = data.get("seq")
    total = data.get("total")
    if not require_header:
        if not isinstance(seq, int) or isinstance(seq, bool) or seq < 0:
            return "seq invalid"
        return None
    if not isinstance(seq, int) or isinstance(seq, bool) or seq < 0:
        return "seq invalid"
    if not isinstance(total, int) or isinstance(total, bool) or total < 1 \
            or total > _MEDIA_MAX_CHUNKS:
        return "total invalid"
    if seq >= total:
        return "seq ≥ total"
    if seq == 0:
        kind = data.get("kind")
        if not isinstance(kind, str) or kind not in _MEDIA_KINDS:
            return "kind missing/invalid (image/video/file/voice)"
        name = data.get("name")
        if not isinstance(name, str) or not name.strip() \
                or len(name) > _MEDIA_NAME_MAX_LEN:
            return "name fehlt/zu lang (≤128)"
        mime = data.get("mime")
        if mime is not None and (not isinstance(mime, str)
                                  or len(mime) > _MEDIA_NAME_MAX_LEN):
            return "mime invalid (≤128)"
        size = data.get("size")
        if not isinstance(size, int) or isinstance(size, bool) or size < 1 \
                or size > _media_max_bytes():
            return f"size invalid (max {_media_max_bytes()} bytes)"
    return None

# ── SEC-W1 B F-01: harte Größen-Grenzen (Relay-RAM-Schutz, Shared-Infra) ────
_CHAT_TEXT_MAX_CHARS = 32 * 1024    # Chat-Text je Nachricht (413 darüber)
_CHAT_QUEUE_MAX_BYTES = 8 * 1024 * 1024  # Bytes-Budget je Broker-Queue
_CHAT_ACTIVITY_MAX = 256           # SEC-W1 D2: Dedup-Map-Cap (war unbounded)
# FIN-T1 RED-06: Owner-Isolation überlebt die Spool-Retention — chat_owner
# wird NICHT mehr ts-geprüft (PoC red06: nach Retention-Ablauf claimte ein
# fremder Agent die chat_id mit 200 und beliefert sie = Cross-Agent-Injektion
# in die Konversation). RAM-Schutz stattdessen per FIFO-Cap: 50 000 chat_ids
# (~10 MB Worst Case, Jahre Betrieb) — weit jenseits jeder realen Nutzung.
_CHAT_OWNER_MAX = 50_000
_AGENT_LIGHT_FRAME_MAX_PER_SECOND = 10  # SEC-W1 W1C-9: keepalive/ping/presence
_CHAT_RATE_MAX_KEYS = 1024          # SEC-W1 W1C-7: Sweep-Schwelle der Rate-Map
# SEC-W2 W2-2: Broker-Routen ohne eigenes Rate-Limit — /crypto_hello füllte
# state.crypto_sessions ungedrosselt (PoC: 30 Hellos in <0.5 s = 30 Sessions,
# alle 200; Prune erst nach 24 h) und /key_publish legte JE Request eine Datei
# an (mkstemp+replace-I/O-Sturm). Beide brauchen nur ~1 Aufruf je Pairing/
# Session — 10/s ist weit über jedem legitimen Bedarf.
_BROKER_ROUTE_MAX_PER_SECOND = 10


def _finite_ms(value) -> "TypeGuard[float]":
    """SEC-W1 B F-03: int(inf) = OverflowError (WS-Handler-Reset), NaN fiel
    durch >= 0 — nur endlich-numerische, nicht-negative Werte koerzfähig."""
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value) and value >= 0)


def _enc_extra_ok(value) -> bool:
    """FIN-T1 RED-05: Whitelist für KLARE enc-Extras, die ans Phone-Frame
    durchgereicht werden (msg_id/elapsed_ms in chat.reply.enc). Bis jetzt
    passierten NaN (als WÖRTLICHES JSON-NaN = invalides JSON für strikte
    Parser wie Kotlin kotlinx.serialization), Objekte und Listen ungeprüft
    (Typ-Konfusion am Phone-Endpunkt; Parität zum Klartext-_finite_ms/F-03).
    Erlaubt: str (≤128 Zeichen), endliche num (int/float), bool — alles
    andere (None = abwesend) wird verworfen."""
    if isinstance(value, bool):
        return True
    if isinstance(value, str):
        return len(value) <= 128
    if isinstance(value, (int, float)):
        return math.isfinite(value)
    return False


def _log_id(value: str, limit: int = 64) -> str:
    """SEC-W1 B F-07: IDs können \\n/\\r enthalten (Log-Forging — täuschende
    Log-Zeilen im Audit) — für die LOG-Ausgabe escapen und kürzen. Nur
    Ausgabe-Helfer, die Verarbeitung bleibt unangetastet."""
    text = str(value)[:limit]
    if "\n" in text or "\r" in text:
        text = text.replace("\r", "\\r").replace("\n", "\\n")
    return text


def _utf8_encodable(value) -> bool:
    """SEC-W1 B F-02: Lone-Surrogates (JSON-escaped) sind UTF-8-NICHT-
    codierbar und warfen erst im Adapter/Phone UnicodeEncodeError (Frame-
    Loop-Tod bzw. Handshake-Abbruch). Fail-closed bereits am Relay."""
    try:
        str(value).encode("utf-8")
    except UnicodeEncodeError:
        return False
    return True


def _entry_bytes(entry) -> int:
    """Approximative Frame-Größe (JSON-Länge + Tupel-Overhead) fürs
    Bytes-Budget — bewusst grob, nie größer als die Realität."""
    try:
        return len(json.dumps(entry[1], default=str)) + 32
    except Exception:
        return 4096


class _ByteBoundedDeque(deque):
    """SEC-W1 B F-01: Broker-Queue mit FRAMES- und BYTES-Budget.

    maxlen=500 begrenzte nur die ANZAHL — ein Agent-Token-Inhaber konnte
    500 Frames à bis ~96 MB spoolen (OOM des Relays = ALLE Agenten + Phone
    tot). Die Subclass führt einen laufenden Byte-Zähler und wirft beim
    Anhang die ÄLTESTEN Frames raus, bis das Budget wieder stimmt (FIFO-
    Parität zu maxlen). Einträge bleiben (ts, message)-Tupel.
    """

    def __init__(self, iterable=(), maxlen=500,
                 max_bytes: int = _CHAT_QUEUE_MAX_BYTES):
        super().__init__(maxlen=maxlen)
        self._max_bytes = max_bytes
        self._bytes = 0
        for item in iterable:
            self.append(item)

    def _evict_oldest(self) -> None:
        while self._bytes > self._max_bytes and len(self) > 1:
            self.popleft()

    def append(self, item) -> None:
        self._bytes += _entry_bytes(item)
        super().append(item)
        self._evict_oldest()

    def extend(self, iterable) -> None:
        items = list(iterable)
        for item in items:
            self._bytes += _entry_bytes(item)
        super().extend(items)
        self._evict_oldest()

    def extendleft(self, iterable) -> None:
        items = list(iterable)
        for item in items:
            self._bytes += _entry_bytes(item)
        super().extendleft(items)
        self._evict_oldest()

    def popleft(self):
        item = super().popleft()
        self._bytes -= _entry_bytes(item)
        return item

    def clear(self) -> None:
        super().clear()
        self._bytes = 0

    def __delitem__(self, index) -> None:
        self._bytes -= _entry_bytes(self[index])
        super().__delitem__(index)


def _crypto_session_max_age_seconds() -> float:
    """E2E-Session-Gültigkeit (env-konfigurierbar, Default 24 h)."""
    try:
        hours = float(os.getenv("ANDROID_RELAY_SESSION_HOURS", "24"))
    except ValueError:
        hours = 24.0
    return max(0.1, hours) * 3600.0


def _agent_crypto_registry(config: dict) -> dict[str, dict]:
    """Öffentliche E2E-Verzeichnis-Infos je Agent aus relay-config.json:
    {agent_id: {ik_pub, spk_pub, fingerprint}}. Fail-closed: nur Agenten MIT
    validem Identity-PubKey tauchen auf — ohne Keys KEIN E2E-Zugang.

    SEC-W1 W1A-6: Der Fingerprint wird SERVERSEITIG aus ik_pub abgeleitet
    (gleiche HKDF-Kette wie adapter fingerprint_of_pub) — der Config-Wert
    ANDROID_AGENT_FINGERPRINT_<ID> war bisher ungeprüft und konnte dem Phone
    einen fremden Agent-Fingerprint vorgaukeln (Identity-Fingerprint-Spoof
    per Config-Buchstabe). Config-Wert dient nur noch als Toleranz-Vergleich
    (Abweichung = Warnung), gesendet wird IMMER der abgeleitete Wert."""
    registry: dict[str, dict] = {}
    for agent_id in _agent_tokens(config):
        ik = config.get(f"{_AGENT_IDENTITY_PREFIX}{agent_id.upper()}")
        if not isinstance(ik, str) or not _HEX64_RE.fullmatch(ik.strip()):
            continue
        derived_fp = _fingerprint_of_pub(ik.strip())
        fp = config.get(f"{_AGENT_FINGERPRINT_PREFIX}{agent_id.upper()}")
        if isinstance(fp, str) and fp.strip() and fp.strip().lower() != derived_fp:
            logger.warning(
                "Config-Fingerprint von Agent %s weicht von der ik_pub-Ableitung ab — "
                "serverseitig abgeleiteter Wert wird gesendet (Config prüfen)",
                agent_id)
        spk = config.get(f"{_AGENT_PREKEY_PREFIX}{agent_id.upper()}")
        registry[agent_id] = {
            "ik_pub": ik.strip().lower(),
            "spk_pub": spk.strip().lower()
            if isinstance(spk, str) and _HEX64_RE.fullmatch(spk.strip()) else "",
            "fingerprint": derived_fp,
        }
    return registry


def _fingerprint_of_pub(pub_hex: str) -> str:
    """agent_fp = HKDF-SHA256 über den Identity-PubKey — byte-identisch zur
    Adapter-Implementierung (contrib/hermes-chat-plugin/adapter.py:
    fingerprint_of_pub): salt=SHA256('flipsi-fp'), info='fb-agent-fp', 32 B."""
    salt = hashlib.sha256(b"flipsi-fp").digest()
    ikm = bytes.fromhex(pub_hex.strip().lower())
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    okm = hmac.new(prk, b"fb-agent-fp" + b"\x01", hashlib.sha256).digest()
    return okm.hex()


def _device_keys_path() -> str:
    """Pfad der Phone-Key-Registry (env-überschreibbar; Default wie _agent_config_path)."""
    return os.getenv(
        "ANDROID_RELAY_KEYS",
        os.path.join(_default_state_dir(), "relay-keys.json"))


def _load_device_keys() -> dict:
    """Phone-Device-Keys lesen (json). Fehlt/kaputt: leeres dict (fail-closed,
    gleiche Logik wie _load_relay_config — Inhalte werden nie geloggt)."""
    try:
        with open(_device_keys_path(), "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def _store_device_keys(entry: dict) -> None:
    """Phone-Device-Keys speichern — Datei 0600 (Muster relay-config.json),
    Private Keys gibt es hier nie (nur Pubs).

    SEC-W1 D3a: Schreiben in Temp-Datei + atomares os.replace — das alte
    O_TRUNC auf der Bestandsdatei LEERTE die Registry physisch, wenn fchmod
    danach einen OSError warf (Probe: Datei hinterher leer). Jetzt bleibt
    der Alt-Bestand bei JEDEM Fehler unangetastet (fail-closed ohne
    Datenverlust). fchmod unter Windows = Best-Effort (mkstemp legt die
    Datei ohnehin 0600 an; AttributeError bewusst geschluckt).
    """
    path = _device_keys_path()
    payload = json.dumps(
        {"v": 1, "device": entry, "ts": int(time.time() * 1000)}, indent=2)
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(prefix=".relay-keys-", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            try:
                os.fchmod(fh.fileno(), 0o600)  # POSIX; Windows: Best-Effort
            except AttributeError:
                pass
            fh.write(payload + "\n")
        os.replace(tmp_path, path)
    except BaseException:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise


def _prune_crypto_sessions(state: _RelayState) -> None:
    """Abgelaufene E2E-Sessions (>24 h) + deren Replay-Tracking entfernen."""
    cutoff = time.time() - _crypto_session_max_age_seconds()
    expired = [sid for sid, entry in state.crypto_sessions.items()
               if entry["ts"] < cutoff]
    for sid in expired:
        del state.crypto_sessions[sid]
        # P1c: Fenster je (sid, richtung, rk) — ALLE Ketten-Schluessel raeumen
        for _k in [k for k in state.crypto_replay if k[0] == sid]:
            del state.crypto_replay[_k]


def _relay_replay_allow(state: _RelayState, session_id: str, direction: str,
                        n: int, ts_ms, ratchet_pub: str) -> bool:
    """Grob-Replay-Schutz je (Session, Richtung, RATCHET-KETTE): 100-Counter-
    Schiebefenster + 10-min-Freshness. True = durchlassen (die Endpunkte
    prüfen zusätzlich GCM + eigene Fenster — der Relay kann Tag-Werte nicht
    verifizieren).

    P1c-FIX (vc36-Drill 02.11.): hdr.n ist NUR je Kette (hdr.rk) eindeutig —
    nach einem DH-Schritt starten BEIDE Seiten legitim wieder bei n=0
    (FRAMELOG-Beweis: Phone n=0/pn=1 mit NEUEM rk nach Rotation, Agent n=0/
    pn=4 mit neuem rk). Der alte (session, richtung)-Schlüssel wertete jeden
    Post-Rotations-Counter als Duplikat → 429-Replay-Tod für die gesamte
    weitere Konversation. Fenster jetzt je Kette: Replay INNERHALB einer
    Kette bleibt gefangen; Alt-Ketten-Replays fängt der Endpunkt
    (seen-peer-pubs-Drop + GCM)."""
    # SEC-W1 W1A-4/B F-04: ts ist PFLICHT und muss endlich-numerisch sein —
    # fehlender/nicht-numerischer/NaN-ts übersprang bis jetzt die 10-min-
    # Prüfung komplett (Freshness-Defense deaktivierbar). Parität zur
    # Endpunkt-F3-Meldung (adapter decrypt_frame / Kotlin PhoneCryptoPeer).
    if not isinstance(ts_ms, (int, float)) or isinstance(ts_ms, bool) \
            or not math.isfinite(ts_ms):
        return False
    if ts_ms < time.time() * 1000 - _FRAME_MAX_AGE_MS:
        return False
    # SEC-W1 W1C-8: ts war nur gegen die VERGANGENHEIT geprüft — ein ts aus
    # der Zukunft (+1 h, PoC) passierte den Freshness-Check. Symmetrisches
    # ±10-min-Fenster (Obergrenze), Parität zur Untergrenze.
    if ts_ms > time.time() * 1000 + _FRAME_MAX_AGE_MS:
        return False
    if not isinstance(n, int) or isinstance(n, bool) or n < 0:
        return False
    # SEC-W1 B F-06: Riesen-n vergiftet das Fenster (fail-open für Zähler-
    # Gift). Oberhalb des Sanity-Caps = verwerfen statt max=n übernehmen.
    if n >= _REPLAY_N_MAX:
        return False
    if not isinstance(ratchet_pub, str) or not _HEX64_RE.fullmatch(ratchet_pub):
        return False  # fail-closed: hdr.rk ist Pflichtfeld (_validate_enc_frame)
    track = state.crypto_replay.setdefault(
        (session_id, direction, ratchet_pub),
        {"max": -1, "seen": deque(maxlen=_REPLAY_WINDOW)})
    if n > track["max"]:
        track["seen"].append(n)
        track["max"] = n
        return True
    if n in track["seen"]:
        return False  # Duplikat/Replay
    if n < track["max"] - _REPLAY_WINDOW:
        return False  # vor dem Fenster
    track["seen"].append(n)  # Lücke im Fenster = Out-of-Order, ok
    return True


def _validate_enc_frame(data: dict) -> Optional[str]:
    """Struktur-Check eines chat.*.enc-Frames. ct bleibt OPAK — der Relay
    entschlüsselt nie. Rückgabe: Fehlergrund (str) oder None (gültig)."""
    hdr = data.get("hdr")
    if not isinstance(hdr, dict):
        return "hdr fehlt"
    if not isinstance(hdr.get("rk"), str) or not _HEX64_RE.fullmatch(hdr["rk"]):
        return "hdr.rk kein 64-Hex"
    for field in ("n", "pn"):
        value = hdr.get(field, 0)
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            return "hdr-Zähler ungültig"
        # SEC-W1 B F-06: Sanity-Cap — Riesen-Zähler sind Zähler-Gift (PoC:
        # n=10^20 vergiftete das Relay-Replay-Fenster fail-open).
        if value >= _REPLAY_N_MAX:
            return "hdr-Zähler außerhalb des Sanity-Caps"
    nonce = data.get("nonce")
    if not isinstance(nonce, str):
        return "nonce fehlt"
    try:
        raw_nonce = base64.b64decode(nonce, validate=True)
    except Exception:
        return "nonce kein base64"
    if len(raw_nonce) != _ENC_NONCE_LEN:
        return "nonce nicht 12 Byte"
    ct = data.get("ct")
    if not isinstance(ct, str) or not ct:
        return "ct fehlt"
    try:
        raw_ct = base64.b64decode(ct, validate=True)
    except Exception:
        return "ct kein base64"
    if len(raw_ct) > _ENC_CT_MAX_BYTES:
        return "ct zu groß"
    if not isinstance(data.get("session_id"), str) or not data["session_id"].strip():
        return "session_id fehlt"
    # SEC-W1 B F-02: Lone-Surrogates (JSON-escaped) sind UTF-8-nicht-
    # codierbar und warfen erst im Adapter/Phone UnicodeEncodeError —
    # fail-closed bereits am Relay (alle chat.*.enc-Richtungen).
    if not _utf8_encodable(data["session_id"]):
        return "session_id nicht UTF-8-codierbar"
    return None


async def _enc_session_active(state: _RelayState, agent_id: str,
                             data: dict) -> Optional[dict]:
    """Bestätigte (hello_ack gesehene), nicht abgelaufene Session zum Frame —
    None = fail-closed (unbekannt/fremd/unbestätigt/älter als 24 h)."""
    session_id = data.get("session_id")
    if not isinstance(session_id, str) or not session_id.strip():
        return None
    async with state.broker_lock:
        _prune_crypto_sessions(state)
        session = state.crypto_sessions.get(session_id)
    if session is None or session["agent_id"] != agent_id or not session["acked"]:
        return None
    return session


async def _agent_has_e2e_session(state: _RelayState, agent_id: str) -> bool:
    """FIN-T1 RED-03: Hat dieser Agent IRGENDEINE bestätigte E2E-Session?

    Einheitliche Warheit für ALLE Klartext-Downgrade-Guards (chat.reply,
    chat.read, chat.activity am Agent-WS + /chat + /chat_event am HTTP-Weg).
    Vorher existierte der Guard nur für reply/event/chat — klare chat.read/
    chat.activity kamen während bestätigter Session durch (PoC red03:
    'gelesen'/'arbeitet…'-Status unverschlüsselt fälschbar = Session-Status-
    Spoofing). Parität zum F1-Guard (3488ec5): Klartext wird in E2E-Sessions
    verworfen, der Agent muss die .enc-Spiegel nutzen."""
    async with state.broker_lock:
        _prune_crypto_sessions(state)
        return any(entry["agent_id"] == agent_id and entry["acked"]
                   for entry in state.crypto_sessions.values())


def get_relay_url() -> str:
    with _relay_lock:
        s = _relay_instance
        port = s.port if s else int(os.getenv("ANDROID_RELAY_PORT", "8766"))
    return f"http://localhost:{port}"


def set_pairing_code(code: str) -> None:
    """Update the pairing code (e.g. when user reconnects with new code).

    SEC-W2 W2-1: Zwei Lücken der Pairing-Rotation sind geschlossen:
    (a) android_setup() rief nur start_relay() — das ist bei laufendem
        Relay ein stilles No-Op (android_relay.py start_relay: 'already
        running' → return), d.h. eine Code-Rotation (z. B. nach Leak des
        alten Codes) blieb WIRKUNGSLOS: der Relay validierte weiter gegen
        den ALTEN Code, während .env den NEUEN speicherte (Tool-Pfad
        verlor den Zugang, geleakter Alt-Code blieb der gültige).
        android_setup ruft jetzt nach start_relay zusätzlich
        set_pairing_code().
    (b) Re-Auth-Lücke: eine Phone-WS, die mit dem ALTEN Code authentifiziert
        wurde, überlebte die Rotation unbegrenzt (PoC: Verbindung blieb
        offen + sendefähig). Bei Code-WECHSEL wird die Phone-WS jetzt
        geschlossen — das Phone muss sich mit dem NEUEN Code neu verbinden
        (fail-closed; Agent-WS bleibt unberührt: Agent-Tokens rotieren
        über relay-config.json, nicht über den Pairing-Code).

    FIN-T1 RED-01: LEERE/Whitespace-Codes werden verworfen (einheitliche
    Verwerf-Route wie start_relay/android_setup) — der Alt-Code bleibt
    gültig, ein leerer Code rotiert NIEMALS den Relay-Auth-Zustand.
    """
    if not _valid_pairing_code(code):
        logger.error(
            "set_pairing_code: empty/invalid pairing code rejected "
            "(fail-closed) — the previous code stays valid")
        return
    with _relay_lock:
        state = _relay_instance
        changed = False
        if state is not None:
            changed = state.pairing_code != code
            state.pairing_code = code
            if changed:
                # FIN-T1 RED-02: Epochen-Bump in derselben kritischen Sektion
                # wie der Code-Wechsel — atomar gegenüber dem WS-Handler, der
                # die Epoche beim Token-Check UND bei der Registrierung liest.
                state.pairing_epoch += 1
        logger.info("Pairing code updated")
    if state is None or not changed:
        return
    # Kick der Phone-WS im Relay-Loop (Thread-sicher; Aufrufer ist Sync-Kontext)
    loop = state.loop
    if loop is None or state.thread is None or not state.thread.is_alive():
        return

    def _kick() -> None:
        asyncio.ensure_future(_cleanup_phone(state, reason="pairing code rotated"))

    try:
        loop.call_soon_threadsafe(_kick)
    except RuntimeError:
        pass  # Loop bereits zu — kein Phone mehr da, nichts zu kicken


# ── Background event-loop entry point ─────────────────────────────────────────


def _run_loop(state: _RelayState, ready: threading.Event) -> None:
    """Runs in the background thread — creates an event loop and serves."""
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    state.loop = loop
    state.phone_ws_lock = asyncio.Lock()
    state.pending_lock = asyncio.Lock()
    state.streams_lock = asyncio.Lock()
    state.agent_ws_lock = asyncio.Lock()
    state.broker_lock = asyncio.Lock()
    state.shutdown_event = asyncio.Event()

    try:
        loop.run_until_complete(_serve(state, ready))
    except Exception:
        logger.exception("Relay event loop crashed")
    finally:
        loop.run_until_complete(loop.shutdown_asyncgens())
        loop.close()


def _ssl_context() -> Optional["ssl.SSLContext"]:
    """Build SSL context from env vars if configured (W3-01 TLS-Schiene).

    Aktivierung: ANDROID_RELAY_TLS=1 + ANDROID_RELAY_CERT/ANDROID_RELAY_KEY
    (Pflicht-Konfig für den Domain-Betrieb). ANDROID_RELAY_TLS=1 ohne
    CERT/KEY ist ein Deploy-Fehler → RuntimeError (fail-closed: der Relay
    startet NICHT still als Plaintext). ANDROID_RELAY_TLS=0 erzwingt
    Plaintext (bewusster Kill-Switch für LAN-Interims/QA). Ohne Flag gilt
    CERT+KEY vorhanden = TLS an (klassische Aktivierung, Default bleibt
    Plaintext-fähig für das LAN-Interim — externer Host ohne TLS bleibt
    über das D5b-Gate in _serve verweigert).
    """
    import ssl as _ssl

    tls_flag = os.getenv("ANDROID_RELAY_TLS")
    cert_path = os.getenv("ANDROID_RELAY_CERT")
    key_path = os.getenv("ANDROID_RELAY_KEY")

    if tls_flag == "0":
        return None
    if tls_flag == "1" and not (cert_path and key_path):
        raise RuntimeError(
            "ANDROID_RELAY_TLS=1 without ANDROID_RELAY_CERT/ANDROID_RELAY_KEY — "
            "mandatory TLS configuration incomplete (W3-01). Provide a "
            "certificate and key or remove ANDROID_RELAY_TLS.")
    if not cert_path or not key_path:
        return None

    ctx = _ssl.SSLContext(_ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert_path, key_path)
    return ctx


# ── Relay HTTP route allowlist ────────────────────────────────────────────────

# HTTP bridge endpoints the relay will forward, and the method each accepts.
# Anything not listed here is refused, so a new CommandDispatcher route is not
# reachable through the relay until it is added — module level rather than a
# local inside _serve() so tests can assert on it. Keeping the two in step is
# what stops an endpoint existing on the phone but being silently unreachable
# through the default (relay) transport.
_ROUTES = {
# GET-only
"/ping":          "GET",
"/screen":        "GET",
"/screenshot":    "GET",
"/apps":          "GET",
"/current_app":   "GET",
"/notifications": "GET",
"/contacts":      "GET",
"/events":        "GET",
"/screen_hash":   "GET",
"/location":      "GET",
"/battery":       "GET",
"/widgets":       "GET",
"/mic_status":    "GET",
"/mic_file":      "GET",
"/files":         "GET",
"/files_search":  "GET",
"/files_count":   "GET",
"/files_permission": "BOTH",
"/file":          "GET",
# POST-only
"/tap":           "POST",
"/tap_text":      "POST",
"/type":          "POST",
"/swipe":         "POST",
"/open_app":      "POST",
"/press_key":     "POST",
"/scroll":        "POST",
"/wait":          "POST",
"/long_press":    "POST",
"/drag":          "POST",
"/describe_node": "POST",
"/find_nodes":    "POST",
"/diff_screen":   "POST",
"/pinch":         "POST",
"/send_sms":      "POST",
"/call":          "POST",
"/media":         "POST",
"/intent":        "POST",
"/broadcast":     "POST",
"/speak":         "POST",
"/stop_speaking": "POST",
"/screen_record": "POST",
"/events/stream": "POST",
"/mic_start":     "POST",
"/mic_stop":      "POST",
"/apk_install":   "POST",
"/files_push":    "POST",
"/photo":         "POST",
"/torch":         "POST",
"/network":       "GET",
"/volume":        "POST",
"/alarm":         "POST",
"/timer":         "POST",
"/notify_reply":  "POST",
"/files_delete":  "POST",
# v0.11.0: Health Connect (read-only) — POST-Route, eigentliche Capability "health"
"/health_data":   "POST",
# PH2-T1: Chat-Broker — in der Allowlist geführt, aber relay-nativ behandelt
# (kein Device-Forward ans Phone, siehe _RELAY_NATIVE_ROUTES).
"/chat":          "POST",
"/chat_event":    "POST",
# PH-3-T2: E2E-Key-Registry + X3DH-Handshake — relay-nativ (Broker-Routen).
"/key_publish":   "POST",
"/key_get":       "GET",
"/crypto_hello":  "POST",
# MEDIA-T1: Medien-/Datei-Versand im Chat (chunked, E2E-only) — relay-nativ
# (kein Device-Forward; Payload läuft DURCH die E2E-Ratchet-Sitzung).
"/media":         "POST",
# READ + WRITE
"/clipboard":     "BOTH",
}

# PH2-T1: Routen mit eigenem Handler — werden in _serve() nicht über den
# generischen Device-Command-Handler geforwardet, sondern vom Chat-Broker bedient.
# MEDIA-T1: /media (chunked, E2E-only) ist relay-nativ wie /chat.
_RELAY_NATIVE_ROUTES = frozenset(
    {"/chat", "/chat_event", "/key_publish", "/key_get", "/crypto_hello", "/media"})


async def _serve(state: _RelayState, ready: threading.Event) -> None:
    """Build the aiohttp app, start the site, and block until shutdown."""
    # v0.10.8-Deploy-Fix: aiohttp-Default client_max_size ist 1MB - grosse
    # POST-Bodies (files_push mit APK als base64) wurden still gekappt, die App
    # sah ein leeres JSON und antwortete 'Pflichtfelder fehlen'. 96MB deckt das
    # app-seitige 48MB-Dateilimit (x1.37 Base64) plus JSON-Overhead ab.
    app = web.Application(client_max_size=96 * 1024 * 1024)
    state.app = app

    # WebSocket endpoint
    async def websocket_handler(request: web.Request) -> web.WebSocketResponse:
        return await _handle_ws(request, state)

    app.router.add_get("/ws", websocket_handler)

    # PH2-T1: Agent-WebSocket (Outbound-Anmeldung je Agent mit eigenem Agent-
    # Token aus relay-config.json) + Chat-Broker-Routen.
    async def agent_ws_handler(request: web.Request) -> web.WebSocketResponse:
        return await _handle_agent_ws(request, state)

    async def chat_handler(request: web.Request) -> web.StreamResponse:
        return await _handle_chat(request, state)

    async def chat_event_handler(request: web.Request) -> web.StreamResponse:
        return await _handle_chat_event(request, state)

    async def media_handler(request: web.Request) -> web.StreamResponse:
        return await _handle_media(request, state)

    # PH-3-T2: Key-Registry-Routen (Phone-Seite, Pairing-Code-Auth) — der
    # Relay verwahrt NUR öffentliche Keys und leitet Handshake-Frames opak
    # weiter; er hat kein Private-Key-Material und kann Inhalte nie lesen.
    async def key_publish_handler(request: web.Request) -> web.StreamResponse:
        return await _handle_key_publish(request, state)

    async def key_get_handler(request: web.Request) -> web.StreamResponse:
        return await _handle_key_get(request, state)

    async def crypto_hello_handler(request: web.Request) -> web.StreamResponse:
        return await _handle_crypto_hello(request, state)

    app.router.add_get("/agent_ws", agent_ws_handler)
    app.router.add_post("/chat", chat_handler)
    app.router.add_post("/chat_event", chat_event_handler)
    app.router.add_post("/media", media_handler)
    app.router.add_post("/key_publish", key_publish_handler)
    app.router.add_get("/key_get", key_get_handler)
    app.router.add_post("/crypto_hello", crypto_hello_handler)

    # PH2-T1: Registry aus der Server-Config vorbefüllen — alle konfigurierten
    # Agenten stehen damit mit online=False bereit (Agent-Liste für die UI).
    agent_config = _load_relay_config()
    crypto_registry = _agent_crypto_registry(agent_config)
    for _aid in _agent_tokens(agent_config):
        state.agents[_aid] = {
            "display_name": _agent_display_name(agent_config, _aid),
            "online": False,
            "last_seen": 0.0,
            # PH-3: Registry-Fingerprint (public Info) — leer ohne Keys.
            "fingerprint": crypto_registry.get(_aid, {}).get("fingerprint", ""),
        }

    # Authenticated APK distribution for /apk_install self-update (FlipsiBridge).
    # Serves the newest APK in the relay's apk_dir; refuses without valid token.
    apk_dir = os.environ.get("ANDROID_RELAY_APK_DIR") or _default_state_dir()

    async def apk_latest(request: web.Request) -> web.StreamResponse:
        # v0.10.8 (Review-Fix): Auth gegen denselben Pairing-Code wie alle anderen
        # Routen (das alte separate Token war ein unthrottled Orakel),
        # konstante Zeit (seit v0.11.0 über _constant_time_eq — Bytes),
        # Rate-Limiting wie /ws (_auth_record_failure) mit IP-Helper.
        remote_ip = _effective_remote_ip(request)
        if _auth_is_blocked(remote_ip):
            raise web.HTTPTooManyRequests(text="Too many failed authentication attempts.")
        auth = request.headers.get("Authorization", "")
        supplied = auth.removeprefix("Bearer ").strip()
        if not supplied or not _constant_time_eq(supplied, state.pairing_code):
            _auth_record_failure(remote_ip)
            logger.warning("APK latest rejected — bad auth from %s", remote_ip)
            return web.json_response({"error": "Unauthorized"}, status=401)
        try:
            # v0.11.0 (Relay-Review): versionsbewusst wählen ("0.10.10" schlägt
            # "0.10.7"); mtime als Beiwert, bei nicht parsebaren Namen greift er.
            candidates = sorted(
                (f for f in os.listdir(apk_dir) if f.endswith(".apk")),
                key=lambda name: (
                    _apk_version_key(name),
                    os.path.getmtime(os.path.join(apk_dir, name)),
                ),
                reverse=True,
            )
        except FileNotFoundError:
            candidates = []
        if not candidates:
            logger.warning("APK latest: no APK available in %s", apk_dir)
            return web.json_response({"error": "No APK available"}, status=404)
        path = os.path.join(apk_dir, candidates[0])
        # v0.10.8: SHA-256 des APK als Header mitschicken — die App verifiziert
        # gegen DIESEN Server-Kontrollierten Wert (nie caller-supplied).
        # v0.11.0: Chunk-Lauf statt Ganzlesen in RAM.
        apk_sha = _sha256_file(path)
        stream = web.StreamResponse(
            status=200,
            headers={
                "Cache-Control": "no-store",
                "Content-Type": "application/vnd.android.package-archive",
                "Content-Disposition": f'attachment; filename="{candidates[0]}"',
                "Content-Length": str(os.path.getsize(path)),
                "X-APK-SHA256": apk_sha,
            },
        )
        await stream.prepare(request)
        with open(path, "rb") as fh:
            while True:
                chunk = fh.read(64 * 1024)
                if not chunk:
                    break
                await stream.write(chunk)
        await stream.write_eof()
        return stream

    app.router.add_get("/apk/latest", apk_latest)


    for path, method in _ROUTES.items():
        if path in _RELAY_NATIVE_ROUTES:
            # PH2-T1: Broker-Routen haben oben eigene Handler — kein Forward.
            continue
        async def handler(request: web.Request, route_path: str = path) -> web.StreamResponse:
            return await _handle_http(request, state, route_path)

        if method in ("GET", "BOTH"):
            app.router.add_get(path, handler)
        if method in ("POST", "BOTH"):
            app.router.add_post(path, handler)

    runner = web.AppRunner(app)
    state.runner = runner
    await runner.setup()

    ssl_ctx = _ssl_context()
    host = os.getenv("ANDROID_RELAY_HOST", "localhost")
    # SEC-W1 D5b: Externer Host ohne TLS war nur eine Warnung — ein Deploy-
    # Tippfehler (ANDROID_RELAY_HOST=0.0.0.0 o. ä.) lieferte Pairing-Code +
    # Chat-Inhalte im Klartext. Fail-closed: explizites Opt-in nötig (gleiches
    # Muster wie FLIPSI_ALLOW_INSECURE im Adapter). W3-01: Der Live-Relay
    # läuft seit 03.11.2026 MIT nativem TLS (ANDROID_RELAY_TLS=1 in der
    # Unit) — das ALLOW_PLAINTEXT-Opt-in ist dort ENTFERNT, das Gate greift.
    if not ssl_ctx and host not in ("localhost", "127.0.0.1", "::1") \
            and os.getenv("ANDROID_RELAY_ALLOW_PLAINTEXT") != "1":
        raise RuntimeError(
            "ANDROID_RELAY_HOST != localhost without TLS is refused — the pairing "
            "code and chat contents would otherwise travel in cleartext. Configure "
            "TLS via ANDROID_RELAY_CERT/ANDROID_RELAY_KEY, or for a private LAN "
            "explicitly set ANDROID_RELAY_ALLOW_PLAINTEXT=1.")
    site = web.TCPSite(runner, host, state.port, ssl_context=ssl_ctx)
    state.site = site
    await site.start()

    scheme = "https" if ssl_ctx else "http"
    logger.info("Relay listening on %s://%s:%d", scheme, host, state.port)

    if not ssl_ctx and host != "localhost":
        logger.warning(
            "⚠️  TLS is NOT configured — all traffic (including pairing tokens) is "
            "sent in cleartext. Set ANDROID_RELAY_CERT and ANDROID_RELAY_KEY env vars "
            "to enable TLS. Binding to %s without TLS is insecure for internet-facing use.",
            host,
        )
    ready.set()

    # Block until shutdown is signalled
    await state.shutdown_event.wait()

    # Cleanup
    await _cleanup_phone(state, reason="relay shutdown")
    await runner.cleanup()
    logger.info("Relay server cleaned up")


# ── Rate limiting for WebSocket auth ─────────────────────────────────────────

_AUTH_MAX_ATTEMPTS = 5  # max failed attempts before blocking
_AUTH_WINDOW_SECONDS = 60  # sliding window for counting failures
_AUTH_BLOCK_SECONDS = 300  # how long to block an IP (5 minutes)
_AUTH_CLEANUP_INTERVAL = 120  # seconds between cleanup sweeps

# {ip: [timestamp, timestamp, ...]} — tracks failed auth attempt times per IP
_auth_failures: dict[str, list[float]] = {}
# {ip: unblock_timestamp} — IPs currently blocked
_auth_blocked: dict[str, float] = {}
_auth_lock = threading.Lock()
_auth_last_cleanup: float = 0.0


def _auth_cleanup() -> None:
    """Remove expired entries from the failure and block dicts."""
    global _auth_last_cleanup
    now = time.monotonic()
    if now - _auth_last_cleanup < _AUTH_CLEANUP_INTERVAL:
        return
    _auth_last_cleanup = now

    # Remove expired blocks
    expired_blocks = [ip for ip, until in _auth_blocked.items() if now >= until]
    for ip in expired_blocks:
        del _auth_blocked[ip]

    # Remove stale failure windows
    cutoff = now - _AUTH_WINDOW_SECONDS
    stale = []
    for ip, timestamps in _auth_failures.items():
        _auth_failures[ip] = [t for t in timestamps if t > cutoff]
        if not _auth_failures[ip]:
            stale.append(ip)
    for ip in stale:
        del _auth_failures[ip]


def _auth_is_blocked(ip: str) -> bool:
    """Check whether *ip* is currently blocked. Also triggers periodic cleanup."""
    now = time.monotonic()
    with _auth_lock:
        _auth_cleanup()
        until = _auth_blocked.get(ip)
        if until is not None:
            if now < until:
                return True
            # Block expired — remove it
            del _auth_blocked[ip]
    return False


def _auth_record_failure(ip: str) -> None:
    """Record a failed auth attempt for *ip*; block if threshold reached."""
    now = time.monotonic()
    with _auth_lock:
        timestamps = _auth_failures.setdefault(ip, [])
        timestamps.append(now)
        # Prune timestamps outside the window
        cutoff = now - _AUTH_WINDOW_SECONDS
        _auth_failures[ip] = [t for t in timestamps if t > cutoff]

        if len(_auth_failures[ip]) >= _AUTH_MAX_ATTEMPTS:
            _auth_blocked[ip] = now + _AUTH_BLOCK_SECONDS
            _auth_failures.pop(ip, None)
            logger.warning(
                "IP %s blocked for %ds after %d failed auth attempts",
                ip,
                _AUTH_BLOCK_SECONDS,
                _AUTH_MAX_ATTEMPTS,
            )


# ── WebSocket handler (phone side) ───────────────────────────────────────────


def _mask_token(token: str) -> str:
    # Never emit even a prefix: the pairing token grants full device control.
    return "****"


# Request-body fields that may carry PII or secrets (phone numbers, SMS text,
# typed text incl. passwords/OTPs, clipboard content, intent extras).
# AGENTS.md: strip phone numbers, recipients, location from tool logs.
_SENSITIVE_BODY_KEYS = frozenset(
    {"to", "number", "body", "text", "message", "extras", "data", "uri", "content"}
)


def _safe_body_repr(body) -> str:
    """JSON repr of a request body with PII-bearing fields redacted.

    v0.10.9-Sicherheitsfix: body kann auch non-dict JSON sein (Array/String/Zahl)
    — vorher AttributeError -> 500. Jetzt defensiv typisieren.
    """
    if not body:
        return "{}"
    if not isinstance(body, dict):
        # SEC-W1 D1: non-dict-Bodies landeten bis 120 Zeichen KLAR im
        # Debug-Log (Arrays/Strings mit Passwörtern/OTPs) — jetzt NUR Typ
        # + Länge, kein Inhalt (PII-Regel ohne Schlupfloch).
        try:
            raw = json.dumps(body)
        except (TypeError, ValueError):
            raw = ""
        return f"<non-dict:{type(body).__name__}> ({len(raw)} bytes)"
    redacted = {
        k: "<redacted>" if k in _SENSITIVE_BODY_KEYS else v for k, v in body.items()
    }
    return json.dumps(redacted, default=str)[:200]


async def _handle_ws(request: web.Request, state: _RelayState) -> web.WebSocketResponse:
    # Rate limiting — check before token validation
    # v0.11.0: IP-Helper (X-Forwarded-For-Bewusstsein, Security-Review HOCH-1)
    remote_ip = _effective_remote_ip(request)
    if _auth_is_blocked(remote_ip):
        logger.warning("Auth attempt from blocked IP %s — returning 429", remote_ip)
        raise web.HTTPTooManyRequests(
            text="Too many failed authentication attempts. Try again later."
        )

    # Authorization header only — the ?token= query string fallback was removed
    # because query strings are logged verbatim by reverse proxies (nginx,
    # Cloudflare, Apache), leaking the pairing code into plaintext access logs.
    auth_header = request.headers.get("Authorization", "")
    if auth_header.startswith("Bearer "):
        token = auth_header.removeprefix("Bearer ").strip()
    else:
        token = ""
    # Constant-time comparison to mitigate timing side-channel attacks that
    # could otherwise leak the pairing code byte-by-byte.
    # PairingManager generates uppercase-only codes — compare exact-case to
    # preserve full key-space entropy.
    if not _constant_time_eq(token, state.pairing_code):
        _auth_record_failure(remote_ip)
        logger.warning(
            "Phone WS rejected — bad token (got %s) from %s", _mask_token(token), remote_ip
        )
        raise web.HTTPForbidden(text="Invalid pairing code")

    # FIN-T1 RED-02: Epoche des MOMENTAN gültigen Codes merken — die
    # Registrierung weiter unten verifiziert sie unter phone_ws_lock erneut.
    pairing_epoch = state.pairing_epoch

    # 02.11.26 Security-Audit-Fix (REL-04/Skeptiker-P2): >4MB-Frames rissen die
    # WS-Verbindung (Journal-Beweis 04:08:41). Jetzt hartes Frame/Message-Limit
    # + receive_timeout, damit ein dekompressionsbomber-Frame stattdessen sauber
    # abgewiesen wird (App bekommt 1009-Code statt toter Socket).
    ws = web.WebSocketResponse(
        heartbeat=15.0,
        max_msg_size=2 * 1024 * 1024,   # 2MB — Screenshots/Streams laufen als HTTP, nie als WS-Frame
        receive_timeout=300.0,           # tote Sockets nach 5 min schlafen legen
    )
    await ws.prepare(request)

    # Only one phone at a time — kick previous if any
    async with state.phone_ws_lock:
        # FIN-T1 RED-02: Rotation-TOCTOU — eine Rotation, die ZWISCHEN dem
        # Token-Check oben und dieser Registrierung landet (Handshake noch in
        # der Luft, await ws.prepare = await-Punkt), wurde vom W2-1-Kick
        # verfehlt (_cleanup_phone lief bei phone_ws=None → No-Op) und die
        # Alt-Code-WS registrierte sich DANACH als aktive Phone-WS unter dem
        # NEUEN Code. Verifizierung von Epoche UND Code in derselben kritischen
        # Sektion wie die Registrierung: Alt-Code-WS wird geschlossen und kann
        # NIE die aktive Phone-WS werden (fail-closed, W2-1-Vertrag).
        if (state.pairing_epoch != pairing_epoch
                or not _constant_time_eq(token, state.pairing_code)):
            logger.warning(
                "Phone WS rejected — pairing code rotated during the "
                "handshake (TOCTOU window closed)")
            await ws.close(code=aiohttp.WSCloseCode.POLICY_VIOLATION,
                           message=b"pairing code rotated")
            return ws
        if state.phone_ws is not None and not state.phone_ws.closed:
            logger.info("Replacing previous phone connection")
            await _fail_inflight(state, reason="phone connection replaced")
            await state.phone_ws.close(
                code=aiohttp.WSCloseCode.GOING_AWAY, message=b"replaced"
            )
        state.phone_ws = ws

    logger.info("Phone connected from %s", request.remote)

    # PH2-MERGE: relay.state-Hallo-Frame VOR der Spool-Auslieferung — die App
    # bekommt die Agenten-Liste damit immer als allerersten Frame.
    await _send_phone_state(state, ws)

    # PH2-T1: Agent→App-Nachrichten aus der Offline-Spool-Outbox ausliefern.
    await _flush_phone_outbox(state)

    try:
        async for msg in ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                await _on_phone_message(state, msg.data)
            elif msg.type == aiohttp.WSMsgType.BINARY:
                await _on_phone_binary(state, msg.data)
            elif msg.type == aiohttp.WSMsgType.ERROR:
                logger.error("Phone WS error: %s", ws.exception())
                break
    finally:
        await _cleanup_phone(state, reason="phone disconnected", expected_ws=ws)

    return ws


async def _send_phone_state(state: _RelayState, ws: web.WebSocketResponse) -> None:
    """PH2-MERGE: relay.state-Hallo-Frame an die Phone-WS (Agenten-Liste).

    agents = [{agent_id, display_name, online}] aus der Registry — fehlen die
    Felder (leere Server-Config), bleibt die Liste leer: die App-UI fällt auf
    ihren Demo-Fallback zurück, kein Crash. Sendefehler = Log, die Verbindung
    bleibt bestehen (der Spool liefert den Rest).

    PH-3 F7 (Jäger): session_active je Agent = Wahrheit des Session-Brokers
    (bestätigte, nicht abgelaufene E2E-Session vorhanden?). Die App gleicht
    beim Re-Connect ihre lokalen Sessions gegen dieses Feld ab — ein vom
    Agenten gesendetes crypto.session_reset, das die Phone-WS nicht erreicht
    hat (Reconnect), wird so nachgeholt statt still weiterlaufen zu lassen.
    """
    if state.broker_lock is None:
        # Locks entstehen erst in _run_loop — vor dem Server-Start kann es
        # keine Sessions geben (Robustheit: NIE werfen, s. Presence-Test).
        e2e_agents: set[str] = set()
    else:
        async with state.broker_lock:
            _prune_crypto_sessions(state)
            e2e_agents = {
                entry["agent_id"]
                for entry in state.crypto_sessions.values()
                if entry["acked"]
            }
    async with state.agent_ws_lock:
        agents = [
            {
                "agent_id": agent_id,
                "display_name": str(entry.get("display_name") or agent_id),
                "online": bool(entry.get("online")),
                "session_active": agent_id in e2e_agents,
            }
            for agent_id, entry in state.agents.items()
        ]
    try:
        await ws.send_json({"type": "relay.state", "v": 1, "agents": agents})
    except Exception as exc:
        logger.warning("Phone: relay.state fehlgeschlagen (%s)", exc)


async def _broadcast_agent_presence(state: _RelayState) -> None:
    """PH2-FIX (E2E P2): relay.state je Agent-Presence-Wechsel ans Phone.

    Agent-Connect (online=True) und Disconnect (offline) ändern die Registry —
    ohne Push erfuhr die App einen Wechsel nur beim nächsten Phone-Connect
    (Hallo-Frame), der Header erstarrete auf 'Offline', während der Agent
    online antwortete (E2E-Fund e2e-c). Der Frame nutzt exakt die Payload-Form
    des Hallo-Frames (_send_phone_state) — die App parst relay.state bereits,
    kein App-Change nötig. Keine offene Phone-WS = No-Op (bewusst KEIN Spool:
    der nächste Connect bekommt den frischen Zustand als Hallo-Frame).
    Sendefehler je WS schluckt _send_phone_state (Log), der Presence-Wechsel
    bleibt wirksam.
    """
    lock = state.phone_ws_lock
    if lock is None:
        # Locks entstehen erst in _run_loop — vor dem Server-Start gibt es
        # keine Phone-WS, der Push ist dann ohnehin ein No-Op.
        return
    async with lock:
        ws = state.phone_ws
        if ws is None or ws.closed:
            return
    await _send_phone_state(state, ws)


async def _on_phone_message(state: _RelayState, raw: str) -> None:
    """Route an incoming message from the phone to the matching pending future."""
    try:
        data = json.loads(raw)
    except (json.JSONDecodeError, RecursionError):
        # SEC-W1 B F-05: tief verschachteltes JSON (PoC: "["*200000, trotz
        # 2-MB-Grenze ~1M Ebenen möglich) wirft RecursionError, KEINE
        # JSONDecodeError — der entkam dem Handler und riss die Phone-WS.
        # SEC-W2 W2-2: Junk-Warnung je Frame war UNDROSSELT (Log-Flood);
        # nur die ersten _AGENT_LIGHT_FRAME_MAX_PER_SECOND je Sekunde loggen.
        if _chat_rate_allow("phone:junk", _AGENT_LIGHT_FRAME_MAX_PER_SECOND):
            logger.warning(
                "Non-JSON message from phone (len=%d) — Inhalt nicht geloggt (PII-Regel)",
                len(raw))
        return

    # SEC-W2 W2-2: Frame-Typ-Konfusion — json.loads liefert auch Arrays/
    # Strings/Zahlen; data.get() riss mit AttributeError die Phone-WS
    # (PoC: send_str("[1,2,3]") → 'list' object has no attribute 'get',
    # Handler-Tod + CLOSE). Parität zur Agent-Seite (isinstance-Guard) und
    # zum W1B-F-02-Adapter-Fix: fail-closed verwerfen statt crashen.
    if not isinstance(data, dict):
        if _chat_rate_allow("phone:junk", _AGENT_LIGHT_FRAME_MAX_PER_SECOND):
            logger.warning("Non-object message from phone — verworfen")
        return

    request_id = data.get("request_id")
    if not request_id:
        if _chat_rate_allow("phone:junk", _AGENT_LIGHT_FRAME_MAX_PER_SECOND):
            logger.warning(
                "Phone message missing request_id (len=%d) — Inhalt nicht geloggt (PII-Regel)",
                len(raw))
        return

    async with state.streams_lock:
        stream_queue = state.streams.get(request_id)
    if stream_queue is not None:
        # v0.11.0 (HOL-Fix): nie auf einen vollen Puffer warten — Textframes
        # sind klein, aber das Prinzip gilt: put_nowait, Überlauf = Abbruch.
        try:
            stream_queue.put_nowait(("message", data))
            return
        except asyncio.QueueFull:
            try:
                stream_queue.get_nowait()
                stream_queue.put_nowait(
                    ("error", "Relay: Stream-Puffer voll — Stream abgebrochen")
                )
            except (asyncio.QueueEmpty, asyncio.QueueFull):
                pass
            async with state.streams_lock:
                if state.streams.get(request_id) is stream_queue:
                    state.streams.pop(request_id, None)
            return

    async with state.pending_lock:
        future = state.pending.pop(request_id, None)

    if future is None:
        logger.debug(
            "No pending future for request_id=%s (possibly timed out)", request_id
        )
        return

    if not future.done():
        future.set_result(data)


def _decode_stream_frame(raw: bytes) -> tuple[str, bytes]:
    """Decode a binary phone frame: uint16 request-id length, UTF-8 id, payload."""
    if len(raw) < 3:
        raise ValueError("Binary stream frame is too short")
    request_id_length = int.from_bytes(raw[:2], byteorder="big", signed=False)
    if request_id_length < 1 or request_id_length > 128:
        raise ValueError("Binary stream frame has an invalid request-id length")
    payload_offset = 2 + request_id_length
    if payload_offset > len(raw):
        raise ValueError("Binary stream frame is truncated")
    request_id = raw[2:payload_offset].decode("utf-8")
    return request_id, raw[payload_offset:]


async def _on_phone_binary(state: _RelayState, raw: bytes) -> None:
    try:
        request_id, payload = _decode_stream_frame(raw)
    except (UnicodeDecodeError, ValueError) as exc:
        logger.warning("Invalid binary stream frame from phone: %s", exc)
        return

    async with state.streams_lock:
        stream_queue = state.streams.get(request_id)
    if stream_queue is None:
        logger.debug("No active stream for request_id=%s", request_id)
        return
    # v0.11.0 (HOL-Fix, Relay-Review F2): put_nowait — der WS-Read-Loop darf
    # NIE auf einen langsamen HTTP-Konsumenten blockieren, sonst friert EIN
    # langsamer /file-Download ALLE anderen Relay-Requests ein. Puffer voll →
    # Stream bewusst abbrechen (Konsument sieht den Fehler und wiederholt).
    try:
        stream_queue.put_nowait(("chunk", payload))
        return
    except asyncio.QueueFull:
        pass
    try:
        stream_queue.get_nowait()
        stream_queue.put_nowait(
            ("error", "Relay: Stream-Puffer voll (langsamer Konsument) — Stream abgebrochen")
        )
    except (asyncio.QueueEmpty, asyncio.QueueFull):
        pass
    async with state.streams_lock:
        if state.streams.get(request_id) is stream_queue:
            state.streams.pop(request_id, None)


async def _cleanup_phone(
    state: _RelayState,
    reason: str = "",
    expected_ws: Optional[web.WebSocketResponse] = None,
) -> None:
    """Clean up phone connection and cancel all pending requests."""
    async with state.phone_ws_lock:
        # A newly connected phone may already have replaced this socket. The
        # old handler must not tear down the replacement or its active requests.
        if expected_ws is not None and state.phone_ws is not expected_ws:
            return
        ws = state.phone_ws
        state.phone_ws = None

    if ws is not None and not ws.closed:
        await ws.close()

    await _fail_inflight(state, reason)


async def _fail_inflight(state: _RelayState, reason: str) -> None:
    """Fail requests that were assigned to a phone connection that is gone."""
    # Fail all pending futures
    async with state.pending_lock:
        pending = dict(state.pending)
        state.pending.clear()

    for rid, fut in pending.items():
        if not fut.done():
            fut.set_exception(ConnectionError(f"Phone disconnected ({reason})"))

    async with state.streams_lock:
        stream_queues = list(state.streams.values())
        state.streams.clear()
    for queue in stream_queues:
        while not queue.empty():
            try:
                queue.get_nowait()
            except asyncio.QueueEmpty:
                break
        queue.put_nowait(("error", f"Phone disconnected ({reason})"))

    if pending:
        logger.info("Cancelled %d pending requests (%s)", len(pending), reason)


# ── PH2-T1: Agent-Registry & Chat-Broker (App ↔ Agent) ────────────────────────

# Chat-Rate-Limit (Sliding Window je Schlüssel) — Schlüssel je Route+Absender.
_chat_rate_lock = threading.Lock()
_chat_rate_hits: dict[str, list[float]] = {}


def _chat_rate_allow(key: str, per_second: int) -> bool:
    """Höchstens per_second Treffer je 1s-Fenster je key; False = über Limit."""
    now = time.monotonic()
    with _chat_rate_lock:
        hits = [t for t in _chat_rate_hits.get(key, []) if now - t <= 1.0]
        if len(hits) >= per_second:
            _chat_rate_hits[key] = hits
            return False
        hits.append(now)
        _chat_rate_hits[key] = hits
        # SEC-W1 W1C-7: Key-Sweep — leere/stale Listen wurden NIE gelöscht
        # (langsam unbegrenztes Wachstum, 100 IPs = 101 Keys für immer; nur
        # mit Pairing-Code/Agent-Token erreichbar, aber langlebig).
        if len(_chat_rate_hits) > _CHAT_RATE_MAX_KEYS:
            cutoff = now - 1.0
            for stale_key in [k for k, v in _chat_rate_hits.items()
                              if not any(t > cutoff for t in v)]:
                del _chat_rate_hits[stale_key]
        return True


def _prune_chat_queue(queue: deque, retention_seconds: float) -> int:
    """Einträge älter als die Retention entfernen; Rückgabe = Anzahl entfernter."""
    cutoff = time.time() - retention_seconds
    dropped = 0
    while queue and queue[0][0] < cutoff:
        queue.popleft()
        dropped += 1
    if dropped:
        logger.info("Chat-Spool: %d abgelaufene Nachricht(en) entfernt", dropped)
    return dropped


def _bearer_token(request: web.Request) -> str:
    """Bearer-Token aus dem Authorization-Header (kein Query-Fallback)."""
    auth_header = request.headers.get("Authorization", "")
    if auth_header.startswith("Bearer "):
        return auth_header.removeprefix("Bearer ").strip()
    return ""


def _resolve_agent_by_token(config: dict, supplied: str) -> Optional[str]:
    """Passende agent_id zum gelieferten Agent-Token finden; None = kein Treffer."""
    if not supplied:
        return None
    for agent_id, token in _agent_tokens(config).items():
        if _constant_time_eq(supplied, token):
            return agent_id
    return None


async def _handle_agent_ws(request: web.Request, state: _RelayState) -> web.WebSocketResponse:
    """Outbound-WS eines Agenten: Anmeldung, Presence, Spool-Auslieferung."""
    remote_ip = _effective_remote_ip(request)
    if _auth_is_blocked(remote_ip):
        logger.warning("Agent WS attempt from blocked IP %s — 429", remote_ip)
        raise web.HTTPTooManyRequests(
            text="Too many failed authentication attempts. Try again later."
        )

    supplied = _bearer_token(request)
    config = _load_relay_config()
    agent_id = _resolve_agent_by_token(config, supplied)
    if agent_id is None:
        _auth_record_failure(remote_ip)
        logger.warning("Agent WS rejected — bad agent token from %s", remote_ip)
        raise web.HTTPUnauthorized(text="Invalid agent token")

    ws = web.WebSocketResponse(
        heartbeat=15.0,
        max_msg_size=2 * 1024 * 1024,
        # Agent-Keep-Alive-Raster = 30 s → zwei verpasste Frames beenden den Socket.
        receive_timeout=60.0,
    )
    await ws.prepare(request)

    # Je Agent EINE Verbindung — eine alte Verbindung wird ersetzt (wie Phone).
    async with state.agent_ws_lock:
        previous = state.agent_ws.get(agent_id)
        if previous is not None and not previous.closed:
            logger.info("Replacing previous agent connection (%s)", agent_id)
            await previous.close(
                code=aiohttp.WSCloseCode.GOING_AWAY, message=b"replaced"
            )
        state.agent_ws[agent_id] = ws
        state.agents[agent_id] = {
            "display_name": _agent_display_name(config, agent_id),
            "online": True,
            "last_seen": time.time(),
            # PH-3: Registry-Fingerprint (public Info) — leer ohne Identity-Keys.
            "fingerprint": _agent_crypto_registry(config).get(agent_id, {}).get("fingerprint", ""),
        }

    logger.info("Agent %s connected from %s", agent_id, request.remote)

    # PH2-FIX (E2E P2): Presence-Wechsel online=True → relay.state an die
    # Phone-WS (App-Header folgt dem Agent-Status live).
    await _broadcast_agent_presence(state)

    # Offline gesammelte App→Agent-Nachrichten ausliefern (Retention s. Config).
    await _flush_agent_queue(state, agent_id, ws)

    try:
        async for msg in ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                await _on_agent_message(state, agent_id, msg.data)
            elif msg.type == aiohttp.WSMsgType.BINARY:
                logger.debug("Agent %s: Binär-Frame (Phase-2-Basis ignoriert)", agent_id)
            elif msg.type == aiohttp.WSMsgType.ERROR:
                logger.error("Agent %s WS error: %s", agent_id, ws.exception())
                break
    finally:
        async with state.agent_ws_lock:
            # PH2-FIX (Jäger B1): Presence-Guard — offline NUR wenn DIESER
            # Socket noch der aktuelle ist (gleicher Guard wie der pop).
            # Bei WS-Replace (neue Verbindung lebt schon) bleibt der Agent
            # online: KEIN offline-Flag, KEIN Presence-Push, KEIN Spinner-
            # Reset — die neue Verbindung übernimmt den Zustand nahtlos.
            is_current = state.agent_ws.get(agent_id) is ws
            if is_current:
                state.agent_ws.pop(agent_id, None)
                entry = state.agents.get(agent_id)
                if entry is not None:
                    entry["online"] = False
                    entry["last_seen"] = time.time()

        if is_current:
            # PH2-FIX (E2E P2): Presence-Wechsel offline → relay.state an die
            # Phone-WS, damit der App-Header nicht auf 'Online' erstarrete.
            await _broadcast_agent_presence(state)
            # PH2-FIX (Jäger B5): Agent-WS weg → laufende 'arbeitet …'-Spinner
            # je chat_id des Agenten zurücksetzen + activity-false ans Phone,
            # sonst bleibt der App-Header nach einem Agent-Crash stecken.
            await _reset_agent_activity(state, agent_id)

    return ws


# PH2-FIX (Jäger B2): FIFO-Cap der Broker-Queues (Phone-Outbox + je-Agent-
# Inbound). 500 Frames reichen für jeden Offline-Spool-Drill; ältere fallen
# bei Überlauf automatisch raus (bounded memory, kein unlimitiertes Wachstum).
_CHAT_QUEUE_MAXLEN = 500

# PH2-MERGE: Frame-Allowlist der Agent-WS — ALLES andere wird geloggt und
# verworfen (kein Crash, keine Broker-Berührung). keepalive/ping = Presence-
# Raster seit PH2-T1; presence = expliziter Presence-Frame; chat.reply =
# Agent→Phone-Antwort (Bridge wie POST /chat_event); chat.read = Lese-
# bestätigung (PH2-T10: Status=read + chat.status-Frame ans Phone); chat.
# activity = Arbeitsstatus (PH2-T10: je Wechsel ein chat.activity-Frame,
# dedupliziert). read/activity sind bewusst NICHT rate-limited.
# PH-FIX-BACKEND-B (02.11.2026, PIPELINE-BUGS „Kosmetik“-Fund): agent.register
# ist allowlisted — der Adapter sendet ihn JEDES Connect/Reconnect (adapter.py
# _read_ws_frames/send_json). Auth läuft NUR über den WS-Connect-Bearer, der
# Frame ist reine Anzeige-Metadaten: display_name übernehmen (Config-Wert der
# relay-config bleibt Fallback), kein Verwerfen mehr — das Log-Rauschen
# („Frame type='agent.register' verworfen“ je Connect) verschleierte echte
# Warnungen.
_AGENT_FRAME_TYPES = frozenset(
    {"keepalive", "ping", "presence", "agent.register",
     "chat.reply", "chat.read", "chat.activity",
     # PH-3-T2: E2E-Frames — der Relay ist opaker Pass-Through (kein Key-
     # Material, ct wird nie geöffnet); Validierung = Struktur + Session.
     "crypto.hello_ack", "crypto.session_reset",
     "chat.reply.enc", "chat.read.enc", "chat.activity.enc",
     # MEDIA-T1: chunked Media (Agent→Phone, opak) + Chunk-Ack (klar, light).
     "chat.media.enc", "chat.media.chunk_ack"}
)

# SIRS-01 (Volltest 03.11.2026): Allowlist der session_reset-Reasons, die der
# Relay 1:1 ans Phone durchreicht. "agent-session-lost" = benigner Reset nach
# Agent-Restart (App: neutraler Hinweis + sofortiger Neu-Handshake statt
# MitM-Warnung); alles Unbekannte/fehlende = fail-closed "fingerprint-mismatch"
# (der bisherige hart-codierte Wert).
# FIX-WELLE-2 B8 (HERZ-Befund 8): "cross-session-takeover" = fremde Session-
# ID-Frame bei aktiver Session (zweite Pairing-Code-Inhaber-Session hat die
# Phone-Session verdrängt / Cross-Session-Replay-Sonde) — die App zeigt eine
# Takeover-Warnung (KEINE MitM-Sprache) und verwirft die alte Session; der
# User verbindet neu (Auto-Heal würde sofort wieder verdrängt werden).
_RESET_REASONS = frozenset({"fingerprint-mismatch", "agent-session-lost",
                            "cross-session-takeover"})

# PH2-T10: Rangfolge der Chat-Status — nur aufsteigende Übergänge
# (queued → delivered → read) erzeugen einen neuen chat.status-Frame.
_CHAT_STATUS_ORDER = {"queued": 0, "delivered": 1, "read": 2}


async def _on_agent_message(state: _RelayState, agent_id: str, raw: str) -> None:
    """Presence-Pflege je Agent-Frame; Keep-Alive = {"type": "keepalive"} je 30 s.

    PH2-MERGE: chat.reply-Frames ({chat_id, text}) wandern mit identischer
    Semantik wie ein POST /chat_event in den Phone-Broker (Spool+Retention,
    chat.event-Frame an die Phone-WS, gemeinsames Rate-Limit je Agent).
    PH2-T10: chat.read/chat.activity pflegen den Broker-Status und sind
    NICHT rate-limited (Lese-/Arbeitsstatus ≠ Chat-Inhalt).
    """
    try:
        data = json.loads(raw)
    except (json.JSONDecodeError, RecursionError):
        # SEC-W1 B F-05: RecursionError entkam dem Handler → Agent-WS-Reset
        # (authentifizierter Selbst-DoS). Parität zu _on_phone_message.
        logger.warning(
            "Agent %s: non-JSON message (len=%d) — Inhalt nicht geloggt", agent_id, len(raw)
        )
        return

    async with state.agent_ws_lock:
        entry = state.agents.get(agent_id)
        if entry is not None:
            entry["last_seen"] = time.time()

    msg_type = data.get("type") if isinstance(data, dict) else None
    if msg_type not in _AGENT_FRAME_TYPES:
        # SEC-W2 W2-2: Junk-Frames waren UNDROSSELT und loggten den Typ per
        # %r UNGEKÜRZT (PoC: 20 Frames mit 100k-Zeichen-Typ = 20 Log-Zeilen
        # à 100 KB — 2-MB-Frames = 2-MB-Log-Zeilen je Frame, Disk-Fill in
        # Minuten). Cap wie W1C-9 (10/s) + _log_id-Kürzung auf 64 Zeichen.
        if not _chat_rate_allow(f"agentjunk:{agent_id}",
                                _AGENT_LIGHT_FRAME_MAX_PER_SECOND):
            logger.debug(
                "Agent %s: Junk-Frame über Cap — verworfen (Log-Drossel)", agent_id)
            return
        logger.warning(
            "Agent %s: Frame type=%r verworfen (Allowlist: %s)",
            agent_id, _log_id(msg_type, 64), sorted(_AGENT_FRAME_TYPES),
        )
        return
    # SEC-W1 W1C-9: Flut-Schutz je Agent — keepalive/ping/presence/agent.register
    # waren/ sind unlimitiert (~76.000 Frames/s gemessen: je Frame JSON-Parse +
    # Lock + last_seen-Write = Loop-Monopolisierung durch authentizierten Agent).
    if msg_type in ("keepalive", "ping", "presence", "agent.register"):
        if not _chat_rate_allow(f"agentframes:{agent_id}",
                                _AGENT_LIGHT_FRAME_MAX_PER_SECOND):
            logger.debug(
                "Agent %s: keepalive/ping/presence/agent.register über Frame-Cap — verworfen",
                agent_id)
            return
    # PH-FIX-BACKEND-B: agent.register = Display-Name-Pflege (kein Verwerfen).
    if msg_type == "agent.register":
        await _handle_agent_register(state, agent_id, data)
        return
    # PH-3-T2: E2E-Frames zuerst (opaker Pass-Through + Session-Broker).
    if msg_type == "crypto.hello_ack":
        await _handle_agent_hello_ack(state, agent_id, data)
        return
    if msg_type == "crypto.session_reset":
        await _handle_agent_session_reset(state, agent_id, data)
        return
    if msg_type == "chat.reply.enc":
        await _handle_agent_chat_reply_enc(state, agent_id, data)
        return
    if msg_type == "chat.read.enc":
        await _handle_agent_chat_read_enc(state, agent_id, data)
        return
    if msg_type == "chat.activity.enc":
        await _handle_agent_chat_activity_enc(state, agent_id, data)
        return
    # MEDIA-T1: chunked Media Agent→Phone (opak) + Chunk-Ack (klar, light).
    if msg_type == "chat.media.enc":
        await _handle_agent_media_enc(state, agent_id, data)
        return
    if msg_type == "chat.media.chunk_ack":
        await _handle_agent_media_ack(state, agent_id, data)
        return
    if msg_type == "chat.reply":
        await _handle_agent_chat_reply(state, agent_id, data)
        return
    if msg_type == "chat.read":
        await _handle_agent_chat_read(state, agent_id, data)
        return
    if msg_type == "chat.activity":
        await _handle_agent_chat_activity(state, agent_id, data)
        return
    # keepalive/ping/presence: nur Presence (oben bereits gepflegt).
    logger.debug("Agent %s: frame type=%r (Presence)", agent_id, msg_type)


async def _handle_agent_register(state: _RelayState, agent_id: str, data: dict) -> None:
    """PH-FIX-BACKEND-B: agent.register — display_name übernehmen, nichts verwerfen.

    Authentifizierung läuft AUSSCHLIESSLICH über den WS-Connect-Bearer
    (relay-config ANDROID_AGENT_TOKEN_<ID>); der Frame ist KEIN Auth-Mittel
    und kein Registrierungsersatz — die WS-Identität (agent_id des Sockets)
    gewinnt, eine im Frame mitgeschickte agent_id wird ignoriert. Zweck ist
    einzig die Anzeige-Metadaten-Pflege: der Adapter meldet seinen
    FLIPSI_DISPLAY_NAME (Config-Wert der relay-config bleibt Erst-Fallback
    beim Connect). Cap 64 = Log/DOM-Hygiene (_log_id-Spiegel). Presence
    wird nur bei TATSÄCHLICHER Änderung gepusht (kein Frame-Sturm je
    Reconnect). last_seen pflegt der Aufrufer (_on_agent_message) bereits.
    """
    raw_name = data.get("display_name")
    if not isinstance(raw_name, str) or not raw_name.strip():
        logger.debug("Agent %s: agent.register ohne display_name — No-Op", agent_id)
        return
    display_name = raw_name.strip()[:64]
    async with state.agent_ws_lock:
        entry = state.agents.get(agent_id)
        if entry is None or entry.get("display_name") == display_name:
            return
        entry["display_name"] = display_name
    logger.info("Agent %s: display_name via agent.register aktualisiert", agent_id)
    await _broadcast_agent_presence(state)


async def _handle_agent_chat_reply(state: _RelayState, agent_id: str, data: dict) -> None:
    """PH2-MERGE: chat.reply-Frame → Phone-Broker — identisch zu /chat_event.

    Gleicher Rate-Limit-Bucket (chat_event:<agent_id>, Chat-Limit je Sekunde),
    gleiche Validierung (chat_id + nicht-leerer text), gleiche Zustellung:
    Phone online = chat.event-Frame an die Phone-WS, offline = 24h-Outbox.
    Ungültige Frames werden geloggt und verworfen — der Agent-Socket bleibt
    offen (kein Crash), der Agent kann sofort einen gültigen Frame nachlegen.
    """
    config = _load_relay_config()
    # PH-3: Downgrade-Schutz — bei bestätigter E2E-Session wird ein KLARTEXT-
    # chat.reply verworfen (der Agent muss chat.reply.enc senden); der
    # Fehlversuch kassiert das Rate-Limit-Kontingent wie ein echter Reply.
    if not _chat_rate_allow(f"chat_event:{agent_id}", _chat_rate_per_second(config)):
        logger.info("Agent %s: chat.reply verworfen — Chat-Rate-Limit", agent_id)
        return
    # FIN-T1 RED-03: einheitlicher Guard-Helfer (Warheit des Session-Brokers).
    if await _agent_has_e2e_session(state, agent_id):
        logger.warning(
            "Agent %s: Klartext-chat.reply verworfen — E2E-Session aktiv (Downgrade-Schutz)",
            agent_id)
        return
    chat_id = data.get("chat_id")
    text = data.get("text")
    if not isinstance(chat_id, str) or not chat_id.strip() or not isinstance(text, str) or not text:
        logger.warning(
            "Agent %s: chat.reply ohne gültiges chat_id/text — verworfen", agent_id
        )
        return

    chat_id = chat_id.strip()
    # SEC-W1 B F-01: Längen-Caps je Frame (RAM-Schutz, Parität zu /chat_event).
    if len(chat_id) > 128:
        logger.warning("Agent %s: chat.reply chat_id > 128 Zeichen — verworfen", agent_id)
        return
    if len(text) > _CHAT_TEXT_MAX_CHARS:
        logger.warning(
            "Agent %s: chat.reply text > %d Zeichen — verworfen (RAM-Schutz)",
            agent_id, _CHAT_TEXT_MAX_CHARS)
        return
    # SEC-W1 B F-02: Lone-Surrogate-chat_ids waren UTF-8-nicht-codierbar und
    # warfen erst im Phone UnicodeEncodeError — fail-closed am Relay.
    if not _utf8_encodable(chat_id):
        logger.warning("Agent %s: chat.reply chat_id nicht UTF-8-codierbar — verworfen", agent_id)
        return

    message = {
        "type": "chat.event",
        "v": 1,
        "chat_id": chat_id,
        "agent_id": agent_id,
        "text": text,
        "ts": int(time.time() * 1000),
    }
    # PH2-T11: elapsed_ms (Empfang→Antwort, vom Adapter gemessen) durchreichen.
    # SEC-W1 B F-03: NaN/inf-Koerzision-Guard (int(inf) = OverflowError).
    elapsed_ms = data.get("elapsed_ms")
    if _finite_ms(elapsed_ms):
        message["elapsed_ms"] = int(elapsed_ms)

    # SEC-W1 W1C-3/W1C-1: Owner-Claim UND Zustellung in EINER Lock-Sektion —
    # der erste zulässige Reply claimt eine unbekannte chat_id (Agent-
    # initiierte Konversationen gehörten bis jetzt NIEMANDEM — jeder zweite
    # Agent konnte einhauen), fremde chat_ids bleiben verworfen.
    delivered = await _deliver_to_phone(state, message, claim=(agent_id, chat_id))
    if delivered is None:
        return
    if not delivered:
        logger.info("Agent %s: chat.reply gespoolt (Phone offline)", agent_id)


# ── PH-3-T2/T3: E2E-Frame-Broker (Agent-Richtung) — opak, fail-closed ────────


async def _handle_agent_hello_ack(state: _RelayState, agent_id: str, data: dict) -> None:
    """crypto.hello_ack: Session als bestätigt markieren + Frame ans Phone
    (Spool wie Chat). Key-Material wird validiert (Hex), aber NIE geloggt."""
    session_id = data.get("session_id")
    if not isinstance(session_id, str) or not session_id.strip():
        logger.warning("Agent %s: hello_ack ohne session_id — verworfen", agent_id)
        return
    async with state.broker_lock:
        _prune_crypto_sessions(state)
        session = state.crypto_sessions.get(session_id)
    if session is None or session["agent_id"] != agent_id:
        logger.warning("Agent %s: hello_ack für unbekannte/fremde Session — verworfen", agent_id)
        return
    kex_pub = data.get("kex_pub")
    if not isinstance(kex_pub, str) or not _HEX64_RE.fullmatch(kex_pub.strip()):
        logger.warning("Agent %s: hello_ack mit ungültigem kex_pub — verworfen", agent_id)
        return
    for field in ("nonce", "ct"):
        value = data.get(field)
        if not isinstance(value, str) or not value:
            logger.warning("Agent %s: hello_ack ohne %s — verworfen", agent_id, field)
            return
    frame = {
        "type": "crypto.hello_ack", "v": 1,
        "session_id": session_id,
        "agent_id": agent_id,
        "kex_pub": kex_pub.strip().lower(),
        "nonce": data["nonce"],
        "ct": data["ct"],
        "ts": int(time.time() * 1000),
    }
    async with state.broker_lock:
        if session_id in state.crypto_sessions:
            state.crypto_sessions[session_id]["acked"] = True
    delivered = await _deliver_to_phone(state, frame)
    logger.info(
        "PH-3: E2E-Session %s bestätigt (Agent %s, %s)",
        _log_id(session_id[:8]), agent_id, "zugestellt" if delivered else "gespoolt")


async def _handle_agent_session_reset(state: _RelayState, agent_id: str,
                                      data: dict) -> None:
    """PH-3 (Sirs Revision (iii)): Fingerprint-Abweichung → Session sofort
    ungültig. Der Relay entfernt die Session (Cleartext danach wieder erlaubt
    bis zur nächsten hello) und informiert das Phone (SessionKeys verwerfen)."""
    session_id = data.get("session_id")
    if not isinstance(session_id, str) or not session_id.strip():
        logger.warning("Agent %s: session_reset ohne session_id — verworfen", agent_id)
        return
    # SIRS-01: Der Agent sendet den Reset JE nach Ursache — die App unterscheidet
    # benign (Agent-Neustart: neutraler Hinweis + sofortiger Neu-Handshake) von
    # einem Sicherheitsereignis (Fingerprint-Abweichung: MitM-Warnung). Der Relay
    # leitet NUR Allowlist-Reasons 1:1 weiter (fail-closed: unbekannt/fehlt =
    # fingerprint-mismatch, der alte hart-codierte Wert).
    reason = data.get("reason")
    if not isinstance(reason, str) or reason.strip() not in _RESET_REASONS:
        reason = "fingerprint-mismatch"
    async with state.broker_lock:
        session = state.crypto_sessions.pop(session_id, None)
    if session is None or session["agent_id"] != agent_id:
        logger.warning("Agent %s: session_reset für unbekannte/fremde Session — ignoriert", agent_id)
        return
    for _k in [k for k in state.crypto_replay if k[0] == session_id]:
        del state.crypto_replay[_k]
    frame = {
        "type": "crypto.session_reset", "v": 1,
        "session_id": session_id,
        "agent_id": agent_id,
        "reason": reason,
        "ts": int(time.time() * 1000),
    }
    await _deliver_to_phone(state, frame)
    logger.warning(
        "PH-3: Session %s auf Agenten-Verlangen zurückgesetzt (Agent %s, "
        "Reason %s) — Cleartext bis zur nächsten hello erlaubt",
        _log_id(session_id[:8]), agent_id, reason)


async def _handle_agent_chat_reply_enc(state: _RelayState, agent_id: str,
                                       data: dict) -> None:
    """chat.reply.enc → opak als chat.event.enc ans Phone (Typ-Umbenennung,
    ct unverändert). Gleiche Guards wie chat.reply: Rate-Limit, chat_id,
    Konversations-Isolation + E2E-Session/Replay (fail-closed, kein Key-Log)."""
    config = _load_relay_config()
    if not _chat_rate_allow(f"chat_event:{agent_id}", _chat_rate_per_second(config)):
        logger.info("Agent %s: chat.reply.enc verworfen — Chat-Rate-Limit", agent_id)
        return
    chat_id = data.get("chat_id")
    if not isinstance(chat_id, str) or not chat_id.strip():
        logger.warning("Agent %s: chat.reply.enc ohne gültiges chat_id — verworfen", agent_id)
        return
    chat_id = chat_id.strip()
    reason = _validate_enc_frame(data)
    if reason is not None:
        logger.warning("Agent %s: chat.reply.enc ungültig (%s) — verworfen", agent_id, reason)
        return
    if await _enc_session_active(state, agent_id, data) is None:
        logger.warning(
            "Agent %s: chat.reply.enc ohne bestätigte E2E-Session — verworfen (fail-closed)",
            agent_id)
        return
    if not _relay_replay_allow(state, data["session_id"], "a2p",
                               data["hdr"]["n"], data.get("ts"),
                               data["hdr"]["rk"]):
        logger.warning("Agent %s: chat.reply.enc Replay/Fenster-Verletzung — verworfen", agent_id)
        return
    frame = {
        "type": "chat.event.enc", "v": 1,
        "chat_id": chat_id,
        "agent_id": agent_id,
        "session_id": data["session_id"],
        "hdr": data["hdr"],
        "nonce": data["nonce"],
        "ct": data["ct"],
        "ts": int(time.time() * 1000),
    }
    # Klare Metadaten durchreichen (PH2-T11 elapsed_ms, msg_id) — nie Inhalt.
    # FIN-T1 RED-05: Typ-/Finite-Whitelist (Parität F-03/_finite_ms) — NaN als
    # WÖRTLICHES JSON-NaN, Objekte und Listen wurden bis jetzt ungeprüft ins
    # Phone-Frame gereicht (invalides JSON für strikte Parser, Typ-Konfusion).
    # elapsed_ms koerziert wie der Klartext-Pfad auf int (endlich, nicht-negativ).
    for extra in ("msg_id", "elapsed_ms"):
        value = data.get(extra)
        if value is None:
            continue
        if extra == "elapsed_ms":
            if _finite_ms(value):
                frame["elapsed_ms"] = int(value)
            continue
        if _enc_extra_ok(value):
            frame[extra] = value
    # SEC-W1 W1C-3/W1C-1: Owner-Claim + Zustellung in EINER Lock-Sektion
    # (gleiche Härtung wie der Klartext-chat.reply; fremde = None).
    delivered = await _deliver_to_phone(state, frame, claim=(agent_id, chat_id))
    if delivered is None:
        return
    if not delivered:
        logger.info("Agent %s: chat.reply.enc gespoolt (Phone offline)", agent_id)


async def _handle_agent_chat_read_enc(state: _RelayState, agent_id: str,
                                      data: dict) -> None:
    """chat.read.enc: msg_id bleibt KLAR → PH2-Status-Logik (read + klarer
    chat.status-Frame ans Phone) läuft unverändert; der enc-Spiegel-Frame
    geht zusätzlich ans Phone, damit dessen Empfangs-Kette mit der Sende-
    Kette des Agenten synchron bleibt (Jede enc-Nachricht zählt)."""
    chat_id = data.get("chat_id")
    msg_id = data.get("msg_id")
    # FIN-T1 RED-05: msg_id ist der Status-Map-Schlüssel (str) — Listen/Objekte
    # wurden bis jetzt nur per truthy-Check durchgelassen und landeten als
    # Typ-Konfusion im Phone-Frame. Strikt str (Parität zum Klartext-chat.read).
    if (not isinstance(chat_id, str) or not chat_id.strip()
            or not isinstance(msg_id, str) or not msg_id.strip()
            or len(msg_id) > 128):
        logger.warning("Agent %s: chat.read.enc ohne gültiges chat_id/msg_id — verworfen", agent_id)
        return
    reason = _validate_enc_frame(data)
    if reason is not None:
        logger.warning("Agent %s: chat.read.enc ungültig (%s) — verworfen", agent_id, reason)
        return
    if await _enc_session_active(state, agent_id, data) is None:
        logger.warning("Agent %s: chat.read.enc ohne bestätigte E2E-Session — verworfen", agent_id)
        return
    if not _relay_replay_allow(state, data["session_id"], "a2p",
                               data["hdr"]["n"], data.get("ts"),
                               data["hdr"]["rk"]):
        logger.warning("Agent %s: chat.read.enc Replay/Fenster-Verletzung — verworfen", agent_id)
        return
    # PH2-Status unverändert (klare msg_id): read + chat.status ans Phone.
    # FIN-T1 RED-03: direkt auf den Status-Kern — der Klartext-Guard des
    # chat.read-Handlers gilt hier NICHT (enc-Spiegel = legitimer Weg).
    if not await _chat_owner_allows(state, agent_id, chat_id.strip()):
        return
    await _chat_read_mark_messages(state, agent_id, chat_id.strip(), msg_id)
    frame = {
        "type": "chat.read.enc", "v": 1,
        "chat_id": chat_id.strip(),
        "msg_id": msg_id,
        "agent_id": agent_id,
        "session_id": data["session_id"],
        "hdr": data["hdr"],
        "nonce": data["nonce"],
        "ct": data["ct"],
        "ts": int(time.time() * 1000),
    }
    delivered = await _deliver_to_phone(state, frame)
    if not delivered:
        logger.debug("Agent %s: chat.read.enc gespoolt (Phone offline)", agent_id)


async def _handle_agent_chat_activity_enc(state: _RelayState, agent_id: str,
                                          data: dict) -> None:
    """chat.activity.enc: working/started_at bleiben KLAR (Dedup-Logik PH2-
    T10 unverändert); in E2E-Sessions ersetzt der enc-Spiegel den klaren
    chat.activity-Frame (das Phone liest working aus dem entschlüsselten
    Spiegel — ein Frame, keine Duplikate, Ratchet synchron)."""
    chat_id = data.get("chat_id")
    working = data.get("working")
    if not isinstance(chat_id, str) or not chat_id.strip() or not isinstance(working, bool):
        logger.warning("Agent %s: chat.activity.enc ohne gültiges chat_id/working — verworfen", agent_id)
        return
    chat_id = chat_id.strip()
    reason = _validate_enc_frame(data)
    if reason is not None:
        logger.warning("Agent %s: chat.activity.enc ungültig (%s) — verworfen", agent_id, reason)
        return
    if await _enc_session_active(state, agent_id, data) is None:
        logger.warning("Agent %s: chat.activity.enc ohne bestätigte E2E-Session — verworfen", agent_id)
        return
    if not _relay_replay_allow(state, data["session_id"], "a2p",
                               data["hdr"]["n"], data.get("ts"),
                               data["hdr"]["rk"]):
        logger.warning("Agent %s: chat.activity.enc Replay/Fenster-Verletzung — verworfen", agent_id)
        return
    if not await _chat_owner_allows(state, agent_id, chat_id):
        return
    started_at = data.get("started_at")
    # SEC-W1 B F-03: started_at-Koerzision nur bei endlich-numerischen Werten
    # (int(inf) = OverflowError, NaN fiel durch die alte >= 0-Prüfung).
    if _finite_ms(started_at):
        started_at = int(started_at)
    else:
        started_at = int(time.time() * 1000)
    key = (agent_id, chat_id)
    async with state.broker_lock:
        if state.chat_activity.get(key) == working:
            return  # Dedup: je Wechsel genau 1 Frame
        state.chat_activity[key] = working
        _cap_chat_activity(state)
    frame = {
        "type": "chat.activity.enc", "v": 1,
        "chat_id": chat_id,
        "agent_id": agent_id,
        "working": working,           # klar: PH2-Dedup + Phone-UI-Fallback
        "started_at": int(started_at),
        "session_id": data["session_id"],
        "hdr": data["hdr"],
        "nonce": data["nonce"],
        "ct": data["ct"],
        "ts": int(time.time() * 1000),
    }
    delivered = await _deliver_to_phone(state, frame)
    if not delivered:
        logger.debug("Agent %s: chat.activity.enc gespoolt (Phone offline)", agent_id)


# ── MEDIA-T1: Agent→Phone Media (chunked, opak) + Chunk-Ack ───────────────────


async def _handle_agent_media_enc(state: _RelayState, agent_id: str,
                                  data: dict) -> None:
    """MEDIA-T1: chat.media.enc → opak ans Phone (Typ bleibt erhalten, ct
    unverändert). Guards wie chat.reply.enc — mit EIGENEM Media-Rate-Bucket
    (B6: Multi-Chunk-Uploads zehren nicht am 2/s-Chat-Limit des Agenten):
    Struktur, klare Media-Metadaten, E2E-Session, Replay-Fenster +
    Konversations-Owner."""
    config = _load_relay_config()
    if not _chat_rate_allow(f"media_event:{agent_id}", _media_rate_per_second(config)):
        logger.info("Agent %s: chat.media.enc verworfen — Media-Rate-Limit", agent_id)
        return
    chat_id = data.get("chat_id")
    if not isinstance(chat_id, str) or not chat_id.strip():
        logger.warning("Agent %s: chat.media.enc ohne gültiges chat_id — verworfen", agent_id)
        return
    chat_id = chat_id.strip()
    reason = _validate_enc_frame(data)
    if reason is not None:
        logger.warning("Agent %s: chat.media.enc ungültig (%s) — verworfen", agent_id, reason)
        return
    reason = _validate_media_meta(data)
    if reason is not None:
        logger.warning("Agent %s: chat.media.enc Metadaten ungültig (%s) — verworfen",
                       agent_id, reason)
        return
    if await _enc_session_active(state, agent_id, data) is None:
        logger.warning(
            "Agent %s: chat.media.enc ohne bestätigte E2E-Session — verworfen (fail-closed)",
            agent_id)
        return
    if not _relay_replay_allow(state, data["session_id"], "a2p",
                               data["hdr"]["n"], data.get("ts"),
                               data["hdr"]["rk"]):
        logger.warning("Agent %s: chat.media.enc Replay/Fenster-Verletzung — verworfen", agent_id)
        return
    frame = {
        "type": "chat.media.enc", "v": 1,
        "chat_id": chat_id,
        "agent_id": agent_id,
        "session_id": data["session_id"],
        "media_id": data["media_id"],
        "seq": data["seq"],
        "total": data["total"],
        "hdr": data["hdr"],
        "nonce": data["nonce"],
        "ct": data["ct"],
        "ts": int(time.time() * 1000),
    }
    for extra in ("kind", "name", "mime", "size"):
        value = data.get(extra)
        if value is None:
            continue
        if _media_meta_ok(value):
            frame[extra] = value
    delivered = await _deliver_to_phone(state, frame, claim=(agent_id, chat_id))
    if delivered is None:
        return
    if not delivered:
        logger.info("Agent %s: chat.media.enc gespoolt (Phone offline)", agent_id)


async def _handle_agent_media_ack(state: _RelayState, agent_id: str,
                                  data: dict) -> None:
    """MEDIA-T1: chat.media.chunk_ack → 1:1 ans Phone (klarer Progress-Frame,
    fire-and-forget). Validierung: media_id/seq (Struktur) + eigenes Rate-
    Limit (Bursts je Chunk großer Dateien); Ok/Fehler-Status des Agenten
    werden als 'ok' (bool, default True) durchgereicht."""
    if not _chat_rate_allow(f"media_ack:{agent_id}", _MEDIA_ACK_MAX_PER_SECOND):
        logger.debug("Agent %s: chunk_ack über Rate-Limit — verworfen", agent_id)
        return
    chat_id = data.get("chat_id")
    if not isinstance(chat_id, str) or not chat_id.strip() \
            or not _utf8_encodable(chat_id.strip()):
        logger.warning("Agent %s: chunk_ack ohne gültiges chat_id — verworfen", agent_id)
        return
    reason = _validate_media_meta(data, require_header=False)
    if reason is not None:
        logger.warning("Agent %s: chunk_ack ungültig (%s) — verworfen", agent_id, reason)
        return
    ok = data.get("ok")
    frame = {
        "type": "chat.media.chunk_ack", "v": 1,
        "chat_id": chat_id.strip(),
        "agent_id": agent_id,
        "media_id": data["media_id"],
        "seq": data["seq"],
        "ts": int(time.time() * 1000),
    }
    if isinstance(ok, bool):
        frame["ok"] = ok
    if data.get("error") is not None and isinstance(data.get("error"), str) \
            and len(data.get("error", "")) <= 128:
        frame["error"] = data["error"][:128]
    delivered = await _deliver_to_phone(state, frame)
    if not delivered:
        logger.debug("Agent %s: chunk_ack gespoolt (Phone offline)", agent_id)


# ── PH2-T10: Chat-Status-Frames (F2-S1/S2/S3) ────────────────────────────────


def _prune_chat_status(state: _RelayState) -> None:
    """Status-Dict um Retention kürzen (kein endloses Wachstum).

    FIN-T1 RED-06: chat_owner bleibt UNBERÜHRT — die Owner-Isolation lief bis
    jetzt mit der 24-h-Retention ab (PoC red06: nach Ablauf claimte ein
    fremder Agent die chat_id mit 200 + Zustellung). Owner-Einträge sind
    dauerhaft (RAM-Schutz: _cap_chat_owner-FIFO, s. dort); sie altern NICHT,
    weil ein Wechsel des Owners die Konversation dauerhaft korrumpieren
    würde. Aufruf NUR unter broker_lock."""
    cutoff = time.time() - _chat_retention_seconds()
    stale_status = [mid for mid, entry in state.chat_status.items() if entry["ts"] < cutoff]
    for mid in stale_status:
        del state.chat_status[mid]


def _cap_chat_owner(state: _RelayState) -> None:
    """FIN-T1 RED-06: FIFO-RAM-Schutz für die dauerhaften Owner-Einträge
    (älteste zuerst raus). 50 000 Konversationen ≈ Jahre Betrieb — die
    Isolation hält damit praktisch dauerhaft; Aufruf NUR unter broker_lock."""
    while len(state.chat_owner) > _CHAT_OWNER_MAX:
        state.chat_owner.pop(next(iter(state.chat_owner)))


def _cap_chat_activity(state: _RelayState) -> None:
    """SEC-W1 D2: chat_activity hatte KEINEN Cleanup-Pfad — 5000 Activity-
    Frames mit frischen chat_ids füllten die Map unbegrenzt (RAM-DoS, ein
    Agent-Token genügte; PoC: auch nach _prune_chat_status unverändert).
    FIFO-Cap wie bei den anderen Maps; Aufruf NUR unter broker_lock."""
    while len(state.chat_activity) > _CHAT_ACTIVITY_MAX:
        state.chat_activity.pop(next(iter(state.chat_activity)))


async def _set_chat_status(
    state: _RelayState, msg_id: str, chat_id: str, agent_id: str, status: str
) -> None:
    """Status je msg_id pflegen; je WECHSEL einen chat.status-Frame ans Phone.

    Nur aufsteigende Übergänge (queued → delivered → read) und echte Wechsel
    erzeugen einen Frame — Downgrades und Duplikate sind stille No-Ops.
    Der Frame läuft über _deliver_to_phone (Phone offline = Spool) und ist
    bewusst NICHT rate-limited (Status ≠ Chat-Inhalt).
    """
    now = time.time()
    async with state.broker_lock:
        _prune_chat_status(state)
        existing = state.chat_status.get(msg_id)
        if existing is not None:
            if _CHAT_STATUS_ORDER.get(existing["status"], -1) >= _CHAT_STATUS_ORDER.get(status, -1):
                return  # kein Fortschritt: Dedup/kein Downgrade
        state.chat_status[msg_id] = {
            "chat_id": chat_id,
            "agent_id": agent_id,
            "status": status,
            "ts": now,
        }
    frame = {
        "type": "chat.status",
        "v": 1,
        "chat_id": chat_id,
        "msg_id": msg_id,
        "status": status,
        "ts": int(now * 1000),
    }
    delivered = await _deliver_to_phone(state, frame)
    if not delivered:
        logger.debug("chat.status %s gespoolt (Phone offline)", status)


async def _chat_owner_allows(state: _RelayState, agent_id: str, chat_id: str) -> bool:
    """PH2-T5: True = der Agent darf in diese chat_id antworten.

    Bekannte chat_id mit anderem Owner = fremd → verwerfen + loggen
    (separate Namespaces je Agent). Unbekannte chat_id = erlaubt.
    """
    async with state.broker_lock:
        owner = state.chat_owner.get(chat_id)
    if owner is not None and owner[0] != agent_id:
        logger.warning(
            "Agent %s: chat.event für fremde chat_id (Owner=%s) — verworfen (Namespace-Isolation)",
            agent_id, owner[0],
        )
        return False
    return True


async def _chat_read_mark_messages(
    state: _RelayState, agent_id: str, chat_id: str, msg_id) -> None:
    """FIN-T1 RED-03: PH2-Status-Kern (aus _handle_agent_chat_read extrahiert).

    Wird von BEIDEN Wegen genutzt: dem Klartext-chat.read (mit E2E-Guard +
    Owner-Check davor) und dem chat.read.enc-Spiegel (Session-/Owner-Checks
    erfolgten bereits im enc-Handler — der Spiegel treibt dieselbe Status-
    Logik, ohne den Klartext-Guard zu durchlaufen)."""
    async with state.broker_lock:
        _prune_chat_status(state)
        if isinstance(msg_id, str) and msg_id.strip():
            targets = [
                mid for mid, entry in state.chat_status.items()
                if mid == msg_id.strip() and entry["chat_id"] == chat_id
            ]
        else:
            targets = [
                mid for mid, entry in state.chat_status.items()
                if entry["chat_id"] == chat_id and entry["status"] != "read"
            ]
    if not targets:
        logger.debug("Agent %s: chat.read ohne treffende Nachricht (chat_id bekannt?)", agent_id)
        return
    for mid in targets:
        await _set_chat_status(state, mid, chat_id, agent_id, "read")


async def _handle_agent_chat_read(state: _RelayState, agent_id: str, data: dict) -> None:
    """PH2-T10 (F2-S2): chat.read-Frame → Status=read + chat.status an Phone.

    Body {chat_id, msg_id?}: mit msg_id wird genau diese Nachricht gelesen,
    ohne msg_id alle noch nicht gelesenen Nachrichten der chat_id.
    NICHT rate-limited; ungültige Frames = loggen + verwerfen.
    PH2-FIX (Jäger B3): fremde chat_id (Owner = anderer Agent) wird wie bei
    chat.reply verworfen + geloggt — read darf KEIN Cross-Agent-Orakel sein
    (Agent B darf Agent A nichts als 'gelesen' stempeln).
    FIN-T1 RED-03: bei bestätigter E2E-Session wird der KLARTEXT-frame
    verworfen (Parität chat.reply//chat_event//chat) — der Agent muss
    chat.read.enc senden; klare 'gelesen'-Receipts sind in enc-Sessions
    Session-Status-Spoofing (PoC red03)."""
    chat_id = data.get("chat_id")
    msg_id = data.get("msg_id")
    if not isinstance(chat_id, str) or not chat_id.strip():
        logger.warning("Agent %s: chat.read ohne chat_id — verworfen", agent_id)
        return
    chat_id = chat_id.strip()
    if await _agent_has_e2e_session(state, agent_id):
        logger.warning(
            "Agent %s: Klartext-chat.read verworfen — E2E-Session aktiv (Downgrade-Schutz)",
            agent_id)
        return
    if not await _chat_owner_allows(state, agent_id, chat_id):
        return
    await _chat_read_mark_messages(state, agent_id, chat_id, msg_id)


async def _reset_agent_activity(state: _RelayState, agent_id: str) -> None:
    """PH2-FIX (Jäger B5): Agent-WS-Disconnect → Spinner-Reset je chat_id.

    Alle working=true-Marker des Agenten werden zurückgesetzt und je
    betroffener chat_id ein chat.activity-Frame (working=false) ans Phone
    gespoolt/geliefert — stirbt der Agent mitten in der Arbeit, erstarret
    der App-Header-Spinner ('arbeitet …') nicht dauerhaft.
    """
    async with state.broker_lock:
        stale = [
            chat_id for (owner, chat_id), working in state.chat_activity.items()
            if owner == agent_id and working
        ]
        for chat_id in stale:
            state.chat_activity[(agent_id, chat_id)] = False
    for chat_id in stale:
        now_ms = int(time.time() * 1000)
        frame = {
            "type": "chat.activity",
            "v": 1,
            "chat_id": chat_id,
            "agent_id": agent_id,
            "working": False,
            "started_at": now_ms,
            "ts": now_ms,
        }
        delivered = await _deliver_to_phone(state, frame)
        if not delivered:
            logger.debug("chat.activity-Reset (working=false) gespoolt (Phone offline)")


async def _handle_agent_chat_activity(state: _RelayState, agent_id: str, data: dict) -> None:
    """PH2-T10 (F2-S3): chat.activity → Phone-Frame je WECHSEL (Dedup).

    Body {chat_id, working: true/false, started_at?}. Gleicher working-Wert
    wie der letzte = stilles No-Op (keine Flood). started_at (Unix-ms) kommt
    vom Agenten, Fallback = jetzt. NICHT rate-limited.
    PH2-FIX (Jäger B3): fremde chat_id (Owner = anderer Agent) wird wie bei
    chat.reply verworfen + geloggt — Agent B darf in Agent A's Conversation
    KEINEN Arbeitsstatus vortäuschen.
    FIN-T1 RED-03: bei bestätigter E2E-Session wird der KLARTEXT-Frame
    verworfen (Parität chat.reply//chat_event//chat/chat.read) — der Agent
    muss chat.activity.enc senden; klarer 'arbeitet…'-Status ist in
    enc-Sessions Session-Status-Spoofing (PoC red03).
    """
    chat_id = data.get("chat_id")
    working = data.get("working")
    if not isinstance(chat_id, str) or not chat_id.strip() or not isinstance(working, bool):
        logger.warning(
            "Agent %s: chat.activity ohne gültiges chat_id/working — verworfen", agent_id
        )
        return
    chat_id = chat_id.strip()
    if await _agent_has_e2e_session(state, agent_id):
        logger.warning(
            "Agent %s: Klartext-chat.activity verworfen — E2E-Session aktiv (Downgrade-Schutz)",
            agent_id)
        return
    if not await _chat_owner_allows(state, agent_id, chat_id):
        return
    started_at = data.get("started_at")
    # SEC-W1 B F-03: started_at-Koerzision nur bei endlich-numerischen Werten
    # (int(inf) = OverflowError, NaN fiel durch die alte >= 0-Prüfung).
    if _finite_ms(started_at):
        started_at = int(started_at)
    else:
        started_at = int(time.time() * 1000)

    key = (agent_id, chat_id)
    async with state.broker_lock:
        if state.chat_activity.get(key) == working:
            return  # Dedup: je Wechsel genau 1 Frame
        state.chat_activity[key] = working
        _cap_chat_activity(state)
    frame = {
        "type": "chat.activity",
        "v": 1,
        "chat_id": chat_id,
        "agent_id": agent_id,
        "working": working,
        "started_at": int(started_at),
        "ts": int(time.time() * 1000),
    }
    delivered = await _deliver_to_phone(state, frame)
    if not delivered:
        logger.debug("chat.activity (working=%s) gespoolt (Phone offline)", working)


async def _flush_agent_queue(state: _RelayState, agent_id: str, ws: web.WebSocketResponse) -> None:
    """App→Agent-Spool an die frische Agent-Verbindung ausliefern (FIFO).

    SEC-W1 W1C-1: Flush läuft komplett unter broker_lock (keine Dubletten
    gegen den Deliver-Pfad). SEC-W1 W1C-2: ein abgebrochener Rest wird
    VORNE wieder eingereiht — extend(rest) hängte die älteren Frames HINTER
    während des Flushs neu eingetroffene Nachrichten (FIFO-Inversion, PoC).
    """
    delivered_msgs: list = []
    async with state.broker_lock:
        queue = state.chat_inbound.get(agent_id)
        if queue:
            _prune_chat_queue(queue, _chat_retention_seconds())
            pending = list(queue)
            queue.clear()
        else:
            pending = []
        if not pending:
            return
        for index, (_ts, message) in enumerate(pending):
            try:
                # JÄGER J3: enc-Spool-Frames bekommen beim Flush eine frische
                # ts — sonst verwirft der Adapter alles >10 min STILL, obwohl
                # der Flush unten schon 'delivered' markiert hat.
                await ws.send_json(_refresh_enc_frame_ts(message))
                # PH2-T10: Spool-Auslieferung = delivered (wie ein Live-Send).
                if _mark_chat_delivered_if_sent(message):
                    delivered_msgs.append(message)
            except Exception as exc:
                rest = pending[index:]
                state.chat_inbound.setdefault(
                    agent_id, _ByteBoundedDeque(maxlen=_CHAT_QUEUE_MAXLEN)
                ).extendleft(reversed(rest))
                logger.warning("Agent %s: Spool-Auslieferung abgebrochen (%s)", agent_id, exc)
                return
    # Status-Pflege erst außerhalb des broker_lock (_set_chat_status nimmt
    # ihn selbst — asyncio.Lock ist nicht reentrant).
    for message in delivered_msgs:
        await _mark_chat_delivered(state, message)
    logger.info("Agent %s: %d gespoolte Nachricht(en) ausgeliefert", agent_id, len(pending))


def _refresh_enc_frame_ts(message: dict) -> dict:
    """JÄGER J3 (P1, JAEGE-RFULL 02.11.2026): E2E-Spool vs. 10-min-Freshness.

    Der Spool hält enc-Frames bis zu 24 h (_chat_retention_seconds), aber die
    Empfänger-Freshness (adapter.py decrypt_frame / PhoneCryptoPeer.decrypt-
    Frame) verwirft alles >10 min (_FRAME_MAX_AGE_MS): Kam der Empfänger erst
    >10 min später online, lieferte der Flush STALE Frames, der Empfänger
    verwarf sie bei der Entschlüsselung STILL — während der Flush die
    Nachricht bereits 'delivered' markiert hatte (Lügen-Chip '✓✓ Zugestellt',
    Nachricht weg). Beide Richtungen: chat.send.enc im Agent-Spool UND
    chat.event.enc/activity.enc/read.enc in der Phone-Outbox.

    Fix = Freshness zählt ab ZUSTELLUNG, nicht ab Einlieferung: Beim Flush
    bekommt JEDES *.enc-Frame eine frische ts. ts ist Klartext-Metadatum
    (NIE im AAD/ct — der AEAD bleibt unberührt); der Replay-Schutz bleibt
    beim Accept-Zeitpunkt-Check (_relay_replay_allow, SEC-W1 W1C-8) plus
    Ratchet-Zähler (hdr.n/rk). Klartext-Frames und crypto.*-Frames werden
    nicht angefasst.
    """
    if (isinstance(message, dict)
            and str(message.get("type") or "").endswith(".enc")
            and isinstance(message.get("ts"), (int, float))
            and not isinstance(message.get("ts"), bool)):
        message["ts"] = int(time.time() * 1000)
    return message


def _mark_chat_delivered_if_sent(message: dict) -> bool:
    """PH2-T10: chat.send/chat.send.enc → delivered-Metadaten liefern, sonst False."""
    return (
        isinstance(message, dict)
        and message.get("type") in ("chat.send", "chat.send.enc")
        and isinstance(message.get("msg_id"), str)
        and bool(message["msg_id"])
    )


async def _mark_chat_delivered(state: _RelayState, message: dict) -> None:
    """PH2-T10: Nachricht als delivered markieren (chat.status-Frame ans Phone)."""
    if _mark_chat_delivered_if_sent(message):
        await _set_chat_status(
            state, message["msg_id"], message["chat_id"], message["agent_id"], "delivered"
        )


async def _deliver_to_agent(state: _RelayState, agent_id: str, message: dict) -> bool:
    """Nachricht spoolen und bei online-Agent sofort zustellen; False = Spool.

    SEC-W1 W1C-1: append + WS-Send + Pop laufen jetzt in EINER broker_lock-
    Sektion — vorher konnte ein konkurrierender (Re)Connect-Flush denselben
    Frame zwischenzeitlich aus der Queue nehmen und 2× senden (Dubletten-
    PoC: chat.send/chat.event doppelt). Pop erst NACH erfolgreicher
    Sendefolge (Objekt-Identität).
    """
    async with state.broker_lock:
        queue = state.chat_inbound.setdefault(
            agent_id, _ByteBoundedDeque(maxlen=_CHAT_QUEUE_MAXLEN))
        _prune_chat_queue(queue, _chat_retention_seconds())
        queue.append((time.time(), message))
        ws = state.agent_ws.get(agent_id)
        if ws is None or ws.closed:
            return False
        try:
            await ws.send_json(message)
        except Exception as exc:
            logger.warning("Chat-Zustellung an Agent %s fehlgeschlagen: %s", agent_id, exc)
            return False
        for index, (_ts, queued) in enumerate(queue):
            if queued is message:
                del queue[index]
                break
    # PH2-T10: Agent-WS-Send erfolgreich → Status delivered an das Phone.
    await _mark_chat_delivered(state, message)
    return True


async def _deliver_to_phone(state: _RelayState, message: dict,
                            claim: Optional[tuple[str, str]] = None) -> Optional[bool]:
    """Nachricht an die Phone-WS; offline = Outbox (Retention s. Config).

    SEC-W1 W1C-1: append + WS-Send + Pop laufen jetzt in EINER broker_lock-
    Sektion (Dubletten-Rennen Deliver × Flush, s. _deliver_to_agent).

    SEC-W1 W1A-2/W1C-3: claim=(agent_id, chat_id) prüft UND setzt den
    Konversations-Owner in derselben Lock-Sektion wie die Zustellung:
    - fremde chat_id (Owner = anderer Agent) → None (verworfen + geloggt)
    - unbekannte chat_id → der ERSTE zulässige Reply CLAIMT (Agent-initiierte
      Konversationen gehören ab sofort dem Antwort-Agenten — Cross-Agent-
      Injection geschlossen, auch das TOCTOU-Fenster zwischen Check und
      Deliver ist mit derselben Lock-Sektion zu).
    Rückgabe: None = fremde chat_id, True = zugestellt, False = gespoolt.
    """
    async with state.broker_lock:
        if claim is not None:
            claiming_agent, chat_id = claim
            _prune_chat_status(state)
            owner = state.chat_owner.get(chat_id)
            if owner is not None and owner[0] != claiming_agent:
                logger.warning(
                    "Agent %s: chat.event für fremde chat_id (Owner=%s) — verworfen (Namespace-Isolation)",
                    claiming_agent, owner[0],
                )
                return None
            if owner is None:
                # FIN-T1 RED-06: Claim ist dauerhaft (kein Retention-Ablauf
                # mehr) — FIFO-Cap als RAM-Schutz, älteste zuerst.
                state.chat_owner[chat_id] = (claiming_agent, time.time())
                _cap_chat_owner(state)
        _prune_chat_queue(state.phone_outbox, _chat_retention_seconds())
        state.phone_outbox.append((time.time(), message))
        ws = state.phone_ws
        if ws is None or ws.closed:
            return False
        try:
            await ws.send_json(message)
        except Exception as exc:
            logger.warning("Chat-Zustellung ans Phone fehlgeschlagen: %s", exc)
            return False
        for index, (_ts, queued) in enumerate(state.phone_outbox):
            if queued is message:
                del state.phone_outbox[index]
                break
    return True


async def _flush_phone_outbox(state: _RelayState) -> None:
    """Agent→App-Spool an die frische Phone-Verbindung ausliefern (FIFO).

    SEC-W1 W1C-1: Flush läuft komplett unter broker_lock — Deliver und Flush
    können dasselbe Frame nicht mehr doppelt senden.
    """
    async with state.broker_lock:
        _prune_chat_queue(state.phone_outbox, _chat_retention_seconds())
        pending = list(state.phone_outbox)
        state.phone_outbox.clear()
        if not pending:
            return
        ws = state.phone_ws
        if ws is None or ws.closed:
            # Replace-Rennen: kein Phone — Spool bleibt vollständig erhalten.
            state.phone_outbox.extend(pending)
            return
        for index, (_ts, message) in enumerate(pending):
            try:
                # JÄGER J3: enc-Spool-Frames bekommen beim Flush eine frische
                # ts — sonst verwirft das Phone (PhoneCryptoPeer.decryptFrame)
                # alles >10 min STILL, obwohl der Chip 'delivered' sagt.
                await ws.send_json(_refresh_enc_frame_ts(message))
            except Exception as exc:
                rest = pending[index:]
                # Rest VORNE wieder einreihen (FIFO-Reihenfolge erhalten).
                state.phone_outbox.extendleft(reversed(rest))
                logger.warning("Phone-Spool-Auslieferung abgebrochen (%s)", exc)
                return
    logger.info("Phone: %d gespoolte Chat-Nachricht(en) ausgeliefert", len(pending))


async def _handle_key_publish(request: web.Request, state: _RelayState) -> web.StreamResponse:
    """PH-3-T2: Phone veröffentlicht seine öffentlichen Device-Keys (X25519
    Pubs, NIE Private) — Ablage relay-keys.json 0600, Inhalte nie geloggt."""
    remote_ip = _effective_remote_ip(request)
    if _auth_is_blocked(remote_ip):
        return web.json_response(
            {"error": "Too many failed authentication attempts. Try again later."},
            status=429,
        )
    if not _constant_time_eq(_bearer_token(request), state.pairing_code):
        _auth_record_failure(remote_ip)
        logger.warning("HTTP /key_publish rejected — bad auth from %s", remote_ip)
        return web.json_response({"error": "Unauthorized"}, status=401)
    # SEC-W2 W2-2: Rate-Limit (vorher ungedrosselt — JE Request legte eine
    # Datei an: mkstemp+write+replace-I/O-Sturm mit Pairing-Code).
    if not _chat_rate_allow(f"key_publish:{remote_ip}", _BROKER_ROUTE_MAX_PER_SECOND):
        return web.json_response({"error": "rate limit exceeded"}, status=429)

    try:
        body = await request.json()
    except Exception:
        body = None
    if not isinstance(body, dict):
        return web.json_response({"error": "JSON body required"}, status=400)
    ik_pub = body.get("ik_pub")
    spk_pub = body.get("spk_pub")
    if (not isinstance(ik_pub, str) or not _HEX64_RE.fullmatch(ik_pub.strip())
            or not isinstance(spk_pub, str) or not _HEX64_RE.fullmatch(spk_pub.strip())):
        return web.json_response(
            {"error": "ik_pub/spk_pub must be 64-hex"}, status=400)
    device_id = str(body.get("device_id") or "phone").strip()[:64] or "phone"
    entry = {
        "device_id": device_id,
        "ik_pub": ik_pub.strip().lower(),
        "spk_pub": spk_pub.strip().lower(),
    }
    # FIN-T1 RED-07: Identity-Wechsel SICHTBAR machen — bis jetzt überschrieb
    # jede(r) Pairing-Code-Inhaber die Phone-Keys LAUTLOS (PoC red07: 8 Swaps
    # je 200, kein Alert-Frame, laufende Sessions unberührt; Warn-only am
    # Agenten = bewusstes W1A-5-Design, aber am Relay kam der Wechsel NICHT
    # einmal ins Log). Jetzt: EINE Warning je Wechsel (device_id/Keys neu
    # veröffentlicht) — Key-Material bleibt ungeloggt, Blockade bewusst NEIN.
    existing = _load_device_keys().get("device") or {}
    if existing and (existing.get("device_id") != entry["device_id"]
                     or existing.get("ik_pub") != entry["ik_pub"]
                     or existing.get("spk_pub") != entry["spk_pub"]):
        logger.warning(
            "PH-3: Phone-Identity-WECHSEL (device_id=%s) — ik_pub/spk_pub neu "
            "veröffentlicht; FP-Abgleich am Agenten prüfen (MITM-Indikator)",
            _log_id(device_id))
    try:
        _store_device_keys(entry)
    except OSError:
        logger.error("PH-3: Key-Registry-Schreibfehler — /key_publish fail-closed")
        return web.json_response({"error": "key registry unavailable"}, status=500)
    logger.info("PH-3: Device-Keys veröffentlicht (device_id=%s) — Keys nicht geloggt",
               _log_id(device_id))
    return web.json_response({"ok": True})


async def _handle_key_get(request: web.Request, state: _RelayState) -> web.StreamResponse:
    """PH-3-T2: Phone liest die PUBLIC Keys eines Agenten aus der Registry
    (relay-config.json). Fail-closed: unbekannter Agent oder Agent ohne
    Identity-Key → 404 (kein E2E-Zugang), Keys nie geloggt."""
    remote_ip = _effective_remote_ip(request)
    if _auth_is_blocked(remote_ip):
        return web.json_response(
            {"error": "Too many failed authentication attempts. Try again later."},
            status=429,
        )
    if not _constant_time_eq(_bearer_token(request), state.pairing_code):
        _auth_record_failure(remote_ip)
        logger.warning("HTTP /key_get rejected — bad auth from %s", remote_ip)
        return web.json_response({"error": "Unauthorized"}, status=401)

    agent_id = str(request.query.get("agent_id") or "").strip().lower()
    if not agent_id:
        return web.json_response({"error": "agent_id required"}, status=400)
    registry = _agent_crypto_registry(_load_relay_config())
    entry = registry.get(agent_id)
    if entry is None:
        logger.warning(
            "PH-3: /key_get ohne Identity-Keys für Agent %s — 404 (fail-closed)", agent_id)
        return web.json_response({"error": "no keys for agent"}, status=404)
    return web.json_response({"agent_id": agent_id, **entry})


async def _handle_crypto_hello(request: web.Request, state: _RelayState) -> web.StreamResponse:
    """PH-3-T2: X3DH-vereinfachter Handshake-Start des Phones (identity/ephe-
    prekey → Session-Key-Ableitung beidseitig). Der Relay legt NUR die Session
    (Metadaten) an und leitet den Frame OPAK an den Agenten weiter — offline
    = Spool (FIFO vor allen enc-Nachrichten), Keys werden nie geloggt."""
    remote_ip = _effective_remote_ip(request)
    if _auth_is_blocked(remote_ip):
        return web.json_response(
            {"error": "Too many failed authentication attempts. Try again later."},
            status=429,
        )
    if not _constant_time_eq(_bearer_token(request), state.pairing_code):
        _auth_record_failure(remote_ip)
        logger.warning("HTTP /crypto_hello rejected — bad auth from %s", remote_ip)
        return web.json_response({"error": "Unauthorized"}, status=401)
    # SEC-W2 W2-2: Rate-Limit — /crypto_hello war ungedrosselt und füllte
    # state.crypto_sessions ohne Grenze (PoC: 30 Hellos < 0,5 s = 30 Sessions,
    # alle 200; Prune erst > 24 h; je Request zudem Config-Datei-I/O).
    if not _chat_rate_allow(f"crypto_hello:{remote_ip}", _BROKER_ROUTE_MAX_PER_SECOND):
        return web.json_response({"error": "rate limit exceeded"}, status=429)

    try:
        body = await request.json()
    except Exception:
        body = None
    if not isinstance(body, dict):
        return web.json_response({"error": "JSON body required"}, status=400)

    agent_id = str(body.get("agent_id") or "").strip().lower()
    session_id = str(body.get("session_id") or "").strip()
    # SEC-W1 B F-02: Lone-Surrogate-session_ids sind UTF-8-nicht-codierbar
    # und warfen erst im Adapter UnicodeEncodeError (Frame-Loop-Tod).
    if not _utf8_encodable(session_id):
        return web.json_response({"error": "session_id not UTF-8 encodable"}, status=400)
    pubs = {}
    for field in ("ik_pub", "ek_pub", "spk_pub"):
        value = body.get(field)
        if not isinstance(value, str) or not _HEX64_RE.fullmatch(value.strip()):
            return web.json_response(
                {"error": f"{field} must be 64-hex"}, status=400)
        pubs[field] = value.strip().lower()
    if not agent_id or not (8 <= len(session_id) <= 128):
        return web.json_response(
            {"error": "agent_id and session_id (8-128 chars) required"}, status=400)

    config = _load_relay_config()
    if agent_id not in _agent_tokens(config):
        return web.json_response({"error": "Unknown agent"}, status=404)
    if agent_id not in _agent_crypto_registry(config):
        logger.warning(
            "PH-3: crypto.hello für Agent %s ohne Identity-Keys — verworfen (fail-closed)",
            agent_id)
        return web.json_response(
            {"error": "agent has no crypto keys"}, status=403)

    async with state.broker_lock:
        _prune_crypto_sessions(state)
        # SEC-W1 W1A-3: Re-Hello auf eine bereits BESTÄTIGTE session_id
        # setzte acked=False zurück und hob damit den Klartext-Downgrade-
        # Guard auf (F1-Vertrag 'Klartext 403 bei bestätigter Session' war
        # keine Relay-Invariante mehr, ohne dass je ein crypto.session_reset
        # floss). Abgelehnt (409); Freigabe nur via crypto.session_reset/24 h.
        existing = state.crypto_sessions.get(session_id)
        if existing is not None and existing["acked"]:
            logger.warning(
                "PH-3: crypto.hello Re-Play auf bestätigte Session %s — abgelehnt (409)",
                _log_id(session_id[:8]))
            return web.json_response(
                {"error": "session_id already active"}, status=409)
        # FIN-T1 RED-04: Session-Slot ist je (session_id, agent_id) gebunden —
        # ein Re-Hello auf eine UNBESTÄTIGTE Session mit ABWEICHENDEM agent_id
        # überschrieb bis jetzt den Broker still (200) und tötete damit den
        # Handshake des Original-Agenten dauerhaft (dessen hello_ack galt fortan
        # als 'fremd'; nur Pairing-Code nötig = gezielter Handshake-DoS +
        # Session-Bindungs-Hijack). Fail-closed: 403, der Original-Agent
        # bleibt Eigentümer; derselbe Agent darf sein unbestätigtes Hello
        # wiederholen (Retries bleiben erlaubt).
        if existing is not None and existing["agent_id"] != agent_id:
            logger.warning(
                "PH-3: crypto.hello Rebind der Session %s auf fremden Agenten "
                "(%s, Owner=%s) — abgelehnt (403)",
                _log_id(session_id[:8]), agent_id, existing["agent_id"])
            return web.json_response(
                {"error": "session_id bound to another agent"}, status=403)
        state.crypto_sessions[session_id] = {
            "agent_id": agent_id,
            "ts": time.time(),
            "acked": False,
        }
    frame = {
        "type": "crypto.hello", "v": 1,
        "session_id": session_id,
        "agent_id": agent_id,
        **pubs,
        "ts": int(time.time() * 1000),
    }
    delivered = await _deliver_to_agent(state, agent_id, frame)
    logger.info(
        "PH-3: crypto.hello Session %s (Agent %s) %s",
        _log_id(session_id[:8]), agent_id, "zugestellt" if delivered else "gespoolt")
    return web.json_response({
        "ok": True,
        "session_id": session_id,
        "delivered": delivered,
        "spooled": not delivered,
    })


async def _handle_chat(request: web.Request, state: _RelayState) -> web.StreamResponse:
    """App→Agent: Nachricht in die je-Agent-Queue; online = sofort zustellen."""
    remote_ip = _effective_remote_ip(request)
    if _auth_is_blocked(remote_ip):
        return web.json_response(
            {"error": "Too many failed authentication attempts. Try again later."},
            status=429,
        )

    config = _load_relay_config()
    if not _constant_time_eq(_bearer_token(request), state.pairing_code):
        _auth_record_failure(remote_ip)
        logger.warning("HTTP /chat rejected — bad auth from %s", remote_ip)
        return web.json_response({"error": "Unauthorized"}, status=401)

    if not _chat_rate_allow(f"chat:{remote_ip}", _chat_rate_per_second(config)):
        return web.json_response({"error": "Chat rate limit exceeded"}, status=429)

    try:
        body = await request.json()
    except Exception:
        body = None
    if not isinstance(body, dict):
        return web.json_response({"error": "JSON body required"}, status=400)

    agent_id = str(body.get("agent_id") or "").strip().lower()
    if agent_id not in _agent_tokens(config):
        return web.json_response({"error": "Unknown agent"}, status=404)

    # PH-3-T2: E2E-Zweig — POST /chat mit {agent_id, chat_id, ct, nonce, hdr,
    # session_id} (kein text). ct bleibt OPAK (der Relay kann Inhalte nie lesen);
    # die chat_id legt das PHONE fest (AAD bindet daran — der Relay darf sie
    # im enc-Modus NICHT ersetzen), msg_id erzeugt der Relay wie im Klartext-Pfad.
    if body.get("ct") is not None:
        if body.get("text"):
            return web.json_response(
                {"error": "text not allowed in enc mode"}, status=400)
        reason = _validate_enc_frame(body)
        if reason is not None:
            logger.warning("HTTP /chat: ungültiger enc-Body (%s) für Agent %s", reason, agent_id)
            return web.json_response({"error": f"invalid enc frame: {reason}"}, status=400)
        chat_id = str(body.get("chat_id") or "").strip()
        if not (8 <= len(chat_id) <= 128):
            return web.json_response(
                {"error": "chat_id required (8-128 chars) in enc mode"}, status=400)
        # SEC-W1 B F-02: Lone-Surrogate-chat_ids sind UTF-8-nicht-codierbar
        # und warfen erst im Phone UnicodeEncodeError — fail-closed am Relay.
        if not _utf8_encodable(chat_id):
            return web.json_response(
                {"error": "chat_id not UTF-8 encodable"}, status=400)
        if await _enc_session_active(state, agent_id, body) is None:
            logger.warning(
                "HTTP /chat: enc ohne bestätigte E2E-Session (Agent %s) — verworfen (fail-closed)",
                agent_id)
            return web.json_response({"error": "crypto session invalid"}, status=403)
        if not _relay_replay_allow(state, body["session_id"], "p2a",
                                   body["hdr"]["n"], body.get("ts"),
                                   body["hdr"]["rk"]):
            logger.warning("HTTP /chat: enc Replay/Fenster-Verletzung (Agent %s) — verworfen", agent_id)
            return web.json_response({"error": "replay detected"}, status=429)
        msg_id = str(uuid.uuid4())
        message = {
            "type": "chat.send.enc", "v": 1,
            "chat_id": chat_id,
            "msg_id": msg_id,
            "agent_id": agent_id,
            "session_id": body["session_id"],
            "hdr": body["hdr"],
            "nonce": body["nonce"],
            "ct": body["ct"],
            "ts": int(time.time() * 1000),
        }
        async with state.broker_lock:
            _prune_chat_status(state)
            # SEC-W1 W1A-2: Bedingungsloser Owner-Write = Hijack — ein
            # Pairing-Code-Inhaber konnte die chat_id eines ANDEREN Agenten
            # übernehmen (Klartext-Zweig generiert die chat_id selbst, der
            # enc-Zweig nimmt sie vom Phone). Nur setzen, wenn unbenutzt oder
            # bereits dieser Agent; fremde = 403 (Spiegel von _chat_owner_allows).
            owner = state.chat_owner.get(chat_id)
            if owner is not None and owner[0] != agent_id:
                logger.warning(
                    "HTTP /chat: enc chat_id %s gehört Agent %s — Übernahme durch %s verweigert",
                    _log_id(chat_id), owner[0], agent_id)
                return web.json_response(
                    {"error": "chat_id belongs to another agent"}, status=403)
            state.chat_owner[chat_id] = (agent_id, time.time())
        await _set_chat_status(state, msg_id, chat_id, agent_id, "queued")
        delivered = await _deliver_to_agent(state, agent_id, message)
        return web.json_response(
            {
                "chat_id": chat_id,
                "msg_id": msg_id,
                "agent_id": agent_id,
                "delivered": delivered,
                "queued": not delivered,
            }
        )

    text = body.get("text")
    file_ref = body.get("file_ref")
    if not isinstance(text, str) or not text:
        return web.json_response({"error": "agent_id and text are required"}, status=400)
    # SEC-W1 B F-01: harter Text-Cap — 500 Frames à bis 96 MB spoolten ohne
    # Grenze in den Relay-Speicher (OOM = ALLE Agenten + Phone tot).
    if len(text) > _CHAT_TEXT_MAX_CHARS:
        return web.json_response(
            {"error": f"text too large (max {_CHAT_TEXT_MAX_CHARS} chars)"}, status=413)
    if file_ref is not None and not isinstance(file_ref, str):
        return web.json_response({"error": "file_ref must be a string"}, status=400)

    # PH-3: Downgrade-Schutz — besteht eine bestätigte E2E-Session für diesen
    # Agenten, wird Klartext verworfen (kein silent Downgrade; ein reset-ter
    # Agent ohne Session bleibt v0.14-legacy-kompatibel, Spec §3.2).
    # FIN-T1 RED-03: einheitlicher Guard-Helfer (Warheit des Session-Brokers).
    if await _agent_has_e2e_session(state, agent_id):
        logger.warning(
            "HTTP /chat: Klartext-Downgrade verworfen — E2E-Session aktiv (Agent %s)", agent_id)
        return web.json_response(
            {"error": "cleartext not allowed while e2e session active"}, status=403)

    chat_id = str(uuid.uuid4())
    msg_id = str(uuid.uuid4())
    message = {
        "type": "chat.send",
        "v": 1,
        "chat_id": chat_id,
        "msg_id": msg_id,
        "agent_id": agent_id,
        "text": text,
        "ts": int(time.time() * 1000),
    }
    if file_ref:
        message["file_ref"] = file_ref

    # PH2-T5: Konversations-Owner festlegen — dieser Agent (und nur er)
    # darf später auf diese chat_id antworten.
    # FIN-T1 RED-06: Owner-Eintrag DAUERHAFT — Retention-Prune löscht ihn
    # nicht mehr (PoC red06: Cross-Agent-Claim nach 24 h); RAM-Schutz = FIFO.
    async with state.broker_lock:
        _prune_chat_status(state)
        state.chat_owner[chat_id] = (agent_id, time.time())
        _cap_chat_owner(state)

    # PH2-T10: Broker-Annahme = queued (je Wechsel ein chat.status-Frame ans
    # Phone; bei Online-Agent folgt delivered direkt danach). Status-Frames
    # sind nicht rate-limited (Status ≠ Chat-Inhalt).
    await _set_chat_status(state, msg_id, chat_id, agent_id, "queued")

    delivered = await _deliver_to_agent(state, agent_id, message)
    return web.json_response(
        {
            "chat_id": chat_id,
            "msg_id": msg_id,
            "agent_id": agent_id,
            "delivered": delivered,
            "queued": not delivered,
        }
    )


async def _handle_chat_event(request: web.Request, state: _RelayState) -> web.StreamResponse:
    """Agent→App: Antwort über die Phone-WS; offline = Outbox-Spool."""
    remote_ip = _effective_remote_ip(request)
    if _auth_is_blocked(remote_ip):
        return web.json_response(
            {"error": "Too many failed authentication attempts. Try again later."},
            status=429,
        )

    supplied = _bearer_token(request)
    config = _load_relay_config()
    agent_id = _resolve_agent_by_token(config, supplied)
    if agent_id is None:
        _auth_record_failure(remote_ip)
        logger.warning("HTTP /chat_event rejected — bad agent token from %s", remote_ip)
        return web.json_response({"error": "Unauthorized"}, status=401)

    if not _chat_rate_allow(f"chat_event:{agent_id}", _chat_rate_per_second(config)):
        return web.json_response({"error": "Chat rate limit exceeded"}, status=429)

    try:
        body = await request.json()
    except Exception:
        body = None
    if not isinstance(body, dict):
        return web.json_response({"error": "JSON body required"}, status=400)

    chat_id = str(body.get("chat_id") or "").strip()
    text = body.get("text")
    if not chat_id or not isinstance(text, str) or not text:
        return web.json_response({"error": "chat_id and text are required"}, status=400)
    # SEC-W1 B F-01: Längen-Caps (chat_id + text) — RAM-Schutz, Parität zum
    # Klartext-/chat.reply-Pfad; 413 statt stiller Spool-Explosion.
    if len(chat_id) > 128:
        return web.json_response({"error": "chat_id too long (max 128)"}, status=413)
    if len(text) > _CHAT_TEXT_MAX_CHARS:
        return web.json_response(
            {"error": f"text too large (max {_CHAT_TEXT_MAX_CHARS} chars)"}, status=413)

    # PH-3 F1 (Jäger P1): Downgrade-SYMMETRIE — /chat_event bekommt den
    # gleichen Guard wie /chat: ist für diesen Agenten eine bestätigte E2E-
    # Session aktiv, wird ein KLARTEXT-Frame verworfen (403, kein silent
    # Downgrade — der Agent muss chat.reply.enc senden). Reset-te Agenten
    # ohne Session bleiben v0.14-legacy-kompatibel (Spec §3.2).
    # FIN-T1 RED-03: einheitlicher Guard-Helfer (Warheit des Session-Brokers).
    if await _agent_has_e2e_session(state, agent_id):
        logger.warning(
            "HTTP /chat_event: Klartext-Downgrade verworfen — E2E-Session aktiv (Agent %s)",
            agent_id)
        return web.json_response(
            {"error": "cleartext not allowed while e2e session active"}, status=403)

    # PH2-T5: Konversations-Isolation — eine chat_id, die einem anderen Agent
    # gehört (per /chat angelegt), ist fremd: 403 (kein Cross-Agent-Zugriff).
    # SEC-W1 W1C-3: unbekannte chat_ids werden jetzt vom ERSTEN Reply geclaimt
    # (Agent-initiierte Konversationen gehörten bis jetzt niemandem) — Claim
    # + Zustellung in EINER broker_lock-Sektion (s. _deliver_to_phone).

    message = {
        "type": "chat.event",
        "v": 1,
        "chat_id": chat_id,
        "agent_id": agent_id,
        "text": text,
        "ts": int(time.time() * 1000),
    }
    # PH2-T11: elapsed_ms (Empfang→Antwort, vom Adapter gemessen) durchreichen.
    # SEC-W1 B F-03: NaN/inf-Koerzision-Guard (int(inf) = OverflowError).
    elapsed_ms = body.get("elapsed_ms")
    if _finite_ms(elapsed_ms):
        message["elapsed_ms"] = int(elapsed_ms)

    delivered = await _deliver_to_phone(state, message, claim=(agent_id, chat_id))
    if delivered is None:
        return web.json_response(
            {"error": "chat_id belongs to another agent"}, status=403
        )
    return web.json_response(
        {
            "chat_id": chat_id,
            "agent_id": agent_id,
            "delivered": delivered,
            "queued": not delivered,
        }
    )


# ── MEDIA-T1: chunked Medien-/Datei-Versand (E2E-only) ───────────────────────


async def _handle_media(request: web.Request, state: _RelayState) -> web.StreamResponse:
    """MEDIA-T1: App→Agent, EIN verschlüsselter Media-Chunk via POST /media.

    Fail-closed wie der enc-Zweig von /chat:
    - ct ist PFLICHT (Klartext-Media existiert NICHT — jedes 'text'-Feld
      ist ein Downgrade-Versuch → 400).
    - Struktur-Check [_validate_enc_frame] + klare Media-Metadaten
      [_validate_media_meta] + bestätigte E2E-Session + Replay-Fenster
      + Konversations-Owner (Identität zum /chat-enc-Zweig).
    - Zustellung über den normalen Agent-Broker (Spool + Retention, ts wird
      beim Flush wie jeder enc-Frame aufgefrischt — J3-Parität).
    """
    remote_ip = _effective_remote_ip(request)
    if _auth_is_blocked(remote_ip):
        return web.json_response(
            {"error": "Too many failed authentication attempts. Try again later."},
            status=429,
        )

    config = _load_relay_config()
    if not _constant_time_eq(_bearer_token(request), state.pairing_code):
        _auth_record_failure(remote_ip)
        logger.warning("HTTP /media rejected — bad auth from %s", remote_ip)
        return web.json_response({"error": "Unauthorized"}, status=401)

    # B6: eigener Media-Burst-Bucket — Chunk-Bursts (bis 50 MB) teilen NICHT
    # mehr den 2/s-Chat-Bucket derselben IP (Herz-Test Befund 6).
    if not _chat_rate_allow(f"media:{remote_ip}", _media_rate_per_second(config)):
        return web.json_response({"error": "Media rate limit exceeded"}, status=429)

    try:
        body = await request.json()
    except Exception:
        body = None
    if not isinstance(body, dict):
        return web.json_response({"error": "JSON body required"}, status=400)

    agent_id = str(body.get("agent_id") or "").strip().lower()
    if agent_id not in _agent_tokens(config):
        return web.json_response({"error": "Unknown agent"}, status=404)

    # Media ist enc-ONLY: ein text-Feld ist per Definition ein Downgrade.
    if body.get("ct") is None:
        return web.json_response(
            {"error": "media requires encrypted chunks (ct) — no cleartext path"},
            status=400)
    if body.get("text"):
        return web.json_response(
            {"error": "text not allowed in media frames"}, status=400)

    reason = _validate_enc_frame(body)
    if reason is not None:
        logger.warning("HTTP /media: ungültiger enc-Body (%s) für Agent %s",
                       reason, agent_id)
        return web.json_response(
            {"error": f"invalid enc frame: {reason}"}, status=400)

    reason = _validate_media_meta(body)
    if reason is not None:
        logger.warning("HTTP /media: ungültige Media-Metadaten (%s) für Agent %s",
                       reason, agent_id)
        return web.json_response(
            {"error": f"invalid media meta: {reason}"}, status=400)

    chat_id = str(body.get("chat_id") or "").strip()
    if not (8 <= len(chat_id) <= 128):
        return web.json_response(
            {"error": "chat_id required (8-128 chars) in media frames"}, status=400)
    if not _utf8_encodable(chat_id):
        return web.json_response(
            {"error": "chat_id not UTF-8 encodable"}, status=400)

    if await _enc_session_active(state, agent_id, body) is None:
        logger.warning(
            "HTTP /media: enc ohne bestätigte E2E-Session (Agent %s) — verworfen (fail-closed)",
            agent_id)
        return web.json_response({"error": "crypto session invalid"}, status=403)
    if not _relay_replay_allow(state, body["session_id"], "p2a",
                               body["hdr"]["n"], body.get("ts"),
                               body["hdr"]["rk"]):
        logger.warning("HTTP /media: enc Replay/Fenster-Verletzung (Agent %s) — verworfen",
                       agent_id)
        return web.json_response({"error": "replay detected"}, status=429)

    # Klare Media-Metadaten (Whitelist, nie Inhalt) in den Agent-Frame.
    media_id = body["media_id"]
    message = {
        "type": "chat.media.enc", "v": 1,
        "chat_id": chat_id,
        "agent_id": agent_id,
        "session_id": body["session_id"],
        "media_id": media_id,
        "seq": body["seq"],
        "total": body["total"],
        "hdr": body["hdr"],
        "nonce": body["nonce"],
        "ct": body["ct"],
        "ts": int(time.time() * 1000),
    }
    for extra in ("kind", "name", "mime", "size"):
        value = body.get(extra)
        if value is None:
            continue
        if _media_meta_ok(value):
            message[extra] = value

    async with state.broker_lock:
        _prune_chat_status(state)
        # SEC-W1 W1A-2-Parität: chat_id-Übernahme nur, wenn frei oder derselbe
        # Agent (Klartext-Zweig-Kontrakt, enc-Zweig übernimmt die Phone-chat_id).
        owner = state.chat_owner.get(chat_id)
        if owner is not None and owner[0] != agent_id:
            logger.warning(
                "HTTP /media: chat_id %s gehört Agent %s — Übernahme durch %s verweigert",
                _log_id(chat_id), owner[0], agent_id)
            return web.json_response(
                {"error": "chat_id belongs to another agent"}, status=403)
        state.chat_owner[chat_id] = (agent_id, time.time())

    delivered = await _deliver_to_agent(state, agent_id, message)
    return web.json_response(
        {
            "chat_id": chat_id,
            "media_id": media_id,
            "seq": body["seq"],
            "agent_id": agent_id,
            "delivered": delivered,
            "queued": not delivered,
        }
    )


# ── HTTP handler (tool side) ─────────────────────────────────────────────────

_RESPONSE_TIMEOUT = 30  # seconds


def _screen_record_response_timeout() -> float:
    """B5 (Herz-Test Befund 5): Antwortfenster für /screen_record.

    Der J4-Drill bewies eine legitime Laufzeit von 31,6 s — das Standard-
    Fenster von 30 s war zu knapp, ein Live-Hot-Patch zog deshalb das RELAY
    auf 55 s (_RESPONSE_RECORD, Undeployed-Drift c6d9e0e≠3d14e31). Der
    Hot-Patch wird hier als konfigurierbares Repo-Feature übernommen, damit
    der nächste Deploy-Sync ihn nicht still verwirft.

    ANDROID_RELAY_SCREEN_RECORD_TIMEOUT überschreibt (Sekunden); Default 55,
    Untergrenze 30 (kürzer als das Standardfenster ergäbe für eine Aufnahme
    keinen Sinn), ungültige Werte fallen auf 55 zurück.
    """
    try:
        value = float(os.getenv("ANDROID_RELAY_SCREEN_RECORD_TIMEOUT", "55"))
    except ValueError:
        return 55.0
    return max(float(_RESPONSE_TIMEOUT), value)


def _response_timeout_for(path: str) -> float:
    """Effektives Antwortfenster für einen Tool-Pfad (tool-seitig)."""
    if path == "/screen_record":
        return _screen_record_response_timeout()
    return float(_RESPONSE_TIMEOUT)


async def _handle_http(
    request: web.Request, state: _RelayState, path: str
) -> web.StreamResponse:
    """Forward an HTTP request from a tool to the phone over WebSocket."""
    # ── Auth check ──────────────────────────────────────────────────────────
    # Require Bearer token matching the pairing code.  The client already sends
    # this (android_tool._auth_headers), so the only effect is blocking
    # unauthenticated local processes from abusing the HTTP tool API.
    remote_ip = _effective_remote_ip(request)
    if _auth_is_blocked(remote_ip):
        logger.warning("HTTP auth attempt from blocked IP %s — 429", remote_ip)
        return web.json_response(
            {"error": "Too many failed authentication attempts. Try again later."},
            status=429,
        )

    auth_header = request.headers.get("Authorization", "")
    token = auth_header.removeprefix("Bearer ").strip() if auth_header.startswith("Bearer ") else ""
    if not _constant_time_eq(token, state.pairing_code):
        _auth_record_failure(remote_ip)
        logger.warning(
            "HTTP %s %s rejected — bad auth from %s (header=%s)",
            request.method, path, remote_ip, _mask_token(token),
        )
        return web.json_response({"error": "Unauthorized"}, status=401)

    # ── Phone connectivity check ────────────────────────────────────────────
    async with state.phone_ws_lock:
        ws = state.phone_ws
        if ws is None or ws.closed:
            return web.json_response(
                {
                    "error": "No phone connected. Open the Hermes app on your phone and connect."
                },
                status=503,
            )

    # Build the command envelope
    request_id = str(uuid.uuid4())
    method = request.method  # GET or POST
    params = dict(request.query)

    body = {}
    if method == "POST":
        try:
            body = await request.json()
        except Exception:
            body = {}

    command = {
        "request_id": request_id,
        "method": method,
        "path": path,
        "params": params,
        "body": body,
    }
    logger.debug(">>> %s %s body=%s", method, path, _safe_body_repr(body))

    # Register the response target *before* sending so we never miss a reply.
    is_binary_stream = path in ("/mic_file", "/file")
    future = None
    stream_queue = None
    if is_binary_stream:
        # v0.11.0 (Relay-Review F2/HOL-Fix größerer Puffer): die Queue nimmt
        # ~16MB an 64KB-Chunks auf — der WS-Read-Loop darf NIE auf einen
        # langsamen HTTP-Konsumenten warten (put_nowait + bewusster Abbruch
        # bei Überlauf, siehe _on_phone_binary).
        stream_queue = asyncio.Queue(maxsize=256)
        async with state.streams_lock:
            state.streams[request_id] = stream_queue
    else:
        future = state.loop.create_future()
        async with state.pending_lock:
            state.pending[request_id] = future

    try:
        await ws.send_json(command)
    except Exception as exc:
        if is_binary_stream:
            async with state.streams_lock:
                state.streams.pop(request_id, None)
        else:
            async with state.pending_lock:
                state.pending.pop(request_id, None)
        logger.error("Failed to send command to phone: %s", exc)
        return web.json_response(
            {"error": f"Failed to send command to phone: {exc}"},
            status=502,
        )

    if is_binary_stream:
        return await _relay_binary_stream(request, state, request_id, stream_queue, stream_path=path)

    # Wait for the phone's response — /screen_record bekommt sein eigenes,
    # längeres Fenster (B5: Aufnahme läuft am Phone, nicht der HTTP-Call).
    response_timeout = _response_timeout_for(path)
    try:
        response_data = await asyncio.wait_for(future, timeout=response_timeout)
    except asyncio.TimeoutError:
        async with state.pending_lock:
            state.pending.pop(request_id, None)
        logger.warning(
            "Phone did not respond within %.0fs for %s %s",
            response_timeout,
            method,
            path,
        )
        return web.json_response(
            {"error": f"Phone did not respond within {response_timeout:.0f}s"},
            status=504,
        )
    except ConnectionError as exc:
        return web.json_response({"error": str(exc)}, status=502)

    # Return the phone's result
    status = response_data.get("status", 200)
    result = response_data.get("result", {})
    return web.json_response(result, status=status)


_MAX_STREAM_BYTES = 256 * 1024 * 1024


async def _relay_binary_stream(
    request: web.Request,
    state: _RelayState,
    request_id: str,
    queue: asyncio.Queue,
    stream_path: str = "/mic_file",
) -> web.StreamResponse:
    """Stream binary phone frames to the authenticated HTTP caller with backpressure."""
    response = None
    try:
        kind, first = await asyncio.wait_for(queue.get(), timeout=_RESPONSE_TIMEOUT)
        if kind == "error":
            return web.json_response({"error": first}, status=502)
        if kind != "message":
            return web.json_response({"error": "Phone sent stream data before metadata"}, status=502)

        stream = first.get("stream") if isinstance(first, dict) else None
        if not isinstance(stream, dict) or stream.get("event") != "start":
            status = first.get("status", 502) if isinstance(first, dict) else 502
            result = first.get("result", {"error": "Invalid stream response"}) if isinstance(first, dict) else {"error": "Invalid stream response"}
            return web.json_response(result, status=status)

        expected_size = int(stream.get("size", -1))
        if expected_size < 0 or expected_size > _MAX_STREAM_BYTES:
            return web.json_response({"error": "Recording size is invalid"}, status=413)

        raw_filename = os.path.basename(str(stream.get("filename", "recording.wav")))
        filename = re.sub(r"[^A-Za-z0-9._-]", "_", raw_filename)
        mime_type = str(stream.get("mimeType", "audio/wav"))
        if stream_path == "/file":
            if not filename or filename == "_":
                filename = "device_file"
        else:
            if not filename.lower().endswith(".wav"):
                filename = "recording.wav"
            if mime_type != "audio/wav":
                mime_type = "application/octet-stream"

        response = web.StreamResponse(
            status=200,
            headers={
                "Cache-Control": "no-store",
                "Content-Type": mime_type,
                "Content-Disposition": f'attachment; filename="{filename}"',
                "Content-Length": str(expected_size),
            },
        )
        await response.prepare(request)

        digest = hashlib.sha256()
        total = 0
        while True:
            kind, payload = await asyncio.wait_for(queue.get(), timeout=_RESPONSE_TIMEOUT)
            if kind == "error":
                raise ConnectionError(str(payload))
            if kind == "chunk":
                total += len(payload)
                if total > expected_size or total > _MAX_STREAM_BYTES:
                    raise ValueError("Phone sent more recording data than declared")
                digest.update(payload)
                await response.write(payload)
                continue
            if kind != "message":
                raise ValueError("Unknown stream event")

            stream_event = payload.get("stream") if isinstance(payload, dict) else None
            event_name = stream_event.get("event") if isinstance(stream_event, dict) else None
            if event_name == "error":
                raise IOError(str(stream_event.get("message", "Phone stream failed")))
            if event_name != "end":
                raise ValueError("Invalid stream completion event")
            if total != expected_size or int(stream_event.get("bytes", -1)) != total:
                raise IOError("Recording stream length does not match metadata")
            expected_sha = str(stream_event.get("sha256", ""))
            if not re.fullmatch(r"[0-9a-f]{64}", expected_sha) or not hmac.compare_digest(
                digest.hexdigest(), expected_sha
            ):
                raise IOError("Recording stream checksum mismatch")
            await response.write_eof()
            return response
    except asyncio.TimeoutError:
        logger.warning("Timed out receiving microphone stream request_id=%s", request_id)
        if response is None:
            return web.json_response({"error": "Phone stream timed out"}, status=504)
        response.force_close()
        return response
    except Exception as exc:
        logger.warning("Microphone stream failed request_id=%s: %s", request_id, exc)
        if response is None:
            return web.json_response({"error": "Phone stream failed"}, status=502)
        response.force_close()
        return response
    finally:
        async with state.streams_lock:
            if state.streams.get(request_id) is queue:
                state.streams.pop(request_id, None)
        # If the HTTP consumer disappeared while the bounded queue was full,
        # free any waiting WebSocket producer immediately.
        while not queue.empty():
            try:
                queue.get_nowait()
            except asyncio.QueueEmpty:
                break
