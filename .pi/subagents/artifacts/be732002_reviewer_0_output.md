I have now read the full 9191-line diff and verified the baseline files in the repo. Here is my review.

---

1. VERDICT: **approve-with-nits**

2. ARCHITECTURE:
The Python-side design is sound and security-conscious. The relay gains two new endpoints (`GET /robot/events`, `POST /robot/realtime/session`) that both reuse the existing pairing-code Bearer auth + per-IP rate limiting via a cleanly extracted `_http_auth_error` helper — no unauthenticated surface is introduced. The GPT Live WebRTC topology correctly keeps `OPENAI_API_KEY` and model mapping Mac-side: the phone sends only an SDP offer plus an allow-listed tier header, and upstream failures are collapsed to bounded machine slugs/statuses before they can reach the APK. The dialog worker confinement is a hard `toolsets="memory"` argument plus stdin-only message passing and `@file` neutralization — real enforcement at the process boundary, not just comments, though its ultimate strength depends on hermes-agent's `cli.main` semantics (external repo). File hygiene (0700/0600, temp WAV deletion, bounded sizes, fsync'd JSONL) and PII redaction layers (cloud-bound vs memory-bound) are well structured. Copy parity is maintained: `android_relay.py` is byte-identical in both locations, and `android_tool.py` differs only by the pre-existing tools/-only docstring.

3. FINDINGS:

1. [MINOR] `tools/android_relay.py` & `hermes-android-plugin/android_relay.py`, `_handle_realtime_session` (hunk `@@ -582,14 +697,254 @@`). The outbound safety identifier is an HMAC keyed with the long-lived OpenAI key over the low-entropy pairing code:
   ```
   +    safety_identifier = hmac.new(
   +        api_key.encode("utf-8"),
   +        f"cradata-realtime:{state.pairing_code}".encode("utf-8"),
   +        hashlib.sha256,
   +    ).hexdigest()
   ```
   The comment's rationale ("cannot be used to brute-force it") holds only while the API key is secret. If the API key **and** this header value are ever disclosed together (e.g., provider-side logging), the 6-char pairing code (32^6 ≈ 1.1e9) becomes offline brute-forceable, recovering the relay auth credential. Fix: use a random per-session identifier, or HMAC keyed with a server-local random secret rather than the API key.

2. [MINOR] Relay→phone plaintext exposure of the SDP answer (same endpoint). The SDP answer carries ICE credentials for the OpenAI media session and travels over `http://`/`ws://` unless the operator fronts the relay with TLS — an extension of the documented "No Encryption" limitation in SECURITY.md, now carrying live-voice session material and transcript events. `docs/robot-dialog.md` mitigates ("Bind the relay only to the private Tailscale interface"), but SECURITY.md's Known Limitations should explicitly mention the new robot/realtime plaintext surface. Fix: add one sentence to SECURITY.md.

3. [MINOR] `tools/robot_dialog.py`, `RobotDialogService._flush_live_pairs` / `handle_realtime_transcript`. User and assistant Realtime transcripts are paired from two independent FIFOs (`_live_users`, `_live_assistants`). An unanswered user utterance stays queued and gets paired with the *next* assistant reply, so the memory observer can receive a mismatched pair (the private archive is unaffected — it stores each event individually). Fix: pair by adjacency/item-id, or drain unmatched entries on `robot.session_stop`.

4. [MINOR] `tools/robot_dialog.py`, `ConversationArchive`. The private transcript JSONL archive has per-entry bounds (12k chars) and 0600 permissions but no retention/rotation — unbounded growth under `~/.hermes/robot-dialog/transcripts/`. Only the opt-out flag exists (`ROBOT_DIALOG_TRANSCRIPT_ARCHIVE=false`). Fix: add an optional max-file-count/age cap mirroring the phone-side `MAX_COMPLETED_VIDEOS` retention.

5. [NIT] Copy parity — the +174 (tools/) vs +170 (plugin/) difference in `android_tool.py` is exactly 4 docstring lines added only to `tools/android_tool.py` (hunk `@@ -40,6 +40,10 @@`: the four `android_noise_*` tool-list lines). All functional hunks (function bodies `@@ +731,116`, schemas `@@ +1571,62`, handlers `@@ +1809,10`) are content-identical between copies, and both `android_relay.py` copies share blob hashes `662efda..1ddfc68` (byte-for-byte identical). The baseline files already differed (module docstrings), so byte-for-byte parity was never the convention for `android_tool.py`. However, the plugin copy's docstring header still reads `hermes-android tool — 42 android_* tool handlers + schemas.` (line 2, no hunk touches it) while README/skill files moved to 46. Fix: update or drop the count there.

6. [NIT] `pyproject.toml` `[project.scripts]` / `setup.py` `entry_points`: these add **no new dependencies** (only the `hermes-robot-dialog = tools.robot_dialog:main` console script; `tools/` is a package with `__init__.py`, so `find_packages()` ships it). But `logging.basicConfig(...)` lives only in the `if __name__ == "__main__":` guard of `robot_dialog.py`, so when invoked via the entry point, INFO logs (including `--check` output) are silent; only WARNING+ reaches stderr via lastResort. Fix: move logging setup into `main()`.

7. [NIT] `tools/whisper_worker.py`: `import whisper` (openai-whisper) is an undeclared optional runtime dependency; it is only documented in `docs/robot-dialog.md`. Acceptable as opt-in (the worker degrades to a DialogError), but consider an optional extra (e.g. `[voice]`) so installs are reproducible.

8. [NIT] `tools/robot_dialog.py`, `OpenAIResponsesProvider` (default model `chat-latest`): fully implemented and tested, but no selectable backend routes to it — `"openai"` maps to `gpt_live`, and `build_provider` raises for `gpt_live`. It is dormant code in this PR. Fix: remove or clearly mark it as reserved for a future mode.

4. POSITIVES:
- **Credential isolation verified**: `OPENAI_API_KEY` is only read Mac-side (`os.getenv` in `_handle_realtime_session` and `OpenAIResponsesProvider`); phone sends only SDP + tier header; no key/token/IP reaches the APK path, logs, or response bodies. HTTPError handling deliberately discards upstream bodies ("Never include a response body: providers may echo prompts or secrets") in both `_json_request` and the streaming provider.
- **Bounded error slugs verified**: `_safe_realtime_error_details` regex-bounds `type/code/param` to `[A-Za-z0-9_.:/+-]{1,160}` and never forwards `message`; statuses collapse to {400,401,403,429,502}; tests assert slug-only extraction and message rejection.
- **No unauthenticated endpoints**: both new relay routes call `_http_auth_error` (pairing-code Bearer + IP rate limiting/blocking); test asserts 401 without token and 503-without-key body contains no "key".
- **Worker confinement is real, not a comment**: `hermes_main(query=..., toolsets="memory", ...)` is a hard-coded call; spoken text travels via stdin JSON (test asserts `"Privater Satz" not in " ".join(command)`); `@` → full-width `＠` neutralizes Hermes' `@file` expansion (tested); model/provider are slug-validated (injection test included); worker error output omits provider text (`type(exc).__name__` only).
- **Phone-event channel strictly validated**: allow-listed names, exact key-set equality, role allow-list, 4000-char cap, `itemId` regex, NUL rejection, relay-assigned sequence numbers; tests assert arbitrary events, `role: "system"`, and extra keys are dropped.
- **PII convention preserved and extended**: relay log masking gains `"caption"` in `_SENSITIVE_BODY_KEYS`; cloud-bound text gets `redact_personal_identifiers` (email/phone/intro-name, 2000-char cap); memory-bound text gets `redact_for_memory` (email/phone/secret values); each is unit-tested. Event logging emits only the event name, never transcript text.
- **Media retention enforced**: PTT WAVs live only in a `TemporaryDirectory` per turn and are fetched under a 32 MB declared-size cap with WAV format validation (mono/16 kHz); noise-video downloads are capped at 256 MB with `ftyp` magic validation and temp-file cleanup on error; phone-side retention (10 clips + stale `.part` cleanup) is surfaced via `retentionLimit`.
- **Config bounding**: `bounded_env_int` clamps record seconds (2–15), VAD (300–3000 ms), history, timeouts; relay URL scheme validated (`http/https` only); process lock (flock, 0600) prevents duplicate companions; session-id store and transcript files created 0600 in 0700 dirs (permission assertions in tests).
- **install.sh** only copies the three companion files into the plugin dir (no autostart, no secrets); `.gitignore` adds `/backups/` for local PII backups.
- **Secrets scan clean**: only fixtures appear (`TEST01`/`TEST85`/`ROBOT1` pairing codes, `test@example.com`, loopback IPs, `<mac-tailscale-address>` and `OPENAI_API_KEY=...` placeholders). No real keys, IPs, emails, or operator home paths.

5. OPEN-QUESTIONS:
1. Does hermes-agent's `cli.main(toolsets="memory")` guarantee zero device/terminal/purchase/message/call tools — including when `resume=<session_id>` restores a previously persisted session? This repo's tests can only assert the kwargs passed; the enforcement lives in the hermes-agent repo. Recommend pinning/documenting the required CLI contract.
2. Is `OpenAI-Safety-Identifier` actually consumed upstream? If not, dropping it removes finding 1 entirely.
3. Model slugs `gpt-realtime-2.1-mini`, `gpt-realtime-2`, `gpt-realtime-2.1`, `chat-latest` look operator-specific/futuristic — please confirm they are valid upstream model identifiers before release (env overrides exist, but defaults should resolve).
4. Is unbounded transcript-archive growth (finding 4) intentional for the operator's archival use case?

Supervisor follow-ups (I could not execute; read-only + broken `rg` binary in this environment): run `pytest tests/` to confirm the new suites pass, and `git diff main... -- tools/android_tool.py hermes-android-plugin/android_tool.py` if a mechanical parity check beyond my hunk-by-hunk comparison is desired.