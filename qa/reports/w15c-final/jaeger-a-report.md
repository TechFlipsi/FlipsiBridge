# Jaeger A — Compose-UI-Jagd @2341665 (deleg_d38fbbe9, 12.11.2026)
Befunde (9 total ueber beide Jaeger, hier A: 6):

## A-BLOCKER (thematischer Blocker, 3 Funktionen = 1 Defekt)
- B5/A: DashboardRules.kt:77-79 statusLabel ("Beschaeftigt/Online/Offline"), :58-67 relativeTime ("gerade eben/vor X min/Std."), :83-89+97-98 summaryLine/agentCountLabel — deutsche Literale gerendert in DashboardScreen.kt :296/:288-290/:128; EN-Ansicht zeigt deutsch. => W15c b957a76 gefixt (LabelRes-Muster + Plurals, 443+2 Keys je Datei).

## A-KLEIN (3) — alle W15c 4b81775 gefixt
- OnboardingScreens.kt:687 height(72.dp) statt heightIn(min=72.dp) + Text:702 ohne maxLines => geheoht
- RelayServerList.kt:285/:304 profile.label/url ohne maxLines/Ellipsis => maxLines=1+ellipsis
- StateBanner.kt:51 background(tint 0.14) ohne shape/clip => background(…, shapes.large)

## A-LEER bewiesen (5 Kategorien gecheckt, 43 UI-Dateien)
- clip-nach-background: 0; verwaiste Legacy-Kompos: 0 (40 @Composable referenziert); Status-Logik konsistent (read=3/gruen etc.); Key-Paritaet 432+85 sauber; Format-Args 0 Mismatch.
