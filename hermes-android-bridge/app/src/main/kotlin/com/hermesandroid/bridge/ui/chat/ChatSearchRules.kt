package com.hermesandroid.bridge.ui.chat

/**
 * W4-B (Chat-Suche): Reine Regeln der Verlaufssuche — JVM-getestet
 * (ChatSearchRulesTest); die Suche-UI (ChatScreen: Magnifier am Chat-Kopf,
 * Suchleiste, Treffer-Liste, Sprung-Scroll) rendert nur.
 *
 * ## Filter-Verband (ChatHistoryStore, >200-Eintrag-Verhältnis)
 * Die Suche läuft ÜBER DEN VOLLEN Verlauf, wie er auf dem Bildschirm lebt:
 * [ChatMessage]-Liste = Disk-Restore (ChatHistoryStore.loadAll, FIFO-Cap
 * 500/Agent, Byte-Cap 512 KiB) + Live-Ankömmlinge. Sie hängt bewusst NICHT
 * an chatEvents (RelayClient-Flow, 200er-Cap = evicted Einträge unauffindbar)
 * — >200-Verläufe sind voll suchbar, weil der Restore den Disk-Stand
 * komplett in `messages` legt. Der Anzeige-Cap [MAX_RESULTS] begrenzt NUR
 * die Trefferliste, NIEMALS den Suchlauf (vollständiger Scan vor dem Cap).
 *
 * ## Suchband
 * - Case-insensitives Enthaltens (normalizeQuery + lowercase beidseitig);
 *   Whitespace-Query = inaktiv.
 * - Korpus je Bubble: Text (auch Media-Label-Text) + Zitat-Text der Antwort-
 *   Bubble ([ChatMessage.quoted]) — ein Treffer im Zitat springt ebenfalls
 *   zur Bubble.
 * - previewAround liefert ein Fenster um den Ersten Hit (Ellipsen an den Rändern).
 *
 * ## Drill-Acker (Bau-Welle, Emulator)
 * - Magnifier am Chat-Kopf → Suchleiste (Tastatur fokusiert), Verlauf bleibt
 *   an der Stelle; Trefferliste ersetzt die Timeline bis zur Auswahl.
 * - Drill >200: Agenten-Verlauf über 200 Bubbles füllen (Disk-Store hält 500)
 *   → Suchbegriff im ÄLTESTEN Eintrag trifft trotz chatEvents-Cap 200.
 * - Tap auf Treffer → Timeline zurück, Sprung-Scroll zur Bubble (Reduced:
 *   sofortiger Scroll), Ember-Rand ~1,6 s (Highlight), Suchleiste bleibt.
 * - Umlaute case-insensitiv: „MÜNZEN“ trifft „münzen“.
 * - Leere Query/kein Treffer: „Keine Treffer“-Zeile, Sprung unmöglich.
 */
object ChatSearchRules {

    /** Anzeige-Cap der Trefferliste (Suchlauf selbst läuft im VOLLBESTAND). */
    const val MAX_RESULTS = 50

    /** Radius des Hit-Fensters je Trefferzeile. */
    const val PREVIEW_RADIUS = 60

    const val LABEL_MATCH_ONE = "1 Treffer"
    const val LABEL_MATCH_MANY = "Treffer"

    /** Ein Suchtreffer: Bubble-Anker (id + Listenposition) + Anzeige-Daten. */
    data class SearchMatch(
        val messageId: Long,
        val index: Int,                 // Listenposition im Verlauf (Sprung-Anker)
        val fromAgent: Boolean,
        val time: String,
        val previewText: String,        // getragener Text (Bubble- oder Zitat-Text)
        val previewStart: Int,          // Hit-Index innerhalb previewText
    )

    /** Normalisierte Query (trim); Blank = Suche inaktiv. */
    fun normalizeQuery(query: String): String = query.trim()

    /** true = Trefferliste aktiv (Query nicht-leer). */
    fun active(query: String): Boolean = normalizeQuery(query).isNotEmpty()

    /** Case-insensitives Erst-Vorkommen von [needle] in [text] (−1 = kein Hit). */
    fun hitIndex(text: String, needle: String): Int =
        text.lowercase().indexOf(needle)

    /**
     * Treffer über den VOLLSTÄNDIGEN Verlauf — vollständiger Scan (jede Bubble
     * geprüft), dann Cap [MAX_RESULTS] NUR für die Anzeige (chronologische
     * Reihenfolge bleibt; Sprung-Anker = Listenindex, LazyColumn-keyed by id).
     */
    fun matches(query: String, messages: List<ChatMessage>): List<SearchMatch> {
        val needle = normalizeQuery(query).lowercase()
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<SearchMatch>(MAX_RESULTS)
        for ((index, message) in messages.withIndex()) {
            val direct = hitIndex(message.text, needle)
            val hit = when {
                direct >= 0 -> direct to message.text
                message.quoted != null -> {
                    val inQuote = hitIndex(message.quoted.text, needle)
                    if (inQuote >= 0) inQuote to message.quoted.text else -1 to ""
                }
                else -> -1 to ""
            }
            if (hit.first >= 0) {
                out.add(
                    SearchMatch(
                        messageId = message.id,
                        index = index,
                        fromAgent = message.fromAgent,
                        time = message.time,
                        previewText = previewAround(hit.second, hit.first),
                        previewStart = maxOf(0, hit.first - (PREVIEW_RADIUS / 2)),
                    ),
                )
            }
            if (out.size >= MAX_RESULTS) break
        }
        return out
    }

    /** Fenster um den ERTEN Hit (Radius [PREVIEW_RADIUS]), „…“ an den Rändern. */
    fun previewAround(text: String, hitIndex: Int, radius: Int = PREVIEW_RADIUS): String {
        if (hitIndex < 0 || hitIndex >= text.length) return text
        val start = maxOf(0, hitIndex - (radius / 2))
        val end = minOf(text.length, hitIndex + radius)
        val window = text.substring(start, end)
        return (if (start > 0) "…" else "") + window + (if (end < text.length) "…" else "")
    }

    /** Trefferzahl-Zeile (deutsch; 1 = Singular). */
    fun countLabel(count: Int): String = when {
        count == 1 -> LABEL_MATCH_ONE
        else -> "$count $LABEL_MATCH_MANY"
    }
}
