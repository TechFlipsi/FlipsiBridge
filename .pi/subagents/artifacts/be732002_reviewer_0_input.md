# Task for reviewer

You are reviewing GitHub PR #96 on hermes-android (draft, +7939/-162, 56 files).
Repo root: /home/paseo/projects/hermes-android — read AGENTS.md and SECURITY.md there first for repo conventions (this is a security-sensitive remote-control bridge: full device access once paired).
The full unified diff is at /home/paseo/projects/hermes-android/.pr-review/pr96.diff (9190 lines).
Current repo files reflect main BEFORE the PR; use them as the baseline for understanding what changed.
Do NOT modify any files. Read-only review.

Output format (strict):
1. VERDICT: one of 'approve', 'approve-with-nits', 'request-changes', 'block'
2. ARCHITECTURE: 3-6 sentence assessment of design quality for your scope
3. FINDINGS: numbered list, each tagged [BLOCKER]/[MAJOR]/[MINOR]/[NIT] with file path and line context, evidence quote from the diff, and concrete fix suggestion
4. POSITIVES: brief list of things done well
5. OPEN-QUESTIONS: anything needing maintainer/author clarification
Be specific and evidence-based. Cite diff lines. No vague hand-waving.

YOUR SCOPE: the Python toolset side of the PR. Files:
- tools/robot_dialog.py (1806 lines, NEW — robot dialog backends incl. OpenAI Realtime tiers mini/standard/top)
- tools/hermes_dialog_worker.py (170, NEW — PR claims answer workers get memory tools ONLY, no device/purchase/message/call capabilities)
- tools/whisper_worker.py (62, NEW)
- tools/android_relay.py (+436/-66) and hermes-android-plugin/android_relay.py (+436/-66)
- tools/android_tool.py (+174) and hermes-android-plugin/android_tool.py (+170)
- tests/: test_robot_dialog.py (699), test_android_relay.py (213), test_android_tool.py (+75/-5), test_hermes_dialog_worker.py (68)
- pyproject.toml (+3), setup.py (+3), install.sh (+4), skills/android/SKILL.md, hermes-android-plugin/skill.md, README.md, docs/robot-dialog.md (213), .gitignore (+3)

REVIEW PRIORITIES:
1. Credentials & security (this is a remote-control bridge): how are OpenAI API keys / relay tokens handled in robot_dialog.py and workers? PR claims credentials stay Mac-side and errors are reduced to bounded slugs before reaching the phone — verify. Any key/token/IP leakage into logs, tool responses, or the APK path? Any new network endpoints opened without token auth?
2. Worker capability confinement: verify hermes_dialog_worker.py actually restricts the worker to memory-only tools — no path to android_* device tools, purchases, sends, or calls. Look for capability lists, tool filtering, and whether the restriction is enforceable or just a comment.
3. Copy-parity convention: repo convention is tools/ == hermes-android-plugin/ for Python. android_relay.py shows identical +436/-66 both copies (good), but android_tool.py shows +174 (tools/) vs +170 (plugin/) — diff the two versions' hunks and explain the 4-line difference; flag if it breaks byte-for-byte parity.
4. New dependencies: what do pyproject.toml/setup.py add (+3 lines each)? Are they justified, pinned reasonably, and healthy? install.sh +4 — what does it do now?
5. PII convention: repo strips phone numbers/recipients/location from tool responses — do the new dialog/voice tools preserve that convention for transcripts and media paths?
6. Media retention: PR claims bounded size + retention for completed media and temporary PTT WAVs — find the enforcement code and assess it.
7. Test quality: do test_robot_dialog.py (699 lines) and others actually assert the security-relevant behaviors (credential isolation, error-slug bounding, worker confinement) or just happy paths?
8. Secrets scan: grep diff hunks in your scope for anything looking like an API key, token, IP address, email, personal identifier, or absolute home paths of the operator.
Extract per-file hunks from the diff or read the whole diff — your choice, but cover ALL files in scope.

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