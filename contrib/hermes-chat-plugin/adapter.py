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
import tempfile
import threading
import time
import uuid
import weakref
from datetime import datetime
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
     "chat.media.enc",
     # B4 (Multi-Gerät): Backfill-Router-Frame des Relays (ids-only, Klar-
     # Steuer) + Revoke-Verband (Session/Pin-Kappung je Agent).
     "chat.backfill_request", "device.revoked",
     # PH-4-T2: Relay-Instanz-Identität je Connect (signed; TOFU-Pin-Verband
     # unten — Chat-Text bleibt Daten, kein Tool-Call-Pfad).
     "agent.ident",
     # W21 (Anruf P1, roadmap-anruf.md §3.4): Call-Signaling Rückkanal je
     # Geräte-Session (E2E enc) — KEIN Gateway-Push, nur Call-Zustandsmaschine.
     "call_ringing.enc", "call_accepted.enc", "call_declined.enc",
     "call_missed.enc", "call_ended.enc"}
)
_PROTOCOL_VERSION = 1


def _relay_canonical_bytes(meta: dict) -> bytes:
    """PH-4-T2: kanonische Bytes der Relay-Framesignatur — sortierte Keys, keine
    Spaces, ensure_ascii=False. PARITÄT tools/android_relay.py
    (_canonical_meta_bytes); Änderungen an einer Seite brechen die Verifikation
    (bewusst: die Formel ist Verbandsverankert, nicht frei wählbar)."""
    return json.dumps(meta, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def _relay_verify_sig(bare: dict, verify_key_hex: str, sig_b64: str) -> bool:
    """Ed25519-Verifikation eines Relay-Metadaten-Envelopes (fail-soft-Rückgabe
    False bei jeder Unstimmigkeit — nie fatal). verify_key = 64-hex, sig =
    base64(std) über _relay_canonical_bytes(bare)."""
    if not isinstance(verify_key_hex, str) or len(verify_key_hex) != 64 \
            or not isinstance(sig_b64, str) or not sig_b64:
        return False
    try:
        from cryptography.hazmat.primitives.asymmetric.ed25519 import (
            Ed25519PublicKey,
        )
        Ed25519PublicKey.from_public_bytes(
            bytes.fromhex(verify_key_hex)).verify(
            base64.b64decode(sig_b64, validate=True),
            _relay_canonical_bytes(bare))
        return True
    except Exception:
        return False

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
# W21 (G12, roadmap-anruf.md §7.2): "screengrab" = Agent-Screenshot aus dem
# Bedienungshilfen-Pfad — Bild lebt beim Agent NUR für die Vision-Analyse;
# KEIN Galerie-/MediaStore-Weg am Phone (App-Lane), Meta-Lauf hier identisch.
_MEDIA_KINDS = frozenset({"image", "video", "file", "voice", "screengrab"})

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

# Komposer Welle 12: Whitelist für die /model-Übersetzung (Injection-Schutz)
_COMPOSER_MODEL_RE = re.compile(r"^[A-Za-z0-9._:@/+-]{1,64}$")
_COMPOSER_EFFORTS = frozenset({"low", "medium", "high", "max"})
_HELP_TEXT = (
    "Befehle:\n"
    "/new — Agenten-Unterhaltung zurücksetzen (mit Bestätigung)\n"
    "/status — Status: E2E-Sitzung, Verbindung, Verlauf-Kurzbasis\n"
    "/help — diese Befehlsliste\n"
    "Hinweis: Befehle steuert der Adapter, nicht die App."
)

# ── W18 (Sir, 17.11.2026): agent.models-Broadcast (Fixliste 35) ───────────────
# Das Modell & Denken-Sheet der App braucht die WÄHLISTE vom Server, sonst
# bleibt nur „Auto". Der Adapter sammelt den Katalog + aktuelles Modell/
# Denk-Stufe aus der Agenten-Konfiguration und broadcastet sie:
#   - JE Connect (ohne Session: Klartext 'agent.models' Late-Joiner-Sofort),
#   - JE bestätigtem E2E-Handshake (hello_ack-Verband, PFLICHT enc),
#   - JE 60 s Refresh-Timer (spät verbindende Geräte kriegen den Katalog
#     nachgeschoben, statt ihn erst bei der ersten Nachricht zu sehen).
# Verband: chat.session_meta (Fixliste 14) — enc-Spiegel bei aktiver Session,
# Klartext nur, wenn KEINE Session aktiv (der App-Downgrade-Guard verwirft
# Klartext bei aktiver Session). Der Relay ist opaker Pass-Through mit den
# Typen 'agent.models'/'agent.models.enc' (Fixliste 35, W16-Verband); Struktur-
# Gate-Parität zum Relay-_agent_models_ok + App-MAX_AGENT_MODELS.
AGENT_MODELS_FRAME = "agent.models"
AGENT_MODELS_ENC_FRAME = "agent.models.enc"
AGENT_MODELS_LIST_MAX = 24    # Relay _agent_models_ok / App MAX_AGENT_MODELS
AGENT_MODELS_ENTRY_MAX = 64   # je Modell-ID ≤ 64 (Parität _agent_models_ok)
AGENT_MODELS_REFRESH_S = 60   # Refresh-Timer für Late-Joiner (Fixliste 35)
# Default-Katalog (Provider ollama-cloud): sir-festgelegte sinnvolle Sammlung.
# Override: FLIPSI_MODELS (Komma-Liste); fehlt die Zerlegung → bleibt dieser Katalog.
AGENT_MODELS_DEFAULT_CATALOG: tuple[str, ...] = (
    "glm-5.3", "glm-5.3-flash", "deepseek-v4-pro", "gpt-oss:120b",
    "mistral-large-4", "minimax-m3", "kimi-k3:cloud",
)

# ── W19 (Sir, 17.11.2026): agent.status-Broadcast (Hook-Verband) ─────────────
# Live-Arbeitszustand je Agent auf der bestehenden Agent-WS: der Dashboard-
# Pill/Slot-Verband der App (AgentStatusRules.kt, Relay-Vertrag bff3e63) liest
#   {agent_id, working, started_at, label?(≤64), sub?{id≤64,name≤64}, end?}.
# Adapterseitig wird der Zustand von Hermes-Lifecycle-Hooks gespeist:
#   pre_gateway_dispatch (Policy-Hook → IMMER None!)     → parent ON (Text-Event)
#   pre_tool_call (FAIL-CLOSED ≤ ~2 ms reine Dicts!)     → parent ON/Label-Refresh
#   post_tool_call                                       → web-Verband-Zähler,
#                             'delegate_task' wird NIE als Ende gewertet
#   subagent_start / subagent_stop                       → Sub-Slot OPEN/CLOSE
#                             (Join-Schlüssel = child_session_id)
#   on_session_end (feurt je TURN, mehrfach!)            → platform 'subagent' =
#                             NUR Herzschlag-Refresh (kein Close); eigene
#                             Session → parent OFF.
# Frames laufen via run_coroutine_threadsafe auf den Adapter-WS-Loop (Hook-
# Callbacks auf Hermes-Threads), Coalesce-Cap: max 1 offener Send-Drain je
# Adapter. enc-first: aktive E2E-Session → 'agent.status.enc' (Klar-Spiegel
# working/started_at/label/sub/end + opaker ct, chat.activity-Parität), ohne
# Session Klartext (Relay-Downgrade-Schutz verwirft den sonst).
AGENT_STATUS_FRAME = "agent.status"
AGENT_STATUS_ENC_FRAME = "agent.status.enc"
_STATUS_PLATFORM_ID = "flipsibridge"   # Plattform-ID dieses Adapters (Whitelist-Kopf)
# W19-Verband (17.11., Sir-Befund: Pill zeigte Discord/Telegram-Turns nie): Agentarbeit ist
# plattformübergreifend sichtbar — Events dieser Plattformen zählen als eigener Agent-Status.
_STATUS_PLATFORM_WHITELIST = frozenset({_STATUS_PLATFORM_ID, "discord", "telegram"})
_STATUS_LABEL_CAP = 64                 # Relay-Gate-Parität: label/sub je str ≤ 64
_STATUS_SUB_SLOTS_CAP = 16             # FIFO-RAM-Schutz der Sub-Slot-Map
_STATUS_SEND_TIMEOUT_S = 0.5           # Status-Send-Gate (fail-soft, nie Chat-kritisch)
_STATUS_WEB_TOOLS = frozenset({"web_search", "web_extract", "browser_exec"})
_STATUS_WEB_LABEL = "Sucht im Web"     # web-Verband-Label (fester Vertrag an die App)

# ── F1 (Sir, 18.11.2026, Vollgas-Batch): cron.status-Live-Verband ────────────
# Geplante/laufende Cron-Jobs des GATEWAYS auf der bestehenden Agent-WS —
# APP-GENERISCH (der App ist es egal, welcher Agent schickt): Frame lebt je
# AGENT (chat_id "agent-global", Parität agent.status), Payload nur Bekanntes:
#   {jobs: [{name ≤64, status: running|planned|paused, next_at?: unix-ms}]}
# Quelle: SSOT <hermes_home>/cron/jobs.json (read-only, TTL-Cache — der Gateway-
# change_watcher feuert auf genau dieser Datei), Override FLIPSI_CRON_JOBS_FILE
# (Tests/isolierte Profile). Mechanik = agent.status identisch: enc-first
# (E2E-Session → NUR 'cron.status.enc', Klartext wirft der App-Downgrade-Guard
# weg), Send-Gate je Frame; Sends je Connect + je bestätigtem Handshake + je
# Reconnect + Refresh-Loop (Change-Detect, späte Geräte kriegen den Stand).
# Relay-Whitelist-Verband: die Frame-Typen müssen in der Agent-Whitelist
# (tools/android_relay.py _AGENT_FRAME_TYPES) erlaubt sein — BEFUND notiert.
CRON_STATUS_FRAME = "cron.status"
CRON_STATUS_ENC_FRAME = "cron.status.enc"
_CRON_STATUS_JOBS_MAX = 16             # Liste gekappt (RAM-/Frame-Schutz, ehrlich)
_CRON_STATUS_NAME_CAP = 64             # Parität Label-Cap (Relay-Gate-Welt)
_CRON_STATUS_SEND_TIMEOUT_S = 0.5      # Status-Send-Gate (fail-soft, Parität)
_CRON_STATES_ORDER = {"running": 0, "planned": 1, "paused": 2}
_CRON_REFRESH_S = 60.0                 # Refresh/Change-Poll (env FLIPSI_CRON_REFRESH_S)
_CRON_STORE_TTL_S = 15.0               # Store-Lese-Cache (read-only, kein Lock-I/O)

# ── F3 (Sir, 18.11.2026, offener Verband seit W19): chat.session_meta ────────
# push_session_meta aus dem hermes-android-plugin (P40-Verband) in den Live-
# Adapter nachziehen: Entry-Point der Gateway-Side-Hooks (flipsi-session-meta —
# beobachtet agent:start/step/end + async_delegations, pusht Session-Quelle +
# aktives Modell + laufende Sub-Agenten). Seit W19 liefen diese Pushes INS LEERE
# (Hook skippte bei is_connected=False; Adapter hatte die Methode nicht — jeder
# Aufruf = Exception → Log-Zeile). FIX hier: Queue statt Skip — Nicht-verbundene
# Pushes werden je chat_id REPLACE-geparkt und nach dem Reconnect nachgeliefert.
# enc-first (Parität Fixliste 14): mit E2E-Session NUR 'chat.session_meta.enc'
# (Klartext wirft der App-Downgrade-Guard weg), ohne Session klartext. Meta =
# REPLACE (was None ist, fehlt am Phone → Zeile weg). Persistierte Chat-Ziele
# (P40): nach Gateway-Restart ist _received_at leer — die Persistenz hält die
# bekannten Slots wach (fail-soft, Cap 32).
SESSION_META_FRAME = "chat.session_meta"
SESSION_META_ENC_FRAME = "chat.session_meta.enc"
_SESSION_META_MAX_SUB_AGENTS = 8       # Parität P40 (Hook _SUBS_MAX = 8)
_SESSION_META_STR_CAP = 64             # Anzeige-Felder gekappt (Protokoll-Parität)
_SESSION_META_QUEUE_CAP = 16           # FIFO-RAM-Schutz der Nachliefer-Queue
_SESSION_META_SEND_RETRY_MAX = 3       # fehlgeschlagene Drains → verwerfen
_META_STATE_DIR_ENV = "FLIPSI_META_STATE_DIR"  # Override (Tests) — Default: plugin/state

# ── B3 (Multi-Gerät, Plan roadmap-multigeraete.md Kap. 1/2/3/4/5): ────────────
# Session-Map je Gerät (je Geräte-ik eine eigene X3DH/Ratchet-Session), Pin-Map
# je ik_pub (TOFU-TOFU je Gerät), Antwort-Fan-out je Geräte-Session,
# chat.sync.enc-Echo an NEBENgeräte (Loop-sicher), chat.backfill.enc aus dem
# Adapter-Verlaufs-Spool (K je Agent, Deckel), alles E2E je Geräte-Session.
# KEINE neuen Krypto-Primitives: die bestehenden X3DH/Ratchet-Bausteine werden
# je (Gerät × Agent) instanziert (Plan Kap. 3.1, Empfehlung (I)).
B3_SYNC_ENC_FRAME = "chat.sync.enc"          # Echo des NUTZER-Texts an NEBENgeräte
B3_BACKFILL_ENC_FRAME = "chat.backfill.enc"  # Gateway-Verlauf als E2E an das Anfragt-Gerät
B3_BACKFILL_REQUEST_FRAME = "chat.backfill_request"  # Klar-Steuer-Frame (ids-only)
B3_HISTORY_K = 200            # K je Agent-Chat (Plan Kap. 4.1: K = 200, env-tunbar)
B3_BACKFILL_MAX = 200         # Backfill-K je Anfrage (Deckel, Parität zum Plan-K)
B3_BACKFILL_RATE_S = 30.0     # je Session max 1 Backfill-Request je 30 s (DoS-Schild)
B3_DEVICE_PIN_CAP = 8         # Pin-Map je Agent (Plan Kap. 3.1: Cap 8 Pins/Agent)
B3_SESSIONS_CAP = 8           # Session-Map je Agent (DoS-Schild; Parität Pin-Cap)
B3_SYNC_ECHO_DEDUP = 256      # FIFO-Dedupe je Echo (kein Doppel-/Re-Echo je msg_id)
B3_ECHO_FIELD = "sync"        # eindeutiges Echo-Feld im ct-Payload ("fremd-eigenes")
B3_SYNC_OF_FIELD = "sync_of"  # msg_id-Bezug: die Echo-Blase trägt die Ursprungs-msg_id
B3_BACKFILL_BATCH_MAX = 64    # Entries je backfill.enc-Frame (ct-Cap-Schutz)
B3_BACKFILL_BYTES_MAX = 96 * 1024  # Raw-Payload-Deckel je Backfill-Frame (< ct-Cap)
B3_BACKFILL_ENTRY_TEXT_CAP = 24_000  # je Entry hart (B batches bleiben < Bytes-Max)
B3_STATUS_SNAPSHOT_FRAME = "chat.status_snapshot"  # ids-only (App-Parser-Form)
B3_STATUS_SNAPSHOT_CAP = 500  # Parität App ChatBackfillRules.STATUS_SNAPSHOT_CAP
B3_STATUS_KNOWN = frozenset({"queued", "delivered", "read", "failed"})
B3_DEVICE_REVOKED_FRAME = "device.revoked"  # Revoke-Verband (Relay→Adapter)

# ── W21 (Sir-Schiene „Anruf", roadmap-anruf.md §1/§3.3/§3.4/§3.5): Call-Signaling ─
# Adapter-Seite des kleinen Signaling-Satzes: der Agent INITIIERT (Tool
# flipsi_call_user, Capability „calls" — Freigabe-Gate Parität android_call),
# das Phone quittiert (ringing/accepted/declined/missed/ended.enc je Geräte-
# Session enc, Relay routet session-gebunden). ALLE Inhalte (kind/reason/say/
# TTS-Chunks) liegen IM Ratchet-ct — Klar bleiben nur IDs. KEIN WebRTC, KEIN
# Klartext-Audio (§3.1): TTS geschieht AM PHONE — der Adapter markiert Sätze
# im Call-Modus mit call_chunk_seq (§3.3), die App spricht sie (ChatSpeech-
# Speaker-Pfad). Verpasste/abgewiesene Anrufe fallen auf Normal-Chat zurück
# (§3.5 — nichts geht verloren).
CALL_INVITE_FRAME = "call_invite.enc"     # Agent→Phone: klingeln (§3.4)
CALL_END_FRAME = "call_end.enc"           # Agent→Phone: auflegen (Terminierung)
CALL_SIGNAL_FRAMES = frozenset({          # Phone→Agent: Rückkanal je Session
    "call_ringing.enc", "call_accepted.enc", "call_declined.enc",
    "call_missed.enc", "call_ended.enc"})
_CALL_ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
_CALL_KIND = "voice"                      # v1: Sprachanruf (§3.4)
_CALL_REASON_MAX_CHARS = 600              # Klingel-Grund (Vollbild-Text)
_CALL_SAY_MAX_CHARS = 8000                # Durchsage-Cap (Parität Chat-Text-Schiene)
_CALL_CHUNK_MAX_CHARS = 600               # TTS-Chunks satzweise (§3.3: früh raus)
_CALL_CHUNKS_MAX = 32                     # harter Chunk-Deckel je Durchsage
_CALLS_CAP = 16                           # FIFO-RAM-Schutz der Call-Map
_CALL_STATE_TTL_S = 600                   # Terminal-Einträge altern (Spätsignale)
_CALL_RING_TIMEOUT_MIN_S = 5.0
_CALL_RING_TIMEOUT_MAX_S = 120.0
_CALL_RING_TIMEOUT_S_DEFAULT = 45.0       # env FLIPSI_CALL_RING_TIMEOUT
_CALL_AUTO_END_DEFAULT = True             # env FLIPSI_CALL_AUTO_END („0" = aus)
_CALL_TOOLS = ("flipsi_call_user", "flipsi_call_end")

_SYNC_ENC_FRAME = B3_SYNC_ENC_FRAME
# Live-Adapter-Registry: Modul-Level-Hooks fannen je Callback an alle
# verbundenen Adapter-Instanzen (WeakSet → kein Leak bei Plugin-Reload).
STATUS_ADAPTERS: "weakref.WeakSet" = weakref.WeakSet()


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
        # W21 (G12): Agent-Screenshots werden als solche angezeigt.
        "screengrab": "Screenshot",
    }.get(kind, "Datei")


def _format_media_bytes(size: int) -> str:
    """Byte-Größe als deutsche Anzeige (KB/MB, eine Nachkommastelle)."""
    if size >= 1024 * 1024:
        return f"{size / (1024 * 1024):.1f} MB"
    return f"{max(1, size // 1024)} KB"


def _parse_iso_to_ms(value) -> Optional[int]:
    """F1: ISO-8601-Zeitstempel → Unix-ms (Cron-Store next_run_at, aware bevor-
    zugt). Naive Werte werden ehrlich als LOKALE Zeit gewertet (der Cron-Store
    des Gateways schreibt tz-aware Stamps). Unbrauchbar → None (Feld fällt
    beim Frame-Bau — kein erfundener Zeitpunkt)."""
    text = str(value or "").strip()
    if not text:
        return None
    try:
        dt = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.astimezone()
        return int(dt.timestamp() * 1000)
    except (ValueError, TypeError, OverflowError, OSError):
        return None


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
    der Relay benennt chat.reply.enc → chat.event.enc um).

    W4-FIX-JAG2 (S3): msg_id bleibt bewusst AUCH außerhalb des AAD —
    dokumentierte Acceptance, kein Fix:
      * Kompat: jede AAD-Änderung bricht die laufenden Alt-Adapter/APK-
        Sessions (AAD steckt in JE enc-Frame; keine Übergangsvariante ohne
        Doppel-Decode-Pfad). Frame-Typen sind in dieser Welle unveränderlich.
      * Realitätsgrad: der Kanal ist TLS-gebunden (eigene Relay-URL, Pin-
        Verband) — msg_id-Substitution erfordert eine Relay-Kompromittierung,
        gegen die AAD ohnehin nichts schützt (der Relay öffnet nie ct).
      * Rolle: msg_id ist Ack-/Dedupe-Buchhaltung (chat.read-Ziel, Status-
        Schlüssel), kein Integritätsträger — der Inhalts-Schutz trägt ct+
        AAD; ein gefälschtes msg_id ohne passenden ts/Status-Wegwerfen
        verschiebt nur, WO ein legitimer Frame ankommt, nicht WAS drin steht.
    Falls eine künftige Welle Bindung nachholt: AAD-Extension-Feld + Version-
    Sprung des enc-Formats, NIE still auf denselben Verband umschalten.
    """
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

    # ── B3: Geräte-Verband je Session (Routing-Metadaten, ids-only) —
    # device_key = Registry-Anker (device_id des Relays oder "ik:<pub>"),
    # chat_id = letzter gesehener Wire-Chat_id dieses Geräts (Fan-out-Kontext).
    device_key: str = ""
    device_id: str = ""
    device_ik: str = ""
    chat_id: Optional[str] = None

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
        # ── B3 Session-MAP (Plan Kap. 3.1 Option (I)): je GERÄT eine eigene
        # X3DH/Ratchet-Session (key = device_id mit Relay-Registry-Wahrheit,
        # Fallback ik_pub bei Legacy-Hellos). `self._crypto_session` bleibt als
        # Primär-Referenz (freshestes Hello) für Alt-Pfade bestehen — Antwort-/
        # Broadcast-Pfade fächern über _device_sessions() auf.
        self._crypto_sessions: dict[str, AgentCryptoSession] = {}
        # B3 Sync-Echo-Dedupe je Ursprungs-msg_id (kein Doppel-/Re-Echo; FIFO).
        self._sync_echo_dedup: dict[str, float] = {}
        # B3 Backfill-Rate je Geräte-Session (1 Request / 30 s, DoS-Schild).
        self._backfill_last: dict[str, float] = {}
        # B3 Backfill-Verlaufs-Quelle: injizierbar (Tests; Prod-Wiring → B6).
        # Signatur: (gw_chat_id) → [ {text, from_agent, msg_id?, time?, status?} ].
        # None = ehrliche Antwort mit truncated=True, OHNE Verlauf zu behaupten.
        self._backfill_history_fn: Optional[Callable[[str], list]] = None
        # B3 K je Agent-Chat (Plan Kap. 4.1: K = 200, env-tunbar, Deckel 1000).
        try:
            self.backfill_k: int = max(
                1, min(1000, int(_cfg(extra, "FLIPSI_BACKFILL_K",
                                      default=str(B3_HISTORY_K)) or B3_HISTORY_K)))
        except (ValueError, TypeError):
            self.backfill_k = B3_HISTORY_K
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
        # Komposer Welle 12: Pro-Chat-Modell/Denken-Auswahl aus der App —
        # Stash (Klartext-Metadaten, nie geloggt) + Applied-Tracking.
        self._composer_meta: dict[str, dict] = {}
        self._applied_composer: dict[str, tuple] = {}

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

        # --- W18 (Sir): agent.models-Broadcast (Fixliste 35) ---
        # Katalog + current/effort je Connect, je bestätigtem Handshake und
        # alle 60 s Refresh. Fail-soft: der Broadcast bricht nie den Chat.
        # Override-Kette je Wert: Env/extra → Hermes-Config (delegation.*) →
        # Default-Katalog-Verband.
        _models_env = _cfg(extra, "FLIPSI_MODELS")
        self._models_override: Optional[list[str]] = (
            [m.strip() for m in _models_env.split(",") if m.strip()]
            if _models_env else None)
        self._current_model_override: str = _cfg(extra, "FLIPSI_CURRENT_MODEL")
        self._current_effort_override: str = _cfg(extra, "FLIPSI_REASONING_EFFORT")
        self.hermes_config_path: str = _cfg(extra, "FLIPSI_HERMES_CONFIG") or os.path.join(
            os.path.expanduser("~"), ".hermes", "config.yaml")
        try:
            self.models_refresh_s: int = max(
                1, int(_cfg(extra, "FLIPSI_MODELS_REFRESH_S",
                            default=str(AGENT_MODELS_REFRESH_S))
                       or AGENT_MODELS_REFRESH_S))
        except ValueError:
            self.models_refresh_s = AGENT_MODELS_REFRESH_S

        # --- W19 (Sir): agent.status-Zustandsmaschine (Hook-Verband) ---
        # parent_working = Agent-Level-(ON beim FlipsiBridge-Text-Event bzw.
        # Scope-Treffer im pre_tool_call / OFF beim on_session_end der EIGENEN
        # plattform-Session); sub_slots = offene Subagent-Slots je
        # child_session_id (OPEN/CLOSE per subagent_start/stop). parent_sessions
        # = gelernte Gateway-Session-IDs (aus on_session_end mit EIGENER
        # Plattform) — der Scope-Schlüssel für pre_tool_call/Subagent-Hooks,
        # damit fremde Plattformen (Telegram/Discord/...) den Status nicht
        # falten. Coalesce-Cap: max 1 offener Send-Drain; der Drain sendet
        # gesammelte Frames nacheinander (dedupgleiche parent-Frames bleiben
        # weg). Sämtliche Hook-Callbacks arbeiten reine in-memory (pre_tool_call
        # ist FAIL-CLOSED — Timeout blockiert den Tool-Call!).
        self._status_lock = threading.Lock()
        self._status: dict = {
            "loop": None,             # Adapter-WS-Event-Loop (setzt connect())
            "working": False,         # parent_working
            "started_at": None,       # Unix-ms des letzten ON (Herzschlag-refreshed)
            "label": None,            # logischer Top-Level-Label (≤64 oder None)
            "web_count": 0,           # web-Verband-Zähler (Suche-Offen-Verband)
            "sub_slots": {},          # child_session_id → {id, name, started_at}
            "recent_closed": {},      # zuletzt geschlossene child_session_id → ts
                                      # (Doppel-Stop-Band, FIFO-Cap 64)
            "pending": None,          # Coalesce-Cap: deque oder None
            "fut": None,              # letzte Drain-Future (concurrent.futures.Future)
            "parent_sessions": set(), # gelernte eigene Session-IDs (Scope)
        }
        # W19: Live-Registry für die Modul-Level-Hook-Fächer (WeakSet → kein
        # Leak; Filtersatz "_connected" schließt nie verbundene Instanzen aus).
        STATUS_ADAPTERS.add(self)
        self._models_task: Optional[asyncio.Task] = None  # W18: Refresh-Timer

        # --- F1 (Sir): cron.status-Live-Verband (Gateway-Cron-Jobs → App) ---
        self._cron_store_override: Optional[str] = _cfg(extra, "FLIPSI_CRON_JOBS_FILE")
        try:
            self.cron_refresh_s: float = max(
                0.05, float(_cfg(extra, "FLIPSI_CRON_REFRESH_S",
                                 default=str(_CRON_REFRESH_S)) or _CRON_REFRESH_S))
        except ValueError:
            self.cron_refresh_s = float(_CRON_REFRESH_S)
        self._cron_cache: tuple = (0.0, [])   # (monotonic-Expiry, raw-job-Liste)
        self._cron_task: Optional[asyncio.Task] = None
        self._cron_sent_fp: str = ""          # Dedup-Band: zuletzt REAL gesendet

        # --- F3 (Sir): chat.session_meta-Nachliefer-Verband (P40 nachziehen) --
        # Queue je chat_id → (meta, attempts): nicht verbundene bzw. fehlgesch-
        # lagene Pushes werden gebunden und nach dem Reconnect nachgeliefert
        # (statt Skip/Verlust — der offene Verband seit W19).
        self._meta_lock = threading.Lock()
        self._meta_queue: dict[str, tuple[dict, int]] = {}
        self._meta_fut: Optional[Any] = None  # concurrent.futures.Future des Drains
        self._meta_state_dir: Optional[str] = _cfg(extra, _META_STATE_DIR_ENV)

        # --- PH-4-T2: Relay-Instanz-Identität (Ed25519, signed) ---------------
        # Fail-soft-Verband: agent.ident wird verifiziert + TOFU-gepinnt, die
        # Frame-Signatur (crypto.hello) fail-soft geprüft (Warn-Log + Zähler,
        # "NIEMAL" heißt: keine Operationsbrüche v1 — harte Scharfe ist ein
        # W23-Kandidat (KDoc am Handler). Zähler = Diagnose-Band.
        self._relay_identity_stats: dict[str, int] = {
            "ident_ok": 0, "ident_invalid": 0, "ident_pin_conflict": 0,
            "sig_ok": 0, "sig_bad": 0, "sig_missing": 0,
        }
        self._relay_verify_key: Optional[bytes] = None   # Ed25519-Pub am Pin
        self._relay_pin_path_override: Optional[str] = _cfg(
            extra, "FLIPSI_RELAY_IDENTITY_PINS")

        # ── W21 (Anruf P2, roadmap-anruf.md §3.5): Call-Zustandsmaschine + ────
        # generisches Bridge-Tool. Ring-Fenster + Auto-End je Agent konfigurier-
        # bar (env FLIPSI_CALL_RING_TIMEOUT / FLIPSI_CALL_AUTO_END); die Call-
        # Map (call_id → Zustand + Ring-Future + TTS-Zähler) lebt im Prozess,
        # Cap/TTL räumt _call_register (FIFO, kein ungebundenes Wachstum).
        try:
            self.call_ring_timeout_s: float = max(
                _CALL_RING_TIMEOUT_MIN_S,
                min(_CALL_RING_TIMEOUT_MAX_S,
                    float(_cfg(extra, "FLIPSI_CALL_RING_TIMEOUT",
                               default=str(_CALL_RING_TIMEOUT_S_DEFAULT))
                        or _CALL_RING_TIMEOUT_S_DEFAULT)))
        except (TypeError, ValueError):
            self.call_ring_timeout_s = _CALL_RING_TIMEOUT_S_DEFAULT
        self.call_auto_end: bool = (_cfg(extra, "FLIPSI_CALL_AUTO_END",
                                         default="1") != "0")
        # call_id → {call_id, state, fut, say, wire_chat_id, gw_chat_id,
        #            bound_session, started, tts_seq, was_accepted}
        self._calls: dict[str, dict] = {}
        self._calls_lock = threading.Lock()

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
        # W19: WS-Loop für die Status-Hook-Callbacks merken (run_coroutine_threadsafe
        # von Hermes-Threads). Wiederverbinden ersetzt Loop + wirft ein ggf. vom
        # toten Loop hängendes Coalesce-Offen weg.
        self._status["fut"] = None
        self._status["pending"] = None
        self._status["loop"] = asyncio.get_running_loop()
        logger.info("FlipsiBridge-Adapter verbunden (agent_id=%s)", self.agent_id)
        # W18 (Fixliste 35): Refresh-Timer für den agent.models-Broadcast
        # (Late-Joiner) + SOFORT-Broadcast je Connect — ohne Session als
        # Klartext, mit Session nur enc (App-Downgrade-Guard würde Klartext
        # verwerfen). Fail-soft: kein Connect-Erfolg hängt am Broadcast.
        self._models_task = asyncio.get_running_loop().create_task(
            self._models_refresh_loop())
        try:
            await self.emit_models()
        except Exception:
            logger.warning("FlipsiBridge: agent.models-Connect-Send fehlgeschlagen",
                           exc_info=True)
        # F1: cron.status je Connect (Late-Joiner-Sofort, wie emit_models) +
        # Refresh-Loop (Change-Detect) starten. Fail-soft — kein Connect-Erfolg
        # hängt am Broadcast.
        try:
            await self.emit_cron_status()
        except Exception:
            logger.warning("FlipsiBridge: cron.status-Connect-Send fehlgeschlagen",
                           exc_info=True)
        self._cron_task = asyncio.get_running_loop().create_task(
            self._cron_refresh_loop())
        # F3: Nachliefer-Queue abarbeiten — Pushes aus der getrennten Phase
        # (Hook-Verband) werden hier geholt, statt zu verfallen.
        self._schedule_meta_drain()
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
        # W19: Status-Send-Verband vom (sterbenden) Loop abkoppeln — ein neuer
        # connect() setzt Loop + Coalesce-Stand neu.
        self._status["fut"] = None
        self._status["pending"] = None
        self._status["loop"] = None
        if self._models_task is not None:
            self._models_task.cancel()
            try:
                await self._models_task
            except (asyncio.CancelledError, Exception):
                pass
            self._models_task = None
        if self._cron_task is not None:
            self._cron_task.cancel()
            try:
                await self._cron_task
            except (asyncio.CancelledError, Exception):
                pass
            self._cron_task = None
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
        if is_home_notice:
            # W18 (17.11.26, Sir): der Gateway-Home-Kanal-Hinweis (= die
            # '/sethome'-Aufforderung) darf NIE im App-Chat landen — der
            # 24-h-Fenster-Verband (FIX-WELLE-2 B3) ließ je Fenster einen
            # Hinweis durch, den Sir als Chat-Verschmutzung meldet. Neu:
            # IMMER still wegwerfen (success=True: informativ, kein Fehler).
            logger.info("FlipsiBridge: Home-Kanal-Hinweis unterdrückt (W18: nie zustellen)")
            return SendResult(success=True)
        msg_id = uuid.uuid4().hex[:16]
        received_ms = self._received_at.get(wire_chat_id)
        started_at = received_ms if received_ms is not None else int(time.time() * 1000)
        await self._send_activity(wire_chat_id, working=True, started_at=started_at)
        # PH-3: aktive E2E-Sessions → chat.reply.enc je GERÄT (B3-FAN-OUT:
        # je Geräte-Session eigene Chiffre; Text ist NUR im AES-GCM-ct).
        payload = {"text": content, "msg_id": msg_id}
        if reply_to:
            payload["reply_to"] = reply_to
        # W21 (Anruf P2, roadmap-anruf.md §3.3): Call-Modus-Marker — läuft
        # GERADE ein angenommener Anruf in diesem Chat, wird JEDE Agent-Antwort
        # als TTS-Chunk markiert (call_chunk_seq fortlaufend je Anruf); die App
        # spricht sie (ChatSpeechSpeaker-Pfad). Ohne offenen Anruf: exakt das
        # alte Verhalten (kein Marker, kein Pfad-Unterschied).
        call_marker = self._call_marker_for(wire_chat_id)
        if call_marker is not None:
            payload["call_chunk_seq"] = call_marker["seq"]
            payload["call_id"] = call_marker["call_id"]
        extra_meta = {"elapsed_ms": max(0, int(time.time() * 1000) - received_ms),
                      "metadata": metadata} if received_ms is not None else \
            ({"metadata": metadata} if metadata else {})
        fanout = self._enc_fanout_frames("chat.reply.enc", wire_chat_id,
                                         payload, clear={"msg_id": msg_id})
        envelopes: list[dict]
        if fanout:
            envelope = fanout[0]
            envelopes = list(fanout)
        else:
            envelopes = []
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
        for key, value in extra_meta.items():
            if value is None:
                continue
            for env_i in (envelopes or [envelope]):
                env_i[key] = value
        try:
            try:
                await self._send_json(envelope)
                # B3-FAN-OUT: Rest-Geräte je EIGENER Session-Chiffre (fail-soft:
                # ein toter Slot bricht nie die Antwort an die offenen Geräte).
                for extra_env in envelopes[1:] if fanout else []:
                    try:
                        await self._send_json(extra_env)
                    except Exception as exc:
                        logger.warning("FlipsiBridge: chat.reply.enc Fan-out-Send "
                                       "fehlgeschlagen (Gerät): %s", exc)
            except Exception as exc:
                # Fan-out-Extras trotzdem zustellen (Primär-Verband getrennt).
                if fanout and len(envelopes) > 1:
                    for extra_env in envelopes[1:]:
                        try:
                            await self._send_json(extra_env)
                        except Exception:
                            pass
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

    # ───────────────────────── W18: agent.models-Broadcast (Fixliste 35) ──

    @staticmethod
    def _sanitize_model_list(raw: Any) -> list[str]:
        """Katalog hart trimmen — Struktur-Gate-Parität zum Relay
        (_agent_models_ok) + App-MAX_AGENT_MODELS: nicht-leere Strings ≤ 64,
        Dedup (Reihenfolge bleibt), Deckel 24. Ungültige Einträge fliegen
        still (fail-soft: schrottige Overrides nehmen den Katalog nicht mit).
        Zusätzlich _COMPOSER_MODEL_RE-Charset: jede gepickte Modell-ID muss
        denselben /model-Weg wieder zurücklaufen dürfen."""
        out: list[str] = []
        if not isinstance(raw, (list, tuple)):
            return out
        for item in raw:
            if not isinstance(item, str):
                continue
            entry = item.strip()
            if (not entry or len(entry) > AGENT_MODELS_ENTRY_MAX
                    or not _COMPOSER_MODEL_RE.match(entry) or entry in out):
                continue
            if len(out) >= AGENT_MODELS_LIST_MAX:
                break
            out.append(entry)
        return out

    def _read_hermes_delegation(self) -> tuple[str, str, str]:
        """Aktuelles Modell/Denk-Stufe aus der Hermes-Agenten-Konfig lesen
        (self.hermes_config_path → delegation.model/reasoning_effort/provider).

        PyYAML wenn vorhanden, sonst toleranter Zeilen-Scan (fail-soft):
        Kinder des Top-Level-Blocks 'delegation:' bis zum nächsten Key.
        Rückgabe ('', '', '') = nicht lesbar — der Verband fällt dann auf
        Env-Override bzw. den Default-Katalog zurück.
        """
        try:
            with open(self.hermes_config_path, "r", encoding="utf-8") as fh:
                text = fh.read()
        except OSError:
            return "", "", ""
        try:
            import yaml  # type: ignore[import-untyped]
            data = yaml.safe_load(text) or {}
            block = data.get("delegation") if isinstance(data, dict) else None
            if isinstance(block, dict):
                return (str(block.get("model") or "").strip(),
                        str(block.get("reasoning_effort") or "").strip(),
                        str(block.get("provider") or "").strip())
            return "", "", ""
        except Exception:
            pass
        # Zeilen-Scan (kein yaml-Modul / ungültiges YAML):
        model = effort = provider = ""
        in_block = False
        for line in text.splitlines():
            if re.match(r"^delegation:\s*(?:#.*)?$", line):
                in_block = True
                continue
            if not in_block:
                continue
            if line and not line[0].isspace():
                break  # nächster Top-Level-Key
            m = re.match(r"\s+model:\s*([^\s#]+)", line)
            if m:
                model = m.group(1).strip().strip("'\"")
            m = re.match(r"\s+reasoning_effort:\s*([^\s#]+)", line)
            if m:
                effort = m.group(1).strip().strip("'\"")
            m = re.match(r"\s+provider:\s*([^\s#]+)", line)
            if m:
                provider = m.group(1).strip().strip("'\"")
        return model, effort, provider

    def _model_catalog(self) -> tuple[list[str], str, str]:
        """(Katalog, current, effort) sammeln — override-fähig, fail-soft.

        Katalog: FLIPSI_MODELS-Override (init-Zerlegung) sonst AGENT_MODELS_
        DEFAULT_CATALOG; current: FLIPSI_CURRENT_MODEL → delegation.model;
        effort: FLIPSI_REASONING_EFFORT → delegation.reasoning_effort.
        current zählt als Katalog-Mitglied (fehlt er, wird er angehängt).
        """
        base = self._sanitize_model_list(self._models_override) \
            if self._models_override is not None \
            else self._sanitize_model_list(list(AGENT_MODELS_DEFAULT_CATALOG))
        current = self._current_model_override
        effort = self._current_effort_override
        if not current or not effort:
            cfg_model, cfg_effort, _provider = self._read_hermes_delegation()
            if not current:
                current = cfg_model
            if not effort:
                effort = cfg_effort
        current = current.strip()
        if len(current) > AGENT_MODELS_ENTRY_MAX:
            current = ""
        if current and current not in base:
            if len(base) < AGENT_MODELS_LIST_MAX:
                base = base + [current]
            else:
                base = base[:-1] + [current]  # Deckel: current verdrängt das letzte
        if not current and base:
            current = base[0]  # Auto-Default: erster Katalog-Eintrag
        return base, current, effort.strip()

    async def emit_models(self) -> bool:
        """agent.models-Broadcast — enc-first in chat.session_meta-Parität:
        aktive E2E-Session → PFLICHT 'agent.models.enc' (Klartext würde vom
        App-Downgrade-Guard verworfen); ohne Session → Klartext 'agent.models'
        (Legacy-Weg, App akzeptiert ihn nur ohne Session). Payload: models
        (Liste), current (aktives Modell), effort (Denk-Stufe). Rückgabe:
        gesendet (True) — Fehler sind fail-soft geloggt, nie fatal.

        REPLAY-GRENZE (Relay-Verband, W18-Stand): _handle_agent_models baut
        den weitergereichten Klartext-Frame NEU (models-only) — current/effort
        kommen real nur im enc-Pfad ans Phone (opaker Pass-Through). Der
        enc-Pfad ist der verbandspflichtige (Session aktiv = Pflicht), der
        Klartext-Weg bleibt Übergangs-Legacy (nur Liste, vor erstem Handshake).
        """
        if not self._connected or self._ws is None:
            return False
        try:
            models, current, effort = self._model_catalog()
        except Exception:
            logger.warning("FlipsiBridge: agent.models-Katalog unlesbar — Broadcast übersprungen",
                           exc_info=True)
            return False
        model_frames = self._enc_fanout_frames(
            AGENT_MODELS_ENC_FRAME, "",
            {"models": models, "current": current, "effort": effort})
        if model_frames is None:
            model_frames = [{
                "type": AGENT_MODELS_FRAME, "v": _PROTOCOL_VERSION,
                "agent_id": self.agent_id,
                "models": models, "current": current, "effort": effort,
            }]
        sent = False
        # B3-FAN-OUT: Katalog je Geräte-Session (fail-soft; Rückgabe = Primär).
        for models_env in model_frames:
            try:
                await self._send_json(models_env)
                sent = True
            except Exception as exc:
                logger.warning("FlipsiBridge: agent.models-Broadcast fehlgeschlagen: %s", exc)
        return sent

    async def _models_refresh_loop(self) -> None:
        """agent.models je 60 s nachsenden — spät verbindende Geräte kriegen
        den Katalog ohne erstes Warten. Fehler sind fail-soft; Canceln über
        disconnect(). Start-Verzögerung = models_refresh_s (je Connect sendet
        connect() bereits sofort)."""
        while True:
            await asyncio.sleep(self.models_refresh_s)
            try:
                await self.emit_models()
            except Exception:
                logger.warning("FlipsiBridge: agent.models-Refresh fehlgeschlagen",
                               exc_info=True)

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
                # F1: Late-Joiner + Ist-Stand nach dem WS-Neuaufbau (Parität
                # zur W20-Snapshot-Probe). Fail-soft, je Frame einzeln.
                try:
                    await self.emit_cron_status()
                except Exception:
                    logger.warning("FlipsiBridge: cron.status-Reconnect-Send "
                                   "fehlgeschlagen", exc_info=True)
                # F3: In der Totphase geparkte Meta-Pushes nachliefern.
                self._schedule_meta_drain()
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

        # ── B4 (Multi-Gerät): Relay-Steuer-Frames VOR der Namespace-Prüfung ────
        # chat.backfill_request: Router-Frame des Relays (der Owner-Verband ist
        # am RELAY geprüft; der Frame trägt den device_id-Stempel des
        # anfragenden Geräts). device.revoked: Revoke-Verband (Session/Pin-
        # Kappung) — je Agent-Ziel ohne chat_id (Broadcast-Semantik).
        if etype == "device.revoked":
            self._handle_device_revoked(envelope)
            return
        # PH-4-T2: Relay-Identität je Connect (Server→Agent) — TOFU-Pin-Verband
        # VOR der Namespace-Prüfung (Frame trägt bewusst KEINEN agent_id-Feld
        # — die WS-Identität ist hier die RELAY-Instanz, nicht der Agent).
        if etype == "agent.ident":
            try:
                self._handle_relay_ident(envelope)
            except Exception:
                logger.warning("FlipsiBridge: agent.ident-Verarbeitung fehlgeschlagen",
                               exc_info=True)
            return
        if etype == "chat.backfill_request":
            claim_agent = str(envelope.get("agent_id") or "").strip()
            if claim_agent and claim_agent != self.agent_id:
                self.rejected_frames.append({"type": etype,
                                             "reason": "fremde agent_id (Backfill-Router)"})
                logger.warning("FlipsiBridge: chat.backfill_request für fremde agent_id %s abgelehnt",
                               _log_id(claim_agent, 24))
                return
            try:
                self._handle_backfill_request(envelope)
            except Exception:
                # Guard-Aufrufer-Parität (_guard_agent_frame-Verband): eine
                # Exception im Backfill-Pfad reißt die Agent-WS nie mit.
                logger.warning("FlipsiBridge: chat.backfill_request-Verarbeitung fehlgeschlagen",
                               exc_info=True)
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
            # PH-4-T2: Relay-Instanz-Signatur fail-soft verifizieren (Zähler-
            # band; der Frame wird STETS weiterverarbeitet — kein Verbandbruch).
            self._relay_verify_frame_sig(envelope)
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

        # ── W21 (Anruf P1, roadmap-anruf.md §3.4): Call-Signaling Phone→Agent ──
        # E2E enc je Geräte-Session. KEIN Chat-Text, KEIN Gateway-Push (I3-
        # Parität: Signaling ist kein Nachricht-Inhalt) — die Frames füttern
        # ausschließlich die Call-Zustandsmaschine des call_user-Tools.
        if etype in CALL_SIGNAL_FRAMES:
            self._handle_call_signal(envelope, etype, chat_id)
            return

        # PH-3-T3: chat.*.enc — entschlüsseln (Klartext NUR intern, nie geloggt).
        if etype in ("chat.send.enc", "chat.event.enc"):
            text = self._decrypt_inbound(envelope, etype, chat_id=chat_id)
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
            # Komposer Welle 12: Klartext-Pfad — optionale Felder auf Frame-Ebene
            # (modell/denken), gleicher Stash wie der E2E-Pfad.
            _pm = str(envelope.get("model") or "").strip()
            _pe = str(envelope.get("effort") or "").strip()
            if _pm or _pe:
                self._composer_meta[chat_id] = {"model": _pm, "effort": _pe}
        # PH2-T11: Empfangszeit für elapsed_ms notieren (begrenzt, FIFO-Cap).
        self._received_at[chat_id] = int(time.time() * 1000)
        while len(self._received_at) > 256:
            self._received_at.pop(next(iter(self._received_at)))
        # F3 (P40-Verband): bekannte Chat-Ziele PERSISTIERT halten — nach einem
        # Gateway-Restart ist _received_at leer, das Hook-Push an die Slots
        # lief ins LEERE. Fail-soft: Schreibfehler = debug-Log, nie Block.
        self._persist_chats(chat_id)
        # W4-B2: Adapter-gesteuerter Slash-Präfix-Parser — NACH Dekrypt und
        # Fp-Gatter, VOR der Gateway-Zustellung. True = lokal behandelt (kein
        # Gateway-Push; Read-Receipt + Antwort hier); /new und alle Nicht-
        # Befehle fallen durch (normale Deliver-Pipeline).
        if self._handle_slash_command(envelope, chat_id, text or ""):
            return
        # Komposer Welle 12: App-Modell-/Denken-Auswahl → session-scoped
        # /model-Befehl VOR der eigenen Nachricht (Referenz-UX: der Chip am
        # Komposer schaltet das Modell der Chat-Sitzung). Danach normale
        # Zustellung der Nachricht selbst.
        if self._composer_meta.get(chat_id):
            self._schedule(self._apply_composer_switch(chat_id))
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

    def _device_sessions(self) -> list:
        """B3: ALLE offenen (nicht abgelaufenen) Geräte-Sessions — Primär-Session
        zuerst (Alt-Pfad-Parität), dann Rest in Etablierungs-Reihenfolge.
        Abgelaufene Entries werden ausgesondert (24-h-Cap je Gerät)."""
        out: list = []
        primary = self._crypto_session
        for key in list(self._crypto_sessions.keys()):
            sess = self._crypto_sessions.get(key)
            if sess is None:
                self._crypto_sessions.pop(key, None)
                continue
            if sess.expired():
                self._crypto_sessions.pop(key, None)
                if primary is sess:
                    primary = None
                    self._crypto_session = None
                continue
            if out and sess in out:
                continue
            out.append(sess)
        if primary is not None and primary not in out and not primary.expired():
            out.insert(0, primary)
        return out

    def _session_for_frame(self, envelope: dict):
        """B3: Geräte-Session über die KLARE session_id des Envelopes suchen —
        Cross-Session-Replay bleibt blockiert (decrypt_frame prüft session_id
        zusätzlich gegen den Ratchet-Header; hier nur die Routing-Meta)."""
        frame_session = str(envelope.get("session_id") or "").strip()
        if not frame_session:
            return None
        for sess in self._crypto_sessions.values():
            if sess.session_id == frame_session:
                return sess
        return None

    def _enc_fanout_frames(self, frame_type: str, chat_id: str, payload: dict,
                           clear: Optional[dict] = None,
                           exclude: Optional[Any] = None) -> Optional[list]:
        """B3-FAN-OUT (Plan Kap. 2.2 (b1)): je offener Geräte-Session EIGENES
        encrypt_frame — NIE dieselbe Chiffre an alle Geräte (je Session eigene
        Ratchet-Kette + AAD session_id|chat_id|agent_id). Rückgabe:
        None = keine Session (Legacy-Klartext-Weg bleibt Aufrufersache);
        sonst Liste: Frame[0] = Primär-Session (Alt-Verband), Rest fail-soft.

        B4: exclude = eine Session-Instanz, die BEWusst NICHT bedient wird —
        der Sync-Echo schließt das SENDENDE Gerät aus (dessen Blase existiert
        schon lokal; das Echo geht an die NEBENgeräte)."""
        sessions = self._device_sessions()
        if not sessions:
            return None
        frames = []
        for sess in sessions:
            if exclude is not None and sess is exclude:
                continue
            # WIRE-chat_id JEGERÄT: AAD bindet die Relay-chat_id der Session —
            # ein fremdes Chat-Routing würde der App-Platte ein falsches Feld
            # geben (Plan 2.3 #1). Ohne eigene Chat-ID (Gerät ohne Post) fällt
            # der Frame auf die Primary/Envelope-Kontext-ID — Decrypt bleibt
            # valid, Merge-Vertrag der App übernimmt als msg_id-Schlüssel.
            wire = getattr(sess, "chat_id", None) or chat_id
            frames.append(sess.encrypt_frame(frame_type, wire, payload, clear=clear))
        return frames

    # ────────────────────────────────── B4 (Multi-Gerät): Verdrahtung ────────
    # Kap. 2.3 #1/#2: msg_id = globaler Einheitsschlüssel; Nutzer-Post von
    # Gerät A → Gateway-Weg wie bisher + Sync-Echo chat.sync.enc an ALLE
    # ANDEREN offenen Geräte-Slots (je Empfänger eigene Chiffre); Backfill-
    # Antwort aus dem Gateway-Verlauf; Revoke kappet Session+Pin des Geräts.

    def _session_for_backfill_request(self, chat_id: str, device_id: str):
        """Ziel-Session eines chat.backfill_request auflösen — bevorzugt über
        den device_id-Stempel des Relays (Session-MAP je Gerät), fallback über
        die Wire-chat_id (Session-Kontext). None = fail-closed (keine Session
        → keinen Verlauf in die Luft versenden)."""
        device_id = str(device_id or "").strip()
        chat_id = str(chat_id or "").strip()
        for key, sess in list(self._crypto_sessions.items()):
            if sess is None or sess.expired():
                continue
            if device_id and (key == device_id
                              or str(getattr(sess, "device_id", "") or "").strip() == device_id):
                return sess
        if chat_id:
            for sess in list(self._crypto_sessions.values()):
                if sess is None or sess.expired():
                    continue
                if getattr(sess, "chat_id", None) == chat_id:
                    return sess
        return None

    def _handle_backfill_request(self, envelope: dict) -> None:
        """chat.backfill_request (Phone→Relay→Adapter, ids-only Klar) →
        Verlaufs-Antwort an das ANFORDERNDE Gerät. Rate-Schild je Geräte-
        Session (B3_BACKFILL_RATE_S, 1 Request / 30 s, DoS-Schild — die App
        sendet bewusst nur EINEN Request je Connect × Agent)."""
        chat_id = str(envelope.get("chat_id") or "").strip()
        if not chat_id:
            self.rejected_frames.append({"type": "chat.backfill_request",
                                         "reason": "chat_id fehlt"})
            logger.warning("FlipsiBridge: chat.backfill_request ohne chat_id — verworfen")
            return
        gateway_chat = str(envelope.get("gateway_chat") or "").strip() or chat_id
        device_id = str(envelope.get("device_id") or "").strip()
        try:
            limit = int(envelope.get("limit"))
        except (TypeError, ValueError):
            limit = B3_BACKFILL_MAX
        limit = max(1, min(B3_BACKFILL_MAX, min(limit, self.backfill_k)))
        target = self._session_for_backfill_request(chat_id, device_id)
        if target is None:
            logger.warning(
                "FlipsiBridge: chat.backfill_request (device %s) ohne passende "
                "Geräte-Session — verworfen (fail-closed)", _log_id(device_id, 24))
            return
        rate_key = str(getattr(target, "device_key", "") or target.session_id)
        now = time.time()
        last = self._backfill_last.get(rate_key)
        if last is not None and (now - last) < B3_BACKFILL_RATE_S:
            logger.info(
                "FlipsiBridge: chat.backfill_request über Rate-Schild (%.1fs < %.0fs, "
                "device %s) — verworfen", now - last, B3_BACKFILL_RATE_S,
                _log_id(device_id, 24))
            return
        self._backfill_last[rate_key] = now
        while len(self._backfill_last) > 8:
            self._backfill_last.pop(next(iter(self._backfill_last)))
        self._schedule(self._send_backfill_response(
            target, chat_id, gateway_chat, limit))

    def _backfill_gateway_chat_candidates(self, sess, wire_chat_id: str,
                                          gateway_chat: str) -> list:
        """Verlaufs-Kandidaten je Request (geordnet, ohne Dubletten):
        (1) die je GERÄT abgeleitete stabile Gateway-ID (Session-ik — B3-Norm,
            'flipsi-phone-<ik8>'), (2) das bekannte Wire→GW-Mapping, (3) der
            vom App-Vertrag übertragene gateway_chat-Wert, (4) die Wire-chat_id
            selbst (Legacy-Form 'chat-…' vor dem Identity-Verband)."""
        candidates: list = []

        def add(value: Any) -> None:
            value = str(value or "").strip()
            if value and value not in candidates:
                candidates.append(value)

        ik = str(getattr(sess, "device_ik", "") or "").strip().lower()
        if ik:
            add(self._STABLE_CHAT_PREFIX + ik[:8])
        add(self._gw_chat_by_relay.get(wire_chat_id))
        add(gateway_chat)
        add(wire_chat_id)
        return candidates

    def _backfill_entries(self, candidates: list, limit: int) -> tuple:
        """Verlauf-Einträge (ids+Text) über die Quelle sammeln — Kandidaten
        bis zum ERSTEN Ergebnis (None = kein Bestand für diesen Kandidaten).
        Rückgabe: (entries_ASC, truncated). Die Quelle liefert {text,
        from_agent, msg_id?, time?|ts?} (Injektion) oder den Prod-Reader. KEIN
        Klartext außerhalb dieses Prozesses — die Frames verschließt der
        Ratchet (siehe _send_backfill_response)."""
        raw: Optional[list] = None
        if self._backfill_history_fn is not None:
            for candidate in candidates:
                try:
                    raw = self._backfill_history_fn(candidate)
                except Exception:
                    logger.warning("FlipsiBridge: Backfill-Quelle fehlgeschlagen (Kandidat %s)",
                                   _log_id(candidate, 32), exc_info=True)
                    raw = None
                if raw is not None:
                    break
        else:
            for candidate in candidates:
                raw = self._gateway_history_entries(candidate, limit)
                if raw is not None:
                    break
        if raw is None:
            # Ehrliche Antwort OHNE Verlauf zu behaupten (B3-Verband: keine
            # Quelle = leer mit truncated=True, kein stiller Verlaufs-Anspruch).
            return [], True
        entries: list = []
        seen_ids: set = set()
        truncated = False
        for row in raw:
            if not isinstance(row, dict):
                continue
            text = row.get("text")
            if not isinstance(text, str) or not text.strip():
                continue
            msg_id = str(row.get("msg_id") or "").strip()
            if msg_id:
                if msg_id in seen_ids:
                    continue  # msg_id-Idempotenz (erstes Auftreten gewinnt)
                seen_ids.add(msg_id)
            timestamp = row.get("ts", row.get("time"))
            try:
                ts = int(float(timestamp) * 1000)
            except (TypeError, ValueError):
                ts = int(time.time() * 1000)
            if len(text) > B3_BACKFILL_ENTRY_TEXT_CAP:
                text = text[:B3_BACKFILL_ENTRY_TEXT_CAP]
            status = row.get("status")
            entries.append({
                "text": text,
                "from_agent": bool(row.get("from_agent")),
                "msg_id": msg_id or None,
                "ts": ts,
                # Status nur DURCHSCHLEIFEN (ids-only Snapshot-Quelle); der
                # Snapshot-Filter nimmt NUR bekannte Werte (kein erfundener Chip).
                "status": status if isinstance(status, str) else None,
            })
        if len(entries) > limit:
            entries = entries[-limit:]  # BEHÄLT die neuesten K (Chronologie ASC)
            truncated = True
        return entries, truncated

    @staticmethod
    def _backfill_batches(entries: list) -> list:
        """Batches à ≤ 64 Entries (ct-Cap-Schutz, Parität B3_BACKFILL_BATCH_MAX)
        mit Bytes-Deckel je Batch (B3_BACKFILL_BYTES_MAX): ein Einzel-Entry
        über dem Deckel wird TRUNCATED (kurzer Text schlägt einen am Relay
        verworfenen Frame — ehrlich statt stiller Verlust)."""
        batches: list = []
        current: list = []
        estimated = 64.0  # json-Overhead-Basis

        def weight_of(item: dict) -> int:
            try:
                return len(json.dumps(item, ensure_ascii=False).encode("utf-8"))
            except (TypeError, ValueError):
                return len(str(item).encode("utf-8"))

        for entry in entries:
            weight = weight_of(entry)
            if current and (len(current) >= B3_BACKFILL_BATCH_MAX
                            or estimated + weight > B3_BACKFILL_BYTES_MAX):
                batches.append({"entries": current, "truncated": False})
                current = []
                estimated = 64.0
            if not current and weight > B3_BACKFILL_BYTES_MAX:
                # Einzel-Entry unverhältnismäßig groß → Text kapppen (der
                # Batch bleibt zustellbar; kein Frame fällt am Relay).
                entry = dict(entry)
                entry["text"] = entry.get("text", "")[:B3_BACKFILL_BYTES_MAX // 3]
                weight = weight_of(entry)
            current.append(entry)
            estimated += weight
        if current:
            batches.append({"entries": current, "truncated": False})
        return batches

    async def _send_backfill_response(self, sess: Any, wire_chat_id: str,
                                      gateway_chat: str, limit: int) -> None:
        """chat.backfill.enc-Batches an DAS anfordernde Gerät (JE eigene
        Session-Chiffre — NIE an alle Geräte) + abschließend ids-only
        chat.status_snapshot (Form-Verband App-Parser: Array {msg_id,status})."""
        candidates = self._backfill_gateway_chat_candidates(sess, wire_chat_id, gateway_chat)
        entries, truncated = self._backfill_entries(candidates, limit)
        wire = getattr(sess, "chat_id", None) or wire_chat_id
        batches = self._backfill_batches(entries)
        if not batches:
            # Ehrlich „Verlauf leer/unbekannt" — die App endet die Fortschritt-
            # Zeile (ChatBackfillRules: leere Batch = ehrliches Ende).
            batches = [{"entries": [], "truncated": truncated}]
        last = len(batches) - 1
        sent_any = False
        for index, payload in enumerate(batches):
            if index == last:
                payload["truncated"] = truncated
            try:
                frame = sess.encrypt_frame(B3_BACKFILL_ENC_FRAME, wire, payload)
                await self._send_json(frame)
                sent_any = True
            except Exception as exc:
                logger.warning("FlipsiBridge: chat.backfill.enc-Send fehlgeschlagen (Batch %d/%d): %s",
                               index + 1, last + 1, exc)
                return  # ratchet-Fortschritt am Ziel bleibt konsistent (Header-n-Skip)
        if sent_any:
            logger.info("FlipsiBridge: chat.backfill.enc — %d Batch(es), %d Entries "
                        "(truncated=%s) an device %s",
                        last + 1, len(entries), truncated, _log_id(str(getattr(sess, "device_key", "") or sess.session_id), 24))
        statuses = self._backfill_snapshot_statuses(entries)
        if not statuses:
            return
        try:
            await self._send_json({
                "type": B3_STATUS_SNAPSHOT_FRAME, "v": _PROTOCOL_VERSION,
                "agent_id": self.agent_id, "chat_id": wire,
                "session_id": sess.session_id,
                "ts": int(time.time() * 1000),
                "statuses": statuses,
            })
        except Exception as exc:
            logger.warning("FlipsiBridge: chat.status_snapshot-Send fehlgeschlagen: %s", exc)

    @staticmethod
    def _backfill_snapshot_statuses(entries: list) -> list:
        """ids-only Status-Snapshot aus dem Verlauf (msg_id→ queued/delivered/
        read/failed, Parität ChatStatusRank.KNOWN; KEIN erfundener Stand —
        Entries ohne msg_id/stdstatus landen NICHT im Snapshot). Cap = 500,
        BEHÄLT die neuesten (Chronologie ASC)."""
        out: list = []
        for entry in entries:
            msg_id = str(entry.get("msg_id") or "")
            status = entry.get("status")
            if not msg_id or status not in B3_STATUS_KNOWN:
                continue
            out.append({"msg_id": msg_id, "status": status})
        return out[-B3_STATUS_SNAPSHOT_CAP:]

    def _gateway_history_entries(self, gw_chat_id: str, limit: int) -> Optional[list]:
        """B6-PROD-Verband: Gateway-Verlaufs-Zug für den Backfill (der Adapter
        läuft IM Gateway-Prozess — der Session-Broker der eigenen Plattform-
        Quelle ist die Turn-Wahrheit, Kap. 2.3 #3). FAIL-SOFT: hermes_state/
        Session/DB-Fehler → None (ehrlich OHNE Verlauf zu behaupten); die
        Sitzungs-Datenbank wird ONLY-READ geöffnet (nie Schreiben am Live-DB)."""
        if not isinstance(gw_chat_id, str) or not gw_chat_id.strip():
            return None
        try:
            from hermes_state import SessionDB, _default_db_path  # noqa (lazy)
        except Exception:
            return None
        db = getattr(self, "_backfill_state_db", None)
        if db is None:
            try:
                db = SessionDB(_default_db_path(), read_only=True)
                # Nur-Read-Zugriff: keine append-/maintenance-Pfade; Instance
                # wird je Adapter gecacht (Rate-Schild = max 1 Request/30 s).
                self._backfill_state_db = db
            except Exception as exc:
                logger.warning(
                    "FlipsiBridge: Gateway-Verlaufs-DB nicht lesbar (%s) — Backfill ehrlich ohne Verlauf",
                    type(exc).__name__)
                return None
        try:
            session_id = db.find_session_by_origin(
                platform=_STATUS_PLATFORM_ID, chat_id=gw_chat_id.strip())
            if not session_id:
                return None
            rows = db.get_messages(
                session_id, latest=True,
                limit=max(1, min(int(limit or 0) or self.backfill_k, B3_BACKFILL_MAX)))
        except Exception as exc:
            logger.warning(
                "FlipsiBridge: Gateway-Verlaufs-Zug fehlgeschlagen (%s) — Backfill ehrlich ohne Verlauf",
                type(exc).__name__)
            return None
        entries: list = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            role = str(row.get("role") or "")
            content = row.get("content")
            if role not in ("user", "assistant") or not isinstance(content, str) \
                    or not content.strip():
                continue
            try:
                ts = float(row.get("timestamp") or 0.0)
            except (TypeError, ValueError):
                ts = 0.0
            entries.append({
                "text": content,
                "from_agent": role == "assistant",
                "time": ts,
            })
        return entries

    def _send_sync_echo_frames(self, chat_id: str, text: str, envelope: dict) -> None:
        """B4 ECHO (Kap. 2.3): Nutzer-Post von Gerät A → chat.sync.enc an ALLE
        ANDEREN offenen Geräte-Slots (je Empfänger eigene Chiffre — Fan-out
        über die B3-Session-MAP, das SENDENDE Gerät ausgeschlossen). Der
        Echo trägt sync:true + sync_of=<Ursprungs-msg_id> (App-Kaskade
        ChatSyncRules.originMsgId → Chip-Bindung + Dedup je msg_id).
        Fail-soft: Echo-Verlust schadet nicht (Backfill-Norm, Kap. 4.1)."""
        if not isinstance(text, str) or not text.strip():
            return
        origin = str(envelope.get("msg_id") or "").strip()
        if origin:
            if origin in self._sync_echo_dedup:
                logger.debug("FlipsiBridge: Sync-Echo für msg %s bereits gesendet — übersprungen",
                             _log_id(origin, 12))
                return
            self._sync_echo_dedup[origin] = time.time()
            while len(self._sync_echo_dedup) > B3_SYNC_ECHO_DEDUP:
                self._sync_echo_dedup.pop(next(iter(self._sync_echo_dedup)))
        sender = self._session_for_frame(envelope)
        payload: dict = {"text": text, "sync": True}
        clear: Optional[dict] = None
        if origin:
            payload["msg_id"] = origin
            payload["sync_of"] = origin
            clear = {"msg_id": origin}
        frames = self._enc_fanout_frames(_SYNC_ENC_FRAME, chat_id, payload,
                                         clear=clear, exclude=sender)
        if not frames:
            return

        async def _send() -> None:
            for frame in frames:
                try:
                    await self._send_json(frame)
                except Exception as exc:
                    logger.warning("FlipsiBridge: chat.sync.enc-Send fehlgeschlagen: %s", exc)

        self._schedule(_send())
        logger.info("FlipsiBridge: Sync-Echo geplant — %d Neben-Gerät(e) (msg %s)",
                    len(frames), _log_id(origin, 12) or "<neu>")

    def _handle_device_revoked(self, envelope: dict) -> None:
        """B4 REVOKE-Verband (Kap. 4.2): das Relay hat ein Gerät entfernt —
        die je-Agent-Verbande kappen: E2E-Session(s) der SESSION-MAP (device_id
        Anker) + Pin-Eintrag(e) des Geräts (TOFU je ik_pub). Fail-soft gegen
        unbekannte Geräte (nichts zu kappen ist legitim)."""
        device_id = str(envelope.get("device_id") or "").strip()
        if not device_id:
            logger.warning("FlipsiBridge: device.revoked ohne device_id — verworfen")
            return
        ik_pubs: list = []
        touched = 0
        for key, sess in list(self._crypto_sessions.items()):
            match = (key == device_id
                     or str(getattr(sess, "device_id", "") or "").strip() == device_id)
            if not match:
                continue
            ik = str(getattr(sess, "device_ik", "") or "").strip().lower()
            if ik:
                ik_pubs.append(ik)
            was_primary = self._crypto_session is sess
            self._crypto_sessions.pop(key, None)
            touched += 1
            if was_primary:
                self._crypto_session = None
        if not touched:
            logger.info(
                "FlipsiBridge: device.revoked für %s ohne offene Geräte-Session — "
                "nichts zu kappen", _log_id(device_id, 24))
            return
        removed_pins = self._remove_phone_pin_iks(ik_pubs)
        if self._pin_warned_ik and self._pin_warned_ik in ik_pubs:
            self._pin_warned_ik = ""
        logger.warning(
            "FlipsiBridge: DEVICE-REVOKED %s — %d Geräte-Session(s) gekappt, "
            "%d Pin-Eintrag(e) entfernt (Revoke-Verband Kap. 4.2)",
            _log_id(device_id, 24), touched, removed_pins)

    def _remove_phone_pin_iks(self, ik_pubs: list) -> int:
        """Pin-Einträge aus der Pin-MAP entfernen (B4 Revoke) — atomar wie
        _write_phone_pin (mkstemp + os.replace), fail-soft: unlesbare Datei =
        0 Entfernungen (Pin bleibt, die Prüfung toleriert wie bisher)."""
        raw = self._read_phone_pin()
        pins = self._pin_entries(raw)
        if not isinstance(pins, dict):
            return 0
        targets = [ik for ik in ik_pubs if isinstance(ik, str) and ik]
        remaining = dict(pins)
        removed = 0
        for ik in targets:
            if remaining.pop(ik, None) is not None:
                removed += 1
        if not removed:
            return 0
        legacy_anchor = str((raw or {}).get("ik_pub") or "").strip().lower()
        if legacy_anchor not in remaining:
            legacy_anchor = ""
        payload_dict = {"v": 2, "pins": remaining}
        if legacy_anchor:
            top = remaining.get(legacy_anchor)
            if isinstance(top, dict):
                payload_dict["ik_pub"] = legacy_anchor
                payload_dict["pinned"] = str(top.get("pinned") or "")
        payload = json.dumps(payload_dict, indent=2) + "\n"
        directory = os.path.dirname(os.path.abspath(self.phone_pin_file)) or "."
        os.makedirs(directory, exist_ok=True)
        import tempfile as _tempfile
        try:
            fd, tmp_path = _tempfile.mkstemp(prefix=".phone-pin-", dir=directory)
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as fh:
                    try:
                        os.fchmod(fh.fileno(), 0o600)
                    except AttributeError:
                        pass
                    fh.write(payload)
                os.replace(tmp_path, self.phone_pin_file)
            except BaseException:
                try:
                    os.unlink(tmp_path)
                except OSError:
                    pass
                raise
        except OSError as exc:
            logger.warning("FlipsiBridge: Pin-Kappung konnte nicht geschrieben werden: %s", exc)
        return removed

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
        # ── B3 Session-MAP: je GERÄT eine eigene Session (Plan Kap. 3.1 (I)).
        # Registry-Anker ist die device_id, die der Relay (B2: Registry-Wahrheit,
        # WS-authentifiziert) im hello-Pfad durchreicht — BEKANNTE Geräte
        # verdrängen NIEMALS fremde Slots (Plan 0.1 #3 / Kap. 4.2 B7: kein
        # Takeover-Fehlalarm pro echtem Zweitgerät). Legacy-Hellos OHNE
        # device_id (Alt-App/Klartext-Probe) behalten die Ein-Gerät-Semantik:
        # der frische Slot verdrängt die Map (SIRS-01/B8-Modell unverändert).
        _ik_pub_b3 = str(envelope.get("ik_pub") or "").strip().lower()
        device_key = (str(envelope.get("device_id") or "").strip()
                      or ("ik:" + _ik_pub_b3 if _ik_pub_b3 else session.session_id))
        if not envelope.get("device_id"):
            self._crypto_sessions.clear()
        else:
            # Cap-Schild je Agent (B3_SESSIONS_CAP, Parität Pin-Cap): ein
            # NEUES Gerät verdrängt die älteste ANDERE Session (der Primär-/
            # ankommende Slot ist nie Opfer). Fail-loud, kein Stillschweigen.
            while (len(self._crypto_sessions) >= B3_SESSIONS_CAP
                   and device_key not in self._crypto_sessions):
                oldest = next(iter(self._crypto_sessions))
                if self._crypto_sessions[oldest] is session:
                    break
                evicted = self._crypto_sessions.pop(oldest)
                logger.warning(
                    "FlipsiBridge: Session-MAP voll (Cap %d) — älteste Geräte-"
                    "Session %s (device %s) evicted (B3-Neu-Gerät)",
                    B3_SESSIONS_CAP, evicted.session_id[:8], _log_id(oldest, 24))
        if device_key in self._crypto_sessions:
            old = self._crypto_sessions[device_key]
            if old is not session:
                logger.info("FlipsiBridge: Session-Rotate je Gerät %s (%s → neu %s)",
                            _log_id(device_key, 24), old.session_id[:8],
                            session.session_id[:8])
        self._crypto_sessions[device_key] = session
        # Geräte-Metadaten je Session (B3-Fan-out/Backfill-Routing, ids-only).
        session.device_key = device_key
        session.device_id = str(envelope.get("device_id") or "")
        session.device_ik = _ik_pub_b3
        session.chat_id = getattr(session, "chat_id", None)
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
        wieder verworfen (kein Zustand ohne bestätigte Verbindung). Nach
        ERFOLGREICHEM Ack gilt die Session als bestätigt → W18: der
        agent.models-Broadcast geht sofort als 'agent.models.enc' raus
        (enc-Verband-Pflicht: Klartext würfe der App-Downgrade-Guard weg).
        Fail-soft: kein Ack-Erfolg hängt am Broadcast."""
        try:
            await self._send_json(ack)
        except Exception as exc:
            logger.warning("FlipsiBridge: hello_ack-Send fehlgeschlagen: %s", exc)
            for key in list(self._crypto_sessions.keys()):
                if self._crypto_sessions.get(key) is session:
                    self._crypto_sessions.pop(key, None)
            if self._crypto_session is session:
                self._crypto_session = None
            return
        try:
            await self.emit_models()
        except Exception:
            logger.warning("FlipsiBridge: agent.models-Handshake-Send fehlgeschlagen",
                           exc_info=True)
        # W20 (Sir-Befund 17.11., Slot-Leichen nach Reconnect): Nach jedem
        # (Re)Connect den ECHTEN Ist-Stand nachschieben — der Adapter hält
        # die Wahrheit (working + offene Sub-Slots) im Prozess-Dict, aber
        # Frames während einer getrennten Verbindung (z.B. Relay-Restart)
        # sind fail-soft VERLOREN; das Phone blieb auf Alt-Ständen sitzen
        # (fertige Subs als Leichen, working-Stand veraltet). Der Snapshot
        # gleicht das mit EINEM Status-Frame + je offenem Slot EINEM OPEN-
        # Frame ab — verwaiste Leichen (end kam offline) sind im Dict
        # bereits geräumt und tauchen im Snapshot nicht mehr auf.
        try:
            await self._emit_status_snapshot()
        except Exception:
            logger.warning("FlipsiBridge: Status-Snapshot-Send fehlgeschlagen",
                           exc_info=True)
        # F1: cron.status je bestätigtem Handshake neu (enc-Verband-Pflicht —
        # Klartext vom Connect wird vom App-Downgrade-Guard verworfen).
        try:
            await self.emit_cron_status()
        except Exception:
            logger.warning("FlipsiBridge: cron.status-Handshake-Send fehlgeschlagen",
                           exc_info=True)
        # F3: Nachliefer-Queue abarbeiten — ab jetzt läuft enc sicher.
        self._schedule_meta_drain()

    async def _emit_status_snapshot(self) -> None:
        """W20: Ist-Stand (working/label + offene Sub-Slots) nach (Re)Connect.

        Ein Frame je Zustand, enc-first wie die Hook-Frames — NUR-lesen vom
        _status-Dict (unter Lock kopiert), keine Zustandsänderung, kein
        Coalesce-Bypass (nutzt denselben Send-Weg wie emit_agent_status).
        """
        now = self._status_now_ms()
        with self._status_lock:
            working = bool(self._status.get("working"))
            label = self._status.get("label")
            slots = [
                {
                    "id": slot_id,
                    "name": str(slot.get("name") or "")[:64],
                }
                for slot_id, slot in (self._status.get("sub_slots") or {}).items()
                if isinstance(slot, dict)
            ]
        await self.emit_agent_status(working=working, label=label, started_at=now)
        for slot in slots:
            if not slot["name"]:
                continue
            await self.emit_agent_status(
                working=working, label=None, started_at=now,
                sub=slot, end=False)

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

    def _pin_entries(self, pin: Optional[dict]) -> Optional[dict]:
        """B3 Pin-MAP-Normalisierung (Plan Kap. 3.1/5): v2 {"pins": {ik: entry}}
        ODER Legacy v1 {"ik_pub": …} → {ik_pub: entry}. MIGRATION: der bestehende
        Einzel-Pin bleibt als Pin des ERSTEN adoptierten Geräts gültig (er wird
        beim nächsten Schreiben als v2-Map mitgeschleppt — kein Pin-Verlust).
        None = unlesbar (Prüfung überspringen wie bisher)."""
        if not isinstance(pin, dict):
            return None
        pins: dict = {}
        raw = pin.get("pins")
        if isinstance(raw, dict):
            for ik, entry in raw.items():
                ik_s = str(ik or "").strip().lower()
                if ik_s:
                    pins[ik_s] = entry if isinstance(entry, dict) else {"pinned": str(entry)}
        legacy_ik = str(pin.get("ik_pub") or "").strip().lower()
        if not pins and legacy_ik:
            pins[legacy_ik] = {"pinned": str(pin.get("pinned") or ""), "src": "legacy"}
        return pins

    def _check_phone_identity_pin(self, hello: dict) -> None:
        """SEC-W1 W1A-5 + FIX-WELLE-2 B2 + B3: Pin-MAP je ik_pub.

        relay-keys.json (vom Relay gepublishte Phone-Device-Keys) hat am
        Agenten NULL Consumer — `respond_to_hello` validierte nur die Hex-
        Form, jeder Paarungs-Code-Inhaber etablierte sich als 'das Phone'
        (Session-Verdrängung, FP-ABGLEICH verglich dann nur Angreifer-vs-
        Agent). Fix: Der ERSTE Handshake pint ik_pub des Phones lokal
        (0600, neben dem Identity-File); ein WECHSEL ist ein lautes
        Warn-Ereignis (Neu-Gerät ODER Übernahme — klärt der FP-Abgleich).

        B3 (Multi-Gerät, Plan Kap. 3.1): die Pin-Datei wird zur PIN-MAP je
        ik_pub (Cap je Agent = B3_DEVICE_PIN_CAP). BEKANNTE Geräte (ik in
        der Map) sind der Normalzustand — sie erzeugen KEINE Wechsel-Warnung
        mehr (Parität Plan Kap. 4.2 B7: kein Dauer-Fehlalarm pro echtem
        Zweitgerät). JE NEUEM ik bleibt EIN LOUD-Ereignis (B2-Dedupe), die
        Adoption passiert erst nach BESTÄTIGTEM FP-Abgleich
        (_confirm_phone_pin). Datei unlesbar = Warnung + Prüfung übersprungen.

        FIX-WELLE-2 B2 (P3, HERZ-Befund 2): Der Pin erneuerte sich NIE —
        JEDE Reconnect-Wiederholung desselben Identity-Wechsels warnte
        erneut (Warn-Spam, Alarm-Fatigue: eine echte Übernahme ging in der
        Flut unter). Jetzt: (a) je NEUEM ik_pub genau EINE Warnung
        (_pin_warned_ik-Dedupe je Adapter-Lebenszeit), (b) nach BESTÄTIGTEM
        FP-Abgleich pint der Adapter die neue Identity persistent
        (_confirm_phone_pin) — danach ist der neue Key der gepinnte
        Normalzustand, keine weitere Warnung."""
        ik_pub = str(hello.get("ik_pub") or "").strip().lower()
        if not ik_pub:
            return
        pin_raw = self._read_phone_pin()
        if pin_raw is None:
            return
        pins = self._pin_entries(pin_raw)
        if pins is None:
            return
        if ik_pub in pins:
            return
        if not pins:
            try:
                self._write_phone_pin(ik_pub, src="tofu")
                logger.info("FlipsiBridge: Phone-Identity gepinnt (TOFU, erste Sichtung)")
            except OSError as exc:
                logger.warning("FlipsiBridge: Phone-Pin konnte nicht gespeichert werden: %s", exc)
            return
        if ik_pub == self._pin_warned_ik:
            # B2: für DIESEN Key wurde bereits gewarnt — Wiederholungs-/
            # Reconnect-Spam unterdrücken, bis ein ANDERER Key auftaucht.
            logger.info("FlipsiBridge: PHONE-IDENTITY-WECHSEL (gepinnt %s… → neu %s…) "
                        "bereits gemeldet — Wiederholungswarnung unterdrückt (B2)",
                        next(iter(pins))[:8], ik_pub[:8])
            return
        self._pin_warned_ik = ik_pub
        logger.warning(
            "FlipsiBridge: PHONE-IDENTITY-WECHSEL erkannt (gepinnt %s… → neu %s…) — "
            "neues Gerät ODER Übernahme-Versuch (weiterer Paarungs-Code-Inhaber). "
            "Fingerprint-Abgleich am NEUEN Phone prüfen! (B3: nach bestätigtem "
            "Abgleich wird die Identity in die Pin-MAP je Gerät adoptiert)",
            next(iter(pins))[:8], ik_pub[:8])

    def _confirm_phone_pin(self, ik_pub: str = "") -> None:
        """FIX-WELLE-2 B2 + B3: FP-Abgleich BESTANDEN = die Phone-Identity
        ist vom User bestätigt → Pin in der Pin-MAP je GERÄT adoptieren.

        B3 (Plan Kap. 3.1): der bestehende Pin-Verband bleibt (Migration:
        Legacy-Einzel-Pin = zuerst adoptiertes Gerät); die NEUE Identity wird
        ZUSÄTZLICH eingetragen (Top-Level 'ik_pub' trägt für Legacy-Leser den
        zuletzt bestätigten Key). Nur wenn der Pin wirklich fehlt; der
        Cap-Schutz (B3_DEVICE_PIN_CAP) verweigert die Adoption ab dem
        Cap je Agent (LOUD, kein stiller Pin-Verlust).
        """
        ik_pub = str(ik_pub or self._active_phone_ik).strip().lower()
        if not ik_pub:
            return
        pins = self._pin_entries(self._read_phone_pin())
        if pins is None:
            return
        if ik_pub in pins:
            return
        if len(pins) >= B3_DEVICE_PIN_CAP:
            logger.warning(
                "FlipsiBridge: Pin-Cap je Agent (%d) erreicht — Identity %s… NICHT "
                "adoptiert (B3-Cap-Schild; Gerät in der App entfernen/Revoke und "
                "wieder koppeln)", B3_DEVICE_PIN_CAP, ik_pub[:8])
            return
        try:
            self._write_phone_pin(ik_pub, src="fp-confirm")
            self._pin_warned_ik = ""
            logger.info("FlipsiBridge: Phone-Identity nach BESTÄTIGTEM Fingerprint-"
                        "Abgleich in die Pin-MAP je Gerät übernommen (%s…, %d Pins) — "
                        "künftige Handshakes dieses Geräts warnen nicht mehr (B2/B3)",
                        ik_pub[:8], len(self._pin_entries(self._read_phone_pin()) or {}))
        except OSError as exc:
            logger.warning("FlipsiBridge: Phone-Pin-Aktualisierung fehlgeschlagen: %s", exc)

    def _write_phone_pin(self, ik_pub: str, src: str = "tofu") -> None:
        """Phone-Identity-Pin(s) schreiben — B3 PIN-MAP v2 (je ik_pub ein Eintrag,
        Top-Level 'ik_pub' = Legacy-Anker für Alt-Leser), Cap je Agent; 0600,
        ATOMAR (SEC-W2 W2-3).

        SEC-W2 W2-3: das alte O_TRUNC auf der Bestandsdatei konnte den
        Phone-Pin bei Absturz MITTLERES Schreibens leeren/korruptieren
        (Parität zu W1 D3a für relay-keys.json) — Folge: TOFU-Pin weg →
        nächster Handshake pint den Angreifer-Key als 'erstes Gerät'.
        Jetzt: Temp-Datei (mkstemp = 0600) + atomares os.replace; der
        Alt-Bestand bleibt bei JEDEM Fehler unangetastet."""
        ik_pub = str(ik_pub or "").strip().lower()
        if not ik_pub:
            raise ValueError("ik_pub fehlt")
        pins = self._pin_entries(self._read_phone_pin()) or {}
        pins[ik_pub] = {"pinned": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "src": src}
        entry = pins.pop(ik_pub)
        ordered = {ik: pins[ik] for ik in list(pins.keys())[:B3_DEVICE_PIN_CAP - 1]}
        ordered[ik_pub] = entry
        payload = json.dumps(
            {"v": 2, "ik_pub": ik_pub,
             "pinned": entry.get("pinned") or time.strftime("%Y-%m-%dT%H:%M:%S%z"),
             "pins": ordered},
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

    def _decrypt_inbound(self, envelope: dict, etype: str,
                         chat_id: str = "") -> Optional[str]:
        """chat.*.enc entschlüsseln → Text (None = verworfen, Grund geloggt
        OHNE Klartext). Cross-Session-Replay wird per session_id geblockt.
        B3: Session-Suche in der Geräte-MAP (je Gerät eine Session) — der
        Ratchet von B decodiert KEINE Frames von A (session_id-Bindung im
        decrypt_frame bleibt zweite Verteidigungslinie).
        B3-Strengung (PH-3/SIRS-01-Testvertrag, Alt-Pfad-Parität): die Geräte-
        MAP autorisiert KEINE Zustellung ohne intakten Primär-Anchor — fehlt
        die Primär-Session (`_crypto_session` None, Agent-Restart) oder ist die
        getroffene Session abgelaufen, gilt der Frame als 'Session dem Agenten
        nicht bekannt' → SIRS-01-Pfad unten (fail-closed + crypto.session_reset,
        KEIN stilles Verwerfen). Takeover-Sonde nur bei intaktem Primär-Kontext."""
        sess = self._session_for_frame(envelope)
        _primary = self._active_session()
        if sess is not None and (_primary is None or sess.expired()):
            sess = None
        if sess is None and _primary is not None and self._crypto_sessions:
            # B3: Frame von einem Gerät, das der Adapter (noch) nicht führt —
            # Takeover-/Replay-Sonde (B8-Verband: crypto.session_reset + Neu-
            # Handshake; die legitimen Geräte-Slots bleiben unberührt).
            frame_session = str(envelope.get("session_id") or "").strip()
            self.rejected_frames.append({
                "type": etype,
                "reason": "fremde session_id (Cross-Session-Replay/-Takeover) — "
                          "crypto.session_reset + Neu-Handshake angestoßen"})
            logger.warning(
                "FlipsiBridge: %s mit fremder session_id %s verworfen — Cross-Session-"
                "Takeover-/Replay-Sonde; crypto.session_reset ans Relay, Neu-Handshake "
                "angestoßen (FIX-WELLE-2 B8/B3-Map)", etype, _log_id(frame_session, 16))
            if frame_session and not self._session_reset_pending:
                self._session_reset_pending = True
                self._schedule(self._send_session_reset(
                    frame_session, "cross-session-takeover"))
            return None
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
        try:
            payload = sess.decrypt_frame(envelope)
        except CryptoFrameError as exc:
            self.rejected_frames.append({"type": etype, "reason": str(exc)})
            logger.warning("FlipsiBridge: %s-Entschlüsselung fehlgeschlagen (%s) — Frame verworfen",
                           etype, exc)
            return None
        # B3: Wire-chat_id des Geräts je Session merken (Fan-out-Kontext —
        # AAD der Antwort bindet die RELAY-chat_id des adressierten Geräts).
        if chat_id:
            sess.chat_id = chat_id
        # Komposer Welle 12: optionale Felder aus der inneren Map stashen
        # (modell/denken). Klartext nur im Prozess, nie in Logs.
        # (B3-Nebenbefund: der Stash hielt chat_id ohne Parameter/Binding —
        # jeder enc-Frame mit modell/effort wäre mit NameError gecrasht.)
        _cmodel = str(payload.get("model") or "").strip()
        _ceffort = str(payload.get("effort") or "").strip()
        if _cmodel or _ceffort:
            wire_chat = chat_id or str(envelope.get("chat_id") or "")
            if wire_chat:
                self._composer_meta[wire_chat] = {"model": _cmodel, "effort": _ceffort}
        return str(payload.get("text") or "")

    def _handle_fingerprint_check(self, envelope: dict, chat_id: str, text: str) -> bool:
        """Sirs Revision (iii): 'FP-ABGLEICH: <64 Hex>' → Vergleich gegen den
        EIGENEN Session-Fingerprint + automatische chat.reply-Antwort.
        True = als Protokoll-Nachricht behandelt (kein Gateway-Push)."""
        if not text.startswith(_FP_PREFIX):
            return False
        # B3: der ABGLEICH gehört je GERÄT — Session des FRAME-Sendes gesucht
        # (Fallback: Primär-Session für Alt-Frames ohne session_id-Kontext).
        sess = self._session_for_frame(envelope) or self._active_session()
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
        # B3/FW2-B2-Parität: BESTÄTIGter Abgleich pint je GERÄT — der FP-Frame
        # kennt dessen Identity über seine Session (keine aktive-Anchor-Falle).
        if sess is not None and getattr(sess, "device_ik", ""):
            self._active_phone_ik = sess.device_ik
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
            # Phone-Identity → TOFU-Pin persistent setzen (endet den Warn-
            # Spam bei jedem Reconnect, HERZ-Befund 2). B3: je GERÄT in der
            # Pin-MAP adoptieren (Rückwärtskompatibel ohne device_ik: Anchor).
            self._confirm_phone_pin(getattr(session, "device_ik", "") or "")
            return
        session_id = session.session_id
        logger.warning("FlipsiBridge: Fingerprint-ABWEICHUNG in Session %s — Session invalidiert "
                       "(möglicher Man-in-the-Middle)", session_id[:8])
        # B3: Map-Entry DESSELBEN Geräts mitwerfen (kein Zustand ohne Vertrauen);
        # fremde Geräte-Slots bleiben unberührt (je-Gerät-Isolation).
        for key in list(self._crypto_sessions.keys()):
            if self._crypto_sessions.get(key) is session:
                self._crypto_sessions.pop(key, None)
        if self._crypto_session is session:
            self._crypto_session = None
            self._session_reset_pending = True
        await self._send_session_reset(session_id, "fingerprint-mismatch")

    def _send_read_receipt(self, chat_id: str, msg_id: str) -> None:
        """PH2-T11 (F2-S2): chat.read-Frame feuern (fire-and-forget, nie brechen).

        PH-3: aktive E2E-Session → chat.read.enc (msg_id bleibt klar für die
        PH2-Status-Logik des Relays, ct = verschlüsselter Spiegel).
        """
        read_frames = self._enc_fanout_frames(
            "chat.read.enc", chat_id, {"msg_id": msg_id}, clear={"msg_id": msg_id})

        async def _send() -> None:
            if read_frames is not None:
                # B3-FAN-OUT: Read-Receipt je Geräte-Session (msg_id bleibt klar).
                for read_env in read_frames:
                    try:
                        await self._send_json(read_env)
                    except Exception as exc:
                        logger.warning("FlipsiBridge: chat.read-Frame fehlgeschlagen: %s", exc)
                return
            try:
                await self._send_json({
                    "type": "chat.read", "v": _PROTOCOL_VERSION,
                    "agent_id": self.agent_id, "chat_id": chat_id, "msg_id": msg_id,
                })
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
        # ── B4 ECHO-VERBAND (Kap. 2.3): der Nutzer-Post dieses Geräts geht
        # PARALLEL als chat.sync.enc an ALLE ANDEREN offenen Geräte-Slots
        # (je Empfänger eigene Chiffre; sync_of = Ursprungs-msg_id). Die
        # Zustellung zum Gateway läuft exakt wie bisher — das Echo ist ein
        # NEBENAUSGANG (fail-soft, kein Fan-out-Verlust hängt den Turn auf).
        # Nur E2E-Frames spiegeln (Legacy-Klartext-Posts haben keine Geräte-
        # Session-Wahrheit — ein Echo an den SENDER selbst wäre Unsinn).
        if str(envelope.get("type") or "").endswith(".enc"):
            self._send_sync_echo_frames(chat_id, text, envelope)
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

    def _apply_composer_switch(self, chat_id: str) -> None:
        """Komposer Welle 12: App-Auswahl → Gateway-/model-Befehl (session-scoped).

        Der Gateway kennt '/model <name> [--provider ...] [--reasoning level]'
        aus dem CLI-Katalog (busy_policy=reject) — die Übersetzung passiert
        HIER (Flipsi-Adapter, kein Gateway-Patch). 'Auto' kommt nie an (die
        App sendet dann kein model-Feld). Denken-only (ohne Modell) braucht
        das zuletzt angewendete Modell. busy=reject des Gateways verwirft
        den Befehl — die Applied-Trackings bleiben ungesetzt, die nächste
        Nachricht mit derselben Auswahl versucht es erneut.
        """
        meta = self._composer_meta.pop(chat_id, None)
        if not meta:
            return
        model = str(meta.get("model") or "").strip()
        effort = str(meta.get("effort") or "").strip()
        if model and not _COMPOSER_MODEL_RE.match(model):
            logger.info("FlipsiBridge: Komposer-Modell '%s' unsauber — verworfen", model[:8])
            return
        effort_ok = effort in _COMPOSER_EFFORTS
        last_model, last_effort = self._applied_composer.get(chat_id, (None, ""))
        if model:
            if (model, effort if effort_ok else "") == (last_model, last_effort):
                return
            cmd = "/model " + model
            if effort_ok:
                cmd += " --reasoning " + effort
            self._applied_composer[chat_id] = (model, effort if effort_ok else "")
        elif effort_ok:
            if not last_model or not _COMPOSER_MODEL_RE.match(last_model):
                logger.info("FlipsiBridge: Komposer-Denken '%s' ohne bekanntes Modell — übersprungen",
                            effort)
                return
            if effort == last_effort:
                return
            cmd = "/model " + last_model + " --reasoning " + effort
            self._applied_composer[chat_id] = (last_model, effort)
        else:
            return
        try:
            gw_chat_id = self._stable_gateway_chat_id(chat_id)
            self._ensure_phone_auto_approved(gw_chat_id)
            if _UNDER_HERMES:
                source = self.build_source(chat_id=gw_chat_id,
                                           chat_name=self.get_chat_info(gw_chat_id)["name"],
                                           chat_type="dm",
                                           user_id=str(gw_chat_id),
                                           user_name="FlipsiBridge Komposer",
                                           message_id="")
                from gateway.platforms.event import MessageEvent, MessageType  # noqa: F401
                event = MessageEvent(source=source, text=cmd,
                                     message_type=MessageType.TEXT)  # type: ignore[name-defined]
                logger.info("FlipsiBridge: Komposer-Switch an Gateway: %s", cmd)
                asyncio.get_running_loop().create_task(self._handle_and_ack(
                    event, chat_id, "composer-%d" % int(time.time() * 1000)))
            elif self.message_callback is not None:
                self.message_callback({
                    "chat_id": gw_chat_id, "text": cmd,
                    "message_id": "composer-%d" % int(time.time() * 1000)})
        except Exception:
            logger.warning("FlipsiBridge: Komposer-Switch fehlgeschlagen", exc_info=True)

    async def _send_activity(self, chat_id: str, working: bool, started_at: int) -> None:
        """PH2-T11 (F2-S3): chat.activity-Frame senden — Fehler brechen nie die Antwort.

        PH-3: aktive E2E-Session → chat.activity.enc (working/started_at bleiben
        klar für die Relay-Dedup-Logik, ct = verschlüsselter Spiegel).
        """
        # PH-3: aktive E2E-Sessions → chat.activity.enc je GERÄT (B3-Fan-out).
        acts = self._enc_fanout_frames(
            "chat.activity.enc", chat_id,
            {"working": working, "started_at": started_at},
            clear={"working": working, "started_at": started_at})
        if acts is not None:
            for activity_env in acts:
                try:
                    await self._send_json(activity_env)
                except Exception as exc:
                    logger.warning("FlipsiBridge: chat.activity-Frame fehlgeschlagen: %s", exc)
            return
        envelope = {
            "type": "chat.activity", "v": _PROTOCOL_VERSION,
            "agent_id": self.agent_id, "chat_id": chat_id,
            "working": working, "started_at": started_at,
        }
        try:
            await self._send_json(envelope)
        except Exception as exc:
            logger.warning("FlipsiBridge: chat.activity-Frame fehlgeschlagen: %s", exc)

    # ───────────────────── W19: agent.status-Broadcast (Hook-Verband) ───────────

    @staticmethod
    def _status_now_ms() -> int:
        """Unix-ms (Transitions-/Herzschlag-Zeitstempel, Parität chat.activity)."""
        return int(time.time() * 1000)

    @staticmethod
    def _status_clip(value: Any, cap: int = _STATUS_LABEL_CAP) -> str:
        """String-Gate (Parität Relay-_agent_status_ok): trim + ≤ cap; Nicht-String
        oder leer → '' (Feld fällt beim Frame-Bau)."""
        if not isinstance(value, str):
            return ""
        return value.strip()[:cap]

    @classmethod
    def _status_sub_ok(cls, sub: Any) -> Optional[dict]:
        """sub-Gate: {id, name} je nicht-leer ≤64 (Relay-Vertrag), sonst None
        (Feld-Drop statt Frame-Verlust, Parität _agent_status_ok)."""
        if not isinstance(sub, dict):
            return None
        sid = cls._status_clip(sub.get("id"))
        sname = cls._status_clip(sub.get("name"))
        if not sid or not sname:
            return None
        return {"id": sid, "name": sname}

    def _cap_sub_slots(self) -> None:
        """FIFO-RAM-Schutz der Sub-Slot-Map (Aufruf NUR unter _status_lock)."""
        slots = self._status["sub_slots"]
        while len(slots) > _STATUS_SUB_SLOTS_CAP:
            slots.pop(next(iter(slots)))

    def _mark_recent_closed(self, child_session_id: str, now: int) -> None:
        """Doppel-Stop-Band füttern (Aufruf NUR unter _status_lock): child_session_ids
        mit bereits gesendetem CLOSE werden beim nächsten Stop still übersprungen
        (Relayslot-Verband ist je Frame idempotent, aber kein Rauschen); FIFO-Cap."""
        recent = self._status["recent_closed"]
        recent[child_session_id] = now
        while len(recent) > 64:
            recent.pop(next(iter(recent)), None)

    async def emit_agent_status(self, working: bool, label: Optional[str] = None,
                                sub: Optional[dict] = None, end: bool = False,
                                started_at: Optional[int] = None) -> bool:
        """W19: agent.status-Frame über die bestehende Agent-WS — fail-soft.

        enc-first (emit_models-Parität): aktive E2E-Session → 'agent.status.enc'
        (Klar-Spiegel working/started_at/label/sub/end + opaker ct über
        encrypt_frame; chat_id "" — der Status lebt JE AGENT, der Relay hat
        KEINEN Owner-Claim), ohne Session Klartext 'agent.status' (Relay-
        Downgrade-Schutz würde Klartext sonst verwerfen). end geht NUR als
        Slot-Verband (mit sub IMMER; OPEN end=False, CLOSE end=True) — reine
        parent-Frames ohne end halten den working-Dedup des Relays intakt.
        Rückgabe gesendet (True/False); Fehler werfen NIE.
        """
        if not self._connected or self._ws is None:
            return False
        mirror: dict = {
            "working": bool(working),
            "started_at": (int(started_at) if started_at is not None
                           else self._status_now_ms()),
        }
        label = self._status_clip(label)
        if label:
            mirror["label"] = label
        sub_ok = self._status_sub_ok(sub)
        if sub_ok is not None:
            mirror["sub"] = sub_ok
        if sub_ok is not None or end:
            mirror["end"] = bool(end)
        status_frames = self._enc_fanout_frames(
            AGENT_STATUS_ENC_FRAME, "", dict(mirror), clear=dict(mirror))
        if status_frames is None:
            status_frames = [{
                "type": AGENT_STATUS_FRAME, "v": _PROTOCOL_VERSION,
                "agent_id": self.agent_id, **mirror,
            }]
        sent = False
        # B3-FAN-OUT: Status je Geräte-Session (working bleibt klar je Frame —
        # Relay-Dedup-Verband; Fehler brechen nie den Status-Verband).
        for status_env in status_frames:
            try:
                # Send-Gate 0,5 s: ein hängender WS-Schreiber darf den Status-
                # Verband (und über die Coalesce-Drain den Hook-Pfad) nie festnageln.
                await asyncio.wait_for(self._send_json(status_env), _STATUS_SEND_TIMEOUT_S)
                sent = True
            except Exception as exc:
                logger.warning("FlipsiBridge: agent.status-Send fehlgeschlagen: %s", exc)
        return sent

    def _schedule_status_frames(self, frames: Optional[list[dict]]) -> None:
        """Hook-Them: Status-Frames auf den Adapter-WS-Loop legen (nie blockierend —
        pre_tool_call ist FAIL-CLOSED, Callbacks reine in-memory Dicts).

        Coalesce-Cap: max 1 offener Send-Drain je Adapter. Läuft bereits einer,
        hängen neue Frames an dessen Queue (Reihenfolge bleibt); sonst startet
        genau ein neuer Drain. Deskriptoren tragen die emit_agent_status-Args
        (working/label/sub/end/started_at) — der Drain liest sie unverändert,
        kein State-Reread-Race.
        """
        if not frames or not self._connected or self._status["loop"] is None:
            return
        with self._status_lock:
            pending = self._status["pending"]
            if pending is not None:
                for frame in frames:
                    pending.append(dict(frame))
                return
            queue: deque = deque(dict(frame) for frame in frames)
            self._status["pending"] = queue
            loop = self._status["loop"]
        try:
            future = asyncio.run_coroutine_threadsafe(
                self._status_drain_coro(queue), loop)
        except Exception:
            logger.warning("FlipsiBridge: agent.status-Scheduling fehlgeschlagen",
                           exc_info=True)
            with self._status_lock:
                if self._status["pending"] is queue:
                    self._status["pending"] = None
            return
        self._status["fut"] = future

    async def _status_drain_coro(self, pending: deque) -> None:
        """W19-Drain: sendet gesammelte Status-Frames je Reihenfolge, wartet auf
        keine weiteren; das (working, label)-Dedup lebt am RELAY (J4-Fund-3-
        Verband), der Drain schickt JE Frame real heraus. Send-Gate je Frame =
        _STATUS_SEND_TIMEOUT_S."""
        try:
            while True:
                with self._status_lock:
                    if not pending:
                        if self._status["pending"] is pending:
                            self._status["pending"] = None
                        break
                    frame = pending.popleft()
                try:
                    await self.emit_agent_status(**frame)
                except Exception:
                    logger.warning("FlipsiBridge: agent.status-Drain-Fehler",
                                   exc_info=True)
                # J4-Fund 4: KEIN sent_working/sent_label-Band — die Dedup-
                # Wahrheit über working/label lebt am RELAY (J4-Fund-3-Verband,
                # (working, label)-Paar je agent_id); das alte Adapter-Band
                # war ausschließlich schreibend (toter State).
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.warning("FlipsiBridge: agent.status-Drain-Abbruch", exc_info=True)
        finally:
            # Safety-Net: Drain beendet/abgebrochen → Coalesce-Stand frei.
            with self._status_lock:
                if self._status["pending"] is pending:
                    self._status["pending"] = None

    async def _drain_status_pending(self, timeout: float = 2.0) -> None:
        """Test-/Shutdown-Helfer: auf den laufenden Coalesce-Drain warten (fail-soft)."""
        fut = self._status["fut"]
        if fut is None:
            return None
        try:
            await asyncio.wait_for(asyncio.wrap_future(fut), timeout)
        except Exception:
            logger.debug("FlipsiBridge: Status-Drain-Wait fehlgeschlagen/leer",
                         exc_info=True)
        return None

    @staticmethod
    def _status_event_fields(event: Any) -> tuple[str, str]:
        """(platform, text) aus einem pre_gateway_dispatch-Event — objekt- oder
        dict-förmig toleriert (Gateway-MessageEvent vs. Test-Injektion)."""
        if event is None:
            return "", ""
        if isinstance(event, dict):
            source = event.get("source")
            text = event.get("text")
        else:
            source = getattr(event, "source", None)
            text = getattr(event, "text", None)
        if isinstance(source, dict):
            platform = source.get("platform")
        else:
            platform = getattr(source, "platform", None)
        platform = getattr(platform, "value", platform)
        return str(platform or "").strip().lower(), str(text or "")

    def _status_slot_name(self, child_role: str, child_goal: str) -> str:
        """Slot-Name (≤64): goal (Volltext gekappt); sonst Rolle; sonst 'Subagent'
        (Verband: 'goal[:64] oder Rolle+goal-kurz')."""
        goal = self._status_clip(child_goal)
        if goal:
            return goal
        role = self._status_clip(child_role)
        return (role.capitalize() or "Subagent")[:_STATUS_LABEL_CAP]

    # --- Hook-Callbacks (Hermes-Lifecycle, je Modul-Level-Fächer) -----------
    # Kontrakt je Callback: async def **kwargs, try/except-suppress rundum
    # (im Fächer), reine in-memory-Verbandsarbeit + _schedule_status_frames,
    # Rückgabe IMMER None.

    # ───────────────── F1 (Sir, 18.11.2026): cron.status-Live-Verband ─────────
    # Geplante/laufende Cron-Jobs des GATEWAYS → App (Karte „Geplante Aufgaben“,
    # APP-GENERISCH — die App kennt keinen Agent-Namen im Frame). Quelle:
    # <hermes_home>/cron/jobs.json (read-only + TTL, die gleiche SSOT, auf der
    # der Gateway-Change-Watcher feuert). Sende-Mechanik = agent.status:
    # enc-first je Geräte-Session, Send-Gate, Sends je Connect/Handshake/
    # Reconnect + Change-Detect-Refresh (nur bei geändertem Zeilen-Stand).

    @staticmethod
    def _cron_store_default_path() -> str:
        """Hermes-Home des aktiven Profils → cron/jobs.json (fail-soft Fallback
        auf ~/.hermes — Parität zum Gateway-Observer-Schema)."""
        try:
            from hermes_cli.config import get_hermes_home  # noqa: PLC0415
            return os.path.join(get_hermes_home(), "cron", "jobs.json")
        except Exception:
            return os.path.join(os.path.expanduser("~"), ".hermes",
                                "cron", "jobs.json")

    def _load_cron_jobs_raw(self) -> list:
        """Cron-Store read-only lesen (fail-soft, TTL-Cache _CRON_STORE_TTL_S).
        Kein Schreibzugriff, keine DB-Locks: JSON-Snapshot. Fehler/unlesbar =
        leere Liste (kein Job erfunden)."""
        now = time.monotonic()
        expires, cached = self._cron_cache
        if now < expires:
            return cached
        path = self._cron_store_override or self._cron_store_default_path()
        jobs: list = []
        try:
            with open(path, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            if isinstance(data, dict):
                raw = data.get("jobs")
                if isinstance(raw, list):
                    jobs = [j for j in raw if isinstance(j, dict)]
            elif isinstance(data, list):
                jobs = [j for j in data if isinstance(j, dict)]
        except (OSError, ValueError):
            logger.debug("FlipsiBridge: cron-Store nicht lesbar (%s)", path)
        except Exception:
            logger.debug("FlipsiBridge: cron-Store-Lesefehler (%s)", path)
        self._cron_cache = (now + _CRON_STORE_TTL_S, jobs)
        return jobs

    @staticmethod
    def _cron_row(job: dict) -> Optional[dict]:
        """Ein Store-Job → App-Zeile {name, status, next_at?}. Ehrlich: nur
        Bekanntes bleibt; completed/deaktiviert fliegt (keine laufende/geplante
        Aufgabe), paused trägt NIE next_at (altgeparkte Stamps sind gefälschte
        „demnächst“-Angaben)."""
        name = str(job.get("name") or "").strip()
        if not name:
            return None
        enabled = job.get("enabled")
        if enabled is False:
            return None
        state = str(job.get("state") or "").strip().lower()
        if job.get("fire_claim"):
            status = "running"            # worker hält einen Claim am Job
        elif state == "paused" or job.get("paused_at"):
            status = "paused"
        elif state in ("", "scheduled", "pending", "queued"):
            status = "planned"
        elif state == "running":
            status = "running"
        else:
            return None                   # completed/unbekannt → Zeile weg
        row: dict = {"name": name[:_CRON_STATUS_NAME_CAP], "status": status}
        next_ms = _parse_iso_to_ms(job.get("next_run_at"))
        if status == "planned" and next_ms is not None:
            row["next_at"] = int(next_ms)
        return row

    def _cron_rows(self) -> list[dict]:
        """Store → geordnete Zeilen: running zuerst (kein next_at), dann geplant
        nach nächster Ausführung, paused zuletzt; Cap _CRON_STATUS_JOBS_MAX."""
        rows = [r for r in (self._cron_row(j) for j in self._load_cron_jobs_raw())
                if r is not None]
        rows.sort(key=lambda r: (_CRON_STATES_ORDER.get(r["status"], 3),
                                 r.get("next_at") or (1 << 62)))
        return rows[:_CRON_STATUS_JOBS_MAX]

    @staticmethod
    def _cron_fingerprint(rows: list[dict]) -> str:
        """Zeilen-Stand als stabiler JSON-Fingerprint (Change-Detect der
        Refresh-Loop — unveränderter Stand = kein Frame, spart Rate-Plätze)."""
        try:
            return json.dumps(rows, sort_keys=True, separators=(",", ":"),
                              ensure_ascii=False)
        except Exception:
            return ""

    async def emit_cron_status(self) -> bool:
        """cron.status-Frame über die bestehende Agent-WS — fail-soft.

        Mechanik = emit_agent_status (F1-Verband): enc-first (E2E-Session →
        NUR 'cron.status.enc' mit Payload {jobs: …} im ct; Klartext wirft der
        App-Downgrade-Guard weg), ohne Session Klartext 'cron.status'; Frame
        lebt je AGENT (kein chat_id-Feld — APP-GENERISCH). Rückgabe gesendet;
        Erfolg aktualisiert das Change-Detect-Band (_cron_sent_fp).
        """
        if not self._connected or self._ws is None:
            return False
        rows = self._cron_rows()
        fp = self._cron_fingerprint(rows)
        payload = {"jobs": rows}
        frames = self._enc_fanout_frames(CRON_STATUS_ENC_FRAME, "",
                                         dict(payload))
        if frames is None:
            frames = [{
                "type": CRON_STATUS_FRAME, "v": _PROTOCOL_VERSION,
                "agent_id": self.agent_id, **payload}]
        sent = False
        for status_env in frames:
            try:
                await asyncio.wait_for(self._send_json(status_env),
                                       _CRON_STATUS_SEND_TIMEOUT_S)
                sent = True
            except Exception as exc:
                logger.warning("FlipsiBridge: cron.status-Send fehlgeschlagen: %s",
                               exc)
        if sent:
            self._cron_sent_fp = fp
        return sent

    async def _cron_refresh_loop(self) -> None:
        """F1-Refresh: alle cron_refresh_s den Zeilen-Stand prüfen — nur bei
        Änderung (Fingerprint ≠ zuletzt REAL gesendet) geht ein Frame raus
        (relative „in X min“-Anzeigen am Phone rechnen je Ticker; das Frame
        trägt die DATEN, nicht die Uhr). Start-Verzögerung = cron_refresh_s
        (je Connect sendet connect() bereits sofort). Fail-soft, stirbt mit
        disconnect()."""
        while True:
            await asyncio.sleep(self.cron_refresh_s)
            try:
                fp = self._cron_fingerprint(self._cron_rows())
                if fp == self._cron_sent_fp:
                    continue
                await self.emit_cron_status()
            except Exception:
                logger.warning("FlipsiBridge: cron.status-Refresh fehlgeschlagen",
                               exc_info=True)

    # ───────────────── F3 (Sir, offener Verband seit W19): chat.session_meta ──
    # push_session_meta (Entry-Point des flipsi-session-meta-Gateway-Hooks)
    # + P40-Verband (Meta-Push-Ziele mit Persistenz) + Nachliefer-Queue:
    # Nicht verbundene / fehlgeschlagene Pushes werden je chat_id REPLACE-
    # gebunden und nach dem Reconnect nachgeliefert (statt Skip/Verlust).

    @classmethod
    def live_instances(cls) -> list:
        """LIVE-Adapter-Instanzen (WeakSet-Snapshot, Parität zum hermes-android-
        plugin) für den Gateway-Observer: der Hook findet die Klasse über den
        Loader-Namespace und fannen die Pushes an diese Instanz-Liste — Connect-
        Filter macht der Aufrufer (is_connected-Verband)."""
        return list(STATUS_ADAPTERS)

    def _chats_state_path(self):
        """Persistente Chat-Ziele je Agent (P40): <state-dir>/<agent>.chats.json.
        Default = ~/.flipsibridge/chat-state (Parität zum media_dir-Verband des
        Adapters — bewusst NICHT im Plugin-Dir, sonst kippen Repo-Checkouts
        Schreibartefakte in den Baum); Override FLIPSI_META_STATE_DIR (Tests/
        isoliertes Profil). Fail-soft: alle Fehler der Schreib-/Lese-Schiene =
        debug-Log."""
        safe = re.sub(r"[^A-Za-z0-9_-]", "_", str(self.agent_id))[:48] or "agent"
        base = self._meta_state_dir or os.path.join(
            os.path.expanduser("~"), ".flipsibridge", "chat-state")
        return os.path.join(base, safe + ".chats.json")

    # ───────────── PH-4-T2: Relay-Instanz-Identität (Ed25519, signed) ───────
    # Der Relay instanziert EINEN Ed25519-Key (persistent am Relay; die
    # Identity-Datei verlässt den Relay-Host nicht) und aushändigt je Connect
    # die agent.ident-Nachricht {relay_id, verify_key, ts, sig}. Diese Seite:
    # (1) Konsistenz (relay_id == SHA-256(verify_key)), (2) Sig-Verifikation
    # (kanonische Bytes PARITÄT tools/android_relay.py _canonical_meta_bytes),
    # (3) TOFU-Pin (Fingerprint-Ablage je Relay-URL im Adapter-State — der
    # Brief nennt „relay-config.json"; die Wahrheit am Code: die Adapter-Seite
    # hat keinen verlässlichen Zugriff auf die Relay-Config, deshalb lebt der
    # Pin hier in relay-identity-pins.json je URL, gleiche Verantwortung).
    # Pin-WECHSEL = potenzieller MITM-Hinweis: WARN-Log, bewusst NICHT hart
    # blocken (v1 — die Erst-Einführung darf keinen laufenden Betrieb brechen;
    # jede härtere Scharfe (Fail-Closed bei Pin-Konflikt/Pin-Pflicht) ist ein
    # bewusster W23-Kandidat). Frame-Signaturen: crypto.hello fail-soft prüfen
    # mit Warn-Log + Zähler; fehlende Signatur (Legacy-Relay) = Zählerband,
    # KEIN Verlust.
    def _relay_ident_pin_path(self) -> Optional[str]:
        """Ablage der TOFU-Pins: Override FLIPSI_RELAY_IDENTITY_PINS (Tests/
        isoliert) sonst <Adapter-State>/relay-identity-pins.json (gleiche
        State-Verband wie _chats_state_path; „je relay-config.json" = je
        Relay-Ziel ein Eintrag, key = origin <host:port>)."""
        if self._relay_pin_path_override:
            return self._relay_pin_path_override
        safe_base = self._meta_state_dir or os.path.join(
            os.path.expanduser("~"), ".flipsibridge", "chat-state")
        return os.path.join(safe_base, "relay-identity-pins.json")

    @staticmethod
    def _relay_origin(url: str) -> str:
        """Origin-Schlüssel (host[:port]) einer wss/ws-URL — je Relay-Instanz
        EIN Pin-Slot (Scheme-agnostisch: derselbe Host über wss/ws bleibt
        derselbe Relay-Ops-Verband)."""
        m = re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)", str(url or "").strip())
        return m.group(1).lower() if m else "unbekannt"

    def _relay_pins_load(self) -> dict:
        try:
            path = self._relay_ident_pin_path()
            if path and os.path.exists(path):
                with open(path, "r", encoding="utf-8") as fh:
                    data = json.load(fh)
                return dict(data) if isinstance(data, dict) else {}
        except Exception:
            logger.debug("FlipsiBridge: Relay-Pin-File unlesbar (TOFU neutral)", exc_info=True)
        return {}

    def _relay_pins_save(self, pins: dict) -> None:
        try:
            path = self._relay_ident_pin_path()
            if not path:
                return
            os.makedirs(os.path.dirname(path), exist_ok=True)
            tmp_fd, tmp_path = tempfile.mkstemp(
                dir=os.path.dirname(path), prefix=".relay-pins-", suffix=".tmp")
            with os.fdopen(tmp_fd, "w", encoding="utf-8") as fh:
                json.dump(pins, fh)
            os.replace(tmp_path, path)
        except Exception:
            logger.debug("FlipsiBridge: Relay-Pin-File nicht schreibbar (TOFU-RAM)", exc_info=True)

    def _handle_relay_ident(self, envelope: dict) -> None:
        """agent.ident verifizieren + TOFU-pinnen (fail-soft): ungültige Form /
        Signatur = Zähler + Warn, Frame verworfen (KEIN Pin-Write!); gültig →
        Pin-Verband (Erst-Pin = TOFU, Abweichung = WARN ohne Block v1)."""
        verify_key = str(envelope.get("verify_key") or "").strip().lower()
        relay_id = str(envelope.get("relay_id") or "").strip().lower()
        sig = str(envelope.get("sig") or "").strip()
        hex_ok = bool(re.fullmatch(r"[0-9a-f]{64}", verify_key)) and \
            bool(re.fullmatch(r"[0-9a-f]{64}", relay_id))
        if not (hex_ok and len(verify_key) == 64 and len(relay_id) == 64):
            self._relay_identity_stats["ident_invalid"] += 1
            logger.warning("FlipsiBridge: agent.ident ungültige Form — verworfen")
            return
        # Konsistenz-Band: relay_id ist DEFINIERT als SHA-256(verify_key).
        if hashlib.sha256(bytes.fromhex(verify_key)).hexdigest() != relay_id:
            self._relay_identity_stats["ident_invalid"] += 1
            logger.warning(
                "FlipsiBridge: agent.ident inkonsistent (relay_id ≠ SHA-256(verify_key)) — verworfen")
            return
        bare = {k: v for k, v in envelope.items() if k != "sig"}
        if not _relay_verify_sig(bare, verify_key, sig):
            self._relay_identity_stats["ident_invalid"] += 1
            logger.warning(
                "FlipsiBridge: agent.ident-Signatur UNGÜLTIG — verworfen (kein Pin-Write)")
            return
        origin = self._relay_origin(self.relay_url)
        pins = self._relay_pins_load()
        pin = pins.get(origin)
        if pin is None:
            pins[origin] = {
                "relay_id": relay_id, "verify_key": verify_key,
                "pinned_at": int(time.time()), "url": self.relay_url,
                "mode": "tofu",
            }
            self._relay_pins_save(pins)
            logger.info(
                "FlipsiBridge: Relay-Identität TOFU-gepinnt (origin=%s relay_id=%s)",
                origin, relay_id[:16] + "…")
        elif str(pin.get("relay_id") or "") != relay_id:
            # Pin-Wechsel = potenzieller MITM-Hinweis (Relay-Key-Rotation ODER
            # Angriff). v1: WARN + Zähler, NICHT blocken (W23: Fail-Closed).
            self._relay_identity_stats["ident_pin_conflict"] += 1
            logger.warning(
                "FlipsiBridge: RELAY-IDENTITÄTS-WECHSEL am origin %s — gepinnt %s, "
                "jetzt %s — potenzieller MITM-Hinweis (Pin bleibt, NICHT blockiert v1)",
                origin, str(pin.get("relay_id"))[:16] + "…", relay_id[:16] + "…")
        self._relay_identity_stats["ident_ok"] += 1
        self._relay_verify_key = bytes.fromhex(verify_key)

    def _relay_verify_frame_sig(self, envelope: dict) -> None:
        """Fail-soft-Sig-Verifikation relay-gesendeter Frames (v1: crypto.hello
        = Handshake-Verband). Sig fehlt (Legacy-Relay) → Zählerband, kein
        Verlust; Sig bad → Warn + Zähler, Frame bleibt trotzdem im Betrieb
        (KEIN Operationsbruch v1 — harte Scharfe = W23-Kandidat)."""
        if self._relay_verify_key is None:
            return  # noch keine agent.ident gesehen → kein Verifikationsband
        sig = envelope.get("sig")
        if not isinstance(sig, str) or not sig:
            self._relay_identity_stats["sig_missing"] += 1
            return
        bare = {k: v for k, v in envelope.items() if k != "sig"}
        try:
            from cryptography.hazmat.primitives.asymmetric.ed25519 import (
                Ed25519PublicKey,
            )
            Ed25519PublicKey.from_public_bytes(self._relay_verify_key).verify(
                base64.b64decode(sig, validate=True),
                _relay_canonical_bytes(bare))
            self._relay_identity_stats["sig_ok"] += 1
        except Exception:
            self._relay_identity_stats["sig_bad"] += 1
            logger.warning(
                "FlipsiBridge: Relay-Framesignatur UNGÜLTIG (fail-soft, Zählerband) — typ=%r",
                str(envelope.get("type") or "")[:24])

    def _persist_chats(self, chat_id: str) -> None:
        """Aktuelles _received_at (neueste zuerst, Cap 32) je Agent speichern.
        Fail-soft: jeder Fehler = debug-Log, nie ein Push-Block (P40)."""
        try:
            path = self._chats_state_path()
            os.makedirs(os.path.dirname(path), exist_ok=True)
            ids = [c for c, _ in sorted(self._received_at.items(),
                                        key=lambda item: item[1], reverse=True)]
            if chat_id not in ids:
                ids.insert(0, chat_id)
            with open(path, "w", encoding="utf-8") as fh:
                json.dump({"chats": ids[:32], "updated_at": int(time.time())}, fh)
        except Exception as exc:
            logger.debug("FlipsiBridge: Chat-Ziele-Persistenz fehlgeschlagen: %s",
                         exc)

    def _persisted_chats(self) -> list:
        """Persistierte Chat-IDs lesen (fail-soft: [] = nicht lesbar — kein Ziel
        aus einer kaputten Datei)."""
        try:
            with open(self._chats_state_path(), "r", encoding="utf-8") as fh:
                data = json.load(fh)
            chats = data.get("chats") if isinstance(data, dict) else None
            if isinstance(chats, list):
                return [str(c) for c in chats if str(c).strip()]
        except Exception:
            pass
        return []

    def meta_push_targets(self) -> list:
        """Wire-Chat-IDs der Phone-Chats — Push-Ziele für Gateway-Hooks (P40):
        neueste Empfangsaktivität zuerst, dann Gateway-Map, dann Persistenz
        (überlebt Gateway-Restarts). Read-only, dedup; ohne Bekanntes: leere
        Liste (Hook pusht dann nichts — keine Chat-ID erfunden)."""
        targets: list = []
        seen: set = set()
        try:
            for chat_id, _ts in sorted(self._received_at.items(),
                                       key=lambda item: item[1], reverse=True):
                if chat_id not in seen:
                    seen.add(chat_id)
                    targets.append(chat_id)
        except Exception:  # pragma: no cover — defekte Map = kein Ziel aus ihr
            pass
        try:
            for chat_id in self._gw_chat_by_relay:
                if chat_id not in seen:
                    seen.add(chat_id)
                    targets.append(chat_id)
        except Exception:  # pragma: no cover
            pass
        try:
            for chat_id in self._persisted_chats() or []:
                if chat_id not in seen:
                    seen.add(chat_id)
                    targets.append(chat_id)
        except Exception:  # pragma: no cover
            pass
        return targets

    @staticmethod
    def _session_meta_clip(value: Any) -> str:
        """String-Gate der Meta-Felder: trim + cap; Nicht-String = '' (Feld
        fällt beim Meta-Bau — kein Fake-Wert)."""
        if not isinstance(value, str):
            return ""
        return value.strip()[:_SESSION_META_STR_CAP]

    def _build_session_meta(self, platform: Any, model: Any,
                            sub_agents: Optional[list]) -> dict:
        """Kanonisches Meta (REPLACE-Semantik — was hier nicht steht, fehlt am
        Phone): platform/model je bekannt ≥1 Zeichen, sub_agents [{name, status?,
        model?}] (name Pflicht, Cap 8 = Hook-Parität)."""
        meta: dict = {}
        platform_clipped = self._session_meta_clip(platform)
        if platform_clipped:
            meta["platform"] = platform_clipped
        model_clipped = self._session_meta_clip(model)
        if model_clipped:
            meta["model"] = model_clipped
        subs: list = []
        for entry in sub_agents or []:
            if not isinstance(entry, dict):
                continue
            name = self._session_meta_clip(entry.get("name"))
            if not name:
                continue  # Name-freie Einträge fliegen (Phone: Zeile weg)
            sub: dict = {"name": name}
            status = self._session_meta_clip(entry.get("status"))
            if status:
                sub["status"] = status
            sub_model = self._session_meta_clip(entry.get("model"))
            if sub_model:
                sub["model"] = sub_model
            subs.append(sub)
            if len(subs) >= _SESSION_META_MAX_SUB_AGENTS:
                break
        if subs:
            meta["sub_agents"] = subs
        return meta

    def _meta_enqueue(self, chat_id: str, meta: dict) -> None:
        """F3-Kern: Push in die Nachliefer-Queue — REPLACE je chat_id (neueste
        Meta gewinnt), FIFO-Cap wirft ÄLTESTE Einträge raus (RAM-Schutz;
        Attempts-Stand wird übernommen)."""
        with self._meta_lock:
            previous = self._meta_queue.get(chat_id)
            attempts = previous[1] if previous else 0
            self._meta_queue[chat_id] = (dict(meta), attempts)
            while len(self._meta_queue) > _SESSION_META_QUEUE_CAP:
                self._meta_queue.pop(next(iter(self._meta_queue)))

    def _meta_requeue_failed(self, chat_id: str, meta: dict,
                             attempts: int) -> None:
        """Send-Misserfolg → zurück in die Queue mit attempts+1 (Cap
        _SESSION_META_SEND_RETRY_MAX dann verwerfen — keine Ewig-Queue).
        attempts MUSS der Vor-POP-Stand des Drains sein: der Drain nimmt den
        Entry VOR dem Send aus der Queue, der Zähler überlebt nur, wenn er
        übergeben wird (sonst zählt jeder Versuch wieder bei 1 → Ewig-Queue)."""
        new_attempts = (attempts or 0) + 1
        with self._meta_lock:
            if new_attempts > _SESSION_META_SEND_RETRY_MAX:
                logger.warning("FlipsiBridge: session_meta-Nachlieferung verworfen "
                               "(chat=%s nach %d Fehlversuchen)",
                               chat_id[:24], new_attempts)
                return
            self._meta_queue[chat_id] = (dict(meta), new_attempts)
            while len(self._meta_queue) > _SESSION_META_QUEUE_CAP:
                self._meta_queue.pop(next(iter(self._meta_queue)))

    async def _meta_send_one(self, chat_id: str, meta: dict) -> bool:
        """EIN Meta-Push enc-first auf den aktuellen Stand (Parität
        emit_agent_status): mit Geräte-Session NUR chat.session_meta.enc
        (Payload NUR im ct — der Phone-Payload-Parser liest es, der Downgrade-
        Guard würde Klartext verwerfen), ohne Session Klartext chat.session_meta
        mit ts. Fehler brechen NIE den Aufrufer (Status ≠ Inhalt)."""
        wire_chat_id = self._relay_chat_id_for(chat_id)
        frames = self._enc_fanout_frames(
            SESSION_META_ENC_FRAME, wire_chat_id, {"session_meta": dict(meta)})
        if frames is None:
            frames = [{
                "type": SESSION_META_FRAME, "v": _PROTOCOL_VERSION,
                "agent_id": self.agent_id, "chat_id": wire_chat_id,
                "session_meta": dict(meta), "ts": int(time.time() * 1000)}]
        sent = False
        for meta_env in frames:
            try:
                await asyncio.wait_for(self._send_json(meta_env),
                                       _STATUS_SEND_TIMEOUT_S)
                sent = True
            except Exception as exc:
                logger.warning("FlipsiBridge: chat.session_meta-Send fehlgeschlagen "
                               "(chat=%s): %s", chat_id[:24], exc)
        return sent

    async def _meta_drain_coro(self) -> None:
        """F3-Nachliefer-Drain: Queue abarbeiten je Reihenfolge; je Eintrag ein
        Send mit AKTUELLEM enc-Stand (auch Einträge, die während Disconnect als
        Klartext-Ziel geparkt wurden, gehen nach Session-Etablierung als enc).
        Erfolg = Entry raus; Misserfolg = Attempts+1 (Cap → verwerfen — der
        Attempt-Stand ÜBERLEBT den Pop, sonst rotiert der Drain ewig). Ohne
        WS: stoppt (Queue bleibt — der nächste connect()/Reconnect plant
        neu)."""
        while self._connected and self._ws is not None:
            with self._meta_lock:
                if not self._meta_queue:
                    return
                chat_id, (meta, attempts) = next(iter(self._meta_queue.items()))
                del self._meta_queue[chat_id]
            ok = await self._meta_send_one(chat_id, meta)
            if ok:
                continue
            if not self._connected or self._ws is None:
                self._meta_enqueue(chat_id, meta)  # Attempts-Reset egal: frischer Start
                return
            self._meta_requeue_failed(chat_id, meta, attempts)
            await asyncio.sleep(0.05)

    def _schedule_meta_drain(self) -> None:
        """F3: Nachliefer-Drain auf den Adapter-WS-Loop legen (coalesce: max 1
        offener Drain; ein laufender liest die Queue frisch weiter). Ohne Loop:
        Queue bleibt — kein Schedul-Fehler, der connect()-Pfad plant nach."""
        loop = self._status.get("loop")
        if loop is None or not self._connected:
            return
        with self._meta_lock:
            if not self._meta_queue:
                return
            if self._meta_fut is not None and not self._meta_fut.done():
                return
        try:
            fut = asyncio.run_coroutine_threadsafe(self._meta_drain_coro(), loop)
        except Exception:
            logger.warning("FlipsiBridge: session_meta-Nachliefer-Scheduling "
                           "fehlgeschlagen", exc_info=True)
            return
        with self._meta_lock:
            self._meta_fut = fut

    async def drain_meta_queue(self) -> None:
        """Test-Hook: den Nachliefer-Drain synchron ausführen (kein Scheduling)."""
        await self._meta_drain_coro()

    async def push_session_meta(
        self,
        chat_id: str,
        *,
        platform: Any = None,
        model: Any = None,
        sub_agents: Optional[list] = None,
    ) -> bool:
        """F3 (P40-Verband nachziehen): Session-Meta eigenständig ans Phone
        drücken — Entry-Point der Gateway-Side-Hooks (platform/model/sub_agents,
        REPLACE-Semantik: was None ist, fehlt am Phone → Zeile weg).

        F3-Fix des W19-Skips: ohne Verbindung (oder bei Send-Fehlschlag) geht
        die Meta NICHT verloren — sie wird je chat_id gebunden und nach dem
        Reconnect nachgeliefert ([_schedule_meta_drain] an connect/hello_ack/
        Frame-Loop-Reconnect). enc-first wie chat.activity-Parität. Rückgabe:
        gesendet (True) — geparkt/niete = False; Fehler werfen NIE.
        """
        meta = self._build_session_meta(platform, model, sub_agents)
        chat_id = str(chat_id or "").strip()
        if not meta or not chat_id:
            return False
        if not self._connected or self._ws is None:
            self._meta_enqueue(chat_id, meta)   # F3: Queue statt Skip
            return False
        sent = await self._meta_send_one(chat_id, meta)
        if not sent:
            self._meta_enqueue(chat_id, meta)   # F3: Send-Fail = Queue statt Verlust
        return sent

    async def hook_pre_gateway_dispatch(self, **kwargs: Any) -> None:
        """W19: parent ON bei Whitelist-Text-Event (flipsibridge/discord/
        telegram); bereits working → KEIN Reset (Label/Herzschlag bleiben).
        Policy-Hook: Rückgabe IMMER None (nichts skippen/rewriten)."""
        platform, text = self._status_event_fields(kwargs.get("event"))
        if platform not in _STATUS_PLATFORM_WHITELIST or not text.strip():
            return None
        now = self._status_now_ms()
        frames: list[dict] = []
        with self._status_lock:
            if not self._status["working"]:
                self._status["working"] = True
                self._status["started_at"] = now
                self._status["label"] = None
                self._status["web_count"] = 0
                frames.append({"working": True, "label": None, "started_at": now})
        self._schedule_status_frames(frames)
        return None

    async def hook_pre_tool_call(self, **kwargs: Any) -> None:
        """W19: Label-Refresh 'Tool: <name>' (web-Tools → fester Label 'Sucht im
        Web' + web-Verband-Zähler +1) sowie parent ON bei Scope-Treffer
        (gelernte eigene Session — Folge-/Cron-Turns ohne Inbound-Text).
        pre_tool_call ist FAIL-CLOSED: NUR Dict-Operationen, kein await/IO."""
        tool_name = self._status_clip(kwargs.get("tool_name"))
        if not tool_name:
            return None
        session_id = str(kwargs.get("session_id") or "").strip()
        is_web = tool_name in _STATUS_WEB_TOOLS
        if is_web:
            label = _STATUS_WEB_LABEL
        else:
            label = self._status_clip(f"Tool: {tool_name}")
            if not label:
                label = "Tool"
        now = self._status_now_ms()
        frames: list[dict] = []
        with self._status_lock:
            st = self._status
            if st["working"]:
                if is_web:
                    st["web_count"] += 1
                st["label"] = label
                frames.append({"working": True, "label": label,
                               "started_at": st["started_at"]})
            elif session_id and session_id in st["parent_sessions"]:
                st["web_count"] = 1 if is_web else 0
                st["working"] = True
                st["started_at"] = now
                st["label"] = label
                frames.append({"working": True, "label": label, "started_at": now})
        self._schedule_status_frames(frames)
        return None

    async def hook_post_tool_call(self, **kwargs: Any) -> None:
        """W19: web-Verband-Zähler zurücknehmen (KEIN Frame — das Label flippt
        erst beim nächsten pre_tool_call zurück); 'delegate_task' wird NIE als
        Ende gewertet (Slots/Turn-Events schließen)."""
        tool_name = self._status_clip(kwargs.get("tool_name"))
        if tool_name == "delegate_task":
            return None
        if tool_name in _STATUS_WEB_TOOLS:
            with self._status_lock:
                if self._status["web_count"] > 0:
                    self._status["web_count"] -= 1
        return None

    async def hook_subagent_start(self, **kwargs: Any) -> None:
        """W19: Sub-Slot OPEN — emit working=True sub={id: child_subagent_id,
        name: goal[:64] bzw. Rolle} end=False. Join-/Slot-Schlüssel =
        child_session_id; Scope = gelernte eigene Parent-Session (fremde
        Plattform-Subagents falten den Status nicht)."""
        child_session_id = str(kwargs.get("child_session_id") or "").strip()
        child_subagent_id = str(kwargs.get("child_subagent_id") or "").strip()
        if not child_session_id or not child_subagent_id:
            return None
        parent_session_id = str(kwargs.get("parent_session_id") or "").strip()
        now = self._status_now_ms()
        frames: list[dict] = []
        with self._status_lock:
            st = self._status
            if parent_session_id and parent_session_id not in st["parent_sessions"]:
                return None  # Scope: fremde Plattform — still überspringen
            slot = {
                "id": self._status_clip(child_subagent_id),
                "name": self._status_slot_name(str(kwargs.get("child_role") or ""),
                                               str(kwargs.get("child_goal") or "")),
                "started_at": now,
            }
            st["sub_slots"][child_session_id] = slot
            self._cap_sub_slots()
            frames.append({"working": True, "sub": {"id": slot["id"],
                                                    "name": slot["name"]},
                           "end": False, "started_at": now})
        # Security-Fix-Welle 17.11.: Slot-Logs über _log_id — child_goal kann
        # Zeilenumbrüche tragen (Log-Forging im Journal); der FRAME bleibt
        # unverändert (Klartext-Name ist Protokoll-Daten fürs Phone).
        logger.info("FlipsiBridge: agent.status Sub-Slot OPEN id=%s name=%s",
                    _log_id(slot["id"]), _log_id(slot["name"]))
        self._schedule_status_frames(frames)
        return None

    async def hook_subagent_stop(self, **kwargs: Any) -> None:
        """W19: Sub-Slot CLOSE — emit working=False sub={id, name} end=True
        (started_at = Slot-Start; Phone rechnet die Laufzeit). child_status /
        duration_ms landen im Info-Log (keine Slot-Felder im Vertrag).
        child_session_id=None-Fallback: genau 1 offener Slot → dieser; sonst
        Slot-ID 'unknown'. JE CLOSE merkt die child_session_id (Doppel-Stop-
        Band) — ein zweiter Stop für dieselbe ID bleibt still."""
        child_session_id = str(kwargs.get("child_session_id") or "").strip()
        parent_session_id = str(kwargs.get("parent_session_id") or "").strip()
        child_role = str(kwargs.get("child_role") or "")
        child_status = self._status_clip(kwargs.get("child_status")) or "unknown"
        try:
            duration_ms = int(kwargs.get("duration_ms") or 0)
        except (TypeError, ValueError):
            duration_ms = 0
        now = self._status_now_ms()
        frames: list[dict] = []
        with self._status_lock:
            st = self._status
            if parent_session_id and parent_session_id not in st["parent_sessions"]:
                return None
            slot = None
            if child_session_id:
                slot = st["sub_slots"].pop(child_session_id, None)
                if slot is not None and child_session_id in st["recent_closed"]:
                    # Slot wurde neu geöffnet und ist wieder zu — Normallfall
                    # (Verband je Start/Stop-Paar), kein Doppel-Suppress.
                    del st["recent_closed"][child_session_id]
            elif len(st["sub_slots"]) == 1:
                slot = st["sub_slots"].pop(next(iter(st["sub_slots"])))
            if slot is not None:
                sub_desc = {"id": slot["id"], "name": slot["name"]}
                started_at = slot["started_at"]
                if child_session_id:
                    self._mark_recent_closed(child_session_id, now)
            elif child_session_id:
                if child_session_id in st["recent_closed"]:
                    logger.debug(
                        "FlipsiBridge: agent.status Sub-Slot CLOSE doppelt "
                        "unterdrückt (id=%s)", self._status_clip(child_session_id))
                    return None
                # Slot war nie offen (Scope-Lücke beim OPEN) — Close trotzdem
                # verbandstreu: child_session_id als Slot-ID, einmalig.
                self._mark_recent_closed(child_session_id, now)
                sub_desc = {"id": self._status_clip(child_session_id),
                            "name": self._status_slot_name(child_role, "")}
                started_at = now
            else:
                sub_desc = {"id": "unknown",
                            "name": self._status_slot_name(child_role, "")}
                started_at = now
            frames.append({"working": False, "sub": sub_desc, "end": True,
                           "started_at": started_at})
        logger.info("FlipsiBridge: agent.status Sub-Slot CLOSE id=%s status=%s "
                    "duration_ms=%d", _log_id(sub_desc["id"]), _log_id(child_status),
                    duration_ms)
        self._schedule_status_frames(frames)
        return None

    async def hook_on_session_end(self, **kwargs: Any) -> None:
        """W19: on_session_end feurt JE TURN (mehrfach!). platform='subagent' →
        NUR Herzschlag-Refresh (passenden offenen Slot auffrischen; KEIN Close —
        subagent_stop schließt). Eigenes Plattform-Value → parent OFF + Session
        lernen (Scope-Schlüssel für pre_tool_call/Subagent-Hooks). Fremde
        Plattform: weder lernen noch falten."""
        session_id = str(kwargs.get("session_id") or "").strip()
        # B2 (Flagship-Review 17.11.): Normalisierung SYMMETRISCH zum ON-Gate
        # (_status_event_fields lower()-t) — kapitalisierte Plattformwerte
        # (z.B. 'Discord') lernten sonst nie die Session und die Pill blieb
        # dauerhaft ON (Stuck-ON). Beide Gates nutzen dieselbe Kleinschreibung.
        platform = str(kwargs.get("platform") or "").strip().lower()
        if not session_id:
            return None
        now = self._status_now_ms()
        frames: list[dict] = []
        with self._status_lock:
            st = self._status
            if platform == "subagent":
                slot = st["sub_slots"].get(session_id)
                if slot is not None:
                    slot["started_at"] = now  # Herzschlag (kein Frame, kein Close)
                    st["started_at"] = now    # Herzschlag für künftige parent-Frames
                return None
            if platform and platform not in _STATUS_PLATFORM_WHITELIST:
                return None
            st["parent_sessions"].add(session_id)
            if st["working"]:
                st["working"] = False
                frames.append({"working": False, "label": None,
                               "started_at": st["started_at"] or now})
                st["label"] = None
                st["web_count"] = 0
                st["started_at"] = None
                if st["sub_slots"]:
                    logger.info(
                        "FlipsiBridge: agent.status parent OFF mit %d offenen "
                        "Sub-Slots (keine CLOSE-Frames — Zombie-Slots, FIFO-Cap "
                        "begrenzt): %s", len(st["sub_slots"]),
                        ", ".join(sorted(st["sub_slots"])[:4]))
        self._schedule_status_frames(frames)
        return None

    # ───────────────────────── W21: Anruf-Backend (Call-Signaling, §3.4/§3.5) ──

    def _call_register(self, call_id: str, gw_chat_id: Optional[str],
                       wire_chat_id: Optional[str]) -> dict:
        """Call-Auftrag registrieren (Aufruf NUR am WS-Loop). FIFO-Cap + TTL-
        Sweep (Terminal-Einträge altern — späte Signale werden damit noch
        erkannt, aber nie ungebunden gesammelt)."""
        now = time.time()
        state = {"call_id": call_id, "state": "ringing", "fut": None, "say": None,
                 "wire_chat_id": wire_chat_id, "gw_chat_id": gw_chat_id,
                 "bound_session": None, "started": now, "tts_seq": 0,
                 "was_accepted": False}
        with self._calls_lock:
            stale = [cid for cid, entry in self._calls.items()
                     if now - float(entry.get("started") or now) > _CALL_STATE_TTL_S]
            for cid in stale:
                self._calls.pop(cid, None)
            while len(self._calls) >= _CALLS_CAP:
                self._calls.pop(next(iter(self._calls)))
            self._calls[call_id] = state
        return state

    def _call_fanout_frames(self, frame_type: str, payload: dict,
                            clear: Optional[dict] = None) -> Optional[list]:
        """Je Geräte-Session EIN eigener enc-Frame — aber NUR Sessions mit
        bekanntem Wire-chat_id (AAD bindet die Relay-chat_id; ein Anruf ohne
        bekannten Konversations-Rahmen geht NICHT in die Luft). None = keine
        fähige Session → fail-closed beim Aufrufer."""
        sessions = [s for s in self._device_sessions()
                    if getattr(s, "chat_id", None)]
        if not sessions:
            return None
        return [sess.encrypt_frame(frame_type, sess.chat_id, payload, clear=clear)
                for sess in sessions]

    def _call_reserve_tts_seq(self, call_id: str) -> Optional[int]:
        """TTS-Chunk-Sequenz für die Durchsage reservieren (Monotonie je Anruf;
        nur in state 'accepted' — sonst None)."""
        with self._calls_lock:
            call = self._calls.get(call_id)
            if call is None or call.get("state") != "accepted":
                return None
            seq = int(call.get("tts_seq") or 0)
            call["tts_seq"] = seq + 1
            return seq

    def _call_marker_for(self, wire_chat_id: str) -> Optional[dict]:
        """Call-Modus-Bindung für send() (§3.3): offener ANGENOMMENER Anruf in
        diesem Chat? Rückgabe {"call_id", "seq"} (Sequenz fortlaufend je Anruf)
        oder None (kein Call-Modus — exakt das Alt-Verhalten)."""
        with self._calls_lock:
            for call in self._calls.values():
                if call.get("state") != "accepted":
                    continue
                if wire_chat_id in (call.get("wire_chat_id"), call.get("gw_chat_id")):
                    seq = int(call.get("tts_seq") or 0)
                    call["tts_seq"] = seq + 1
                    return {"call_id": call["call_id"], "seq": seq}
        return None

    @staticmethod
    def _call_split_chunks(text: str) -> list[str]:
        """TTS-Split (§3.3): JE Satz = EIN Chunk (satztrennend erst raus, dann
        wird der nächste verarbeitet — kein Stillschweigen über Absätze).
        Riesensätze werden hart geteilt; harter Chunk-Deckel (RAM-Strengung)."""
        text = (text or "").strip()
        if not text:
            return []
        parts = re.split(r"(?<=[.!?…])\s+", text)
        chunks: list[str] = []
        for part in parts:
            part = part.strip()
            while len(part) > _CALL_CHUNK_MAX_CHARS:
                hard = part[:_CALL_CHUNK_MAX_CHARS]
                part = part[_CALL_CHUNK_MAX_CHARS:].lstrip()
                chunks.append(hard)
            if part:
                chunks.append(part)
        if len(chunks) > _CALL_CHUNKS_MAX:
            chunks = chunks[:_CALL_CHUNKS_MAX]
        return chunks

    def _handle_call_signal(self, envelope: dict, etype: str, chat_id: str) -> None:
        """W21 (Anruf P1/P2): call_ringing/accepted/declined/missed/ended.enc →
        Call-Zustandsmaschine (KEIN Gateway-Push — Signaling ist kein Chat-
        Text, I3-Parität). Fail-closed Kette Parität _decrypt_inbound
        (Geräte-MAP + Primär-Anchor + decrypt_frame), danach NUR der lokale
        Anruf-Auftrag: fremde/späte call_ids werden verworfen + diagnostiziert
        (kein Spoofing in fremde Ringe)."""
        call_id = str(envelope.get("call_id") or "").strip()
        if not call_id or not _CALL_ID_RE.fullmatch(call_id):
            self.rejected_frames.append({"type": etype,
                                         "reason": "call_id fehlt/ungültig"})
            logger.warning("FlipsiBridge: %s ohne gültiges call_id — verworfen", etype)
            return
        with self._calls_lock:
            call = self._calls.get(call_id)
        if call is None:
            self.rejected_frames.append({
                "type": etype,
                "reason": "kein lokaler Anruf-Auftrag (fremd oder spät)"})
            logger.warning(
                "FlipsiBridge: %s für unbekannten call_id %s — verworfen "
                "(fail-closed)", etype, _log_id(call_id, 24))
            return
        sess = self._session_for_frame(envelope)
        primary = self._active_session()
        if sess is not None and (primary is None or sess.expired()):
            sess = None
        if sess is None:
            # Parität chat.media.enc-Strengung: ohne intakten Primär-Anchor
            # autorisiert die Geräte-MAP KEINEN Call — SIRS-01-Schiene
            # (crypto.session_reset statt stillem Verwerfen).
            frame_session = str(envelope.get("session_id") or "").strip()
            self.rejected_frames.append({
                "type": etype,
                "reason": "session dem Agenten nicht bekannt/abgelaufen — "
                          "crypto.session_reset angestoßen"})
            logger.warning(
                "FlipsiBridge: %s mit unbekannter/abgelaufener session_id %s — "
                "verworfen (fail-closed)", etype, _log_id(frame_session, 16))
            if frame_session and not self._session_reset_pending:
                self._session_reset_pending = True
                self._schedule(self._send_session_reset(
                    frame_session, "agent-session-lost"))
            return
        try:
            payload = sess.decrypt_frame(envelope)
        except CryptoFrameError as exc:
            self.rejected_frames.append({"type": etype, "reason": str(exc)})
            logger.warning(
                "FlipsiBridge: %s-Entschlüsselung fehlgeschlagen (%s) — Frame verworfen",
                etype, exc)
            return
        # Wire-chat_id der Session nachschärfen (Echo des Frames, Parität
        # _decrypt_inbound — Fan-out-Kontext für Antwort-/End-Frames).
        if chat_id and not getattr(sess, "chat_id", None):
            sess.chat_id = chat_id
        signal = etype.rsplit(".", 1)[0]  # call_ringing|accepted|declined|missed|ended
        with self._calls_lock:
            if call.get("state") != "ringing":
                # Spät-/Doppelsignal nach Terminal-Ereignis — bewusst ohne
                # Wirkung (Terminal-Einträge altern erst mit der TTL).
                logger.debug(
                    "FlipsiBridge: %s für call_id %s nach Terminal-Zustand %s — ignoriert",
                    etype, _log_id(call_id, 24), call.get("state"))
                return
            if signal == "call_ringing":
                logger.info(
                    "FlipsiBridge: Anruf %s klingelt (device %s)",
                    _log_id(call_id, 24),
                    _log_id(str(getattr(sess, "device_key", "")), 24))
                return
            call["bound_session"] = sess
            if signal == "call_accepted":
                call["state"] = "accepted"
                call["was_accepted"] = True
            elif signal == "call_declined":
                call["state"] = "declined"
            elif signal == "call_missed":
                call["state"] = "missed"
            elif signal == "call_ended":
                # call_ended klingelnd = Abweisung (§3.4 'verpasst'), nach
                # Annahme = reguläres Gesprächsende (App oder Nutzer).
                call["state"] = "ended" if call.get("was_accepted") else "declined"
            else:
                return  # Router-Verband macht diesen Fall unerreichbar
            fut = call.get("fut")
        logger.info("FlipsiBridge: Anruf %s → %s", _log_id(call_id, 24), call["state"])
        if fut is not None and not fut.done():
            fut.set_result(call["state"])

    async def call_user(self, reason: str, say: Optional[str] = None,
                        chat_id: Optional[str] = None,
                        timeout_s: Optional[float] = None) -> dict:
        """W21 (Anruf P2, roadmap-anruf.md §3.5): Agent INITIIERT einen Anruf.

        Generisches Bridge-Tool (Capability „calls", Freigabe-Gate wie
        interaction/open_app → flipsi_call_pre_tool_call). Ablauf:
          call_invite.enc je offene Geräte-Session (Inhalt NUR im ct: kind/
          reason) → Ring-Fenster (signals je Session enc) → Ergebnis-Dict:
          {ok, status: accepted|declined|missed|ended|failed, call_id,
           fallback_sent, tts_chunks, chat_id, error?}
        - accepted: say wird satzweise als TTS-Chunks (call_chunk_seq, §3.3)
          gesendet; AUTO-END (Cron-Rezept „Durchsage") schickt danach
          call_end.enc. say=None = offener Gesprächsanruf (Agent terminiert
          via flipsi_call_end).
        - declined/missed/ended: Chat-Fallback (§3.5) — say als normale
          Nachricht; NICHTS geht verloren.
        Fail-closed: KEIN Anruf ohne bestätigte E2E-Session mit bekannter
        Wire-chat_id (kein erfundenes Routing, kein Klartext-Pfad).
        """
        if not self._connected or self._ws is None:
            return {"ok": False, "error": "nicht verbunden — kein Anruf (fail-closed)"}
        reason = str(reason or "").strip()
        if not reason:
            return {"ok": False, "error": "reason erforderlich"}
        if len(reason) > _CALL_REASON_MAX_CHARS:
            reason = reason[:_CALL_REASON_MAX_CHARS]
        say = str(say or "").strip() or None
        if say and len(say) > _CALL_SAY_MAX_CHARS:
            say = say[:_CALL_SAY_MAX_CHARS]
        probe = self._call_fanout_frames("probe", {}, None)
        if probe is None:
            return {"ok": False,
                    "error": "keine bestätigte E2E-Session mit bekannter "
                             "Phone-Konversation — Anruf fail-closed verweigert"}
        wire_chat_id = probe[0]["chat_id"]
        gw_chat_id = self._stable_gateway_chat_id(wire_chat_id)
        if timeout_s is not None:
            try:
                timeout = float(timeout_s)
            except (TypeError, ValueError):
                timeout = self.call_ring_timeout_s
            timeout = max(_CALL_RING_TIMEOUT_MIN_S,
                          min(_CALL_RING_TIMEOUT_MAX_S, timeout))
        else:
            timeout = self.call_ring_timeout_s
        call_id = "call-" + uuid.uuid4().hex[:10]
        call = self._call_register(call_id, gw_chat_id, wire_chat_id)
        call["say"] = say
        loop = asyncio.get_running_loop()
        fut = loop.create_future()
        call["fut"] = fut
        frames = self._call_fanout_frames(
            CALL_INVITE_FRAME, {"kind": _CALL_KIND, "reason": reason},
            clear={"call_id": call_id}) or []
        send_errors = 0
        for frame in frames:
            try:
                await self._send_json(frame)
            except Exception as exc:
                send_errors += 1
                logger.warning("FlipsiBridge: call_invite-Send fehlgeschlagen: %s", exc)
        if send_errors >= len(frames):
            with self._calls_lock:
                self._calls.pop(call_id, None)
            return {"ok": False, "status": "failed", "call_id": call_id,
                    "error": "call_invite konnte nicht gesendet werden (WS tot)"}
        # ── Ring-Fenster: Signals werden von _handle_call_signal gesetzt ─────
        result_state = "missed"
        try:
            result_state = await asyncio.wait_for(fut, timeout=timeout)
        except asyncio.TimeoutError:
            # Kein Terminal-Signal im Fenster → verpasst (§3.5): das Phone kann
            # weiter klingeln (spätes accept landet auf dem TTL-Eintrag), der
            # Fallback unten sichert die Durchsage als Chat-Nachricht.
            with self._calls_lock:
                entry = self._calls.get(call_id)
                if entry is not None and entry.get("state") == "ringing":
                    entry["state"] = "missed"
        finally:
            with self._calls_lock:
                entry = self._calls.get(call_id)
                if entry is not None:
                    entry["fut"] = None
        status = result_state if result_state in (
            "accepted", "declined", "ended", "missed") else "missed"
        fallback_sent = False
        tts_chunks = 0
        if status == "accepted":
            for chunk in self._call_split_chunks(say or ""):
                seq = self._call_reserve_tts_seq(call_id)
                if seq is None:
                    break  # Session/Zustand starb unter dem Call — ehrlich melden
                msg_id = uuid.uuid4().hex[:16]
                reply_frames = self._call_fanout_frames(
                    "chat.reply.enc",
                    {"text": chunk, "msg_id": msg_id,
                     "call_chunk_seq": seq, "call_id": call_id},
                    clear={"msg_id": msg_id})
                if not reply_frames:
                    status = "failed"
                    break
                sent = 0
                for reply_frame in reply_frames:
                    try:
                        await self._send_json(reply_frame)
                        sent += 1
                    except Exception as exc:
                        logger.warning(
                            "FlipsiBridge: TTS-Chunk-Send fehlgeschlagen: %s", exc)
                if sent:
                    tts_chunks += 1
            if (say and self.call_auto_end and status == "accepted"):
                end_result = await self.call_end(call_id=call_id)
                if end_result.get("ok"):
                    status = "ended"
        elif say:
            # §3.5 Chat-Fallback: verpasst/abgewiesen/eend-bei-Ring — die
            # Durchsage geht als normale Chat-Nachricht raus (nichts verloren).
            fallback = await self.send(wire_chat_id, say)
            fallback_sent = bool(fallback.success)
        return {"ok": True, "status": status, "call_id": call_id,
                "fallback_sent": fallback_sent, "tts_chunks": tts_chunks,
                "chat_id": gw_chat_id}

    async def call_end(self, call_id: Optional[str] = None,
                       chat_id: Optional[str] = None) -> dict:
        """W21 (Terminierung, §1 'Anrufe sind terminierbar'): offenen Anruf
        beenden — nach call_id, sonst der angenommene Anruf im Ziel-Chat,
        sonst (Ring) der älteste offene Ring im Ziel-Chat. Nach Annahme geht
        call_end.enc über die GEBUNDENE Geräte-Session; im Ring als Fan-out
        (jeder klingelnde Bildschirm bekommt das Ende). Fail-closed ohne
        offenen Anruf."""
        if not self._connected or self._ws is None:
            return {"ok": False, "error": "nicht verbunden (fail-closed)"}
        with self._calls_lock:
            target = None
            if call_id:
                target = self._calls.get(str(call_id).strip())
            elif chat_id:
                wire_filter = self._relay_chat_id_for(chat_id)
                for prefer in ("accepted", "ringing"):
                    for entry in self._calls.values():
                        if entry.get("state") != prefer:
                            continue
                        if wire_filter in (entry.get("wire_chat_id"),
                                           entry.get("gw_chat_id")):
                            target = entry
                            break
                    if target is not None:
                        break
            else:
                for prefer in ("accepted", "ringing"):
                    for entry in self._calls.values():
                        if entry.get("state") == prefer:
                            target = entry
                            break
                    if target is not None:
                        break
            if target is None:
                return {"ok": False, "status": "none",
                        "error": "kein offener Anruf gefunden"}
            target_call_id = str(target["call_id"])
            target_state = str(target.get("state") or "")
            target["state"] = "ended"
            fut = target.get("fut")
            target["fut"] = None
            bound = target.get("bound_session")
            wire_chat_id = target.get("wire_chat_id") or ""
        if fut is not None and not fut.done():
            # Ring-Phase: der wartende call_user wacht mit 'ended' auf.
            fut.set_result("ended")
        clear = {"call_id": target_call_id}
        if bound is not None and getattr(bound, "chat_id", None):
            frames = [bound.encrypt_frame(CALL_END_FRAME, bound.chat_id,
                                          {}, clear=clear)]
        else:
            frames = self._call_fanout_frames(CALL_END_FRAME, {}, clear=clear) or []
        sent = 0
        for frame in frames:
            try:
                await self._send_json(frame)
                sent += 1
            except Exception as exc:
                logger.warning("FlipsiBridge: call_end-Send fehlgeschlagen: %s", exc)
        if not frames or sent < 1:
            return {"ok": False, "call_id": target_call_id,
                    "error": "call_end konnte nicht gesendet werden (WS tot)"}
        logger.info(
            "FlipsiBridge: Anruf %s beendet (Zustand davor: %s, %d Frame(s))",
            _log_id(target_call_id, 24), target_state, sent)
        return {"ok": True, "status": "ended", "call_id": target_call_id,
                "chat_id": (self._stable_gateway_chat_id(wire_chat_id)
                            if wire_chat_id else None)}

    # ───────────────────────────────── MEDIA-T1: Medien im Chat (E2E-only)

    def _handle_media_chunk(self, envelope: dict, chat_id: str) -> None:
        """MEDIA-T1: EIN verschlüsselter Media-Chunk (chat.media.enc).

        Kette: entschlüsseln (Ratchet) → chunk_ack an das Phone (Progress,
        klar + light) → Reassembly je media_id → bei Vollständigkeit: Datei
        ins Media-Dir schreiben, sha256 prüfen (fail-closed) und als Gateway-
        NACHRICHT liefern (Pfad + Art + Größe — Text bleibt der Chat-Weg).
        """
        # B3: Media-Chunks laufen je Geräte-Session — Session über die KLARE
        # session_id suchen (Map-Verband, nicht mehr nur die Primär-Session).
        sess = self._session_for_frame(envelope)
        # B3-Strengung (Parität _decrypt_inbound): ohne intakten Primär-Anchor
        # (Agent-Restart) oder bei abgelaufener Session autorisiert der
        # MAP-Eintrag KEINEN Chunk — fail-closed + crypto.session_reset
        # (SIRS-01: kein stilles Verwerfen); der plain-MAP-Miss unten bleibt
        # beim alt bewiesenen Reject.
        if sess is not None and (self._active_session() is None or sess.expired()):
            frame_session = str(envelope.get("session_id") or "").strip()
            self.rejected_frames.append({
                "type": "chat.media.enc",
                "reason": "session dem Agenten nicht bekannt (Agent-Neustart) — "
                          "crypto.session_reset + Neu-Handshake angestoßen"})
            logger.warning(
                "FlipsiBridge: chat.media.enc mit unbekannter session_id %s — "
                "Primär-Session fort (Agent-Neustart) → fail-closed, "
                "crypto.session_reset angestoßen", _log_id(frame_session, 16))
            if frame_session and not self._session_reset_pending:
                self._session_reset_pending = True
                self._schedule(self._send_session_reset(
                    frame_session, "agent-session-lost"))
            return
        if sess is None:
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
        # PH2-FIX (Jäger B4)-Parität: verarbeitet → read. W4-B3 (Read-Ack-P3-
        # Fix): die Broker-msg_id des CHUNKS, der das Assembly abgeschlossen
        # hat, wandert in den Deliver-Envelope — danach trägt der EXISTIERENDE
        # Read-Chain (_deliver_inbound → _send_read_receipt → chat.read.enc mit
        # klarer msg_id) die 'Gelesen'-Markierung und der Relay schlägt im
        # chat_status zu (Chip-Kette queued → delivered → read bei reinen
        # Medien). Fehlt die msg_id (alter Relay < W4), bleibt es exakt beim
        # heutigen Verhalten: leere msg_id → Relay verwirft fail-safe.
        self._deliver_inbound(chat_id, text, {
            "type": "chat.media.enc", "media_id": media_id, "kind": kind,
            "name": name, "size": size, "path": path,
            "msg_id": str(envelope.get("msg_id", "") or "")})

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


# ── W19: Modul-Level-Hook-Fächer (register → ctx.register_hook) ─────────────
# Modul-Level, weil Hermes die Hooks je PLUGIN registriert — zur Registrie-
# rungszeit existiert noch keine Adapter-Instanz: je Callback fannen die
# Funktionen an ALLE verbundenen Adapter (WeakSet-Registry, siehe oben).
# Kontrakt (hooks.md/plugins_dispatch): async def **kwargs, exceptions sind
# fail-soft weggefangen, Rückgabe IMMER None — pre_gateway_dispatch ist ein
# Policy-Gate (Nicht-None würde skip/rewrite bedeuten) und pre_tool_call ein
# FAIL-CLOSED-Gate (Nicht-None wäre ein Block-Directive); beides ist hier
# NIEMALS die Absicht. Reine in-memory-Verbandsarbeit, Scheduling non-block
# (run_coroutine_threadsafe + Coalesce-Cap).

def _flipsi_status_adapters() -> list:
    """Snapshot verbundener Adapter (fail-soft: Iterations-Races schlucken)."""
    try:
        return [adapter for adapter in list(STATUS_ADAPTERS)
                if getattr(adapter, "_connected", False)]
    except Exception:
        logger.warning("FlipsiBridge: Status-Adapter-Snapshot fehlgeschlagen",
                       exc_info=True)
        return []


async def _status_fan_out(hook_name: str, kwargs: dict) -> None:
    """Fächer: je verbundenem Adapter die hook_*-Methode aufrufen; JEDE Ausnahme
    je Adapter wegfangen (ein kaputter Verband darf nie den Gateway-Loop oder
    einen Tool-Call belasten)."""
    method_name = f"hook_{hook_name}"
    for adapter in _flipsi_status_adapters():
        method = getattr(adapter, method_name, None)
        if method is None:
            continue
        try:
            await method(**kwargs)
        except Exception:
            logger.warning("FlipsiBridge: Status-Hook '%s' fehlgeschlagen (%s)",
                           hook_name, method_name, exc_info=True)


async def _hook_pre_gateway_dispatch(**kwargs: Any) -> None:
    await _status_fan_out("pre_gateway_dispatch", kwargs)
    return None  # IMMER None (Policy-Hook)


async def _hook_pre_tool_call(**kwargs: Any) -> None:
    await _status_fan_out("pre_tool_call", kwargs)
    return None  # IMMER None (FAIL-CLOSED-Hook: kein Block-Directive)


async def _hook_post_tool_call(**kwargs: Any) -> None:
    await _status_fan_out("post_tool_call", kwargs)
    return None


async def _hook_subagent_start(**kwargs: Any) -> None:
    await _status_fan_out("subagent_start", kwargs)
    return None


async def _hook_subagent_stop(**kwargs: Any) -> None:
    await _status_fan_out("subagent_stop", kwargs)
    return None


async def _hook_on_session_end(**kwargs: Any) -> None:
    await _status_fan_out("on_session_end", kwargs)
    return None


# ── W21 (Anruf P2, roadmap-anruf.md §3.5): generisches Call-Bridge-Tool ───────
# Route: Gateway-Tool (Sync) → run_coroutine_threadsafe auf den WS-Loop des
# Adapters (Parität agent.status-Verband) → Adapter → Relay → App. Freigabe-
# Gate = Capability „calls" als pre_tool_call approve-Directive — exakt die
# Gate-Mechanik wie interaction/open_app (android_call-Muster): interaktive
# Kontexte fragen Sir, Cron folgt approvals.cron_mode, alles andere lehnt ab.
# NIE auto-approve. Ergebnisse sind JSON-Strings (Parität android_*-Tools).
_FLIPSI_CALL_TOOL_TIMEOUT_S = 150.0   # Ring-Fenster + TTS-Chunks + End-Send

_FLIPSI_CALL_USER_SCHEMA = {
    "name": "flipsi_call_user",
    "description": (
        "Start a voice call to the paired phone via FlipsiBridge "
        "(full-screen ringing, WhatsApp-style). reason is shown while ringing; "
        "say is spoken by the phone's TTS after the call is accepted. If the "
        "call is declined or missed, say falls back to a normal chat message "
        "so nothing is lost. Returns a JSON summary."),
    "parameters": {
        "type": "object",
        "properties": {
            "reason": {"type": "string",
                       "description": "Kurzer Grund des Anrufs (z. B. 'Wetter durchsagen') "
                                      "— wird beim Klingeln angezeigt."},
            "say": {"type": "string",
                    "description": "Text, der nach der Annahme gesprochen wird (TTS am "
                                   "Phone). Leer = Anruf ohne Durchsage (offenes Gespräch)."},
            "chat_id": {"type": "string",
                        "description": "Optionale Ziel-Konversation; leer = die "
                                       "Standard-Phone-Konversation des Agenten."},
        },
        "required": ["reason"],
    },
}

_FLIPSI_CALL_END_SCHEMA = {
    "name": "flipsi_call_end",
    "description": (
        "Hang up an active (or still ringing) voice call on the paired phone "
        "via FlipsiBridge — terminates a call started with flipsi_call_user."),
    "parameters": {
        "type": "object",
        "properties": {
            "call_id": {"type": "string",
                        "description": "Optionale Anruf-ID (aus flipsi_call_user); "
                                       "leer = der offene Anruf."},
            "chat_id": {"type": "string",
                        "description": "Optionale Ziel-Konversation (Disambiguierung)."},
        },
        "required": [],
    },
}

_FLIPSI_CALL_APPROVAL_REASONS = {
    "flipsi_call_user": "starts a real voice call that rings the paired phone",
    "flipsi_call_end": "hangs up a voice call on the paired phone",
}


def flipsi_call_pre_tool_call(tool_name, args=None, **_hook_kwargs):
    """Capability „calls" (roadmap-anruf.md §3.5): Freigabe-Gate Parität
    android_call — approve-Directive an den Hermes-Approval-Verband; NIEMALS
    auto-approve. Nicht-Call-Tools: None (unverändert)."""
    reason = _FLIPSI_CALL_APPROVAL_REASONS.get(tool_name)
    if reason is None:
        return None
    return {
        "action": "approve",
        "message": (f"flipsibridge requests approval — this tool {reason} "
                    f"({tool_name})."),
        "rule_key": f"flipsibridge:{tool_name}",
    }


def _flipsi_call_adapters() -> list:
    """Verbundene Adapter, session-fähige zuerst (Parität Status-Verband)."""
    adapters = [a for a in list(STATUS_ADAPTERS)
                if getattr(a, "_connected", False)]
    with_sessions = [a for a in adapters
                     if getattr(a, "_active_session", lambda: None)() is not None]
    return with_sessions or adapters


def _flipsi_call_tool_dispatch(method: str, kwargs: dict) -> str:
    """Sync-Brücke Gateway-Thread → Adapter-WS-Loop. Rückgabe = JSON-String
    (Parität android_*-Tools); JEDE Ausnahme wird in ein Fehler-JSON übersetzt
    (kein Tool-Crash im Gateway)."""
    adapters = _flipsi_call_adapters()
    if not adapters:
        return json.dumps({"ok": False,
                           "error": "kein verbundener FlipsiBridge-Adapter (Phone offline?)"})
    adapter = adapters[0]
    loop = (getattr(adapter, "_status", None) or {}).get("loop")
    if loop is None or loop.is_closed():
        return json.dumps({"ok": False, "error": "Adapter-WS-Loop nicht bereit"})
    try:
        fut = asyncio.run_coroutine_threadsafe(
            getattr(adapter, method)(**kwargs), loop)
        result = fut.result(timeout=_FLIPSI_CALL_TOOL_TIMEOUT_S)
    except Exception as exc:
        return json.dumps({"ok": False, "error": str(exc)})
    return json.dumps(result)


def flipsi_call_user(reason: str, say: str = "", chat_id: str = "") -> str:
    """Anruf initiieren (generisch; Ergebnis = JSON)."""
    return _flipsi_call_tool_dispatch("call_user", {
        "reason": reason, "say": say or None, "chat_id": chat_id or None})


def flipsi_call_end(call_id: str = "", chat_id: str = "") -> str:
    """Offenen Anruf terminieren (generisch; Ergebnis = JSON)."""
    return _flipsi_call_tool_dispatch("call_end", {
        "call_id": call_id or None, "chat_id": chat_id or None})


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

    # W19: agent.status-Hook-Verband (Muster: raft-Adapter register_hook-Schleife).
    # Modul-Level-CBs fannen an alle verbundenen Adapter; kontraktgemäß IMMER
    # None (pre_gateway_dispatch = Policy-Gate, pre_tool_call = FAIL-CLOSED) und
    # Callbacks ≤ ~2 ms reine in-memory Dict-Operationen + non-block Scheduling.
    for hook_name, callback in (
        ("pre_gateway_dispatch", _hook_pre_gateway_dispatch),
        ("pre_tool_call", _hook_pre_tool_call),
        ("post_tool_call", _hook_post_tool_call),
        ("subagent_start", _hook_subagent_start),
        ("subagent_stop", _hook_subagent_stop),
        ("on_session_end", _hook_on_session_end),
    ):
        ctx.register_hook(hook_name, callback)

    # W21 (Anruf P2, roadmap-anruf.md §3.5): generisches Call-Bridge-Tool je
    # Agent — Capability „calls", Freigabe-Gate (approve-Directive) wie
    # interaction/open_app. check_fn = gleiche Abhängigkeits-Schiene wie die
    # Plattform selbst (aiohttp); zur Call-Zeit gilt zusätzlich fail-closed
    # (verbindet/nicht session-fähig = Fehler-JSON, nie stiller Downgrade).
    for tool_name, schema, handler in (
        ("flipsi_call_user", _FLIPSI_CALL_USER_SCHEMA, flipsi_call_user),
        ("flipsi_call_end", _FLIPSI_CALL_END_SCHEMA, flipsi_call_end),
    ):
        try:
            ctx.register_tool(
                name=tool_name,
                toolset="flipsibridge",
                schema=schema,
                handler=handler,
                check_fn=check_flipsibridge_requirements)
        except (AttributeError, TypeError):
            logger.warning(
                "FlipsiBridge: register_tool für %s nicht verfügbar (ältere "
                "Plugin-API) — Anruf-Tool übersprungen", tool_name)
    try:
        ctx.register_hook("pre_tool_call", flipsi_call_pre_tool_call)
    except (AttributeError, TypeError):
        logger.warning(
            "FlipsiBridge: Call-Freigabe-Hook nicht registrierbar (ältere "
            "Plugin-API) — Tools bleiben approval-unabhängig sichtbar")


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