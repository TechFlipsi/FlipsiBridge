package com.hermesandroid.bridge.chat

/**
 * PH-6-REST: Volltext-Suchindex über ALLE Agenten-Verläufe.
 *
 * Die Screen-Suche (ChatSearchRules) filtert nur die GELADENEN Bubbles des
 * offenen Chat-Screens. PH-6-REST verlangt Volltextsuche ÜBER ALLE
 * Agenten-Verlauf-Dateien — auch das, was gerade NICHT auf dem Bildschirm
 * liegt. Dieser Index lebt im RAM (inverted index token → Doc-IDs), wird vom
 * [ChatHistoryStore] gespeist:
 *
 * - Build beim Restore: load()/loadAll() indexieren das Disk-Ergebnis
 *   (lazy/hintergrund-freundlich — die Aufrufer laufen ohnehin auf IO-Threads;
 *   ChatHistoryStore.ensureSearchIndexAsync primt zusätzlich beim App-Start).
 * - Inkrementell: append() indexiert jede persistierte Bubble einzeln,
 *   mergeBackfill/cap-Kürzung spiegeln die Agent-Scheibe (Disk-Wahrheit).
 * - Tokenisierung bewusst einfach (Soll-Aufnahme): lowercase (Locale.ROOT —
 *   Umlaute case-insensitiv, „MÜNZEN“ trifft „münzen“), Wort-Grenzen
 *   (\p{L}\p{N}), KEIN N-Gramm, Wort-Präfix-Suche („hal“ trifft „hallo“).
 *   Token-Deckel 64 Zeichen gegen pathologische Riesen-Wörter.
 * - Such-Vertrag: Mehrwort-Query = AND je Wort; Treffer = exaktes Token ODER
 *   Token mit Query-Präfix.
 * - Ranking [score] = Relevanz (exakt 2.0 > Präfix 1.0, +TF-Beitrag, cap 3)
 *   + Zeit-Faktor (0.0 ältester Kandidat … 1.0 neuester Kandidat, linear über
 *   Rang — robust gegen Index-Eviction). Sortierung: score ↓, dann seq ↓.
 * - Speicher-Cap [maxEntries] Docs je Index (FIFO über Insertion-Order):
 *   älteste Docs fliegen bei Überlauf raus; der nächste Load/merge spiegelt
 *   die Disk-Wahrheit zurück (Cap = RAM-Deckel, nie Datenverlust auf Platte).
 * - JVM-rein (kein android-Import): testbar in SearchIndexRulesTest (gradle-
 *   Unit-Tests UND standalone-Kotlin-Compile, W18-Verband).
 *
 * Snippet: Fenster um die ERSTE gematchte Stelle im ORIGINAL-Text (case
 * bleibt erhalten), „…“ an den Rändern bei Zuschnitt.
 */
class SearchIndex(private val maxEntries: Int = MAX_INDEX_ENTRIES) {

    /** Index-Anfrage des Stores (Entkopplung von den GUI-Typen). */
    data class Indexable(
        val agentId: String,
        val seq: Long,          // Store-Zeilen-Identität je Agent (Entry.seq)
        val msgId: String?,     // Broker-msg_id (null bei Alt-Daten/Send-Fehler)
        val fromAgent: Boolean,
        val text: String,
        val time: String,       // „HH:mm“-Anzeigezeit aus dem Entry
    )

    /**
     * PH-6-Treffer (agent, localId = Entry.seq, msg_id, Text-Snippet, Zeit).
     * [fromAgent] für KI-Badge, [score] für Debug/Sortierung beim Aufrufer.
     */
    data class Hit(
        val agentId: String,
        val localId: Long,
        val msgId: String?,
        val fromAgent: Boolean,
        val snippet: String,
        val time: String,
        val score: Double,
    )

    internal class Doc(
        val agentId: String,
        val seq: Long,
        val msgId: String?,
        val fromAgent: Boolean,
        val text: String,
        val time: String,
        val tokens: Map<String, Int>, // token → Vorkommen (tf)
    )

    private class TokenSpan(val token: String, val start: Int, val endExclusive: Int)

    private val monitor = Any()
    /** Insertion-Order = FIFO (älteste Doc zuerst raus bei Cap-Überlauf). */
    private val docs = LinkedHashMap<Long, Doc>()
    private val postings = HashMap<String, HashSet<Long>>()
    private val docIdsByAgent = HashMap<String, HashSet<Long>>()
    private var nextDocId = 1L

    companion object {
        /** RAM-Deckel: 20k Docs ≻ Alltag (500 je Agent × ~10 Agenten = 5k). */
        const val MAX_INDEX_ENTRIES = 20_000

        /** AND-Terme je Query (Deckel gegen pathologische Queries). */
        const val MAX_QUERY_TERMS = 8

        /** Token-Länge-Deckel (Riesen-Worte truncated — Snibble-Schutz). */
        const val MAX_TOKEN_CHARS = 64

        /** Zielbreite des Snippet-Fensters je Treffer. */
        const val SNIPPET_WIDTH = 120

        /** Anzeige-Default-Cap der Trefferliste (Suchlauf selbst komplett). */
        const val DEFAULT_RESULT_LIMIT = 50

        const val WEIGHT_EXACT = 2.0
        const val WEIGHT_PREFIX = 1.0
        const val WEIGHT_TF = 0.1
        const val TF_CAP = 3
        const val WEIGHT_RECENCY = 0.5

        /** Tokenisierung (Original-Text, span-erhaltend): Wort-Grenzen über
         * [Char.isLetterOrDigit] (deckt \p{L}/\p{N}), Token = lowercase
         * [java.util.Locale.ROOT] (kein Türk-İ-Zufall), Deckel
         * [MAX_TOKEN_CHARS] — der SPAN bleibt im Original (Snippet-Fenster
         * mit echter Schreibweise). PRIVAT: Rückgabetyp TokenSpan ist
         * klassen-intern; Aufrufer nutzen [tokenize]. */
        private fun spans(text: String): List<TokenSpan> {
            val out = ArrayList<TokenSpan>()
            var start = -1
            for (i in text.indices) {
                val ch = text[i]
                if (ch.isLetterOrDigit()) {
                    if (start < 0) start = i
                } else if (start >= 0) {
                    emit(out, text, start, i)
                    start = -1
                }
            }
            if (start >= 0) emit(out, text, start, text.length)
            return out
        }

        private fun emit(out: MutableList<TokenSpan>, text: String, from: Int, to: Int) {
            var end = to
            if (end - from > MAX_TOKEN_CHARS) end = from + MAX_TOKEN_CHARS
            val token = text.substring(from, end).lowercase(java.util.Locale.ROOT)
            if (token.isNotEmpty()) out.add(TokenSpan(token, from, to))
        }

        /** Query-/Doc-Tokenisierer (identische Regeln — Pflicht für AND-Vertrag). */
        fun tokenize(text: String): List<String> = spans(text).map { it.token }
    }

    // ── Aufbau (Build + inkrementell) ────────────────────────────────────────

    /** Einen Eintrag indexieren (inkrementell, append-Pfad). Gleiche
     *  (agentId, seq) ersetzt (Re-Index nach Edited/Merge). */
    fun index(msg: Indexable): Boolean = synchronized(monitor) { indexInternal(msg) }

    /** Agent-Scheibe ersetzen (load/mergeBackfill = Disk-Wahrheit). */
    fun indexAgent(agentId: String, msgs: List<Indexable>) = synchronized(monitor) {
        clearAgentInternal(agentId)
        msgs.forEach(::indexInternal)
    }

    /** Eine Store-Zeile aus dem Index nehmen (Cap-Kürzung spiegelt sich hier). */
    fun remove(agentId: String, seq: Long): Boolean = synchronized(monitor) {
        removeInternal(agentId, seq)
    }

    /** Alle Docs eines Agenten („Chat löschen“ spiegelt sich hier). */
    fun clearAgent(agentId: String) = synchronized(monitor) { clearAgentInternal(agentId) }

    /** Voll-Rebuild-Vorbereitung (loadAll): alles weg. */
    fun clear() = synchronized(monitor) {
        docs.clear()
        postings.clear()
        docIdsByAgent.clear()
        nextDocId = 1L
    }

    fun entryCount(): Int = synchronized(monitor) { docs.size }
    fun isEmpty(): Boolean = synchronized(monitor) { docs.isEmpty() }
    fun knownAgents(): Set<String> = synchronized(monitor) { HashSet(docIdsByAgent.keys) }

    // ── Suche (PH-6-Soll: query → agent/localId/msg_id/Snippet/Zeit) ─────────

    /**
     * Volltextsuche über den RAM-Index. Mehrwort-Query = AND; leere Query,
     * unbekannte Terme oder unprimter Index = leere Liste. Der Cap [limit]
     * schneidet NUR die Ausgabe (Ranking läuft über ALLE Kandidaten).
     */
    fun search(query: String, limit: Int = DEFAULT_RESULT_LIMIT): List<Hit> =
        synchronized(monitor) {
            val terms = tokenize(query).distinct().take(MAX_QUERY_TERMS)
            if (terms.isEmpty() || limit <= 0) return emptyList()

            // AND über Terme: je Term exakte ODER Präfix-Postings; Term ohne
            // Match kippt die ganze Query (streng — keine Teil-Treffer).
            var candidates: MutableSet<Long>? = null
            for (term in terms) {
                val ids = HashSet<Long>()
                postings[term]?.let { ids.addAll(it) }
                for ((token, set) in postings) {
                    if (token != term && token.startsWith(term)) ids.addAll(set)
                }
                if (ids.isEmpty()) return emptyList()
                val next = candidates?.apply { retainAll(ids) } ?: ids
                if (next.isEmpty()) return emptyList()
                candidates = next
            }
            val docIds = candidates ?: emptySet()
            if (docIds.isEmpty()) return emptyList()

            // Zeit-Faktor über Rang unter den Kandidaten (seq aufsteigend —
            // neuester Kandidat = 1.0, ältester = 0.0; evictions-robust).
            val ranked = docIds
                .mapNotNull { id -> docs[id] }
                .sortedBy { it.seq }
            val denominator = if (ranked.size <= 1) 0 else ranked.size - 1

            val hits = ArrayList<Hit>(ranked.size)
            ranked.forEachIndexed { rank, doc ->
                var relevance = 0.0
                for (term in terms) {
                    val tf = doc.tokens[term]
                    if (tf != null) {
                        relevance += WEIGHT_EXACT + minOf(tf, TF_CAP) * WEIGHT_TF
                    } else {
                        // Präfix-Treffer: deterministisch das Token mit höchstem
                        // tf wählen (tie → lexikographisch kleiner).
                        var bestTf = 0
                        var bestToken: String? = null
                        for ((token, tfDoc) in doc.tokens) {
                            if (!token.startsWith(term)) continue
                            val better = tfDoc > bestTf ||
                                (tfDoc == bestTf && bestToken != null && token < bestToken)
                            if (better || bestToken == null) {
                                bestTf = tfDoc
                                bestToken = token
                            }
                        }
                        if (bestToken != null) {
                            relevance += WEIGHT_PREFIX + minOf(bestTf, TF_CAP) * WEIGHT_TF
                        }
                    }
                }
                val recency = if (denominator == 0) 0.0 else rank.toDouble() / denominator
                hits.add(
                    Hit(
                        agentId = doc.agentId,
                        localId = doc.seq,
                        msgId = doc.msgId,
                        fromAgent = doc.fromAgent,
                        snippet = snippetOf(doc, terms),
                        time = doc.time,
                        score = relevance + WEIGHT_RECENCY * recency,
                    ),
                )
            }
            hits
                .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.localId })
                .take(limit)
        }

    // ── Interna ──────────────────────────────────────────────────────────────

    private fun indexInternal(msg: Indexable): Boolean {
        removeInternal(msg.agentId, msg.seq)
        val doc = Doc(
            agentId = msg.agentId,
            seq = msg.seq,
            msgId = msg.msgId,
            fromAgent = msg.fromAgent,
            text = msg.text,
            time = msg.time,
            tokens = tokenize(msg.text).groupingBy { it }.eachCount(),
        )
        val id = nextDocId++
        docs[id] = doc
        docIdsByAgent.getOrPut(msg.agentId) { HashSet() }.add(id)
        for (token in doc.tokens.keys) {
            postings.getOrPut(token) { HashSet() }.add(id)
        }
        evictOldestBeyondCap()
        return true
    }

    private fun removeInternal(agentId: String, seq: Long): Boolean {
        val ids = docIdsByAgent[agentId] ?: return false
        val victim = ids.firstOrNull { id -> docs[id]?.seq == seq } ?: return false
        removeDoc(victim)
        return true
    }

    private fun clearAgentInternal(agentId: String) {
        val ids = docIdsByAgent.remove(agentId) ?: return
        ids.forEach(::removeDoc)
    }

    private fun evictOldestBeyondCap() {
        while (docs.size > maxEntries) {
            val eldest = docs.keys.firstOrNull() ?: return
            removeDoc(eldest)
        }
    }

    private fun removeDoc(id: Long) {
        val doc = docs.remove(id) ?: return
        for (token in doc.tokens.keys) {
            postings[token]?.let { set ->
                set.remove(id)
                if (set.isEmpty()) postings.remove(token)
            }
        }
        docIdsByAgent[doc.agentId]?.let { set ->
            set.remove(id)
            if (set.isEmpty()) docIdsByAgent.remove(doc.agentId)
        }
    }

    /** Snippet: Fenster um die ERSTE gematchte Stelle im Original-Text. */
    private fun snippetOf(doc: Doc, terms: List<String>): String {
        val spans = spans(doc.text)
        var hitStart = -1
        var hitEnd = doc.text.length
        loop@ for (span in spans) {
            for (term in terms) {
                if (span.token == term || span.token.startsWith(term)) {
                    hitStart = span.start
                    hitEnd = span.endExclusive
                    break@loop
                }
            }
        }
        if (hitStart < 0) return ellipsize(doc.text, 0, minOf(doc.text.length,
            SNIPPET_WIDTH))
        val start = maxOf(0, hitStart - SNIPPET_WIDTH / 2)
        val end = maxOf(minOf(doc.text.length, hitStart + SNIPPET_WIDTH / 2), hitEnd)
        return ellipsize(doc.text, start, end)
    }

    private fun ellipsize(text: String, start: Int, end: Int): String {
        val window = text.substring(start, end)
        return (if (start > 0) "…" else "") + window + (if (end < text.length) "…" else "")
    }
}
