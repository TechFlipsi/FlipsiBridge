# Build-Report W15c-FINAL — 12.11.2026 (Final-Build v0.15.3)

Auftrag: Final-Build v0.15.3 @4b81775 am Stand-PC — pull origin main (Erwartung 4b81775), testPlayDebugUnitTest, assemblePlayRelease + bundlePlayRelease, Artefakte nach C:\tmp-artifacts + /root/FlipsiBridge/artifacts/ als `app-play-release-0.15.3.aab/.apk` (überschreibt das obsolete vc104-Set von 15:52; der `-w15b`-Satz bleibt als Historie), MD5, aapt-Beweis. NUR BUILD — kein Emulator-Bedienung, kein Shutdown, keine Prozesse gekillt (Drill-Umgebung stellt der Drill-Subagent per Skill flipsibridge-release-emulator-drill wieder her).

## 1. Git-Pull (Stand-PC-Klon C:\Dev\hermes-android-bridge\hermes-android-bridge)
- `git pull origin main`: Fast-forward `c7a84fa..4b81775`, 11 Dateien geändert (W15c-Fixes: StateBanner-Shape, Onboarding-heightIn, RelayServerList-Ellipsis, Chat-Pending-Merker nur bei Match, HealthRationale unter AppLanguageGate, + Tests/Strings)
- HEAD: `4b817759ef65d509b951318d5d2a53aeb8863291` — ERWARTUNG 4b81775 ERFÜLLT ✓

## 2. Unit-Tests: testPlayDebugUnitTest — GRÜN ✓
- gradlew.bat testPlayDebugUnitTest: Exit 0, `BUILD SUCCESSFUL in 1m 14s`
- Log: C:\Dev\w15c-tests.log (Stand-PC)
- Exakt (aus 58 XML-Ergebnisdateien in app\build\test-results\testPlayDebugUnitTest):
  **TESTS=387 FAILURES=0 ERRORS=0 SKIPPED=2** (identisch zu W15b-II)

## 3. Release-Build: assemblePlayRelease + bundlePlayRelease — GRÜN ✓
- Exit 0, `BUILD SUCCESSFUL in 1m 3s`
- Tasks inkl. `:app:signPlayReleaseBundle`, `:app:assemblePlayRelease`, `:app:bundlePlayRelease` (signiert)
- Log: C:\Dev\w15c-build.log (Stand-PC)

## 4. Artefakte
Quellen:
- APK: app\build\outputs\apk\play\release\flipsibridge-0.15.3-play.apk (13.310.913 B)
- AAB: app\build\outputs\bundle\playRelease\app-play-release.aab (12.678.393 B)

Finale Namen (beide Ziele, überschreiben das obsolete vc104-Set von 15:52):
- C:\tmp-artifacts\app-play-release-0.15.3.apk / .aab
- /root/FlipsiBridge/artifacts/app-play-release-0.15.3.apk / .aab
- Der `-w15b`-Satz bleibt in beiden Verzeichnissen unangetastet (Historie, 16:37/16:40 Uhr).

### MD5 (certutil am PC = md5sum auf kibot, identisch ✓)
| Artefakt | MD5 | Größe |
|---|---|---|
| app-play-release-0.15.3.apk | 6e6aa867d7b922b32f81911011fc7d1b | 13.310.913 B |
| app-play-release-0.15.3.aab | 002ba704894b304dc9e28417139f4833 | 12.678.393 B |

## 5. aapt-Beweis (build-tools 35.0.1, APK in C:\tmp-artifacts)
```
package: name='at.flipsi.bridge.play' versionCode='105' versionName='0.15.3-play' platformBuildVersionName='16' compileSdkVersion='36'
launchable-activity: name='com.hermesandroid.bridge.MainActivity'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
```
→ **versionCode=105 ✓, versionName=0.15.3-play ✓** (Play-Flavor-Paket at.flipsi.bridge.play ✓)

## 6. versionCode-Lage (Auftragsvorgabe)
- versionCode bleibt 42 (github) / 105 (play) — kein Bump; die nie hochgeladenen vc105-W15b-Artefakte werden durch diesen Satz ersetzt (intern), die alten vc104-0.15.3-Dateien von 15:52 waren obsolet und wurden überschrieben.

## 7. Betriebs-Status
- KEIN Shutdown, KEINE Prozesse gekillt; Emulator/Relay/CDP blieben unangetastet (DOWN, wird vom Drill-Subagenten per Skill flipsibridge-release-emulator-drill wiederhergestellt).
- scp'd .ps1-Helfer (w15c-verify.ps1, w15c-collect.ps1) auf dem PC wieder gelöscht; nur die Logs C:\Dev\w15c-tests.log / w15c-build.log bleiben als Beleg liegen.
