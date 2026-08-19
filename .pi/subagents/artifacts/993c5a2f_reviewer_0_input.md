# Task for reviewer

You are reviewing an external fork PR on the hermes-android repo: PR #95 'feat: Microphone support Phase 1 (Ambient Sound Recording)' by Thykes3000.

REPO CONTEXT (hard rules from AGENTS.md):
- hermes-android is a remote-control bridge: Kotlin bridge app (hermes-android-bridge/) + Python toolset (tools/, tests/, hermes-android-plugin/).
- SECURITY-CRITICAL: once paired, the bridge has full device access. Never expose pairing codes, server IPs, tokens, screen content outside the task.
- Direct USB/LAN dev -> phone Ktor server port 8765; relay mode -> port 8766.
- Python tools must strip PII (phone numbers, recipients, locations) from responses/logs (existing convention).
- Bug-fix regression tests go in tests/ (Python) and bridge unit tests (Kotlin).
- Destructive/sensitive on-device actions need user confirmation.

PR CONTENTS (already fetched):
- Worktree: /tmp/pr95-wt  (branch pr-95-review, merge-base with origin/main is 08770ab)
- Diff: /tmp/pr95.diff (3390 lines)
- Adds: audio/ package in Kotlin bridge (MicrophoneRecorderService, MicrophoneRecordingFiles, MicrophoneRecordingState, WavFileWriter) + unit tests
- Modifies: AndroidManifest.xml, BridgeRouter.kt, CommandDispatcher.kt, RelayClient.kt, BridgeApplication.kt, BridgeNotificationListener.kt
- Modifies Python: tools/android_relay.py (+210/-12), tools/android_tool.py (+142), hermes-android-plugin/* copies, tests/test_android_tool.py (+82/-4), tests/test_android_relay.py
- Also adds/edits many files that look unrelated to mic support: CODEX-START.md, HANDOVER.md, MILESTONES.md, docs/FALLSTRICKE.md, docs/KI-KOORDINATION.md, docs/cradata/* (German personal experiment reports), edits to README.md, SECURITY.md, CHANGELOG.md, skills/android/SKILL.md, setup.py

REVIEW FOCUS (in priority order):
1. SECURITY: Are the new mic endpoints (mic_start/mic_stop/mic_status, any audio fetch/download endpoint) authenticated exactly like existing endpoints? Any path traversal in audio file fetch (filename params)? Any unauthenticated routes? Any token/IP leakage in new code paths or logs? Where do recordings land and who can read them? Indefinite recording (duration=0) abuse surface.
2. CORRECTNESS: WAV writer correctness (header fields, byte counts, little-endian), recording state machine races, foreground service lifecycle (startForeground within timeout, stop handling, process death), relay forwarding correctness (chunking, timeouts), duplicate code between tools/ and hermes-android-plugin/ copies (repo convention: they must stay unified).
3. CONVENTIONS: commit hygiene, scope creep (are the unrelated docs/SECURITY.md edits acceptable?), PII-stripping convention respected, test quality (do new tests actually cover the new behavior?).
4. Does the Kotlin code compile conceptually (API usage plausible for the target SDK used in hermes-android-bridge/app/build.gradle.kts)?

Work from /tmp/pr95-wt and /tmp/pr95.diff. Do NOT modify any files. Do NOT run builds that write outside /tmp.

OUTPUT FORMAT:
## Strengths
## Critical (must fix, esp. security)
## Important
## Minor / nits
## Scope assessment (which files belong in this PR, which should be dropped)
## Verdict (merge as-is / request changes / reject, with one-line rationale)

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