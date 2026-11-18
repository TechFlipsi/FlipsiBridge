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
import secrets
import tempfile
import threading
import time
import uuid
import datetime
from collections import deque
from pathlib import Path
from typing import Optional, TypeGuard

import aiohttp
from aiohttp import web

logger = logging.getLogger("android_relay")

# ── PH-4 (O4): Security-Parameter = Server-Config, nie hard-coded ────────────
# Jedes Security-Limit ist per Env konfigurierbar (systemd-Unit / relay.env) und
# wird von _env_clamped_* AUF das DoS-Schild geclampt: die Konfig darf die
# Schilder nur VERSCHÄRFEN, nie lockern — Default (Env weg/ungültig) ist der
# bisherige Hardcode (fail-closed). Syntax-Guards (Device-/Call-/Media-Regex,
# Namespace-Syntax) bleiben bewusst NICHT konfigurierbar: eine Konfig könnte
# sonst die Validierung weiten (O4-Ausnahme, dokumentiert in deployment/README).


def _env_clamped_float(name: str, default: float, lo: float, hi: float) -> float:
    """Env-Float lesen; wegfallend/ungültig/nicht-finite = Default, sonst auf
    [lo, hi] geclampt (Klammer = hartes Maximum, konfigurierbar nur innerhalb)."""
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = float(raw)
    except ValueError:
        return default
    if not math.isfinite(value):
        return default
    return max(lo, min(hi, value))


def _env_clamped_int(name: str, default: int, lo: int, hi: int) -> int:
    """Env-Int lesen (via _env_clamped_float), sonst wie dort clamplen."""
    return int(_env_clamped_float(name, float(default), float(lo), float(hi)))


# ── Module-level state ────────────────────────────────────────────────────────

_relay_lock = threading.Lock()
_relay_instance: Optional["_RelayState"] = None


class _PhoneSlot:
    """B1 (Multi-Gerät): EIN Live-Slot je Gerät — das WS plus Verbindungs-
    Metadaten. Der Slot stirbt mit der Verbindung; der REGISTRIERTE Gerätein-
    trag (state.device_registry) + die je-Gerät-Outbox (state.device_outbox)
    überleben sie (Spool-Erhaltung, Migration ohne Verlust)."""

    __slots__ = ("ws", "device_id", "alias", "admin", "connected_at",
                 "last_seen")

    def __init__(self, ws, device_id: str, alias: str = "", admin: bool = False):
        self.ws = ws
        self.device_id = device_id
        self.alias = alias or ""
        self.admin = bool(admin)
        self.connected_at = time.time()
        self.last_seen = time.time()


class _RelayState:
    """Holds all mutable state for one running relay instance."""

    def __init__(self, pairing_code: str, port: int):
        self.pairing_code: str = pairing_code
        self.port: int = port
        # FIN-T1 RED-02: Rotations-Epoche — wird bei JEDEM Code-WECHSEL unter
        # _relay_lock inkrementiert. Die Phone-WS-Registrierung liest die Epoche
        # nach dem Token-Check und verifiziert sie unter devices_lock erneut:
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

        # ── B1 (Multi-Gerät): Geräte-Registry statt phone_ws singular ────────
        # device_registry = die BEKANNTEN Geräte (persistiert relay-devices.json
        # v2: {v:2, devices:{device_id: {alias, admin, epoch_of_enroll,
        # enrolled_at, last_seen}}}); devices = nur die LEBENDEN Slots eines
        # je Geräts (ws + Metadaten); ein Reconnect ersetzt ausschließlich
        # SEINEN eigenen Slot — fremde Slots bleiben unberührt (W17-Vertrag,
        # jetzt Registry-Logik). device_outbox = JE Gerät eine eigene Agent→App-
        # Spool-Queue (Parität zur alten Einheits-Outbox, _ByteBoundedDeque);
        # frames ohne Geräte-Bezug (vor erstem Bekanntsein) landen im
        # fallback_outbox und werden beim ersten Adopt-Gerät übernommen
        # (Spool-Verlust ausgeschlossen, Migration ohne Verlust).
        self.device_registry: dict[str, dict] = {}
        self.devices: dict[str, _PhoneSlot] = {}
        self.device_outbox: dict[str, deque] = {}
        # B4 Revoke: je bekanntem Gerät ein EPHEMERES Rückgabe-Handle (request_id)
        # für den device_revoke-Frame (App kennt NUR maskierte Kennungen — PII-
        # Regeln). Lebt nur bis zum Restart (Neu-Mint je _send_phone_state-Lese-
        # zugriff); Revok entbindet es. Kein Persistenz-Bedarf (App liest den
        # Stand je Connect frisch).
        self.device_revoke_ids: dict[str, str] = {}
        self.fallback_outbox: deque = _ByteBoundedDeque(maxlen=_CHAT_QUEUE_MAXLEN)
        self.broker_lock: Optional[asyncio.Lock] = None
        # Sticky-Default für den Tool-Weg (Befehls-Hüllposten): das zuletzt
        # REGISTRIERTE Gerät (Plan Kap. 1.1, bewusst klein gehalten).
        self.last_active_device: Optional[str] = None
        # Gerätech-Cap (B1): wird in _serve aus relay-config ('max_devices')/
        # env gebunden; None = _device_cap() rechnet je Aufruf (Tests).
        self.device_cap: Optional[int] = None
        # K8-Fix (Kobold): KEINE eager Lock-Erzeugung hier — __init__ läuft OHNE
        # Event-Loop; der Lock wird in _run_loop (Z. ~220) gebunden, bevor der
        # Server laust. Der Alte eager-Init war ein totes Feld (wurde ersetzt).
        self.phone_ws_lock: Optional[asyncio.Lock] = None  # Legacy-Alias devices_lock
        self.devices_lock: Optional[asyncio.Lock] = None

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
        # abdruck kommt später). Locks lazy wie devices_lock (in _run_loop).
        self.agents: dict[str, dict] = {}
        self.agent_ws: dict[str, web.WebSocketResponse] = {}
        self.agent_ws_lock: Optional[asyncio.Lock] = None

        # PH2-T1: Chat-Broker — je Agent eine Inbound-Queue (App→Agent) plus
        # eine je-Gerät-Outbox (Agent→App). Einträge: (timestamp, message-dict).
        # PH2-FIX (Jäger B2): beide Queues mit maxlen=500 (FIFO) — ein
        # Offline-Phone/Agent kann den Relay-Speicher NICHT unbegrenzt füllen
        # (älteste Frames fallen automatisch raus, Retention prune weiter).
        self.chat_inbound: dict[str, deque] = {}

        # PH2-T10 (F2-S1): Chat-Status je msg_id — {msg_id: {chat_id, agent_id,
        # status, ts}}. Übergänge: queued → delivered (Agent-WS-Send) → read
        # (chat.read-Frame des Agenten). Je Wechsel geht ein chat.status-Frame
        # an die Geräte-Slots; Status-Frames sind NICHT rate-limited.
        self.chat_status: dict[str, dict] = {}

        # PH2-T10 (F2-S3): Activity-Dedup je (agent_id, chat_id) — letzter
        # working-Wert; nur ein WECHL erzeugt einen Phone-Frame (keine Flood).
        self.chat_activity: dict[tuple[str, str], bool] = {}

        # W19 (SIRS-Neued): agent.status-Dedup — letzter working-Wert JE
        # agent_id (Parallel-State zu chat_activity, kein Chat-Bezug). Nur
        # working-Wechsel erzeugen einen Frame; Frames mit sub/end werden je
        # Frame durchgereicht (die App verwaltet Sub-Slots je sub.id/end).
        self.agent_status: dict[str, tuple[bool, Optional[str]]] = {}

        # PH2-T5: Konversations-Isolation — chat_id → (owner_agent_id, ts).
        # Eine Agenten-Antwort auf eine fremde chat_id wird verworfen + geloggt.
        # FIN-T1 RED-06: Einträge DAUERHAFT (Retention-Prune fasst sie nicht
        # mehr an; RAM-Schutz = _cap_chat_owner-FIFO).
        self.chat_owner: dict[str, tuple[str, float]] = {}

        # PH-3-T2: E2E-Session-Broker — {session_id: {agent_id, ts, acked,
        # device_id}} (24-h-Gültigkeit, Fail-closed). B2: device_id = die
        # Geräte-Identität des Session-Erzeugers (Session-Broker = Routing-
        # Wahrheit: Agent→enc-Frames fächern über session_id→device_id auf;
        # der Adapter je Gerät führt je Gerät eine eigene E2E-Session).
        # Der Relay sieht NUR Metadaten: keine Keys, kein Klartext, ct bleibt
        # opak. Zugriff ausschließlich im Event-Loop → kein eigener Lock nötig.
        self.crypto_sessions: dict[str, dict] = {}
        # PH-3: Grob-Replay-Tracking je (session_id, richtung, ratchet_pub) —
        # Fenster 100 + 10-min-Freshness (Defense-in-Depth; die GCM-Prüfung
        # machen die Endpunkte, der Relay kann Inhalte by design nicht prüfen).
        # P1c: hdr.n ist nur JE RATCHET-KETTE (hdr.rk) eindeutig.
        self.crypto_replay: dict[tuple[str, str, str], dict] = {}

        # ── B1 Anmelde-Tor (pairing.request/approve) ─────────────────────────
        # pairing_requests: device_id → {request_id, ts, expires, ip} — die
        # AUSSTEHenden Anmelde-Anfragen unbekannter Geräte (dem Admin-Gerät
        # als pairing.request-Frame gemeldet; cap-Paarität zum Cap-Schild).
        # pairing_approvals: device_id → {expires, used} — die erteilten Fre-
        # gaben (TTL einmalig; eine verbrauchte/abgelaufene Freigabe öffnet
        # NICHT — fail-closed). Beide Maps bewusst CAPPED + TTL (Kein Registry-
        # Wachstum ohne Grenze, Kap. 8 #2).
        self.pairing_requests: dict[str, dict] = {}
        self.pairing_approvals: dict[str, dict] = {}

        # W17-F2 (Optionales Kopplungs-Passwort): NUR der Hash-Record
        # {"v", "method", "iter", "salt_hex", "hash_hex"} oder None (= Schutz
        # inaktiv). Kein Klartext jemals im RAM/Log; geladen in _serve() aus
        # pairing_password.json (0600), gesetzt über die authentifizierte
        # Route /password_set (Bearer = Kopplungscode).
        self.pairing_pw: Optional[dict] = None

    # ── Legacy-Kompatibilität (W17.1-Semantik + Bestands-Tests) ──────────────
    # phone_ws/phone_device_id/phone_outbox spiegeln die EIN-Gerät-Welt auf die
    # Geräte-Registry (Slot/Bucket des Legacy-Default-Geräts "phone" bzw. Fallback-
    # Bucket vor dem ersten Bekanntsein). Innere Pfade nutzen NUR devices/
    # device_registry — die Properties sind Test-/Tool-Fassade, keine zweite
    # Zustandswahrheit.
    @property
    def phone_ws(self):
        slot = self.devices.get("phone")
        if slot is None:
            for other in self.devices.values():
                if other.ws is not None:
                    slot = other  # erstes lebendiges fremdes Slot-Fenster
                    break
        return None if slot is None else slot.ws

    @phone_ws.setter
    def phone_ws(self, value):
        if value is None:
            slot = self.devices.pop("phone", None)
            if slot is not None:
                self.devices.pop("phone", None)
            return
        slot = self.devices.get("phone")
        if slot is None:
            slot = _PhoneSlot(value, "phone")
            self.devices["phone"] = slot
        else:
            slot.ws = value
            slot.connected_at = time.time()

    @property
    def phone_device_id(self):
        if self.last_active_device:
            return self.last_active_device
        if self.devices:
            return next(iter(self.devices))
        return "phone" if self.device_registry else None

    @property
    def phone_outbox(self):
        """Legacy-Fassade: die Outbox des Geräts "phone" — vor dem ersten
        bekannten Gerät der Fallback-Bucket (Spool-Verlust ausgeschlossen)."""
        if "phone" in self.device_registry or "phone" in self.device_outbox \
                or "phone" in self.devices:
            return self.device_outbox.setdefault(
                "phone", _ByteBoundedDeque(maxlen=_CHAT_QUEUE_MAXLEN))
        if self.device_outbox and self.last_active_device in self.device_outbox:
            return self.device_outbox[self.last_active_device]
        return self.fallback_outbox


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
    """B1: irgendein Geräte-Slot ist lebendig (Legacy-Name erhalten — ein
    einzelner Slot verhält sich exakt wie die alte Ein-Gerät-Welt)."""
    with _relay_lock:
        s = _relay_instance
        if s is None:
            return False
        for slot in s.devices.values():
            ws = slot.ws
            if ws is not None and not getattr(ws, "closed", True):
                return True
        return False


def get_device_registry() -> dict[str, dict]:
    """B1-Snapshot der Geräte-Registry (maskierte Kennungen — PII-Regel).
    Je Gerät alias, admin, online (Slot?) + letze Kennung als Maske."""
    with _relay_lock:
        s = _relay_instance
        if s is None:
            return {}
        snapshot = {}
        for device_id, entry in s.device_registry.items():
            items = dict(entry) if isinstance(entry, dict) else {}
            snapshot[_mask_id(device_id)] = {
                "alias": str(items.get("alias") or ""),
                "admin": bool(items.get("admin")),
                "online": bool(device_id in s.devices),
            }
        return snapshot


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


# ── W17-F1: Ein-Gerät-pro-Kopplungscode ───────────────────────────────────────

# Device-Kennung-Format: ASCII-Wort (Buchstaben/Ziffern/Punkt/Minus/Unterstrich),
# 1..64 Zeichen — genug für eine UUID/Install-Hash, zu klein für Header-DoS.
_DEVICE_ID_RE = re.compile(r"[A-Za-z0-9._-]{1,64}")

# WS-Close-Code für den Race-Slot (Pre-Prepare-403 ist nach dem Upgrade nicht
# mehr möglich — hier spricht das Relay denselben Sachverhalt als Close-Code;
# Bereich 4000-4999 = private use, von aiohttp/OkHttp sauber transportiert).
_WS_CLOSE_CODE_IN_USE = 4403
# ── B4 Revoke (Multi-Gerät, Kap. 4.2): 4403-FAMILIE (44xx App-Bereich) — die
# App klassifiziert 4403 als code-in-use (PairingHandshakePolicy), daher trägt
# der Revoke-Kick einen EIGENEN Familiencode + die Vertrags-Reason 'device-revoked'
# (App-Log zeigt sie; Reconnect des revokten Geräts landet fail-closed im Tor).
_WS_CLOSE_CODE_DEVICE_REVOKED = 4404
_WS_CLOSE_REASON_DEVICE_REVOKED = b"device-revoked"


def _parse_device_id(request: web.Request) -> Optional[str]:
    """W17-F1: stabile Gerätekennung aus dem WS-Auth-Handshake (Header
    'X-Device-Id'). None = Gerät liefert keine (Legacy-Client / abwesend).
    Ungültiges Format (Zeichen/Länge außerhalb [A-Za-z0-9._-]{1,64}) = None —
    fail-closed Richtung Legacy-Semantik, NICHT Richtung 500er."""
    headers = getattr(request, "headers", request)
    try:
        raw = (headers.get("X-Device-Id") or "").strip()
    except (AttributeError, TypeError):
        return None
    if not raw:
        return None
    if _DEVICE_ID_RE.fullmatch(raw):
        return raw
    return None


def _pairing_slot_locked(state: "_RelayState", device_id: Optional[str]) -> bool:
    """W17-F1: Kern-Regel des Ein-Gerät-pro-Code-Locks.

    True, wenn ein KONKRETER zweiter Gerät-Handshake (anderer/unbekannter
    device_id) abgewiesen werden soll, weil der Slot IN DIESER Sekunde von
    einer LEBENDEN Phone-WS gehalten wird. False = Handshake kann normal
    durchgehen:

      - Slot frei (keine WS oder WS zu)                 → False (Wiederaufnahme
        nach Todesfall — NOCH kein Dauer-Lock: aiohttp heartbeat=15 schließt
        tote Sockets binnen Sekunden, das Freigabe-Event läuft über
        _cleanup_phone und räumt die Registrierung MIT).
      - Inhaber LEBT und hat KEINE Kennung (Legacy) und Kommer auch ohne
        Kennung                                        → False (Legacy-Parität:
        exakt das bisherige Replace-Verhalten, sonst würde die Upgrade-Welle
        Alt-Clients aussperren).
      - andernfalls (IDs präsent und ungleich, inkl. Fälle 'Inhaber ohne
        Kennung vs. Kommer mit Kennung' und umgekehrt)  → True (fail-closed
        gegen das Aushebeln des aktiven Geräts).
    """
    holder = state.phone_ws
    if holder is None or holder.closed:
        return False
    holder_id = state.phone_device_id
    if holder_id is None and device_id is None:
        # Legacy-Parität: beide Seiten ohne Kennung = bisheriges Replace-
        # Verhalten (bestehende Suite hängt daran — und ein Alt-Client kann
        # ohnehin nicht zwischen eigenem Reconnect und Fremdkappler trennen).
        return False
    # Zwei echte Kennungen gleich → desselben Geräts Reconnect: erlaubt.
    # Alles andere (ungleich, oder eine Seite ohne Kennung) → lock.
    return not (holder_id is not None and device_id is not None
                and hmac.compare_digest(holder_id.encode("utf-8"),
                                        device_id.encode("utf-8")))


def _slot_lock_reject_log(state: "_RelayState", device_id: Optional[str],
                          remote_ip: str, phase: str) -> None:
    """W17-F1: EINE Warn-Zeile je abgewiesenem Fremd-Handshake (Kennung
    maskiert — PII-Regel; die Kennung selbst ist ein per-Install-Merker)."""
    logger.warning(
        "Phone WS rejected — pairing code in use by another live device "
        "(holder=%s, newcomer=%s, ip=%s, phase=%s)",
        _mask_id(state.phone_device_id), _mask_id(device_id), remote_ip, phase)


def _mask_id(value: Optional[str]) -> str:
    # Kennung nur als Länge/Präsenz im Log — keine vollständige PII-Leak-Fläche.
    if not value:
        return "none"
    return f"<masked:{len(value)}>"


# ── B1 (Multi-Gerät): Geräte-Registry v2 + Anmelde-Tor ─────────────────────────
# EIN Kopplungscode = N Geräte (Sir-Order 02.11./17.11.): die REGISTRIERUNG der
# Geräte lebt in relay-devices.json v2 ({v:2, devices:{device_id: entry}});
# JEDES bekannte Gerät bekommt seinen EIGENEN WS-Slot, seine EIGENE Outbox und
# kann ohne Tor (neu-)koppeln. Unbekannte Geräte passieren das ANMELDE-TOR
# (pairing.request → Freigabe pairing.approve, TTL 10 min, einmalig; alternativ
# das W17.2-Kopplungs-Passwort als Direkt-Enroll) — ohne Tor-Bestehen bleibt
# der 403/4403-family "code-in-use" der W17.1-Sperre als Fehlton bestehen und
# die IP-Rate-Chain zählt weiter.
_DEVICE_REGISTRY_MAX_DEFAULT = 4   # Sir-Decision-Kern (Plan Kap. 6): 4 Geräte
_DEVICE_CAP_HARD_MAX = 8           # hartes Maximum — Cap ist DoS-Schild
_PAIRING_TTL_SECONDS = _env_clamped_float(
    "ANDROID_RELAY_PAIRING_TTL_SECONDS", 600.0, 60.0, 600.0)  # 10 min — Anfrage- UND Freigabe-Fenster (O4: Env nur verschärfen [60..600])
_PAIRING_PENDING_MAX = _env_clamped_int(
    "ANDROID_RELAY_PAIRING_PENDING_MAX", 8, 2, 8)     # cap-Paarigkeit der Tor-Maps (Kap. 8 #2; O4: Env nur kleiner = strenger)

_DEVICE_REGISTRY_FILE = "relay-devices.json"


def _device_registry_path() -> str:
    """Pfad der Geräte-Registry (env ANDROID_RELAY_DEVICE_REGISTRY; Default =
    State-Dir wie relay-keys.json / pairing_password.json)."""
    return os.getenv(
        "ANDROID_RELAY_DEVICE_REGISTRY",
        os.path.join(_default_state_dir(), _DEVICE_REGISTRY_FILE))


def _device_cap(config: Optional[dict] = None) -> int:
    """Geräte-Cap: relay-config 'max_devices' überschreibt env
    ANDROID_RELAY_DEVICE_CAP; hart begrenzt auf [1, 8], ungültig = Default 4."""
    value = None
    if config is not None:
        value = config.get("max_devices")
    if value is None:
        env_value = os.getenv("ANDROID_RELAY_DEVICE_CAP")
        value = env_value if env_value is not None else _DEVICE_REGISTRY_MAX_DEFAULT
    try:
        return max(1, min(_DEVICE_CAP_HARD_MAX, int(value)))
    except (TypeError, ValueError):
        return _DEVICE_REGISTRY_MAX_DEFAULT


def _DEVICE_CAPACITY(state: "_RelayState") -> int:
    """Effektive Kapazität je State (am Start aus der Config gebunden)."""
    return int(state.device_cap) if state.device_cap else _device_cap()


def _device_registry_entry_from_v1_row(row: dict, epoch: int,
                                       admin: bool = False) -> dict:
    """v1-Eintrag (mtls 'enrolled'-Zeile ODER keys-'device'-Zeile) → v2-Geräte-
    Registry-Form (epoch_of_enroll je Gerät; epoch = Fallback ohne Feld)."""
    try:
        entry_epoch = int(row.get("epoch_of_enroll", epoch))
    except (TypeError, ValueError):
        entry_epoch = int(epoch)
    ts = row.get("ts") or row.get("enrolled_at") or 0
    try:
        ts_ms = int(float(ts) * 1000) if float(ts) < 10_000_000_000 else int(float(ts))
    except (TypeError, ValueError):
        ts_ms = 0
    return {
        "alias": str(row.get("alias") or ""),
        "admin": bool(row.get("admin", admin)),
        "epoch_of_enroll": int(entry_epoch),
        "enrolled_at": int(ts_ms),
        "last_seen": int(row.get("last_seen") or 0),
    }


def _load_device_registry_file() -> dict:
    """relay-devices.json lesen — v2 strikt, v1-tolerant (Lesefallback), fehlend
    = {} (frische Registry). Beschädigte Datei = byte-genau als .corrupt-<ts>
    Backup gerettet, dann {} (die Selbst-Adoption baut sie neu auf; kein stiller
    Overwrite der Alt-Bytes)."""
    path = Path(_device_registry_path())
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except (OSError, ValueError):
        logger.error(
            "DEVICE-REGISTRY %s unlesbar — Alt-Bytes bleiben als Backup "
            "erhalten, Registry startet LEER (Selbst-Adoption übernimmt)", path)
        try:
            path.with_name(path.name + ".corrupt-"
                           + time.strftime("%Y%m%d-%H%M%S")).write_bytes(
                path.read_bytes())
        except OSError as exc:
            logger.warning("DEVICE-REGISTRY: Corrupt-Backup fehlgeschlagen: %s", exc)
        return {}
    if not isinstance(data, dict):
        logger.warning("DEVICE-REGISTRY: unerwartetes Format in %s — ignoriert", path)
        return {}
    if data.get("v") == 2 and isinstance(data.get("devices"), dict):
        devices = {}
        for key, row in data["devices"].items():
            if not isinstance(key, str) or not _DEVICE_ID_RE.fullmatch(key) \
                    or not isinstance(row, dict):
                continue  # kaputte Zeilen: übersprungen (fail-closed Form)
            devices[key] = _device_registry_entry_from_v1_row(row,
                                                              int(data.get("epoch") or 1))
        return devices
    # v1-Lesefallback: ein {"devices": {...}} ohne v ODER das Einzel-Gerätein-
    # trag {"v":1, "device": {...}} — beide lesen wir ehrlich (Rollback-Rückweg).
    devices = {}
    if isinstance(data.get("devices"), dict):
        source = data["devices"]
    elif isinstance(data.get("device"), dict):
        source = {"phone": data["device"]}
    else:
        return {}
    epoch = int(data.get("epoch") or 1)
    for key, row in source.items():
        row = row if isinstance(row, dict) else {"device_id": str(key)}
        if not isinstance(key, str) or not _DEVICE_ID_RE.fullmatch(key):
            continue
        devices[key] = _device_registry_entry_from_v1_row(row, epoch, admin=True)
    return devices


# Ein Geräte-Registry-File-Write ist ein kurzer atomarer Block, der je aus dem
# Event-Loop (Handshake-Thread) als aus Sync-Nachbarn (Tests) laufen kann —
# derselbe threading.Lock hält die File-Schreibart seriell (RAM-Zugriffe
# bleiben Event-Loop-gebunden).
_DEVICE_REGISTRY_FILE_LOCK = threading.Lock()


def _store_device_registry(entries: dict, keep_backup: bool = True) -> None:
    """Geräte-Registry atomar (tmp + os.replace, 0600) als v2 schreiben.
    Vor dem ERSTEN v2-Write über einem Nicht-v2-Bestand liegt ein byte-genauer
    Backup (.v1.bak) nebenan — Rollback + Nachvollziehbarkeit (Parität zur
    keys-Registry-Sicherung)."""
    path = Path(_device_registry_path())
    payload = {
        "v": 2,
        "ts": int(time.time() * 1000),
        "devices": {
            key: dict(entries[key]) for key in sorted(entries)
            if isinstance(key, str) and _DEVICE_ID_RE.fullmatch(key)
        },
    }
    with _DEVICE_REGISTRY_FILE_LOCK:
        directory = path.parent
        os.makedirs(directory, exist_ok=True)
        if keep_backup and path.exists():
            backup = path.with_name(path.name + ".v1.bak")
            if not backup.exists():
                try:
                    backup.write_bytes(path.read_bytes())
                except OSError as exc:
                    logger.warning("DEVICE-REGISTRY: v1-Backup fehlgeschlagen: %s", exc)
        fd, tmp = tempfile.mkstemp(prefix=".relay-devices-", dir=directory)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                try:
                    os.fchmod(fh.fileno(), 0o600)
                except AttributeError:
                    pass
                fh.write(json.dumps(payload, indent=2, ensure_ascii=False) + "\n")
            os.replace(tmp, path)
        except BaseException:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise


def _seed_device_registry_v1() -> tuple[dict, str]:
    """v1-Bestand einlesen (MIGRATION, OHNE Verlust — die Quellen werden nur
    GELESEN, nie verändert):
      1. mtls_devices.json (mTLS-'enrolled'-Einträge: je Gerät ein Eintrag mit
         epoch_of_enroll — die Zert-Geräte gelten als BEKANNT);
      2. relay-keys.json v1 (der aktuelle Phone-Eintrag: das bestehende
         Einzel-Gerät — es übernimmt als ADMIN, Sirs aktuelles Gerät).
    Rückgabe: (entries, quellen-Notiz für das LOUD-Log)."""
    entries: dict[str, dict] = {}
    sources: list[str] = []
    # 1) mTLS-Registry (nur LESEN — /etc/mtls_live-Daten werden nie berührt)
    try:
        from tools.mtls_enroll import _registry_load as _mtls_reg, _tls_paths as _mtp
    except ImportError:
        from mtls_enroll import _registry_load as _mtls_reg, _tls_paths as _mtp  # type: ignore[no-redef]
    try:
        reg = _mtls_reg() or {}
        enrolled = reg.get("enrolled") or reg.get("devices") or {}
        epoch = int(reg.get("epoch") or 1)
        for device_id, row in enrolled.items():
            if not isinstance(device_id, str) or not _DEVICE_ID_RE.fullmatch(device_id):
                continue
            if device_id in entries:
                continue
            entries[device_id] = _device_registry_entry_from_v1_row(
                row if isinstance(row, dict) else {}, epoch, admin=False)
        if entries:
            sources.append(f"mtls_devices.json ({len(entries)})")
    except Exception as exc:  # Registry-Verband bleibt beim Start reinlesend
        logger.warning("DEVICE-REGISTRY-Migration: mtls-Quelle unlesbar (%s)", exc)
    # 2) Key-Registry v1 — DER Device-Eintrag des bestehenden Bestands wird
    #    ADMIN (das ist das Gerät, das heute koppelt).
    try:
        keys = _load_device_keys() or {}
        single = keys.get("device") if isinstance(keys, dict) else None
        if isinstance(single, dict):
            device_id = str(single.get("device_id") or "phone").strip()[:64] or "phone"
            if _DEVICE_ID_RE.fullmatch(device_id):
                if device_id in entries:
                    entries[device_id]["admin"] = True
                else:
                    entries[device_id] = _device_registry_entry_from_v1_row(
                        single, 1, admin=True)
                    sources.append("relay-keys.json (admin)")
    except Exception as exc:
        logger.warning("DEVICE-REGISTRY-Migration: keys-Quelle unlesbar (%s)", exc)
    return entries, " + ".join(sources) if sources else "leer (frische Registry)"


def _migrate_device_registry() -> dict:
    """Start-Migration (nur LESEND gegenüberallen v1-Quellen): fehlt die v2-
    Datei, wird der v1-Bestand ADOPTIERT (mtls-Geräte bekannt, Key-Device =
    Admin), unter dem LOUD-Logzeile persistiert und die Registry geliefert.
    Vorhandene v2-Datei = geladen (keine Migration)."""
    path = Path(_device_registry_path())
    if path.exists():
        entries = _load_device_registry_file()
        logger.info("DEVICE-REGISTRY v2 geladen (%d Gerät(e) bekannt)", len(entries))
        return entries
    entries, sources = _seed_device_registry_v1()
    try:
        _store_device_registry(entries)
        logger.warning(
            "DEVICE-REGISTRY v1→v2 MIGRATION: %d Gerät(e) adoptiert aus [%s] — "
            "das bestehende Einzel-Gerät ist ADMIN, alle Geräte bleiben bekannt "
            "(kein Zwangs-Kopplung). Datei: %s (+.v1.bak Backup)",
            len(entries), sources, path)
    except OSError as exc:
        logger.error("DEVICE-REGISTRY: Migration-Persist fehlgeschlagen (fail-soft): %s", exc)
    return entries


def _normalize_registry_device_id(device_id) -> str:
    """Kennung normalisieren (WS-Header None = Legacy ohne Header): "phone" ist
    DER Legacy-Default (Parität /key_publish Z. ~3970); Form-Gate fail-closed."""
    raw = str(device_id or "").strip()
    if not raw:
        raw = "phone"
    return raw if _DEVICE_ID_RE.fullmatch(raw) else "phone"


def _device_known(state: "_RelayState", device_id: str) -> bool:
    entry = state.device_registry.get(device_id)
    return entry is not None and isinstance(entry, dict)


def _pairing_requests_prune(state: "_RelayState") -> None:
    """Tor-Maps nach TTL räumen + hart cappen (Kap. 8 #2: kein ungebundenes
    Anmelde-Pending). Aufruf NUR unter devices_lock."""
    now = time.time()
    for device_id in [k for k, v in state.pairing_requests.items()
                      if float(v.get("expires") or 0) < now]:
        state.pairing_requests.pop(device_id, None)
    for device_id in [k for k, v in state.pairing_approvals.items()
                      if float(v.get("expires") or 0) < now]:
        state.pairing_approvals.pop(device_id, None)
    while len(state.pairing_requests) > _PAIRING_PENDING_MAX:
        oldest = min(state.pairing_requests,
                     key=lambda k: state.pairing_requests[k].get("ts") or 0)
        state.pairing_requests.pop(oldest, None)
    while len(state.pairing_approvals) > _PAIRING_PENDING_MAX:
        oldest = min(state.pairing_approvals,
                     key=lambda k: state.pairing_approvals[k].get("expires") or 0)
        state.pairing_approvals.pop(oldest, None)


def _pairing_approval_valid(state: "_RelayState", device_id: str) -> bool:
    """TTL-Gatter der Freigabe: nur ungenutzte, nicht abgelaufene Freigaben
    öffnen (pairing.approve TTL-abgelaufen = abgelehnt, fail-closed)."""
    entry = state.pairing_approvals.get(device_id)
    if not isinstance(entry, dict) or entry.get("used"):
        return False
    return float(entry.get("expires") or 0) >= time.time()


def _pairing_request_open(state: "_RelayState", device_id: str,
                          remote_ip: str) -> tuple[Optional[dict], bool]:
    """Anmelde-Anfrage anlegen (dedupe: dieselbe Kennung im TTL-Fenster erzeugt
    KEINEN neuen Frame-Spam — der Rückkehr-Flag sagt, ob neu erzeugt); NUR
    frische Anfragen werden als Frame an die Admin-Slots ausgeliefert.
    Aufruf NUR unter devices_lock."""
    now = time.time()
    existing = state.pairing_requests.get(device_id)
    if isinstance(existing, dict) and float(existing.get("expires") or 0) >= now:
        return existing, False  # Dedupe: Anfrage steht, keine Re-Frame-Flut
    request = {
        "request_id": uuid.uuid4(),
        "ts": int(now * 1000),
        "expires": now + _PAIRING_TTL_SECONDS,
        "ip": remote_ip,
    }
    state.pairing_requests[device_id] = request
    _pairing_requests_prune(state)
    return request, True


async def _pairing_request_emit(state: "_RelayState", device_id: str,
                                request: dict) -> None:
    """pairing.request an die lebenden ADMIN-Slots (ohne Admin: an jeden
    lebenden Slot — sonst verstummte das Tor). Kennung maskiert (PII-Regel)."""
    async with (state.devices_lock or _NoAsyncLock()):
        slots = [slot for slot in state.devices.values()
                 if slot.ws is not None and not slot.ws.closed]
        admins = [slot for slot in slots if slot.admin] or slots
    if not admins:
        logger.info(
            "ANMELDE-TOR: kein lebender Slot — Anfrage von %s nur im Log",
            _mask_id(device_id))
        return
    frame = {
        "type": "pairing.request", "v": 1,
        "request_id": str(request["request_id"]),
        "device_id_masked": _mask_id(device_id),
        "ip": request.get("ip") or "",
        "ts": int(time.time() * 1000),
        "ttl_s": _PAIRING_TTL_SECONDS,
    }
    for slot in admins:
        try:
            await slot.ws.send_json(frame)
        except Exception as exc:
            logger.warning("pairing.request-Zustellung fehlgeschlagen (%s): %s",
                           _mask_id(device_id), exc)


async def _handle_pairing_admin_frame(state: "_RelayState", sender_device_id: str,
                                      data: dict) -> None:
    """pairing.approve / pairing.deny vom Admin-Gerät (WS-Bein authentifiziert).
    
    approve: Anfrage (falls vorhanden) verbrauchen + Freigabe (TTL 10 min,
    einmalig) je device_id anlegen — der NÄCHSTE Handshake dieses Geräts
    enrollt. deny: Anfrage räumen (kein stiller Anschluss; Admin sieht nichts
    mehr). Rechts-Norm: NUR der ADMIN-Slot verwaltet (PH5-T7); ein Non-Admin-
    Gerät (falls je keins) wird abgewiesen — fail-closed."""
    async with (state.devices_lock or _NoAsyncLock()):
        slot = state.devices.get(sender_device_id)
        if slot is None or slot.ws is None or slot.ws.closed:
            logger.warning("pairing-Frame von unbekanntem Slot %s — verworfen",
                           _mask_id(sender_device_id))
            return
        is_admin = bool(slot.admin) or not any(
            other.admin for other in state.devices.values())
    if not is_admin:
        logger.warning(
            "ANMELDE-TOR: approve/deny von NON-ADMIN-Gerät %s abgewiesen (PH5-T7)",
            _mask_id(sender_device_id))
        return
    body_device_id = str(data.get("device_id") or "").strip()
    request_id = str(data.get("request_id") or "").strip()
    device_id = body_device_id
    if not device_id and request_id:
        for candidate, entry in state.pairing_requests.items():
            if str(entry.get("request_id")) == request_id:
                device_id = candidate
                break
    if not device_id or not _DEVICE_ID_RE.fullmatch(device_id):
        logger.warning("pairing-Frame ohne gültige device_id/request_id — verworfen")
        return
    if data.get("type") == "pairing.deny":
        state.pairing_requests.pop(device_id, None)
        logger.info("ANMELDE-TOR: Anfrage von %s ABGEWIESEN (deny)",
                    _mask_id(device_id))
        return
    now = time.time()
    # Freigabe anlegen — eine BESTEHENDE (noch gültige) Freigabe wird frisch
    # gestempelt (Admin kann neu freigeben, alter Stempel verfällt mit TTL).
    state.pairing_requests.pop(device_id, None)
    state.pairing_approvals[device_id] = {"expires": now + _PAIRING_TTL_SECONDS,
                                          "used": False}
    while len(state.pairing_approvals) > _PAIRING_PENDING_MAX:
        oldest = min(state.pairing_approvals,
                     key=lambda k: state.pairing_approvals[k].get("expires") or 0)
        state.pairing_approvals.pop(oldest, None)
    logger.warning(
        "ANMELDE-TOR: Freigabe für %s erteilt (Admin %s, TTL %d s, einmalig) — "
        "der NÄCHSTE Kopplungs-Handshake dieses Geräts enrollt",
        _mask_id(device_id), _mask_id(sender_device_id), _PAIRING_TTL_SECONDS)


async def _handle_phone_backfill_request(state: "_RelayState", sender_device_id: str,
                                         data: dict) -> None:
    """B4: chat.backfill_request (Phone→Agent, Klar-Steuer-Frame ids-only).

    Der App-Vertrag (ChatBackfillRules.requestFrame) sendet {type, v, chat_id,
    gateway_chat, limit} OHNE request_id — heute (VOR B4) still verworfen.
    Router-Kette: chat_owner (Konversations-Wahrheit am Relay) → Agent-WS, mit
    device_id-Stempel des ANFORDERNDEN Geräts (das Adapter-Pin-/Session-Routing
    kann die Antwort-Session sonst nicht auflösen). Fail-closed: unbekannte
    chat_id (kein Owner) = verworfen; EIGENER Rate-Bucket je Gerät (der
    Adapter-Verband trägt zusätzlich das B3_BACKFILL_RATE_S-Schild je Session)."""
    chat_id = _enc_frame_valid_chat_id(data.get("chat_id"))
    if chat_id is None:
        logger.warning("Backfill-Request von Gerät %s ohne gültiges chat_id — verworfen",
                       _mask_id(sender_device_id))
        return
    sender_key = _normalize_registry_device_id(sender_device_id)
    if not _chat_rate_allow(f"backfillreq:{sender_device_id}", 2):
        logger.info("Backfill-Request von Gerät %s über Rate-Cap — verworfen",
                    _mask_id(sender_device_id))
        return
    gateway_chat = data.get("gateway_chat")
    if not isinstance(gateway_chat, str) or not gateway_chat.strip() \
            or len(gateway_chat) > 128 or not _utf8_encodable(gateway_chat):
        # Tolerant wie der App-Parser: fehlender/ungültiger Feldname → fallback
        # auf chat_id (identischer Handle laut Plan Kap. 4.1).
        gateway_chat = chat_id
    else:
        gateway_chat = gateway_chat.strip()
    try:
        limit = int(data.get("limit"))
    except (TypeError, ValueError):
        limit = 200
    limit = max(1, min(200, limit))
    async with state.broker_lock:
        owner = state.chat_owner.get(chat_id)
    agent_id = owner[0] if owner else None
    if agent_id is None:
        # Kap. 2.3-Fallback (frisches Gerät): chat_owner existiert für eine
        # NIE gepostete Wire-chat_id NICHT — der SESSION-BROKER kennt die
        # (device → agent)-Gegenstelle über das HELLO des anfragenden Geräts.
        # Bei mehreren Agenten-Gegenständen gewinnt die FRISCHESTE Session
        # (das Hello kurz vor dem Backfill-Request ist die Chat-Wahl).
        async with state.broker_lock:
            stale_sessions = [
                (entry.get("ts") or 0.0, entry.get("agent_id"))
                for entry in state.crypto_sessions.values()
                if isinstance(entry, dict) and entry.get("device_id") == sender_key
            ]
        if stale_sessions:
            agent_id = max(stale_sessions)[1]
        if not agent_id:
            logger.warning(
                "Backfill-Request für unbekannte chat_id %s (Gerät %s) — verworfen "
                "(fail-closed: kein Konversations-Owner, keine Geräte-Session)",
                _log_id(chat_id), _mask_id(sender_device_id))
            return
        logger.info(
            "Backfill-Request ohne chat_owner — Router-Fallback über den "
            "Session-Broker (Gerät %s → Agent %s)",
            _mask_id(sender_device_id), agent_id)
    frame = {
        "type": "chat.backfill_request", "v": 1,
        "chat_id": chat_id,
        "gateway_chat": gateway_chat,
        "limit": limit,
        # Stempel: WELCHES Gerät fragt (Antwort-Session-Auflösung am Adapter).
        "device_id": _normalize_registry_device_id(sender_device_id),
        "ts": int(time.time() * 1000),
    }
    delivered = await _deliver_to_agent(state, agent_id, frame)
    if not delivered:
        logger.info("Agent %s offline — chat.backfill_request von Gerät %s gespoolt",
                    agent_id, _mask_id(sender_device_id))


def _device_revoke_id(state: "_RelayState", device_id: str) -> str:
    """B4: EPHEMERES Revoke-Handle je bekanntem Gerät (in-memory request_id).
    Die App kennt NUR maskierte Kennungen — das relay.state devices[]-Feld
    'request_id' ist der Antwortträger des device_revoke-Frames (Parität
    pairing.approve-Norm: request_id ODER raw device_id). Mint faul je Lese-
    Zugriff; Revok entbindet das Handle. Verwaiste Handles werden lazily
    geräumt (Cap = 2× Registry-Größe, kein ungebundenes Wachstum)."""
    handle = state.device_revoke_ids.get(device_id)
    if handle is None:
        handle = uuid.uuid4().hex[:16]
        state.device_revoke_ids[device_id] = handle
        while len(state.device_revoke_ids) > max(1, len(state.device_registry)) * 2 \
                and state.device_revoke_ids:
            first = next(iter(state.device_revoke_ids))
            # Nur verwaiste Handles (Gerät nicht mehr registriert) räumen.
            if first in state.device_registry:
                break
            state.device_revoke_ids.pop(first)
    return handle


async def _handle_phone_device_revoke(state: "_RelayState", sender_device_id: str,
                                      data: dict) -> None:
    """B4: device_revoke (Kap. 4.2) — Gerät entfernen via request_id (Revoke-
    Handle ODER offene Kopplungs-Anfrage) oder raw device_id; OHNE Ziel = das
    EIGENE Gerät (Abmelden, für JEDES Gerät erlaubt). Fail-closed:
    - Fremd-Gerät entfernen darf NUR der ADMIN-Slot (kein 'kein Admin
      vorhanden'-Fallback — das Anmelde-Tor hat seine eigene Norm).
    - Unauflösbare/unbekannte Ziele = verworfen (LOUD-Log, keine Wirkung).
    Verband: Registry-Eintrag weg (+persist), Session-Broker-Einträge des
    Geräts weg, je-Agent-Adapter via device.revoked-Frame benachrichtigt
    (Session + Pin-Kappung dort), WS des Zielgeräts close (4404/Familie,
    reason 'device-revoked'), Outbox-Bucket entleert, Rest-Geräte bekommen
    den neuen relay.state-Stand."""
    sender_key = _normalize_registry_device_id(sender_device_id)
    body_device_id = str(data.get("device_id") or "").strip()
    request_id = str(data.get("request_id") or "").strip()
    target_ws: Optional[web.WebSocketResponse] = None
    registry_changed = False
    async with (state.devices_lock or _NoAsyncLock()):
        sender_slot = state.devices.get(sender_key)
        if sender_slot is None or sender_slot.ws is None or sender_slot.ws.closed:
            logger.warning("device_revoke von unbekanntem/totem Slot %s — verworfen",
                           _mask_id(sender_key))
            return
        target = None
        # (a) Revoke-Handle je bekanntem Gerät (relay.state devices[].request_id).
        if request_id:
            for candidate, handle in state.device_revoke_ids.items():
                if handle == request_id:
                    target = candidate
                    break
            if target is None:
                # Fallback: offene Kopplungs-Anfrage desselben Handle-Konzepts.
                _pairing_requests_prune(state)
                for candidate, entry in state.pairing_requests.items():
                    if str(entry.get("request_id") or "") == request_id:
                        target = candidate
                        break
        # (b) raw device_id (Admin-/Tool-Weg; App trägt bewusst NIE rohe ids).
        if target is None and body_device_id:
            candidate = _normalize_registry_device_id(body_device_id)
            if _device_known(state, candidate) \
                    or (candidate == sender_key and sender_key in state.devices):
                target = candidate
        # (c) ohne Ziel = eigenes Gerät abmelden.
        if target is None:
            target = sender_key
        if target != sender_key and not sender_slot.admin:
            logger.warning(
                "device_revoke: NON-ADMIN Gerät %s wollte %s entfernen — ABGEWIESEN "
                "(fail-closed Kap. 4.2: nur Admin-Slots entfernen; eigenes Abmelden bleibt frei)",
                _mask_id(sender_key), _mask_id(target))
            return
        if not _device_known(state, target) and target not in state.devices:
            logger.warning(
                "device_revoke: Ziel %s weder Registry noch Slot — verworfen",
                _mask_id(target))
            return
        was_known = state.device_registry.pop(target, None)
        registry_changed = was_known is not None
        state.device_revoke_ids.pop(target, None)
        state.pairing_requests.pop(target, None)
        state.pairing_approvals.pop(target, None)
        state.device_outbox.pop(target, None)  # kein Spool-Bestand für tote Geräte
        target_slot = state.devices.get(target)
        if target_slot is not None and target_slot.ws is not None \
                and not target_slot.ws.closed:
            target_ws = target_slot.ws
        _loud_revoke_log(state, sender_key, target, was_known, bool(was_known))

    # Session-Broker: alle E2E-Session-Entries des Zielgeräts entbinden (kein
    # Routing mehr an das revokte Gerät; der Adapter kappet die Sessions/Pins
    # selbst via device.revoked-Frame).
    async with state.broker_lock:
        stale = [sid for sid, entry in state.crypto_sessions.items()
                 if isinstance(entry, dict) and entry.get("device_id") == target]
        for sid in stale:
            state.crypto_sessions.pop(sid, None)
        if stale:
            logger.info("DEVICE-REVOKE: %d Session-Broker-Einträge von Gerät %s entbunden",
                        len(stale), _mask_id(target))
    if registry_changed:
        try:
            _store_device_registry(state.device_registry)
        except OSError as exc:
            logger.error("DEVICE-REGISTRY: Revoke-Persist fehlgeschlagen (%s)", exc)
    if target_ws is not None:
        try:
            await target_ws.close(code=_WS_CLOSE_CODE_DEVICE_REVOKED,
                                  message=_WS_CLOSE_REASON_DEVICE_REVOKED)
        except Exception as exc:
            logger.debug("Revoke-Close fehlgeschlagen (%s) — Slot stirbt mit dem Socket", exc)
    # Adapter benachrichtigen (Session/Pin-Kappung je Agent, fire-and-forget).
    await _notify_agents_device_revoked(state, target)
    # Rest-Geräte: frischer relay.state-Stand (DevicesCard verliert das Gerät).
    await _broadcast_agent_presence(state)


def _loud_revoke_log(state, sender_key, target, was_known_entry, was_known: bool) -> None:
    """LOUD-Revoke-Log (Kap. 4.2: kein stilles Entfernen). Kennungen IMMER
    maskiert (PII-Regel, _mask_id); was_known_entry = ggf. gephoppte
    Registry-Zeile (nur für den Verbleib-Zähler von Interesse)."""
    del was_known_entry
    logger.warning(
        "DEVICE-REVOKE: Gerät %s von Slot %s entfernt (Registry=%s, %d Geräte "
        "verbleiben) — WS-Kick (4404 device-revoked) + Adapter-Sessions/Pins kappen",
        _mask_id(target), _mask_id(sender_key), was_known,
        len(state.device_registry))


async def _notify_agents_device_revoked(state: "_RelayState", device_id: str) -> None:
    """device.revoked an alle verbundenen Agent-WS (Adapter kappet dort die
    E2E-Session(s) + Pin-Eintrag(e) des Geräts je Agent). Raw device_id ist hier
    Legitimität an den Backend-Bein (die Session-Map des Adapters trägt sie
    bereits aus dem Hello-Verband); die LOG-Ausgabe bleibt _mask_id."""
    frame = {"type": "device.revoked", "v": 1, "device_id": device_id,
             "ts": int(time.time() * 1000)}
    async with state.agent_ws_lock:
        sockets = [ws for ws in state.agent_ws.values()
                   if ws is not None and not ws.closed]
    for ws in sockets:
        try:
            await ws.send_json(frame)
        except Exception as exc:
            logger.warning("device.revoked-Zustellung fehlgeschlagen: %s", exc)


class _NoAsyncLock:
    """Fallback-Kontext für Pfad vor erster Lock-Bindung (keine Device-Aktionen
    vor _run_loop existent — Anfragen/Tore sind dann leer)."""

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False


def _device_gate_decide(state: "_RelayState", device_id_raw,
                        remote_ip: str) -> tuple[str, str]:
    """B1-Anmelde-Entscheidung (W17.1-Sperre → Tor):
      - ("accepted", key)   : bekanntes Gerät (direkt, Reconnect ersetzt nur
                              SEINEN Slot) — Cap gilt NICHT (bekannt bleibt).
      - ("enroll", key)     : unbekannt, aber Tor BESTANDEN (gültige Freigabe
                              ODER verifiziertes Kopplungs-Passwort) — das
                              Enrollment passiert ATOMAR bei der Registrierung.
      - ("device-cap", key) : unbekannt + Registry voll → 403 device-cap.
      - ("rejected", key)   : unbekannt ohne Tor → 403/4403 code-in-use
                              (bestehende Fehlton) + Rate-Chain.
    Aufruf NUR unter devices_lock; EMIT des pairing.request-frames passiert
    außerhalb (await-Punkt)."""
    device_id = _normalize_registry_device_id(device_id_raw)
    if _device_known(state, device_id):
        return "accepted", device_id
    if not state.device_registry:
        # Erst-Annahme (MIGRATION Kap. 5): leere Registry → die erste Annahme
        # ist Selbst-Adoption (Kein Zwangs-Kopplung; das Gerät wird als Admin
        # übernommen, LOUD-Enroll-Log bei der Registrierung). Ab 1 Eintrag
        # gilt Registry-Mode mit Tor.
        return "enroll", device_id
    cap = _DEVICE_CAPACITY(state)
    if len(state.device_registry) >= cap:
        return "device-cap", device_id
    _pairing_requests_prune(state)
    if _pairing_approval_valid(state, device_id):
        return "enroll", device_id
    if state.pairing_pw is not None:
        # Kopplungs-Passwort am Relay konfiguriert → das W17.2-Tor ist DER
        # Zugang: dieses Handshake hat das Passwort bereits verifiziert
        # (W17.2-Gate oben) → Direkt-Enroll (Option b).
        return "enroll", device_id
    return "rejected", device_id


# ── W17-F2: Optionales Kopplungs-Passwort (nur Hash) ─────────────────────────

_PAIRING_PW_MIN_LEN = 8
_PAIRING_PW_MAX_LEN = 256
_PAIRING_PW_DEFAULT_ITER = 200_000
_PAIRING_PW_MIN_ITER = 1_000      # Schutz gegen fahrlässig gespeicherte Schilder
_PAIRING_PW_MAX_ITER = 10_000_000

_PAIRING_PW_RECORD_FILE = "pairing_password.json"


def _pairing_password_path() -> str:
    """Ablage des Hash-Records (kein Klartext). Env-überschreibbar für Tests;
    Default = State-Dir (Plugin-Datenverzeichnis / /opt-Fallback) im 0600-File."""
    return os.getenv("ANDROID_RELAY_PASSWORD_FILE") or os.path.join(
        _default_state_dir(), _PAIRING_PW_RECORD_FILE)


def _pairing_pw_iterations() -> int:
    """PBKDF2-Runden — env nur für Tests/Notfall-Tuning; Default OWASP-nah."""
    try:
        value = int(os.getenv("ANDROID_RELAY_PW_ITERATIONS",
                              str(_PAIRING_PW_DEFAULT_ITER)))
    except (TypeError, ValueError):
        return _PAIRING_PW_DEFAULT_ITER
    return max(_PAIRING_PW_MIN_ITER, min(_PAIRING_PW_MAX_ITER, value))


def _pairing_password_derive(password: str, salt: bytes, iterations: int) -> bytes:
    """PBKDF2-HMAC-SHA256 (stdlib, lizenz- und dep-frei) über die roten Bytes."""
    return hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, iterations)


def _pairing_password_record_for(password: str) -> dict:
    """Neuer Hash-Record für /password_set — Salt frisch, Klartext NIE abgelegt."""
    salt = secrets.token_bytes(16)
    iterations = _pairing_pw_iterations()
    digest = _pairing_password_derive(password, salt, iterations)
    return {
        "v": 1,
        "method": "pbkdf2-sha256",
        "iter": iterations,
        "salt_hex": salt.hex(),
        "hash_hex": digest.hex(),
    }


def _valid_pairing_password_record(record) -> bool:
    """Form-Prüfung eines Hash-Records (Ladepfad) — fail-closed ohne Record."""
    if not isinstance(record, dict):
        return False
    if record.get("method") != "pbkdf2-sha256":
        return False
    try:
        iterations = int(record.get("iter"))
    except (TypeError, ValueError):
        return False
    if not (_PAIRING_PW_MIN_ITER <= iterations <= _PAIRING_PW_MAX_ITER):
        return False
    salt_hex = record.get("salt_hex")
    hash_hex = record.get("hash_hex")
    if not (isinstance(salt_hex, str) and isinstance(hash_hex, str)):
        return False
    if len(salt_hex) != 32:          # 16 Bytes Salt
        return False
    try:
        return bool(bytes.fromhex(salt_hex)) and bool(bytes.fromhex(hash_hex))
    except ValueError:
        return False


def _load_pairing_password_record() -> Optional[dict]:
    """Hash-Record vom 0600-File laden; alles Ungültige = None (Schutz aus,
    Warn-Log — kein Crash beim Start)."""
    path = _pairing_password_path()
    try:
        with open(path, "r", encoding="utf-8") as fh:
            record = json.load(fh)
    except FileNotFoundError:
        return None
    except (OSError, ValueError):
        logger.warning("W17-F2: %s unlesbar — Passwortschutz läuft aus (fail-closed)", path)
        return None
    if not _valid_pairing_password_record(record):
        logger.warning(
            "W17-F2: %s ungültig (Form) — Passwortschutz läuft aus (fail-closed)", path)
        return None
    return record


def _store_pairing_password_record(record: dict) -> None:
    """Hash-Record atomar auf 0600. Kein Klartext im File, nichts im Log."""
    path = _pairing_password_path()
    payload = json.dumps(record, sort_keys=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(payload)
        # File-Modus absichern, auch wenn der Pfad VOR diesem Write 0-open war.
        os.chmod(path, 0o600)
    except Exception:
        try:
            os.unlink(path)
        except OSError:
            pass
        raise


def _clear_pairing_password_record(state: "_RelayState") -> None:
    """Schutz entfernen: File löschen (muss es) + RAM-Record None (immer)."""
    path = _pairing_password_path()
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass
    except OSError:
        logger.warning("W17-F2: Hash-File %s konnte nicht gelöscht werden", path)
    state.pairing_pw = None


def _pairing_password_verify(state: "_RelayState", supplied: str) -> bool:
    """Konstante-Zeit-Verifikation eines gelieferten Kopplungs-Passworts gegen
    den gespeicherten Hash-Record. Jede Ausnahme = False (fail-closed)."""
    record = state.pairing_pw
    if not isinstance(record, dict):
        return False
    try:
        salt = bytes.fromhex(str(record["salt_hex"]))
        iterations = int(record["iter"])
        expected = bytes.fromhex(str(record["hash_hex"]))
    except (KeyError, TypeError, ValueError):
        return False
    if not salt or not expected or iterations < _PAIRING_PW_MIN_ITER:
        return False
    if not supplied:
        return False
    try:
        candidate = _pairing_password_derive(supplied, salt, iterations)
    except Exception:
        return False
    return hmac.compare_digest(candidate, expected)


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


def _call_rate_per_second(config: Optional[dict] = None) -> int:
    """W21 (Anruf P1): eigenes Signaling-Ratenlimit je Agent (Buckets 'callsign:*').

    relay-config.json (CALL_RATE_PER_SECOND) überschreibt env
    ANDROID_RELAY_CALL_RATE; Default 2/s — ein Anruf ist ein seltener Event,
    der Invite darf den Chat-/Media-Buckets NICHT die Plätze wegnehmen
    (FLIPSI-Frame-Guard-Verband: ein neuer Handler klingt NIE heiße Pfade ab).
    """
    value = None
    if config is not None:
        value = config.get("CALL_RATE_PER_SECOND")
    if value is None:
        value = os.getenv("ANDROID_RELAY_CALL_RATE", "2")
    try:
        return max(1, int(value))
    except (TypeError, ValueError):
        return 2


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
_REPLAY_WINDOW = _env_clamped_int(
    "ANDROID_RELAY_REPLAY_WINDOW", 100, 10, 100)        # Counter-Fenster je Richtung (Relay-Grobprüfung; O4: Env nur verschärfen)
_FRAME_MAX_AGE_MS = _env_clamped_int(
    "ANDROID_RELAY_FRAME_MAX_AGE_MS", 10 * 60 * 1000, 60 * 1000, 10 * 60 * 1000)  # Frame-Freshness (Replay-Fenster 100 + 10 min; O4: Env nur verschärfen)
# SEC-W1 B F-06: Sanity-Cap für hdr.n/pn — ein Riesen-n (PoC: 10^20) wurde als
# "neu" akzeptiert (max=n) und vergiftete danach das Fenster (legitimes n=5
# fiel auf 429 'replay' → Session-DoS). 2^32 Nachrichten je Ratchet-Kette
# sind physikalisch unerreichbar; alles darüber = Zähler-Gift → verwerfen.
_REPLAY_N_MAX = 1 << 32

# ── W21 (Anruf-Backend P1, roadmap-anruf.md §3.4): Call-Signaling ─────────────
# Neuer, KLEINER Frame-Satz über die BESTEHENDEN E2E-Kanäle — kein WebRTC, kein
# Audioweg am Relay (§3.1 bewusst: Nutzeraudio wird on-device transkribiert,
# Agentenantworten sind TTS am Phone). Signaling-Inhalte (kind/reason/duration)
# liegen AUSSCHLIESSLICH im Ratchet-ct; Klar bleiben nur IDs (chat_id, call_id,
# session, hdr) — Parität chat.reply.enc/chat.read.enc. Fail-closed: NUR
# bestätigte E2E-Sessions je Richtung; EIGENE Rate-Buckets ('callsign:*', nie im
# heißen Chat-Bucket); KEINE neue Route, KEINE Call-State-Maschine am Relay —
# der Relay bleibt opaker Pass-Through (Verband wie chat.sync.enc B4).
_CALL_INVITE_ENC_FRAME = "call_invite.enc"   # Agent→Phone: klingeln
_CALL_END_ENC_FRAME = "call_end.enc"         # Agent→Phone: auflegen
_PHONE_CALL_ENC_FRAMES = frozenset({
    "call_ringing.enc",   # App quittiert das Klingeln (§3.4)
    "call_accepted.enc",  # Nutzer hat angenommen
    "call_declined.enc",  # Nutzer hat abgelehnt
    "call_missed.enc",    # App meldet verpasst (Ring ohne Annahme)
    "call_ended.enc",     # Gespräch beendet (App oder Nutzer)
})
_CALL_ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
_CALL_SIGNAL_MAX_PER_SECOND = 6  # Phone→Relay Signaling je Gerät (Burst-Schild)

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
# W21 (G12, roadmap-anruf.md §7.2): kind "screengrab" = Agent-Screenshot aus dem
# Bedienungshilfen-Pfad (RAM → E2E-Bild-Message, NIE Galerie/MediaStore). Der
# Relay prüft nur die Metadaten-Form — der Bild-Inhalt bleibt im ct (opak).
_MEDIA_KINDS = frozenset({"image", "video", "file", "voice", "screengrab"})
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
        # W21 (G12): "screengrab" ist ein eigener, erlaubter Meta-Kind-Wert.
        if not isinstance(kind, str) or kind not in _MEDIA_KINDS:
            return "kind missing/invalid (image/video/file/voice/screengrab)"
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
_CHAT_QUEUE_MAX_BYTES = _env_clamped_int(
    "ANDROID_RELAY_CHAT_SPOOL_MAX_BYTES", 8 * 1024 * 1024, 64 * 1024,
    8 * 1024 * 1024)  # Bytes-Budget je Broker-Queue (O4 Spool-Quota: Env nur verschärfen)
_CHAT_ACTIVITY_MAX = 256           # SEC-W1 D2: Dedup-Map-Cap (war unbounded)
# W19 (SIRS-Neued): agent.status-Dedup-Map (letzter working-Wert je agent_id)
# — FIFO-Cap Parität chat_activity.
# J4-Fund 3: der Band-Wert ist das (working, label)-Paar je agent_id — ein
# Label-Wechsel bei gleichem working ist ein Transition („Sucht im Web" muss
# die App erreichen); Slot-Frames (sub/end) schreiben das Band NICHT.
_AGENT_STATUS_MAX = 256
# W4-FIX-JAG2 (S6): chat_status-Cap — die 24-h-Retention (_prune_chat_status)
# hilft nicht gegen einen Flood FRISCHER msg_ids binnen der Retention (RAM-
# Flood-Schwester von SEC-W1 D2, nur je msg_id statt je chat_id). FIFO-Cap
# Parität zu chat_activity/_cap_chat_owner.
_CHAT_STATUS_MAX = 256
# FIN-T1 RED-06: Owner-Isolation überlebt die Spool-Retention — chat_owner
# wird NICHT mehr ts-geprüft (PoC red06: nach Retention-Ablauf claimte ein
# fremder Agent die chat_id mit 200 und beliefert sie = Cross-Agent-Injektion
# in die Konversation). RAM-Schutz stattdessen per FIFO-Cap: 50 000 chat_ids
# (~10 MB Worst Case, Jahre Betrieb) — weit jenseits jeder realen Nutzung.
_CHAT_OWNER_MAX = 50_000
_AGENT_LIGHT_FRAME_MAX_PER_SECOND = 10  # SEC-W1 W1C-9: keepalive/ping/presence
# W16-J4F1: Meta/Models-Handler haben einen EIGENEN Light-Bucket —
# sonst verdrängt ein session_meta/models-Push die Rate-Plätze von
# chat.reply (gleiches chat_event-Bucket = stiller Nachrichtenverlust).
_AGENT_META_LIGHT_MAX_PER_SECOND = 6  # ~2 Frames je Meta-Event, Puffer
# J4-Fund 2: cron.status/.enc bekommen einen EIGENEN Light-Bucket (Parität
# W16-J4F1 für agentmeta — NIE einen geteilten Bucket mit chat.reply: ein
# Cron-Refresh verdrängt sonst live Chat-Nachrichten; zwei Buckets = zwei
# Budgets je Richtung). ~1 Frame je Connect/Handshake + Refresh je Änderung.
_CRON_STATUS_MAX_PER_SECOND = 6
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


# ── PH-4-T2: Relay-Backend-Identität (Ed25519) ──────────────────────────────
# EINE Identität je Relay-INSTANZ, einmalig generiert + persistent über
# Neustarts (Pfad-Verband am TLS-/Config-Stamm: <tls-dir>/../identity, Live
# = /etc/flipsibridge/identity; Env-Override für Tests/Daemon). Relay-ID =
# SHA-256(Ed25519-Pub) als Hex — WIRD GELoggt, der private Schlüssel geht
# NIE in irgendein Log (Audits wie PH-3).
#
# KDoc-Begründung „leistungsschonende Signatur v1": TLS (+ optionales mTLS-
# Gate) trägt die Transport-Integrität JEDES Frames; die Ed25519-Schicht ist
# Instanz-IDENTITÄT („dieses Relay") + OFFLINE-Nachweisbarkeit (Handshake-
# Transkripte sind auch außerhalb des TLS-Streams gegen den Instanz-Key
# belegbar — Forensik/Support). Signiert werden deshalb NUR der Handshake-
# Verband (crypto.hello + crypto.hello_ack + crypto.session_reset) und die
# agent.ident-Nachricht — KEIN jeder Chat-Frame (chat.*-Inhalte sind doppelt
# ratchet-enc mit AAD; das Relay ist bewusst opak und trägt dort außer
# Transport-Integrität (TLS) keine zusätzliche Signatur). Verification am
# Phone (hello_ack/session_reset) bleibt App-Kandidat W23 — das sig-Feld
# ist dort harmlos (unbekannte Felder ignoriert der Parser).
_RELAY_IDENTITY_ENV = "ANDROID_RELAY_IDENTITY_DIR"
_RELAY_IDENTITY_FILE = "relay-identity.json"
_RELAY_IDENTITY_LOCK = threading.Lock()
# Cache nach Lazy-Load (0600-Datei; der private Schlüssel geht NIE in Logs).
_RELAY_IDENTITY_CACHE: Optional[dict] = None


def _relay_identity_dir() -> Optional[str]:
    """Pfad-Lotse der Identity-Ablage: 1) Env {_RELAY_IDENTITY_ENV};
    2) <TLS-DIR>/../identity (P23-Verband /etc/flipsibridge/{tls,identity});
    3) <state-dir>/identity (Relay-schreibbarer State-Stamm). Erstes Level,
    dessen Erzeugung klappt, gewinnt; nichts klappbar → None (Identität
    fällt bewusst auf ephemere RAM-Keys zurück — Warn-Log, Relays-ID dreht
    je Start, TOFU-Pins am Adapter gehen auf Warn)."""
    candidates = []
    env_dir = os.getenv(_RELAY_IDENTITY_ENV, "").strip()
    if env_dir:
        candidates.append(env_dir)
    tls_dir = os.getenv("ANDROID_RELAY_TLS_DIR", "").strip()
    if tls_dir:
        candidates.append(os.path.join(os.path.dirname(tls_dir.rstrip("/")),
                                       "identity"))
    try:
        candidates.append(os.path.join(_default_state_dir(), "identity"))
    except Exception:
        pass
    for folder in candidates:
        try:
            os.makedirs(folder, mode=0o700, exist_ok=True)
            probe = os.path.join(folder, ".write-probe")
            with open(probe, "w", encoding="utf-8") as fh:
                fh.write("ok")
            os.remove(probe)
            return folder
        except Exception:
            continue
    return None


def _relay_identity_path() -> Optional[str]:
    folder = _relay_identity_dir()
    return None if folder is None else os.path.join(folder, _RELAY_IDENTITY_FILE)


def _relay_identity() -> Optional[dict]:
    """Identität der Relay-Instanz laden ODER einmalig erzeugen (Datei 0600,
    JSON {priv, relay_id, created_at}). Fail-soft: cryptography fehlt
    oder die Ablage ist unbenutzbar → RAM-Keys (ephemer), Warn-Log. Der
    private Schlüssel verlässt NIE dieses Modul (nur Sign-Bytes)."""
    global _RELAY_IDENTITY_CACHE
    if _RELAY_IDENTITY_CACHE is not None:
        return _RELAY_IDENTITY_CACHE
    with _RELAY_IDENTITY_LOCK:
        if _RELAY_IDENTITY_CACHE is not None:
            return _RELAY_IDENTITY_CACHE
        # Wichtig: cryptography IST Relay-Pflicht-Dependency (requirements).
        try:
            from cryptography.hazmat.primitives.asymmetric.ed25519 import (
                Ed25519PrivateKey,
            )
            from cryptography.hazmat.primitives import serialization
        except Exception:
            logger.warning(
                "PH-4-T2: cryptography ohne Ed25519 — Relay-Identität deaktiviert")
            return None
        priv_hex = None
        created_at = None
        fresh_keygen = False
        persist_ok = False
        path = _relay_identity_path()
        if path is not None:
            try:
                with open(path, "r", encoding="utf-8") as fh:
                    stored = json.load(fh)
                candidate = str(stored.get("priv") or "")
                if len(candidate) == 64 and not any(
                        c not in "0123456789abcdef" for c in candidate.lower()):
                    priv_hex = candidate.lower()
                    created_at = stored.get("created_at")
                    persist_ok = True  # geladene Datei = persistenter Stand
            except FileNotFoundError:
                pass  # erster Start → unten generieren + schreiben
            except Exception as exc:
                logger.warning(
                    "PH-4-T2: Identity-File unlesbar (%s) — neu generiert",
                    type(exc).__name__)
        if priv_hex is None:
            try:
                sk = Ed25519PrivateKey.generate()
                priv_hex = sk.private_bytes(
                    serialization.Encoding.Raw,
                    serialization.PrivateFormat.Raw,
                    serialization.NoEncryption()).hex()
                created_at = datetime.datetime.now(
                    datetime.timezone.utc).isoformat()
                fresh_keygen = True
            except Exception:
                logger.warning("PH-4-T2: Ed25519-Keygen fehlgeschlagen")
                return None
        try:
            sk = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(priv_hex))
            pub_hex = sk.public_key().public_bytes(
                serialization.Encoding.Raw,
                serialization.PublicFormat.Raw).hex()
        except Exception:
            logger.warning("PH-4-T2: Pubkey-Derivation fehlgeschlagen")
            return None
        relay_id = hashlib.sha256(bytes.fromhex(pub_hex)).hexdigest()
        if fresh_keygen and path is not None:
            try:
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(fd, "w", encoding="utf-8") as fh:
                    json.dump({"priv": priv_hex, "relay_id": relay_id,
                               "created_at": created_at}, fh)
                persist_ok = True
            except Exception as exc:
                logger.warning(
                    "PH-4-T2: Identity-File nicht schreibbar (%s) — ephemere Identity",
                    type(exc).__name__)
                path = None
        _RELAY_IDENTITY_CACHE = {
            "priv": bytes.fromhex(priv_hex), "pub_hex": pub_hex,
            "relay_id": relay_id, "created_at": created_at,
            "path": path if persist_ok else None,
        }
        logger.info("PH-4-T2: Relay-Identität aktiv (relay_id=%s%s)",
                    relay_id[:16] + "…",
                    "" if persist_ok else " [ephemeral-Verband]")
        return _RELAY_IDENTITY_CACHE


def _canonical_meta_bytes(meta: dict) -> bytes:
    """Kanonische Frame-Bytes der Signatur (flaches Metadaten-Dict): sortierte
    Keys, keine Spaces — PARITÄT zum Adapter (contrib/hermes-chat-plugin/
    adapter.py _relay_canonical_bytes) für die Cross-Komponente-Verifikation."""
    return json.dumps(meta, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def _relay_sign_meta(frame: dict) -> None:
    """Signiert den FLACHEN Metadaten-Envelope eines Frames in-place:
    frame['sig'] = base64(Ed25519(canonical(frame ohne sig))). Fail-soft:
    ohne Identität bleibt der Frame unverändert (Legacy-Relay-Nachricht).
    Der Relay-Transkript-Schutz gilt für die KLAREN Felder; ct bleibt opak
    (E2E-Integrität transportiert der Ratchet selbst)."""
    ident = _relay_identity()
    if ident is None:
        return
    try:
        import base64 as _b64
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        frame["relay_id"] = ident["relay_id"]  # VOR dem Signieren (Bindung!)
        bare = {k: v for k, v in frame.items() if k != "sig"}
        sk = Ed25519PrivateKey.from_private_bytes(ident["priv"])
        sig = sk.sign(_canonical_meta_bytes(bare))
        frame["sig"] = _b64.b64encode(sig).decode("ascii")
    except Exception:
        # Fail-soft: Signatur-Fehlschlag ohne relay_id-Hinterlassung —
        # sonst trüge der Frame eine ID, deren Bindung nicht belegbar ist.
        frame.pop("relay_id", None)
        logger.warning("PH-4-T2: Frame-Signatur fehlgeschlagen — Frame unverändert")


def _relay_ident_frame() -> Optional[dict]:
    """agent.ident-Nachricht (Server→Agent je Connect): {type, v, relay_id,
    verify_key, ts, sig}. verify_key = Ed25519-Pub (hex), relay_id =
    SHA-256(verify_key) — die Adapter-Seite prüft die Konsistenz + Signatur
    (TOFU-Pin). Keine Identität → None (Agent bleibt ohne Pin-Band)."""
    ident = _relay_identity()
    if ident is None:
        return None
    frame = {
        "type": "agent.ident", "v": 1,
        "relay_id": ident["relay_id"],
        "verify_key": ident["pub_hex"],
        "ts": int(time.time() * 1000),
    }
    _relay_sign_meta(frame)
    return frame


def _load_device_keys() -> dict:
    """Phone-Device-Keys lesen (json). Fehlt/kaputt: leeres dict (fail-closed,
    gleiche Logik wie _load_relay_config — Inhalte werden nie geloggt)."""
    try:
        with open(_device_keys_path(), "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def _load_device_keys_map() -> dict:
    """B2: v2-MAP der gerätegebundenen Key-Einträge — v2 {"v":2, "devices":
    {...}} wird direkt gelesen; Legacy v1 = {"device": entry} → Map unter
    dem Gerät-Schlüssel seines device_id (Default "phone"). Rollback-Lesefall-
    back: BEIDE Formen bleiben lesbar; das v2-File trägt außerdem "device" =
    Spiegel des letztenPublishes (alte Binary liest weiter)."""
    raw = _load_device_keys() or {}
    if not isinstance(raw, dict):
        return {}
    if raw.get("v") == 2 and isinstance(raw.get("devices"), dict):
        return dict(raw["devices"])
    single = raw.get("device")
    if isinstance(single, dict):
        device_id = str(single.get("device_id") or "phone").strip()[:64] or "phone"
        return {device_id: single}
    return {}


def _store_device_keys(entries: dict, last_entry: Optional[dict] = None) -> None:
    """B2: gerätegebundene Key-Registry als v2 speichern — Datei 0600 (Muster
    relay-config.json), Private Keys gibt es hier nie (nur Pubs).

    Format v2: {"v": 2, "ts": ms, "devices": {device_id: {device_id, ik_pub,
    spk_pub, epoch_of_enroll, enrolled_at}}, "device": <letzter Publish>}.
    Der "device"-Spiegel hält den V1-Lesefestpunkt: eine gerollte Back-ALT-
    Binary (v1-Verband) liest ihn weiter (Plan Kap. 5, Invarianz (4)) — und
    Bestands-Asserts bleiben ehrlich. Der ERSTE v2-Write über einem v1-Bestand
    sichert die Alt-Bytes byte-genau als relay-keys.json.v1.bak nebenan.

    SEC-W1 D3a: Schreiben in Temp-Datei + atomares os.replace — das alte
    O_TRUNC auf der Bestandsdatei LEERTE die Registry physisch, wenn fchmod
    danach einen OSError warf. fchmod unter Windows = Best-Effort.
    """
    path = _device_keys_path()
    devices = {
        str(key): dict(value) for key, value in (entries or {}).items()
        if isinstance(key, str) and _DEVICE_ID_RE.fullmatch(key)
        and isinstance(value, dict)
    }
    if last_entry is not None and isinstance(last_entry, dict):
        mirror = dict(last_entry)
    elif devices:
        mirror = dict(next(iter(devices.values())))
    else:
        mirror = {}
    payload = {
        "v": 2,
        "ts": int(time.time() * 1000),
        "devices": devices,
        "device": mirror,
    }
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    # v1-Backup (einmalig): die Alt-Bytes (v1 {"v":1,"device":…}) bleiben
    # byte-genau nebenan — Rollback + Migration-Nachvollziehbarkeit.
    try:
        with open(path, "rb") as fh:
            existing = fh.read()
    except FileNotFoundError:
        existing = b""
    except OSError:
        existing = None
    if existing:
        parse_ok, parse_v2 = False, False
        try:
            parsed = json.loads(existing)
            parse_ok = True
            parse_v2 = parsed.get("v") == 2 if isinstance(parsed, dict) else False
        except ValueError:
            parse_ok = False
        if parse_ok and not parse_v2:
            backup = Path(path + ".v1.bak")
            if not backup.exists():
                try:
                    backup.write_bytes(existing)
                    logger.warning(
                        "KEY-REGISTRY v1→v2 MIGRATION: Alt-Bytes nach %s gesichert "
                        "(v1-Eintrag wandert beim nächsten /key_publish unter "
                        "seinen device_id-Schlüssel; LOUD, kein stiller Doppeldatensatz)",
                        backup)
                except OSError as exc:
                    logger.warning("KEY-REGISTRY: v1-Backup fehlgeschlagen: %s", exc)
    fd, tmp_path = tempfile.mkstemp(prefix=".relay-keys-", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            try:
                os.fchmod(fh.fileno(), 0o600)  # POSIX; Windows: Best-Effort
            except AttributeError:
                pass
            fh.write(json.dumps(payload, indent=2, ensure_ascii=False) + "\n")
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
    # W16.3 (P41): Code-Wechsel öffnet das Enrollment-Fenster neu
    # (CODE-EINMALIGKEIT: je Epoch ein Neugerät; Reset hier).
    try:
        try:
            from tools.mtls_enroll import reset_epoch as _mtls_reset_epoch
        except ImportError:
            from mtls_enroll import reset_epoch as _mtls_reset_epoch
        import threading as _threading
        _threading.Thread(target=_mtls_reset_epoch, daemon=True).start()
    except Exception as exc:
        logger.warning("mtls_enroll epoch-reset fehlgeschlagen (fail-soft): %s", exc)
    # Kick der Phone-WS im Relay-Loop (Thread-sicher; Aufrufer ist Sync-Kontext)
    loop = state.loop
    if loop is None or state.thread is None or not state.thread.is_alive():
        return

    def _kick() -> None:
        asyncio.ensure_future(_cleanup_phone(state, reason="pairing code rotated"))
        # B1: Tor-Fenster gehören zur Code-Epoche — Anfragen/Freigaben der
        # alten Epoche räumen (ein Stempel der Alt-Epoche öffnet unter dem
        # NEUEN Code nichts mehr, fail-closed).
        state.pairing_requests.clear()
        state.pairing_approvals.clear()
        logger.info("B1: Anmelde-Tor-Fenster nach Code-Rotation geräumt")

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
    state.devices_lock = asyncio.Lock()
    state.phone_ws_lock = state.devices_lock  # Legacy-Alias (gleiches Lock)
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


# ── W16.3 Stufe 2, Layer 2 (16.11.26): CN-vs-Registry-Gate (W17-RÜCKHOLUNG auf
# Sir-Weisung 17.11.: Zertifikat bleibt zusätzlich drin, PW = dritter Faktor) ──
_MTLS_GATE_PATHS = frozenset(
    {"/ws", "/chat", "/chat_event", "/media", "/key_publish", "/key_get", "/crypto_hello"})

def _mtls_gate(request: "web.Request") -> Optional["web.Response"]:
    """Layer 2 hinter dem NPM-Zert-Zwang (Layer 1 = nginx 421 bei fehlendem Zert):
    NPM reicht X-SSL-Verify/X-SSL-Device-DN durch (server_proxy.conf, Regex-Location);
    hier: Verify=SUCCESS UND CN in der Device-Registry nötig. Fail-closed bei
    Registry-Fehlern. Exklusiv Prod (ANDROID_MTLS_ENFORCE=1) — Drills/direkte
    Agenten-Verbindungen (/agent_ws Direct) laufen ohne Gate."""
    if os.getenv("ANDROID_MTLS_ENFORCE", "0") != "1":
        return None
    if request.path not in _MTLS_GATE_PATHS:
        return None
    # Security-Fix-Welle 17.11.: X-SSL-Verify/X-SSL-Device-DN sind PROXY-Header
    # (nginx setzt beide server-seitig nach der Client-Zert-Prüfung) — ohne
    # Peer-Check wäre das Gate von JEDEM Direkt-Client per Header-Fälschung
    # überwindbar (verify=SUCCESS + bekannter CN aus der Registry). Peer-Check
    # analog XFF in _effective_remote_ip: nur der Socket-Peer == Trusted Proxy
    # (env ANDROID_RELAY_TRUSTED_PROXY_IP, Prod: NPM) darf die Header setzen;
    # jeder andere Peer wird wie verify=NONE behandelt → 403-Regel-Lauf.
    # Bewusst request.remote (Socket-Peer) statt _effective_remote_ip: für
    # Proxy-Verkehr löst _effective_remote_ip zur XFF-CLIENT-IP auf — ein
    # Vergleich gegen die Proxy-IP fiele legitimen Proxy-Verkehr.
    trusted = _trusted_proxy_ip()
    peer = request.remote or "unknown"
    headers_trusted = bool(trusted) and peer == trusted
    dn = request.headers.get("X-SSL-Device-DN") or ""
    m = re.search(r"CN=flipsibridge-([0-9a-fA-F-]+)", dn)
    verify = request.headers.get("X-SSL-Verify", "") if headers_trusted else ""
    if verify == "SUCCESS" and m:
        try:
            try:
                from tools.mtls_enroll import _registry_load as _rg, \
                        _registry_default as _rdef
            except Exception:
                from mtls_enroll import _registry_load as _rg, \
                        _registry_default as _rdef
            reg = _rg() or {}
            # B2: Registry v2 trägt die Geräte unter "devices" (Legacy v1 =
            # "enrolled") — Lesefallback hält beide Formen lebendig.
            enrolled = reg.get("enrolled") or reg.get("devices") or {}
            try:
                reg_epoch = int(reg.get("epoch", 1))
            except (TypeError, ValueError):
                reg_epoch = int((_rdef() or {}).get("epoch", 1))
            lowered = {str(k).lower(): v for k, v in enrolled.items()}
            info = lowered.get(m.group(1).lower())
            if info is not None:
                # P43 (17.11.): Epoch-Check — der Eintrag trägt die Epoche
                # seiner Zert-Ausstellung (epoch_of_enroll). Nach einem
                # Code-Wechsel (Epoch-Rotation via set_pairing_code) zählt ein
                # ALT-Epochen-Eintrag NICHT mehr als bekannt (fail-closed wie
                # ein Unbekannter) — ABER: Eintrag und Zertifikat bleiben
                # UNBERÜHRT in der Registry (kein stiller Wipe); ein Renewal
                # mit dem neuen Code stellt die Geltung ohne Datenverlust her.
                # Legacy-Einträge OHNE epoch_of_enroll (Bestand vor P43) zählen
                # als aktuelle Epoche (fail-open, kein Brutalisieren).
                en_epoch = (info.get("epoch_of_enroll", reg_epoch)
                            if isinstance(info, dict) else reg_epoch)
                try:
                    en_epoch = int(en_epoch)
                except (TypeError, ValueError):
                    en_epoch = reg_epoch  # defekte Angabe → wie Legacy behandeln
                if en_epoch == reg_epoch:
                    return None
                logger.warning(
                    "mtls-gate: Ablehnung %s (CN=%r bekannt, aber Alt-Code-Epoche: "
                    "Zert-Epoche %s ≠ Registry-Epoche %s — Eintrag/Zert bleiben "
                    "erhalten, Renewal mit dem neuen Code stellt die Geltung her)",
                    request.path, m.group(1), en_epoch, reg_epoch)
                return web.json_response({"error": "mtls-stale-epoch"}, status=403)
        except Exception as exc:
            logger.warning("mtls-gate: Registry-Fehler — fail-closed: %s", exc)
    else:
        logger.info("mtls-gate: Ablehnung %s (verify=%r, dn=%.60s)", request.path,
                    request.headers.get("X-SSL-Verify", "<kein Header>"), dn)
    return web.json_response({"error": "mtls-unknown-device"}, status=403)

@web.middleware
async def _mtls_gate_mw(request: "web.Request", handler):
    gated = _mtls_gate(request)
    if gated is not None:
        return gated
    return await handler(request)


async def _serve(state: _RelayState, ready: threading.Event) -> None:
    """Build the aiohttp app, start the site, and block until shutdown."""
    # v0.10.8-Deploy-Fix: aiohttp-Default client_max_size ist 1MB - grosse
    # POST-Bodies (files_push mit APK als base64) wurden still gekappt, die App
    # sah ein leeres JSON und antwortete 'Pflichtfelder fehlen'. 96MB deckt das
    # app-seitige 48MB-Dateilimit (x1.37 Base64) plus JSON-Overhead ab.
    app = web.Application(client_max_size=96 * 1024 * 1024,
                          middlewares=[_mtls_gate_mw])
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

    # ── W17-F2: Optionales Kopplungs-Passwort laden (0600-File, nur HASH) ────
    # Set/Clear laufen über die NUN registrierten /password_set//password_clear-
    # Routen (Bearer-Auth wie üblich); hier NUR der Start-Laden. Kein Klartext
    # im File, das Log nennt nur den Aktiv-Zustand — nie Hash, Salt oder Path.
    state.pairing_pw = _load_pairing_password_record()
    if state.pairing_pw is not None:
        logger.info("W17-F2: Kopplungs-Passwortschutz AKTIV (Hash-Record geladen)")

    # ── B1: Geräte-Registry laden / v1→v2-Migration (ohne Verlust) ───────────
    # Vor dem ersten Handshake: vorhandene v2-Datei laden; fehlt sie, wird der
    # v1-Bestand (mtls-'enrolled'-Einträge + der relay-keys-Eintrag) ADOPTIERT
    # (nur LESEND gegenüber den Quellen; .v1.bak-Backup nebenan, LOUD-Log).
    state.device_registry = _migrate_device_registry()
    state.device_cap = _device_cap(agent_config)
    logger.info("B1: Geräte-Cap = %d (relay-config max_devices / env / Default 4)",
                state.device_cap)

    # W17-F2 (Sir-Direktive): Optionales Extra-Passwort für die KOPLUNG —
    # Set/Entfernen über die bestehende authentifizierte Verbindung (Bearer
    # = Kopplungscode, IP-Rate-Chain wie alle auth-Routen), Status ohne
    # Klartext-Rückgabe. Bewusst RELAY-NATIV und NICHT in _ROUTES: die
    # Gerät-Routen-Forward-Kette (Allowlist + Device-Command-Handler) bleibt
    # unberührt, genauso die Agenten-Routen (/agent_ws, /chat — SEPARAT).
    async def password_set_handler(request: web.Request) -> web.StreamResponse:
        return await _handle_password_set(request, state)

    async def password_clear_handler(request: web.Request) -> web.StreamResponse:
        return await _handle_password_clear(request, state)

    async def password_status_handler(request: web.Request) -> web.StreamResponse:
        return await _handle_password_status(request, state)

    app.router.add_post("/password_set", password_set_handler)
    app.router.add_post("/password_clear", password_clear_handler)
    app.router.add_get("/password_status", password_status_handler)

    # Authenticated APK distribution for /apk/latest (FlipsiBridge self-update
    # downloads; W16/Punkt-26: the /apk_install device route is gone — the app's
    # installer capability was removed, updates run via Play/GitHub).
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
                (
                    f
                    for f in os.listdir(apk_dir)
                    if f.endswith(".apk")
                    # PH-7/J2-F4+J4-F3 (11.11.2026): Play-APKs gehören nie auf den
                    # Self-Update-Track (eigene App-ID, Updates via Play Store) —
                    # ansonsten installiert ein GitHub-Gerät eine Zweit-App.
                    and not f.endswith("-play.apk")
                ),
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
        # W16 (Fixliste-Punkt 26): HEAD = reiner Update-Check der App (ohne
        # APK-Body, ohne SHA-Hash). aiohttp serviert HEAD auf add_get; der
        # GET-Pfad (altes Self-Update / manuelle Downloads) bleibt unberührt.
        # X-APK-Version kommt aus dem neuesten APK-Dateinamen — ohne lesbarer
        # Version FEHLT der Header (fail-closed: die App bleibt still).
        if request.method == "HEAD":
            version_match = _APK_VERSION_PATTERN.search(candidates[0])
            head_headers = {"Cache-Control": "no-store"}
            if version_match:
                head_headers["X-APK-Version"] = version_match.group(0)
            return web.Response(status=200, headers=head_headers)
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

    # W16.3 (P41, Sir): Enrollment-Routen /mtls/enroll[/challenge] — fail-soft:
    # Init-Fehler (keine OpenSSL-CA-Materialien) = Routen OFF + Log, der Rest
    # des Relays läuft unverändert (P22-Stufe 1: Ausstellen + Registry +
    # Code-Einmaligkeit; Enforcement am Socket = Stufe 2 nginx-Verband).
    # W17-RÜCKHOLUNG (17.11., Sir: „Zertifikat bleibt zusätzlich drin“).
    try:
        try:  # Daemon lädt android_relay als tools.android_relay → Package-Weg
            from tools.mtls_enroll import add_routes as _mtls_add_routes
        except ImportError:  # Flat-Import (direkter Modul-Lauf)
            from mtls_enroll import add_routes as _mtls_add_routes
        if _mtls_add_routes(app):
            logger.info("mTLS-Enrollment-Routen aktiv (P41)")
        else:
            logger.info("mTLS-Enrollment OFF (Init fehlgeschlagen — fail-soft)")
    except Exception as exc:
        logger.warning("mTLS-Enrollment-Wiring fehlgeschlagen (fail-soft): %s", exc)
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

_AUTH_MAX_ATTEMPTS = _env_clamped_int(
    "ANDROID_RELAY_AUTH_MAX_ATTEMPTS", 5, 1, 5)  # max failed attempts before blocking (O4: Env nur verschärfen [1..5])
_AUTH_WINDOW_SECONDS = _env_clamped_int(
    "ANDROID_RELAY_AUTH_WINDOW_SECONDS", 60, 60, 3600)  # sliding window for counting failures (O4: nur länger = strenger)
_AUTH_BLOCK_SECONDS = _env_clamped_int(
    "ANDROID_RELAY_AUTH_BLOCK_SECONDS", 300, 300, 86400)  # how long to block an IP (O4: nur länger = strenger)
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
# Security-Fix-Welle 17.11. + PH-4-Log-Redaktion (P23.3): password/token/
# authorization/code ergänzt, dazu secret/apikey/bearer/credentials/otp/pin/
# psk/prekey/session_key — Tokens/Codes/Keymaterial landen NIE im Log. Das
# Matching ist case-insensitive und REKURSIV (siehe _redact_sensitive).
_SENSITIVE_BODY_KEYS = frozenset(
    {"to", "number", "body", "text", "message", "extras", "data", "uri", "content",
     "password", "token", "authorization", "code", "secret", "apikey", "api_key",
     "bearer", "credentials", "credential", "otp", "pin", "psk", "prekey",
     "session_key", "access_token", "auth", "authorization_header"}
)
# PH-4: Tiefen-Begrenzung des Redaktions-Baumlaufs (DoS-Schild gegen riesige/
# zirkuläre Strukturen); jenseits der Tiefe = Platzhalter ohne Inhalt.
_SENSITIVE_REDACT_DEPTH_MAX = 6


def _redact_sensitive(value, depth: int = 0):
    """PH-4 Log-Redaktion: Wertbaum rekursiv wandern und Werte unter sensiblen
    Schlüsseln durch "<redacted>" ersetzen. Bisher redigierte _safe_body_repr
    nur TOP-LEVEL-Keys — verschachtelte {"params": {"token": ...}}-Bodies
    flossen KLAR ins Debug-Log (Audit-Fund P23.3). Case-insensitiv; Listen/
    Tupeln werden elementweise gewandert; jenseits _SENSITIVE_REDACT_DEPTH_MAX
    nur ein Platzhalter; Nicht-Container unverändert."""
    if depth > _SENSITIVE_REDACT_DEPTH_MAX:
        return "<redacted-depth>"
    if isinstance(value, dict):
        out = {}
        for k, v in value.items():
            key = k if isinstance(k, str) else str(k)
            if key.lower() in _SENSITIVE_BODY_KEYS:
                out[k] = "<redacted>"
            else:
                out[k] = _redact_sensitive(v, depth + 1)
        return out
    if isinstance(value, (list, tuple)):
        return [_redact_sensitive(item, depth + 1) for item in value]
    return value


# PH-4 Code-Verdunkelung: Request-Body-Debug ist Default-OFF — selbst wenn der
# Logger auf DEBUG steht, werden Bodies NUR mit explizitem
# ANDROID_RELAY_DEBUG_BODY_LOGGING=1 ans Log gereicht (per Call gelesen →
# ohne Neustart umschaltbar). Ohne Env: body=<off> (kein Inhalt, auch nach
# _safe_body_repr-Redaktion).
_DEBUG_BODY_LOGGING_ENV = "ANDROID_RELAY_DEBUG_BODY_LOGGING"


def _debug_body_enabled() -> bool:
    """Debug-Body-Logging bewusst per Env opt-in (Default OFF)."""
    return os.getenv(_DEBUG_BODY_LOGGING_ENV, "") == "1"


def _log_command_debug(method: str, path: str, device: Optional[str], body) -> None:
    """Debug-Zeile je Phone-Command — PH-4 Log-Redaktion: ohne Debug-Env wird
    der Body NIE an den Logger gereicht; mit Env über _safe_body_repr
    (rekursiv redigiert). Method/Path/Device bleiben (ohne Query-Params)."""
    if not logger.isEnabledFor(logging.DEBUG):
        return
    if _debug_body_enabled():
        logger.debug(">>> %s %s device=%s body=%s", method, path,
                     _mask_id(device), _safe_body_repr(body))
    else:
        logger.debug(">>> %s %s device=%s body=<off>", method, path,
                     _mask_id(device))


def _safe_body_repr(body) -> str:
    """JSON repr of a request body with PII-bearing fields redacted.

    v0.10.9-Sicherheitsfix: body kann auch non-dict JSON sein (Array/String/Zahl)
    — vorher AttributeError -> 500. Jetzt defensiv typisieren.
    PH-4: Redaktion jetzt REKURSIV über den ganzen Key-Baum (sensible
    verschachtelte Felder landen nicht mehr klar im Debug-Log).
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
    redacted = _redact_sensitive(body)
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

    # ── W17-F2: Optionales Kopplungs-Passwort (Code UND Passwort) ────────────
    # Die Kopplungs-Route ist der WS-Handshake selbst: ist der Passwortschutz
    # aktiv, verlangt sie ZUSÄTZLICH zum Bearer-Code den Header
    # 'X-Pairing-Password'. Fehl-Eingaben (fehlerhaft oder falsch) laufen wie
    # falsche Codes in die bestehende IP-Rate-Chain (_auth_record_failure) —
    # das Rate-Limit-Schild verhindert einen Offline-Brute-Force-Gang über das
    # Handshake-Fenster. Fehl-Text = strukturiertes JSON (wie /key_get-Fehler):
    # die App zeigt daraufhin ihr Passwort-Prompt statt des Code-Fehlers.
    if state.pairing_pw is not None:
        supplied_password = request.headers.get("X-Pairing-Password") or ""
        if not supplied_password:
            # Fehl-Eingabe = Handshake-Versuch ohne Pflichtfeld: in dieselbe
            # IP-Rate-Chain wie falsche Codes/Passwörter (Fail-Schild).
            _auth_record_failure(remote_ip)
            logger.warning("Phone WS rejected — password gate active, no password from %s",
                           remote_ip)
            raise web.HTTPForbidden(
                text=json.dumps({
                    "error": "password-required",
                    "detail": "Pairing password required (X-Pairing-Password header)",
                }),
                content_type="application/json",
                headers={"X-Bridge-Error": "password-required"},
            )
        if not _pairing_password_verify(state, supplied_password):
            _auth_record_failure(remote_ip)
            logger.warning("Phone WS rejected — wrong pairing password from %s", remote_ip)
            raise web.HTTPForbidden(
                text=json.dumps({
                    "error": "password-invalid",
                    "detail": "Pairing password wrong",
                }),
                content_type="application/json",
                headers={"X-Bridge-Error": "password-invalid"},
            )

    # ── B1: Geräte-Registry-Tor (W17.1-Sperre → Anmelde-Tor) ─────────────────
    # Der Kopplungs-Handshake trägt die stabile Gerätekennung (X-Device-Id;
    # NONE = Legacy-Client → Legacy-Default "phone"). Bekannte Geräte
    # (device_id in der Registry) koppeln DIREKT — ein Reconnect ersetzt
    # ausschließlich SEINEN eigenen Slot. Unbekannte Geräte passieren das
    # ANMELDE-TOR: gültige Freigabe (pairing.approve, TTL 10 min, einmalig)
    # ODER verifiziertes Kopplungs-Passwort (W17.2, Option b) → Enroll;
    # sonst 403/4403 "code-in-use" (bestehender Fehlton) + IP-Rate-Chain zählt
    # weiter + EINE pairing.request-Anfrage an die Admin-Slots (dedupe im
    # TTL-Fenster). Cap-voll bei NEUER Anmeldung → 403/4403 "device-cap".
    device_id = _parse_device_id(request)
    devices_lock = state.devices_lock or state.phone_ws_lock
    if devices_lock is None:
        # Locks entstehen erst in _run_loop — vor dem Server-Start kann es
        # keinen Handshake geben (Robustheit-Guard wie beim broker_lock).
        raise web.HTTPServiceUnavailable(
            text=json.dumps({"error": "server warming up"}),
            content_type="application/json",
        )
    enroll_requested = False
    rejected_request = None
    async with devices_lock:
        decision, gate_device_id = _device_gate_decide(state, device_id, remote_ip)
        if decision == "rejected":
            # Kein Tor-Bestehen: Anfrage anlegen (dedupe); Frame-EMIT + Rate-
            # Chain-Count laufen NICHT unter dem Lock (await-Punkte unten) und
            # NUR bei frisch angelegter Anfrage (kein Spam im TTL-Fenster).
            rejected_request, request_fresh = _pairing_request_open(
                state, gate_device_id, remote_ip)
            if not request_fresh:
                rejected_request = None
            _slot_lock_reject_log(state, device_id, remote_ip, phase="pre-check")
        elif decision == "device-cap":
            logger.warning(
                "Phone WS rejected — device cap reached (%s, ip=%s, phase=pre-check)",
                _mask_id(gate_device_id), remote_ip)
        elif decision == "enroll":
            # Tor bestanden: das Enrollment passiert atomar in der Race-Sektion
            # unten (Consumption der Freigabe + Registry-Write + Slot-Einheit).
            enroll_requested = True
    if decision == "rejected":
        if rejected_request is not None:
            await _pairing_request_emit(state, gate_device_id, rejected_request)
        _auth_record_failure(remote_ip)
        raise web.HTTPForbidden(
            text=json.dumps({
                "error": "code-in-use",
                "detail": "This pairing code is already connected to another device",
            }),
            content_type="application/json",
            headers={"X-Bridge-Error": "code-in-use"},
        )
    if decision == "device-cap":
        raise web.HTTPForbidden(
            text=json.dumps({
                "error": "device-cap",
                "detail": "Device limit reached for this pairing code",
            }),
            content_type="application/json",
            headers={"X-Bridge-Error": "device-cap"},
        )
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

    # Registrierung in der Geräte-Registry (ein Slot je Gerät)
    async with devices_lock:
        # FIN-T1 RED-02: Rotation-TOCTOU — eine Rotation, die ZWISCHEN dem
        # Token-Check oben und dieser Registrierung landet (Handshake noch in
        # der Luft, await ws.prepare = await-Punkt), wurde vom W2-1-Kick
        # verfehlt und die Alt-Code-WS registrierte sich DANACH. Verifizierung
        # von Epoche UND Code in derselben kritischen Sektion wie die
        # Registrierung: Alt-Code-WS wird geschlossen und kann NIE die aktive
        # WS werden (fail-closed, W2-1-Vertrag).
        if (state.pairing_epoch != pairing_epoch
                or not _constant_time_eq(token, state.pairing_code)):
            logger.warning(
                "Phone WS rejected — pairing code rotated during the "
                "handshake (TOCTOU window closed)")
            await ws.close(code=aiohttp.WSCloseCode.POLICY_VIOLATION,
                           message=b"pairing code rotated")
            return ws
        # B1-Race-Sektion: der Pre-Check oben lief OHNE Hold über den
        # prepare-Await. Post-Prepare läuft dieselbe Abweisung als WS-Close-
        # Code (4403-Familie), die App behandelt ihn wie den 403-JSON.
        decision, gate_device_id = _device_gate_decide(state, device_id, remote_ip)
        if decision == "rejected":
            _slot_lock_reject_log(state, device_id, remote_ip, phase="race-check")
            await ws.close(code=_WS_CLOSE_CODE_IN_USE, message=b"code-in-use")
            return ws
        if decision == "device-cap":
            logger.warning(
                "Phone WS rejected — device cap reached (%s, ip=%s, phase=race-check)",
                _mask_id(gate_device_id), remote_ip)
            await ws.close(code=_WS_CLOSE_CODE_IN_USE, message=b"device-cap")
            return ws
        if enroll_requested and decision == "enroll":
            # Enrollment ATOMAR mit der Slot-Registrierung: Freigabe verbrauchen
            # (einmalig), Registry-Eintrag schreiben (epoch_of_enroll = JETZIGE
            # Code-Epoche je Gerät), Datei persistieren — ein zweiter Handshake
            # desselben Geräts findet es danach als BEKANNT (accepted).
            state.pairing_approvals.pop(gate_device_id, None)
            state.pairing_requests.pop(gate_device_id, None)
            state.device_registry.setdefault(gate_device_id, {})
            state.device_registry[gate_device_id].update({
                "admin": bool(state.device_registry[gate_device_id].get("admin"))
                         or len(state.device_registry) == 1,
                "epoch_of_enroll": int(state.pairing_epoch),
                "enrolled_at": int(time.time() * 1000),
            })
            try:
                _store_device_registry(state.device_registry)
            except OSError as exc:
                logger.error(
                    "DEVICE-REGISTRY: Enroll-Persist fehlgeschlagen (%s) — "
                    "Gerät %s bleibt Slot-fähig, aber UNBEKANNT beim Neustart",
                    exc, _mask_id(gate_device_id))
            logger.warning(
                "ANMELDE-TOR: Gerät %s ENROLLT (epoch_of_enroll=%s, admin=%s, "
                "cap=%d/%d) — Registry persistiert",
                _mask_id(gate_device_id), state.pairing_epoch,
                bool(state.device_registry[gate_device_id].get("admin")),
                len(state.device_registry), _device_cap())
        device_key = gate_device_id
        entry = state.device_registry.get(device_key) or {}
        previous = state.devices.get(device_key)
        if previous is not None and previous.ws is not None \
                and not previous.ws.closed:
            # Reconnect DESSELBEN Geräts: das bisherige Replace-Verhalten
            # bleibt — genau er erlaubt den zuverlässigen Doze/WLAN-Reconnect
            # ohne Neukopplung. Andere Geräte-Slots bleiben UNBERÜHRT
            # (Registry-Gesetz statt Fremd-Kick; W17-Vertrag 1:1 je Slot).
            logger.info("Replacing previous connection of device %s",
                        _mask_id(device_key))
            await _fail_inflight(state, reason="device connection replaced")
            try:
                await previous.ws.close(
                    code=aiohttp.WSCloseCode.GOING_AWAY, message=b"replaced")
            except Exception as exc:
                logger.debug("Alt-Slot-Close fehlgeschlagen (%s) — Slot ersetzt", exc)
        state.devices[device_key] = _PhoneSlot(
            ws, device_id=device_key, alias=str(entry.get("alias") or ""),
            admin=bool(entry.get("admin")))
        state.last_active_device = device_key
        entry["last_seen"] = int(time.time() * 1000)
        # Erst-Adoption (Migration ohne Verlust): das erste verbindende Gerät
        # übernimmt den Fallback-Spool-Bucket (kein Spool-Verlust beim Upgrade).
        if device_key not in state.device_outbox and state.fallback_outbox:
            state.device_outbox[device_key] = state.fallback_outbox
            state.fallback_outbox = _ByteBoundedDeque(maxlen=_CHAT_QUEUE_MAXLEN)
            logger.info("SPOOL-MIGRATION: Fallback-Outbox → Gerät %s übernommen",
                        _mask_id(device_key))
        elif device_key not in state.device_outbox:
            state.device_outbox[device_key] = _ByteBoundedDeque(
                maxlen=_CHAT_QUEUE_MAXLEN)

    logger.info("Phone connected from %s", request.remote)

    # PH2-MERGE: relay.state-Hallo-Frame VOR der Spool-Auslieferung — die App
    # bekommt die Agenten-Liste damit immer als allerersten Frame.
    await _send_phone_state(state, ws)

    # PH2-T1: Agent→App-Nachrichten aus der je-Gerät-Spool-Outbox ausliefern.
    await _flush_phone_outbox(state, device_key)

    try:
        async for msg in ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                await _on_phone_message(state, msg.data, device_key)
            elif msg.type == aiohttp.WSMsgType.BINARY:
                await _on_phone_binary(state, msg.data)
            elif msg.type == aiohttp.WSMsgType.ERROR:
                logger.error("Phone WS error: %s", ws.exception())
                break
    finally:
        await _cleanup_phone(state, reason="phone disconnected", expected_ws=ws)

    return ws


async def _send_phone_state(state: _RelayState, ws: web.WebSocketResponse) -> None:
    """PH2-MERGE: relay.state-Hallo-Frame an eine Geräte-WS (Agenten-Liste).

    agents = [{agent_id, display_name, online}] aus der Registry — fehlen die
    Felder (leere Server-Config), bleibt die Liste leer: die App-UI fällt auf
    ihren Demo-Fallback zurück, kein Crash. Sendefehler = Log, die Verbindung
    bleibt bestehen (der Spool liefert den Rest).

    PH-3 F7 (Jäger): session_active je Agent = Wahrheit des Session-Brokers
    (bestätigte, nicht abgelaufene E2E-Session vorhanden?). Die App gleicht
    beim Re-Connect ihre lokalen Sessions gegen dieses Feld ab — ein vom
    Agenten gesendetes crypto.session_reset, das die Phone-WS nicht erreicht
    hat (Reconnect), wird so nachgeholt statt still weiterlaufen zu lassen.

    B1: devices[] je Registry-Gerät (Kennung maskiert, alias, online, admin) —
    die App-Geräteverwaltung liest sie; Kennungen im Frame bleiben maskiert
    (PII-Regel, _mask_id-Parität)."""
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
    async with (state.devices_lock or _NoAsyncLock()):
        # B4 (Kap. 4.2): devices[] liest Registry ∪ Slots — OFFLINE Geräte
        # bleiben sichtbar (online=False + last_seen), nicht nur Live-Slots.
        # Kennungen bleiben maskiert (PII-Regel, _mask_id); last_seen = Unix-ms
        # der letzten Registrierungs-/Slot-Pflege (0 = unbekannt, ehrlich).
        live = {
            device_id: slot
            for device_id, slot in state.devices.items()
            if slot.ws is not None and not slot.ws.closed
            and isinstance(device_id, str) and _DEVICE_ID_RE.fullmatch(device_id)
        }
        ordered_ids = list(state.device_registry.keys())
        for device_id in live:
            if device_id not in state.device_registry:
                ordered_ids.append(device_id)
        devices = []
        for device_id in ordered_ids:
            slot = live.get(device_id)
            entry = state.device_registry.get(device_id) or {}
            last_seen = int(entry.get("last_seen") or 0)
            if not last_seen and slot is not None:
                last_seen = int(slot.last_seen * 1000)
            devices.append({
                "device_id": _mask_id(device_id),
                "alias": str(entry.get("alias") or ""),
                "online": slot is not None,
                "admin": bool(entry.get("admin")),
                "last_seen": max(0, last_seen),
                # B4 Revoke-Handle: Antwortträger des device_revoke-Frames
                # (App kennt NUR die Maske — Parität pairing.approve-Norm).
                "request_id": _device_revoke_id(state, device_id),
            })
    try:
        await ws.send_json({"type": "relay.state", "v": 1, "agents": agents,
                            "devices": devices})
    except Exception as exc:
        logger.warning("Phone: relay.state fehlgeschlagen (%s)", exc)


async def _broadcast_agent_presence(state: _RelayState) -> None:
    """PH2-FIX (E2E P2): relay.state je Agent-Presence-Wechsel an ALLE Geräte-
    Slots (B1-Fan-out; bei EINEM Gerät exakt das alte Verhalten).

    Agent-Connect (online=True) und Disconnect (offline) ändern die Registry —
    ohne Push erfuhr die App einen Wechsel nur beim nächsten Phone-Connect
    (Hallo-Frame), der Header erstarrete auf 'Offline', während der Agent
    online antwortete (E2E-Fund e2e-c). Der Frame nutzt exakt die Payload-Form
    des Hallo-Frames (_send_phone_state) — die App parst relay.state bereits,
    kein App-Change nötig. Kein offener Geräte-Slot = No-Op (bewusst KEIN
    Spool: der nächste Connect bekommt den frischen Zustand als Hallo-Frame).
    Sendefehler je WS schluckt _send_phone_state (Log), der Presence-Wechsel
    bleibt wirksam."""
    devices_lock = state.devices_lock
    if devices_lock is None:
        # Locks entstehen erst in _run_loop — vor dem Server-Start gibt es
        # keine Geräte-WS, der Push ist dann ohnehin ein No-Op.
        return
    async with devices_lock:
        targets = [slot.ws for slot in state.devices.values()
                   if slot.ws is not None and not slot.ws.closed]
    for ws in targets:
        await _send_phone_state(state, ws)


async def _on_phone_message(state: _RelayState, raw: str,
                            device_id: Optional[str] = None) -> None:
    """Route an incoming message from the phone to the matching pending future.

    B1: pairing.approve/pairing.deny (Anmelde-Tor-Freigabe des Admin-Geräts)
    werden VOR der request_id-Route behandelt (Frames ohne request_id sind
    hier legitim); die WS-Identität (device_id) gewinnt — approves von nicht-
    vorhandenen/toten Slots werden verworfen (fail-closed im Frame-Handler).
    """
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

    # ── B1 Anmelde-Tor: Admin-Freigabe (approve) / Abweisung (deny) ─────────
    msg_type = data.get("type")
    if msg_type in ("pairing.approve", "pairing.deny") and device_id:
        await _guard_phone_frame(
            _handle_pairing_admin_frame, state, device_id, data,
            "_handle_pairing_admin_frame")
        return

    # ── B4 Backfill-Request (Phone→Agent, Klar-Steuer-Frame ids-only) ────────
    # FRÜHER stille verworfen („missing request_id“): der App-Vertrag
    # (ChatBackfillRules.requestFrame) trägt KEIN request_id — der Frame geht
    # je Connect eines frischen Geräts und braucht den Router zum Agenten
    # (chat_owner = Konversations-Wahrheit). Rate: EIGENER Bucket (nie im
    # heißen chat-Bucket — FLIPSI-Frame-Guard-Verband).
    if msg_type == "chat.backfill_request" and device_id:
        await _guard_phone_frame(
            _handle_phone_backfill_request, state, device_id, data,
            "_handle_phone_backfill_request")
        return

    # ── B4 Revoke (Gerät entfernen / eigenes Abmelden) ───────────────────────
    if msg_type == "device_revoke" and device_id:
        await _guard_phone_frame(
            _handle_phone_device_revoke, state, device_id, data,
            "_handle_phone_device_revoke")
        return

    # ── W21 (Anruf P1, roadmap-anruf.md §3.4): Phone→Agent Call-Signaling ─────
    # E2E-enc je Geräte-Session (ringing/accepted/declined/missed/ended.enc).
    # Router wie pairing/backfill/revoke — Frames ohne request_id sind hier
    # legitim. Der Handler prüft fail-closed (bestätigte Session + Geräte-
    # Bindung) und fächert über den Session-Broker auf den ZUGEORDNETEN Agenten
    # (nie ein Frame-Top-Level agent_id).
    if msg_type in _PHONE_CALL_ENC_FRAMES and device_id:
        await _guard_phone_frame(
            _handle_phone_call_signal, state, device_id, data,
            "_handle_phone_call_signal")
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


async def _guard_phone_frame(handler, state: _RelayState, device_id: str,
                             data: dict, name: str) -> None:
    """B1-Jäger-Parität (_guard_agent_frame): ein Exception eines Frame-
    Handlers reißt die Geräte-WS nicht mit — Fail-soft, Frame verworfen,
    Log mit Traceback. CancelledError läuft weiter durch."""
    try:
        await handler(state, device_id, data)
    except asyncio.CancelledError:
        raise
    except Exception:
        logger.exception(
            "Gerät %s: Frame-Handler %s fehlgeschlagen — Frame verworfen (WS bleibt)",
            _mask_id(device_id), name)


def _device_bucket(state: _RelayState, device_id: str) -> deque:
    """Je-Gerät-Outbox-Bucket (erzeugt on demand, ByteBounded wie der alte
    Einheits-Spool)."""
    bucket = state.device_outbox.get(device_id)
    if bucket is None:
        bucket = _ByteBoundedDeque(maxlen=_CHAT_QUEUE_MAXLEN)
        state.device_outbox[device_id] = bucket
    return bucket


async def _cleanup_phone(
    state: _RelayState,
    reason: str = "",
    expected_ws: Optional[web.WebSocketResponse] = None,
) -> None:
    """B1: Slot-Freigabe JE Gerät — Stirbt eine Verbindung, fällt NUR ihr
    EIGENER Slot; Registry-Eintrag + Outbox-Bucket bleiben (kein Dauer-Lock,
    keine Spool-Verweise). expected_ws-Guard: eine ALT-WS (Replace-Fall) kann
    den neuen Slot nicht anfassen (Parität zur alten Slot-Freigabe)."""
    devices_lock = state.devices_lock or state.phone_ws_lock
    if devices_lock is None:
        return
    async with devices_lock:
        targets: list[web.WebSocketResponse] = []
        if expected_ws is not None:
            for device_id, slot in state.devices.items():
                if slot.ws is expected_ws:
                    targets.append(expected_ws)
                    del state.devices[device_id]
                    log_target = _mask_id(device_id)
                    # B4 last_seen-Pflege: der Registry-Eintrag stirbt mit dem
                    # Slot NICHT — letzte Sichtung stempeln (offline-Geräte
                    # bleiben damit ehrlich in relay.state devices[] sichtbar)
                    # und fail-soft persistieren (Registry-Pflege, kein
                    # Spool-/Slot-Verband berührt).
                    entry = state.device_registry.get(device_id)
                    if isinstance(entry, dict):
                        entry["last_seen"] = int(time.time() * 1000)
                        try:
                            _store_device_registry(state.device_registry)
                        except OSError as exc:
                            logger.warning(
                                "DEVICE-REGISTRY: last_seen-Persist fehlgeschlagen (%s)", exc)
                    break
            if not targets:
                # Replaced/Rotiert-WS: der frische Inhaber ist längst drin —
                # die ALT-WS darf die Registrierung des NEUEN nicht anfassen.
                return
        else:
            # Rotation/Shutdown (kein expected_ws): ALLE lebenden Slots räumen
            # UND schließen (Parität zum Alt-Verhalten: ws.close() weckt den
            # Phone-Read-Loop — sonst hängt eine Alt-Verbindung als Geist).
            for device_id, slot in state.devices.items():
                targets.append(slot.ws)
                log_target = None
            state.devices.clear()
            log_target = "alle Slots"

    for ws in targets:
        if ws is not None and not getattr(ws, "closed", True):
            try:
                await ws.close()
            except Exception:
                pass

    await _fail_inflight(state, reason)
    if log_target is not None:
        logger.debug("Geräte-Slot freigegeben (%s) — Grund: %s", log_target, reason)


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


# ── W17-F2: Kopplungs-Passwort-Routen (authentifiziert, kein Klartext) ────────


def _password_route_auth(request: web.Request, state: "_RelayState") -> Optional[web.Response]:
    """Auth-Verband der /password_*-Routen — exakt das Muster der Key-Registry:
    IP-Block-Precheck (429) → Bearer = Kopplungscode (konstante Zeit) mit
    Fehlversuch-Zählung in der bestehenden IP-Rate-Chain → 401-JSON.
    None = authentifiziert (weiter zum Handler); sonst die Fehler-Response."""
    remote_ip = _effective_remote_ip(request)
    if _auth_is_blocked(remote_ip):
        return web.json_response(
            {"error": "Too many failed authentication attempts. Try again later."},
            status=429,
        )
    if not _constant_time_eq(_bearer_token(request), state.pairing_code):
        _auth_record_failure(remote_ip)
        logger.warning("HTTP /password auth rejected — bad auth from %s", remote_ip)
        return web.json_response({"error": "Unauthorized"}, status=401)
    return None


async def _handle_password_set(request: web.Request, state: "_RelayState") -> web.Response:
    """W17-F2: Passwortschutz setzen — der Nutzer ruft dies über die bestehende
    authentifizierte Verbindung auf (Bearer-Auth wie üblich). Der Server hält
    NUR den PBKDF2-HMAC-SHA256-Hash + Salt (0600-File); Klartext existiert
    ausschließlich als Request-Body-Einheit und wird NIE geloggt oder
    gespeichert (Antworten ohne Klartext-Rückgabe)."""
    auth_error = _password_route_auth(request, state)
    if auth_error is not None:
        return auth_error
    remote_ip = _effective_remote_ip(request)
    try:
        body = await request.json()
    except Exception:
        body = None
    if not isinstance(body, dict):
        return web.json_response({"error": "JSON body required"}, status=400)
    password = body.get("password")
    if (not isinstance(password, str)
            or not (_PAIRING_PW_MIN_LEN <= len(password) <= _PAIRING_PW_MAX_LEN)):
        return web.json_response(
            {"error": f"password must be {_PAIRING_PW_MIN_LEN}-{_PAIRING_PW_MAX_LEN} chars"},
            status=400)
    record = _pairing_password_record_for(password)
    try:
        _store_pairing_password_record(record)
    except OSError:
        logger.error("W17-F2: /password_set — Hash-File-Schreibfehler (fail-closed)")
        return web.json_response({"error": "password file unavailable"}, status=500)
    # Ref-Swap (atomar): laufende WS-Handshakes sehen entweder None (alter
    # Zustand) oder den vollständigen Record — nie einen halben Zustand.
    state.pairing_pw = record
    logger.info("W17-F2: Kopplungs-Passwortschutz AKTIV gesetzt (Hash gespeichert; ip=%s)",
                remote_ip)
    return web.json_response({"ok": True, "active": True})


async def _handle_password_clear(request: web.Request, state: "_RelayState") -> web.Response:
    """W17-F2: Passwortschutz entfernen — 0600-File gelöscht, RAM-Record None.
    Antwort ohne Klartext-Rückgabe; nach dem Clear akzeptiert die Kopplungs-
    Route wieder Code ohne Passwort (Statuszähler liefert die App-Wahrheit)."""
    auth_error = _password_route_auth(request, state)
    if auth_error is not None:
        return auth_error
    remote_ip = _effective_remote_ip(request)
    _clear_pairing_password_record(state)
    logger.info("W17-F2: Kopplungs-Passwortschutz INAKTIV entfernt (ip=%s)", remote_ip)
    return web.json_response({"ok": True, "active": False})


async def _handle_password_status(request: web.Request, state: "_RelayState") -> web.Response:
    """W17-F2: Aktiv-Status für die App (Schalteranzeige + ob das Passwortfeld
    in der Kopplung erscheint). Nur wahr/falsch — NIE Hash, Salt oder Klartext."""
    auth_error = _password_route_auth(request, state)
    if auth_error is not None:
        return auth_error
    return web.json_response({"active": state.pairing_pw is not None})


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
_CHAT_QUEUE_MAXLEN = _env_clamped_int(
    "ANDROID_RELAY_CHAT_SPOOL_MAXLEN", 500, 10, 500)  # (O4 Spool-Quota: Env nur verschärfen)

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
     # W16 (Fixliste 35): Modell-Katalog je Agent (klar + enc) — Fail-soft.
     "agent.models", "agent.models.enc",
     # W19 (SIRS-Neued): agent.status — Live-Arbeitszustand je Agent (klar +
     # enc): working/started_at/label/sub/end; Status = Transition (kein
     # Rate-Bucket, kein Spool), Sub-Slots je Frame durchgereicht.
     "agent.status", "agent.status.enc",
     # F1/J4-Fund 2: cron.status-Live-Verband (Gateway-Cron-Jobs, Karte
     # „Geplante Aufgaben") — klar + enc, EIGENER Rate-Bucket 'cronstatus:*'
     # (nie geteilt mit chat.reply), enc = opaker Pass-Through.
     "cron.status", "cron.status.enc",
     "chat.reply", "chat.read", "chat.activity",
     # PH-3-T2: E2E-Frames — der Relay ist opaker Pass-Through (kein Key-
     # Material, ct wird nie geöffnet); Validierung = Struktur + Session.
     "crypto.hello_ack", "crypto.session_reset",
     "chat.reply.enc", "chat.read.enc", "chat.activity.enc",
     # B4 (Multi-Gerät): Sync-Echo + Backfill-Verlauf (je Geräte-Session enc,
     # der Relay fächert session_id→device und bleibt opak) + Status-Snapshot
     # (KLAR — ids-only, kein Chat-Inhalt; Form-Contract Parität App-Parser
     # ChatBackfillRules.parseStatusSnapshot: Array- UND Map-Form toleriert).
     "chat.sync.enc", "chat.backfill.enc", "chat.status_snapshot",
     # W16 (Fixliste 14): Session-Meta eigenständig (klar + enc) — Sub-Agent
     # / Plattform-Update MITTEN im Turn; Relay = opaker Pass-Through.
     "chat.session_meta", "chat.session_meta.enc",
     # MEDIA-T1: chunked Media (Agent→Phone, opak) + Chunk-Ack (klar, light).
     "chat.media.enc", "chat.media.chunk_ack",
     # W21 (Anruf P1, roadmap-anruf.md §3.4): Call-Signaling (E2E-only, eigener
     # Rate-Bucket 'callsign:*', Owner-Claim; Inhalte NUR im ct — der Relay
     # bleibt opaker Pass-Through, keine Call-State-Maschine am Relay).
     "call_invite.enc", "call_end.enc"}
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
        # SEC-W2 W2-2 + B4-Grenze: die Allowlist wächst je Frame-Welle — die
        # LISTE im Log ist deshalb GEKAPPT (Vorschau < 400 Zeichen, kein
        # Log-Amplification-Rückschlag je neuem Frame-Typ).
        preview = sorted(_AGENT_FRAME_TYPES)
        if len(preview) > 14:
            preview = preview[:14] + [f"…(+{len(preview) - 14} weitere)"]
        logger.warning(
            "Agent %s: Frame type=%r verworfen (Allowlist: %s)",
            agent_id, _log_id(msg_type, 64), preview,
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
        await _guard_agent_frame(_handle_agent_register, state, agent_id, data, "_handle_agent_register")
        return
    # PH-3-T2: E2E-Frames zuerst (opaker Pass-Through + Session-Broker).
    if msg_type == "crypto.hello_ack":
        await _guard_agent_frame(_handle_agent_hello_ack, state, agent_id, data, "_handle_agent_hello_ack")
        return
    if msg_type == "crypto.session_reset":
        await _guard_agent_frame(_handle_agent_session_reset, state, agent_id, data, "_handle_agent_session_reset")
        return
    if msg_type == "chat.reply.enc":
        await _guard_agent_frame(_handle_agent_chat_reply_enc, state, agent_id, data, "_handle_agent_chat_reply_enc")
        return
    if msg_type == "chat.read.enc":
        await _guard_agent_frame(_handle_agent_chat_read_enc, state, agent_id, data, "_handle_agent_chat_read_enc")
        return
    if msg_type == "chat.activity.enc":
        await _guard_agent_frame(_handle_agent_chat_activity_enc, state, agent_id, data, "_handle_agent_chat_activity_enc")
        return
    # W16 (Fixliste 14): Session-Meta eigenständig (klar + enc).
    if msg_type == "chat.session_meta.enc":
        await _guard_agent_frame(_handle_agent_chat_session_meta_enc, state, agent_id, data, "_handle_agent_chat_session_meta_enc")
        return
    if msg_type == "chat.session_meta":
        await _guard_agent_frame(_handle_agent_chat_session_meta, state, agent_id, data, "_handle_agent_chat_session_meta")
        return
    # W16 (Fixliste 35): Modell-Katalog (klar + enc).
    if msg_type == "agent.models.enc":
        await _guard_agent_frame(_handle_agent_models_enc, state, agent_id, data, "_handle_agent_models_enc")
        return
    if msg_type == "agent.models":
        await _guard_agent_frame(_handle_agent_models, state, agent_id, data, "_handle_agent_models")
        return
    # MEDIA-T1: chunked Media Agent→Phone (opak) + Chunk-Ack (klar, light).
    if msg_type == "chat.media.enc":
        await _guard_agent_frame(_handle_agent_media_enc, state, agent_id, data, "_handle_agent_media_enc")
        return
    if msg_type == "chat.media.chunk_ack":
        await _guard_agent_frame(_handle_agent_media_ack, state, agent_id, data, "_handle_agent_media_ack")
        return
    # ── W21 (Anruf P1, roadmap-anruf.md §3.4): Call-Signaling Agent→Phone ─────
    # E2E-only (Inhalte NUR im ct), Guards wie chat.reply.enc — aber EIGENE
    # Rate-Buckets ('callsign:*', nie im heißen Chat-Bucket) + Owner-Claim am
    # chat_id-Namensraum (fremde Konversation = Verwurf).
    if msg_type == "call_invite.enc":
        await _guard_agent_frame(_handle_agent_call_invite, state, agent_id, data, "_handle_agent_call_invite")
        return
    if msg_type == "call_end.enc":
        await _guard_agent_frame(_handle_agent_call_end, state, agent_id, data, "_handle_agent_call_end")
        return
    # ── B4 (Multi-Gerät): Sync-Echo / Backfill-Batch / Status-Snapshot ───────
    # Sync+Backfill = E2E-enc opak (Guards wie chat.reply.enc, EIGENE Rate-
    # Buckets — FLIPSI-Frame-Guard: ein neuer Handler klingt NIE den heißen
    # chat.reply-Pfad ab), Snapshot = Klar (ids-only, Chat-Status-Transition-
    # Klasse: kein Spool, kein Rate-Bucket — wie chat.status PH2-T10).
    if msg_type == "chat.sync.enc":
        await _guard_agent_frame(_handle_agent_chat_sync_enc, state, agent_id, data, "_handle_agent_chat_sync_enc")
        return
    if msg_type == "chat.backfill.enc":
        await _guard_agent_frame(_handle_agent_chat_backfill_enc, state, agent_id, data, "_handle_agent_chat_backfill_enc")
        return
    if msg_type == "chat.status_snapshot":
        await _guard_agent_frame(_handle_agent_chat_status_snapshot, state, agent_id, data, "_handle_agent_chat_status_snapshot")
        return
    if msg_type == "chat.reply":
        await _guard_agent_frame(_handle_agent_chat_reply, state, agent_id, data, "_handle_agent_chat_reply")
        return
    if msg_type == "chat.read":
        await _guard_agent_frame(_handle_agent_chat_read, state, agent_id, data, "_handle_agent_chat_read")
        return
    if msg_type == "chat.activity":
        await _guard_agent_frame(_handle_agent_chat_activity, state, agent_id, data, "_handle_agent_chat_activity")
        return
    # W19 (SIRS-Neued): agent.status — Live-Zustand je Agent. Status ist
    # Transition: KEIN Rate-Bucket (niemals im chat.reply-Bucket), KEIN
    # Spool (W18: Live-Zustand, kein Verlauf); working-Dedup je agent_id,
    # sub/end je Frame durchgereicht.
    if msg_type == "agent.status.enc":
        await _guard_agent_frame(_handle_agent_status_enc, state, agent_id, data, "_handle_agent_status_enc")
        return
    if msg_type == "agent.status":
        await _guard_agent_frame(_handle_agent_status, state, agent_id, data, "_handle_agent_status")
        return
    # ── F1/J4-Fund 2: cron.status-Live-Verband (Gateway-Cron-Jobs) ──────────
    # Karte „Geplante Aufgaben" am Phone. Klar + enc je Connect/Handshake/
    # Refresh (Adapter emit_cron_status), EIGENER Rate-Bucket 'cronstatus:*'
    # (nie geteilt mit chat.reply), enc = opaker Pass-Through.
    if msg_type == "cron.status.enc":
        await _guard_agent_frame(_handle_agent_cron_status_enc, state, agent_id, data, "_handle_agent_cron_status_enc")
        return
    if msg_type == "cron.status":
        await _guard_agent_frame(_handle_agent_cron_status, state, agent_id, data, "_handle_agent_cron_status")
        return
    # keepalive/ping/presence: nur Presence (oben bereits gepflegt).
    logger.debug("Agent %s: frame type=%r (Presence)", agent_id, msg_type)


async def _guard_agent_frame(handler, state: _RelayState, agent_id: str,
                             data: dict, name: str) -> None:
    """J4-F4 (W16): Frame-Handler nicht fatal — eine Exception eines
    Handlers reißt die Agent-WS nicht mit; Fail-soft, Frame verworfen,
    Log mit Traceback (kein Abort). CancelledError läuft weiter durch."""
    try:
        await handler(state, agent_id, data)
    except asyncio.CancelledError:
        raise
    except Exception:
        logger.exception(
            "Agent %s: Frame-Handler %s fehlgeschlagen — Frame verworfen (WS bleibt)",
            agent_id, name)


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

    PH-4-T2: Auf agent.register antwortet der Relay mit agent.ident (signed
    Instanz-Identität, TOFU-Aushändigung). Bewusst als ANTWORT statt ungefragt
    je Connect: der Frame-Loop eines Agenten bleibt in seiner Reihenfolge
    unberührt (kein erstes, ungefragtes Identitäts-Frame vor chat.hello —
    sonst brechen existierende Agent-Implementierungen, die das erste Frame
    strikt erwarten, still). Agenten ohne register (Legacy) erfahren die
    Identität nicht — sie nutzen sie auch nicht (Fail-soft).
    """
    raw_name = data.get("display_name")
    if not isinstance(raw_name, str) or not raw_name.strip():
        logger.debug("Agent %s: agent.register ohne display_name — No-Op", agent_id)
        return
    display_name = raw_name.strip()[:64]
    async with state.agent_ws_lock:
        entry = state.agents.get(agent_id)
        changed = entry is not None and entry.get("display_name") != display_name
        ws = state.agent_ws.get(agent_id)
        if entry is not None:
            entry["display_name"] = display_name
    ident_reply = _relay_ident_frame()
    if ident_reply is not None and ws is not None and not ws.closed:
        try:
            await ws.send_json(ident_reply)
        except Exception:
            logger.info("Agent %s: agent.ident-Zustellung fehlgeschlagen", agent_id)
    if not changed:
        # Re-Register (Reconnect/Refresh): Name unverändert — kein Presence-Sturm.
        return
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
    # B2: der Relay reicht die Broker-Device-Identität der Session mit — der
    # Adapter (spätere Welle) führt je Gerät eine eigene E2E-Session; die
    # KOMPONENTE im Frame ist die Broker-Wahrheit (nicht Agent-Body).
    session_device_id = session.get("device_id") if isinstance(session, dict) else None
    frame = {
        "type": "crypto.hello_ack", "v": 1,
        "session_id": session_id,
        "agent_id": agent_id,
        "kex_pub": kex_pub.strip().lower(),
        "nonce": data["nonce"],
        "ct": data["ct"],
        "ts": int(time.time() * 1000),
    }
    if session_device_id:
        frame["device_id"] = str(session_device_id)
    # PH-4-T2: hello_ack = Rest-Transkript des Handshake-Verbands → signiert
    # (App-Verifikation = W23-Kandidat; das sig-Feld schadet am Phone nicht).
    _relay_sign_meta(frame)
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
    # B2: Device-Identität mit (Broker-Wahrheit vor dem Pop) — der Adapter
    # je Gerät kann den Reset je Session/Gerät zuordnen.
    session_device = session.get("device_id") if isinstance(session, dict) else None
    if session_device:
        frame["device_id"] = str(session_device)
    # PH-4-T2: session_reset = Enrollment-/Pairing-Sensibel-Verband →
    # signiert (sensibler Steuerkanal in v1, offline belegbar).
    _relay_sign_meta(frame)
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
    # W16 (Fixliste 14): klare session_meta auch im enc-Mirror (App liest das
    # Klartext-Top-Level im Phone-Handler gleich mit; ct = Kettensync).
    session_meta = data.get("session_meta")
    if session_meta is not None and not _session_meta_ok(session_meta):
        logger.warning("Agent %s: chat.activity.enc mit ungültiger session_meta — Feld verworfen", agent_id)
        session_meta = None
    if session_meta is not None:
        frame["session_meta"] = session_meta
    delivered = await _deliver_to_phone(state, frame)
    if not delivered:
        logger.debug("Agent %s: chat.activity.enc gespoolt (Phone offline)", agent_id)


# ── B4 (Multi-Gerät): chat.sync.enc / chat.backfill.enc / chat.status_snapshot┘
# Der Adapter verschließt je Geräte-Session (eigene Chiffre, AAD-Bindung); der
# Relay bleibt opaker Pass-Through mit Session-Routing (session_id → Ziel-
# Gerät) und prüft NUR Struktur + bestätigte Session + Replay-Fenster. KEIN
# Klartext-Verlauf, KEIN Key-Material am Relay (Kap. 2.2/7: Sirs Verbot).


def _enc_frame_valid_chat_id(value) -> Optional[str]:
    """B4: chat_id-Form-Gate für die neuen enc-Frames — Rückgabe = normalisierte
    chat_id oder None (verwerfen). Parität zum /chat-enc-Pfad (8..128, UTF-8,
    Strip)."""
    if not isinstance(value, str):
        return None
    chat_id = value.strip()
    if not (8 <= len(chat_id) <= 128) or not _utf8_encodable(chat_id):
        return None
    return chat_id


async def _handle_agent_chat_sync_enc(state: _RelayState, agent_id: str,
                                      data: dict) -> None:
    """chat.sync.enc → ECHO des NUTZER-Texts einer Nachricht (Gerät A) an die
    ANDEREN Geräte-Slots (je Ziel-Session eigene Chiffre, Adapter erzeugt je
    Empfänger EINEN Frame). Der Relay fächert über session_id → Gerät und
    spoolt offline Ziele in die EIGENE Bucket (Verbands-Parität chat.reply.enc:
    ein Gerät, das die Nachricht verpasst, holt sie beim Reconnect nach).
    Guards: EIGENER Rate-Bucket (verdrängt nie chat.reply), chat_id-Form,
    Struktur, bestätigte Session, Replay-Fenster, Owner-Claim — fail-closed."""
    config = _load_relay_config()
    if not _chat_rate_allow(f"agentsync:{agent_id}", _chat_rate_per_second(config)):
        logger.info("Agent %s: chat.sync.enc verworfen — Sync-Rate-Limit (Eigener-Bucket)", agent_id)
        return
    chat_id = _enc_frame_valid_chat_id(data.get("chat_id"))
    if chat_id is None:
        logger.warning("Agent %s: chat.sync.enc ohne gültiges chat_id — verworfen", agent_id)
        return
    reason = _validate_enc_frame(data)
    if reason is not None:
        logger.warning("Agent %s: chat.sync.enc ungültig (%s) — verworfen", agent_id, reason)
        return
    if await _enc_session_active(state, agent_id, data) is None:
        logger.warning(
            "Agent %s: chat.sync.enc ohne bestätigte E2E-Session — verworfen (fail-closed)",
            agent_id)
        return
    if not _relay_replay_allow(state, data["session_id"], "a2p",
                               data["hdr"]["n"], data.get("ts"),
                               data["hdr"]["rk"]):
        logger.warning("Agent %s: chat.sync.enc Replay/Fenster-Verletzung — verworfen", agent_id)
        return
    frame = {
        "type": "chat.sync.enc", "v": 1,
        "chat_id": chat_id,
        "agent_id": agent_id,
        "session_id": data["session_id"],
        "hdr": data["hdr"],
        "nonce": data["nonce"],
        "ct": data["ct"],
        "ts": int(time.time() * 1000),
    }
    # Klare Metadaten (msg_id) durchreichen — CHIPS-Kette: echo-Blase trägt
    # DENSELBE Ursprungs-msg_id (Kap. 2.3 #1): der chat.status-Frame trifft
    # die Blase auf JEDEM Gerät. Nie Inhalt/Klartext im Klartext-Feld.
    msg_id = data.get("msg_id")
    if isinstance(msg_id, str) and msg_id.strip() and len(msg_id) <= 128 \
            and _utf8_encodable(msg_id):
        frame["msg_id"] = msg_id
    elif msg_id is not None:
        logger.warning("Agent %s: chat.sync.enc mit ungültigem msg_id-Feld — Feld verworfen", agent_id)
    delivered = await _deliver_to_phone(state, frame, claim=(agent_id, chat_id))
    if delivered is None:
        return
    if not delivered:
        logger.info("Agent %s: chat.sync.enc gespoolt (Ziel-Gerät offline)", agent_id)


async def _handle_agent_chat_backfill_enc(state: _RelayState, agent_id: str,
                                          data: dict) -> None:
    """chat.backfill.enc → Verlaufs-Batch (Gateway-Verlauf je Geräte-Session
    enc) an das ANFORDERNDE Gerät. Session-Routing (requesting Session →
    Gerät); offline = EIGENE Outbox-Bucket (spool — Kap-Guard im Adapter:
    K je Anfrage, Batches ≤ 64; der Spool bleibt dadurch klein). Guards:
    EIGENER Rate-Bucket + chat_id + Struktur + Session + Replay (fail-closed)."""
    if not _chat_rate_allow(f"agentbackfill:{agent_id}", 6):
        logger.info("Agent %s: chat.backfill.enc verworfen — Backfill-Rate-Limit", agent_id)
        return
    chat_id = _enc_frame_valid_chat_id(data.get("chat_id"))
    if chat_id is None:
        logger.warning("Agent %s: chat.backfill.enc ohne gültiges chat_id — verworfen", agent_id)
        return
    reason = _validate_enc_frame(data)
    if reason is not None:
        logger.warning("Agent %s: chat.backfill.enc ungültig (%s) — verworfen", agent_id, reason)
        return
    if await _enc_session_active(state, agent_id, data) is None:
        logger.warning(
            "Agent %s: chat.backfill.enc ohne bestätigte E2E-Session — verworfen (fail-closed)",
            agent_id)
        return
    if not _relay_replay_allow(state, data["session_id"], "a2p",
                               data["hdr"]["n"], data.get("ts"),
                               data["hdr"]["rk"]):
        logger.warning("Agent %s: chat.backfill.enc Replay/Fenster-Verletzung — verworfen", agent_id)
        return
    frame = {
        "type": "chat.backfill.enc", "v": 1,
        "chat_id": chat_id,
        "agent_id": agent_id,
        "session_id": data["session_id"],
        "hdr": data["hdr"],
        "nonce": data["nonce"],
        "ct": data["ct"],
        "ts": int(time.time() * 1000),
    }
    delivered = await _deliver_to_phone(state, frame)
    if not delivered:
        logger.info("Agent %s: chat.backfill.enc gespoolt (Ziel-Gerät offline)", agent_id)


# B4: bekannte Chat-Status-Werte (ids-only Snapshot) — Parität zur App-Sicht
# ChatStatusRank.KNOWN {queued, delivered, read, failed} + Relay-_CHAT_STATUS_ORDER.
_STATUS_SNAPSHOT_KNOWN = frozenset({"queued", "delivered", "read", "failed"})
_STATUS_SNAPSHOT_CAP = 500          # Parität App ChatBackfillRules.STATUS_SNAPSHOT_CAP
_STATUS_SNAPSHOT_MSG_CAP = 128      # msg_id-Längen-Parität zum chat.read.msg_id


def _snapshot_statuses_ok(statuses) -> Optional[list]:
    """Struktur-Gate für chat.status_snapshot (ids-only, Klar). Erlaubt die
    BEIDEN App-tolerierten Formen: A) Array von {msg_id|messageId, status|state}
    B) Map {msg_id: status}. Rückgabe = normalisierte Array-Form (Liste von
    {msg_id, status}) oder None (verwerfen). KEIN Chat-Inhalt hier — nur ids."""
    if isinstance(statuses, dict):
        pairs = []
        for msg_id, value in statuses.items():
            if not isinstance(msg_id, str) or not msg_id.strip() \
                    or len(msg_id) > _STATUS_SNAPSHOT_MSG_CAP \
                    or not _utf8_encodable(msg_id):
                continue
            if not isinstance(value, str) or value not in _STATUS_SNAPSHOT_KNOWN:
                continue
            pairs.append({"msg_id": msg_id.strip(), "status": value})
        return pairs
    if not isinstance(statuses, list) or len(statuses) > _STATUS_SNAPSHOT_CAP:
        return None
    pairs = []
    for entry in statuses:
        if not isinstance(entry, dict):
            continue
        msg_id = entry.get("msg_id", entry.get("messageId"))
        value = entry.get("status", entry.get("state"))
        if not isinstance(msg_id, str) or not msg_id.strip() \
                or len(msg_id) > _STATUS_SNAPSHOT_MSG_CAP \
                or not _utf8_encodable(msg_id):
            continue
        if not isinstance(value, str) or value not in _STATUS_SNAPSHOT_KNOWN:
            continue
        pairs.append({"msg_id": msg_id.strip(), "status": value})
    return pairs


async def _handle_agent_chat_status_snapshot(state: _RelayState, agent_id: str,
                                             data: dict) -> None:
    """chat.status_snapshot (KLAR, ids-only: msg_id→delivered/read/failed, Cap
    500) → an das anfordernde Gerät (session_id-Routing) bzw. bei fehlender
    session_id an JEDES bekannte Gerät (Broadcast-Verband _deliver_to_phone).
    Transition-Klasse wie chat.status PH2-T10: KEIN Spool (Live-Zustand, kein
    Verlauf), KEIN Rate-Bucket; Adapter-Cap + Relay-Cap gegen RAM-Flut."""
    chat_id = _enc_frame_valid_chat_id(data.get("chat_id"))
    if chat_id is None:
        logger.warning("Agent %s: chat.status_snapshot ohne gültiges chat_id — verworfen", agent_id)
        return
    pairs = _snapshot_statuses_ok(data.get("statuses"))
    if pairs is None:
        logger.warning("Agent %s: chat.status_snapshot mit ungültiger statuses-Form — verworfen", agent_id)
        return
    session_id = data.get("session_id")
    if session_id is not None and (not isinstance(session_id, str)
                                   or not session_id.strip()
                                   or not _utf8_encodable(session_id)):
        session_id = None
    frame = {
        "type": "chat.status_snapshot", "v": 1,
        "chat_id": chat_id,
        "agent_id": agent_id,
        "ts": int(time.time() * 1000),
        "statuses": pairs,
    }
    if isinstance(session_id, str) and session_id.strip():
        frame["session_id"] = session_id.strip()
    # spool=False: Snapshot ist Zustands-Fenster (kein Verlauf) — ein offline
    # Gerät holt den Stand beim NÄCHSTEN Backfill frisch (Adapter sendet dann).
    delivered = await _deliver_to_phone(state, frame, spool=False)
    if not delivered:
        logger.info("Agent %s: chat.status_snapshot verworfen (kein lebender Ziel-Slot)", agent_id)


def _session_meta_ok(value) -> bool:
    """W16 (Fixliste 14): Struktur-Gate für session_meta-Objekte (Parität zum
    Kotlin-Parser: platform/model = kurze Strings, sub_agents ≤ 8 je
    {name Pflicht, status?, model? — alle ≤ 64), Gesamtpayload ≤ 2 KiB.
    Fail-closed: alles Unbekannte/Übergröße = Frame ohne session_meta."""
    if value is None:
        return True
    if not isinstance(value, dict):
        return False
    encoded = json.dumps(value, separators=(",", ":"), ensure_ascii=True)
    if len(encoded) > 2048:
        return False
    for key in ("platform", "model"):
        item = value.get(key)
        if item is not None and (not isinstance(item, str) or len(item) > 64):
            return False
    subs = value.get("sub_agents")
    if subs is not None:
        if not isinstance(subs, list) or len(subs) > 8:
            return False
        for sub in subs:
            if not isinstance(sub, dict):
                return False
            name = sub.get("name")
            if not isinstance(name, str) or not name.strip() or len(name) > 64:
                return False
            for opt in ("status", "model"):
                item = sub.get(opt)
                if item is not None and (not isinstance(item, str) or len(item) > 64):
                    return False
    return True


async def _handle_agent_chat_session_meta_enc(state: _RelayState, agent_id: str,
                                              data: dict) -> None:
    """W16 (Fixliste 14): chat.session_meta.enc → opak ans Phone (Typ bleibt).
    session_meta liegt NUR im ct (Adapter versetzt die Meta ins ct); der
    Relay öffnet nie. Guards wie chat.reply.enc: Rate-Limit, chat_id,
    Struktur, bestätigte E2E-Session, Replay-Fenster + Owner-Claim."""
    config = _load_relay_config()
    if not _chat_rate_allow(f"agentmeta:{agent_id}", _AGENT_META_LIGHT_MAX_PER_SECOND):
        logger.info("Agent %s: chat.session_meta.enc verworfen — Meta-Rate-Limit", agent_id)
        return
    chat_id = data.get("chat_id")
    if not isinstance(chat_id, str) or not chat_id.strip():
        logger.warning("Agent %s: chat.session_meta.enc ohne gültiges chat_id — verworfen", agent_id)
        return
    chat_id = chat_id.strip()
    reason = _validate_enc_frame(data)
    if reason is not None:
        logger.warning("Agent %s: chat.session_meta.enc ungültig (%s) — verworfen", agent_id, reason)
        return
    if await _enc_session_active(state, agent_id, data) is None:
        logger.warning(
            "Agent %s: chat.session_meta.enc ohne bestätigte E2E-Session — verworfen (fail-closed)",
            agent_id)
        return
    if not _relay_replay_allow(state, data["session_id"], "a2p",
                               data["hdr"]["n"], data.get("ts"),
                               data["hdr"]["rk"]):
        logger.warning("Agent %s: chat.session_meta.enc Replay/Fenster-Verletzung — verworfen", agent_id)
        return
    frame = {
        "type": "chat.session_meta.enc", "v": 1,
        "chat_id": chat_id,
        "agent_id": agent_id,
        "session_id": data["session_id"],
        "hdr": data["hdr"],
        "nonce": data["nonce"],
        "ct": data["ct"],
        "ts": int(time.time() * 1000),
    }
    delivered = await _deliver_to_phone(state, frame, claim=(agent_id, chat_id))
    if not delivered:
        logger.debug("Agent %s: chat.session_meta.enc gespoolt (Phone offline)", agent_id)


async def _handle_agent_chat_session_meta(state: _RelayState, agent_id: str,
                                          data: dict) -> None:
    """W16 (Fixliste 14): chat.session_meta (Klartext-Legacy). Guards wie
    chat.activity (Downgrade: bei E2E verworfen — das spart dem Phone das
    Drop-Frame), Struktur-Gate, Owner-Claim; session_meta bleibt oben-Level
    klar (Legacy-Session braucht den Plain-Pfad)."""
    config = _load_relay_config()
    if not _chat_rate_allow(f"agentmeta:{agent_id}", _AGENT_META_LIGHT_MAX_PER_SECOND):
        logger.info("Agent %s: chat.session_meta verworfen — Chat-Rate-Limit", agent_id)
        return
    chat_id = data.get("chat_id")
    if not isinstance(chat_id, str) or not chat_id.strip():
        logger.warning("Agent %s: chat.session_meta ohne gültiges chat_id — verworfen", agent_id)
        return
    chat_id = chat_id.strip()
    session_meta = data.get("session_meta")
    if session_meta is not None and not _session_meta_ok(session_meta):
        logger.warning("Agent %s: chat.session_meta ungültige session_meta-Struktur — verworfen", agent_id)
        return
    if await _agent_has_e2e_session(state, agent_id):
        logger.warning(
            "Agent %s: Klartext-chat.session_meta verworfen — E2E-Session aktiv (Downgrade-Schutz)",
            agent_id)
        return
    if not await _chat_owner_allows(state, agent_id, chat_id):
        return
    frame = {
        "type": "chat.session_meta", "v": 1,
        "chat_id": chat_id,
        "agent_id": agent_id,
        "ts": int(time.time() * 1000),
    }
    if session_meta is not None:
        frame["session_meta"] = session_meta
    delivered = await _deliver_to_phone(state, frame, claim=(agent_id, chat_id))
    if not delivered:
        logger.debug("Agent %s: chat.session_meta gespoolt (Phone offline)", agent_id)


# ── W19 (SIRS-Neued): agent.status — Live-Arbeitszustand je Agent ────────────


def _agent_status_ok(data) -> Optional[dict]:
    """W19 Struktur-Gate für agent.status/.enc-Bodies (pure, ohne Log).

    Pflichtfelder: agent_id (str ≤64), working (bool), started_at (endliche
    ms) — Verstoß → None = Frame verwerfen (Parität chat.activity: Fail-soft
    statt Throw, die Agent-WS bleibt offen).
    Optionale Felder: label (str ≤64), sub ({id, name} je str ≤64), end
    (bool) — Verstoß gegen ein OPTIONSfeld lässt nur das Feld fallen, der
    Frame bleibt gültig (Parität _session_meta_ok: Feld-Drop, kein Frame-
    Verlust; die Warnung loggt der Handler).
    Rückgabe: validierte Felder oder None."""
    if not isinstance(data, dict):
        return None
    body_agent_id = data.get("agent_id")
    if (not isinstance(body_agent_id, str) or not body_agent_id.strip()
            or len(body_agent_id.strip()) > 64):
        return None
    working = data.get("working")
    if not isinstance(working, bool):
        return None
    started_at = data.get("started_at")
    # SEC-W1 B F-03-Parität: nur endlich-numerische ms koerzierbar (bool
    # zählt nicht als ms, int(inf) wirft OverflowError).
    if not _finite_ms(started_at):
        return None
    fields: dict = {
        "agent_id": body_agent_id.strip(),
        "working": working,
        "started_at": int(started_at),
    }
    label = data.get("label")
    if (label is not None and isinstance(label, str) and label.strip()
            and len(label) <= 64):
        fields["label"] = label.strip()
    sub = data.get("sub")
    if sub is not None and isinstance(sub, dict):
        sub_id = sub.get("id")
        sub_name = sub.get("name")
        if (isinstance(sub_id, str) and sub_id.strip()
                and len(sub_id.strip()) <= 64
                and isinstance(sub_name, str) and sub_name.strip()
                and len(sub_name.strip()) <= 64):
            fields["sub"] = {"id": sub_id.strip(), "name": sub_name.strip()}
    end = data.get("end")
    if end is not None and isinstance(end, bool):
        fields["end"] = end
    return fields


def _cap_agent_status(state: _RelayState) -> None:
    """W19: FIFO-RAM-Schutz der agent_status-Map (Parität _cap_chat_activity);
    Aufruf NUR unter broker_lock."""
    while len(state.agent_status) > _AGENT_STATUS_MAX:
        state.agent_status.pop(next(iter(state.agent_status)))


def _agent_status_dedup_allow(state: _RelayState, agent_id: str,
                              fields: dict) -> bool:
    """W19 Transition/Slot-Verband: Dedup JE agent_id über das (working,
    label)-Paar — J4-Fund 3: ein Label-Wechsel bei gleichem working ist ein
    Transition („Sucht im Web" muss die App erreichen), der gleiche Verband
    ist stilles No-Op (je Wechsel genau 1 Frame). Frames des Slot-Verbands
    (sub/end) sind KEIN Dedup — je Frame durchreichen, die App verwaltet
    Sub-Slots je sub.id/end; sie dürfen das Haupt-Aggregat
    state.agent_status[agent_id] NICHT mit dem Slot-working überschreiben
    (sonst druckt der nächste parent-Frame denselben Stand doppelt). State
    parallel zu chat_activity, KEIN Rate-Bucket (Status ist Transition);
    Write NUR unter broker_lock."""
    slot_verband = "sub" in fields or fields.get("end") is not None
    if slot_verband:
        return True  # Slot-Verband ohne Dedup UND ohne Aggregat-Write
    band = (fields["working"], fields.get("label"))
    if state.agent_status.get(agent_id) == band:
        return False  # Dedup: je Wechsel genau 1 Frame
    state.agent_status[agent_id] = band
    _cap_agent_status(state)
    return True


async def _deliver_agent_status_to_phone(state: _RelayState, frame: dict) -> bool:
    """W18/W19: agent.status = Live-Zustand, KEIN Verlauf — Zustellung NUR
    bei online Geräten (B1: Fan-out an ALLE lebenden Slots, _deliver_locked
    mit spool=False). Kein Gerät verbunden/tote WS = False = bewusst verworfen:
    KEIN Outbox-Append, kein Spool (ein 'arbeitet…'-Zustand ist beim
    Reconnect längst Geschichte)."""
    async with state.broker_lock:
        return await _deliver_locked(state, frame, spool=False)


# ── F1/J4-Fund 2: cron.status-Live-Verband (Gateway-Cron-Jobs → App) ────────
# Karte „Geplante Aufgaben": Adapter emit_cron_status sendet je Connect/
# Handshake + nur-je-Änderung im Refresh-Loop (Fingerprint); dieser Verband
# lag bis hierhin KOMPLETT tot (beide Typen fehlten in _AGENT_FRAME_TYPES
# = Junk-Drop, App-Handler toter Code). enc = opaker Pass-Through (Guards
# wie agent.status.enc), EIGENER Rate-Bucket 'cronstatus:*' (nie geteilt
# mit chat.reply), Klartext nur ohne E2E-Session (enc-first, Downgrade-
# Schutz Parität agent.status). Delivery via _deliver_to_phone (Spool wie
# agent.models — Katalog-Klasse; ein spät zugestellter enc-Frame >10 min
# fällt ohnehin am App-ts-Fenster weg (Freshness), schadet also nicht.


async def _handle_agent_cron_status_enc(state: _RelayState, agent_id: str,
                                        data: dict) -> None:
    """F1/J4-Fund 2: cron.status.enc → Phone (Typ bleibt, ct opak). Prüfkette
    = Klon der agent.status.enc-Kette: EIGENER Rate-Bucket (cronstatus:*),
    _validate_enc_frame (Form), _enc_session_active fail-closed,
    _relay_replay_allow (Fenster je Kette). KEIN Owner-Claim (kein chat_id-
    Anspruch — der Verband lebt je Agent), KEIN Dedup (der Adapter deduppt
    über den Zeilen-Fingerprint)."""
    if not _chat_rate_allow(f"cronstatus:{agent_id}", _CRON_STATUS_MAX_PER_SECOND):
        logger.info("Agent %s: cron.status.enc verworfen — Cron-Rate-Limit", agent_id)
        return
    reason = _validate_enc_frame(data)
    if reason is not None:
        logger.warning("Agent %s: cron.status.enc ungültig (%s) — verworfen",
                       agent_id, reason)
        return
    if await _enc_session_active(state, agent_id, data) is None:
        logger.warning(
            "Agent %s: cron.status.enc ohne bestätigte E2E-Session — verworfen (fail-closed)",
            agent_id)
        return
    if not _relay_replay_allow(state, data["session_id"], "a2p",
                               data["hdr"]["n"], data.get("ts"),
                               data["hdr"]["rk"]):
        logger.warning(
            "Agent %s: cron.status.enc Replay/Fenster-Verletzung — verworfen",
            agent_id)
        return
    if data.get("agent_id") not in (None, agent_id):
        logger.warning(
            "Agent %s: cron.status.enc meldet fremde body-agent_id %s — WS-Identität gewinnt",
            agent_id, _log_id(data.get("agent_id")))
    frame = {
        "type": "cron.status.enc", "v": 1,
        "agent_id": agent_id,
        "session_id": data["session_id"],
        "hdr": data["hdr"],
        "nonce": data["nonce"],
        "ct": data["ct"],
        "ts": int(time.time() * 1000),
    }
    # J4-Fund 1 (AAD-Kette): chat_id-Klarfeld mitnehmen — Parität zum
    # agent.status.enc-Rebuild (Kotlin-AAD 'fb-v1|session_id|chat_id|agent_id').
    frame["chat_id"] = data.get("chat_id") or ""
    delivered = await _deliver_to_phone(state, frame)
    if not delivered:
        logger.debug("Agent %s: cron.status.enc keine Live-Zustellung (Phone offline)",
                     agent_id)


async def _handle_agent_cron_status(state: _RelayState, agent_id: str,
                                    data: dict) -> None:
    """F1/J4-Fund 2: cron.status (Klartext-Legacy, NUR ohne E2E-Session).
    Struktur-Gate auf der Jobs-Liste (FAIL-CLOSED: keine Liste/kaputte
    Zeile = Verwurf, Parität agent.models-Liste), Downgrade-Schutz (bei
    bestätigter E2E-Session verworfen — der Agent muss cron.status.enc
    senden), WS-Identität gewinnt, EIGENER Rate-Bucket."""
    if not _chat_rate_allow(f"cronstatus:{agent_id}", _CRON_STATUS_MAX_PER_SECOND):
        logger.info("Agent %s: cron.status verworfen — Cron-Rate-Limit", agent_id)
        return
    jobs = data.get("jobs")
    if not _agent_cron_jobs_ok(jobs):
        logger.warning("Agent %s: cron.status ungültige Jobs-Liste — verworfen",
                       agent_id)
        return
    if await _agent_has_e2e_session(state, agent_id):
        logger.warning(
            "Agent %s: Klartext-cron.status verworfen — E2E-Session aktiv (Downgrade-Schutz)",
            agent_id)
        return
    if data.get("agent_id") not in (None, agent_id):
        logger.warning(
            "Agent %s: cron.status meldet fremde body-agent_id %s — WS-Identität gewinnt",
            agent_id, _log_id(data.get("agent_id")))
    frame = {
        "type": "cron.status", "v": 1,
        "agent_id": agent_id,
        "jobs": jobs,
        "ts": int(time.time() * 1000),
    }
    delivered = await _deliver_to_phone(state, frame)
    if not delivered:
        logger.debug("Agent %s: cron.status keine Live-Zustellung (Phone offline)",
                     agent_id)


async def _handle_agent_status_enc(state: _RelayState, agent_id: str,
                                   data: dict) -> None:
    """W19: agent.status.enc → Phone (Typ bleibt). Prüfkette = Klon der
    chat.activity.enc-Kette: Struktur-Gate auf den KLAREN top-level-Feldern
    (working/started_at/label/sub/end bleiben klar = Dedup + Phone-UI-
    Fallback, Parität chat.activity.enc), _validate_enc_frame (Form),
    _enc_session_active fail-closed, _relay_replay_allow (Replay-Fenster).
    ct bleibt OPAK (nie geöffnet). KEIN Owner-Claim (kein chat_id — der
    Status lebt je Agent), KEIN Rate-Bucket, KEIN Spool (Live-Zustand)."""
    fields = _agent_status_ok(data)
    if fields is None:
        logger.warning(
            "Agent %s: agent.status.enc ohne gültige agent_id/working/started_at — verworfen",
            agent_id)
        return
    reason = _validate_enc_frame(data)
    if reason is not None:
        logger.warning("Agent %s: agent.status.enc ungültig (%s) — verworfen",
                       agent_id, reason)
        return
    if await _enc_session_active(state, agent_id, data) is None:
        logger.warning(
            "Agent %s: agent.status.enc ohne bestätigte E2E-Session — verworfen (fail-closed)",
            agent_id)
        return
    if not _relay_replay_allow(state, data["session_id"], "a2p",
                               data["hdr"]["n"], data.get("ts"),
                               data["hdr"]["rk"]):
        logger.warning(
            "Agent %s: agent.status.enc Replay/Fenster-Verletzung — verworfen",
            agent_id)
        return
    async with state.broker_lock:
        if not _agent_status_dedup_allow(state, agent_id, fields):
            return
    frame = {
        "type": "agent.status.enc", "v": 1,
        "agent_id": agent_id,
        "working": fields["working"],       # klar: PH2-Dedup + UI-Fallback
        "started_at": fields["started_at"],
        "session_id": data["session_id"],
        "hdr": data["hdr"],
        "nonce": data["nonce"],
        "ct": data["ct"],
        "ts": int(time.time() * 1000),
    }
    # J4-Fund 1 (AAD-Kette): das Klartext-chat_id des Agent-Frames MUSS in
    # den Phone-Rebuild — Kotlin bindet die AAD 'fb-v1|session_id|chat_id|
    # agent_id' an das chat_id-ENVELOPE-Feld; fehlt es, baut die App die AAD
    # mit chat_id='' → GCM-Tag ungültig → Frame verworfen (alle agent-
    # scoped Verbände tot NACH der ersten Nutzer-Nachricht: ab dann nutzt
    # der Adapter wire = sess.chat_id in `_enc_fanout_frames`). Falscher/
    # gemogelter Wert schadet nicht: die Endpunkte fangen ihn mit GCM.
    frame["chat_id"] = data.get("chat_id") or ""
    if "label" in fields:
        frame["label"] = fields["label"]
    if "sub" in fields:
        frame["sub"] = fields["sub"]
    if fields.get("end") is not None:
        frame["end"] = fields["end"]
    delivered = await _deliver_agent_status_to_phone(state, frame)
    if not delivered:
        logger.debug(
            "Agent %s: agent.status.enc verworfen — Phone offline (Live-Zustand, kein Spool)",
            agent_id)


async def _handle_agent_status(state: _RelayState, agent_id: str,
                               data: dict) -> None:
    """W19 (SIRS-Neued): agent.status (Klartext) → Live-Arbeitszustand ans
    Phone. Body {agent_id, working, started_at, label?, sub?, end?} — die
    WS-Identität gewinnt (Parität agent.register: eine im Frame
    mitgeschickte agent_id wird NIE als Zustell-Identität akzeptiert,
    Abweichung = Warning). Struktur-Gate fail-soft (Parität chat.activity),
    Downgrade-Schutz (bei bestätigter E2E-Session verworfen — der Agent
    muss agent.status.enc senden), working-Dedup je agent_id (State
    parallel zu chat_activity), sub/end JE Frame durchgereicht. KEIN
    Rate-Bucket (Status ist Transition, niemals im chat.reply-Bucket), KEIN
    Spool (W18: Live-Zustand, kein Verlauf)."""
    fields = _agent_status_ok(data)
    if fields is None:
        logger.warning(
            "Agent %s: agent.status ohne gültige agent_id/working/started_at — verworfen",
            agent_id)
        return
    if fields["agent_id"] != agent_id:
        logger.warning(
            "Agent %s: agent.status meldet fremde body-agent_id %s — WS-Identität gewinnt",
            agent_id, _log_id(fields["agent_id"]))
    if await _agent_has_e2e_session(state, agent_id):
        logger.warning(
            "Agent %s: Klartext-agent.status verworfen — E2E-Session aktiv (Downgrade-Schutz)",
            agent_id)
        return
    async with state.broker_lock:
        if not _agent_status_dedup_allow(state, agent_id, fields):
            return
    frame = {
        "type": "agent.status", "v": 1,
        "agent_id": agent_id,
        "working": fields["working"],
        "started_at": fields["started_at"],
        "ts": int(time.time() * 1000),
    }
    if "label" in fields:
        frame["label"] = fields["label"]
    if "sub" in fields:
        frame["sub"] = fields["sub"]
    if fields.get("end") is not None:
        frame["end"] = fields["end"]
    delivered = await _deliver_agent_status_to_phone(state, frame)
    if not delivered:
        logger.debug(
            "Agent %s: agent.status verworfen — Phone offline (Live-Zustand, kein Spool)",
            agent_id)


def _agent_models_ok(models) -> bool:
    """W16 (Fixliste 35): Struktur-Gate für agent.models-Listen — Liste ≤ 24,
    je Eintrag nicht-leerer String ≤ 64. Fail-closed: sonst verwerfen."""
    if models is None:
        return True
    if not isinstance(models, list) or len(models) > 24:
        return False
    for entry in models:
        if not isinstance(entry, str) or not entry.strip() or len(entry) > 64:
            return False
    return True


# F1/J4-Fund 2: cron.status-Zeilen-Vertrag (Parität
# contrib/hermes-chat-plugin/adapter.py _cron_row + App-Parser
# DashboardRules/parseCronJobs): name ≤ 64, status ∈ {planned, running,
# paused}, next_at NUR bei planned mit strikt positivem ms.
_CRON_KNOWN_STATES = frozenset({"planned", "running", "paused"})


def _agent_cron_jobs_ok(jobs) -> bool:
    """Struktur-Gate für die cron.status-Jobs-Liste (fail-closed: keine Liste
    oder eine kaputte Zeile = Frame verwerfen — Klartext-Pfad. Der enc-Pfad
    liest die Liste NICHT, ct bleibt opak)."""
    if not isinstance(jobs, list) or len(jobs) > 32:
        return False
    for row in jobs:
        if not isinstance(row, dict):
            return False
        name = row.get("name")
        if not isinstance(name, str) or not name.strip() or len(name) > 64:
            return False
        status = row.get("status")
        if not isinstance(status, str) or status not in _CRON_KNOWN_STATES:
            return False
        next_at = row.get("next_at")
        if next_at is None:
            if status == "planned":
                # planned OHNE next_at bleibt gültig (nächste Ausführung
                # unbekannt — Parität App-Parser: next_at optional).
                continue
        elif isinstance(next_at, bool) or not isinstance(next_at, int) \
                or next_at <= 0 or not _finite_ms(next_at):
            return False
        elif status != "planned":
            return False  # next_at gibt es NUR für planned (Adapter-Vertrag)
    return True


async def _handle_agent_models_enc(state: _RelayState, agent_id: str,
                                   data: dict) -> None:
    """W16 (Fixliste 35): agent.models.enc → opak ans Phone (Typ bleibt,
    Listen liegen NUR im ct). Guards wie chat.reply.enc (Rate/Struktur/
    Session/Replay + Owner-Claim)."""
    config = _load_relay_config()
    if not _chat_rate_allow(f"agentmeta:{agent_id}", _AGENT_META_LIGHT_MAX_PER_SECOND):
        logger.info("Agent %s: agent.models.enc verworfen — Meta-Rate-Limit", agent_id)
        return
    reason = _validate_enc_frame(data)
    if reason is not None:
        logger.warning("Agent %s: agent.models.enc ungültig (%s) — verworfen", agent_id, reason)
        return
    if await _enc_session_active(state, agent_id, data) is None:
        logger.warning(
            "Agent %s: agent.models.enc ohne bestätigte E2E-Session — verworfen (fail-closed)",
            agent_id)
        return
    if not _relay_replay_allow(state, data["session_id"], "a2p",
                               data["hdr"]["n"], data.get("ts"),
                               data["hdr"]["rk"]):
        logger.warning("Agent %s: agent.models.enc Replay/Fenster-Verletzung — verworfen", agent_id)
        return
    frame = {
        "type": "agent.models.enc", "v": 1,
        "agent_id": agent_id,
        "session_id": data["session_id"],
        "hdr": data["hdr"],
        "nonce": data["nonce"],
        "ct": data["ct"],
        "ts": int(time.time() * 1000),
    }
    # J4-Fund 1 (AAD-Kette): chat_id-Klarfeld mitnehmen — Parität zum
    # agent.status.enc-Rebuild (Kotlin-AAD 'fb-v1|session_id|chat_id|agent_id').
    frame["chat_id"] = data.get("chat_id") or ""
    delivered = await _deliver_to_phone(state, frame)
    if not delivered:
        logger.debug("Agent %s: agent.models.enc gespoolt (Phone offline)", agent_id)


async def _handle_agent_models(state: _RelayState, agent_id: str, data: dict) -> None:
    """W16 (Fixliste 35): agent.models (Klartext-Legacy). Struktur-Gate +
    Durchreich ans Phone (App entscheidet Downgrade selbst)."""
    config = _load_relay_config()
    if not _chat_rate_allow(f"agentmeta:{agent_id}", _AGENT_META_LIGHT_MAX_PER_SECOND):
        logger.info("Agent %s: agent.models verworfen — Meta-Rate-Limit", agent_id)
        return
    models = data.get("models")
    if models is not None and not _agent_models_ok(models):
        logger.warning("Agent %s: agent.models ungültige Liste — verworfen", agent_id)
        return
    frame = {
        "type": "agent.models", "v": 1,
        "agent_id": agent_id,
        "ts": int(time.time() * 1000),
    }
    if models is not None:
        frame["models"] = models
    delivered = await _deliver_to_phone(state, frame)
    if not delivered:
        logger.debug("Agent %s: agent.models gespoolt (Phone offline)", agent_id)


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


# ── W21 (Anruf P1, roadmap-anruf.md §3.4): Call-Signaling am Relay ─────────────


def _call_frame_valid_call_id(value) -> Optional[str]:
    """call_id-Form-Gate: 8-64 Zeichen [A-Za-z0-9_-], UTF-8 (Parität media_id).
    Rückgabe = normalisierte call_id oder None (verwerfen)."""
    if not isinstance(value, str):
        return None
    call_id = value.strip()
    if not _CALL_ID_RE.fullmatch(call_id) or not _utf8_encodable(call_id):
        return None
    return call_id


async def _call_frame_guards(state: _RelayState, agent_id: str, data: dict,
                             label: str) -> Optional[tuple[str, str]]:
    """Gemeinsame fail-closed Guard-Kette der Agent→Phone-Call-Frames:

    EIGENER Rate-Bucket ('callsign:<agent>' — verdrängt NIE chat.reply),
    chat_id-/call_id-Form, enc-Struktur [_validate_enc_frame — ct bleibt
    OPAK], bestätigte E2E-Session [_enc_session_active — NUR bekannte
    Sessions] und Replay-Fenster [_relay_replay_allow].
    Rückgabe (chat_id, call_id) oder None = verwerfen (Grund geloggt)."""
    config = _load_relay_config()
    if not _chat_rate_allow(f"callsign:{agent_id}", _call_rate_per_second(config)):
        logger.info("Agent %s: %s verworfen — Call-Rate-Limit (Eigener-Bucket)",
                    agent_id, label)
        return None
    chat_id = _enc_frame_valid_chat_id(data.get("chat_id"))
    if chat_id is None:
        logger.warning("Agent %s: %s ohne gültiges chat_id — verworfen",
                       agent_id, label)
        return None
    call_id = _call_frame_valid_call_id(data.get("call_id"))
    if call_id is None:
        logger.warning("Agent %s: %s ohne gültiges call_id — verworfen",
                       agent_id, label)
        return None
    reason = _validate_enc_frame(data)
    if reason is not None:
        logger.warning("Agent %s: %s ungültig (%s) — verworfen", agent_id, label, reason)
        return None
    if await _enc_session_active(state, agent_id, data) is None:
        logger.warning(
            "Agent %s: %s ohne bestätigte E2E-Session — verworfen (fail-closed)",
            agent_id, label)
        return None
    if not _relay_replay_allow(state, data["session_id"], "a2p",
                               data["hdr"]["n"], data.get("ts"),
                               data["hdr"]["rk"]):
        logger.warning(
            "Agent %s: %s Replay/Fenster-Verletzung — verworfen", agent_id, label)
        return None
    return chat_id, call_id


def _call_enc_passthrough_frame(frame_type: str, agent_id: str, data: dict,
                                chat_id: str, call_id: str) -> dict:
    """Opaker Durchreich-Frame (Inhalte NUR im ct; Klar = IDs + Krypto-Hülle).
    Parität chat.reply.enc/chat.sync.enc: hdr/nonce/ct unverändert, ts frisch."""
    return {
        "type": frame_type, "v": 1,
        "chat_id": chat_id,
        "call_id": call_id,
        "agent_id": agent_id,
        "session_id": data["session_id"],
        "hdr": data["hdr"],
        "nonce": data["nonce"],
        "ct": data["ct"],
        "ts": int(time.time() * 1000),
    }


async def _handle_agent_call_invite(state: _RelayState, agent_id: str,
                                    data: dict) -> None:
    """W21 (Anruf P1): call_invite.enc → opak ans Phone (§3.4: Vollbild
    'incoming call'). kind/reason/say liegen NUR im ct; Klar bleiben IDs.
    Owner-Claim (Parität chat.reply.enc): eine fremde chat_id (Owner =
    anderer Agent) wird verworfen; eine unbekannte chat_id wird vom ERSTEN
    Invite geclaimt (Agent-initiierter Anruf gehört ab sofort dem Agenten —
    Namespace-Isolation der Call-Schiene). Session-Guards fail-closed."""
    guarded = await _call_frame_guards(state, agent_id, data, "call_invite.enc")
    if guarded is None:
        return
    chat_id, call_id = guarded
    frame = _call_enc_passthrough_frame(
        _CALL_INVITE_ENC_FRAME, agent_id, data, chat_id, call_id)
    delivered = await _deliver_to_phone(state, frame, claim=(agent_id, chat_id))
    if delivered is None:
        return
    if not delivered:
        logger.info("Agent %s: call_invite.enc gespoolt (Phone offline)", agent_id)


async def _handle_agent_call_end(state: _RelayState, agent_id: str,
                                 data: dict) -> None:
    """W21 (Anruf P1): call_end.enc → opak ans Phone (Auflegen: Agent-Initiative
    ODER Echo des Tools). Gleiche Guards wie der Invite (eigenes Bucket-Member,
    Form, Session, Replay); Owner-Claim unverändert — ein End in einer fremden
    Konversation gilt nicht."""
    guarded = await _call_frame_guards(state, agent_id, data, "call_end.enc")
    if guarded is None:
        return
    chat_id, call_id = guarded
    frame = _call_enc_passthrough_frame(
        _CALL_END_ENC_FRAME, agent_id, data, chat_id, call_id)
    delivered = await _deliver_to_phone(state, frame, claim=(agent_id, chat_id))
    if delivered is None:
        return
    if not delivered:
        logger.info("Agent %s: call_end.enc gespoolt (Phone offline)", agent_id)


async def _handle_phone_call_signal(state: _RelayState, sender_device_id: str,
                                    data: dict) -> None:
    """W21 (Anruf P1): Phone→Agent Call-Signaling (call_ringing/accepted/
    declined/missed/ended.enc) — fail-closed je SENDER-Gerät:

    EIGENER Rate-Bucket je Gerät; Session-Broker = Routing-Wahrheit: die
    Session muss BESTÄTIGT und geackt sein UND zu DIESEM Gerät gehören (ein
    fremder Slot signalisiert keine fremde Session); das Ziel-agent_id
    kommt IMMER aus dem Broker (nie aus dem Frame-Inhalt). ct bleibt OPAK,
    Replay-Fenster p2a, Zustellung als opaker Frame an die Agent-WS
    (Spool-Verband wie chat.backfill_request — Spät-Signale verwirft der
    Adapter selbst; ein Ring/Ende ist beim Flush längst Geschichte)."""
    frame_type = str(data.get("type") or "")
    if not _chat_rate_allow(f"callsig:{sender_device_id}",
                            _CALL_SIGNAL_MAX_PER_SECOND):
        logger.info("Gerät %s: %s über Call-Signal-Rate-Cap — verworfen",
                    _mask_id(sender_device_id), frame_type)
        return
    session_id = data.get("session_id")
    if not isinstance(session_id, str) or not session_id.strip() \
            or not _utf8_encodable(session_id):
        logger.warning("Gerät %s: %s ohne gültige session_id — verworfen",
                       _mask_id(sender_device_id), frame_type)
        return
    chat_id = _enc_frame_valid_chat_id(data.get("chat_id"))
    call_id = _call_frame_valid_call_id(data.get("call_id"))
    if chat_id is None or call_id is None:
        logger.warning("Gerät %s: %s ohne gültiges chat_id/call_id — verworfen",
                       _mask_id(sender_device_id), frame_type)
        return
    reason = _validate_enc_frame(data)
    if reason is not None:
        logger.warning("Gerät %s: %s ungültig (%s) — verworfen",
                       _mask_id(sender_device_id), frame_type, reason)
        return
    sender_key = _normalize_registry_device_id(sender_device_id)
    async with state.broker_lock:
        _prune_crypto_sessions(state)
        session = state.crypto_sessions.get(session_id)
        broker_agent = str(session["agent_id"]) \
            if isinstance(session, dict) and session.get("agent_id") else None
        broker_device = str(session.get("device_id") or "") \
            if isinstance(session, dict) else ""
        acked = bool(session.get("acked")) if isinstance(session, dict) else False
    if broker_agent is None or not acked:
        logger.warning(
            "Gerät %s: %s ohne bestätigte E2E-Session — verworfen (fail-closed)",
            _mask_id(sender_device_id), frame_type)
        return
    if broker_device != sender_key:
        logger.warning(
            "Gerät %s: %s signalisiert Fremd-Session (Geräte-Bindung %s) — "
            "verworfen (fail-closed)", _mask_id(sender_device_id), frame_type,
            _mask_id(broker_device) if broker_device else "?")
        return
    if not _relay_replay_allow(state, session_id, "p2a",
                               data["hdr"]["n"], data.get("ts"),
                               data["hdr"]["rk"]):
        logger.warning("Gerät %s: %s Replay/Fenster-Verletzung — verworfen",
                       _mask_id(sender_device_id), frame_type)
        return
    frame = _call_enc_passthrough_frame(
        frame_type, broker_agent, data, chat_id, call_id)
    delivered = await _deliver_to_agent(state, broker_agent, frame)
    if not delivered:
        logger.info("Agent %s offline — %s von Gerät %s gespoolt",
                    broker_agent, frame_type, _mask_id(sender_device_id))


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


def _cap_chat_status(state: _RelayState) -> None:
    """W4-FIX-JAG2 (S6): chat_status hatte nur die ts-Retention
    (_prune_chat_status) — ein Flood frischer msg_ids wächst ungebunden
    binnen der 24 h (RAM-DoS, Schwesterfall von chat_activity/SEC-W1 D2).
    FIFO-Cap Parität; Aufruf NUR unter broker_lock."""
    while len(state.chat_status) > _CHAT_STATUS_MAX:
        state.chat_status.pop(next(iter(state.chat_status)))


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
        # W4-FIX-JAG2 (S6): FIFO-RAM-Schutz NACH dem Insert — hält die Map
        # hart ≤ _CHAT_STATUS_MAX (Cap vor dem Insert ließe einen Überschuss
        # je Aufruf zurück; Parität zu _cap_chat_activity: Cap nach dem Add).
        _cap_chat_status(state)
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
    # W16 (Fixliste 14): klare session_meta am Activity-Frame (Adapter
    # _send_activity) — Struktur-Gate + Pass-Through; App liest sie im
    # Activity-Handler.
    session_meta = data.get("session_meta")
    if session_meta is not None and not _session_meta_ok(session_meta):
        logger.warning("Agent %s: chat.activity mit ungültiger session_meta — Feld verworfen", agent_id)
        session_meta = None
    frame = {
        "type": "chat.activity",
        "v": 1,
        "chat_id": chat_id,
        "agent_id": agent_id,
        "working": working,
        "started_at": int(started_at),
        "ts": int(time.time() * 1000),
    }
    if session_meta is not None:
        frame["session_meta"] = session_meta
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
    """PH2-T10: chat.send/chat.send.enc/chat.media.enc → delivered-Metadaten
    liefern, sonst False.

    W4-B3: Media-Chunks tragen seit der pure-Medien-Lücke-Fix ebenfalls eine
    Broker-msg_id — der delivered-Aufstieg (Agent-WS-Send erfolgreich) erbt
    denselben Status-Verband wie Chat-Text (Chip-Kette queued → delivered
    → read)."""
    return (
        isinstance(message, dict)
        and message.get("type") in ("chat.send", "chat.send.enc", "chat.media.enc")
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
                            claim: Optional[tuple[str, str]] = None,
                            spool: bool = True) -> Optional[bool]:
    """B1: Nachricht an die Geräte-Slots — Session-ROUTING + Fan-out.

    Routing (Kap. 2.2/3: Session-Broker = Routing-Wahrheit):
    - Frame MIT session_id → Ziel = das Gerät der Session
      (crypto_sessions[session_id].device_id). Offline → NUR die EIGENE
      Outbox-Bucket dieses Geräts (ein Gerät bekommt nur seine eigene
      Outbox). Unbekannte/abgelaufene Session → konservativer Broadcast.
    - Frame OHNE session_id (Klar-Chat/Status/Presence) → Broadcast an JEDES
      bekannte Gerät (Registry ∪ Slots): live = send, offline = je Gerät
      EIGENE Outbox (spool) — oder bewusst verworfen (spool=False).

    SEC-W1 W1C-1: append + WS-Send + Pop laufen in EINER broker_lock-Sektion
    (Dubletten-Rennen Deliver × Flush, s. _deliver_to_agent).

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
        # ── B1-Routing: session_id → Ziel-Gerät, sonst Broadcast ────────────
        _prune_crypto_sessions(state)
        session_id = message.get("session_id")
        targets = None
        if isinstance(session_id, str) and session_id:
            session_entry = state.crypto_sessions.get(session_id)
            if isinstance(session_entry, dict) and session_entry.get("device_id"):
                targets = [str(session_entry["device_id"])]
        delivered = await _deliver_locked(state, message, spool=spool,
                                          targets=targets)
    return True if delivered else False


async def _deliver_locked(state: _RelayState, message: dict,
                          spool: bool = True,
                          targets: Optional[list[str]] = None) -> bool:
    """NACKT-Zustell-Kern (MUST NUR MIT gehaltenem broker_lock gerufen werden,
    asyncio.Lock ist nicht reentrant): Send an alle lebenden Ziel-Slots,
    Spool je Gerät in die EIGENE Bucket; keine Ziele → Fallback-Bucket (das
    alte Ein-Gerät-Verhalten, Spool-Verlust ausgeschlossen). True = an mind.
    einen Slot zugestellt."""
    if targets is None:
        targets = list(state.devices)
        for device_id in state.device_registry:
            if device_id not in targets:
                targets.append(device_id)
    delivered = False
    for device_key in targets:
        slot = state.devices.get(device_key)
        ws = slot.ws if slot is not None else None
        if ws is None or getattr(ws, "closed", True):
            if spool:
                bucket = _device_bucket(state, device_key)
                _prune_chat_queue(bucket, _chat_retention_seconds())
                bucket.append((time.time(), message))
            continue
        if spool:
            bucket = _device_bucket(state, device_key)
            _prune_chat_queue(bucket, _chat_retention_seconds())
            bucket.append((time.time(), message))
        try:
            await ws.send_json(message)
        except Exception as exc:
            logger.warning(
                "Chat-Zustellung an Gerät %s fehlgeschlagen: %s",
                _mask_id(device_key), exc)
            continue  # spool=True: Eintrag bleibt (Frame nicht verloren);
            # spool=False: NIE etwas angehängt (Live-Zustand kein Spool)
        if spool:
            for index, (_ts, queued) in enumerate(bucket):
                if queued is message:
                    del bucket[index]
                    break
        delivered = True
    if not targets and spool:
        # Noch KEIN bekanntes Gerät (frischer State vor erster Registrierung):
        # Fallback-Bucket = das alte Ein-Gerät-Verhalten (kein Spool-Verlust).
        _prune_chat_queue(state.fallback_outbox, _chat_retention_seconds())
        state.fallback_outbox.append((time.time(), message))
        spooled = True
    return delivered


async def _flush_phone_outbox(state: _RelayState,
                              device_id: Optional[str] = None) -> None:
    """Agent→App-Spool JE GERÄT ausliefern (FIFO) — ohne device_id wird der
    Fallback-Bucket (B1-Migration: bestehender Spool vor erstem Bekanntsein)
    abgearbeitet (Parität zum alten Einheits-Flush).

    SEC-W1 W1C-1: Flush läuft komplett unter broker_lock — Deliver und Flush
    können dasselbe Frame nicht mehr doppelt senden.
    """
    async with state.broker_lock:
        if device_id is not None:
            queue = state.device_outbox.get(device_id)
        else:
            queue = state.fallback_outbox
        if queue is None:
            return
        _prune_chat_queue(queue, _chat_retention_seconds())
        pending = list(queue)
        queue.clear()
        if not pending:
            return
        if device_id is not None:
            slot = state.devices.get(device_id)
            ws = slot.ws if slot is not None else None
        else:
            ws = state.phone_ws
        if ws is None or getattr(ws, "closed", True):
            # Replace-Rennen: kein Gerät — Spool bleibt vollständig erhalten.
            queue.extend(pending)
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
                queue.extendleft(reversed(rest))
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
    device_id = _normalize_registry_device_id(body.get("device_id"))
    now_ms = int(time.time() * 1000)
    entry = {
        "device_id": device_id,
        "ik_pub": ik_pub.strip().lower(),
        "spk_pub": spk_pub.strip().lower(),
        "epoch_of_enroll": int(state.pairing_epoch),
        "enrolled_at": now_ms,
    }
    # FIN-T1 RED-07 (B2 erweitert): Identity-Wechsel SICHTBAR — v2 mit
    # GERÄTEGEBUNDENER Map: ein Wechsel des SELBEN device_id (Keys anders) =
    # LOUD; ein NEUES device_id, sobald die Registry NICHT leer ist, = ebenso
    # LOUD (ein neues Gerät in der Key-Registry ist ein Sicht-Ereignis, Plan
    # Kap. 8: JEDE neue Geräte-Anmeldung ist sichtbar). Erstveröffentlichung
    # in eine LEERE Registry = still (Bestands-Parität). Key-Material bleibt
    # ungeloggt, Blockade bewusst NEIN.
    current_keys = _load_device_keys_map()
    existing = current_keys.get(device_id)
    if (existing is not None and (existing.get("device_id") != entry["device_id"]
                                  or existing.get("ik_pub") != entry["ik_pub"]
                                  or existing.get("spk_pub") != entry["spk_pub"])):
        logger.warning(
            "PH-3: Phone-Identity-WECHSEL (device_id=%s) — ik_pub/spk_pub neu "
            "veröffentlicht; FP-Abgleich am Agenten prüfen (MITM-Indikator)",
            _log_id(device_id))
    elif existing is None and current_keys:
        logger.warning(
            "PH-3: Phone-Identity-WECHSEL (device_id=%s) — NEUES Gerät in der "
            "Key-Registry (Multi-Gerät-Bestand %d) — prüfen, ob erwartet",
            _log_id(device_id), len(current_keys))
    current_keys[device_id] = entry
    try:
        _store_device_keys(current_keys, last_entry=entry)
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
    # ── B2: Device-Identität im Hello-Pfad ───────────────────────────────────
    # device_id kommt aus dem Body (bevorzugt, Parität /key_publish) ODER dem
    # WS-Header-Feld der Kopplung (X-Device-Id); LEGACY-Default = "phone".
    # NUR Durchreichung/Verband — keine Krypto-Änderung, keine neuen Primitives;
    # der Adapter je Gerät (spätere Welle) führt je (agent, Gerät) die eigne
    # E2E-Session. Eingebene Kennung mit schlechter Form = 400 (fail-closed
    # Input-Gate, Parität zur WS-_parse_device_id-Verwerf-Route).
    raw_device = str(body.get("device_id") or "").strip()
    if raw_device and not _DEVICE_ID_RE.fullmatch(raw_device):
        return web.json_response(
            {"error": "device_id must be an ASCII word (≤64)"}, status=400)
    if not raw_device:
        header_device = _parse_device_id(getattr(request, "headers", {}) or {})
        raw_device = header_device or ""
    device_id = _normalize_registry_device_id(raw_device)
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
            "device_id": device_id,  # B2: Session→Gerät (Routing-Wahrheit)
        }
    frame = {
        "type": "crypto.hello", "v": 1,
        "session_id": session_id,
        "agent_id": agent_id,
        "device_id": device_id,
        **pubs,
        "ts": int(time.time() * 1000),
    }
    # PH-4-T2: Handshake-Verband signiert (Instanz-Identität + Offline-
    # Nachweisbarkeit; Fail-soft ohne Identität — kein Feld, Legacy-Verband).
    _relay_sign_meta(frame)
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
    # Komposer Welle 12: optionale Composer-Felder (Klartext-Zweig) —
    # nur saubere kurze Tokens weiterreichen; enc-Zweig trägt sie im ct.
    _cm = str(body.get("model") or "").strip()
    _ce = str(body.get("effort") or "").strip()
    if _cm and re.match(r"^[A-Za-z0-9._:@/+-]{1,64}$", _cm):
        message["model"] = _cm
    if _ce and _ce in {"low", "medium", "high", "max"}:
        message["effort"] = _ce

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
    # W4-B3 (Read-Ack-P3-Fix): jede Chunk-POST erzeugt eine Broker-msg_id
    # (Parität /chat enc-Zweig) — das Phone lernt sie aus dieser Response
    # (App-Chip-Bindung), der Adapter führt sie im Deliver-Envelope nach, so
    # dass chat.read.enc 'Gelesen' endlich greift (pure-Medien-Lücke zu).
    media_id = body["media_id"]
    msg_id = str(uuid.uuid4())
    message = {
        "type": "chat.media.enc", "v": 1,
        "chat_id": chat_id,
        "msg_id": msg_id,
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

    # W4-B3: Broker-Annahme = queued (Parität /chat enc-Zweig Z. 3156) — der
    # Phone-Status-Chip bindet an msg_id: queued → delivered → read.
    await _set_chat_status(state, msg_id, chat_id, agent_id, "queued")
    delivered = await _deliver_to_agent(state, agent_id, message)
    return web.json_response(
        {
            "chat_id": chat_id,
            "msg_id": msg_id,
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

    body: dict = {}
    if request.method == "POST":
        try:
            body = await request.json()
            body = body if isinstance(body, dict) else {}
        except Exception:
            body = {}

    # ── Geräte-Auswahl (B1 Tool-Weg) ────────────────────────────────────────
    # Connectivity-Check = 'irgendein Gerät verbunden'; der Befehls-Hüllposten
    # bekommt optional ein Geräteziel (Kap. 8 #7): body device_id (POST),
    # Query-Param device_id oder Header X-Device-Id; Default = sticky das
    # zuletzt registrierte Gerät. Kein Gerät: 503 (alter Ton); WUNSCH-Gerät
    # nicht verbunden: 503 mit Gerät-Kontext (kein falsches Gerät).
    requested_device = str(
        (body.get("device_id") if isinstance(body, dict) else "")
        or request.query.get("device_id") or "").strip() \
        or _parse_device_id(request) or None
    if requested_device and not _DEVICE_ID_RE.fullmatch(requested_device):
        return web.json_response(
            {"error": "invalid device_id (ASCII-Wort ≤64)"}, status=400)
    devices_lock = state.devices_lock or state.phone_ws_lock
    if devices_lock is None:
        raise web.HTTPServiceUnavailable(
            text=json.dumps({"error": "server warming up"}),
            content_type="application/json",
        )
    async with devices_lock:
        if requested_device:
            slot = state.devices.get(requested_device)
            if slot is None or slot.ws is None or slot.ws.closed:
                logger.warning(
                    "Tool-Weg: Wunsch-Gerät %s nicht verbunden (path=%s)",
                    _mask_id(requested_device), path)
                return web.json_response(
                    {"error": (f"Requested device {_mask_id(requested_device)} "
                               "is not connected."),
                     "device_id": _mask_id(requested_device)},
                    status=503)
            ws = slot.ws
            device_for_cmd = requested_device
        else:
            slot = state.devices.get(state.last_active_device or "")
            if slot is None or slot.ws is None or slot.ws.closed:
                for candidate in state.devices.values():
                    if candidate.ws is not None and not candidate.ws.closed:
                        slot = candidate
                        break
                    slot = None
            if slot is None or slot.ws is None or slot.ws.closed:
                return web.json_response(
                    {
                        "error": "No phone connected. Open the Hermes app on your phone and connect."
                    },
                    status=503,
                )
            ws = slot.ws
            device_for_cmd = slot.device_id

    # Build the command envelope
    request_id = str(uuid.uuid4())
    method = request.method  # GET or POST
    params = dict(request.query)

    command = {
        "request_id": request_id,
        "method": method,
        "path": path,
        "params": params,
        "body": body,
    }
    _log_command_debug(method, path, device_for_cmd, body)

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


# ── PH-4 Code-Verdunkelung: --version-Banner NUR auf explizites Flag ─────────
# Die Versionskennung fließt bewusst NICHT spontan in Logs/HTTP-Antworten
# (keine Versions-Disclosure im Journal/Fehlerpfad) — abrufbar einzig via
# `python tools/android_relay.py --version`. Das Modul ist Bibliothek/Daemon-
# Baustein: bare Ausführung startet KEINEN Relay (start_relay lebt im Daemon).
def _resolve_relay_version() -> str:
    """Versionskennung aus den Paket-Metadaten ('hermes-android'); ohne
    installiertes Paket ehrlich 'dev' — kein erfundener Fixwert."""
    try:
        from importlib.metadata import version as _pkg_version

        return _pkg_version("hermes-android")
    except Exception:
        return "dev"


if __name__ == "__main__":  # pragma: no cover — Flag-Route, kein Relay-Start
    import argparse

    _parser = argparse.ArgumentParser(
        prog="android_relay",
        description="FlipsiBridge phone relay — Bibliothek/Daemon-Baustein.")
    _parser.add_argument(
        "--version", "-V", action="version",
        version=f"android_relay {_resolve_relay_version()}")
    _parser.parse_args()
