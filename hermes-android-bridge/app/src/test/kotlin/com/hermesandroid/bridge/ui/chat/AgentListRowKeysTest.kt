package com.hermesandroid.bridge.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W24 Lane D: JVM-Beweis des Agenten-Listen-Reihen-Keys — die agentId NUR je
 * non-blank UND einmalig (W23-Befund: Alt-""-Zeilen im Relay-Bestand; ein
 * Doppelt-Key wirft die LazyColumn), alles andere Positions-Fallback.
 */
class AgentListRowKeysTest {

    /** Leer = keine Reihen = keine Keys. */
    @Test
    fun emptyList() {
        assertEquals(emptyList<String>(), AgentListRowKeys.keysOf(emptyList<String>()))
    }

    /** Non-blank agentIds → stabile agent-Keys je Reih (W24-Verband). */
    @Test
    fun nonBlankIdsBecomeAgentKeys() {
        assertEquals(
            listOf("agent:a1", "agent:a2", "agent:a3"),
            AgentListRowKeys.keysOf(listOf("a1", "a2", "a3")),
        )
    }

    /** Alt-""-Zeilen → Positions-Fallback "row:<idx>", identisch je Reih. */
    @Test
    fun blankIdsFallBackToPosition() {
        assertEquals(
            listOf("agent:a1", "row:1", "row:2"),
            AgentListRowKeys.keysOf(listOf("a1", "", "")),
        )
        assertEquals(listOf("row:0"), AgentListRowKeys.keysOf(listOf(" ")))
    }

    /**
     * JE WIEDERHOLUNG (auch non-blank) = Positions-Fallback — ein doppelter
     * "agent:"-Key würde die LazyColumn WERFEN (IllegalArgumentException);
     * der zweite Auftritt fällt ehrlich auf die Position zurück.
     */
    @Test
    fun duplicateAgentIdsNeverDuplicateKeys() {
        assertEquals(
            listOf("agent:a1", "row:1", "agent:a2"),
            AgentListRowKeys.keysOf(listOf("a1", "a1", "a2")),
        )
        assertEquals(
            listOf("agent:x", "row:1", "row:2"),
            AgentListRowKeys.keysOf(listOf("x", "x", "x")),
        )
    }

    /** Key-Listen sind immer eindeutig (LazyColumn-Vertrag je Reih). */
    @Test
    fun keysAreAlwaysUnique() {
        for (ids in listOf(
            listOf("a", "a", "b", "b", "", " "),
            listOf("", "", "", "a", "a"),
            listOf("z", "", "z", ""),
        )) {
            val keys = AgentListRowKeys.keysOf(ids)
            assertEquals(keys.size, keys.toSet().size)
            assertEquals(ids.size, keys.size)
        }
    }
}
