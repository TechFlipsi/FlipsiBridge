I have now read the full 9,190-line diff (all hunks, including the out-of-scope Python for secret-scan completeness) and verified the new code against the baseline auth model in `BridgeServer.kt`, `BridgeRouter.kt`, `CommandDispatcher.kt`, `RelayClient.kt`, `PairingManager.kt`, `BridgeApplication.kt`, `AndroidManifest.xml`, and `build.gradle.kts`.

## Review

### 1. VERDICT: **request-changes**

One remotely-triggerable app crash in the new noise-watcher lifecycle, plus one security-policy change (unconditional boot/update auto-reconnect) that needs an opt-out and SECURITY.md treatment. Everything else is high quality and security-conscious.

### 2. ARCHITECTURE

The design is sound and shows real security thinking. All new remote command surface (`/robot_state`, `/robot_status`, `/noise_watch_*`) flows through the existing single-source-of-truth `CommandDispatcher.dispatch`, which is gated identically on both transports: the local Ktor server's `BridgeServer` interceptor (Bearer pairing code on every path except `/ping`, verified in baseline `BridgeServer.kt`) and the relay-authenticated WebSocket. No new unauthenticated entry points. The GPT Live WebRTC design keeps the OpenAI key strictly server-side: the APK ships only a 189-line local HTML page with a strict CSP, an `.invalid` base origin, and an SDP exchange proxied through the authenticated relay — verified no key material anywhere in Kotlin or assets. The noise watcher is properly opt-in (UI switch + runtime perms), visibly notified, and storage-bounded (≤30 s clips @ 2 Mbps, 10-file retention, stale `.part` cleanup). The two lifecycle weaknesses are the unconditional `startForeground(camera|microphone)` in `NoiseTriggeredVideoService.onCreate` and the always-on `BootReceiver`, detailed below.

### 3. FINDINGS

**1. [MAJOR] Remote `/noise_watch_stop` can crash the whole bridge app** — `media/NoiseTriggeredVideoService.kt`, `onCreate` (~line 117) + companion `stop()` (~line 77); `server/CommandDispatcher.kt`, `/noise_watch_stop` (hunk `@@ -434,6 +536,83 @@`).
Evidence:
```
override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    startForegroundCompat("Loud-noise video watcher is ready")
}
...
fun stop(context: Context) {
    context.startService(Intent(...).apply { action = ACTION_STOP })
}
...
method == "POST" && path == "/noise_watch_stop" -> {
    NoiseTriggeredVideoService.stop(BridgeApplication.instance)
    mapOf("status" to "stopping") to 202
}
```
`stop()` is unguarded (no permission check, no "is it running" check) and `onCreate` promotes to foreground with `FOREGROUND_SERVICE_TYPE_CAMERA or FOREGROUND_SERVICE_TYPE_MICROPHONE` before any action/permission handling. With `targetSdk = 34`, `startForeground` with camera/microphone types throws `MissingForegroundServicePermissionException` if `CAMERA`/`RECORD_AUDIO` are not granted — the common case, since the watcher is opt-in. So a routine authenticated `android_noise_watch_stop()` tool call (one of the 46 published tools) when the watcher was never enabled creates the service → crash → bridge process (Ktor server + relay WS + accessibility) dies.
Fix: guard `stop()` with `if (!NoiseTriggerState.snapshot().active) return`, and/or move the foreground promotion into the `ACTION_START` path after the existing permission check (for `ACTION_STOP`, `stopSelf()` without foreground promotion), plus a try/catch fallback around `startForeground`.

**2. [MAJOR] Unconditional auto-reconnect on every boot and every app update, no opt-out** — `boot/BootReceiver.kt` (whole file) + `AndroidManifest.xml` receiver declaration (hunk `@@ -75,5 +87,23 @@`).
Evidence:
```
<action android:name="android.intent.action.BOOT_COMPLETED" />
<action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
<action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
```
plus the KDoc: "Kein manuelles 'Connect' mehr nötig — die App verbindet sich mit dem zuletzt gespeicherten Relay." Combined with the pre-existing `RelayClient.autoConnect()` in `BridgeApplication.onCreate`, every reboot — and every APK install (`MY_PACKAGE_REPLACED`), which this repo does frequently via the `latest-build` auto-publish — silently re-establishes full remote control with no user interaction. This directly undercuts SECURITY.md's stated mitigation "Disconnect when not in use: Tap Disconnect in the app when you're not actively using it," and there is no UI toggle to disable it. The three actions are protected broadcasts (so third-party spoofing isn't feasible — `exported="true"` is acceptable), so this is a policy issue, not an injection hole.
Fix: make it opt-in (e.g., a MainActivity switch backed by `PackageManager.setComponentEnabledSetting`) or at minimum prompt/confirm on first boot; document the behavior change in SECURITY.md under the security model.

**3. [MINOR] `/noise_watch_start` parameter parsing can throw on malformed JSON** — `server/CommandDispatcher.kt`, `/noise_watch_start` hunk:
```
val threshold = body.get("thresholdRms")?.asDouble ?: NoiseTriggeredVideoService.DEFAULT_THRESHOLD_RMS
val clipSeconds = body.get("clipSeconds")?.asInt ?: ...
```
Unlike the careful `isJsonPrimitive`/type checks used for `stop_on_silence`/`silence_ms` in `/mic_start` (same PR), a string/boolean/object here throws `NumberFormatException`/`UnsupportedOperationException`, caught upstream as a generic 500 instead of a clean 400. Not a crash (both transports catch), but inconsistent with the PR's own validation standard. Fix: mirror the `/mic_start` type-checking pattern.

**4. [MINOR] No test for the path-traversal defenses in `NoiseVideoFiles.resolve()`** — `media/NoiseVideoFiles.kt` (`resolve`, ~line 40); tests in `NoiseVideoFilesTest.kt` cover only `enforceRetention`. The `resolve()` logic is security-relevant (it backs both `GET /noise_video_file` route and the WS stream path): the `requestedName != File(requestedName).name` + prefix/suffix checks + `canonicalFile.parentFile` comparison look correct to me, but this is exactly the kind of code that deserves regression tests (e.g., `../noise_x.mp4`, `noise_..mp4`, absolute paths, wrong-case `.MP4`).

**5. [MINOR] Concurrency hygiene in `NoiseTriggeredVideoService`** — `thresholdRms`, `clipSeconds`, `cooldownSeconds`, `lastTriggerElapsedMs`, `cameraRecorder`, `monitorJob` are plain vars written on the main thread in `onStartCommand` and read from `Dispatchers.IO` coroutines. No `@Volatile`, no synchronization. In practice benign here (worst case: a stale threshold for one run), but it violates the careful `@Volatile` discipline the same PR uses in `RelayClient`/`RobotRealtimeController`.

**6. [MINOR] Stop semantics during an active clip recording** — `CameraVideoRecorder.record()` blocks until the clip finishes; `stopWatcher()` cancels the monitor job and closes the recorder but does not join the in-flight recording runnable. If `stop` arrives mid-clip, the clip still completes, is finalized, and retention-enforced after the service "stopped". Decide and document: either interrupt/discard the in-flight clip on stop, or note that stop completes the current clip. Related: if the outer `done.await(duration + 12s)` times out in `CameraVideoRecorder.record`, the method returns failure while the camera runnable keeps running on its handler thread (self-heals, but worth a comment).

**7. [NIT] Microphone contention gaps** — `/noise_watch_start` checks only `MicrophoneRecordingState` (the WAV recorder), not the robot dialog's live mic. A remote `/noise_watch_start` while GPT Live holds the mic will race WebRTC's capture; the watcher fails gracefully (AudioRecord init → `NoiseTriggerState.failed`), but a `RobotRealtimeController.active` guard would make the 409 story symmetric. Also `NoiseTriggeredVideoService.stop()` uses plain `startService`; if the service is already stopped this needlessly recreates it (see finding 1 for the dangerous version of this).

**8. [NIT] Secrets scan result: clean.** No API keys, tokens, real IPs, emails, or personal identifiers in any Kotlin/asset/test hunk. Only obviously-fake test pairing codes (`ROBOT1`, `TEST85`, `TEST01`) and placeholder docs values (`http://<mac-tailscale-address>:8765`). The "OpenAI credentials never reach Android" claim holds: `OPENAI_API_KEY` is read only in the Mac-side relay (`android_relay.py` `_handle_realtime_session`), and the Android side ships only tier names (`mini`/`standard`/`top`) over an allow-listed header.

### 4. POSITIVES

- **Auth continuity verified end-to-end**: new Ktor route `/noise_video_file` sits inside `configureRouting()` covered by the `BridgeServer` interceptor; new relay endpoints `/robot/events` and `/robot/realtime/session` both call the extracted `_http_auth_error` (same Bearer pairing code + IP rate limiting); WS events ride the already-authenticated phone socket. No weak or unauthenticated entry points introduced.
- **WebView hardening is exemplary** (`RobotRealtimeController.kt` + `assets/robot_realtime.html`): JS enabled only for the bundled asset; `allowFileAccess`/`allowContentAccess`/`domStorage` off; all navigation blocked (`shouldOverrideUrlLoading` returns true); `MIXED_CONTENT_NEVER_ALLOW`; CSP `default-src 'none'; script-src 'unsafe-inline'; media-src blob:`; base URL is the unresolvable `https://cradata.invalid/`; `onPermissionRequest` grants only audio-capture and only to the trusted origin; SDP answers injected with `JSONObject.quote` (no JS injection path).
- **SDP exchange is defensive both directions**: offer validated (`v=0` prefix, ≤64 KB, no NUL), tier allow-listed, answer re-validated, upstream errors collapsed to bounded German strings — provider messages/credentials never reach the Pixel UI.
- **Robot event channel is content-free and strictly allow-listed on both sides** (`RelayClient.sendRobotEvent` + relay `_on_phone_event` with exact key-set checks); transcript events bounded (≤4000 chars, role allow-list, `[A-Za-z0-9_.:-]{1,160}` itemId) and validated twice (Android + relay).
- **Noise watcher privacy controls**: visible ongoing notification with state text, opt-in switch + runtime permission flow in `MainActivity`, 10-file retention + 1-hour stale-`.part` cleanup (tested), owner-only dir perms, `Cache-Control: no-store` + `attachment` disposition on video download, SHA-256 integrity-checked relay streaming, and a `MediaScannerConnection` scan only after finalization.
- **Microphone ownership protocol** between the watcher and robot dialog (pause/resume with state snapshot, polling with bounded timeout, 409 conflicts on `/mic_start` and `/noise_watch_start`) is well designed, and `NoiseTriggerStateTest` covers the pause-preserves-settings semantics.
- **`ActionExecutor.speak` no longer echoes spoken text** into command responses/relay logs — good PII reduction aligned with AGENTS.md conventions.
- **GPT Live fails closed**: if a transcript can't reach the relay, the Pixel stops the live session rather than continuing unrecorded (`NativeBridge.onTranscript`), matching the documented promise.
- **Test coverage of the new logic is genuinely good**: 6 new Kotlin test files covering VAD edge cases, trigger-state transitions, retention, dispatch allow-list validation (including caption non-echo), and face rendering; Robolectric wiring (`isIncludeAndroidResources`) matches the existing `libs.robolectric` dependency.

### 5. OPEN-QUESTIONS

1. Is boot/update auto-start (finding 2) intended to be unconditional? Will SECURITY.md's "disconnect when idle" guidance be revised, or should a toggle gate `BootReceiver`?
2. **Android 14 background FGS start restrictions**: `/noise_watch_start` calls `startForegroundService` for a `camera|microphone` FGS while the app may be in the background state (typical when driven through the relay). Has this been verified on a real Android 14 device? If restricted, the error path degrades gracefully (`runCatching` → 500), but the feature would silently be UI-only.
3. `selectedRealtimeTier` defaults to `TOP` (highest-cost model tier) — intentional product default?
4. `/robot_state` with `show=true` lets the remote agent pop the robot UI at any time (by design per docs) — should this work/be suppressed when the device is locked?

Supervisor/maintainer commands to run (I had no shell access; the repo's `rg`/`fd` binaries used by my grep/find tools are broken — every search failed with `Syntax error: "(" unexpected`, so I read the diff cover-to-cover instead):
- `cd hermes-android-bridge && ./gradlew test` (verify the 6 new Kotlin test files, incl. Robolectric `RobotFaceViewTest`)
- Manual: on an Android 14 device with camera/mic permissions NOT granted, call `android_noise_watch_stop()` and observe the crash from finding 1.