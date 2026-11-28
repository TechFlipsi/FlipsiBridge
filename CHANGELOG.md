## [0.18.2] - 2026-11-25
versionCode 60 (github) / 123 (play).
### Hinzugefügt
- **Live-Status für Zweitgeräte:** Verbindest du ein weiteres Gerät (z. B.
  ein Tablet), sieht es sofort, woran die Agenten gerade arbeiten und welche
  Unter-Agenten laufen — auch wenn es sich später einklinkt, ohne dass die
  App neu starten oder eine Aktion auslösen muss.
### Behoben
- **Sichtbare Navigation und Bedienelemente:** Auf Tablets und im
  Querformat sitzt die Seitenleiste jetzt korrekt unterhalb der Kopfzeile —
  bislang war der oberste Menüpunkt hinter der Kopfzeile verdeckt und
  dadurch unauffindbar. Ebenso ist der Aufhängeknopf während eines laufenden
  Anrufs wieder frei (die Anruf-Leiste lag hinter der Kopfzeile), die
  Treffer-Liste der Chat-Suche endet sauber über der Wischleiste, und der
  Einrichtungs-Wizard sowie der PIN-Sperrbildschirm vermeiden die
  Uhrzeile und die Wischleiste auf Android 15+.
- **Kopplungspasswort nur bei der Einrichtung:** Ist das optionale
  Zusatz-Passwort aktiv, verlangt es die App jetzt ausschließlich beim
  Koppeln neuer Geräte — bereits gekoppelte Geräte verbinden sich wie
  gewohnt (vorher konnte ein nachträglich gesetztes Passwort bereits
  eingerichtete Geräte zeitweise aussperren). Fehlversuche sperren die
  Verbindung IP-weit für 5 Minuten.
- **Sauberes Listen-Ende auf Android-16-Geräten:** Auch die Agenten-, E2E-
  und Aufgaben-Listen sowie die Chat-Liste enden jetzt mit deutlichem
  Abstand über der Systemleiste — bislang zählte die App den unteren Rand
  auf Tablet- und Querformat-Ansichten bis zu dreifach, wodurch Karten
  sichtbar in der Wischleiste landeten.
- **Einheitliche Meldungen:** Rückmeldungen des Standard-Assistenten und
  des Teilen-Dialogs erscheinen jetzt als einheitliche Hinweis-Leiste im
  App-Design statt als kurze System-Meldungen.

## [0.18.1] - 2026-11-25
### Behoben
- **Deutlich besser lesbare Hinweisleisten:** Die Aktions-Schrift auf den
  orangefarbenen Hinweisleisten ist jetzt dunkel und erreicht saubere
  Kontrastwerte (6,7:1 statt vorher 2,2:1) — ein Gewinn für die
  Barrierefreiheit.
- **Nachrichten-Uhrzeiten** tragen kräftigere Schrift und volle Deckkraft —
  auf dunklem Hintergrund sind sie jetzt deutlich leichter zu lesen.
- **Größere Symbole** für Löschen/Kopieren bei Einladungen und für
  Umschalten/Löschen bei geplanten Aufgaben (Touch-Fläche unverändert 48dp).
- **Mehr Platz am unteren Rand** von Dashboard und Einstellungen.
- **Fehlermeldungen in Klartext:** Hinweis-Leisten zeigen verständliche
  Worte statt technischer Backend-Codes (z.B. bei zu vielen Versuchen
  oder verweigerten Aktionen).
- **Einladungen:** Die Liste zeigt die echte Restzeit („läuft in X Min. ab"),
  abgelaufene oder widerrufene Codes sind klar als solche erkennbar, und
  bei mehrdeutigen Geräte-Kennungen erscheint ein ehrlicher Hinweis.
- **Einmalige Aufgaben:** Datum und Uhrzeit formatieren sich beim Tippen
  selbst („202611" → „2026-11", „900" → „9:00").
- **Geplante Aufgaben je Agent:** Sprünge zu einem Agenten ohne Aufgaben
  enden nie mehr in einer leeren Sackgasse; Anlegen, Pausieren und Löschen
  gehen sicher an den jeweils gewählten Agenten.

## [0.18.0] - 2026-11-24
### Hinzugefügt
- **Geplante Aufgaben komplett verwalten:** Cronjobs lassen sich jetzt direkt
  in der App anlegen (Name, Zeitplan oder einmalig mit Datum und Uhrzeit),
  pausieren, fortsetzen und löschen — je Agent, über die verschlüsselte
  Verbindung.
- **Agenten-Zentrale:** Das Dashboard zeigt je Agent Status, Anzahl der
  geplanten Aufgaben und was er verwalten kann — ein Tipp auf die Kachel
  öffnet dessen Aufgaben.
- **Agent hinzufügen:** Über einen Einladungscode (Einstellungen, nur
  Admin-Gerät) verbindest du neue Agenten — selbst eingerichtet, von einem
  bestehenden Agenten oder durch ein anderes System.
- **Kopplungs-Anfragen** zeigen sich jetzt zusätzlich als Banner am
  Bildschirmrand — auch wenn die Geräte-Karte zugeklappt ist.
- **Geräte-Karte:** Hinweis, dass inaktive Geräte nach 14 Tagen automatisch
  entfernt werden.
### Behoben
- **Mehrere Geräte:** Jedes Gerät nutzt jetzt seine eigene Kennung —
  Zweitgeräte überschreiben sich nicht mehr gegenseitig die Schlüssel, und
  die verschlüsselte Verbindung wird je Gerät statt global bewertet.
- Entfernte Altlasten (toter Zertifikats-Dialog, veraltete Hinweis-Texte,
  hartcodierte deutsche Benachrichtigungen — jetzt mehrsprachig).

## [0.17.3] - 2026-11-24
### Behoben
- **Chat am Tablet (zwei Spalten):** Die Agentenliste links war unsichtbar —
  sie lag hinter der Kopfzeile. Sie erscheint jetzt korrekt darunter, ein
  Tippen wählt einen Agenten aus und markiert ihn mit einem Balken; verlässt
  du das Gespräch („Zurück”), wandert die Markierung mit bzw. wird sauber
  geräumt, statt am alten Agenten hängen zu bleiben.

## [0.17.2] - 2026-11-24
### Hinzugefügt
- **Tablet-Unterstützung:** Die App passt sich jetzt der Bildschirmgröße an —
  auf Tablets übernimmt eine Seitenleiste statt der unteren Leiste, im breiten
  Tablet-Format erscheint der Chat als zwei Spalten (Agentenliste links,
  Gespräch rechts, bündig zentriert) und das Dashboard zeigt seine Karten
  zweispaltig. Auf dem Handy im Hochformat ändert sich nichts — im Querformat
  nutzt die Navigation dieselbe Seitenleiste wie das Tablet.
### Behoben
- Drehen des Gerätes verliert nicht mehr offene Dialoge, den Suchzustand oder
  eine laufende Sprachnachricht — die Aufnahme läuft beim Drehen weiter.
  Gesendete-und-gescheiterte Nachrichten behalten ihren Fehlerhinweis.

## [0.17.1] - 2026-11-23
### Hinzugefügt
- **Geplante Aufgaben (5. Tab):** Unten rechts ergänzt ein weiterer Reiter-
  knob die bestehenden Bereiche — die Cronjobs deiner Agenten, nach Agent
  gruppiert, mit nächster Laufzeit. Ein Cronjob lässt sich aus der App
  heraus löschen (mit Bestätigungs-Dialog); nach einer Löschung erscheint
  sofort der frische Stand. Der Dashboard-Block „Geplante Aufgaben" ist
  dafür in den Tab gewandert, damit alles an einem Platz liegt.
- **Fertig-Meldungen:** Wenn ein Sub-Agent oder ein Haupt-Agent seine
  Arbeit beendet, bekommst du eine Benachrichtigung. Beide Fälle lassen
  sich in den Einstellungen je einzeln an- und abschalten (Standard: an).
  Die Meldungen laufen über den bestehenden Benachrichtigungskanal — kein
  weiterer Dienst.
- **Ausgehender Anruf:** Im Chat-Kopf deiner Agenten gibt es einen
  Telefon-Knopf. Ein Tap ruft den Agenten an (Vollbild „Klingeln …" mit
  Abbrechen); nimmt er an, läuft das Gespräch wie beim eingehenden Anruf —
  im selben Sprach-Verfahren, kein weiteres Protokoll. Verlorene Anrufe
  landen im Verlauf.
- **Geräte benennen (Server-seitig):** In den Einstellungen › Geräte kannst
  du jedem Gerät einen Namen geben (z. B. „Xiaomi", „Tablet"). Der Name
  wird serverseitig gespeichert und erscheint auf allen deinen Geräten
  identisch — du weißt also beim Entfernen sicher, welches Gerät du gerade
  ausklinkst. Der Einrichtungs-Assistent fragt den Namen des Geräts direkt
  mit ab (überspringbar). Ungenannte Geräte bleiben bei „Gerät 1/2/3".
- **Verbindungstimer ehrlich:** Im Verbindungsbanner siehst du den
  nächsten Verbindungsversuch als Countdown („Neuer Versuch in 42 s" bzw.
  „5 Min 10 s" beim Sperren-Fenster) — der Zähler verschwindet, sobald der
  Versuch läuft, statt auf „0 s" stehen zu bleiben. Der Hinweis in der
  Detail-Zeile lässt sich pro Stau einmal wegklicken.
- **Stausymbol-Farbcode:** Die Statuspunkte an den Agenten zeigen den
  Verbindungszustand nur als Punkt (Farbe), nicht mehr als Text-Doppelung.

### Behoben
- Einstellungen: Ein Doppel-Eintrag „Standard-Assistent" ist entfernt —
  der Menü-Bereich zeigt je Thema EIN Element.
- Geräte-Liste: Der Bestätigungs-Dialog beim Entfernen nannte das falsche
  Gerät, wenn mehrere Geräte noch keinen Namen haben.
- Benachrichtigungen: Agent-Fertig-Meldungen konnten Chat-Meldungen mit
  gleicher ID still ersetzen (Kollision im Millisekunden-Fenster) —
  eigene ID-Räume vermeiden das.
- Kopplungs-Verwaltung: Umbenennen durch ein Nicht-Verwaltungs-Gerät,
  während das Verwaltungs-Gerät offline ist, wird abgelehnt (statt still
  ausgeführt). Zusätzlich begrenzt ein Schwellwert-Fenster die Rate dieser
  Umbenenn-Anfragen.
- Verbindung: Der Countdown des Verbindungsbanners bleibt nicht mehr auf
  „Neuer Versuch in 0 s" stehen, wenn das System den Wecker verzögert
  einschaltet (Doze).
- Cron-Liste: Der Lösch-Knopf hat jetzt ein standardkonformes Touch-Ziel
  (48 dp statt 28 dp).

### Server (im selben Release-Paket)
- Kopplungs-Enrollment über den Proxy lieferte einen Henne-Ei-403 — der
  Pfad ist für /mtls/enroll[/challenge] freigeschaltet.
- Geräte-Registry: Die 14-Tage-Frist prüft jetzt auch im laufenden Betrieb
  (stündlicher Sweep), nicht nur beim Start; frisch gekoppelte Geräte
  erschöpfen das Geräte-Limit nicht mehr dauerhaft.
- Ausgehender Anruf: Der Server-Frame (call_request) ist in der Freigabe-
  Liste des Adapters und die Antwort läuft über denselben Anruf-Auftrag —
  inklusive Verwurf überalterter Anträge (> Ring-Fenster).
- Fertig-Meldungen: Der Server reicht jetzt `cron.remove_ok`,
  `agent.subagent_done` und `agent.idle_transition` an die App durch
  (vorher still verworfen) — klar und verschlüsselt, mit eigenem
  Zähler-Fenster und Downgrade-Schutz.

## [0.17.0] - 2026-11-18
### Hinzugefügt
- **Anrufe:** Du kannst vom Agenten angerufen werden — Vollbild-Klingeln mit Annehmen
  und Ablehnen (auch aus dem gesperrten Zustand), im Anruf sprichst du durch Drücken
  (Halten = sprechen, Loslassen = senden) und unterbrichst laufende Antworten mit einem
  Tastendruck (Barge-in). Freihändiges Sprechen im Anruf: die Erkennung
  läuft auf dem Gerät (der Ton verlässt das Handy nicht) und sendet den erkannten Text
  automatisch; ist sie nicht verfügbar, schaltet die App sichtbar auf das bekannte
  Drücken-Verfahren zurück.
- **Anruf-Verlauf im Chat:** Beendete Anrufe erscheinen als Anruf-Zeile mit
  Dauer (z. B. „Anruf (2:41)“), verpasste Anrufe als eigene Zeile — ein Tipp bereitet die
  Rückruf-Nachricht an den Agenten vor. Ein Schalter in den Einstellungen legt fest,
  ob der Anruf-Verlauf aufgezeichnet wird; „Chat löschen“ räumt Anruf-Zeilen mit auf.
- **Multi-Gerät:** Eine Kopplung — mehrere Geräte. Du kannst weitere Geräte (z. B. ein
  zweites Handy oder ein Tablet) an derselben Brücke koppeln (Freigabe durch das
  Verwaltungs-Gerät im Zeitfenster oder Kopplungs-Passwort); eigene Nachrichten erscheinen
  live auf allen Geräten, ein neu gekoppeltes Gerät holt den bisherigen Verlauf nach,
  und in den Einstellungen siehst du alle Geräte mit „zuletzt verbunden“ — jedes Gerät
  lässt sich jederzeit zweistufig entfernen (deaktivieren, dann lösen).
- **Assistent-Paket:** Die App kann die Standard-Assistenten-Rolle am Gerät übernehmen
  (Einrichtung mit sichtbarem Ergebnis/Fehlerweg). Danach: Fragen per Taste halten und
  Stimme, Live-Follow-ups ohne Aufwärmen, Bildschirm-Fragen mit je-Sitzungs-Zustimmung,
  Erinnerungen und Termine per Stimme, Apps öffnen per Stimme, Telefonate steuern.
  Overlay-Bubble über allen Apps (verschiebbar, Tap öffnet die Stimme)
  mit klar geführtem Berechtigungs-Weg, und „Seite fragen“-Mini-Panel — aus dem
  Standard-Assistenten-Wisch oder per „Teilen“ aus dem Browser (Frage/Zusammenfassen/
  Link in den Chat senden).
- **Globale Volltextsuche:** Suche reicht jetzt über alle Agenten-Verläufe hinaus —
  unter den Treffern der offenen Unterhaltung erscheinen Treffer aus den
  anderen Verläufen (mit Ausschnitt des Treffers, Gruppierung je Agent, Sprung zur
  Nachricht mit Hervorhebung). Der Suchindex wird im Hintergrund aufgebaut; vorher
  zeigt die App ehrlich an, dass er noch bereitgestellt wird.
- **Geplante Agenten-Aufgaben (Cron-Karte):** Im Dashboard erscheint eine Karte mit den
  geplanten Aufgaben des Agenten — du siehst, was er sich vorgenommen hat, ohne die
  Konfiguration zu öffnen.
- **Verschlüsselter Verlauf am Gerät:** Der Chat-Verlauf wird verschlüsselt auf dem
  Gerät verwahrt und gegen Veränderung gesiegelt; Anruf-Einträge sind einbezogen („Siegel“); Änderungen an der Siegel-
  Grundlage brechen die Verwahrung sichtbar ab statt still zu verschicken. Migration
  bestehender Verläufe läuft beim ersten Start des Updates ab; scheitert sie,
  bleibt der Verlauf unangetastet (kein Verlust, keine beschädigten Schreib-
  vorgänge).
- **AGB-Erststart-Akzeptanz:** Beim ersten Start werden AGB und Datenschutz
  einmal akzeptiert („Lesen“-Ausklapp je Text); Ablehnen beendet die App sauber mit
  Hinweis. Bestehende Installationen werden nach dem Update nicht erneut gefragt.
- **Open-Source-Lizenzen:** Im Info-Sheet gibt es die Übersicht der
  verwendeten Open-Source-Bibliotheken (Name — Version — Lizenz) plus Verweis auf die
  vollständige Lizenzdatei.

### Behoben
- **Modell-Auswahl generisch:** Das freie Modell-Eingabefeld ist vollständig entfernt —
  im Modell- und Denken-Blatt wird nur aus der Liste gewählt, die der verbundene Server
  liefert („Auto“ zeigt das aktive Modell, Denken-Stufe übernimmt die Server-Vorgabe).
- **Denken-Schalter „Aus“:** „Aus“ bleibt zuverlässig aus (kein Zurückspringen beim
  Neuaufbau der Oberfläche oder App-Start).
- **Häkchen-Persistenz:** Status-Häkchen (Gesendet/Zugestellt/Gelesen) fallen nicht mehr
  auf ältere Stufen zurück — auch nicht nach App-Neustart oder wenn viele Medien hinter-
  einander übertragen werden.
- **Agenten-Status über alle Kategorien hinweg:** Die Agenten-Arbeit auf der Startseite
  ist auch sichtbar, wenn sie über einen anderen verbundenen Kanal (z. B. Discord oder
  Telegram) läuft; Beschäftigt-Slots verschwinden pünktlich am Ende und werden beim
  Verbinden nachgereicht statt als Leichen stehenzubleiben.
- **Anruf-Races:** Doppel-Einladungen werden verworfen statt Anruf-Tod, Klingel-/Beendig-
  ungs-Rennen sind abgedeckt, Restzustände einer alten Klingeln räumen sich beim Verbinden
  selbst auf, Anruf-Übersicht bleibt ohne hängende Einladungen.
- **Verlaufräumung/Verlaufladen:** „Chat löschen“ entfernt Verlauf samt Verwahrungs-Siegel
  sauber mit; das Laden in einem Rutsch schützt vor Verlust-Rennen; die Siegelablage wird
  ohne Zwischenschreibungen ersetzt (keine beschädigten Siegel bei Unterversorgung).
- **Oberfläche (Design-Säuberung):** Kontraste auf Lesbarkeit gebracht, Abstände zwischen
  Text und rechtsbündigen Elementen gefestigt (kein Ankleben), Touch-Ziele ≥ 48 dp,
  einheitliche Textdichte ohne gemischte Beschreibungszeilen; neue Texte vollständig
  Deutsch und Englisch.

- **Zustands-Wiederherstellung des Chats:** Ein Zählfehler im Zustands-Speicher (bestand
  über mehrere Versionen) zerschlug den Verlauf bei der Wiederherstellung nach
  App-Neustart oder Gerätedrehung — behoben; alte gespeicherte Zustände bleiben lesbar.
- **Freihändiges Sprechen, robuster:** Aussetzer der Geräte-Erkennung werden begrenzt
  und mit sichtbarem Hinweis auf das Drücken-Verfahren zurückgeleitet (keine Endlos-
  Wiederholung); ein diktierter Satz geht nicht mehr still verloren und wird nicht mehr
  doppelt gesendet; zwei Anrufe in derselben Minute zählen nicht mehr als derselbe Anruf.

### Serverseite
- **Multi-Gerät im Backend:** Geräte-Verwaltung am Relay und im Agent-Adapter —
  Freigabe-Tor für neue Geräte mit Zeitfenster, Verlaufsnachholung für frisch gekoppelte
  Geräte, zweistufiges Entfernen; jedes Gerät führt seine eigene verschlüsselte Sitzung
  (kein Gruppen-Schlüssel — der Verlust eines Geräts betrifft nur seine Sitzung).
- **Härtung des Relay-Betriebs:** systemd-Vorlage mit eigenem Dienst-Benutzer
  (deployment/, mit Migrations- und Rollback-Hinweisen), Umgebungsregeln nur verschärfbar,
  Logs schwärzen auch verschachtelte Codes/Tokens, Debug-Ausgabe standardmäßig aus,
  `--version`-Flag zum Ablesen der Backend-Stufe.
- **Relay-Identität:** Jede Relay-Instanz ist mit eigenem Schlüssel
  (Ed25519) eindeutig identifizierbar; Agenten prüfen die Identität beim Verbinden,
  merken sich den Fingerabdruck und warnen bei Wechsel (kein stiller Identitätswechsel).
- **Signierte Installation:** Das Einrichtungsskript (`install-relay.sh`)
  prüft beim Installieren die Paket-Checksumme gegen die im GitHub-Release notierte
  und bricht bei Abweichung ab (erwartet/geliefert als Hash-Vergleich).
- versionCode 54 (github) / 117 (play).

## [0.16.8] - 2026-11-17
### Hinzugefügt
- **Agent-Betreiber-Status (W19):** die Startseite zeigt die Sub-Agenten-Slots live an (grün = frei, Ember = beschäftigt) und was der Agent gerade macht (Label „Sucht im Web“ u. a.) — W19-Verband `agent.status` (working/label/sub/end) am RelayClient (`agentStatus`/`agentSubs`-Flows, Regeln in AgentStatusRules, Aufräumen an allen 3 Verbindungspunkten). versionCode 51 (github) / 114 (play).
### Behoben
- nichts App-seitig außer dem W19-Verband.
## [0.16.7] - 2026-11-17
### Behoben
- **Composer-Chip als Kürzel A/M (W18, Schrumpf-Fix):** der Composer-Chip zeigt statt „Auto · <Modell>“ nur mehr die Kürzel **A** (kein explizites Modell = Agenten-/Config-Standard) und **M** (Manuel = abweichendes Modell für diesen Chat) — die lange Chip-Beschriftung blähte die Composer-Pill auf und ließ die Nachrichten-Zeile schrumpfen; Modell-Details bleiben im Modell- und Denken-Sheet, TalkBack-Klartext in composer_chip_*_a11y (je values/values-de/values-en). versionCode 50 (github) / 113 (play).
- **Home-Kanal-Hinweis wird nie im Chat zugestellt (W18):** der Adapter wirft den /sethome-Hinweis immer still weg (statt nur im 24-h-Fenster) — der Hinweis landet nie im Agent-Chat (test_fw2_b3_home_notice.py grün).
## [0.16.5] - 2026-11-17
### Behoben
- **mTLS-Client-Kette (W17-Fix, Handy-Befund „Enroll-Loop, keine Verbindung"):** der
  TLS-Client legte als Client-Kette das Android-auto-erzeugte SELF-SIGNED-Dummy-Zert
  („CN=flipsibridge-device") aus dem Keystore-Entry vor statt das vom Server
  ausgestellte Device-Zertifikat — nginx verifiziert die Kette gegen die Device-CA
  → FAILED → 421 → die App lief in einen Endlos-Enroll-Renewal-Loop, WS nie verbunden.
  Neu: die Kette = das Server-Zertifikat aus dem Enrollment (Prefs), KeyStore-Kette
  nur als Fallback. Beweisbasis: serverseitige Kette (421/Gate/Bearer) 100% grün
  (DIAG-Log am NPM), Handy-Enrollments 4× 200, WS-Handshake stumm.
### Rückholung (17.11.26)
- **mTLS-Verband wieder vollständig in der App** (MtlsSupport/MtlsCard/Enrollment):
  war in der 17.11-Nacht-Sitzung komplett entfernt; Abwägung: Zertifikat bleibt
  zusätzlich aktiv (Device-Faktor), optionales Kopplungs-Passwort (Einstellungen ›
  Server) bleibt.
### Serverseite
- Play vc110 Notes-Fix (0.16.1-Text unter 0.16.4 — korigiert), mTLS-Stufe-2-Verband
  (nginx + Relay-Gate) wieder scharf, Device-CA frisch ausgestellt.

## [0.16.4] - 2026-11-16
### Behoben
- **Verbindungsversuch-Verband (P42):** WS-Verbindungsversuche laufen ENDLOS weiter
  (MAX_RETRIES 5 → Int.MAX_VALUE). Der 5er-Cap ließ die App nach 5 Fehlversuchen
  still stehen (kein weiterer Versuch, keine Netzwechsel-Reaktion) — Frozen-Verband
  am 16.11.2026: Handy verließ den Verbindungs-Wach-Zustand nie wieder von selbst.
- Rate-Disziplin übernimmt weiterhin der Doze-budgetierte Alarm-Takt (5 min), nicht
  ein Retry-Cap. Backoff unangetastet (1 s basis, 30 s Deckel).
### Serverseite (schon live)
- Stufe 2: NPM verlangt Client-Zert für Handy-Routen (421 sonst), /mtls/* frei;
  Relay-Gate prüft Zert-CN gegen Device-Registry (403 fail-closed); Agenten-Direct
  unverändert ohne Zert.

## [0.16.3] - 2026-11-16 — Welle 16.3: mTLS-Enrollment (Zertifikat-Button tut etwas)
- **Enrollment-Verband (P41: „Zertifikat wählen passiert nichts“):** der Button in Einstellungen › Server › mTLS-Zertifikat war der KeyChain-Systemdialog — ohne installiertes Zertifikat schließt er still (Sackgasse: das Zertifikat MUSS erst der Server ausstellen). NEU: „Zertifikat anfordern“ startet das komplette Enrollment — AndroidKeystore-EC-Leg (non-exportable) + hand-kodierter PKCS#10-CSR (kein BouncyCastle-Dep) + Proof-of-Possession (Server-Nonce signiert) → `/mtls/enroll` am Relay stellt das Gerät-Zertifikat aus (Device-CA), Statuszeile + Snack zeigen CN; KeyChain bleibt nur als Fallback-Pfad in decorate(). versionCode 46 (github) / 109 (play).
- **Relay (mtls_enroll.py):** POST /mtls/enroll/challenge + /mtls/enroll (code-gated, Rate-Chain, Struktur-Boundaries) — Device-CA (RSA 3072, /etc/flipsibridge/tls/device-ca.*), CSR-Signierung (400 Tage), Registry `mtls_devices.json` + je-Gerät PEM. **CODE-EINMALIGKEIT („nur einmal die Codes gehen“):** je Kopplungs-Epoch enrollt der Code GENAU EIN NEUGERÄT (403 code-used sonst); Code-Neuerzeugung (= set_pairing_code) erhöht die Epoch und öffnet das Fenster neu; Wiederaufnahme derselben device_id (renewal) bleibt ohne Code möglich.
- **PoP-Härte (diagnostiziert via E2E-Diagnose):** openssl toleriert überlange Signatur-Dateien STILL (führende N Bytes gelten, Rest ignoriert — b64-Reste —„xx“-Mutation verifizierte trotzdem) → Signaturlänge aus dem Public Key: RSA exakt N/8, EC band [16, (N/8+4)·2]. Nonces one-shot (5-min TTL). Fail-soft: CA-Init-Fehler = Routen OFF + Log, der Rest des Relays läuft weiter.
- Enforcement am WS-Socket bewusst NICHT in dieser Stufe (Stufe 2 = nginx/NPM ssl_verify_client-Verband, folgt als eigene Welle); die App legt das Zertifikat ab dem nächsten Handshake vor (decorate-Branch app-Keystore VOR KeyChain).
## [0.16.2] - 2026-11-16 — Welle 16.2: Server-Taste folgt dem Live-Zustand (Verbinden↔Trennen)
- **Verbindungs-Taste (P39):** die Taste in Einstellungen › Server FOLGT dem Live-Zustand — verbunden = „Trennen" (rot, RelayClient.disconnect + Snack), getrennt = „Verbinden" (Ember, connectViaProfile aufs AKTIVE Profil + „Verbinde …"-Snack), während CONNECTING/RECONNECTING = „Verbinde …" (disabled). Vorher: dauerhaft „Trennen" — nach dem Trennen eine Sackgasse ohne sichtbare Verbinden-Fläche (der Aktiv-Chip zeigte nur das Profil-Flag). Neue Strings: conn_connecting/snack_connecting/server_no_active_snack (je values/values-de/values-en).
- versionCode 45 (github) / 108 (play).

## [0.16.1] - 2026-11-16 — Welle 16.1: 2 UI-Punkte (FP ins ⋮-Menü + Composer-Mitte)
- **FP-Abgleich im ⋮-Menü (P36):** der FP-Button wanderte aus der Chat-Kopf-Zeile ins ⋮-Menü (Eintrag „FP-Abgleich" vor „Chat löschen", derselbe onFpCheck-Callback — Funktion unverändert). KEIN-DOPPEL-Grundsatz: der Kopf-Button ist ENTFERNT, nicht doppelt gezeigt; damit entfällt auch die P32-Clip-Quelle (FP-Feld am schmalen Kopf) samt der headerNarrow-Kompakt-Variante. Kopf-Zeile = Magnifier (+play: Herz) + ⋮. Neuer string chat_menu_fp (values/values-de/values-en = „FP-Abgleich"/„FP check").
- **Composer-Text mittig (P37):** Hinweis „Nachricht schreiben" UND getippter Text sitzen vertikal MITTIG in der Composer-Pill („weiter oben"-Befund — vorher top-bündig im 48dp-Feld). Fix: decorationBox-Box in ChatWidgets.kt mit heightIn(min 48dp) + contentAlignment CenterStart — 1 Zeile = mittig, mehr Zeilen füllt (maxLines 4 unverändert).
- **Rechtliches-Vollansicht ENTFERNT (P38):** der „Über die App: Rechtliches (Vollansicht)“-Sprung + Route 3 (LegalScreenPlaceholder) sind KOMPLETT raus (Anforderung: „wirklich komplett weg“) — hinter der Tiefe standen ausschließlich die Phase-8-Platzhalterkarten („Inhalte folgen in Phase 8“), während die echten Rechtstexte bereits im ℹ️-About-Sheet lagen. Verband mitgeräumt: requestLegalDepth/consumeLegalDepth, LaunchedEffect-Routensetzer, dead ChatScreenPlaceholder (+ScreenPlaceholderBody/LegalSectionCard), 3 obsolete Strings je Locale; alte saved Route 3 fällt auf Dashboard zurück. versionCode-Zähler unverändert dafür (liegt im selben 0.16.1-Bau).
- versionCode 44 (github) / 107 (play).

## [0.16.0] - 2026-11-16 — Welle 16: WhatsApp-Chat-Redesign + Update-Flow-Neubau + Session-Live
- **Chat wie WhatsApp (Referenz-Screens):** Nav-Bar verschwindet beim Öffnen einer Conversation (P15), Bubbles wachsen mit dem Text ohne Label-Spalte (KI-Badge kompakt in der Meta-Fußzeile, P16), Suchmodus blendet Composer+Buttons aus und kehrt VOLL zurück mit bewahrtem Entwurf (P28), WA-Composer als Pill mit rundem inline Send/Mikro-Knopf — Feld leer = Halten-Dialog mit Aufnahme, Loslassen = senden, Wischen-links = Abbrechen, <0,5 s = verwerfen (P30/P30b/P28), Tab-Zeile weg — Mediathek + Agenten-Profil im ⋮-Menü (Agent ansehen/Chatdesign/Mediathek/Chat löschen, E2E-Keys bleiben) (P32), dynamische Profilbilder je Agent (Falloptik: deterministische Farbe + Initial) (P32).
- **Sprachnachrichten WA-Bubble (P31):** Play/Pause-Kreis, echte Wellenform (1× dekodiert, File+RAM-Cache), Fortschritt über der Welle, verstrichene Sekunden, gleiche Bubble für Agent-TTS-Antworten (On-Device-Synthese in app-privatem Cache) und gesendete Audios.
- **Dashboard = Agenten-live (P13/P14/P17):** Online-Punkt-Abstand fix, Verbindung/Master/Fußzeile-Kacheln entfernt, Session-Quelle (Discord/Telegram/Bridge/Cron), Modell + laufende Sub-Agenten je Agent live via session_meta-Frames (Adapter + Relay-Passthrough klar+E2E, Struktur-Guard 2 KiB/8 Subs).
- **Einstellungen (P11/P12/P19/P20/P27/P21):** Interaction-Toggle-Refresh-Kette (Disclosure-Host Screen-level), Standard-Assistent: Ergebnis-Handler + Rolle-Fallback-Führer, Settings-Untertitel raus (clean), Rechtliches+Version nur hinter ℹ️ (About-Sheet, Route 3 als Tiefe), Server-Liste kompakt (Status-Dot/Domain/Aktiv-Chip/Stift/✗), extra URL-Feld weg (P21-Nachtrag).
- **Update-Flow (P26):** In-App-Install KOMPLETT raus (UpdateInstaller, /apk_install, Capability, REQUEST_INSTALL_PACKAGES, FileProvider), Update-Check fail-closed (Relay HEAD /apk/latest || GitHub latest) + Push-Notification-Channel „Updates" mit Tap je Edition (Play | GitHub), Rate 1/Tag + App-Start, Dedupe je Version.
- **Security (P25):** AppLock PBKDF2 ≥120 k (130 k) + transparente Legacy-Hash-Migration + JVM-Test.
- **Relay (P14-Verband):** chat.session_meta/.enc-Allowlist + Dispatch, Handler mit Rate/Owner/Replay-Guards, session_meta-Durchreich an chat.activity (klar+enc).
- versionCode 43 (github) / 106 (play).

## [0.15.3] - 2026-11-12 — Welle 15: Fixliste (Chat/Dashboard/Einstellungen/I18n)
- **W15b: 5 Re-Drill-Fixes (Funktionstest-Beweise qa_w15):** (A) MessageBubble-Meta-Zeile (Zeit+Häkchen) stand OBEN über der ersten Textzeile — M3-Surface stapelt ihren Inhalt in einer Box; Column-Wrap setzt Zitat/Text/Medien oben und die Metareihe ALS LETZTES Element unten-rechts (WhatsApp-Stil, ChatWidgets.kt). (B) Agenten-Tab ohne Top-Bar-Titel — das Tab-Label trägt den Namen, kein Doppel-Titel (BridgeScreen.kt). (C) Berechtigungs-Accordion-Kacheln wie PermissionRow: kurzer Status-Badge (aktiv/fehlt) + kompakter „Öffnen“-Chip zentriert statt „Nicht erteilt“+„Systemeinstellungen“, die die Zeile sprengten (PermissionsSettingsCard.kt). (D) SettingsNav speichert Sektions-Labels als @StringRes — der Top-Bar-Pfad löst live auf; „Einstellungen › Language“ in deutscher UI war ein eingefrorener Titel nach Sprachwechsel (common_sprache neu, Parität 432 Keys je Datei). (E) Dashboard-Verbindungskarte schlank: Server-Adresse-Feld + Verbinden-Button raus (bleiben in der Server-Gruppe der Einstellungen), nur Status + Trennen (DashboardScreen.kt).
- **Fix 1/2/10 (Chat):** Bubbles haben unten keine abgerüttelten Ecken mehr (14 dp rund voll), Uhrzeit+Status klein unten rechts IN der Bubble ohne Debug-Text («Übertragen (1 Chunks)» weg), Stufen ✓ versendet / ✓✓ zugestellt / ✓✓ GRÜN gelesen (SignalGreen; chat.read-Wiring lag vor).
- **Fix 3/4 (Agenten-Screen):** «Agenten»-Schriftzug + Untertitel komplett entfernt, Liste beginnt direkt mit Such-Kapsel, Rhythmus-Abstand konsistent.
- **Fix 5 (I18n):** Root-Cause war Hardeutsch in der UI (stringResource umgangen) — 419+85 Keys je 3 Dateien, Sprachwechsel wirkt jetzt live (AppLanguageGate wrap/System-Restore).
- **Fix 6/7 (Einstellungen):** Kachel-Layout global: Text volle Breite oben, Status darunter zentriert, Öffnen-Button daneben; Top-Bar zeigt Pfad («Einstellungen › Server»), Doppel-Titel weg.
- **Fix 8/9 (Dashboard):** Neue Startseite = Status-Dashboard (Agenten-Live-Slots, was sie tun, Verbindungskarte integriert; NavigationBar Dashboard|Chat|Agenten|E2E), «Setup überspringen» landet jetzt auf dem Dashboard.
- versionCode 42 (github) / 105 (play).

## [0.15.2] - 2026-11-12 — Welle 13: Kopplungs-Drill-Fixes (Send/Prekey)
- **Fix (Chat UI):** Conversation-Kopf lief auf schmalen Flächen (sw262dp = Drill-Emulator) über: das FP-Abgleich-Label ohne maxLines brach auf 11 Zeilen um (Button 184×506 dp), verdrängte die Composer-Pill unter ihre `.clip()`-Kante und schlug die Meta-Reihe (Anhang/Modell-Chip/Mikrofon/Send-Kreis) pixellos weg. Fix: `maxLines=1` + `softWrap=false` am FP-Label (ChatScreen.kt); breite Screens unberührt.
- **Fix (Onboarding):** `key_get` ohne Agent-Prekey (`spk_pub=""`) erzeugt nie mehr stillen Warte-Tod: klare Fehlermeldung „Relay fehlt Prekey für <agentId>" + begrenzter automatischer Retry (3 Versuche, Backoff, Reset bei geliefertem Prekey) mit Handlungshinweis (AgentPrekeyPolicy + RelayClient + Onboarding-Fehlercard, strings DE/EN).
- **Tests:** AgentPrekeyPolicyTest (5 Eckfälle); PC-Verifizierung `testPlayDebugUnitTest` BUILD SUCCESSFUL in 51 s.
- **Drill-Nachweise Welle 13:** E2E-Kopplung über 3 Sessions (fb-c36ad/fb-cfd64/fb-63cac) inkl. Re-Handshake nach App-Neustart, POST /chat 200, POST /media 200 (Voice-Note m4a 95 KB), App-Chips ✓✓ Gelesen / ✓ Übertragen.

## [0.15.0] - 2026-11-11 — PH-7 Play-Edition (Play-Store-Route) + W4-Lane C im Bau
- **Play-Edition (PH-7/PS-G-1, Dual-Edition DE-T2..T5):** play-Flavor `at.flipsi.bridge.play` mit Manifest-Overlay (`app/src/play/`: REQUEST_INSTALL_PACKAGES, QUERY_ALL_PACKAGES, SEND_SMS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Termux-RUN_COMMAND via tools:node="remove") + `<queries>`-Launcher-Intent als Package-Visibility-Ersatz für QUERY_ALL_PACKAGES (PS-A-10; /apps-Codepfad bleibt EIN Weg, queryIntentActivities ist bereits Launcher-basiert). Feature-Branches via BuildConfig: sendSms = SMS-Intent-Entwurf (PS-A-14), /apk_install = 403-Route mit Klarfehler (PS-A-11, doppelt verankert: Gate filtert selfupdate aus userFacing), Akku-Optimierung = Settings-Liste statt REQUEST-Dialog (PS-A-18), Termux-Revival-Watchdog play-no-op + Card-Hide (PS-G-7-Default). Play targetSdk 36 (hartes Play-Gate seit 31.08.2026 — Verlängerung 01.11.2026 verpasst), Version-Tracks: play versionCode 100+ („0.15.0-play"), github versionCode 38 („0.15.0", volle Funktion). GitHub-Edition unverändert voll.
- **W4-Lane C (Mediathek-Tab + In-App-Sprachwahl) im Bau enthalten** — Details im [Unreleased]-Block; Emulator-Beweise kommen mit der Play-Bau-Welle (qa/drill-w4-lanec-sprache-mediathek.md).
- versionCode 38 (github) / 100 (play).
- **Play-Jaeger-Fix-Welle** — abgeschlossen (siehe Fix-Zeile oben); **Welle-4-Rücklick-Fixe:** FGS-Typ edition-aware in promoteForeground (V-J4-S1: DATA_SYNC in play würde den 5-s-Crash werfen), play-Service-Overlay in `<application>` gepackt (Welle-3-AAPT-Beweis), Boot-Notification mit POST_NOTIFICATIONS-Check (V-J4-F02), play-SMS-Toggle in „Gerät bedienen" (N1), INVASIVE_CAPS edition-aware + Dialog-Text (N2), 16/15-KDoc neutral (N3).
- **Supporter-IAP in der APK (11.11.2026):** einmaliger „Unterstützer"-Badge 2,99 € via Google-Play-Billing (`playImplementation billing-ktx:7.1.1` NUR play; Produkt-ID `supporter`; sperrt NICHTS = perk-Rule) — play-src/play SupporterCard (BillingClient 7.1-Callback-API, Acknowledge+Restore+Pending) + github-Stub (gleiche Signatur, src/github/); Karte sitzt im „App"-Accordion hinter der SelfUpdateCard; Produkt-Konfiguration in der Konsole = Anbietersitzung (bis dahin zeigt die Karte ehrlich „nicht verfügbar").
- **Policy-Erfüllung (PS-G-2, Anforderung: „Die App muss das erfüllen!"):** DisclosureFlow (policy/DisclosureFlow.kt) — prominente Offenlegung + ausdrückliche Einwilligung mit Consent-Zeitstempel je sensibler Freigabe (a11y, notification-listener, mikrofon, health, all-files, overlay, sensible Capability-Toggles), play-gefiltert (GitHub-UX unverändert), Dialoge mit „Nicht jetzt" = kein Zwang; Compliance-Matrix (20 Klauseln aus den amtlichen Policy-Artikeln 9888379+9888170, je Zeile Zustand+Beweis+REST).

## [0.14.1] - 2026-11-03 — Medien/Datei-Versand E2E + Fix-Welle 2 (B1–B8) + TLS-nativ am Relay
- **Medien-/Datei-Versand E2E (MEDIA-T1):** Anhang-Sheet im Agent-Chat (Foto/Video, Datei, Sprachnachricht) mit E2E-verschlüsseltem Chunk-Upload (POST /media, Double-Ratchet-verschlüsselt, Chunk-Ack je Block), Medien-Bubbles mit Übertragungs-Chip, empfangsseitige Medien-Verweise im Verlauf; Agent-Adapter assembliert und speichert md5-paritätisch. Live bewiesen im echt gepaarten Chat: Foto 10 561 B, 4-stufige MD5-Parität PC→Gerät→TLS/E2E→Agent.
- **Fix-Welle 2 (B1–B8, Her-Test 40er + Redrills):** AttachSheet-ID-Vorzeichen-Bug (B7, P2) — Live-Agent-IDs sind hashCode-Werte und können negativ sein; Vertrag vorzeichenfest (AttachSheetRules.isConversationOpen), damit öffnet das Anhang-Sheet auch im Live-Chat und der System-BackHandler greift; Chat-Status-Chip-/Takeover-/Spool-Fixes (B1–B8) inkl. cross-session-takeover-Session-Reset-Heilung; AppLock-Autolock-Test-Semantik (J7) aktualisiert; _bridge_url-Fallback korrigiert (RF-01).
- **TLS-nativ am Relay (W3-01):** ANDROID_RELAY_TLS-Schalter — natives TLS auf dem 8766-Socket mit fail-closed RuntimeError statt stillem Plaintext-Fallback (D5b); Live-Relay läuft mit TLS + NPM-HTTPS-Front, tcpdump-Beweis 0 Klartext (nur TLS-Records) auf 8766 und 443.
- Backend-Suite 357 pytest grün (env-clean); JVM-Suite am Stand-PC 273 Tests / 2 bekannte Windows-Symlink-Baseline.
- versionCode 37.

## [0.14.0] - 02.11.2026 — PH-2X+PH-3: App-Sperre, OEM-Profile, E2E-Verschlüsselung
- **E2E-Verschlüsselung volles Double-Ratchet Handy↔Agent (PH-3):** X25519-X3DH-Handshake (fb-v1) mit Identity+PreKey je Seite, volles Double Ratchet nach Signal-Spec (KDF_RK/KDF_CK, Skipped-Keys-Cache, Replay-Fenster 100 + 10 min Freshness, Nonce-Zähler-Bindung, Rotation je Peer-Announcement und je Session), AES-256-GCM-Frames (chat.send.enc/chat.event.enc/chat.reply.enc) — der Relay sieht nur Chiffre. Fingerprint (8×4 Hex) in der App (Settings-Gruppe 5 „Verschlüsselung“) sichtbar, FP-Abgleich-Button im Chat-Header sendet den Abgleich verschlüsselt, der Agent gleicht automatisch ab und antwortet „Fingerprint-Abgleich: OK — Code ist gleich“ (bei Abweichung: Session sofort invalidiert, fail-closed). Keys der App versiegelt über AndroidKeyStore (IdentityStore), Klartext-Fallback existiert nicht.
- **App-Sperre PIN+Biometrie+Auto-Sperre (PH-2X X-1):** App-weite PIN (4–6 Ziffern, 2×-Bestätigung), Gate VOR dem Nav-Host (Chat-Inhalte werden im gesperrten Zustand nie gerendert), PIN-Hash mit Keystore-versiegeltem Salt, 5 Fehlversuche → progressiv 30/60/120 s Sperre (persistent über App-Restart), Biometrie-Unlock über Plattform-Keyguard (kein Crash ohne Hardware), Auto-Sperre 30 s/3 min/Nie. Alles lokal, kein Netz-Sync.
- **OEM-Kompatibilitätsprofile 8 Hersteller (PH-2X X-2):** DeviceIntentResolver mit Profilen (Xiaomi/HyperOS, Samsung/OneUI, OPPO+realme+OnePlus, VIVO/iQOO, Huawei+Honor, Motorola, Nothing, generisch-AOSP) — je Feature (Autostart/Akku/Overlay/Benachrichtigungen) Kandidaten-Intents, erster auflösbare gewinnt, ohne Treffer informative Leit-Zeile statt Crash; generischer AOSP-Fallback auf jedem Gerät.
- versionCode 36.

## [0.13.0] - 02.11.2026 — PH-2-Complete: Chat-Status-Chips + Presence-Live + Chat-Fehler-Banner
- **Chat-Status-Chips (F2 S1–S3, PH2-T10/T11/T12):** Je eigener Nachricht Status-Chip-Sequenz ✓ Gesendet → ✓✓ Zugestellt (Relay-Broker-Status je msg_id) → ✓✓ Gelesen in BLAU (chat.read vom Adapter, sofort nach Empfang); ⏱ Wartet bei Offline-Spool. Header zeigt bei Agent-Arbeit „arbeitet … (mm:ss)" mit Spinner (chat.activity working=true + started_at), die Antwort-Bubble zeigt „Antwort nach X s" (elapsed_ms im Reply-Envelope, Empfang→Antwort-Send inkl. Gateway — ehrlichkeits-Shim dokumentiert). Presence-Wechsel (Online-Punkt/„Offline") kommen live ohne App-Restart.
- **Chat-Fehler-Banner sichtbar (E2E-Fund e2e-d P1):** Sende-Fehler (z. B. HTTP 429 des Relays) waren im verbundenen Zustand unsichtbar (Route 2 hatte keine Statusanzeige). Eigenes dismissbares Fehler-Banner direkt unter dem Conversation-Kopf, Fehlertext aus dem RelayClient-Result; Demo-Fallback unberührt.
- **Presence-Live-Update ohne App-Restart (E2E-Fund e2e-c P2):** Relay pusht jetzt bei JEDEM Agent-Presence-Wechsel (Connect/Disconnect) einen relay.state-Frame an die offene Phone-WS — der Chat-Header zeigt Online/Offline live, ohne Neustart.
- **Jäger-Fixrunde (90dfe0b) integriert:** Presence-Guard + Spinner-Reset bei Agent-Disconnect, Owner-Check auf chat.read (kein Cross-Agent-Lese-Orakel), Read-/Chip-Semantik-Verfeinerungen.
- Basiseinträge 0.12.9 (Relay WS-Backbridge, ChatScreen-Realtime, KI-Badge) siehe unten.
- versionCode 35.

## [0.12.9] - 02.11.2026 — PH-2-Merge: Chat-Anbindung (Relay WS-Backbridge + ChatScreen-Realtime)
- 0.12.9 (versionCode 34): PH-2-Merge — Relay WS-Backbridge (chat.reply→Phone-Broker inkl. Spool/Retention), relay.state-Agenten-Liste beim Phone-Connect, ChatScreen-Realtime (sendChat → POST /chat, chatEvents → Conversation, Demo-Fallback), KI-Badge bleibt.
- versionCode 34.

## [0.12.8] - 02.11.2026 — Welle-3-Abnahme-Fund: FAILED/RECONNECTING-Banner sichtbar machen (PH1-W3)
- **Fix Banner-Verdeckung (P1, Drill-Beweis):** Der Statusbanner zeigte bei vorhandenem Code-Event (REGENERATED — persistiert in den Prefs seit dem allerersten Code-Anlegen) per early-return dauerhaft „Kopplungscode wurde neu erstellt" und verdeckte damit auf jedem Normalgerät die FAILED- und RECONNECTING-Banner. Der komplette PH1-T3/T4-Banner-Zustands-Text (z. B. „Server-Adresse ist ungültig — URL in den Einstellungen prüfen und erneut verbinden.") war im Normalbetrieb unsichtbar — Drill-Beweis: FAILED-State aktiv, ConnectionCard zeigte die rohe Ursache, der Banner aber den Code-Hinweis. Fix: FAILED + RECONNECTING in die Ausschlussliste des Code-Event-Banners (wie CONNECTED/PAIRING_BROKEN); verdeckt bleibt nur die Erstnutzer-Führung bei IDLE/CONNECTING.
- versionCode 33.

## [0.12.7] - 02.11.2026 — QA-Jagd-Fixes (PH1, Paranoiker P1+P2)
- **Fix ic_monitor_rec.xml Subpfad-Geometrie:** Der Welle-2-Bulk-Transform (Y+960) hatte das degenerierte Original-Subpfad-Ende `v-400 400` (fc8b693 — Linie ohne sichtbares Etwas) fälschlich zu `v-400l400` umgeschrieben = gefülltes Dreieck (160,600)(160,200)(560,200) im Monitor-Screen. Restauriert originalgetreu `v-400 400Z`; Beweis: Python-Pfad-Parser VORHER Dreiecksfläche 80000 + malformedes `l400` (eine statt x-y-Koordinate), NACHHER 0/degeneriert wie Original.
- **Doku-Kommentar build.gradle.kts präzisiert (Bump-Regel):** Skeleton-Phase kein Bump; spätere Wellen bumped regulär (30/0.12.5, 31/0.12.6) — Regel: versionCode-Bump + CHANGELOG im selben Commit je App-Änderung.
- versionCode 32.

## [0.12.6] - 02.11.2026 — Einstellungen-Accordion + Icons-Fix + Fehler-Texte (PH1-Welle 2)
- **Einstellungen-Screen (PH1-T2):** Route 1 = 3 ausklappbare Gruppen (Accordion je Gruppe mit Pfeil, eigener rememberSaveable-Klappe-Zustand je Gruppe): **Server** (Relay-URL-Feld = gleiche SSOT wie Home, Kopplungscode = bestehende PairingCard, mTLS-Bereich = bestehende MtlsSupport-Route nur angezeigt/geöffnet), **App** (Autostart, Akku-Optimierungs-Hinweis via System-Intent, Benachrichtigungen/Overlay/Datei/Aufnahme/Health-Routen = bestehende Berechtigungs-Karten, Termux-Toggle, Selbst-Update: github-Edition aktiv, play-Edition disabled mit Hinweis „nur in der GitHub-Edition" per BuildConfig.FLAG_FULL_EDITION), **Agenten** (Master-Schalter + die 16 Capability-Toggles in 4 klapptbaren Gruppen-Karten; JEDE Toggle-Änderung schreibt CapabilityGate + BridgeStatus.refreshConfig() unverändert weiter). Home schlank: Banner + Verbindungs-Status + Master-Status-Kompakt + Fußzeile — die langen Listen wanderten in die Einstellungen.
- **Fähigkeiten-Gruppen-Accordion (PH1-T5):** CapabilityGroupCard hat jetzt optional expand/onExpandedChange (Kopf = Touch-Feld, Pfeil, Zeilen nur ausgeklappt); je Gruppe eigener rememberSaveable-Zustand.
- **Fehler-Texte (PH1-T4):** FAILED(ursache)/RECONNECTING(ursache) zeigen im Banner Ursache + Nutzer-Handlung in generischem Deutsch (z. B. „Server nicht erreichbar — URL in den Einstellungen prüfen und Verbindung wiederherstellen."); die rohe Relay-Meldung bleibt in der Statuszeile der Verbindungskarte.
- **Impressum-Rahmen (PH1-T6):** je Rechts-Abschnitt (Impressum/Datenschutz/Nutzungsbedingungen) eine strukturierte Inhalts-Karte mit „Inhalte folgen in Phase 8" — keine privaten Daten im Gerüst.
- **Icons-Fix (PH1-W2-A):** 29 Haus-Drawables ic_* trugen Material-Symbols-Originalpfade in der 0,-960-Konvention (`M440-280` — Minus als Koordinaten-Trenner, vom einfachen `M-`-Grep nicht erkannt) → Android clippt negative Viewport-Bereiche, die Icons renderten unsichtbar (Welle-1-Pixel-Beweis für StatusBanner/Badge). Fix: absolute Y-Koordinaten je Pfad um +960 geshiftet (226 Koordinaten; derselbe Mechanismus wie der ic_chat-Fix); ic_chat war bereits gefixt, ic_placeholder (Viewport 24) war sauber.
- versionCode 31.

## [0.12.5] - 02.11.2026 — Navigation-Grundgerüst + Status-Migration + Doku-Fixes (PH1-Welle 1)
- **Navigation-Grundgerüst (PH1-T1):** BridgeScreen = einfacher Nav-Host per rememberSaveable-Int-Route (0=Home mit dem BESTEHENDEN Funktionsumfang unverändert, 1=Einstellungen-Platzhalter „Welle 2", 2=Chat-Platzhalter „Direkt-Chat — Phase 2", 3=Impressum/Datenschutz-Gerüst mit 3 Abschnitts-Headern + „Wird in Phase 8 mit Inhalten hinterlegt"). Top-Bar mit Einstellungen-/Chat-/Info-Icons (Material-Icons; KEINE neue Dependency, KEIN androidx.navigation — Settings/Info aus dem Compose-BOM-Icons-Set, Chat-Bubble als eigener Vector-Drawable im Hausstil); Back-Taste = Route zurück.
- **Status-Migration (PH1-T3):** ui/BridgeStatus.ConnectionState = sealed interface (IDLE, CONNECTING, CONNECTED, RECONNECTING(ursache), PAIRING_BROKEN, FAILED(ursache)) + StateFlow<ConnectionState> (+ eigene Flows für Server-Adresse und Anzeige-Text). RelayClient meldet strukturiert (neues Phase-Enum an notifyStatus); die String-Matching-Route (message.contains("Verbinde") …) ist KOMPLETT raus — Banner und Verbindungskarte matchen exhaustiv auf den Zustand (when ohne else).
- **Doku-/Konfig-Fixes (PH1-T0):** alte gradle-Task-Namen in README.md/docs/install.md/docs/AGENT_SETUP.md/FLIPSI_BRIDGE_SPEC.md auf die Flavor-Tasks umgestellt (assembleGithubDebug/assembleGithubRelease, Pfade outputs/apk/github/…); APK-Dateiname je Flavor `flipsibridge-<version>-<flavor>.apk` (Relay-/apk/latest-Parser bleibt kompatibel); build.yml EOF-Newline.
- versionCode 30.

## [0.12.4] - 02.11.2026 — App generisch machen (Play-Store-Vorbereitung)
- Agenten-Namen entfernt: accessibility_description + caps_section_hint sagen jetzt nur "Agent" (keine Produkt-/Agenten-Namen nutzersichtbar).
- **Ehemalige LAN-Ausnahme aus network_security_config entfernt** → `dashboard.lan` (generischer Beispiel-Hostname; die eigene Route bleibt funktional identisch für den lokalen Cleartext-ausfall).
- **Mikro-Notification** (MicrophoneRecorderService): "Hermes Bridge microphone" → "FlipsiBridge Mikrofon" + deutsche Beschreibung.
- **BridgeServer-Hint:** "Hermes Bridge app" → "the app" (Play-Store-Neutralität).
- **CommandDispatcher-Dokumentstring:** "Hermes drives the device through" → "the server agent drives the device through".
- **ClipData-Marker** "hermes" → "bridge" + TTS-ID-Marker "hermes_speak_..." → "bridge_speak_..." (kein nutzersichtbarer Effekt).
- Rest-Referenzen sind nur noch interne Package-/PREFS-Namen (com.hermesandroid / hermes_bridge_prefs) — bewusst beibehalten, da Package-Umbenennung = Reinstall +Prefs-Datenverlust; für die Play-Store-Phase wird ein Package-Rename geplant.

## [0.12.3] - 01.11.2026 — WS-Kopplung über TLS-Domain gefixt (Befund: App verbindet nicht)
- buildWsUrl baut bei https-WSS-Route NIE PORT 8766 an — https://relay.example.com = Reverse-Proxy auf 443; Port-Anbau = 403 vom Proxy = laut Kopplungstod. Plaintext-LAN (http): Port 8766 bleibt. Beweis: WS-Handshake via 443 = 101 Switching Protocols.

- Neue HealthRationaleActivity (Kotlin/Compose, deutsch) + Manifest-Deklaration: androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE + Android-14 activity-alias (VIEW_PERMISSION_USAGE / HEALTH_PERMISSIONS + START_HEALTH_PERMISSION_RATIONALE). Erklärt LESEN-only-Route (Schritte/Puls/Schlaf/Gewicht...) + Zurück-Button. Health Connect zeigt die App NUR mit der Rationale-Activity in der Connected-Apps-Liste.

### P0: Einzeltoggles schrieben nicht ins Gate (dreifach belegt: PAR-1/SK-01/CHEF-01)
- Die Gruppenkarten-`onToggle` verwurfen cap+Zustand (`{ _, _ -> refreshConfig() }` — Redesign-Verlust aus v0.11) → alle 16 Fähigkeiten-Switches in den Gruppenkarten stumm. Jetzt: `CapabilityGate.setEnabled` + Refresh.
### Akku: A11y-Drossel-Rückweg (PAR/chef-02/SK-02)
- WS-Tod (`onClosed`/`onFailure`) resetted nur EventStore-Flags, nicht die Service-QUELLE → A11y-Service blieb nach jeder Stream-Session bis Reboot auf VOLLEM Event-Set. Jetzt `applyEventThrottle(false)` in beide Callbacks.
### Absturz: `/files_permission` auf Android 8–11 (SK-10)
- `Environment.isExternalStorageManager()` = API 30+ ungeschützt beim GET/POST → `NoSuchMethodError` (kein Exception-Fang!) = remote-crashbar. Jetzt SDK-30-Guard (GET mit älterem READ_EXTERNAL-Fallback, POST mit klarer Route).
### UI-Richtigkeit
- **Zeilenhöhe ohne Beschreibungs-Cut (F01, KRIT):** `height(48dp)` klemmte 2-zeiligen Text → Beschreibungen optisch abgeschnitten (PIL: Sub-Bänder 6–9 px). Jetzt `heightIn(min = 48dp)` in CapabilityRow/PermissionRow/MasterCard.
- **Banner-Glyph bei GETRENNT (CHEF-05):** StateBanner-When hatte OFF nicht → else lief auf das Warn-Glyph. Jetzt explizit Off-Glyph.
- **Roter Banner-Titel (F04):** „Kopplungscode abgelehnt" in SignalRed (4,31:1 < AA) → SignalRedText.
- **Busy-Detektor (F05):** „Neuverbindung …"-Texte erkannte weder Banner- noch Karten-Zustandsroute → Spinner/Pill zeigten bei der Wiederholungs-Leiter nichts. Beide Routen ergänzt.
- **Divider-Kontrast (F11):** Trenner/Feld-Border Line→LineStrong.
- **Onboarding-Refresh (CHEF-03):** Rückkehr aus System-Settings bumpt jetzt configVersion (MainActivity `onResume` → `refreshConfig`) — Freigabe-Badges sind sofort frisch.
- Master-Dialog: „Ohne invasive"-Meldung nennt die ECHTE Zahl nach Write (SK-11); toter `masterChecked`-State entfernt.
### Verbindungs-Selbstheilung (SK-03)
- Auth-Sonde (45-min) macht keinen Radio-Burst mehr bei Flugmodus (Verbindungsscheck vor dem Versuch); Takt bleibt bestehen.
### Security
- Logcat leakt nicht mehr den Relay-Host: WebSocket-Log via `safeUrlForLog`, onFailure wirft die Throwable-Message raus (PAR-P2/F1-Politik).
- Undo-Restore mit Frische-Check: bewusste Änderungen im 10-s-Snackbar-Fenster werden nicht überschrieben (SK-04).
### Deutsch-Einheit
- Foreground-Service-Notification des Eingabehilfe-Pfads deutsch („Brücke aktiv"/Kanal-Beschreibung) statt „Connected to server" (F08).
### Bewusst NICHT geändert (dokumentiert)
- PAR-2 (setPackage-Pins auf Settings-Intents): brüchig auf OEM-Varianten (MIUI-Securitycenter et al.), Angriffs-Vektor erfordert bereits installierte Fake-App mit Intent-Filter — resolveActivity-Precheck + Kette bleiben.
- SK-08: Health-Cap-Advertisement ohne Rechte = Design-Natur; Server erhält ordentliche 403-Fehlerantwort mit Rechte-Liste, UI zeigt „0/6 Rechte".
- CHEF-06: Notification-Events kommen seit Android 8 über den NotificationListener-Store; A11y-Mirror war die veraltete Route.
- PAR-3 (LAN-Klartext-Bearer): Bestands-Design (nur Heim-LAN-Bewusstsein im Footer) — TLS/mTLS auf der Handy-Server-Seite = v0.13.0-Kandidat.

### Crashes am Berechtigungs-Fluss (Meldung: App-„Fehler"-Protokoll bei „Alle Dateien zugreifen")
- **UI „Alle Dateien": deterministischer Crash gefixt.** Die Zeile startete die Listen-Action `MANAGE_ALL_FILES_ACCESS_PERMISSION` MIT package-URI — AOSP-Settings deklariert den package-Filter nur an der per-App-Action; `IntentFilter.matchData` matcht URI↔Filter-ohne-Data NIE → ActivityNotFoundException → ungeschützter Crash. Neu: `startFirstResolvable`-Helper (resolveActivity-Precheck + runCatching) mit Kette per-App-Action → Liste ohne URI → App-Details, Hinweis statt Crash.
- **HUD-Overlay:** dieselbe Fehlerklasse (Listen-Action + package-URI) — gleiche Kette, App-Action/Listen/Details.
- **Eingabehilfe:** gleicher Schutz (OEM-Varianten ohne die Action).
- **Server-Weg `POST /files_permission`:** `startActivity` dort war ungeschützt (ANFE = ganzer Prozess inkl. Bridge) — jetzt `runCatching` + ordnungsgemäße 503-Fehlermeldung.
- **Verbinden mit defekter URL = Crash (QA-Drill T20 am Emulator):** OkHttp `Request.Builder().url()` wirft bei ungültiger host/port-Form synchron (IllegalArgumentException) — Tippfehler im Server-Addr-Feld riss die App ab. Neu: `runCatching` um die Request-Bau + klare Statusmeldung „Server-Adresse ungültig — Format: https://dein-relay.example", kein Reconnect-Loop mit der falschen Adresse, normaler Neuanlauf nach Korrektur.
### Health Connect: „keine Berechtigung erteilt" diagnostizierbar + heilbar
- **Pre-Flight:** Capability-AN schattete noch nichts; `getSdkStatus` BEVOR der Consent-Lauf startet — nicht installiert → Ursachen-Dialog (mit Health-Connect-Systemeinstellungen-API 34+) + Play-Store-Fallback (market→https); Update nötig → eigener Dialog; verfügbar → Consent-Lauf.
- **Differenzierter Callback:** vollständig erteilt (Zahl) / TEILWEISE (fehlende Metriken NAMENTLICH) / leer („still blockiert oder abgebrochen"-Dialog mit Ausweigen — kein Bare-Toast mehr).
- **Zeile zeigt echten Zustand**: Grant-Status via client.permissionController (LaunchedEffect) — „0/6 Rechte" statt vernebelndes „Schalter AN"; Health-Connect fehlt → eigener Badge.
### UI v0.12.0 — „Fähigkeiten-Zentrale" (Material 3 dunkel, Apple-HIG-Qualität)
- **MASTER-SCHALTER „Alle Fähigkeiten"**: ein Schalter für alle 16 — beim AN mit Bestätigungsdialog, der die invasiven 5 NAMENTLICH listet + „Ohne invasive einschalten"-Weg; Master-AUS mit Undo-Snackbar (exakter Zustands-Snapshot + Restore). CapabilityGate bleibt unverändert (nur öffentliche API, neue ui/components/CapabilityMaster).
- **Gruppen-Karten mit Icons + Zähler-Badges** statt unsichtbare Gruppierung: Ansehen & Zustände / Gerät bedienen / Schreiben & Selbstpflege / Invasiv (roter 1dp-Rand — Warnung über FORM). Komplett neue Zeilen (Icon 24dp, ≥48dp ganze Zeile, Switch als Anzeige `onCheckedChange=null` + `toggleable(Role.Switch)`), StatusBadge (Icon+Text+Ton statt reiner Farbe), 30 Vector-Drawables (google/material-design-icons-Pfade, Apache-2.0).
- **Berechtigungen = 2 Zweck-Karten** („Grundlage der Fernsteuerung" / „Daten & Anzeige") statt 8-Zeilen-Monokarte; „Öffnen"-Chips (Sentence-Case); Akku-Zeile mit dem HAUPTGRUND im Text.
- **Tokens:** `SignalRedText` (roter TEXT — SignalRed war 4,31:1 auf Panel, unter 4,5:1-AA), `LineStrong` (Trenner — Line war 1,26:1), `OnEmber`; Shape-Scale (20dp-Hauptkarten), Spacing-Objekt, `FlipsiType.Code`; TalkBack-Headings + stateDescription; keine hartcodierten fontSize/Farben mehr im Screen.
- **Aufräum:** totes Legacy-UI entfernt (`CapabilitySwitches.kt`, `activity_main.xml`, vestigiale toml-Versionen, `caps_section_title`).
### Akku-Optimierung — ohne eine einzige Absenkung der Verbindungswachstufe
- **P0 A11y-Event-Sturm beendet:** Hochfrequenz-Events (CONTENT_CHANGED/SCROLL/FOCUSED/TEXT…) werden im Standardzustand bereits IM SERVICE verworfen (vor Allokation/IPC) und die Event-Quellen per dynamischem serviceInfo im System gedrosselt (DEFAULT-Set = Fenster-/Klick-Events, notificationTimeout 300) — bis 40 Events/s beim Scrollen wecken den Prozess nicht mehr. Volles Set NUR bei aktivem /events/stream.
- **P0 Stream-Leak:** WS-Tod setzt `highVolumeEvents`/`streaming` zurück (vorher blieb Dauer-Vollcapture bis zum Prozesstod).
- **P1 Doppel-Keepalive:** Client-Ping 20→45 s — NAT/NPM hält der 15-s-Server-Heartbeat; −100 Client-Frames/h, Mobilfunk-Radio-Wakes −56 %. Toterkennung stiller Drops 20-40 → 45-90 s (Netzwechsel deckt NetworkCallback sofort ab).
- **P1 Doppel-FGS:** zweite Dauer-Promotion im A11y-Service („Connected to server") gestrichen — die Priorität trägt der dataSync-FGS; ScreenRecorder hebt mediaProjection selbst.
- **P2:** FGS-Notification-Updates per notify() statt voller startForeground()-Neu-Anmeldung + 5-min-Alarms startet den Service nur, wenn er verstorben ist (`instance`-Guard); ScreenRecorder-HandlerThread lazy (lebt nur bei echter Aufnahme, release() räumt ab); ungenutzte SCHEDULE_EXACT_ALARM-Permission entfernt; 5-min-Alarms zünden keinen Versuch bei total fehlendem Netz (Flugmodus — NetworkCallback holt die Rückkehr).
- **P2 Telemetrie:** `/network` liefert `batteryUnrestricted` — verliert das Gerät die Akku-Freistellung, sieht es der Agent sofort.
- Unangetastet (Erreichbarkeit zuerst): Retry-Ladder 1→30 s, 5-min-Low-Rate-Alarm, NetworkCallback-Sofortversuch, 401/403-Stopp, 45-min-Auth-Sonde, Wakelock-Regeln.
### Version
- versionCode 25, versionName 0.12.0. TargetSdk bleibt 34 (Android-15-6-h-FGS-Falle für dataSync ist im Audit dokumentiert; onTimeout-Fallback kommt mit dem 35er-Sprung).

## [0.11.0] - 30.10.2026
### Verbindungskern — Rebuild (Anforderung: „jederzeit Zugriff, nie neuer Kopplungscode")
- **Kopplungscode-SSOT**: ZWEI divergierende Code-Kopien (PairingManager `pairing_code` vs. RelayClient `relay_pairing_code`) entfernt — UI-Regenerate wirkte sonst nie am WebSocket (die Vorfall-Wurzel: 403 → Relay-Block → „neuer Kopplungscode nötig"). Code wird bei JEDEM Versuch frisch gelesen.
- **Vault-Persistenz**: Code liegt zusätzlich in app-privatem `getDir("vault")` — ein Prefs-Wipe (HyperOS-Cleaner) wird SICHTBAR WIEDERHERGESTELLT (Event RESTORED + Notification), nie mehr still neu generiert. Nur wenn Prefs UND Vault leer sind: Neuanlage, und das mit sichtbarer Meldung.
- **Kein Terminalzustand mehr**: nach dem Retry-Budget läuft die Verbindung im endlosen 5-Min-Modus (Low-Rate) statt „Tap Connect to retry" — nach nachtlichem WLAN-Ausfall kommt die Brücke von allein zurück.
- **Wache (Alarm)**: Versuche ≥60 s laufen als AlarmManager `setAndAllowWhileIdle` statt Coroutine-Sleep — Doze-überlebensfähig. Alarmlauf-Ruf räumt `reconnectPending` selbst (der Stuck-Guard schluckte vorher den Alarmschuss — Emulator-Repro).
- **Auth-Stop-Politik**: 401/403 stoppt nach 3 Versuchen (Relay blockt bei 5 — Endlos-Retry lockte die eigene IP), persistenter `auth_broken`-Zustand + klare UI-Anzeige. 429 = Blockfenster abwarten, ohne Budget zu verbrennen.
- **NetworkCallback**: Netz wieder da → sofortiger Versuch. NUR `onAvailable` (Capabilities-Sturm entfernt — Emulator-Repro).
- **Boot-Restart**: BOOT_COMPLETED/MY_PACKAGE_REPLACED/QUICKBOOT-Receiver + `BridgeConnectionService` (echter FGS, Type dataSync) — nach Handy-Neustart und App-Update startet die Brücke selbst; nicht mehr vom optionalen Accessibility-Service abhängig.
- **FGS-Regel-Crash behoben**: connect()-internes disconnect() rief stopService() im 5-s-FGS-Fenster → `ForegroundServiceDidNotStartInTimeException` (die App starb exakt beim Verbinden — Emulator-Repro). Trennen = nur WS aus, FGS bleibt mit „Getrennt"-Anzeige.
### Health Connect (read-only)
- Route `POST /health_data` (App: `HealthDataBridge`): Aggregate über 1..30 Tage — Schritte, Distanz, Kalorien, Herzfrequenz (min/avg/max), Schlafdauer, jüngstes Gewicht. Keine Rohdaten-Streams (PII-Minimierung). Capability `health`, Default AUS, UI-Schalter + Consent-Dialog im Permissions-Bereich.
- Relay-Allowlist `_ROUTES` + docs/architecture.md + Tool `android_health` (tools/ + /opt-Kopie) + Test-Zähler.
### UI-Redesign (Jetpack Compose, Material 3, dunkel)
- Komplett neue UI (XML-Endlosliste ersetzt): Statusbanner (immer oben, Farbe + Text), geführter Fluss (Schritt 1 Kopplung → Schritt 2 Verbinden), Berechtigungskarte mit HyperOS-Akku-/Autostart-Zeilen, Fähigkeiten in 4 Gruppen (Ansehen/Bedienen/Schreiben/Invasiv), Inline-Ladeanzeige, 48dp-Touch-Ziele, „öffnen"-Chips, kein Sprachmix.
### Relay (Server)
- `hmac.compare_digest`-TypeError-Fall beseitigt (non-ASCII-Token war ein 500er UND ungezählt → Rate-Limit umgehbar): UTF-8-Bytes-Vergleich über `_constant_time_eq`.
- Rate-Limit-Keying per **X-Forwarded-For** (letzter Eintrag = vom trusted Proxy angehängt) — hinter dem NPM teilen sich sonst ALLE externen Clients eine IP (gezielter Lockout des legitimen Handys möglich).
- `/apk/latest`: versionsbewusste APK-Auswahl (0.10.10 > 0.10.7), Chunked-SHA-256 (kein Ganzlesen in RAM), Rejekt-Logging.
- **HOL-Blocking behoben**: Stream-Puffer 8→256, WS-Read-Loop blockiert nie mehr auf einem langsamen HTTP-Konsumenten (put_nowait + bewusster Abbruch bei Überlauf).
- Daemon-Watchdog: toter Relay-Thread beendet den Daemon (exit 2) → systemd startet neu (vorher: „active", aber dunkel).
### Toolchain
- Migration auf Kotlin 2.0.21 / AGP 8.9.1 / Gradle 8.11.1 / compileSdk 36 (Health Connect 1.1.0 verlangt das), targetSdk bewusst 34. ktor 2.3.13.
### Tests
- Kotlin: PairingVaultTest (Robolectric: Restore/Regenerate/SSOT) + Suite (105/107 grün; 2 = Windows-Symlink-Artefakte, Linux-Beleggrünlauf ok). Python: 175/175.
### Emulator-Simulation (Night-QA, Pixel-4-AVD)
- Verlust-Drill: Relay-Kill → Ladder → Wache → automatische Wiederverbindung ohne Eingriff (live belegt, 200). Update-Empfang (MY_PACKAGE_REPLACED → Service-Hochfahrt) live belegt.
### Version
- versionCode 24, versionName 0.11.0.

## [0.10.8] - 25.09.2026
### Security (upstream review, 6 Blocking-Punkte behoben)
- **/apk_install token leak behoben**: Update-Quelle ist FIX auf das konfigurierte Relay (/apk/latest) — kein Aufrufer-URL-Parameter mehr, der Pairing-Token geht nie an fremde Hosts. SHA-256 kommt als X-APK-SHA256-Header serverseitig, wird gegen DIESEN Wert geprüft.
- **CapabilityGate deny-unknown**: Unbekannte Routen sind abgelehnt statt erlaubt. /mic_file ist jetzt an das Gate gebunden (lokal + Relay).
- **Migration für Bestandsinstallationen**: Apps, die vor dem Gating liefen (Relay-URL vorhanden), behalten alles aktiv (legacy_had_relay-Flag bei connect). Neue Installationen starten default OFF.
- **mTLS korrekt über KeyChain**: KeyChain.choosePrivateKeyAlias + getPrivateKey statt der fehlerhaften CA-Store-Annahme.
- **Datei-Pfad-Sanitizing**: unbekanntes Top-Level-Segment wird abgelehnt (kein Root-Fallback mehr); /files_push + /files_delete unter separater Capability "files_write" (destruktiv getrennt vom Lesen).
- **/notify_reply**: eigene Capability "notify_reply" (Outbound-Action ≠ Lesen); RemoteInput.addResultsToIntent statt manueller Bundle-Zusammensetzung.
- **Relay /apk/latest**: Auth gegen denselben Pairing-Code wie alle Routen, konstante Zeit (hmac.compare_digest), Rate-Limiting. Kein separates Token mehr.
- **Kamera-Dialog** nur noch bei aktivierter device-Capability statt bei jedem App-Start.
- **Neue Security-Tests**: tests/test_security_v0_10_8.py (13 Tests: deny-unknown, sanitize, installer, migration, capability separation, relay auth).
### Fork-Hygiene
- Private-Kontext-Referenzen aus Quellcode entfernt.
### Version
- versionCode 23, versionName 0.10.8 (signierte Release-APK).

## [0.10.0-0.10.7-flipsi] - 21.09.2026
### Neu (Phase 10 - Geräte-Hardware)
- **device-Capability**: Foto auf Befehl (CameraX-frei, CameraManager + ImageReader, Retry-Loop, aufrecht: +90° CW byte-rotation ohne EXIF-Tag - Empirie über 5 Testfotos), Taschenlampe, Netz-Status (WiFi/BT/Internet/metered/transport, Permissions ACCESS_NETWORK_STATE + ACCESS_WIFI_STATE), Lautstärke lesen/setzen (media/ring/alarm/notification), Wecker & Timer (SET_ALARM-Permission, System-Clock-Intent).
- **notifications erweitert**: /notify_reply - Direkt-Antwort in Benachrichtigungen (RemoteInput, WhatsApp/Telegram/SMSe), Antwort über key oder package.
- CAMERA-Laufzeitrecht wird beim App-Start angefragt.
- Python-Tools: android_photo, android_torch, android_network_status, android_volume, android_alarm, android_timer, android_notify_reply.
### Fix
- Foto-Orientierung: 4 Versuche (EXIF-Override, +90, -90, roh) - finale Lösung: byte-level +90° CW, kein EXIF-Tag. Live verifiziert.

## [0.9.6-flipsi] - 21.09.2026
### Kritisch
- **Installer-Start schlug stumm fehl**: REQUEST_INSTALL_PACKAGES + canRequestPackageInstalls()-Check mit Auto-ffnung der "Apps aus unbekannten Quellen"-Einstellungsseite.

## [0.9.5-flipsi] - 21.09.2026
### Neu
- **Generelles Löschen** (Freigabe 21.09.): /files_delete akzeptiert path ODER paths[] (max 200/Call). Ordner bleiben gesperrt.
- UpdateInstaller sendet Pairing-Code als Bearer (RelayClient.pairingCode zuerst), Relay /apk/latest akzeptiert Token auch als Query-Param.

# Changelog

All notable changes to this project are documented here. Format based on [Keep a Changelog](https://keepachangelog.com/); this project adheres to Conventional Commits.

## [0.9.2-flipsi] - 21.09.2026
### Kritisch
- **CapabilityGate.init() wurde NIE aufgerufen** — SharedPreferences blieb null: alle Agent-Fähigkeiten-Schalter waren funktionslos (das 403-Gate konnte nie offen sein) und alles fiel nach App-Neustart auf AUS zurück. Jetzt korrekt in BridgeApplication.onCreate initialisiert.
### Hinweis
- Bildschirmaufnahme-Berechtigung gilt laut Android nur pro Sitzung — nach App-Neustart fragt das Consent-Dialogfeld einmal neu (Android-Sicherheit, absichtlich kein Umweg). Der Schalter zeigt den echten Zustand.

## [0.9.0-flipsi] - 21.09.2026
### Neu (Phase 4 — Dateien + Selbstupdate)
- **Datei-Zugriff (Capability "files")**: /files (listen), /files_search (rekursiv), /files_count (Zähler je Endung), /file (Binär-Stream). Lese-only, Pfad-Whitelist, max 512 MB.
- **APK-Selbstupdate (Capability "selfupdate")**: /apk_install mit SHA-256-Pflicht, Installer-Start mit Nutzerbestätigung; Relay /apk/latest (token-geschützt).
- **Python-Tools**: android_files_list/search/count, android_file_get, android_apk_install.

## [0.9.1-flipsi] - 21.09.2026
### Fix
- **Duplikat-Bug:** Die Agent-Faehigkeiten-Liste wuchs bei jedem App-FFnen um einen kompletten Satz (bind() lief in onResume erneut, ohne die alte Liste zu entfernen). Jetzt idempotent ueber getaggten Container.

## [0.5.0] - 2026-09-05

### Added
- Termux auto-revival watchdog (#100): after repeated relay failures, an **opt-in** (default OFF) switch fires the Termux `RUN_COMMAND` intent to restart the stack, then schedules a delayed reconnect. Pure `RevivalPolicy` gate/counter/cooldown logic with unit tests; `SecurityException` surfaces visibly instead of being swallowed.
- `include_removed` option on `android_notifications` (#100): dismissed notifications are retained on-device but excluded by default — callers must opt in explicitly to read notifications the user has cleared.
- Notification buffer raised from 100 to 300 entries (#100).
- `android_press_key("wake")` turns the screen on via a short auto-releasing wake lock. `power` is unchanged and still opens the long-press power dialog (#101).
- `android_*` tools fall back to reading `ANDROID_BRIDGE_URL`/`ANDROID_BRIDGE_TOKEN` from `~/.hermes/.env` when the gateway process does not export them (`os.environ` still takes precedence) (#101).
- Four microphone tools (`android_mic_record`, `android_mic_stop`, `android_mic_status`, `android_mic_fetch`) with relay-routed binary WAV streaming and `MEDIA:` delivery.
- Recorder state reporting and atomic `.part` → `.wav` finalization so interrupted recordings are never advertised as complete.
- Keep-last-10 retention for completed WAV recordings.

### Fixed
- Mic recorder state machine can no longer stick at `starting` after an early STOP or service destruction — `/mic_start` returned 409 until app restart before (#98).
- Plugin now registers the android usage skill via `ctx.register_skill()`, so agents discover the accessibility-tree-first guidance instead of defaulting to screenshot + vision. Screenshot tool description defers to `android_read_screen`/`android_find_nodes`.
- Use one canonical microphone directory for recording, status, and download.
- Use the on-device-tested `VOICE_RECOGNITION` source with saturating 2.5x PCM gain and stop the recorder service after a STOP command.
- Register microphone routes, schemas, and handlers in both the standalone toolset and installable plugin.
- Replace deprecated aiohttp bare route handlers and add end-to-end binary-stream regression coverage.

### Security
- `android_mic_fetch` connection errors no longer echo the bridge host:port from `requests` exception text (#99).
- Regression coverage for the security-critical mic paths (#99): `MicrophoneRecordingFiles.resolve()` traversal/symlink defense, relay binary-stream negative paths (checksum/length/oversize/disconnect), and the 30-minute sample cap.
- Remove device-specific connection details and legacy SCP instructions from microphone tooling.
- Stop logging notification content and pairing-token prefixes, including in debug builds.

## [0.4.1] - 2026-08-09

### Fixed
- relay reconnect: an unreachable server address retried forever. Each failed attempt fired `onFailure`, which cancelled the retry coroutine and started a new one with a fresh counter, so the 5-attempt cap was never reached and the UI stayed stuck on "Connecting…". Attempt count and exponential backoff now live in a `ReconnectPolicy` whose budget survives across callbacks. The budget is refilled only by a user-initiated Connect or by a session that stayed up ≥60s — reaching `onOpen` is not enough, so a relay that accepts the socket and immediately drops it (wrong pairing code) also stops instead of flapping forever. Callbacks from superseded sockets are ignored via a generation guard, `onClosed`/`onFailure` for one dead socket can no longer schedule two attempts, and the exhaustion path retires the socket so a late callback can't overwrite the "Tap Connect to retry" status
- version string on the main view was hardcoded to `v0.2.0` in both portrait and landscape layouts; now dynamically reads from `BuildConfig.VERSION_NAME`

## [0.4.0] - 2026-08-03

### Security
- sendIntent: block dangerous activity-launch actions (CALL, CALL_PRIVILEGED, ~20 sensitive settings screens) plus an `android.settings.*` prefix catch-all so new settings actions are denied by default (#89, extended by audit)
- sendIntent: URI scheme denylist (`intent://`, `market://`, `tel:`, `smsto:`, `mmsto:`) and `content://settings` / contacts provider prefixes close scheme-based bypasses of the action blocklist; fixes single-colon scheme extraction that let `tel:123` through (#91)
- sendBroadcast: blocklist for dangerous broadcast actions (#80) incl. system-destructive ones (shutdown / master_clear / factory_reset) (#87)
- searchContacts: URL-encode the query to prevent URI injection (#84)
- pairing code now sent as `Authorization: *** header on the relay WebSocket handshake instead of a `?token=` query parameter (which leaks into reverse-proxy access logs); `?token=` fallback fully removed from relay WS auth and plugin copy (#78, #83)
- redact PII-bearing fields (recipient, phone number, message/typed/clipboard text, intent extras) from relay debug body logs
- unauthenticated `/ping` no longer discloses the bridge version (#81)

### Fixed
- recycle intermediate ancestor AccessibilityNodeInfo nodes on the path to a match in ScreenReader.findNodeByTextDfs and ActionExecutor.findNodeByIdInTree — previously leaked on every tap_text/tap-by-id call, exhausting the accessibility node pool over long sessions

### Changed
- docs/install.md is now the single end-to-end install doc: added the on-phone permission-grant checklist and a "Persistent relay (systemd)" section documenting the contrib/ daemon + unit file; README points there and tool count fixed (36 → 38)
- install.sh cleans its temp dir on all exit paths via trap
- document all 38 registered tools in the tools/android_tool.py module docstring; mark android_send_sms/android_call docstrings as destructive (confirm-first)
- `android_read_screen` now excludes System UI (status bar, nav bar) by default for token efficiency; pass `include_system_ui=true` to include it. Use `android_press_key` for back/home/recents (#34, @null-dev)
- screen hashes/diffs no longer churn on clock/battery updates since System UI is filtered from the tree by default (#34)

## [0.3.0]

### Added
- feat(bridge): add per-IP auth rate limiting to BridgeServer (#51)
- Notification listener — agent reads incoming notifications in real-time (`android_notifications` / `android_events`)
- Clipboard bridge — read/write clipboard between server and phone (`android_clipboard_read` / `android_clipboard_write`)
- Direct SMS and calls without UI navigation (`android_send_sms` / `android_call`)
- Location sharing — agent reads phone GPS location (`android_location`)
- hermes-agent v0.3 plugin system integration (`hermes-android-plugin/`, 38 tools)

### Changed
- wrap /wait handler in withContext(Dispatchers.Main) for thread-safety (#55)
- bind relay to localhost by default via ANDROID_RELAY_HOST env var (#38)
- cap ScreenRecorder duration to 30s to prevent OOM (#37)
- wrap ScreenRecorder.record() in Dispatchers.IO (#36)
- strip PII from makeCall and clipboardWrite responses (#35)
- downgrade relay body logging from INFO to DEBUG with truncation (#47, #33)
- strip recipient phone number from SMS success response (#28)
- convert ensureTts from CountDownLatch to suspend function (#27)

### Fixed
- fix(android_macro): halt on transport-level errors, not just success=false (#54)
- add runtime permission guard to location() (#49)
- null-check Bitmap.copy() result in takeScreenshot (#50)
- synchronize NotificationStore getAll/getSince/clear with lock (#45)
- replace ACTION_CUT with ACTION_SET_TEXT empty string in typeText clearFirst (#46)
- use exact-case comparison for pairing code auth (#43)
- align pressKey schema with actually supported keys (#44)
- synchronize EventStore clear(), getAll(), getSince() with lock (#41)
- synchronize NotificationStore.markRemoved() with lock (#42)
- fix ConcurrentLinkedDeque size/removeLast race condition (#39)
- remove dead code in diffScreen — unused currentTexts map (#40)
- recycle AccessibilityWindowInfo objects in /current_app handler (#30)
- recycle AccessibilityWindowInfo in findNodeById and readWidgets (#31)
- recycle AccessibilityWindowInfo in ScreenReader (#32)

[Unreleased]: https://github.com/TechFlipsi/FlipsiBridge/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/TechFlipsi/FlipsiBridge/releases/tag/v0.5.0
[0.4.1]: https://github.com/TechFlipsi/FlipsiBridge/releases/tag/v0.4.1
[0.4.0]: https://github.com/TechFlipsi/FlipsiBridge/releases/tag/v0.4.0
[0.3.0]: https://github.com/TechFlipsi/FlipsiBridge/releases/tag/v0.3.0

## [0.16.6] - 2026-11-17
### Behoben
- **WS-Client TLS: handshake-Killer behoben (Device-Key vs Conscrypt-Digest)** — das Android-Keystore-Device-Verband (KeyPair setDigests(SHA256)) brach im TLS 1.3-ClientAuth-Verband mit „Incompatible digest" (-13) zusammen; der Handshake starb, bevor das Client-Zertifikat versendet wurde (serverseitig therefore `verify=NONE`) → Endlosschleife Enroll/Verbinden. Der mTLS-WebSocket-Client spricht jetzt bewusst TLS 1.2 (SHA256-ClientAuth kompatibel zum bestehenden Key — kein Re-Enroll nötig). Zukünftige Device-Keys erzeugen mit SHA256+SHA384+SHA512.
- mTLS-Kette = Server-Device-Zert aus dem Enrollment (W17-Fix db996cb, mitgeliefert).
### Serverseite
- `/agent_ws` über Reverse-Proxy (Stufe-2-Map): Freipfad für Agent-Adapter-Lane (Token-Auth am Relay, kein Client-Zert) — der Adatper-WS-Pfad wurde mit der Stufe-2-Wiederherstellung versehentlich 421-gesperrt.
