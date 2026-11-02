# FlipsiBridge-Chat-Adapter (Hermes-Plugin, Skeleton)

Hermes-Platform-Adapter (PH-2 / P3-Skeleton) für den FlipsiBridge-Chat: der Adapter
läuft **in einer Hermes-Agent-Instanz** und verbindet sich **OUTBOUND** zum
FlipsiBridge-Relay — wie das Handy NAT-frei, damit Agenten aus Containern und
getrennten Homelabs denselben Relay nutzen. Chat-Texte landen als **Gateway-Messages**
in normalen Sessions (voller Tool-/Memory-/Skill-Stack wie bei Discord/Telegram),
Antworten gehen über `send()` zurück.

## Aufbau

```
plugin.yaml        Plugin-Manifest (requires_env: URL + Token + Agent-ID je Agent-Heim)
adapter.py         FlipsiBridgeChatAdapter(BasePlatformAdapter) + register(ctx)
_base_fallback.py  Schnittstellen-Spiegel NUR für Repo-Skeleton/Selbst-Test ohne Hermes
config.example.yaml Config-Baustein (generische Install-Skizze, s. unten)
test_adapter.py    Selbst-Test mit Mock-WS: connect / send / receive-Roundtrip / Pinning
```

## Schnittstelle (Referenz: `gateway/platforms/base.py`)

| Methode | Verhalten |
|---|---|
| `connect()` | WS-Verbindung OUTBOUND zur Agent-WS-Route (Default `/agent_ws`, per `FLIPSI_WS_ROUTE` anpassbar — ROADMAP P1 benennt die Route `/agent/ws`, beide Namen bleiben damit kompatibel) + Bearer-Agent-Token + `agent.register`-Envelope + Frame-Loop |
| `disconnect()` | Frame-Loop canceln, WS + Session schließen |
| `send()` | JSON-Envelope (`type=chat.reply`, `route=/chat`) über die Agent-WS |
| `send_typing()` | `typing`-Event (gedrosselt, kein Polling) |
| `send_image()` | Bild-URL-Envelope (Datei-Chunk-Stream = eigener P6-Task) |
| `get_chat_info()` | `{name, type: "dm", chat_id}` |
| Empfang | Frame-Loop: `chat.deliver` → MessageEvent → `self.handle_message()` — **immer Nachricht, NIE ein Tool-Call** |

## Agentenschutz (ROADMAP I3, im Adapter durchgesetzt)

1. **URL-Whitelist-Pinning:** `connect()` verweigert JEDE URL, die nicht exakt in
   `FLIPSI_ALLOWED_URLS` steht (Default: genau die 1 konfigurierte Relay-URL). Kein
   Fallback auf „beliebige Backends"; das Agent-Token geht nur an den gepinnten Host.
2. **Schema-Pflicht:** nur `wss://`/`https://` (`ws/http` nur mit `FLIPSI_ALLOW_INSECURE=1`
   für lokale QA-Drills); userinfo in der URL wird abgelehnt.
3. **Namespace-Prüfung:** `chat.deliver` mit fremder `agent_id` wird abgelehnt und geloggt.
4. **Keine Tool-Call-Pfeife:** der Frame-Loop akzeptiert nur Envelope-Typen aus einer
   Allowlist (`chat.deliver`, `chat.ack`, `roster`, `presence`, `pong`). Alles andere —
   insbesondere Kommando-/Tool-Semantik — wird ignoriert + als `rejected_frames`-Eintrag
   diagnostiziert. Chat-Text ist Daten, niemals Steuerbefehl.
5. **Geräte-Whitelist:** optionale `FLIPSI_ALLOWED_DEVICES` schränkt chat_ids ein.
6. **Token-Hygiene:** Agent-Token nur im Auth-Header, nie im Envelope, nie im Log,
   nie im Repo (Beispiel-Dateien enthalten ausschließlich Platzhalter).

## Install-Skizze (generisch — je Agent ein eigenes Heim + eigenes Token)

```
1. cp -r contrib/hermes-chat-plugin <agent-home>/.hermes/plugins/flipsibridge-chat
2. config.yaml:  gateway.platforms.flipsibridge { enabled: true }   # s. config.example.yaml
3. <agent-home>/.hermes/.env (0600):
     FLIPSI_RELAY_URL=wss://<relay-host>/agent_ws
     FLIPSI_AGENT_TOKEN=<je-agent-geheim>     # nur für DIESEN Host gültig
     FLIPSI_AGENT_ID=<agent-id>
     FLIPSI_ALLOWED_URLS=wss://<relay-host>/agent_ws
4. Relay-Seite: je-Agent-Token im Relay-Slim-Env hinterlegen (PH-2 P1/P2, nicht Teil
   dieses Skeletons) — das Token hier ist das Gegenstück, NICHT dasselbe Env-File.
5. Test: python3 <plugin-dir>/test_adapter.py   (Mock-WS, kein Relay nötig)
```

Vollständiger Live-Betrieb (E2E-Verschlüsselung, Reconnect-Politik, Datei-Transfer)
folgt in P3/P6/P7 — dieses Verzeichnis ist bewusst ein Skeleton: kein Deploy auf
Agenten-Homes, keine echten Hostnamen/Tokens im Repo.

## Selbst-Test

```
$ python3 test_adapter.py
```

Prüft ohne Netzwerk mit einer Mock-WS: URL-Pinning (Fremd-URL + Fremd-Schema werden
verweigert), connect + `agent.register`, send → `/chat`-Envelope, receive-Roundtrip
(`chat.deliver` → Nachricht-Callback), Ablehnung von Tool-Call-Frames und fremden
Namespaces, sauberes disconnect.