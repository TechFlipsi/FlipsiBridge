# W20 / PH-5 — Multi-Gerät-Architekturplan (Handy + Tablet, 2 Handys)

**Status:** PLAN (NUR-LESEN-Ergebnis) · Erzeugt 17.11.2026 · Kein Bau, kein Code-Edit ohne Sirs GO.

## SIRS BINDENDE ANFORDERUNGEN (Plan-Kopf, verbindlich)

> **Sir, 17.11. Nacht (GO Multi-Gerät):** „der Chat muss auch auf die anderen Geräte landen,
> damit der Nutzer egal wo weiterschreiben kann" = VOLLER Chat-Sync über alle Geräte.
> Jedes Gerät eigener Verbindungsplatz, Sitzungs-Routing ohne Verlaufsmischung,
> ADMIN DARF JEDES GERÄT VERWALTEN (PH5-T7).
>
> **Sir, 17.11. Nacht (Sicherheit, ÜBER ALLEN Fixwellen):** „Bitte bedenke bei allen
> Änderungen und Fixes die Sicherheit — großen Wert! Chat-Sync muss entsprechend auch
> KOMPLETT verschlüsselt übertragen werden."

Direkte Architektur-Konsequenzen aus Sirs Sicherheits-Wortlaut:

1. Chat-Sync darf **KEINE Klartext-Verläufe am Relay** ablegen. Option (a) „Relay hält
   Verlauf" gilt NUR in der Form „Relay speichert ausschließlich E2E-verschlüsselte
   Blobs (keine Meta im Klartext über Routing-Notwendiges hinaus)" — sonst verworfen.
2. JEDER Inhalt-Frame zwischen zwei Geräten läuft **über den Agent als E2E-Endpunkt**
   (Gerät A → Agent-Session-A enc → Agent → enc an Gerät B) ODER über einen sauber
   begründeten Geräte-Gruppenschlüssel. Beide Wege sind hier bewertet (Kap. 2, 3).
3. Steuer-Frames (chat.status, read-Haken) tragen **keine Chat-Inhalte im Klartext**.
4. SECURITY-Kapitel (Kap. 8) deckt JEDE neue Angriffsfläche mit Schutz + Begründung,
   warum die gewählte Option die sicherste ist.
5. Eigenes Kapitel **VERSCHLÜSSELUNGS-VERBAND** (Kap. 7): was läuft wo enc (TLS + E2E),
   was bewusst NICHT.

**Sirs Kernauftrag:** der Chat (Verlauf, Haken, Antwort) muss auf ALLEN Geräten landen —
Weiter-schreiben von jedem Gerät. App-lokale Chat-Platte bleibt SSOT je Gerät; minimal
invasive Anbindung ans heutige Design; Relay broadcastet + backfillt ohne Klartext-Verlauf.

**Kurz-Empfehlung:** EIN Kopplungs-Code = N Geräte je State (Geräte-Registry + Anmelde-Tor), **kein**
Gerät-eigene Codes; Chat-Sync über **Agent als E2E-Hub** (je Gerät × je Agent eigentständige
X3DH/Ratchet-Session, Agent-Fan-out + Sync-Echo, Agent-Backfill für frische Geräte),
**kein** Gerätes-Gruppenschlüssel, **kein** Verlauf am Relay (auch nicht verschlüsselt).

---

## 0. Ist-Stand (echte Code-Stellen, zitiert)

### 0.1 Relay — `tools/android_relay.py` (4793 Z.)

| # | Thema | Zeile / Beweis |
|---|-------|----------------|
| 1 | **EIN Phone-Slot** | Z.83 `self.phone_ws: Optional[web.WebSocketResponse] = None` (singular); Z.87 `phone_ws_lock` |
| 2 | Gerätekennung (W17-F1) | Z.151–157 `phone_device_id: Optional[str]` — **eine einzige Kennung** für den aktiven Slot |
| 3 | **EIN Code = EIN Gerät** (Gate) | Z.306–339 `_pairing_slot_locked()`: lebender Inhaber + andere/unbekannte `device_id` → `True` (Lock). Z.1901–1910 Pre-Check = `HTTP 403 {"error":"code-in-use"}`; Z.1947–1950 Race-Check = WS-Close `4403` (`X-Bridge-Error: code-in-use`) |
| 4 | Replace-Regel | Z.1951–1964: Reconnect **desselben** Geräts ersetzt seinen Slot; `state.phone_ws = ws`, `state.phone_device_id = device_id` atomar unter Lock |
| 5 | Slot-Freigabe | Z.2172–2196 `_cleanup_phone`: WS tot/Rotate/Shutdown → `phone_device_id = None` (kein Dauer-Lock) |
| 6 | Presence/State | Z.1990–2029 `_send_phone_state` (Agenten-Liste + `session_active` je Agent), Z.2032–2054 `_broadcast_agent_presence` — **an genau die eine Phone-WS** |
| 7 | Outbox | Z.115 `phone_outbox = _ByteBoundedDeque(maxlen=_CHAT_QUEUE_MAXLEN)` — **eine einzige** Agent→App-Spool-Queue; Z.2449–2452 `_CHAT_QUEUE_MAXLEN = 500`; Z.3908–3937 `_flush_phone_outbox` beim Connect (J3: `_refresh_enc_frame_ts` je Flush Z.3930) |
| 8 | Deliver | Z.3859–3905 `_deliver_to_phone`: broker_lock, Outbox-Append, **ein** `ws.send_json`, Retention-Prune (Z.659–667, Default 24 h) |
| 9 | Status-Broker | Z.118–126 `chat_status` (msg_id→Status) + `chat_activity` (Dedup je Agent×chat); Z.2501–2503 Rangfolge queued→delivered→read; Z.3525–3562 `_set_chat_status` → `chat.status`-Frame (ohne Rate-Limit, „Status ≠ Chat-Inhalt") |
| 10 | Owner-Isolation | Z.134–138 + Z.3565–3579 `_chat_owner_allows` (dauerhaft, FIFO-Cap `_CHAT_OWNER_MAX`, Z.3481–3523) |
| 11 | **Inbound je Agent** | Z.113 `chat_inbound: dict[str, deque]` (App→Agent, je Agent) — **bereits Multi-fähig je Agent, aber absenderlos** |
| 12 | Key-Registry | Z.958–1006 `relay-keys.json` 0600, Speicherform `{"v": 1, "device": entry}` — **GENAU EIN Device-Eintrag**; Z.3970 `device_id = str(body.get("device_id") or "phone")` (Default); Z.3976–3989 Identity-Wechsel = LOUD-Warning |
| 13 | E2E-Broker | Z.140–149 `crypto_sessions` (session_id→{agent_id,ts,acked}, 24 h) + `crypto_replay` (Fenster 100 + 10 min, Z.682–684 `_REPLAY_WINDOW`/`_FRAME_MAX_AGE_MS`); Z.4027–4132 `/crypto_hello` (Re-Play 409, Rebind 403 — Session je (session_id, agent_id), **keine device_id-Bezug**) |
| 14 | Handshake-Tore | Z.1843–1881 Epoche + optionaler Passwort-Tor (`X-Pairing-Password`, W17-F2), Z.1916–1920 WS-Limits (heartbeat 15 s, 2 MB, receive_timeout 300 s) |
| 15 | Tool-Weg | Z.4568+ `_handle_http` → Befehls-Hüllposten an die **eine** Phone-WS (Z.4594–4603 Connectivity-Check singular) |
| 16 | Reset-Gründe | Z.2488–2499 `_RESET_REASONS` inkl. `cross-session-takeover` (Zweite Code-Inhaber-Session = App-Warnung) — **spricht heute EIN-Gerät-Norm an** |
| 17 | mTLS-Gate | Z.1380–1450 Geräterouten hinter X-SSL-Verify/Device-DN + P43-Epochen-Check (17.11. Fixwelle) |

### 0.2 Adapter — `contrib/hermes-chat-plugin/adapter.py` (3233 Z.)

| # | Thema | Zeile / Beweis |
|---|-------|----------------|
| 1 | **EINE aktive E2E-Session** | Z.962 `self._crypto_session: Optional[AgentCryptoSession] = None` (singular je Adapter) |
| 2 | X3DH-Responder | Z.678–723 `AgentCryptoSession.respond_to_hello`; AAD bindet `fb-v1|session_id|chat_id|agent_id` (Z.370–396) |
| 3 | TOFU-Phone-Pin | Z.927–932 `phone_pin.json`; Z.1878–1928 `_check_phone_identity_pin`: **EIN gepinnter `ik_pub` je Agent**, Wechsel = LOUD („neues Gerät ODER Übernahme", Z.1923–1927), Bestätigung nach FP-Abgleich (Z.1929–1950, B2) |
| 4 | „EIN Code"-Designtreue | Z.933–947: Sir-Order 02.11.2026 (PSK tot, „EIN Code = der Relay-Pairing-Code") — **Designvorgabe, die Option (b) je-Gerät-Code berücksichtigen muss** |
| 5 | Gateway-Chat-Mapping | Z.1307–1333 `_stable_gateway_chat_id` / `_relay_chat_id_for` — relay chat_id → Gateway-Chat |
| 6 | Fingerprints | Z.329–339 `fingerprint_of_pub`/`fingerprint_display` (FP-Abgleich-Fenster = Geräteidentitäts-Verifikation) |

### 0.3 App — `hermes-android-bridge` (Kotlin)

| # | Thema | Zeile / Beweis |
|---|-------|----------------|
| 1 | **Verlauf = app-lokal** (SSOT je Gerät) | `ChatHistoryStore.kt` Z.10–25: JSONL „in der APP-PRIVATEN Sandbox (filesDir/chat_history)" hinter AppLock; Z.55–58 `MAX_PER_AGENT = 500`, `MAX_FILE_BYTES = 512 KiB`; Z.73–87 `Entry(seq, agentId, chatId, fromAgent, text, time, status, media*)` |
| 2 | chat_id-Norm | `ChatHistoryStore.kt` Z.76: „chat_id der Session zum Senden (je App-Start neu)" |
| 3 | Merge-Vertrag | `ChatTimelineMerge.kt` Z.17: Dedup-Schlüssel = (agentId-Hash, fromAgent, Text, HH:mm) |
| 4 | Per-Install-Gerätekennung | `PairingManager.kt` Z.331–381 `deviceId()` (Vault-Verband) → `RelayClient.kt` Z.1038–1052 WS-Handshake mit `X-Device-Id` (+ optional Passwort-Header) |
| 5 | Key-Publish | `RelayClient.kt` Z.1109–1111 `publishIdentity()` bei onOpen; Z.2046 `addProperty("device_id", "phone")` — **starr** |
| 6 | E2E je Agent (Phone-Seite) | `RelayClient.kt` Z.650–660: `agentId → aktive (hello_ack-verifizierte) E2E-Session` (Map — je Agent EINE, je Gerät eine); Z.2065–2112 crypto.hello je onlineem Agenten (`/key_get` → `/crypto_hello`); Z.2319+ hello_ack-Verify; `E2eSessionTruth.kt` (relay `session_active` ↔ lokal) |
| 7 | read-Pfeil | Agent→Phone: `RELayClient.kt` Z.1561/2575–2586 empfangen `chat.read.enc`; Relay Z.3609–3635 Agent sendet read-Receipt (verarbeitete Nutzer-Nachricht) — **Phone sendet KEIN read**, „Der Nutzer hat gelesen" existiert als Chip nur agentenseitig |
| 8 | Identity je Install | `IdentityStore.kt`: X25519 Identity+PreKey, AndroidKeyStore-versiegelt, nie exportiert |
| 9 | Einstellungen-Shell | `SettingsHome.kt` (104 Z., AccordionCards-Verband) + `SettingsNav.kt` (54 Z., 2 Routen) — Gerätemanager fehlt komplett |
| 10 | Mediathek | `MediathekIndex.kt` (116 Z.) aus `ChatHistoryStore.load(root, agentId)` (`MediathekTab.kt` Z.457) — Index folgt dem lokalen Verlauf |

**Wesentliche Schlussfolgerung aus 0.x:** Bereits heute ist der **Agent für JEDES Wort im
Chat E2E-Endpunkt** (Nutzer-Fragen kommen per chat.send.enc vom Gerät, Antworten gehen als
chat.reply.enc zurück). Das ist der Hebel: Geräte-Sync braucht keinen zweiten Kanal und kein
Relay-Gedächtnis — der Agent FAN-OUTET und BACKFILLT. Der Relay bleibt opaker Router
(`_AGENT_FRAME_TYPES`-Allowlist Z.2468–2486, „Relay = opaker Pass-Through" Z.2482).

---

## 1. RELAY: `phone_ws` singular → Geräte-Set (= PH5-T2)

### 1.1 Datenstruktur (Zielschnitt)

```
# statt phone_ws singular (Z.83) + phone_device_id singular (Z.157):
devices: dict[str, _PhoneSlot]   # device_id → Slot
class _PhoneSlot:
    ws: WebSocketResponse         # JEDES Gerät EIGENES WS
    device_id: str                # stabil (X-Device-Id, Z.289–303)
    alias: str                    # App-gesetzter Anzeigename (UI, optional)
    connected_at / last_seen
    outbox: _ByteBoundedDeque     # JE GERÄT eigene Outbox (Parität Z.115)
    admin: bool                   # erste = admin (PH5-T7)
```
- Lock-Verband 1:1 von `phone_ws_lock` auf `devices_lock` gebogen; alle heutigen
  Critical-Sektionen (Z.1924–1964, Z.2172–2196, Z.3859–3905, Z.3908–3937, Z.2032–2054)
  behalten ihre Atomar-Form, arbeiten auf `devices[device_id]` statt `state.phone_ws`.
- **Cap:** `ANDROID_RELAY_DEVICE_CAP` (Default **4**, Max 8). Z.4-DoD braucht 2; 4 = Handy+
  Tablet + 2 Handys mit Reserve. Cap ist DoS-Schild (Kap. 8).
- Presence/State (Z.1990–2029): `relay.state` wird pro **Verbindung** gebaut und um
  `devices[]` (maskierte Kennungen `_mask_id`-Parität Z.352–356, alias, online, admin)
  erweitert; `_broadcast_agent_presence` fächert auf ALLE Slots auf.
- Replace-Reconnect **nur eigener** Slot (Parität Z.1951–1964 pro device_id); ein Fremd-Gerät
  kickt NIEMALS (heute: Fremd-Gerät = sofort 4403, Z.1947–1950; neu: Registry-Regel, Kap. 1.2).
- Tool-Weg (Z.4568+): Connectivity-Check „irgendein Gerät verbunden" + Befehls-Hüllposten
  bekommt optional `device_id` (Default = letztes verbundenes Gerät, sticky). PH5-DoD
  verlangt keine Multi-Tool-Parallelität — bewusst klein gehalten (Bau-Block B1).

### 1.2 code-in-use-Gate W17.1 (EIN Code = EIN Gerät) — Entscheidung

**Optionen:**
- **(a) EIN Code = N Geräte** (Geräteliste je State/Nutzer, Admin verwaltet jedes Gerät).
- **(b) Je Gerät ein Code** (N Codes, je Gerät ein eigener Auth-Token).

| Kriterium | (a) EIN Code, N Geräte | (b) je Gerät ein Code |
|---|---|---|
| Minimaler Eingriff | Gate-Logik wird Registry-Logik; Struktur (Bearer-Code) bleibt | N Codes in relay-config + App-Paarungs-Flow für Zweit-Codes + Verwaltung jedes Codes |
| Sir-Bindung „EIN Code für alles" (adapter Z.933–947, 02.11.) | passt | bricht die Order (oder verlangt Sir-Nachbefragung) |
| Passwort-Tor W17.2 (Z.1855–1881) | bleibt 1:1 | je Code ein Passwort? Verwirrung in UI |
| Kompromittierung eines Geräts | Code-Leak → Angreifer kann sich als NEUES Gerät anmelden — aber nur durch das Anmelde-Tor (s. unten), sichtbar + aufhebbar (Revoke) | Code-Leak betrifft NUR dieses Gerät (schärfste Isolation) |
| Verwaltungs-UI | Geräteliste = eine Wahrheit (PH5-T7, das GO setzt das voraus!) | Code-Aushandlung/Ablage = zweites Verwaltungsniveau |
| Test/Suite-Last | W17.1-Suite (test_w17_pairing_slot_password.py) wird auf Registrierungs-Semantik gebogen | Suite bricht breiter (Slot, Rotation, Passwort, mTLS-Epoche) |

**Empfehlung: (a) EIN Code = N Geräte mit Geräte-Registry und Anmelde-Tor.** Begründung:
- Sirs GO nennt „Admin darf jedes Gerät verwalten" = per Definition EIN Verwaltungsraum mit
  Geräteliste — Genau Option (a). Option (b) kauft Code-Isolation mit einem ganzen zweiten
  Aushandlungs-/Ablage-System (woher kommt Zweit-Gerät-Code? Admin erzeugt QR → dritter
  Auth-Verband) — Aufwand ohne das PH5-DoD zu treffen.
- Das Sicherheitsminus gegenüber (b) (Code-Leak = Anmelde-Versuch möglich) wird durch das
  **Anmelde-Tor** kompensiert (das ist die Sicherheits-Mitgift der bisherigen 4403-Gate):
  1. Fremde (`device_id` unbekannt) Handshake = **Anmeldung**, kein stiller Anschluss.
     Default: **Anmelde-Bestätigung** — ein verbundenes Admin-Gerät bekommt
     `pairing.request`-Frame (Kennung maskiert, IP, Zeit) und muss in der App freigeben;
     ohne Freigabe läuft der zweite Handshake in die bestehende IP-Rate-Chain
     (`_auth_record_failure`, Z.693+). Erst-Installation: Registry leer → erste Annahme
     = Selbst-Adoption mit LOUD-Log (Migration, Kap. 6).
  2. Ist das W17.2-Passwort gesetzt, trägt das Anmelde-Gerät es wie heute (Z.1855–1881) —
     Brute-Force-Gang gegen das Tor bleibt im Rate-Schild.
  3. Anmelde-Fenster offen nur während Bestätigungs-Pending (TTL 10 min), sonst
     `code-in-use`-Familientreue-Fehler (`x-bridge-error: device-not-enrolled`).
- Bekanntes Gerät (`device_id` = Registry-Eintrag): eigener Slot, Reconnect ersetzt nur
  seinen eigenen Slot — Exakt-Daumen der W17.1: fremd-kick bleibt unmöglich.

**Gate-Neuform (konkret):** `_pairing_slot_locked()` (Z.306–339) wird zu
`_device_gate(state, device_id) → AUSGANG ∈ {akzeptiert, anmelde-erst-pruefung, abgewiesen}`;
die drei Ausgänge ersetzen 403/4403-Pfade (Z.1901–1910, Z.1947–1950) — Close-Code/JSON-Form
bleiben (4403-Familie + `X-Bridge-Error`), die App muss nur die NEUE Fehlerart kennen.
`is_phone_connected()` (Z.230–236) → `has_connected_device()` (irgendein Slot).

### 1.3 Aufwand: **M** (Bau-Block B1)

---

## 2. CHAT-SYNC (KERN von Sirs Auftrag) = PH5-T4

**Zielbild:** Nutzer schreibt auf Gerät A → sieht die Frage SEBALD auf A; Agent antwortet →
Antwort auf A **und** B; Status/Haken gleich auf A und B; frisches Gerät füllt Verlauf
nach; Weiter-schreiben von B mitten in der Unterhaltung.

### 2.1 Einordnung: der Agent ist der einzige ehrliche Hub

- JEDES Wort geht heute über den Agenten (Nutzer: `/chat` `chat.send(.enc)` → Agent
  Z.4135–4289; Antwort: `chat.reply(.enc)`/`/chat_event` → Phone Z.2662–2689, Z.4304–4400).
  Er kennt den Klartext **notwendigerweise** (Gateway verarbeitet Turn) — Sirs Klartext-Verbot
  richtet sich auf den RELAY, nicht auf die Turn-Verarbeitung.
- Der Adapter ist heute X3DH-Responder je Phone-Hallo (adapter Z.678–723) — Multi-Gerät
  heißt schlicht: **Responder je Gerät**, Session-Map statt `self._crypto_session`
  (Z.962).

### 2.2 Optionen

**Option (a) — Relay hält Verlauf (Server-Speicher, Geräte pullen):**
Nur in Sirs erlaubter Lesart: Relay speichert **ausschließlich E2E-verschlüsselte Blobs**.
Umsetzung wäre Envelope-Verschlüsselung: je Nachricht ein Hülle-CT je Geräte-Speicher-Schlüssel
(oder ein State-Verlaufs-Schlüssel + je Geräte-Wrap) → zweites Kryptosystem neben dem
Ratchet, Verwalten von Speicher-Rotationen, Geräte-Verlust = Hüllen-Rotieren; und der
Ratchet kann gespeicherte Kettenglieder ohnehin nicht (Ratchet-CT ist sessiongebunden —
ein FRISCHES Gerät kann das gespeicherte CT **nicht** entschlüsseln, es bräuchte gerade den
Hüllen-Wege, nicht den Ratchet-Wege). Relay-Verlauf = zusätzlicher Speicher-DoS-Grund +
Retention-Fragen, ohne Feature-Gewinn, weil der Agent den Verlaufs-Bestand ohnehin führt.
**→ VERWORFEN — auch in der nur-verschlüsselten Form: nicht nötig, größerer Angriffs- und
Wartungs-Grundlage.** (Bemerkung: bleibt der einzige Weg, den App-Verlauf auch nach dem Tod
des Agents/Gateway-Re-Install zu bewahren — bewusst NICHT dieses Welle-Ziel.)

**Option (b) — Verlaufs-Replikation Gerät↔Gerät über den Relay (jedes Gerät bekommt alle
Frames live + Backfill je Connect):**
Zutiefst richtig als ZIEL (live-Frames + Backfill). Offen bleibt nur: WER verschlüsselt
Gegenüber und WER backfillt aus welchem Speicher? Zwei Untervarianten:
- **(b1) Agent als E2E-Hub (Signal-Signal-Lesart):** je Gerät eigene Session mit dem Agent;
  Agent FAN-OUTET jeden Turn-Versand je Geräte-Session (einer enc je Ziel); Echo des
  NUTZER-Posts anderer Geräte als `chat.sync.enc` (Agent = Endpunkt); Backfill aus dem
  Gateway-Verlauf, agent-gesendet je Geräte-Session.
- **(b2) Geräte-Gruppenschlüssel:** alle Geräte eines Zustands teilen einen Verlauf-
  Schlüssel (Agent verteilt ihn verschlüsselt je Gerät). EIN Encrypt je Nachricht statt N.
  Kap. 3 bewertet b2 mit Sicherheit — Vor-Votum hier: verworfen (neue Krypto-Primitives,
  Rekey-Zeremonien, größere Kompromittierungs-Zone, N ≤ 4 macht den Gewinn unnötig).

**Option (c) — Hybrid:** (a) für Bestand + (b) für Live. Enthält (a) → verworfen mit (a).

**Empfehlung: (b1) — Verlaufs-Replikation über den Agent als E2E-Endpunkt.**
Das ist minimal-invasiv und Sirs Wortlaut folgt:

| Sync-Teil | Pfad (alles E2E, Relay opak) | Neu am heutigen Design |
|---|---|---|
| **Live Antwort** Agent→alle | Adapter verschickt `chat.reply.enc` je Geräte-Session (N Frames statt 1); Relay fächert je `session_id`→`device_id` auf (Session-Broker bekommt Device-Bezug, Kap. 3). Offline für Gerät X → X's EIGENE Outbox (Kap. 1.1), Flush bei Connect mit J3-Ts-Refresh (Z.3930 Parität) | Adapter: `_crypto_sessions: dict[session_id, AgentCryptoSession]` statt Z.962; Fan-out-Schleife im Reply-Pfad (Z.2662-Parität) |
| **Live Nutzer-Nachricht** A→B | Adapter nimmt A's `chat.send.enc` (Z.4135–4232) an → nach Gateway-Turn-Aufnahme verschließt er den NUTZER-Text als **`chat.sync.enc`** je ANDEREM Gerät (typ = Sync, `origin_device`, `origin_msg_id`, `from_agent=false`, `session_id` je Ziel). Relay leucht nach session_id → Ziel-Slot. **Erhaltende Geräte hängen NUR LOKAL an (ChatHistoryStore.append) und posten NICHT nach /chat** → Echo kann nicht wieder in den Gateway-Verlauf fallen (kein Duplex-Loop) | neues Frame-Set chat.sync.enc (Struktur-Gate-Pass-Through, Allowlist Z.2468–2486 +1), App-Ingest-Regel |
| **Backfill** (frisches Gerät) | Gerät X hello_ack-OK → X sendet (device→Agent) `chat.backfill_request` {gateway_chat, since?, limit}; Adapter/Gateway liest Verlaufs-Zug → `chat.backfill.enc`-Batches (K-Turns, je je Geräte-Session enc — bestehende `chat.reply.enc`-Konstruktion wiederverwendet). App: append in `ChatHistoryStore` — Merge über bestehenden Vertrag (`ChatTimelineMerge` Z.17-Dedup + **msg_id-Idempotenz** statt Minuten-Dedup: neu als msg_id-Schlüssel — s. B5) | Agent-seitiger Export-Zug (Gateway-Konversations-Zug + Adapter), App-Request + Ingest; **kein** Relay-Speicher |
| **Status-Haken** | 2 Wege (je je Richtung):
 1. `chat.status` (ids-only: chat_id/msg_id/status/ts, Z.3547–3555) **broadcast** an ALLE Slots — Nutzer-Nachrichten-Chips (queued/delivered/read) sind agentenseitige Verarbeitungs-Receipts; echo-Blasen tragen DENSelben `origin_msg_id` → chip-Anzeige konsistent über Geräte.
 2. read-Status des Nutzers für Agenten-Antworten: heute **KEIN** phone-seitiges send (Kap. 0.3 #7) — bleibt so; die „gelesen"-Chip-Wahrheit = Agent-verarbeitet, dadurch geräteinvariant.
 **Kein Chat-Inhalt im Klar-Form** — genau Sirs Anforderung (3); msg_id/chat_id = Routing-Metadaten (heute schon Klar in enc-Modi: Z.2878–2880 „msg_id bleibt KLAR"). | `_deliver_to_phone` (Z.3859) → `_deliver_to_devices` (Broadcast je Status-/Presence-Frame); Relay sendet zusätzlich `chat.status_snapshot` (ids-only, Cap 500 neueste) an frisch verbundene Geräte → Chips-Stand auch nach Backfill |
| **Presence/Arbeiten** | `agent.status`/`chat.activity` (working-Klar nach heutiger Design-Entscheidung Z.2928–2930 — bewusst KEIN Inhalt, Arbeitszustand) → Broadcast je Wechsel (Z.3669–3732 unangetastet, Deliver fächert) | Deliver-Fan-out |

**Warum (b1) die sicherste Option ist (Kurzbegründung, vertieft in Kap. 3/8):**
- Der Relay hält **keine** Verlaufs-, **keine** Schlüssel-, **keine** Klartext-Inhalte
  (Status-Frame = ids-only; presence = Agentenliste). Relay-Kompromittierung leitet
  Verschlüsseltes um, liest nichts (heutiger Vertrag: „Relay entschlüsselt INHALTE nie",
  Z.680–686 Kommentarblock; bleibt).
- Schlüssel-Fläche wächst minimal: exakt bestehende X3DH/DoubleRatchet/Primitives (adapter
  Z.419–723), nur N-mal instanziert. Kein neues Krypto-Primitive (im Kontrast zu b2).
- Geräteverlust = ein Registry- + Pin-Eintrag weg (Kap. 6/8) — keine Rekey-Zeremonie.
- Echtheits-/Anti-Nachbildung: AAD bindet `session_id|chat_id|agent_id` (adapter Z.370–396)
  — je Geräte-Session eindeutig; ein_sync-Frame, der nicht unter der echten Ziel-Session-Kette
  erzeugt wurde, fällt beim Entschlüssellen durch.

### 2.3 Konsistenz-Regeln (Haken & Verlauf über Geräte)

1. **msg_id ist der globale Einheitsschlüssel** einer Nutzer-Nachricht: erzeugt am Relay
   beim `/chat`-Zustand (Z.4195 clear + enc Z.4195), echo trägt ihn mit → App speichert
   Blase mit `origin_msg_id` → chip-Frame (Z.3547) trifft die Blase auf JEDEM Gerät.
   (ChatHistoryStore.Entry bekommt msgId-Feld heute nicht — neu in B5; status-Snapshot
   und Chips ziehen darüber.)
2. **Nur Agenten erzeugen Inhalte-Verlaufs-Änderungen** (Antwort, Backfill, Echo) —
   Geräte schreiben nie gegenseitig: keine Merge-Konflikte in der Platte, SSOT je Gerät
   bleibt (App-lokal); Konvergenz der Geräte-Kopien ist Ereignis-getrieben.
3. **Agent-seitige Verlaufs-Zustände** (Gateway-Konversation) bleiben die EINE Turn-Wahrheit;
   App-Plattenspiegel ist Ansichts-Kopie + Offline-Bestand. Damit ist „weiter-schreiben von B"
   trivially: B's Frage geht unter B's Session in DENSELBEN Gateway-Verlauf — A sieht sie
   als Echo. Dazu muss das Gateway-Chat-Mapping **nutzer-weit** sein: relay chat_id (je
   Geräte-Sitzung andere) → DENSELBEN Gateway-Chat je (nutzer, agent) (adapter Z.1307
   `_stable_gateway_chat_id` wird auf Namespace+Agent-Norm gebogen — sonst entsteht je
   Gerät ein GESPLITTETER Hermes-Verlauf = Verlaufsmischung in die falsche Richtung).
4. Offline-Mitte: A schreibt, B offline → echo in B's Outbox (ByteBounded 500, 24 h, J3-Ts
   Refresh); B online → Flush. A offline, B online: chat_inbound je Agent (Z.113) trägt die
   Frage bis zum Gateway-Turn; echo entsteht erst nach Verarbeitung → B's Sync-Verzögerung
   = Agent-Erreichbarkeit (ausdrücklich: Agent offline = kein Live-Sync — dokumentiert).

### 2.4 Aufwand: **3 × L-Blöcke** (Adapter-Fan-out B3, Gateway-Chat-Vereinheit B4,
App-Ingest/Platte B5, Backfill B6 — s. Kap. 6)

---

## 3. E2E bei Multi-Gerät (= PH5-T3)

**Frage:** JE Gerät eigene E2E-Session mit JEDEM Agent? — oder Geräte-Gruppenschlüssel?
(= Sirs Anforderung 2: JEDE Verbindung zweier Geräte über den Agent als E2E-Endpunkt ODER
begründeter Gruppenschlüssel.)

### 3.1 Optionen

**(I) Je Gerät × je Agent eigentständige Session (empfohlen):**
- Relay: /crypto_hello bekommt **Device-Bezug**: X-Device-Id-Header-Pflicht im Multi-Modus
  (das WS-Handshake kennt ihn, Z.289–303); `crypto_sessions[session_id]` (Z.140–144,
  Z.4111–4115) um `device_id` erweitern; Ziele aller Agent→Phone-enc-Frames durch
  session_id→device_id (Session-Broker = Routing-Wahrheit).
- Adapter: Session-Map je (agent, device/ik_pub); die TOFU-Pin-Datei (adapter Z.927–932)
  wird zu einer **Pin-Map je ik_pub** (je Geräte-Identity ein Eintrag, Cap 8/Agent, je NEUEM
  Pin EIN Loud-Ereignis mit B2-Dedupe Parität Z.1915–1921). „Je Gerät mit je Agent
  eigentständige Sessions" — JA, genau das.
- App: RelayClient hält je Agent (map Z.650) sein EIN lokales Gerät → kein App-Change bei der
  Session-Norm; Identity je Install (IdentityStore) bleibt — JEDE Install ist ein eigner
  Kryptounternehmer (Handy ≠ Tablet = eigene ik_pub).

**(II) Geräte-Gruppenschlüssel (Sender-Key-Form):** alle Geräte eines Zustands teilen einen
Send-Schlüssel je Agent; Agent verteilt ihn (verschlüsselt je Geräte-Session, TOFU-Pin).
Vorteil: EINE Verschlüsselung pro Nachricht am Agent. Nachteile (Sicherheits-Analyse):
- **Key-Zeugnispflicht/Verteilung:** neue Primitive (Sender-Key/Multicast-Ratchet) auf BEIDEN
  Enden — heute getesteter Ratchet (adapter + App-Tests) wird parallel mit Gruppen-Krypto
  dupliziert; Verifikations-/FP-Abgleich-Fenster müsste je Beitrag je Absender neu gedacht
  werden (FP = je Pair-Verband heute).
- **Geräte-Verlust = was leckt:** der Gruppen-Send-Schlüssel des verlorenen Geräts entschlüsselt
  die GESAMTE Zukunft des Gruppen-Streams bis zum Rekey → Rückruf = Voll-Rekey-Zeremonie
  (alle Geräte, alle Agenten, je nach Verlaufs-Lesart sogar Bestand) — im Kontrast zu (I):
  Wegwerfen eines Session-Zustands + Pin-Eintrag.
- **Kompromittierungs-Zone:** ein Gruppen-Schlüssel-Leak = alle Geräte × alle Agenten ×
  Zukunft; das ist genau die Vergrößerung, die Sirs „großen Wert" schützt.
- **Rückruf-Echtheit:** Rekey-Vertrauen ist ein eigener Angriffsgrund (Rückruf-Frame-Fake,
  Rekey-Verdränger).
- **N ist klein (Cap 4):** Der Fan-out-Aufwand (N enc statt 1) ist vernachlässigbar — Gateway
  erzeugt je Beitrag ohnehin Turn-Weise (nicht Byte-Strom-Multiplizierung).

**→ Empfehlung: (I) je Gerät × je Agent eigentständige Sessions — JEDE Frame-Verbindung
zweier Geräte läuft über den Agent-Endpunkt (Sirs Variante 1), kein Gruppenschlüssel.**
Sicherheits-Begründung: kleinste neue Krypto-Fläche (0 neue Primitive), je-Verwand-Verlust
(1 Session weg statt Rekey-all), AAD/session_id-Bindung bleibt intakt, FP-Abgleich bleibt
je Gerät ehrlich.

### 3.2 Neben-Konsequenzen aus (I)

- `E2eSessionTruth` (App-Datei) bleibt — aber der Relay's `session_active`-Feld-Wahrheit
  (Z.2011–2015) wird **je Verbindung** berichtet (hallo-Frame ist je WS eigen — das Feld
  nimmt die Session-Lage DES GERÄTS): App-Norm `missedReset`/`needsHandshake` bleibt exakt.
- `crypto_hello` Re-Play/Rebind (Z.4090–4110) bleibt — je Geräts Session separat → kein
  Cross-Gerät-Verdränger (der heutige `cross-session-takeover`-Befund (Z.2493–2497) gilt
  weiter für FRÉMD-Sessions; im Registry-Modus ist „zweite Geräte-Session" der Norm, die
  Warnung muß auf Registry-Wahrheit eingestellt sein (bekanntes Gerät ≠ Verdränger) — B7).
- `key_get` (Z.4000+) bleibt je Agent (Agent-Keys sind geräte-unabhängig — Phone-Haltung
  `device_id="phone"`-Eintrag (Z.3970/App Z.2046) wandert in die v2-Registry (Kap. 6).

### 3.3 Aufwand: **L** (B3-Adapter + Teil von B1-Relay)

---

## 4. APP-SEITE (= PH5-T4/T7-App-Umfang)

### 4.1 Verlauf-Ansicht bei frischem Gerät

- **Verbindungs-Lauf:** Connect (Bearer + X-Device-Id, Z.1046–1052) → `relay.state`
  (erweitered um `devices[]`, je Agent `session_active` DES GERÄTS) → hello je onlineem
  Agenten (Z.2065–2112 bestehend) → nach hello_ack: wenn **kein** localer Verlaufs-File
  für agentId (ChatHistoryStore-Datei existiert nicht, Z.96–103) → sende `backfill_request`
  → erhalte `chat.backfill.enc`-Batches → append in Platte (Ziel-Kap 500/512KiB-Verband
  unverändert Z.55–58) → UI rendert wie heute (ChatScreen Z.234+).
- **Verlaufs-Reichweite:** Gateway-Verlauf-Export je (nutzer, agent), Kap-Regel `K = 200
  Einträge je Agent` (env-tunbar) + media-Verweise wo Bestand im Media-Dir liegt — nicht
  liegende Mediennachrichten als „Platzhalter-Blase" (Klartext-Text vorhanden, media-Id als
  Verweis). Nachbestellung: `media_refetch`-Frame → Agent re-enc je Geräte-Session aus dem
  Media-Dir (Retention am Agent, adapter Z.989–999 — bewusst Best-Effort, kein
  Voll-Mediatheken-Spiegelversprechen).
- **Statushken-Stand:** Relay schickt bei Connect `chat.status_snapshot` (ids-only, Cap
  500; Zustand aus chat_status-Map Z.3525, 24-h-Retention ehrlich — älterer Chip-Stand =
  ehrlich „unbekannt"). Chips sind dadurch nach Backfill konsistent soweit Broker-Wahrheit
  existiert.
- **Mediathek:** `MediathekIndex.fromEntries(load(root, agentId))` (MediathekTab Z.457) —
  Index folgt der Platte automatisch; fehlende Bytes = Platzhalter-Regel (kein Partial-Crash).

### 4.2 Gerätemanager-UI (Einstellungen, PH5-T7)

- `SettingsHome`/`SettingsNav` (104/54 Z.) bekommt **Geräte-Card**:
  - Liste `devices[]` aus relay.state: alias („Fabians Handy"/„Tablet"), maskierte
    Kennung (`_mask_id`-Parität), online-Sind, last_seen, admin-Flagg.
  - Aktionen je Gerät (PH5-T7: ADMIN DARF JEDES GERÄT VERWALTEN — kein admin-only-Gerät):
    „Entfernen" (revoke), „Umbenennen" (alias, optional Phase 2), „Dieses Gerät =
    Abmelden" (self-decouple).
  - Revoke = 2-schrittig (Bestätigung + Passwort-Eingabe, wenn W17.2 gesetzt — Parität
    Z.1520–1541) → `device_revoke`-Frame an den Relay → Slot schließen (4403-Familie:
    `device-revoked`) + Registry-Eintrag löschen + Adapter-Session/Pin-Kap (Kap. 3),
    LOUD-Log + Banner auf Rest-Geräten.
  - Anmelde-Tor-UI: `pairing.request`-Frame → Banner „Neues Gerät koppeln? (Kennung, IP,
    Zeit)" → Freigabe/Abweisung (Kap. 1.2).
- **Takeover-Banner-Norm korrigieren (B8):** `cross-session-takeover` (Z.2493–2497) war
  ein Ein-Gerät-Signal. Im Multi-Modus: Fremd-Session eines UNBEKANNten Geräts = Anmelde-
  Tor-Abweisung; echte Registry-Geräte erzeugen keine Takeover-Warnung mehr (sonst
  Dauer-Fehlalarm je echtem Zweitgerät).

### 4.3 Aufwand: **M + M** (B5 Ingest/Platte, B7 UI)

---

## 5. MIGRATION (bestehendes Ein-Gerät-Setup → Multi-Gerät ohne Verlust) = PH5-T1/T5-Bezug

| Bestand | Heute | Ziel-Migration (ohne Verlust) |
|---|---|---|
| WS-Slot | `phone_ws` singular + `phone_device_id` (Z.83/157) | Geräte-Registry leer → **erste Annahme = Selbst-Adoption** (device_id des bestehenden Geräts, LOUD-Log, kein Zwingen-Kopplung); Registry-Mode aktiv ab 1 Eintrag |
| phone-key-Registry | `relay-keys.json` `{"v":1,"device":entry}` (Z.976–1006), App sendet `device_id="phone"` (App Z.2046) | **v2:** `{"v":2,"devices":{device_id: entry}}`; Adoption: bestehender `device`-Eintrag wandert beim nächsten `/key_publish` (App schickt je Install ECHTE `deviceId()` — App-Änderung Z.2046) unter seinen echten Schlüssel; Alter-Eintrag wird ehrlich gelöscht, NICHT still verdoppelt (Identity-Wechsel-Warnung Z.3976–3989 bleibt) |
| Agent-TOFU-Pin | `phone-pin.json` EIN `ik_pub` je Agent (adapter Z.927–932, Z.1878–1928) | **v2:** Pin-Map je `ik_pub` (Adoptie: bestehender Pin = erstes Gerät; je NEUEN Geräte-Pin LOUD + B2-Dedupe); je Agent Cap 8 Pins (RAM + Angriffs-Grund) |
| Agent-Sessions | EIN `_crypto_session` (adapter Z.962) | Session-Map; **bestehende Session bleibt** (kein Neustart-Nötig, kein E2E-Verlust); neue Geräte = neue Hellos |
| App-Verlauf | `ChatHistoryStore` (Z.55+) | **Keine Schema-Änderung nötig** — Sync/Echo/Backfill sind additive Frames; NUR msgId-Feld neu (B5, tolerant: alte Zeilen ohne msgId = kein chip-Verbund, Verlauf-Verlust NEIN) |
| Outbox | EINE `phone_outbox` (Z.115) | Split: beim Upgrade werden bestehende Einträge auf den EINEN adoptierten Slot übernommen (keine Spol-Verlust) |
| Chat-Verläufe (Gateway) | relay chat_id → Gateway-Chat (adapter Z.1307) | Namespace+Agent-Norm (Kap. 2.3 #3) — bestehende Mappings bleiben (kein Verlust), Zweitgerät-Chat_ids fallen in denselben Gateway-Chat |
| cross-session-takeover | Warnung je 2. Session | Registry-Norm (Kap. 4.2) |

**Invarianz-Sätze (Migrations-Akzeptanz):** (1) bestehendes Gerät koppelt NICHT neu;
(2) bestehende E2E-Sessions und Verläufe bleiben; (3) bestehender Spool bleibt;
(4) Rollback = Relay v1-Verband mit alter binary — App bleibt v2-tolerant (neue Frames
wie heute: Allowlist-Verwurf + Log — bewusst Additiv).

---

## 6. AUFWAND + BAU-REIHENFOLGE + GO-TEILE

**Alles unten ist NUR mit Sirs GO gebaut** (dieser Plan ist NUR-LESEN + Plan-Datei). Je
Block: (S ≤ 1 Tag, M ≤ 1 Woche, L > 1 Woche) — je Phase-Ende QA-Jagd nach EXECUTIONSTATE
(4 Jäger + Pen-Test je Backend-Modul B2-Gate, EXECUTIONSTATE Z.68–84).

| Block | Inhalt | Größe | Reihenfolge |
|---|---|---|---|
| **B1** | Relay Geräte-Registry + Slots + Anmelde-Tor (Kap. 1) | M | 1 |
| **B2** | Key-Registry v2 + /crypto_hello Device-Bezug + Session-Broker device_id (Kap. 1/3) | S | 2 |
| **B3** | Adapter je-Gerät-Sessions (Map, TOFU-Pin-Map, Fan-out, chat.sync.enc, Backfill-Quelle) + Gateway-Chat-Namespace-Norm (Kap. 2/3) | L | 3 |
| **B4** | (in B3 eingeschlossen) Echo-Verband + Loop-Zuverlässigkeit (kein Re-Post), Turn-Duplex-Schulung | (L) | — |
| **B5** | App: Sync-Ingest (Echo/Status-Snapshot/Platte + msgId-Feld), backfill_request, Multi-WS-Toleranz, Takeover-Banner-Norm | M | 4 |
| **B6** | Backfill-Gesamt (agent → `chat.backfill.enc`, media_refetch, Status-Snapshot) | M | 5 |
| **B7** | App-Gerätemanager-UI (Geräte-Card, Revoke, alias, pairing.request) | M | 6 |
| **B8** | Migration + v1↔v2-Verband + LOUD-Adoption + Pen-Test-/DoS-Drill (Kap. 5/8) | M | 7 |

Gesamt-Größe: **3×L + 4×M + 1×S** (≈ 2–3 Bau-Wellen mit QA-Wellen). Reihenfolge Begründung:
Registry vor Krypto (Tor-Grund), Adapter vor App (Sync-Quelle), Migration zuletzt.
**Bewusst NICHT mit GO gebaut (Go-spezifische Auslassung):** Option (a) Relay-Verlauf auch
nur-verschlüsselt (verworfen, Kap. 2.2), Gerätes-Gruppenschlüssel (verworfen, Kap. 3),
je-Geräts-Codes (verworfen, Kap. 1.2), Tool-Weg-N-Geräte-Verwaltung (nur sticky-Default,
Kap. 1.1), Mediatheken-Voll-Mirror (Best-Effort-Platzhalter, Kap. 4.1).
**Sir-Decisions im Bau (Einzel-GO-Punkte):** Device-Cap-Zahl (4?), Backfill-K (200?),
Anmelde-Tor-Art (Freigabe-Plicht vs. Passwört-nur), Tool-Default-Geräte-Politik.

---

## 7. VERSCHLÜSSELUNGS-VERBAND (was läuft wo enc — Sirs Kapitel)

**Transportebene (immer, unverändert):** TLS auf jeder Beine (Relay-Beine über den NPM-Proxy,
mTLS-Gate für Geräterouten Z.1380–1450); Agent-WS je Auth via Agent-Token.

**E2E-Ebene (fb-v1: X3DH + DoubleRatchet, AAD = `fb-v1|session_id|chat_id|agent_id`,
adapter Z.340–396):** je Gerät × je Agent eine Session (Kap. 3). Multi-Gerät = Fan-out
je Session — **KEIN** Transport-Verband, KEIN Gruppen-Primitive.

| Daten | Wo | Klartext? | Begründung |
|---|---|---|---|
| Chat-Texte (Nutzer + Agent-Antworten) | Geräte↔Agent | **immer E2E-ct** | Downgrade-Schütze bestehend (Z.2678–2683, Z.4246–4254) — Klartext-Verband nur für reset-te Legacy-Agenten (v0.14, Z.4246–4248) |
| Sync-Echo (Nutzer-Text A→B) | Agent→Geräte | **E2E-ct je Ziel-Session** (chat.sync.enc) | Sirs Anforderung 2 (Agent = Endpunkt) |
| Backfill (Gateway-Verlag-Export) | Agent→Gerät | **E2E-ct je Geräte-Session** | Der Verlaufs-Bestand läuft nie in Klar von Agent zu neuem Gerät |
| Medien-Bytes + media-Chunk | App↔Agent | **E2E-ct (Media-Chunk, `Klaertext-Media existiert NICHT`)** Z.4400–4430) | unverändert |
| session_meta (Modell/Effort/Sub-Agent) | Agent↔Geräte | E2E-ct (chat.session_meta.enc-bestehend), Klar-Feld nur Legacy | W16-Verband bleibt |
| Chat-Status-Haken | Relay→alle Geräte | **Klar — ids-only** (chat_id/msg_id/status/ts) KEIN Inhalt | Routing-Metadaten, relay kann Status nicht enc (msg_id-Verband); Sir-Anforderung 3 ist erfüllt: **keine Inhalte** |
| chat.activity/agent.status (working) | Agent→alle Geräte | working-Wert Klar (bewusst, Z.2928–2930), Rest (Label/session_meta) enc | Arbeitszustand = kein Chat-Inhalt; Dedup-Engine braucht Klar-Verband |
| relay.state / presence / devices[] | Relay→Geräte | Klar (Agentenliste + Gerätemetadaten maskiert) | Registry-Verband, kein Chat-Inhalt; Kennungen nach `_mask_id` geloggt/gesenden (Z.352–356) |
| /chat-POST-Body (enc-Modus) | Gerät→Relay→Agent | ct (text-Feld verboten Z.4168–4171) | unverändert |
| Verlaufs-Speicher (Gateway-Seite, Agent-Prozess) | Agent-Host | Klartext (Gateway-Turn-Verarbeitung) — **nicht am Relay** | Notwendig für Turn; Sirs Klartext-Verbot gilt dem RELAY-Verband — Dokumentiert & von Relay-Gedächtnis getrennt |
| App-lokale Verlauf-Platte | Je Gerät | Klartext hinter AppLock (heutige Entscheidung, ChatHistoryStore Z.17–19) | bewusst — Vorschlag-Option für ENC-local-Store = SEPARATES zukünftiges Thema (kein W20-Ziel) |
| **Verlauf am Relay** | — | **KEIN Verlauf-Speicher (auch nicht verschüsselt) — verworfen** | Kap. 2.2 |

**Wortlaut-Unterschieden:** Sirs „Chat-Sync komplett verschlüsselt übertragen" ist via
(b1) erfüllt: Alle Sync-Frame-Klassen, die Inhalte (Texte/Media/Meta) tragen, laufen je
Geräts-Session E2E-ct über den Agent-Endpunkt — der Relay sieht nur Routing-Metadaten.
Frames, die heben KEINE Inhalte (Status/Presence), bleiben bewusst Klar (keine
Verschlüsselung-Norm-Sicht über ids hinaus), ohne Chat-Inhalt.

---

## 8. SECURITY: NEUE ANGRIFFS-GRUNDLAGEN + SCHUTZ (je je Schutz + warum gewählte Option am sichersten)

| # | Neue Angriffsfläche | Szenario | Schutz (begründet) |
|---|---|---|---|
| 1 | **Code-Leak → Zweit-Gerät-Kopplung** (statt heute 4403-Gate, Z.306–339) | Leakt der Code, kann sich ein Angreifer als NEUES Gerät anmelden | **Anmelde-Tor** (Kap. 1.2): Freigabe-Plage je verbundener Admin (sichtbarer pairing.request je Versuch), optionaler W17.2-Passwort-Tor, IP-Rate-Chain (bestehend Z.693+), Cap 4 (max 8) — ein Versuch ist LOUD + aufhebbar. Warum sicherste Option: (b) je-Gerät-Code isoliert besser, braucht aber ein neuartiges Aushandlungs-/Ablage-System (Angriffs-Grundlage je Code-Aushandlung selbst) und bricht Sirs EIN-Code-Order; die Anmelde-Tor-Einweihung macht (a) ehrlich sichtbar statt still verdrängend (heute: Fremd-WS = 4403-Abweisung, kein Sichtfenster) |
| 2 | **Gerätelisten-/Registry-DoS** | Flooding von Anmelde-Versuchen/Registry-Wachstum | Cap (4/8), je Slot EIN Gerät = Registry-Größe ≤ Cap + Anmelde-Pending ≤ 1 je State + TTL; W17.1-IP-Chain + WS-Limits (Z.1916–1920) bleiben; je-Geräts-Outbox bleibt ByteBounded (Z.850–858) → Flooding-Geräte schaden nur ihrer eigenen Queue, nicht anderen |
| 3 | **Sync-Flood** (A-Geräts-Fragen → je Fan-out) | Ein Geräte-Verband spammte N Geräte je Frage | Chat-Rate je Geräte-Bearer je `/chat` (bestehend `chat:{remote_ip}` Z.4150 → je device_id je IP), Sync-Echo-Verband je Agent-Bucket (Paritäts-chat_event Z.2675), Outbox-Caps je Gerät — Fan-out ist lineares N ≤ Cap, kein Multiplikator > Cap |
| 4 | **Verlaufs-Leak bei Relay-Kompromittierung** | Relay-Prozess kompromittiert (Speicher-Freeze, Logs) | **Kein Verlauf am Relay** (Kap. 2.2 verworfen) — kompromittierter Relay sieht nur: ct (opak), msg/chat/session-ids, Geräte-Metadaten (maskiert), Fingerprints (öfters publik, Z.947–955), Key-Pubs. Keine Klartext-Verläufe, keine privaten Keys (Vertrag Z.680–686 Kommentarblock + relay-keys = pubs-only Z.976–990). Warum sicherste Option: (a) legt selbst-nur-ct-Verlauf in das kompromittierte System — Erhöhung des Schadens-Slots ohne Feature (Kap. 2.2); (b1) hält das Kompromittierungs-Ziel = Routing-Platte |
| 5 | **Geräteverlust** (Handy weg) | Fremdzeigriff auf verlorenes Gerät: app-lokale Platte (behind AppLock) + Verlaufs-Fortlauf | Revoke (Kap. 4.2): Registry-Eintrag + Slot gelöst, session/echo-Zieli-Verband weg je Agent (Kap. 3), LOUD-Banner je Rest-Gerät; Gate/Tool-Beine des verlorenen Geräts fallen (4403-Familie device-revoked). Verlust-Mitgift (heutige Norm): App-Platte bleibt AppLock-geschützt, Identität Keystore-versiegelt (IdentityStore) — Klartext-Platte bleibt eine bewusste Rest-Norm (Kap. 7). KEIN Gruppen-Primitive: kein Rekey-all (Kap. 3.1) — Verlust kostet EINE Session statt der Gruppen-Schlüssel |
| 6 | **Sync-Echo-Loop / Echo-Manipulation** | bösartiges/zirkulierendes Sync-Frame | Sync-Frame = Agent-generiert je Ziel-Session (Ratchet-Kette + AAD); Geräte-Ingest-Regel: **Echo NUR lokale Platte, KEIN Re-Post** (Kap. 2.2-Regel) — kein Loop möglich, sogar bei fehlerhaften Apps; msg_id-Idempotenz verhindert Doppel-Blase |
| 7 | **Registry-/Tool-Verband-Verwirrung** | Tool-Weg (screenshot/mic, Z.4568+) schickt an FALSCHES Gerät | Sticky-Default = letztes verbundenes Gerät + optionaler `device_id`-Zusatz je Befehl; Connectivity-Feinprfung „bezug-Gerät existiert" (503 mit Geräte-Name) |
| 8 | **Anmelde-Tor-Bestätigung-Phish** (pairing.request-Frame-Fake) | Agent-seitig? — relay generiert pairing.request NUR lokal nach WS-Handshake-Versuch; kein Agent-Bein | pairing.request kommt nur vom Relay auf der Admin-WS (nicht Agent-Routing), Kennung/IP maskiert, TTL je Frame — die App zeigt NUR die zuletzt empfangene Anfrage; Revoke- und Freigabe-Aktionen brauchen keinen zweiten Auth-Bearer (WS-Bein ist bereits authentifiziert) |
| 9 | **mTLS-Epochen-/Registry-Verband** | Alt-Gert-Zertifikate nach Code-Wechsel | P43 (Z.1415–1437, 17.11. Fixwelle) bleibt; Registry-Revoke zieht Geräterouten-Verband (Kap. 4.2) |

**Sicherheit-Summe der gewählten Architektur (b1 + Anmelde-Tor + Cap):**
- Krypto-Primitives: **0 neu** (bestehende X3DH/Ratchet/Replay/Fingerprint-Verband, je
  Gerät instanziert).
- Kompromittierungs-Zonen: Relay = Routing-Platte (kein Verlauf, kein Key-Material);
  verlorenes Gerät = EINE Session + lokale Platte (AppLock); Agent = Turn-Verarbeitung
  (notwendigerweise Klartext, nicht Relay).
- Sichtbarkeit: JEDE neue Geräte-Anmeldung, JEDE Identity-Änderung, JEDES Revoke ist LOUD
  (Logs maskiert + Banner-Frames) — keine stillen Anschluss-/Verdränger-Pfade über Geräters.

---

## 9. PH5-Task-Mapping (EXECUTIONSTATE-v1.md Z.208–220)

| PH5-Task | Umsetzung in diesem Plan |
|---|---|
| PH5-T1 Namespace-Modell (Token = namespace:device_id:token_kind) | B1-Registry (Token bleibt EIN Code je State, „token_kind"-Ebene = device_id-Verband + Bearer je Route — bewusst schmaler als Token-Dreiklang, da Sir-Order „EIN Code") |
| PH5-T2 Multi-Device je Namespace (je Device eigener Relay-Slot) | Kap. 1 (Geräte-Set je device_id, Cap 4) |
| PH5-T3 Device-Registry je Namespace (eigene Device-ID + Fingerprint + Status) | Kap. 1 + Key-Registry v2 (B2) + je Agenten-Fingerprint (bleibt je ik_pub-Pin-Map, Adapter) |
| PH5-T4 Session-Routing je Device+Agent (keine Verlaufsmischung) | Kap. 2/3 (je-Gerät-Session, Namespace+Agent-Gateway-Norm, Echo/Backfill) |
| PH5-T5 Cross-Namespace-Isolation | Kap. 8 #1/#4 (+ Owner-Map bleiben je Agent, Z.3565+ — Cross-Agent-Geräte-Isolation = unverändert) |
| PH5-T6 Agentenschutz je Route | unverändert (adapter Z.1091–1116 URL-Pin, Outbox-Norm) + je-Geräts-Session-Caps |
| PH5-T7 Admin = je Gerät je Namespace verwalten | Kap. 4.2 (Gerätemanager-UI, Revoke je Gerät, alias, kein admin-only-Gerät) |
| QA-Verankerung | je Block-Ende: 4-Jäger + Live-Check (EXECUTIONSTATE Z.68–84) + B8 Pen-Test-Drill (DoS/Sync-Flood/Revoke/Verlust-Fall) |

## 10. Test-Matrix (Akzeptanz-Kernproben)

1. Zweit-Gerät-Kopplung: Anmelde-Freigabe → Slot + Registry + relay.state-devices[].
2. Fan-out: Antwort erscheint auf A und B (E2E-ct je Session), Echo-Nutzer-Text A→B, KEIN Doppel-Turn im Gateway.
3. Weiter-schreiben von B: B's Frage → gemeinsamer Gateway-Verlauf → Antwort auch auf A (Echo-Verwandtschaft gespiegelt).
4. Frisches Gerät: backfill 200 Einträge + Status-Snapshot-Chips + Mediatheken-Platzhalter; Kap-Caps (500/512KiB) respektiert.
5. Revoke: Gerät-Verlust-Simulation → Slot schließt (device-revoked), Session/Pin je Agent weg, Rest-Geräte-Banner, Tool-Weg zeigt 503 mit Geräte-Kontext.
6. DoS-Drill: Anmelde-Flooder (TTL/Cap), Sync-Flood (je-Geräts-Rate), Outbox-Flood je Gerät (ByteBounded).
7. Migration: v1-Bestand → Selbst-Adoption KEIN neue Kopplung, bestehende Sessions/Verläufe/Spool unverändert, Rollback-Verband (App tolleriert neue Frames).
8. Downgrade: enc-Modus ohne bestätigte Session je GERÄT → 403 (Parität heute, je-Geräte-Verband statt je-Agent).
