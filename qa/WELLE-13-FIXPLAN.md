# Welle 13 — Fixplan aus Sirs Abnahme-Audit (11.11.2026)

Sirs 7 Befunde — Ursache + Fix je Punkt. Reihenfolge: Schnell-Fixes zuerst, dann Struktur-Umbauten, dann Design-Pass. Abnahme = komplette qa/qa-checkliste-release.md + Bericht in qa/reports/welle-13/.

## P1 — Screenshot-Sperre im Chat („nervt mega") — SCHNELL-FIX
- Ursache: FLAG_SECURE-Weg (W3: war für den PIN-/Sperr-Screen gedacht, klebt aber an der Chat/Whole-Activity-Schale).
- Fix: FLAG_SECURE vollständig aus Chat/MainActivity entfernen. Wenn überhaupt, NUR am PIN-Verriegelungs-Screen selbst (Begründung im Bericht). Verifikation: Emulator-Screencap im Chat ≠ schwarz.

## P2 — Settings-Design (Text-Stacking + klebende Versionszeile + „-play") — SCHNELL-FIX + REDESIGN
- Ursache a) Karten ohne sauberen AutoWrap/Höhen-Fuß (Erlaubnis-Karten brechen Buchstabenweise); b) Version-Text ohne eigenen Abstand direkt unter „Einstellungen"; c) versionName zeigt BuildConfig-String mit Editions-Suffix.
- Fix a) Karten-Layout auf konsistentes Spacing-Regelwerk (16 dp Sektion, 8 dp Zeile, AutoWrap), Abstände überall.
- Fix b) **Sirs Direktive 11.11.: Version = NICHT dauerhaft sichtbar.** Versionszeile aus dem Kopf RAUS; stattdessen ein Info-Button (ℹ️) am Settings-Kopf → About-Sheet mit: Versionsnummer (nur „v0.15.2", ohne -play), Impressum, Datenschutz, Nutzungsbedingungen, ggf. Licenses.
- Fix c) Overall-Redesign der Einstellungen (Sir): aufgeräumt, platzsparend — Abschnitte als **ausklappbare Sektionen** (Expandable/Accordion-Karten), Standard = eingeklappt; Apple-Design-Pass (P5) direkt mit drin.

## P7 — Impressum/Datenschutz/Nutzungsbedingungen + Accept — STRUKTUR
- Fix a) Rechtliches-Gate als Onboarding-Schritt (Accept vor „Fertigstellen", Zustand persistiert).
- Fix b) **Sirs Direktive 11.11.: Rechts-Dokumente leben ausklappbar in den Einstellungen/Info-Bereich** (keine fünf eigenen Top-Level-Einträge): Abschnitt „Rechtliches" mit Expandern — Impressum, Datenschutz (DSGVO, Österreich, Serverbetreiber-Angabe), Nutzungsbedingungen; Inhalt als Assets/Strings, jederzeit erreichbar (Einstellungen → ℹ️-Info), österreichrechtlich korrekt.

## P3 — Sprache kaputt + Sprache = Schritt 1 + Rechtschreibprüfung — STRUKTUR
- Fix a) OnboardingSteps: NEUER Schritt 0 „Sprache" (DE/EN Tiles) VOR der Kopplung; AppLanguage-Gate wird dadurch initialisiert.
- Fix b) Sprachwechsel: Live-Rewrap verifizieren (Settings-Wechsler + Assistenten-Wechsler); Bug-Report wenn set() Texte nicht neu rendert.
- Fix c) Voll-Differ aller strings-Ressourcen (de-AT + EN): Tippfehler, geteilte Wörter, Inkonsistenzen; Liste im Bericht.

## P4 — App-Berechtigungen: Einrichtung + Verwaltung — STRUKTUR
- Fix: Assistenten-Schritt „Berechtigungen" (je Berechtigung: Zweck + Status + Button → Systemeinstellungen) UND Einstellungen-Panel „Berechtigungen" mit demselben Verband. Degradation-Texte bei Verweigerung.

## P5 — Apple-Design-Pass — STRUKTUR/DESIGN
- apple-design-Skill als Grundlage über ALLE Screens: Touchhöhe, Typo-Skala, Abstände, Haptik, Kontraste, Karten-Silhouette; Ergebnis-Problemliste in den Bericht, Fix je Punkt. Zuerst Settings (P2), dann Chat/Komposer, dann Onboarding.

## P6 — „Auto"-Chip tot — BUG-SUCHE SCHNELL-FIX
- Symptom: Tippen auf „Auto" am Komposer bewirkt nichts (Sheet sollte aufgehen; in der Emu-Kette war der Sheet-Pfad noch nicht bewiesen).
- Diagnose: ChatInputBar-Chip → onOpenPicker → composerPickerOpen (ConversationBody-Scope) → ChatModelSheet rendern. Verdächtig: Zustands-Scope/Rückverdrahtung oder Klick-Ziel = zu kleine Hitbox. Fix + Emulator-Klick-Beweis.

## P7 — Impressum/Datenschutz/Nutzungsbedingungen + Accept — STRUKTUR
- Fix: Rechtliches-Gate als Onboarding-Schritt (Accept vor „Fertigstellen", Zustand persistiert), Dokumenttexte als Assets/Strings, Einstellungen → Rechtliches (Impressum, Datenschutz, Nutzungsbedingungen) jederzeit erreichbar. Österreichrechtlich korrekt (Anbieter, Haftungshinweis Play-Grenzen, DSGVO: Datenverarbeitung = nur Kopplungsdaten + Serverbetreiber-Angabe).

## Prozess-Pflichten (ab jetzt bindend)
- Prüfliste: qa/qa-checkliste-release.md = KOMPLETT abzuarbeiten je Prüfung.
- Berichte: qa/reports/<welle>/ (Build-, Drill-, Checklisten-Kopie).
- Privatsphären-Scan (G) = hartes Release-Gate.
