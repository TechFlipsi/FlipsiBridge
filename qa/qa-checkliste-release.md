# RELEASE-QA-CHECKLISTE — FlipsiBridge (Lückenlos-Pflicht)

**Status:** VERBINDLICH ab Welle 13 (Sir-Beschluss 11.11.2026).
**Regeln:** Diese Liste wird bei JEDEM Test-/Release-Durchlauf KOMPLETT abgearbeitet — kein Punkt darf übersprungen werden. Nichteinlösbares = als Befund im Bericht, nie als stilles Abhaken.
**Berichte:** Jeder Durchlauf legt einen Bericht ab unter `qa/reports/<welle oder datum>/` (Build-Report, Drill-Report mit Screenshots, ausgefüllte Kopie dieser Checkliste). Kein Bericht = Release gilt nicht als geprüft.

## A. Identität & Branding
- [ ] App-Name: „FlipsiBridge" — Launcher, Settings-Kopf, Store-Eintrag, Everywhere konsistent (keine Paketnamen als Titel).
- [ ] App-Icon vorhanden und identisch: Launcher (alle DPI), Store-Listing 512×512.
- [ ] Versionsanzeige: NUR „vX.Y.Z" (kein Editions-Suffix wie „-play" in der UI), eigener Zeilenabstand — klebt NIRGENDS an einem anderen Text.
- [ ] Keine Flavor-/Editions-Leaks in der UI (Strings, Debug-Marker, BuildConfig im Text).

## B. Sprache & Texte
- [ ] Sprachauswahl = ERSTER Schritt des Einrichtungsassistenten (vor allem anderen).
- [ ] Sprachwechsel wirkt SOFORT auf jeden sichtbaren Screen und persistiert nach Neustart (Verifikation: je DE und EN mindestens 5 Kernscreens besichtigt).
- [ ] Vollständige Rechtschreibprüfung aller UI-Strings (de-AT und EN) — Differ über alle string-Ressourcen, nicht Musterprobe.
- [ ] KEIN Text-Stacking: Buchstaben untereinander/zerbrochene Wörter = Abfangen. Prüfung: jede Einstellungs-Karte mit langem Text (z. B. Erlaubnis-Karten) im Emulator SCHRIFTLICH besichtigt.
- [ ] Zahlen/Formatierung lokalkonform (de-DE/AT: Punkte, Währung, Datum).

## C. Einrichtungsassistent (Onboarding)
- [ ] Reihenfolge: 1) Sprache → 2) Einführung → 3) Kopplung → 4) Fingerabdruck. Kein anderer Screen vor der Sprache.
- [ ] „Überspringen" in jedem Überspringbaren Schritt; Verhalten dokumentiert (Skip-Merker, Serverliste-Einstieg „Assistent später fortsetzen").
- [ ] Kopplung: BEIDE Modi — Server-Code eingeben UND „Neuen Code am Gerät erzeugen" (Großansicht + Kopieren + Anleitung „am Server eintragen").
- [ ] Berechtigungs-Einrichtungsschritt: App-Berechtigungen erklären (Grund je Berechtigung) + Status + Öffnen der Systemeinstellungen.
- [ ] Rechtliches-Gate: Impressum/Datenschutz/Nutzungsbedingungen ERREICHBAR + Pflicht-Accept vor der Erstnutzung (Checkbox vor „Fertigstellen").
- [ ] Keine Datenabfragen vor dem Rechtliches-Accept.

## D. Berechtigungen (App)
- [ ] Einrichtungs- und/oder Einstellungs-Screen listet ALLE App-Berechtigungen mit Zweck + Status + Sprung in die Systemeinstellungen.
- [ ] Manifest = Minimalset (Audit: aapt2 badging + manuelle Gegenprüfung je Zeile).
- [ ] Verweigerte Berechtigungen: App degradiert sauber (keine Crashes, Hinweistexte statt stiller Fehler).

## E. Chat & Komposer
- [ ] Screenshot-Fähigkeit: Screenshots im Chat MÜSSEN möglich sein (FLAG_SECURE nur dort, wo es zwingend begründet ist — Begründung pro Screen im Bericht). Emulator-„schwarze Screencap" = FAIL.
- [ ] Modell-Chip öffnet das Sheet (echter Klick-Test + Screenshot-Beweis).
- [ ] Sheet funktioniert: Modellwahl speichert, Chip-Label aktualisiert sich, „Auto"-Zustand sichtbar.
- [ ] Denken-Schieber: alle 4 Stufen durchgetestet, Label aktualisiert, Einstellung persistiert + reist mit der Nachricht.
- [ ] Senden: Online + Offline-Fall, Status-Chips (wartend/gesendet/Gelesen/fehlgeschlagen) stimmen.
- [ ] Verlauf: Scrolling, Zitieren, Medien, Voice — je ein Durchgang.

## F. Einstellungen-Design
- [ ] Konstante Spacing-Regeln (keine aneinanderklebenden Texte/Zeilen) — Besichtigung aller Einstellungs-Panels.
- [ ] Dark- und Light-Theme konsistent (Kontraste, keine weißen Blitze).
- [ ] Apple-Design-Grundsätze: Mindest-Touchhöhe 48 dp, Typo-Skala, konsistente Karten, Haptik bei Aktionen, keine plattformfremden Muster.
- [ ] Version/Info-Block sauber (siehe A).

## G. Privatsphäre-Scan (HARTES GATE)
- [ ] Source-Scan (Build-relevant): KEINE privaten Domains (kirchweger.de, flipsi.*-Produktionshosts), KEINE privaten IPs (192.168.*., 10.*., 172.16-31.*), KEINE echten Namen/Telefonnummern/private Mailadressen, KEINE Tokens/Codes/IDs.
- [ ] Release-Strings-Scan: `strings aapt2-Output/dex` gegen dieselbe Negativliste (Beweiszeilen in den Bericht).
- [ ] Manifest-Permissions = Minimalset; keine Debug-Rücktüren im Release.
- [ ] Fehlschlag EINES dieser Punkte = Release-Block (Fix vor jedem weiteren Schritt).

## H. Recht & Play-Compliance
- [ ] Impressum/Datenschutz/Nutzungsbedingungen: Inhalt vorhanden, korrekt (Österreich), erreichbar in der App (Einstellungen → ℹ️-Info-Button, **ausklappbare Sektionen**) + Assistent-Gate.
- [ ] Versionsinfo: NUR im Info-Button (About-Sheet, Format „vX.Y.Z" ohne Suffix) — NICHT dauerhaft im Settings-Kopf sichtbar.
- [ ] Settings-Redesign: Abschnitte als ausklappbare Sektionen, Standard eingeklappt, Spacing/Apple-Pass erfüllt.
- [ ] Play: Datenschutz-Richtlinie-URL gesetzt, Datensicherheit-Formular passend zu den tatsächlichen Datenflüssen, Zielgruppenfragen beantwortet.
- [ ] Keine Server-/Setup-Pflichten, die normale Nutzer nicht erfüllen können, ohne dass die App es erklärt.

## I. Technische Routine (je Build — Beweiszeilen in den Bericht)
- [ ] Compile beider Editions grün (compilePlayReleaseKotlin + compileGithubReleaseKotlin EXECUTED).
- [ ] Unit-Tests: beide Suiten 348/348 grün (NUR die 2 bekannten Windows-Symlink-Baseline-Fails erlaubt; jede andere = FAIL).
- [ ] Badging: package/versionCode/versionName/targetSdk je Edition geprüft.
- [ ] 5× Permission-Greps = 0 (REQUEST_INSTALL_PACKAGES, QUERY_ALL_PACKAGES, SEND_SMS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, RUN_COMMAND).
- [ ] apksigner: SHA-256 = erwartete Signaturkette, beide Artefakte.
- [ ] MD5s aller Artefakte notiert.
- [ ] Emulator-Drill: Onboarding KOMPLETT (mit Sprache!), Kopplung real, Chat-Realversand, Komposer-Aktionen, Settings-Besichtigung — Screenshots im Bericht.
- [ ] Bericht abgelegt: `qa/reports/<welle>/` (Build-Log-Belege, Drill-Report, ausgefüllte Checkliste).

## J. Abschluss-Gate
- [ ] Alle Punkte oben abgehakt ODER als offener Befund mit Verantwortung im Bericht.
- [ ] Sir bekommt: Kurzfassung + Berichtspfad. Kein „fertig" ohne Bericht.

## K. Audio & Sprachfeatures (Welle 13)
- [ ] Eingehende Sprachnachricht: im Chat ANHÖRBAR (Play/Pause/Fortschritt; eingehende Medien werden heruntergeladen, nicht nur als Meta angezeigt). Eigene Sprachnachrichten ebenfalls abspielbar.
- [ ] „Vorlesen“: im Komposer-Sheet wählbar (pro Chat) — Agenten-Antworten werden vom GERÄT vorgelesen (Android-TTS, Queue, Stopp-Eingriff), Einstellung persistiert.
- [ ] Standard-Assistent: „Als Standard-Assistent einrichten“ (RoleManager) funktioniert; ASSIST-Einstieg öffnet Voice-Chat; System-Default wechselbar (Gemini → FlipsiBridge).
- [ ] Fallback bei fehlender TTS-Engine: ehrlicher Hinweistext statt stiller Fehler.
