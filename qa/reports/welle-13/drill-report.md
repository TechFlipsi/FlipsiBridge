# Welle 13 — Drill-Report (Stand 12.11.2026, ~01:00 CET)
Bericht: qa/reports/welle-13/drill-report.md — Status je Checklisten-Punkt.

## Beweise aus Emulator-Drill (APK = flipsibridge-0.15.2-play.apk, MD5 aa898cf195491d6b057d6efe9975f125)
| Checklisten-Punkt | Ergebnis | Beweis |
|---|---|---|
| B Sprache Schritt 1 | ✓ | Assistent startet mit Schritt 1 „Sprache/Language"-Auswahl (Dump + Screenshot fb_d1) |
| B Live-Wechsel DE→App | ✓ | Nach Deutsch-Tap: Header/„Weiter"/„Systemsprache" auf Deutsch, Deutsch = Ember-Rahmen (fb_d2) |
| C Rechtliches-Gate Schritt | ✓ | „Schritt 1 von 5 — Rechtliches" mit 3 Karten; kein Datentransfer vor Accept (fb_legal) |
| C Impressum-Content | ✓ | TechFlipsi / Fabian Kirchweger, Marktplatz 11/3, 4625 Offenhausen — exakt Sirs Angabe; ausklappbare Karten (fb_imp) |
| C Accept-Pflicht | ✓ | Checkbox „Ich akzeptiere" → uiautomator: checked="true" im XML |
| B Kein Text-Stacking | ✓ | Rechtliches-Karte sauber gesetzt (fb_legal/Fachtext gelesen) |
| P11b Agenten-Liste | ✓ | Such-Kapsel + Chips Alle(Ember+✓)/Online/Offline + Avatarzeilen mit Online-Ampel; XML + fb_list2 |
| Skip-Assistent | ✓ | „Einrichtung Überspringen" → Chat-Liste (12b-Verband live) |
| Relay-Adresse-Schritt | ✓ | Feld + „https://REDACTED-RELAY" eintragbar; Fehlerhafte Autokorrektur (Keyboard-Emulator) = kein App-Defekt — Feld akzeptiert Paste-Verband |
| Bridge kibot | ✓-kein Update nötig | /opt/flipsibridge-Dateien = md5-identisch zum Repo; mic_start/stop/status/file + Composer-Felder (model/effort) im Dienst |

## Offen (nächste Runde)
- REAL-Gateway-Kopplung (Relay-URL + Koppelcode per Clipboard/Tastatur-Verband) → dann Häkchen-Verband + Voice + Composer am echten Kanal
- Chat-Fenster-Verband (Bubbles, Tageschips, Wisch-Antwort-Bar) mit echtem Nachrichtenverkehr
- 4-Jäger-Gegenprobe je Fix-Runde (Sirs Abnahme-Standard)
- Impression: „Couple FlipsiBridge" EN-Titel → „Pair FlipsiBridge" (Befund B, kleiner Fix)
