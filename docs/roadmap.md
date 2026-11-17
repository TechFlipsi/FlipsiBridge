

---
## FEATURE-KANDIDAT (17.11.26, Sir-Idee, wartet auf GO): Optionales Extra-Passwort für die Bridge-Kopplung

**Kern:** In Einstellungen › Server kann der Nutzer optional ein Zusatz-Passwort setzen. Bei der Brücken-Einrichtung (Kopplung am neuen Gerät) wird dann neben dem 12-Zeichen-Kopplungscode zusätzlich dieses Passwort verlangt. Ziel: Falls der Code beobachtet/gelakt wird, reicht er allein nicht.

**Architektur (Entwurf):**
- Server-seitig: Passwort NUR als Hash persistiert (PBKDF2/Argon2id, kein Klartext); Ablage neben/unter relay.env bzw. /etc/flipsibridge-Sektor; gesetzt/entfernt über die bestehende authentifizierte WS/HTTP-Verbindung (Token genügt, da Einstellung nur vom gekoppelten Nutzer selbst).
- Kopplungs-Route: prüft Code UND — falls Hash vorhanden — zusätzlich Passwort; Fehlversuche laufen in die bestehende IP-Rate-Chain (429 nach 5).
- Antwort an die App bei Kopplung ohne Passwort: klare „Passwort erforderlich"-Struktur, damit die App das zweite Feld nachzieht (kein Rätselraten).
- App: Einstellungen › Server bekommt Schalter + Passwortfeld (set/entfernen, Anzeige ob aktiv); Kopplungs-Dialog zeigt Passwortfeld nur, wenn Server eines verlangt. Passwortfeld maskiert (kein Klartext im Screenshot-Risiko).
- Kombination mit Ein-Gerät-pro-Code-Verband (separater Feature-Kandidat, gleiche Code-Region): beide in EINER Implementierungswelle durch denselben Sub-Agenten bauen — verhindert Merge-Krieg.

**Umfang:** M — Relay ca. 1–2 h (Set-Route, Gate in Kopplung, Tests) + App ca. 2–3 h (Einstellungen, Kopplungsdialog, Strings 3 Sprachen symmetrisch) + 4-Jäger-QA + Emulator-Re-Drill.

**Risiken:** (a) Passwort-Vergessen → dokumentierter Reset-Weg vom Server aus (SSH-Befehl, dokumentiert im Betriebsprotokoll). (b) Wird es bei JEDER Verbindung verlangt, frustriert es den Alltag (Doze/WLAN-Wechsel) — deshalb Design-Frage an Sir: nur Einrichtung vs. auch jede Verbindung. (c) Kein Klartext-Passwort je Log/Backup — Hash-only.

**Umfang fix (Sir 17.11.26):** Passwort-Abfrage NUR bei der Einrichtung/Kopplung der Brücke (laufende Verbindungen unverändert). Mindestlänge: 8, sonst frei. Reset-Weg: SSH-Befehl vom Server aus, wird im Betriebsprotokoll dokumentiert. → Implementierungswelle: zusammen mit Ein-Gerät-pro-Code (dritter Sub-Agent, nach der mTLS-Räumung).
