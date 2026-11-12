# ENDKONTROLLE W15-Finale — AUDIT-REPORT (STOP)

Datum: 12.11.2026 · Auditor: Endkontrolle-Subagent · Auftrag: Beweiskette prüfen VOR Release/Play/Shutdown.

**Fazit: STOP.** Punkt 1 klar ✓, Punkt 2 mit 1 harten Widerspruch ✗, Punkt 3 teilweise nicht rekonstruierbar ✗. Kein GitHub-Release, kein Play-Upload, kein send-for-review-Klick, KEIN Stand-PC-Shutdown ausgeführt.

## 1. Fixliste 10 Punkte vs Drill-Beweise — KLAR ✓
CHANGELOG-0.15.3-Block deckt Fix 1–10 (1/2/10 Chat, 3/4 Agenten, 5 I18n, 6/7 Einstellungen, 8/9 Dashboard).
- qa_w15/ (scratch): fix01, fix02, fix03, fix05, fix05_en, fix06a/b/c, fix07, fix08, fix09, fix09_de, fix10, fix10b, fix10c_voice, e2e_tab — jeder Fixlisten-Punkt hat Screenshot-Beweis.
- qa_w15b/ (w15b_report.md): Re-Drill auf FINAL-Artefakt (md5 6e6aa867…, vc105) — **5/5 PASS** (fix02b Chat-Bubble-Meta unten-rechts, fix03b Agenten-Tab ohne Titel, fix06b Accordion-Status zentriert DE+EN, fix07b Sprachwechsel live, fix09b Dashboard ohne URL-Feld) + Regression (Send ✓, Voice ✓ E2E, logcat 0 FATAL/0 ANR).
- Keine Lücke in der Fix↔Drill-Abdeckung.

## 2. Build-Kette — 1 WIDERSPRUCH + 1 LÜCKE ✗
Verifiziert ✓:
- kibot-Repo HEAD = `4b81775` (Erwartung erfüllt); build.gradle @HEAD: versionCode **42 (github) / 105 (play)**, versionName 0.15.3.
- MD5 lokal nachgerechnet, alle matchen die Reports:
  - app-play-release-0.15.3.aab `002ba704894b304dc9e28417139f4833` (12.678.393 B)
  - app-play-release-0.15.3.apk `6e6aa867d7b922b32f81911011fc7d1b` (13.310.913 B)
  - app-github-release-0.15.3.apk `35c47406c599c0ad051d0f64ae1ecb39`
  - w15b-Historie: apk `22886a65…` / aab `5fc39a4f…` (matcht w15b-II-Report)
- aapt-Beweis w15c-final: `versionCode='105' versionName='0.15.3-play'`, Paket at.flipsi.bridge.play ✓; Unit-Tests 387/0/0/2 (w15b-II und w15c-final identisch).

**DEFEKT D1 (hart):** Der CHANGELOG-0.15.3-Block — genau der vorgesehene GitHub-Release-Body, enthalten im Tag-Commit `4b81775` — behauptet in Zeile 8: **„versionCode 41 (github) / 104 (play)"**. Das widerspricht build.gradle (42/105), dem aapt-Beweis (vc105) und dem Final-Status (github vc42 / play vc105). Ursache: Zeile stammt aus Commit `595290b` (Erst-Bump 41/104); der Re-Bump `2341665` (→42/105) ergänzte nur die W15b-Zeile und korrigierte die Versionszeile NICHT. Ein Release mit diesem Body veröffentlicht falsche Versions-Metadaten, die den eigenen Assets widersprechen.

**LÜCKE D2:** Für `app-github-release-0.15.3.apk` (Release-Asset) existiert **kein Build-/aapt-Report auf Disk** — nur die Datei (17:16 Uhr) + MD5. Der Play-Satz hat einen vollständigen Report (w15c-final), das GitHub-Asset nicht: Erbaut von welchem Commit/Task, vc/aapt-Beweis = nur im Parent-Kontext behauptet, nicht als Datei belegt.

## 3. Jäger-Befunde-Abdeckung — TEILWEISE UNKLAR ✗
Auf Disk belegbar ✓:
- Jäger-Fixes in W15c: `b957a76` („Jäger B5/A, i18n-Blocker": DashboardRules LabelRes statt deutsche Literale) + `4b81775` („KLEIN/MIT": StateBanner-Shape, Onboarding-heightIn, RelayServerList-Ellipsis, Chat-Pending-Merker, HealthRationale unter AppLanguageGate).
- „Die 3 dokumentierten": w15b_report.md „Befunde abseits der 5 Punkte (nur Protokoll, KEINE Fixes)" = genau 3 (Reply-Meta außerhalb der Bubble / HUD-Kachel am Scrollrand angeschnitten / Supporter-Karte hardcoded DE).

Unklar ✗:
- Die Enumerierung der **9 gefixten** Jäger-Befunde (je Befund-Nummer Jäger A/B) ist auf Disk **nicht rekonstruierbar** — die Jäger-Reports liegen nur im Parent-Kontext, nicht als Datei im Repo/qa-Baum. Kontext sagt „Jäger A+B = 9 Befunde", Auftrag sagt „die 9 fixten vs die 3 dokumentierten" — die Zähl-Semantik (9 total? 9 fix + 3 doc = 12?) lässt sich aus dem Disken-Beweis nicht auflösen. Bis maximal 6 Jäger-Fix-Befunde sind über Commits belegbar (1× b957a76 + 5× KLEIN/MIT in 4b81775).

## Empfohlene Auflösung (Entscheidung Parent/Sir)
1. D1: Einzeilen-Fix der CHANGELOG-Versionszeile (41/104 → 42/105) als eigener Commit. Dann entscheiden: Tag v0.15.3 auf 4b81775 belassen und korrigierten Block als Body (Tag-Baum ≠ Body) ODER Tag auf den Fix-Commit (abweichend von „Tag auf 4b81775").
2. D2: Build-/aapt-Report für app-github-release-0.15.3.apk nachliefern (Commit, Build-Log, aapt badging, MD5) — oder das Asset aus dem w15c-final-Build neu erzeugen und dokumentieren.
3. Punkt 3: Jäger-A/B-Reports (9 Befunde, je Nummer + Fix/Doku-Verweis) als Datei ablegen (z. B. qa/reports/w15c-final/jaeger-abdeckung.md), damit die 9-vs-3-Zählung auditierbar ist.
4. Danach Re-Dispatch: Release → Play-Upload (vc105, interner Track) → CDP send-for-review → Stand-PC-Down mit TCP-22-Beweis.

## Betriebs-Status
Stand-PC unverändert angelassen (Chrome/Console-Session lebt, keine Prozesse beendet, KEIN shutdown — PC-Aus nur nach vollständiger Verifikation).
