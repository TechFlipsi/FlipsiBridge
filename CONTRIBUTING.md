# Contributing zu FlipsiBridge

Danke fürs Interesse! Das Projekt wird von einem Selbsthoster gebaut und täglich auf dem eigenen Handy genutzt.

## Wie du beitragen kannst
- **Bugs**: Issue mit Schritten + Gerät/Android-Version. Logs nur anonymisiert (keine Pairing-Codes, keine IPs!).
- **Fehler/Fähigkeit-Ideen**: Discussion [Feature-Wünsche](https://github.com/TechFlipsi/FlipsiBridge/discussions/2)
- **Übersetzungen**: die UI ist Deutsch-first; EN-Mitarbeit ist unser `good first issue`
- **Code**: Clone, Branch (`feature/...`), PR. Der Android-Code baut am Standalone-Setup (Gradle, Kotlin 2.x + Compose), Backend = Python (pytest).

## Regeln
- Keine echten Daten/API-Keys/IPs in Commits, Logs oder Screenshots (die App verweigert OS-Screenshots bewusst)
- Neue `/command`-Routen brauchen: `CommandDispatcher.kt` + `CapabilityGate`-Eintrag + Relay-Allowlist + docs/architecture-Zeile + Tests
- CI-Grün (GitHub-Flavor + Play-Flavor) vor dem Merge

## Lizenz
Beiträge = MIT wie das Projekt. (Copyright-Zeilen der Basis bleiben unangetastet.)
