---
summary: "Kotlin bridge ↔ Python toolset ↔ WebSocket relay; the 42 android_* tools and how a command flows end-to-end."
read_when:
  - "Changing the relay, bridge app, or tool wiring"
  - "Tracing how an android_* tool call reaches the phone"
  - "Adding a new tool or endpoint"
---

# Architecture

```
User  ──>  Hermes Agent  ──HTTP──>  Relay (localhost:8766)  ──WebSocket──>  Phone
                                          │
                                   aiohttp server
                                   pairing code auth
                                   Zugriffssteuerung
```

The phone **connects out** to the relay over WebSocket (NAT-friendly — no port forwarding). The relay bridges HTTP tool calls from the agent to the phone over that socket. For local/USB dev, tools can talk directly to the phone's Ktor HTTP server on port 8765 by setting `ANDROID_BRIDGE_URL`.

## Two components

| Component | Path | Language | Role |
|-----------|------|----------|------|
| Android bridge app | `hermes-android-bridge/` | Kotlin | Runs on the phone; executes commands via AccessibilityService |
| Python toolset | `tools/`, `tests/`, `hermes-android-plugin/` | Python | Runs on the server; `android_*` tools + WebSocket relay |

The Python code is standalone here for dev/test (`pip install -e .`, `pytest`); the production copy lives in hermes-agent. **The APK does not depend on the Python files.**

## Data flow (relay mode)

1. `android_setup(pairing_code)` starts the relay + configures auth.
2. Phone connects to `/ws` with `Authorization: Bearer [Auth-Schema im internen Security-Design — nicht öffentlich] <pairing_code>`.
3. Agent calls an `android_*` tool → HTTP request to `localhost:8766`.
4. Relay wraps it as a JSON command, sends over WebSocket to the phone.
5. Phone executes the command and returns JSON over WebSocket; completed microphone WAVs use bounded binary frames with length and SHA-256 verification. The bridge keeps the 10 newest completed recordings.
6. Relay returns the phone's response as the HTTP response.

### Command envelope

```
Relay -> Phone:  {"request_id":"uuid","method":"GET|POST","path":"/screen","params":{...},"body":{...}}
Phone -> Relay:  {"request_id":"uuid","result":{...},"status":200}
```

## Android app (Kotlin)

Runs two network endpoints simultaneously:

| Component | Type | Port | Purpose |
|-----------|------|------|---------|
| BridgeServer (Ktor/Netty) | HTTP server | 8765 | Direct USB/LAN dev |
| RelayClient (OkHttp) | WebSocket client | outbound | Connects to remote relay |

Key classes (`app/src/main/kotlin/com/hermesandroid/bridge/`):
- `auth/PairingManager.kt` — Kopplungs-Code-Verwaltung (Details im Security-Design, nicht öffentlich)
- `client/RelayClient.kt` — outbound WebSocket; auto-reconnect with exponential backoff.
- `service/BridgeAccessibilityService.kt` — singleton AccessibilityService; reads UI tree on demand.
- `executor/ActionExecutor.kt` — tap/type/swipe/scroll/wait/open-app; wakes device via `WakeLockManager`.
- `executor/ScreenReader.kt` — traverses accessibility tree → `ScreenNode` hierarchy; finds nodes by text.
- `power/BatteryMonitor.kt` — battery level and charge state from `BatteryManager`; platform values, never status-bar text.
- `overlay/StatusOverlay.kt` — always-on HUD.

Required permissions: `ACCESSIBILITY_SERVICE`, `SYSTEM_ALERT_WINDOW`, `INTERNET`, `WAKE_LOCK`, `FOREGROUND_SERVICE` (plus optional runtime perms for location/contacts/SMS/phone/notifications).

## Relay server (Python — `android_relay.py`)

aiohttp server in a background daemon thread, started by `android_setup()`.

- `/ws` (WebSocket) — phone connects with a Bearer authorization header. On connect the relay sends a `relay.state` hello frame (first frame, PH2) carrying the agent registry as `agents: [{agent_id, display_name, online}]` (empty list when no agents are configured).
- `/agent_ws` (WebSocket, PH2) — agent connects outbound with its own Bearer agent token from the server's relay-config.json. Frame allowlist (PH2): `keepalive`/`ping`/`presence` (presence only), `chat.reply` (`{chat_id, text, elapsed_ms?}` — bridged into the phone broker exactly like POST `/chat_event`, incl. spool + 24h retention and the per-agent chat rate limit), `chat.read` (`{chat_id, msg_id?}` — marks the broker message read → `chat.status=read` phone frame; without `msg_id` all unread messages of that `chat_id`) and `chat.activity` (`{chat_id, working, started_at?}` — forwarded to the phone once per change, deduped per `(agent_id, chat_id)`); `chat.read`/`chat.activity` and the resulting status frames are NOT rate-limited (status ≠ chat content). Anything else is logged and dropped without closing the socket.
- Chat status frames (PH2-T10) — every `/chat` message gets a `msg_id` (returned in the HTTP response) and a broker status `queued → delivered` (on the agent-WS send, live or spool flush) `→ read` (on `chat.read`); each transition pushes one `chat.status {chat_id, msg_id, status, ts}` frame to the phone (spooled while it is offline). Agent replies may carry `elapsed_ms` (receive→reply, measured by the adapter), passed through to the `chat.event` frame. Conversation isolation (PH2-T5): a `chat_id` created via `/chat` belongs to that one agent — replies from a different agent into it are discarded and logged (HTTP `/chat_event`: 403).
- HTTP bridge endpoints (method per path) — GET: `/ping`, `/screen`, `/screenshot`, `/apps`, `/current_app`, `/notifications`, `/contacts`, `/events`, `/screen_hash`, `/location`, `/battery`, `/widgets`, `/mic_status`, `/mic_file`, `/files`, `/files_search`, `/files_count`, `/file`, `/network`, `/key_get`. POST: `/tap`, `/tap_text`, `/type`, `/swipe`, `/open_app`, `/press_key`, `/scroll`, `/wait`, `/long_press`, `/drag`, `/describe_node`, `/find_nodes`, `/diff_screen`, `/pinch`, `/send_sms`, `/call`, `/media`, `/intent`, `/broadcast`, `/speak`, `/stop_speaking`, `/screen_record`, `/events/stream`, `/mic_start`, `/mic_stop`, `/apk_install`, `/files_push`, `/files_delete`, `/photo`, `/torch`, `/volume`, `/alarm`, `/timer`, `/notify_reply`, `/health_data`, `/chat`, `/chat_event`, `/key_publish`, `/crypto_hello`. Both: `/clipboard`, `/files_permission`.
- PH-3 E2E crypto routes (relay-native, broker-only — no device forward) — GET `/key_get?agent_id=` (phone reads the agent's public identity/prekeys from the relay config; 404 fail-closed when the agent has no keys, keys never logged), POST `/key_publish` (`{ik_pub, spk_pub, device_id?}` — phone publishes its own public X25519 keys into the key registry `relay-keys.json`, 0600, pairing-code Bearer), POST `/crypto_hello` (X3DH handshake frame `{session_id, agent_id, ik_pub, ek_pub, spk_pub}` routed opaquely to the agent; session map 24 h, spool while the agent is offline; agent answers `crypto.hello_ack` → phone, afterwards chat runs as opaque `chat.*.enc` frames).
- Auth: pairing code case-sensitive (exact compare, see #43). 5 failed attempts / 60s → IP blocked 5 min. Only one phone connected at a time.

## Tools

42 `android_*` tools span: connectivity (`ping`, `setup`), screen reading (`read_screen`, `screenshot`, `current_app`, `find_nodes`, `describe_node`, `screen_hash`, `diff_screen`), apps (`open_app`, `get_apps`), input (`tap`, `tap_text`, `type`, `long_press`, `drag`, `pinch`), gestures (`swipe`, `scroll`), keys (`press_key`), waiting (`wait`), device (`location`, `search_contacts`, `send_sms`, `call`, `media`, `send_intent`, `broadcast`, `clipboard_read`, `clipboard_write`), events (`notifications`, `events`, `event_stream`), capture (`screen_record`, `read_widgets`), microphone (`mic_record`, `mic_stop`, `mic_status`, `mic_fetch`), and voice (`speak`, `speak_stop`). See README for the full table.

## Integration paths

- **Plugin (preferred):** drop `hermes-android-plugin/` into `~/.hermes/plugins/hermes-android`; `register(ctx)` registers tools via the plugin API.
- **Legacy `tools/`:** import `tools/android_tool.py` directly into hermes-agent's registry.

Both share the same tool implementations and relay code.
