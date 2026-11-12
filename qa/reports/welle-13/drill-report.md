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
| Relay-Adresse-Schritt | ✓ | Feld + „https://<privates-Relay (im lokalen Bericht-File, nicht im Repo)>" eintragbar; Fehlerhafte Autokorrektur (Keyboard-Emulator) = kein App-Defekt — Feld akzeptiert Paste-Verband |
| Bridge kibot | ✓-kein Update nötig | /opt/flipsibridge-Dateien = md5-identisch zum Repo; mic_start/stop/status/file + Composer-Felder (model/effort) im Dienst |

## Offen (nächste Runde)
- REAL-Gateway-Kopplung (Relay-URL + Koppelcode per Clipboard/Tastatur-Verband) → dann Häkchen-Verband + Voice + Composer am echten Kanal
- Chat-Fenster-Verband (Bubbles, Tageschips, Wisch-Antwort-Bar) mit echtem Nachrichtenverkehr
- 4-Jäger-Gegenprobe je Fix-Runde (Sirs Abnahme-Standard)
- Impression: „Couple FlipsiBridge" EN-Titel → „Pair FlipsiBridge" (Befund B, kleiner Fix)

## Re-Drill R2 nach Jäger-Fixes (12.11., ~00:15 CET)
| Punkt | Beweis |
|---|---|
| 6-Schritte-Zähler live | „Schritt 3 von 6 — Relay-Adresse" (Screenshot fb_relay2) + „Schritt 2 von 6 — Rechtliches" (XML) |
| Skip-Verband | Button sichtbar am jeweiligen Schritt (fb_relay2) |
| Impressum/Datenschutz/AGB | Rechtliches-Schritt unangetastet sauber |
| Artefakte final | play.apk 9b9b8f675e… · github.apk cb2b4183… · aab 2319906719… (MD5) |
| Support-Verband | Herz-Knopf im Chat-Kopf → Einstellungen (Supporter-Karte, IAP 2,99) — compile green |
| Supporter-Sichtbarkeit | Badge „Unterstützer" hell-Ember + Herz in Karte und Dialog nach Kauf |
| Kontrast/Checks | DeliveryChecks-Grammatik im Bubble-Verband (Code-Beweis) |

## Offen
- Icon-Assign im Store-Listing: Dialog braucht echten Klick (Sir-Klick-Window 60 s)
- Echte Kopplung am Emulator (Paste/Server-Verband)
