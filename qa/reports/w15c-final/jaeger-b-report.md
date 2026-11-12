# Jaeger B — State/Logik-Jagd @2341665 (deleg_d38fbbe9, 12.11.2026)
Befunde (B: 6):

## B-KLEIN — gefixt in W15c (4)
- B4: DashboardScreen.kt:131-134 Busy-Zaehler ohne online-Gate (DashboardRules.statusOf) => Online-Gate (auch 1s-Ticker :101)
- (B3-teil) ChatScreen.kt:419-427 onOpenedAgent() ohne Agent-Match brennt Merker => nur bei Match
- B5 = identisch Jaeger-A-Blocker (Dashboard-i18n) => W15c b957a76
- B6: HealthRationaleActivity.kt:44-76 hartdeutsch Gate-los => AppLanguage init+GateWrap + health_rationale_* Keys je 3 Dateien

## B-DOKUMENTIERT, bewusst NICHT gefixt (3) — in Commit-Message W15c vermerkt
- B1: MainActivity.kt:124-127/BridgeScreen.kt:228-232 Doku-Abweichung (route = rememberSaveable gewinnt bei Restore statt initialRoute=0) — Verhalten, kein Crash
- B2: OnboardingScreens.kt:258 onSkip-Default = onComplete (scharfe Klinge, aktuelle Call-Site korrekt)
- (relativeTime) Uhr-Skew-Zukunft zeigt dauerhaft "gerade eben" — tolerabler Randfall

## B-LEER bewiesen
- Threads: leer (Main-Handler-Postings :2545-2556/:2380-2400/:2793-2800, StateFlow CAS, @Volatile, ConcurrentHashMap, Disconnect-Generation-Guard)
- Ressourcen-Paritaet: 432/432/432 + onboarding_multi_server 85/85/85; 486 R.string-Refs alle aufoesbar; 0 Format-Mismatch
