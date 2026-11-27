package com.hermesandroid.bridge.client

/**
 * W23 Lane U5 (Punkt W23-5): Retry-Backoff SICHTBAR machen.
 *
 * Reine Countdown-/Format-Regeln für den Reconnect-Backoff des
 * [RelayClient] — bewusst android-frei (JVM-getestet, Parität
 * [ReconnectPolicy]/[CallRules.formatDuration]). Der Client legt nach
 * jedem Fail `now + backoff` als Ziel-Zeitmarke (elapsedRealtime) ab;
 * diese Regeln rechnen daraus die ehrliche Rest-Sekunde und ihren Text.
 */
object RetryCountdown {

    /**
     * Rest-Sekunden bis zum nächsten Verbindungsversuch — AUFGERUNDET
     * (Rest 42,4 s = „43 s“, der Versuch frühestens nach 43 GANZEN
     * Sekunden da sein kann) und NIE negativ (Ziel liegt in der
     * Vergangenheit = 0, kein „-3 s“).
     *
     * @param nextRetryAtElapsedMs Ziel-Marke `now + backoff`
     * (SystemClock.elapsedRealtime-Basis, monotone Boot-Uhr — überlebt
     * Doze-Pausen des Tickers fehlerfrei, da je Tick NEU gerechnet).
     * @param nowElapsedMs die gleiche Uhr, aktuelle Lesung.
     */
    fun remainingSeconds(nextRetryAtElapsedMs: Long, nowElapsedMs: Long): Long {
        val remainingMs = (nextRetryAtElapsedMs - nowElapsedMs).coerceAtLeast(0L)
        return (remainingMs + 999L) / 1000L
    }

    /**
     * Kurz-Dauer für den Banner-Countdown: „42 s“ unter 60, ab 60
     * Minuten+Sekunden („1 Min 30 s“); glatte Minute ohne Rest-Anteil
     * („5 Min“). Negative Eingaben zählen als 0 (nicht negativ).
     * `[secUnit]/[minUnit]` machen die Kurzeinheiten lokal auflösbar
     * (UI: stringResource), die Defaults tragen die deutsche Kanonform.
     */
    fun formatDuration(totalSeconds: Long, secUnit: String = "s", minUnit: String = "Min"): String {
        val s = maxOf(0L, totalSeconds)
        if (s < 60L) return "$s $secUnit"
        val m = s / 60L
        val rest = s % 60L
        return if (rest == 0L) "$m $minUnit" else "$m $minUnit $rest $secUnit"
    }
}
