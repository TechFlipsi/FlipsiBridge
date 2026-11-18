package com.hermesandroid.bridge.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W22 (PH-6): Verträge der GLOBALen Volltextsuche — Regeln in
 * [GlobalSearchRules] (JVM-rein; gradle-Unit-Test UND standalone-Kotlin-
 * Compile, W18-Verband). Integration gegen die ECHTE Engine ([SearchIndex])
 * pinnt die Verkettung index.search() → shapeResults().
 */
class GlobalSearchRulesTest {

    private fun hit(
        seq: Long,
        agentId: String = "alpha",
        text: String = "Treffer im Verlauf",
        score: Double = 1.0,
    ) = SearchIndex.Hit(
        agentId = agentId,
        localId = seq,
        msgId = "m-$seq",
        fromAgent = true,
        snippet = text,
        time = "07:52",
        score = score,
    )

    // ── Debounce + Tür-Gate ──────────────────────────────────────────────────

    @Test
    fun debounceIstMindestens250ms() {
        assertTrue(GlobalSearchRules.DEBOUNCE_MS >= 250L)
    }

    @Test
    fun normalizeQueryKollabiertWhitespace() {
        assertEquals("a b", GlobalSearchRules.normalizeQuery("  a   b  "))
        assertEquals("münzen korb", GlobalSearchRules.normalizeQuery("münzen\t\tkorb"))
        assertEquals("", GlobalSearchRules.normalizeQuery("   "))
    }

    @Test
    fun effectiveQueryGattertMindestLaenge() {
        assertNull(GlobalSearchRules.effectiveQuery(""))
        assertNull(GlobalSearchRules.effectiveQuery("   "))
        assertNull(GlobalSearchRules.effectiveQuery("a"))
        assertEquals("ab", GlobalSearchRules.effectiveQuery(" ab "))
        assertEquals("münzen korb", GlobalSearchRules.effectiveQuery(" münzen   korb "))
    }

    @Test
    fun queryTermsIdentischeRegelWieEngine() {
        assertEquals(listOf("münzen", "korb"), GlobalSearchRules.queryTerms(" Münzen   Korb! "))
        assertTrue(GlobalSearchRules.queryTerms("...").isEmpty())
    }

    // ── Ergebnis-Form: Kappung + Gruppierung ─────────────────────────────────

    @Test
    fun leeresTrefferSetBleibtLeer() {
        val result = GlobalSearchRules.shapeResults(emptyList())
        assertTrue(result.groups.isEmpty())
        assertEquals(0, result.total)
        assertFalse(result.capped)
    }

    @Test
    fun blankAgentIdsWerdenNichtGruppiert() {
        val result = GlobalSearchRules.shapeResults(listOf(hit(1L, agentId = ""), hit(2L, agentId = "")))
        assertTrue(result.groups.isEmpty())
        assertEquals(0, result.total)
    }

    @Test
    fun jeAgentMax10MitEhrlichemRest() {
        val hits = (1L..15L).map { seq -> hit(seq, agentId = "alpha") }
        val result = GlobalSearchRules.shapeResults(hits)
        assertEquals(1, result.groups.size)
        val group = result.groups[0]
        assertEquals(10, group.hits.size)
        assertEquals(15, group.totalForAgent)
        assertEquals(5, group.moreInAgent)
        assertEquals(10, result.total)
        // Je-Agent-Kappung = ehrliche „Gekürzt"-Fahne (Fuß-Zeile der UI).
        assertTrue(result.capped)
        // Reihenfolge der Eingabe bleibt (die Engine liefert bereits sortiert).
        assertEquals(1L, group.hits.first().localId)
        assertEquals(10L, group.hits.last().localId)
    }

    @Test
    fun budgetKapptTeilgruppeImGrenzfall() {
        val hits = ArrayList<SearchIndex.Hit>()
        for (seq in 1L..10L) hits.add(hit(seq, agentId = "alpha"))
        for (seq in 100L..104L) hits.add(hit(seq, agentId = "beta"))
        // Budget 12 < 15 Treffer: alpha voll (10), beta TEIL (2) — Rest ehrlich.
        val result = GlobalSearchRules.shapeResults(hits, limitTotal = 12, perAgent = 10)
        assertEquals(2, result.groups.size)
        assertEquals(12, result.total)
        assertTrue(result.capped)
        val beta = result.groups[1]
        assertEquals(2, beta.hits.size)
        assertEquals(5, beta.totalForAgent)
        assertEquals(3, beta.moreInAgent)
    }

    @Test
    fun gesamtBudget50KapptMitTeilgruppe() {
        val hits = ArrayList<SearchIndex.Hit>()
        // 12 Agenten × 5 Treffer = 60 → Budget 50 = 10 volle Gruppen (je 5);
        // Agenten 11/12 fliegen ganz raus (capped-Fahne + Fuß-Zeile ehrlich).
        for (agent in 1..12) {
            for (n in 1L..5L) {
                hits.add(hit(agent * 100L + n, agentId = "agent-$agent", score = 60.0 - hits.size))
            }
        }
        val result = GlobalSearchRules.shapeResults(hits)
        assertEquals(10, result.groups.size)
        assertEquals(50, result.total)
        assertTrue(result.capped)
        // Agenten 11/12 (jenseits des Budgets) fliegen GANZ — ihre Totals
        // zeigen keine Gruppe; die Summe der gezeigten Gruppen = 50.
        assertEquals(50, result.groups.sumOf { it.totalForAgent })
    }

    @Test
    fun exaktBudgetBleibtUngekuerzt() {
        val hits = (1L..50L).map { seq -> hit(seq, agentId = "a-${(seq - 1) / 5}") }
        val result = GlobalSearchRules.shapeResults(hits, limitTotal = 50, perAgent = 5)
        assertEquals(50, result.total)
        assertEquals(10, result.groups.size)
        assertFalse(result.capped)
    }

    // ── Snippet-Kürzung + Highlight ──────────────────────────────────────────

    @Test
    fun kurzesSnippetBleibtUnverkuertzt() {
        assertEquals("münzen im korb", GlobalSearchRules.truncateSnippet("münzen im korb"))
    }

    @Test
    fun langesSnippetKuerztHintenMitEllipse() {
        val long = "x".repeat(100)
        val cut = GlobalSearchRules.truncateSnippet(long)
        assertTrue(cut.length <= GlobalSearchRules.MAX_SNIPPET_CHARS + 1)
        assertTrue(cut.endsWith("…"))
        assertTrue(cut.startsWith("x"))
    }

    @Test
    fun vorhandeneEndEllipseWirdNichtGedoppelt() {
        val cut = GlobalSearchRules.truncateSnippet("a".repeat(80) + "…")
        assertEquals(1, cut.count { it == '…' })
    }

    @Test
    fun highlightsCaseInsensitiveMitUmlautenUndWortgrenzen() {
        val spans = GlobalSearchRules.highlightSpans(
            "…münzen im Korb sind bunt.",
            listOf("münzen", "korb"),
        )
        assertEquals(2, spans.size)
        assertEquals(1, spans[0].first) // „…"-Rand zählt NICHT mit, Treffer bei m
        assertTrue(spans[0].last > spans[0].first)
        // Case-insensitive: Original-Schreibweise „Korb" getroffen.
        assertEquals("münzen".length + 1 + " im ".length, spans[1].first)
        // Wort-Grenze: „bunt" enthält kein „b"-Querschuss in Nachbarwörter —
        // Term „b" trifft im Snippet KEINEN eigenständigen Treffer.
        val single = GlobalSearchRules.highlightSpans("…münzen im Korb.", listOf("b"))
        assertTrue(single.isEmpty())
    }

    @Test
    fun highlightSpansMergKeineOverlappe() {
        val spans = GlobalSearchRules.highlightSpans("hallo hallo", listOf("hallo", "hall"))
        // Beide Terme treffen dieselben Stellen — gemerged, keine Doppel.
        assertEquals(2, spans.size) // 2 Vorkommen, je 1 gemergter Span
        assertFalse(spans[0].intersects(spans[1]))
    }

    // ── Agent-Anzeige + Sprung-Fähigkeit ─────────────────────────────────────

    @Test
    fun katalogNameGewinntGegenAgentIdAnzeige() {
        assertEquals("J.A.R.V.I.S.", GlobalSearchRules.displayAgentLabel("jarvis-main", mapOf("jarvis-main" to "J.A.R.V.I.S.")))
        // Ehrliche agentId-Anzeige ohne Katalog: auf 14 Zeichen gekürzt + Ellipse.
        assertEquals("sehr_langer_ag…", GlobalSearchRules.displayAgentLabel("sehr_langer_agent_id_123", emptyMap()))
        assertEquals("?", GlobalSearchRules.displayAgentLabel("", emptyMap()))
    }

    @Test
    fun sprungNurBeiBekanntemAgentenMoeglich() {
        val anchor = hit(9L, agentId = "alpha")
        assertTrue(GlobalSearchRules.jumpResolvable(anchor, setOf("alpha", "beta")))
        assertFalse(GlobalSearchRules.jumpResolvable(anchor, setOf("beta")))
        assertFalse(GlobalSearchRules.jumpResolvable(hit(9L, agentId = ""), setOf("")))
    }

    @Test
    fun jumpAnchorTraegtAgentSeqMsgId() {
        val anchor = GlobalSearchRules.jumpAnchor(hit(9L, agentId = "alpha"))
        assertEquals("alpha", anchor.agentId)
        assertEquals(9L, anchor.localId)
        assertEquals("m-9", anchor.msgId)
    }

    // ── Integration: ECHTE Engine → Form ─────────────────────────────────────

    @Test
    fun integrationEngineSucheKapptJeAgent() {
        val index = SearchIndex()
        for (n in 1L..12L) index.index(
            SearchIndex.Indexable(
                agentId = "alpha",
                seq = n,
                msgId = null,
                fromAgent = true,
                text = "Münzen zählen klappt $n",
                time = "08:0$n",
            ),
        )
        for (n in 1L..3L) index.index(
            SearchIndex.Indexable(
                agentId = "beta",
                seq = 100L + n,
                msgId = null,
                fromAgent = false,
                text = "Münzen sind im Korb ($n)",
                time = "09:0$n",
            ),
        )
        val hits = index.search("münzen", limit = GlobalSearchRules.MAX_TOTAL_RESULTS)
        assertEquals(15, hits.size)
        val result = GlobalSearchRules.shapeResults(hits)
        // je Agent max 10: alpha 10 (+2), beta 3 — gesamt 13 angezeigt.
        assertEquals(13, result.total)
        assertEquals(2, result.groups.size)
        val alpha = result.groups.first { it.agentId == "alpha" }
        assertEquals(10, alpha.hits.size)
        assertEquals(12, alpha.totalForAgent)
        assertEquals(2, alpha.moreInAgent)
        val beta = result.groups.first { it.agentId == "beta" }
        assertEquals(3, beta.hits.size)
        assertEquals(3, beta.totalForAgent)
        assertTrue(result.capped)
        // Die Snippets der Engine bleiben Original-Schreibweise + Zeit trägt.
        assertTrue(beta.hits.all { it.snippet.contains("Münzen") })
    }

    @Test
    fun integrationUnbekannterTermBleibtLeer() {
        val index = SearchIndex()
        index.index(
            SearchIndex.Indexable(
                agentId = "alpha", seq = 1, msgId = null, fromAgent = true,
                text = "Münzen zählen", time = "08:00",
            ),
        )
        assertTrue(GlobalSearchRules.shapeResults(index.search("xyz")).groups.isEmpty())
    }

    private fun IntRange.intersects(other: IntRange): Boolean =
        first <= other.last && other.first <= last
}
