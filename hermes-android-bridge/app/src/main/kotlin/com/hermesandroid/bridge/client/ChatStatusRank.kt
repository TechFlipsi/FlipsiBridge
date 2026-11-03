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
     *
     * FIX-WELLE-2 B8: 'failed' aus dem Session-Reset-Sweep blockiert
     * nachkommende queued-/delivered-Frames (Status-Re-Flush nach
     * Reconnect) — nur ein echtes 'read' gewinnt (Selbstheilung für
     * falsch gefegte Nachrichten anderer Agenten). Sonst würde ein
     * Spool-Re-Flush den Chip auf 'Zustellt' zurückkleben und genau die
     * Endlos-Zustellt-Lüge aus HERZ-Befund 8 wiederherstellen.
     */
    fun shouldApply(existing: String?, incoming: String): Boolean {
        if (existing == null) return true
        if (existing == "failed") return incoming == "read"
        return rank(incoming) > rank(existing)
    }

    /**
     * FIX-WELLE-2 B8 (P3, HERZ-Befund 8): Session-Invalidierung fegt alle
     * noch nicht gelesenen Nachrichten auf 'failed' — der Chip darf nie
     * endlos „✓✓ Zugestellt" bleiben.
     *
     * Begründung: Read-Receipts kommen ausschließlich über DIE Session, die
     * die Nachricht verschlüsselt getragen hat. Ist diese Session ungültig
     * (crypto.session_reset — egal ob Agent-Neustart, Takeover oder
     * Fingerprint-Abweichung), kann der Agent DIESE verschlüsselte Kopie nie
     * mehr lesen → 'failed' ist die ehrliche Darstellung, 'Zugestellt'
     * wäre die stille Lüge aus HERZ-Befund 8 (Chat tot, Chip blieb grün).
     * Nachrichten auf ANDEREN Agenten sind nicht betroffen im Schaden: trifft
     * deren Read später ein, gewinnt der Aufstieg (shouldApply: read > failed)
     * — der Sweep lügt nie dauerhaft.
     */
    fun sweepUnreadToFailed(statuses: Map<String, String>): Map<String, String> {
        var changed = false
        val swept = statuses.mapValues { (_, status) ->
            if (status == "queued" || status == "delivered") {
                changed = true
                "failed"
            } else {
                status
            }
        }
        return if (changed) swept else statuses
    }
}