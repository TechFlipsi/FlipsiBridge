package com.hermesandroid.bridge.client

/**
 * PH-3 F4 (Jäger-P2-Fix): Chat-Status-Rang-Dedup — reine Logik-Einheit,
 * SPIEGEL des Relay-_CHAT_STATUS_ORDER (tools/android_relay.py: Rangfolge
 * queued(0) < delivered(1) < read(2); nur aufsteigende Übergänge gewinnen).
 * RelayClient.handleChatStatusFrame wendet sie an, damit ein verspäteter
 * 'delivered'-Frame einen bereits 'read'-Chip NIE zurückklebt
 * (last-write-wins-Falle bei Spool-/Reconnect-Umsortierung).
 */
object ChatStatusRank {

    private val ORDER = mapOf("queued" to 0, "delivered" to 1, "read" to 2)

    /** Rang eines Status (-1 = unbekannt). */
    fun rank(status: String): Int = ORDER[status] ?: -1

    /**
     * true = der eingehende Status DARF den existierenden überschreiben
     * (kein Eintrag oder echter Aufstieg). Duplikate und Downgrades: false.
     */
    fun shouldApply(existing: String?, incoming: String): Boolean {
        if (existing == null) return true
        return rank(incoming) > rank(existing)
    }
}