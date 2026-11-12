# GitHub-Edition-APK vc42 — Build-/aapt-Report (nachgeliefert, kibot-seitig unabhaengig neu verifiziert)
Datum: 12.11.2026 — Artefakt: app-github-release-0.15.3.apk

## Beweise (kibot, frisch gerechnet — unabhaengig vom PC-Bau)
- SHA-Quelle: HEAD 4b81775 (git show -s)
- MD5: 35c47406c599c0ad051d0f64ae1ecb39 (md5sum kibot == PC-Angabe aus deleg_cb70d87b)
- aapt (kibot /opt/android-sdk/build-tools/35.0.0):
  package: name='at.flipsi.bridge' versionCode='42' versionName='0.15.3-github'
  platformBuildVersionCode= '36', compileSdkVersion='36'
- ZIP-Integritaet: 221 Meta/res/classes-Eintraege, unzip liest sauber
- apksigner verify (PC, deleg_cb70d87b): gruen; Zert CN=FlipsiBridge, O=Flipsi, C=AT; SHA-256 1da108d9..4c66b974 == keytool-Keystore-Fingerprint (1D:A1:08:D9:..66:B9:74)

## Provenienz
Bau: gradlew.bat assembleGithubRelease am Stand-PC (HEAD 4b81775), 48 Tasks, validateSigningGithubRelease im Task-Graph; Quellen-Paritaet PC-Baum vs kibot-HEAD: 245 getrackte Dateien geprueft (234 ident, 11 echte Differenzen = 10 Launcher-PNGs + gradle-wrapper.jar, per Remove+Extract synchronisiert + roh-hash-geprueft; alte PC-PNGs waren CRLF-Korrupte).

## Zweck
GitHub-Release-Asset (Edition github, volles Permission-Set, at.flipsi.bridge) — v0.15.3.
