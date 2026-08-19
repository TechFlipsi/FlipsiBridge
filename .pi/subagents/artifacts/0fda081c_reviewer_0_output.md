I have completed the full review — the entire 2900-line diff read, plus current-file verification of the auth interceptor, router, relay handlers, tool helpers, and test inventory. Here is the audit.

---

## 1. VERDICT: **follow-ups-needed**

## 2. SUMMARY

This is a well-engineered, security-conscious change. Endpoint auth is uniformly enforced: the direct-HTTP mic routes sit behind the existing `ApplicationCallPipeline.Plugins` interceptor in `BridgeServer.kt` (only `/ping` exempt), and all four relay mic routes go through `_handle_http`, whose Bearer/`hmac.compare_digest` check is the first statement executed. File serving is traversal-safe via a multi-layer `resolve()` (basename regex + `File.name` check + canonical-path parent check, which also defeats symlinks), and the relay binary stream verifies length and SHA-256 before completing a download. Privacy guardrails (always-on foreground notification, sample-count-enforced 30-min cap, keep-last-10 retention, atomic `.part`→`.wav`, app-scoped storage) are all genuinely in place, and the PR even hardens adjacent areas (full token masking, no notification-content logging). Remaining issues are a narrow recorder-state stuck bug and meaningful test gaps around the security-critical verification paths — none are exploitable confidentiality breaks, so this needs follow-up fixes, not a revert.

## 3. FINDINGS

1. **[MINOR] Recorder state can get permanently stuck at `starting` → `/mic_start` returns 409 until app restart.**
   - `hermes-android-bridge/.../server/CommandDispatcher.kt` (`/mic_start`): `MicrophoneRecordingState.tryReserveStart()` runs *before* the service starts; `hermes-android-bridge/.../audio/MicrophoneRecorderService.kt` `requestStop()`: when `!isRecording.get()` it calls `stopForegroundCompat(); stopSelf()` **without** resetting `MicrophoneRecordingState`.
   - Scenario: START accepted (state STARTING) → system destroys the service before/without the record loop, or a STOP arrives when `isRecording` is already false → nothing ever calls `markError`/`markReady` → `snapshot.isActive` stays true forever → every later `/mic_start` gets `"A microphone recording is already active"` (409).
   - Fix: in `requestStop()`'s not-recording branch, reset state (e.g., `markError("stopped")` or a dedicated `markIdle()`); optionally add a watchdog so a STARTING state older than N seconds is released.

2. **[MINOR] No unit test for `MicrophoneRecordingFiles.resolve()` — the traversal/symlink defense.**
   - `hermes-android-bridge/app/src/test/kotlin/com/hermesandroid/bridge/audio/` contains 4 test files; none exercise `resolve()`. The Python test `tests/test_android_tool.py::test_fetch_rejects_device_paths` only validates the *client-side* check. The security-critical server-side validation (regex, `File(name).name`, canonical parent, unknown-ID→null) is untested.
   - Fix: add cases for `"../x.wav"`, absolute paths, `"%2e%2e"`-style decoded input, symlink pointing outside the dir, non-`.wav` names, unknown-but-valid names (expect null), and blank→latest.

3. **[MINOR] Relay stream verification has no negative-path tests.**
   - `tests/test_android_relay.py::TestMicrophoneBinaryStream` covers only the happy path (`test_wav_is_streamed_from_phone_to_http_client`). Untested: SHA-256 mismatch → aborted download; `total > expected_size` oversize guard; `end.bytes`/length mismatch; mid-stream phone disconnect. These are exactly the lines that make the feature safe (`_relay_binary_stream` in `tools/android_relay.py`: `hmac.compare_digest`, `"Phone sent more recording data than declared"`).
   - Fix: add tests sending a wrong sha256, an extra chunk beyond declared size, and a truncated stream; assert the HTTP client gets an error/incomplete body and no file is accepted.

4. **[MINOR] 30-minute cap has no automated coverage.**
   - The cap is enforced by `sampleLimit = SAMPLE_RATE * effectiveDuration` in `MicrophoneRecorderService.kt` (and `duration=0` correctly maps to `MAX_DURATION_SECONDS`), but nothing tests loop termination at the limit. Manual test list (`docs/manual-tests.md`) also doesn't cover it (a real 30-min wait isn't practical).
   - Fix: extract the sample-limit/loop-bound logic into a pure function (or make duration injectable) and unit-test that recording stops at exactly `sampleLimit` samples.

5. **[NIT] `android_mic_fetch` error strings can echo the bridge host/port.**
   - `tools/android_tool.py` / `hermes-android-plugin/android_tool.py` `android_mic_fetch`: `return json.dumps({"error": str(e)})` — `requests` connection errors embed the URL (host:port). The token is header-only and never leaks, and this matches the existing convention of all other tools, but AGENTS.md asks to avoid exposing device connection details. Consider normalizing to `"Microphone download failed (<type>)"`.

6. **[NIT] SHA-256 is verified at end-of-stream, after bytes were already forwarded to the HTTP client.**
   - `_relay_binary_stream` writes chunks as they arrive; on checksum/length mismatch it `force_close()`s the connection, so consumers see a truncated body. This is safe in practice (the only consumer, `android_mic_fetch`, treats an aborted stream as an error, deletes the temp file, and independently validates RIFF/WAVE magic + Content-Length), but "verified before use" is really "verified before *completion*". Worth a code comment so a future second consumer doesn't skip its own integrity check.

7. **[NIT] Orphaned `.part` cleanup only happens at the *next* recording start.**
   - `MicrophoneRecordingFiles.createPending()` deletes stale `*.wav.part` files; a process death leaves one behind until then. Bounded (one file, never served, never listed in `listCompleted`), matching the CHANGELOG claim — just noting the timing.

## 4. POSITIVES

- **Auth verified on every new path**: Ktor interceptor (`BridgeServer.kt`, Plugins phase, only `/ping` exempt) covers the new `get("/mic_file")` route; relay `_handle_http` authenticates before any work; binary frames only accepted on the handshake-authenticated phone WS (`?token=` legacy rejected, rate limiting applied).
- **Defense-in-depth file resolution**: regex + basename + `canonicalFile` parent check (defeats `..`, absolute paths, URL-decoded traversal, and symlinks); unknown IDs → 404; no header injection possible (`Content-Disposition` built only from regex-validated names).
- **Stream integrity**: bounded queues, backpressure both sides (`ws.queueSize()` on phone, `maxsize=8` on relay), oversize guard (256 MB), constant-time digest compare, per-frame stream re-lookup so an aborted download can't stall the command channel; stream registered before the command is sent (no missed-reply race).
- **Privacy by design**: foreground notification from `onCreate` through finalization; 30-min cap enforced by sample count even for `duration=0`; atomic finalization so interrupted recordings are never advertised; retention never touches `.part`/unrelated files; recordings in app-scoped `getExternalFilesDir` (not world-readable); `no-store` cache headers.
- **Bonus hardening in this PR**: `_mask_token` no longer emits a 2-char prefix; notification content no longer logged even in debug builds; both verified by updated tests.
- **Copy parity kept**: both `android_relay.py` copies land on the *same git blob* (`662efda`); `android_tool.py` hunks are identical across copies (the `android_read_widgets` relocation yields identical ordering; only the documented `android_setup` import differs), and a new test (`test_plugin_copy_registers_all_tools_without_device_specific_values`) enforces 42-tool parity and bans legacy SCP strings.
- **Secrets scan clean**: full diff read — only fixture pairing codes (`SECRET123`, `MICODE`, `REPLCE`), localhost URLs, and normal commit metadata. No tokens, keys, real IPs, or personal identifiers.
- **Resource safety**: `AudioRecord` released on all paths (coroutine `finally`, uninitialized-state path, `onDestroy` fallback); saturating gain (`coerceIn`) verified by test; `handleMessage` try/catch ensures a malformed `name` param yields a 500 result, not a WS crash.

## 5. FOLLOW-UP TASKS

1. Fix stuck-STARTING state: reset `MicrophoneRecordingState` in `requestStop()`'s not-recording branch and/or add a STARTING-age watchdog (finding 1).
2. Add Kotlin unit tests for `MicrophoneRecordingFiles.resolve()` traversal/symlink/unknown-ID cases (finding 2).
3. Add relay negative stream tests: checksum mismatch, oversize, truncation, mid-stream disconnect (finding 3).
4. Make the 30-min cap testable and unit-test it (finding 4).
5. Normalize `android_mic_*` connection-error strings to avoid echoing host/port (finding 5) — apply identically to both copies per parity rule.
6. (Optional) Document end-of-stream verification contract in `_relay_binary_stream` (finding 6).

Supervisor commands to validate (I could not run them — read-only review, and the environment's `grep`/`find` binaries are corrupted):
- `cd /home/paseo/projects/hermes-android && pytest tests/`
- `cd hermes-android-bridge && ./gradlew testDebugUnitTest`

---