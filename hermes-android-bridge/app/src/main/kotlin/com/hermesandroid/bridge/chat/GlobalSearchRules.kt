package com.hermesandroid.bridge.chat

/**
 * W22 (PH-6): Globale Volltextsuche über ALLE Agenten-Verläufe — reine
 * Regeln, JVM-getestet in [com.hermesandroid.bridge.chat.GlobalSearchRulesTest].
 * Die Such-UI (ui/chat/GlobalSearchResults.kt) rendert nur; das Suchfeld
 * bleibt EINmalig die Chat-Suchleiste (KEIN-DOPPEL — hier kein zweites
 * Eingabefeld, hier keine Kopie einer Suchlogik).
 *
 * ## Verband
 * - Quelle ist die Speisung aus dem RAM-Index: der Aufrufer holt Treffer via
 *   [ChatHistoryStore.searchAll] (= [SearchIndex.search], Präfix/AND,
 *   score-sortiert) und legt sie hier in Form ([shapeResults]): je Agent max
 *   [MAX_PER_AGENT] Treffer, gesamt max [MAX_TOTAL_RESULTS] Gruppen-Budget
 *   (Parität zum Anzeige-Cap der Chat-Suche, 50).
 * - Tür-Gate [effectiveQuery]: Whitespace-Normalisierung ([normalizeQuery])
 *   + Mindest-Länge [MIN_QUERY_LENGTH] — 1-Buchstaben-Queries treffen den
 *   Präfix-Index breit und sind unter der lokalen Trefferliste nur Lärm.
 * - Debounce [DEBOUNCE_MS] (= 250): der Tipp-Rhythmus koppelt an den Such-
 *   lauf (LaunchedEffect(query) + delay — Compose-Kapsel in der UI-Datei,
 *   hier pinnen nur Konstante + Test den Soll-Wert ≥ 250 ms).
 * - Snippet-Kürzung [truncateSnippet]: die Engine-Snippets (120-Zeichen-
 *   Fenster, ellipsize) werden für die treffer-/zeilendichte UI gekürzt —
 *   Kürzung NUR am Ende („…“), der Treffer-Fenster-Kern bleibt vorn.
 * - Highlight-Spannen [highlightSpans]: case-insensitive Wort-Grenzen-Treffer
 *   der Query-Terme ([queryTerms]) im Snippet — für die Ember-Pille im UI.
 *
 * Ehrlichkeit: ein unprimter/leerer Index (entryCount = 0) zeigt die UI
 * einen ehrlichen Leerzustand („Noch kein Verlauf indiziert“) — NIE
 * Fake-Treffer. Die Zustands-Zweige leben in der UI-Datei; hier nur Formen.
 */
object GlobalSearchRules {

    /** Debounce der globalen Suche (Brief-Soll: ≥ 250 ms). */
    const val DEBOUNCE_MS = 250L

    /** Mindest-Länge der (normalisierten) Query für den globalen Suchlauf. */
    const val MIN_QUERY_LENGTH = 2

    /** Anzeige-Budget GESAMT (Parität: SearchIndex.DEFAULT_RESULT_LIMIT). */
    const val MAX_TOTAL_RESULTS = 50

    /** Anzeige-Budget je Agent (Gruppen-Brief-Deckel). */
    const val MAX_PER_AGENT = 10

    /** UI-Zeilen-Kürzung des Engine-Snippets (Engine-Fenster = 120). */
    const val MAX_SNIPPET_CHARS = 72

    /** Ehrliche Agent-Anzeige ohne Katalog-Namen (agentId-gekürzt). */
    const val MAX_AGENT_LABEL_CHARS = 14

    /** Sprung-Anker eines globalen Treffers — (agentId, seq) → ChatMessage.id
     *  macht der ChatScreen-Verband (restore/backfill-Bindung, Hook-Report). */
    data class JumpAnchor(
        val agentId: String,
        val localId: Long,      // = Entry.seq (Store-Zeilen-Identität)
        val msgId: String?,     // = Broker-msg_id (null bei Alt-Daten)
    )

    /** Agent-Gruppe der Ergebnis-Form: Treffer-Liste (gekappt) + ehrliche
     *  Gesamtzahl + Rest-Anzeige „+n weitere“. */
    data class AgentGroup(
        val agentId: String,
        val hits: List<SearchIndex.Hit>,
        val totalForAgent: Int,
        val moreInAgent: Int,
    )

    /** Ergebnis-Form: Gruppen in Treffer-Erst-Reihenfolge (+score), gesamt-
     *  Zahl = ANGEZEIGTE Treffer nach Cap, [capped] = es blieb etwas außen. */
    data class Result(
        val groups: List<AgentGroup>,
        val total: Int,
        val capped: Boolean,
    )

    private val WHITESPACE_RUN = Regex("\\s+")

    /** Leerraum-Normalisierung: trim + Whitespace-Ketten → je 1 Space. */
    fun normalizeQuery(query: String): String = query.trim().replace(WHITESPACE_RUN, " ")

    /** AND-Such-Terme der Query (identische Regeln wie der Index). */
    fun queryTerms(query: String): List<String> =
        SearchIndex.tokenize(normalizeQuery(query)).distinct()

    /**
     * Tür-Gate der globalen Suche: null = Abschnitt inaktiv (keine Query /
     * unter Mindest-Länge / nur Leerraum) — die UI rendert dann NICHTS
     * (Abschnitt existiert nur mit aktivem Suchvorgang, KEIN-DOPPEL).
     */
    fun effectiveQuery(query: String): String? {
        val normalized = normalizeQuery(query)
        if (normalized.length < MIN_QUERY_LENGTH) return null
        return normalized
    }

    /**
     * Ergebnis-Form über die Engine-Treffer (score-sortiert, Reihenfolge
     * bleibt): je Agent die ersten [perAgent]-Treffer, dann Gesamtbudget
     * [limitTotal] — Grenzfall-Gruppe wird TEILGekappt (Budget bleibt
     * ehrlich: `total` zählt nur ANGEZEIGTE Treffer).
     */
    fun shapeResults(
        hits: List<SearchIndex.Hit>,
        limitTotal: Int = MAX_TOTAL_RESULTS,
        perAgent: Int = MAX_PER_AGENT,
    ): Result {
        if (nothingGroupable(hits)) return Result(emptyList(), 0, false)
        // 1) je Agent kappen — Reihenfolge der Treffer bleibt (score ↓).
        val totals = HashMap<String, Int>()
        for (hit in hits) totals[hit.agentId] = (totals[hit.agentId] ?: 0) + 1
        val perAgentCapped = totals.values.any { it > perAgent }
        val perAgentFirst = LinkedHashMap<String, MutableList<SearchIndex.Hit>>()
        for (hit in hits) {
            val list = perAgentFirst.getOrPut(hit.agentId) { ArrayList() }
            if (list.size < perAgent) list.add(hit)
        }
        val agentOrder = perAgentFirst.keys.toList() // Erst-Erscheinen = engine-Ordnung
        var budget = limitTotal
        var capped = false
        val groups = ArrayList<AgentGroup>(agentOrder.size)
        for (agentId in agentOrder) {
            if (budget <= 0) capped = true
            if (budget <= 0) break
            val all = perAgentFirst.getValue(agentId)
            val shown = if (all.size > budget) {
                capped = true
                all.subList(0, budget)
            } else {
                all
            }
            budget -= shown.size
            val totalForAgent = totals.getValue(agentId)
            groups.add(
                AgentGroup(
                    agentId = agentId,
                    hits = shown.toList(),
                    totalForAgent = totalForAgent,
                    moreInAgent = totalForAgent - shown.size,
                ),
            )
        }
        return Result(
            groups = groups,
            total = groups.sumOf { it.hits.size },
            capped = capped || perAgentCapped,
        )
    }

    /** Hart-Gate: Treffer mit BLANKEM agentId (Alt-Daten, gson-Null-Härtung
     *  im Store) werden NICHT gruppiert (keine Fake-Gruppe „“). */
    private fun nothingGroupable(hits: List<SearchIndex.Hit>): Boolean =
        hits.all { it.agentId.isBlank() }

    /** Snippet-Kürzung auf [max] Zeichen — Kürzung NUR hinten („…“), das
     *  Trefferfenster steht vorn (Engine-Snippet-Fenster ist schon schmal). */
    fun truncateSnippet(snippet: String, max: Int = MAX_SNIPPET_CHARS): String {
        if (snippet.length <= max) return snippet
        val cut = snippet.take(max)
        // Ellipse nicht doppeln: Engine hat je RAND „…“; hinten dranhängen
        // nur, wenn das gekürzte Ende NICHT bereits in einer Ellipse endet.
        val suffix = if (cut.endsWith(ELLIPSIS)) "" else ELLIPSIS
        return cut.trimEnd() + suffix
    }

    /** Case-insensitive Treffer-Streubereiche der [terms] im Snippet (mit
     *  Wort-Grenzen: kein Querschuss in Nachbarwörtern). Reihenfolge aufsteigend. */
    fun highlightSpans(snippet: String, terms: List<String>): List<IntRange> {
        if (terms.isEmpty()) return emptyList()
        val lower = snippet.lowercase(java.util.Locale.ROOT)
        val out = ArrayList<IntRange>()
        for (term in terms) {
            if (term.isEmpty()) continue
            var from = 0
            while (true) {
                val at = lower.indexOf(term, from)
                if (at < 0) break
                // Wort-Grenzen: Nachbarzeichen KEIN Buchstabe/Ziffer („…“-Rand
                // des Engine-Snippets zählt als Grenze — kein Buchstabe).
                val beforeIsWord = at > 0 && lower[at - 1].isLetterOrDigit()
                val end = at + term.length
                val afterIsWord = end < lower.length && lower[end].isLetterOrDigit()
                if (!beforeIsWord && !afterIsWord) out.add(at until end)
                from = at + term.length
            }
        }
        // Overlap-freie, aufsteigende Streubereiche (Term-Reihenfolge kann sich
        // im Snippet kreuzen — Merge gegen doppelte UI-Spannen).
        val sorted = out.sortedBy { it.first }
        val merged = ArrayList<IntRange>()
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last) {
                val end = maxOf(last.last, range.last)
                merged[merged.lastIndex] = last.first..end
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    /** Ehrliche Agent-Anzeige: Katalog-Name gewinnt (nicht-blank), sonst
     *  agentId-gekürzt, blank = „?“-Kürzel (Avatar fällt auf den Initial). */
    fun displayAgentLabel(agentId: String, names: Map<String, String>): String {
        names[agentId]?.takeIf { it.isNotBlank() }?.let { return it }
        if (agentId.isBlank()) return "?"
        return if (agentId.length <= MAX_AGENT_LABEL_CHARS) {
            agentId
        } else {
            agentId.take(MAX_AGENT_LABEL_CHARS) + ELLIPSIS
        }
    }

    /** true = der Sprung kann WIRKLICH landen (Agent im Katalog des Bildschirms);
     *  sonst bleibt die Zeile ehrlich nicht-klickbar (AKTIONEN-MIT-ERGEBNIS). */
    fun jumpResolvable(hit: SearchIndex.Hit, knownAgentIds: Set<String>): Boolean =
        hit.agentId.isNotBlank() && hit.agentId in knownAgentIds

    /** Sprung-Anker aus einem Engine-Treffer. */
    fun jumpAnchor(hit: SearchIndex.Hit): JumpAnchor =
        JumpAnchor(hit.agentId, hit.localId, hit.msgId)

    private const val ELLIPSIS = "…"
}
