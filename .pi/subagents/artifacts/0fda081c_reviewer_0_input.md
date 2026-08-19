# Task for reviewer

You are reviewing the last (and only) commit of merged PR #95 on hermes-android:
commit 5f2f8ab 'feat(bridge): add microphone recording support' — 33 files, +2030/-72, squash-merged to main.
Repo root: /home/paseo/projects/hermes-android — read AGENTS.md and SECURITY.md there first.
This is a security-sensitive remote-control bridge (full device access once paired). Mic recording is a surveillance-grade capability, so review security-first.

The full commit diff (git show 5f2f8ab) is saved at /home/paseo/projects/hermes-android/.pr-review/pr95.diff (2900 lines).
The commit is in HEAD, so you can also read current files directly (only later commit d119721 added a LICENSE, irrelevant).
Do NOT modify any files. Read-only review. This already shipped — the review is a post-merge audit to catch issues needing follow-up fixes.

SCOPE — what the commit added:
- MicrophoneRecorderService.kt (292 lines): foreground recorder, VOICE_RECOGNITION source, saturating 2.5x gain, 30-min cap
- MicrophoneRecordingFiles.kt (keep-last-10 retention), MicrophoneRecordingState.kt, PcmSampleProcessor.kt, WavFileWriter.kt (atomic .part->wav finalization)
- RelayClient.kt +134: relay WS binary streaming of recordings with SHA-256 verification
- BridgeRouter.kt +22, CommandDispatcher.kt +80: new /mic_start|stop|status|file endpoints (direct HTTP) + relay command handling
- AndroidManifest.xml +9: RECORD_AUDIO permission, foreground service declarations
- Python: tools/android_relay.py + hermes-android-plugin/android_relay.py (+222/+223), android_tool.py both copies (+142/+164): four android_mic_* tools with MEDIA: delivery
- Kotlin unit tests (4 files) + tests/test_android_relay.py +156 + tests/test_android_tool.py +86
- Docs: SECURITY.md, docs/architecture.md, docs/manual-tests.md, CHANGELOG

REVIEW PRIORITIES:
1. ENDPOINT AUTH (highest priority): verify /mic_start, /mic_stop, /mic_status, /mic_file and the relay-command paths all go through the SAME bearer-token verification as the pre-existing endpoints. Look for any route registered without the auth check, or auth checked after partial work.
2. FILE-SERVING SAFETY: /mic_file serves recording files — verify the requested filename/ID cannot escape the recordings directory (path traversal, .., absolute paths, symlink tricks). Check what happens with unknown IDs.
3. RELAY WS BINARY STREAMING: verify the SHA-256 verification is done over the full payload before use, that chunk reordering/truncation is handled, and that only authenticated relay sessions can push/request recordings.
4. PRIVACY GUARDRAILS: foreground notification present (user-visible while recording)? 30-min cap actually enforced? keep-last-10 deletion correct (no orphan .part files)? Are recordings world-readable anywhere (file modes)?
5. PYTHON TOOLS: android_mic_* tools — do they follow the MEDIA:<path> convention, strip PII from responses, and avoid logging audio content? Any token/URL leakage into tool responses or errors?
6. RESOURCE SAFETY: AudioRecord released on all paths (crash mid-recording, service kill)? .part files cleaned or finalized on abnormal stop? Gain stage overflow handling (saturating vs clipping)?
7. TEST COVERAGE: do tests cover auth failures, traversal attempts, cap enforcement, and retention — or only happy paths? Name specific gaps.
8. COPY PARITY: I already verified tools/android_relay.py == hermes-android-plugin/android_relay.py (identical) and android_tool.py differs only in android_setup import style (intentional). Confirm from the diff that #95 kept that discipline — flag if the diff shows any OTHER divergence introduced.
9. SECRETS SCAN: grep the diff for anything looking like API keys, tokens, IPs, or personal identifiers.

Output format (strict):
1. VERDICT: one of 'clean', 'follow-ups-needed', 'security-fix-needed'
2. SUMMARY: 3-5 sentence overall assessment
3. FINDINGS: numbered list, each tagged [BLOCKER]/[MAJOR]/[MINOR]/[NIT] with file path + evidence quote and concrete fix suggestion
4. POSITIVES: brief list
5. FOLLOW-UP TASKS: concrete list of fix tasks the maintainer should open (if any)
Be specific and evidence-based — cite diff/code lines.

## Acceptance Contract
Acceptance level: attested
Completion is not accepted from prose alone. End with a structured acceptance report.

Criteria:
- criterion-1: Return concrete findings with file paths and severity when applicable

Required evidence: review-findings, residual-risks

Finish with a fenced JSON block tagged `acceptance-report` in this shape:
Use empty arrays when no items apply; array fields contain strings unless object entries are shown.
`criteriaSatisfied[].status` must be exactly one of: satisfied, not-satisfied, not-applicable.
`commandsRun[].result` must be exactly one of: passed, failed, not-run.
`manualNotes` and `notes` are optional strings; an empty string means no note and does not satisfy `manual-notes` evidence.
```acceptance-report
{
  "criteriaSatisfied": [
    {
      "id": "criterion-1",
      "status": "satisfied",
      "evidence": "specific proof"
    }
  ],
  "changedFiles": [
    "src/file.ts"
  ],
  "testsAddedOrUpdated": [
    "test/file.test.ts"
  ],
  "commandsRun": [
    {
      "command": "command",
      "result": "passed",
      "summary": "short result"
    }
  ],
  "validationOutput": [
    "validation output or concise summary"
  ],
  "residualRisks": [
    "none"
  ],
  "noStagedFiles": true,
  "diffSummary": "short description of the diff",
  "reviewFindings": [
    "blocker: file.ts:12 - issue found, or no blockers"
  ],
  "manualNotes": "anything else the parent should know"
}
```