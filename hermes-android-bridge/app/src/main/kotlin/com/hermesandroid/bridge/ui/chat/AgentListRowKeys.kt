package com.hermesandroid.bridge.ui.chat

/**
 * W24 (Lane D): Reihen-Key der Agenten-Liste — die agentId NUR je non-blank
 * UND einmalig (W23-Befund: der Relay-Bestand trägt bewusst Alt-""-Zeilen;
 * ein doppelter Lazy-Key wirft die LazyColumn mit IllegalArgumentException),
 * alles andere = Positions-Fallback "row:<idx>" (stabil, solange die
 * abgeleitete Liste gleich bleibt; Agenten-Neuordnung = ehrlicher Wechsel).
 *
 * Datei-Begründ: EIGENE pure-JVM-Datei — der Beweis (AgentListRowKeysTest)
 * läuft ohne Compose-Klasspfad (und passt zur Rules-Datei-Konvention des
 * Bestands). Die Reihen-Zelle (AgentListRow) lebt im ChatScreen (privat, nur
 * dort konsumiert).
 */
internal object AgentListRowKeys {

    /**
     * Keys je Reih (Gleichordnung zur Eingabe-Liste): non-blank agentId =
     * "agent:<id>" nur beim ERSTEN Auftreten; Alt-""-Zeilen und jede
     * Wiederholung = "row:<index>" (Positions-Verband, nie ein Wurf).
     */
    fun keysOf(agentIds: List<String>): List<String> {
        val seen = HashSet<String>()
        return agentIds.mapIndexed { index, agentId ->
            if (agentId.isNotBlank() && seen.add("agent:$agentId")) {
                "agent:$agentId"
            } else {
                "row:$index"
            }
        }
    }
}
