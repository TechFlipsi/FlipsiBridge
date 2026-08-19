I've completed a full review: read the entire 3390-line diff, then verified the security-critical paths against the actual PR tree (surviving at `/home/paseo/projects/hermes-android/.pr95-review/`) and the base files on `main`. Note: the `/tmp/pr95-wt` worktree was deleted mid-review; the identical checkout at `.pr95-review/` (branch `pr-95-review`, tip `897d4bc` fetched from `pull/95/head`) was used instead. No files were modified.

## Review

### Strengths
- **Auth parity is correct.** All new endpoints inherit existing transport auth: direct-HTTP mode is guarded by the global Ktor interceptor in `BridgeServer.kt` (Bearer + per-IP rate limiting, only `/ping` exempt — `BridgeRouter.kt:23` confirms the new `get("/mic_file")` sits under that interceptor), and relay mode requires the Bearer pairing code on both the phone WS handshake (`tools/android_relay.py` `_handle_ws`) and every forwarded HTTP request (`_handle_http`). No unauthenticated routes introduced.
- **Path traversal is defended in depth.** `MicrophoneRecordingFiles.resolve()` (`audio/MicrophoneRecordingFiles.kt:63-75`) applies a strict `[A-Za-z0-9._-]+\.wav` whitelist, a `File(name).name != name` identity check, and canonical-path containment. Tool-side (`android_mic_fetch`) rejects anything that isn't a bare `.wav` filename; relay-side sanitizes the `Content-Disposition` filename. `resolve(null)` returns only the latest generated recording.
- **Robust binary stream design.** Bounded frames (uint16 id-length prefix), per-request bounded queues with backpressure (`asyncio.Queue(maxsize=8)` + phone-side `ws.queueSize()` cap), 256 MB size cap, exact-length + SHA-256 verification (`_relay_binary_stream`), and atomic `.part → .wav` publication so interrupted recordings are never advertised as complete.
- **Abuse surface bounded.** `duration=0` is capped at 30 min device-side (`MicrophoneRecorderService.MAX_DURATION_SECONDS`, also enforced in `CommandDispatcher` and in the Python tools); recording always runs as a visible foreground service with a notification — appropriate for ambient audio.
- **Secret hygiene improved, not degraded, in code:** `_mask_token` no longer emits even a 2-char prefix; `BridgeNotificationListener` no longer logs notification content; the `?token=` query-string removal documented in SECURITY.md matches code already on `main` (docs-only sync, verified against base).
- **Duplicate-copy convention honored:** after the PR, `tools/android_relay.py` and `hermes-android-plugin/android_relay.py` are byte-identical (both end at blob `662efda`); the two `android_tool.py` copies differ only in the documented `android_relay` import path, and a new test asserts the plugin copy registers all 42 tools.
- **Tests cover real behavior:** WAV byte-level assertions, state-machine reservation/finalizing races, frame decode + malformed frames, an end-to-end relay WAV stream with checksum, phone-replacement races, and tool-level start/stop/status/fetch including traversal rejection.
- **WAV writer verified field-by-field** (RIFF/WAVE/fmt/data offsets, PCM=1, mono, byte-rate=sampleRate×2, block-align=2, 16-bit, little-endian; `finish()` patches offsets 4 and 40 correctly). Kotlin API usage is plausible for minSdk 26 / targetSdk 34 (`build.gradle.kts`): channel-based notifications, `STOP_FOREGROUND_REMOVE`, `AudioRecord.read(..., READ_BLOCKING)`, manifest declares `RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE` with `foregroundServiceType="microphone"` and `exported="false"`.

### Critical (must fix)
1. **Live pairing code + relay server IP committed in `HANDOVER.md` (section 0a, item 5):** `Neuer Pairing-Code nach Neuinstallation: AHGRAM` and `Server: http://100.116.64.43:8765`. This repo's core rule (AGENTS.md/SECURITY.md) is that pairing codes grant full device control and must never be published. The CHANGELOG even claims operational values were redacted — they weren't (the first code was replaced by `<rotate-locally>`, the second was left in). The author must rotate that code immediately and drop the file; once merged it is in git history.

### Important
1. **Service-lifecycle leak on the normal stop path** (`MicrophoneRecorderService.kt`): the recording coroutine's `finally` calls `stopSelfResult(startId)` with the START intent's id; after a `mic_stop`, the STOP intent carries a newer startId, so `stopSelfResult` returns false and nothing else stops the service — a started, non-foreground service instance lingers until process death. Harmless in practice (next `mic_start` reuses it), but it should call `stopSelf()` after finalization or track the latest startId.
2. **Scope creep:** ~900 lines of personal fork-coordination docs unrelated to mic support: `CODEX-START.md`, `HANDOVER.md`, `MILESTONES.md`, `docs/FALLSTRICKE.md`, `docs/KI-KOORDINATION.md`, `docs/cradata/BODY-APP-SPEZIFIKATION.md`, `docs/cradata/PIXEL-VERSUCHSBERICHT.md` (German personal experiment reports incl. names, locations, descriptions of photographed people). These belong in the fork, not upstream.
3. **No recording retention policy:** completed WAVs (up to ~57.6 MB each for a 30-min recording) accumulate forever in `getExternalFilesDir(Music)/cradata_audio`. Add a keep-last-N cleanup or document the growth.
4. **Android 12+ background FGS start risk:** `mic_start` arriving while the app is backgrounded may hit `ForegroundServiceStartNotAllowedException`. It degrades gracefully (caught → 500 + `markError`), but the author's device testing appears to have run with the accessibility service active; verify on-device background behavior.
5. **Bundled unrelated fixes** (WS phone-replacement race + `_fail_inflight`, token masking, notification-log redaction, aiohttp lambda-handler cleanup, `setup.py`/`plugin.yaml` 0.3.0→0.4.1 version bump): all sensible, but they should be split out or at least itemized in the PR description.

### Minor / nits
- `android_mic_fetch` uses a scalar `timeout=_timeout()` with `stream=True`; a slow link could trip the 30 s inter-chunk timeout on a 57 MB file — a `(connect, read)` tuple would be kinder.
- `requestStop()` via `startForegroundService(ACTION_STOP)` when state is FINALIZING briefly starts an FGS just to stop it; harmless.
- `WavFileWriter.write()` after `close()` would silently no-op writes while still counting samples — unreachable today, worth a `check`.
- Empty recording (immediate stop) publishes a valid 44-byte WAV; fine, just noting.
- Commit history could not be audited (no shell/git access in this review environment) — only the diff and tree were verifiable.

### Scope assessment
- **Belongs in this PR:** everything in `hermes-android-bridge/.../audio/` + unit tests, `AndroidManifest.xml`, `BridgeApplication.kt`, `BridgeRouter.kt`, `CommandDispatcher.kt`, `RelayClient.kt`, both `android_relay.py`/`android_tool.py` copies, `tests/`, README/architecture/index/install/skill doc updates, CHANGELOG mic entries, mic rows in the tool tables.
- **Acceptable if itemized:** `BridgeNotificationListener.kt` log redaction, `_mask_token`, WS-replacement race fix, aiohttp handler cleanup, `setup.py`/`plugin.yaml` version bump, SECURITY.md `?token=` wording fix.
- **Drop:** `CODEX-START.md`, `HANDOVER.md` (secret leak), `MILESTONES.md`, `docs/FALLSTRICKE.md`, `docs/KI-KOORDINATION.md`, `docs/cradata/*`.

### Verdict
**Request changes** — the mic implementation itself is high-quality, authenticated, well-tested and safe against traversal/streaming abuse, but the PR publishes a live pairing code and server IP in `HANDOVER.md` and carries ~900 lines of personal fork docs that must be removed before merge.

Commands the maintainer/CI must run (I have no shell access here):
- `cd hermes-android-bridge && ./gradlew testDebugUnitTest assembleDebug`
- `pytest tests/ -q` (repo root; Python deps from `requirements.txt`/pyproject dev extras)