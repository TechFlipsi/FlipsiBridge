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

    // ── HAKEN-FIX (17.11.2026): Monotonie + Persistenz-Regeln ────────────────
    //
    // Wurzel des 2-Haken-Bugs: delivered/read lebten NUR in der In-Memory-Map
    // (RelayClient._chatStatus, MAX_CHAT_STATUS = 500, Insertion-Order-Eviction)
    // — der Disk-Store trug nur den Sendezeit-Snapshot. Folgen: (a) >500
    // msg_ids (Medien-Chunks!) evicten laufende Einträge → 2 grüne Haken kippen
    // auf 1; (b) App-Neustart → alles fällt auf den Snapshot zurück. Diese
    // Regeln geben der Fusion/Eviction/Side-Car-Faltordnung einen MONOTONEN
    // Rang: ein fehlender/evicteter SICHTIGER (Live-)Eintrag drückt den Chip
    // NIE unter den höchsten bekannten Stand.

    /** true = Status aus dem bekannten Set (Side-Car-Filter: Korrupte Zeilen
     *  mit Fremd-Wert kommen NIE in die Restore-Faltung). */
    fun isKnown(status: String): Boolean = status in KNOWN

    private val KNOWN = setOf("queued", "delivered", "read", "failed")

    /**
     * Eviction-Priorität (niedrigster Rang fliegt ZUERST raus): flüchtige
     * Zwischenstände (queued → delivered) gehen vor den terminalen Ständen —
     * failed (Sweep-Honesty, HERZ-Befund 8) und read (der Endstand, den der
     * Side-Car zusätzlich sicherstellt) werden so lange wie möglich gehalten.
     * Insertion-Order bleibt der Tie-Break (Parität zum alten Verhalten).
     */
    fun ejectRank(status: String): Int = when (status) {
        "queued" -> 0
        "delivered" -> 1
        "failed" -> 2
        else -> 3  // read (und defensiv Unbekannte — evictbar, aber zuletzt)
    }

    /**
     * Eviction-Kandidaten, RANG-PRIORISIERT (niedrigster [ejectRank] zuerst,
     * stabiler Insertion-Order-Tie-Break). RelayClient.handleChatStatusFrame
     * holt bei Cap-Overflow die Kopf-Kandidaten, schreibt sie VOR dem Rauswurf
     * in den Side-Car (Write-Back) und entfernt sie erst dann — 'read' wird
     * praktisch nie ungepersisted evictet.
     */
    fun evictionVictims(statuses: Map<String, String>): List<String> =
        statuses.entries
            .sortedBy { ejectRank(it.value) }  // stable: Insertion-Order-Tie-Break
            .map { it.key }

    /**
     * PAARWEISE Monotonie-Fusion (Symmetrisch!): nimmt den HÖHEREN der beiden
     * bekannten Stände — mit der shouldApply-Spiegelung:
     * - 'failed' (Session-Reset-Sweep) blockt queued/delivered (Herz-Befund 8),
     *   verliert aber gegen ein reales 'read' (Selbstheilung, Parität).
     * - NULL beiträgt nie (fehlende/evictete Quelle kann NICHT mehr senken
     *   als den Rest der Quellen — Chip-Monotonie).
     * - Unbekannte Status gewinnen NIE (rank -1, Parität zu shouldApply).
     */
    fun fuse(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        a == "failed" -> if (b == "read") "read" else "failed"
        b == "failed" -> if (a == "read") "read" else "failed"
        shouldApply(a, b) -> b
        else -> a
    }

    /**
     * N-äre Fusion der Chip-Quellen (statusOfMessage): chatStatus[msg_id]
     * (Live, evictbar) ⊔ restoredStatusById (Platte: Side-Car + Sendezeit-
     * Snapshot als Höchststand) ⊔ persistenter High-Water je local_id.
     * Monotonie: das Maximum über die NICHT-Null-Quellen — eine Quelle, die
     * verspringt (Eviction in der Live-Map), senkt den Chip nicht.
     */
    fun highestOf(sources: List<String?>): String? =
        sources.fold(null as String?) { acc, status -> fuse(acc, status) }

    /**
     * Side-Car-Faltordnung: chronologische (msg_id, status)-Zeilen → je
     * msg_id der HÖCHSTE bekannte Stand (Paarweise [fuse]). Ein späteres
     * 'queued' nach 'read' (Session/Spool-Replay nach Neustart) senkt den
     * Faltstand NICHT — dieselbe Monotonie wie shouldApply live. Duplikat-
     * Zeilen (CAS-Retries) sind fold-idempotent.
     */
    fun foldRows(rows: List<Pair<String, String>>): Map<String, String> {
        val folded = LinkedHashMap<String, String>()
        for ((msgId, status) in rows) {
            if (msgId.isBlank()) continue
            folded[msgId] = fuse(folded[msgId], status) ?: continue
        }
        return folded
    }
}