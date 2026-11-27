package com.hermesandroid.bridge.ui.chat

/**
 * W4-B (Swipe-to-Reply): Reine Regeln der Wischgeste am Bubble — JVM-getestet
 * (ChatSwipeRulesTest); die Gesten-Verdrahtung in MessageBubble (ChatWidgets)
 * folgt exakt diesen Werten.
 *
 * Vertrag (SIR-Verband): Wischgeste = GLEICHE Wirkung wie Longpress-Ergebnis
 * (onReply → Zitier-Overlay). Nur die NACH-RECHTS-Fahrt triggert (Messenger-
 * Muster; eine Links-Fahrt bleibt der Listen-Fling-Region vorbehalten und
 * gibt nie das Overlay) — die Übersetzung folgt dem Finger live, klemmt bei 0
 * (keine Rückübersetzung nach links) und am [MAX_TRAVEL_DP]-Deckel.
 *
 * ## Drill-Acker (Bau-Welle, Emulator)
 * - Wisch nach rechts ≥ 32 dp → Zitieren-Strip erscheint (Longpress-Kontrast:
 *   gleiche Wirkung, KEIN extra Kontextmenü).
 * - Wisch < 32 dp / links / vertikal (Liste scrollt) → KEIN Overlay, Bubble
 *   läuft sofort zurück auf 0.
 * - Reduced-Motion (Entwickleroptionen → Animator-Scale 0): Settle springt
 *   OHNE Tween sofort auf 0 (kein Eigen-Anim an dieser Stelle).
 * - Wisch während des Zurücklaufens: neuer Drag reißt die Anim ab (Snap),
 *   kein Doppelauslösen (fired-Latch je Drag-Sequenz).
 */
object ChatSwipeRules {

    /** Auslöseschwelle der Wischstrecke (NACH RECHTS). */
    const val TRIGGER_DP = 32f

    /** Übersetzungs-Deckel des Bubble-Nachziehens (Finger folgt, geklemmt). */
    const val MAX_TRAVEL_DP = 80f

    /** Settle-Tween der Rücklauf-Faser (nur OHNE Reduced-Motion). */
    const val SETTLE_MS = 120

    /**
     * true = Auslösen: nur bei POSITIVER Fahrt ([traveledPx] nach rechts) und
     * exakt ab der Schwelle (≥, Grenzfall = ausgelöst).
     */
    fun triggered(traveledPx: Float, triggerPx: Float): Boolean =
        traveledPx >= triggerPx

    /**
     * Übersetzung klemmen: nie negativ (Links-Fahrt zieht auf 0 zurück, nie
     * darüber hinaus) und nie über den [MAX_TRAVEL_DP]-Deckel. Der Wert ist
     * die REINE Klemmung — der Sensordruck rechnet px je Drag-Puls selbst.
     */
    fun clampTravel(traveledPx: Float, maxPx: Float): Float =
        traveledPx.coerceIn(0f, maxPx)
}

/**
 * W4-B (Anim-Faser): Reduced-Pass-Verband je Anim-Stelle im Chat — EINE
 * Wahrheit pro Animationsschritt: die Einstellungs-Anim-Scala des Systems
 * (Settings.Global.ANIMATOR_DURATION_SCALE) wird VOR dem Animationsschritt
 * gelesen; 0 = Zielzustand SOFORT, ohne Tween (Accessibility-Ehrlichkeit:
 * „Motion reduzieren“ wird nie mit Eigen-Animationen übergangen).
 *
 * Angeschlossene Anim-Stellen (W4-Stand):
 * - Swipe-to-Reply Settle-Rücklauf (MessageBubble),
 * - Sprung-Scroll der Chat-Suche (ConversationBody, W4-B Chat-Suche).
 */
object ChatMotionRules {

    /** Anim-Scala des Systems (Settings.Global, Default 1f). */
    fun animatorScale(resolver: android.content.ContentResolver): Float =
        android.provider.Settings.Global.getFloat(
            resolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )

    /** true = Reduced-Motion (Scala 0 oder < 0 = „aus“); > 0 = volle Anim. */
    fun reduced(animatorScale: Float): Boolean = animatorScale <= 0f

    /** Settle-Dauer je Stelle: 0 ms = sofort (Reduced), sonst [ChatSwipeRules.SETTLE_MS]. */
    fun settleTweenMs(reducedMotion: Boolean): Int =
        if (reducedMotion) 0 else ChatSwipeRules.SETTLE_MS
}
