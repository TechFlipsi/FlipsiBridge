package com.hermesandroid.bridge.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PH-6-REST: Verträge des RAM-Volltext-Index über ALLE Agenten-Verläufe —
 * Build/Query/Ranking/Cap/Inkrementell (JVM-rein; gradle-Unit-Test UND
 * standalone-Kotlin-Compile, W18-Verband).
 *
 * Gedacht als Kontrakt-Schicht: SearchIndex ist die Engine, ChatHistoryStore
 * speist sie (Restore/append/merge/clear) — diese Tests pinnen die Engine,
 * ChatHistoryStoreTest/MergeTest decken die Speisung mit ab.
 */
class SearchIndexRules {

    private fun msg(
        seq: Long,
        agentId: String = "alpha",
        text: String,
        msgId: String? = null,
        fromAgent: Boolean = true,
        time: String = "07:52",
    ) = SearchIndex.Indexable(
        agentId = agentId,
        seq = seq,
        msgId = msgId,
        fromAgent = fromAgent,
        text = text,
        time = time,
    )

    // ── Build + Query ────────────────────────────────────────────────────────

    @Test
    fun exactMatchCarriesAgentLocalIdMsgSnippetTime() {
        val index = SearchIndex()
        index.index(msg(1_000L, agentId = "alpha", text = "Münzen zählen klappt",
            msgId = "m-1", time = "07:52"))
        index.index(msg(2_000L, agentId = "beta", text = "ganz anderes Thema"))

        val hits = index.search("münzen")
        assertEquals(1, hits.size)
        val hit = hits[0]
        assertEquals("alpha", hit.agentId)
        assertEquals(1_000L, hit.localId)
        assertEquals("m-1", hit.msgId)
        assertTrue(hit.snippet.contains("Münzen")) // Original-Schreibweise im Snippet
        assertEquals("07:52", hit.time)
        assertTrue(hit.fromAgent)
    }

    @Test
    fun umlautCaseInsensitive() {
        val index = SearchIndex()
        index.index(msg(1L, text = "münzen im korb"))
        assertTrue(index.search("MÜNZEN").size == 1)
        index.index(msg(2L, agentId = "beta", text = "Über morgen"))
        assertTrue(index.search("ÜBER").size == 1)
    }

    @Test
    fun wordPrefixSearchMatches() {
        val index = SearchIndex()
        index.index(msg(1L, text = "Hallo Welt"))
        assertEquals(1, index.search("hal").size)
        assertEquals(1, index.search("hallo").size) // exakt
        assertEquals(0, index.search("halx").size)  // kein Präfix
    }

    @Test
    fun tokenizationSplitsOnNonWordAndLowers() {
        assertEquals(listOf("münzen", "aktion"), SearchIndex.tokenize("MÜNZEN-Aktion"))
        assertEquals(emptyList<String>(), SearchIndex.tokenize("   "))
    }

    @Test
    fun multiTermQueryIsAndSemantics() {
        val index = SearchIndex()
        index.index(msg(1L, text = "grüne münzen"))
        index.index(msg(2L, agentId = "beta", text = "grüne äpfel"))

        val both = index.search("grüne münzen")
        assertEquals(1, both.size)
        assertEquals("alpha", both[0].agentId)

        assertEquals(1, index.search("münzen grüne").size) // Reihenfolge egal (AND)
        assertEquals(0, index.search("grüne bananen").size) // Term ohne Match kippt
    }

    // ── Ranking (Relevanz/Zeit) ──────────────────────────────────────────────

    @Test
    fun equalExactRelevanceNewerRanksFirstAndNoFalsePrefixNoise() {
        val index = SearchIndex()
        index.index(msg(1_000L, text = "kurz haltbar"))                 // „hall…“-Start: trifft „hallo“ NICHT
        index.index(msg(2_000L, agentId = "beta", text = "hallo haus")) // exakt „hallo“
        index.index(msg(3_000L, agentId = "gamma", text = "haltbar hallo auch")) // exakt „hallo“

        val hits = index.search("hallo")
        assertTrue(hits.size == 2)
        // beta und gamma beide exakt (gleiche Relevanz) → Zeit-Faktor als
        // tie-break: gamma (neuerer seq) zuerst. „haltbar“ startet mit „halt“,
        // NICHT mit „hallo“ → alpha (reines Prefix-Noise-Fall) nie Treffer.
        assertEquals("gamma", hits[0].agentId)
        assertEquals("beta", hits[1].agentId)
        assertFalse(hits.any { it.agentId == "alpha" })
    }

    @Test
    fun prefixQueryRanksExactBeatsPurePrefix() {
        val index = SearchIndex()
        index.index(msg(1_000L, text = "halbtag heim"))
        index.index(msg(2_000L, agentId = "beta", text = "hal hallo")) // exakt „hal“

        val hits = index.search("hal")
        assertEquals(2, hits.size)
        assertEquals("beta", hits[0].agentId)  // exakter Token-Match gewinnt
        assertEquals("alpha", hits[1].agentId) // reine Prefix-Treffer danach
    }

    @Test
    fun equalRelevanceNewerSeqRanksAboveOlder() {
        val index = SearchIndex()
        index.index(msg(1_000L, text = "wort alt"))
        index.index(msg(2_000L, agentId = "beta", text = "wort neu"))

        val hits = index.search("wort")
        assertEquals(2, hits.size)
        assertEquals("beta", hits[0].agentId) // Zeit-Faktor: neuerer zuerst
        assertEquals(2_000L, hits[0].localId)
    }

    @Test
    fun limitCapsOutputOnlyNotRankingSweep() {
        val index = SearchIndex()
        for (i in 1..4L) index.index(msg(i * 1_000L, text = "wort $i"))
        val hits = index.search("wort", limit = 2)
        assertEquals(2, hits.size)
        // beste Scores = neueste Docs (Relevanz-Gleichstand → Zeit-Faktor):
        assertEquals(listOf(4_000L, 3_000L), hits.map { it.localId })
    }

    // ── Cap (FIFO-Eviction) ──────────────────────────────────────────────────

    @Test
    fun capEvictsOldestDocsFifo() {
        val index = SearchIndex(maxEntries = 2)
        index.index(msg(1L, text = "alt eins"))
        index.index(msg(2L, agentId = "beta", text = "mitte zwei"))
        index.index(msg(3L, agentId = "gamma", text = "neu drei"))

        assertEquals(2, index.entryCount())
        assertEquals(0, index.search("alt eins").size)  // älteste geflogen
        assertEquals(1, index.search("mitte zwei").size) // Mittel-Doc überlebt
        assertEquals(1, index.search("neu drei").size)   // neuestes überlebt
        assertEquals(setOf("beta", "gamma"), index.knownAgents())
    }

    // ── Inkrementell (Replace/Remove/Clear) ──────────────────────────────────

    @Test
    fun reIndexSameRowReplacesText() {
        val index = SearchIndex()
        index.index(msg(1L, text = "wort alt"))
        index.index(msg(1L, text = "wort neu")) // Edited-Fall: gleiche (agent, seq)

        assertEquals(0, index.search("alt").size)
        assertEquals(1, index.search("neu").size)
        assertEquals(1, index.entryCount())
    }

    @Test
    fun removeDropsSingleRow() {
        val index = SearchIndex()
        index.index(msg(1L, text = "eins zwei"))
        index.index(msg(2L, agentId = "beta", text = "eins drei"))

        assertTrue(index.remove("alpha", 1L))
        assertEquals(1, index.search("eins").size)   // beta bleibt
        assertFalse(index.remove("alpha", 1L))       // schon raus
    }

    @Test
    fun clearAgentDropsOnlyThatAgent() {
        val index = SearchIndex()
        index.index(msg(1L, text = "alpha wort"))
        index.index(msg(2L, agentId = "beta", text = "beta wort"))
        index.clearAgent("alpha")

        assertEquals(0, index.search("alpha wort").size)
        assertEquals(1, index.search("beta wort").size)
        assertEquals(setOf("beta"), index.knownAgents())
    }

    // ── Snippet ──────────────────────────────────────────────────────────────

    @Test
    fun snippetEllipsizesAtBothEdgesAndKeepsOriginalCase() {
        val filler = "x".repeat(300)
        val text = filler + " Trefferwort " + "y".repeat(300)
        val index = SearchIndex()
        index.index(msg(1L, text = text))

        val snippet = index.search("trefferwort").first().snippet
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("Trefferwort"))
        assertTrue(snippet.length < text.length)
    }

    @Test
    fun shortTextSnippedWithoutEllipsis() {
        val index = SearchIndex()
        index.index(msg(1L, text = "kurz"))
        val snippet = index.search("kurz").first().snippet
        assertEquals("kurz", snippet)
    }

    // ── Randfälle ────────────────────────────────────────────────────────────

    @Test
    fun emptyOrUnknownQueryYieldsNothing() {
        val index = SearchIndex()
        assertEquals(0, index.search("").size)
        assertEquals(0, index.search("   ").size)
        index.index(msg(1L, text = "wort"))
        assertEquals(0, index.search("").size)
        assertEquals(0, index.search("wort unbekannt").size) // AND streng
        assertEquals(0, index.search("nirgends").size)
    }

    @Test
    fun emptyResultLimitYieldsNothing() {
        val index = SearchIndex()
        index.index(msg(1L, text = "wort"))
        assertTrue(index.search("wort", limit = 0).isEmpty())
    }
}
