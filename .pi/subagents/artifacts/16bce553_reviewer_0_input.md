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

YOUR SCOPE: the Android/Kotlin + app-asset side of the PR. Files:
- AndroidManifest.xml (+30: new permissions), build.gradle.kts, MainActivity.kt
- audio/: MicrophoneRecorderService.kt (changed), PcmVoiceActivityDetector.kt (new)
- boot/BootReceiver.kt (new)
- client/RelayClient.kt (+164/-11)
- executor/ActionExecutor.kt (small change)
- media/: CameraVideoRecorder.kt, NoiseTriggerState.kt, NoiseTriggeredVideoService.kt (381 lines), NoiseVideoFiles.kt — ALL NEW
- robot/: RobotActivity.kt, RobotFaceView.kt (559), RobotRealtimeController.kt (170), RobotRealtimeTier.kt, RobotUiController.kt — ALL NEW
- server/: BridgeRouter.kt (+18), CommandDispatcher.kt (+180)
- assets/robot_realtime.html (189 lines, new WebView asset)
- Kotlin unit tests under app/src/test/

REVIEW PRIORITIES (security-first, this is a remote-control bridge):
1. Permissions & privacy: what new manifest permissions are added? NoiseTriggeredVideoService records camera video triggered by noise — is there a visible notification, size/retention caps (PR claims bounded size+retention), and is auto-start at boot (BootReceiver) justified/safe?
2. Relay command surface: CommandDispatcher +180 and BridgeRouter +18 add new remotely-triggered commands. Verify they go through the SAME auth/token verification as existing commands — no new unauthenticated or weakly-validated entry points. RelayClient changes: any credential/token handling regressions?
3. WebView: robot_realtime.html — JavaScript enabled? addJavascriptInterface bridges? Which origins/URLs are loaded? Any local file access, cleartext traffic, or hardcoded endpoints/keys? The PR claims OpenAI credentials never reach Android — verify nothing in APK assets or Kotlin code embeds an API key.
4. Realtime tiers: RobotRealtimeController/RobotRealtimeTier — how do audio streams flow? Where does auth come from? Any secret material in the APK?
5. Code quality: thread/coroutine safety in the new services, resource leaks (camera/mic), error handling, test coverage of the new logic (6 new test files — do they cover the security-relevant paths like trigger state and dispatch?).
6. Secrets scan: grep the diff hunks in your scope for anything that looks like an API key, token, IP address, email, or personal identifier.
Extract per-file hunks from the diff (e.g. grep/sed on 'diff --git' boundaries) or read the whole diff — your choice, but cover ALL files in scope.

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