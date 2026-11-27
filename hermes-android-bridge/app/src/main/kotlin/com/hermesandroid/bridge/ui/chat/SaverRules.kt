package com.hermesandroid.bridge.ui.chat

/**
 * W22 A2 Fix 1 (Reviewer-A-Fund, HOCH): die Chunk/Decode-Logik des
 * Nachrichten-Verlauf-Savers als JVM-reines Regel-Objekt — ausgelagert, damit
 * der Saver-Roundtrip je Version JVM-testbar ist (listSaver selbst hängt an
 * androidx SaveableStateRegistry).
 *
 * DER BUG (Bestandsfehler SEIT v2): die Alt-Konstanten heißen „GROUP“, waren
 * aber off-by-one gegen den je Version GESCHRIEBENEN Slot-Umfang:
 *
 * - v1-Legacy: 11 geschrieben / 11 gechunkt — ok.
 * - v2 (W4-B):  15 geschrieben (11 Basis + 4 Zitat) / gechunkt 14 → ab
 *   Nachricht 2 verrutscht der Restore (Alt-Code b0a042d verifiziert).
 * - v3 (W13):   16 geschrieben (15 + date) / gechunkt 15 → gleiche Verschiebe-
 *   welle + date-String doppelt (quoted.text und date wiesen auf denselben
 *   Slot) (Alt-Code 0b3952b verifiziert).
 * - v4 (W22 A2): 20 geschrieben (16 + 4 Call-Slots) / gechunkt 19 → ab Nachricht
 *   2 verrutscht; `g[15] as Boolean` castete auf dem ersten Chunk den date-
 *   String → CCE bei JEDEM v4-Restore mit mehr als einer Nachricht (Alt-Code
 *   aaaf4f0 verifiziert).
 *
 * Die Chunk-Größen hier = die je Version GESCHRIEBENE Slot-Zahl — damit
 * restaurieren Alt-Bundles korrekt (die Flachliste war nie defekt, nur das
 * Chunking), und der aktive v4-Roundtrip ist 1:1.
 *
 * Slot-Lage je Gruppe (v4, Indizes 0-19):
 * 0 id, 1 agentId, 2 fromAgent, 3 text, 4 time,
 * 5 hasMedia, 6 mediaId, 7 kind, 8 name, 9 size, 10 localPath,
 * 11 hasQuote, 12 quoteFromAgent, 13 quoteSender, 14 quoteText,
 * 15 date (v3+), 16 hasCall, 17 direction, 18 missed, 19 durationSec.
 * v3-Gruppe = 16 Slots (date = Index 15 = letzter), v2 = 15 (quoteText = let-
 * zer), Legacy = 11.
 */
internal object SaverRules {

    /** Aktive Gruppen-Version (Flachlisten-Header). */
    const val VERSION_V4 = 4

    /** Chunk-Größen = historisch geschriebene Slot-Zahl je Version (siehe oben). */
    const val GROUP_LEGACY = 11
    const val GROUP_V2 = 15
    const val GROUP_V3 = 16
    const val GROUP_V4 = 20

    /**
     * Chunk-Größe für eine Flachlisten-Version: v4-Bundle (Int 4) → 20, v3 →
     * 16, v2 → 15, Legacy (kein Int-Header, Liste beginnt mit der Long-id)
     * → 11.
     */
    fun chunkSize(version: Int): Int = when {
        version >= VERSION_V4 -> GROUP_V4
        version >= 3 -> GROUP_V3
        version >= 2 -> GROUP_V2
        else -> GROUP_LEGACY
    }

    /**
     * Primitives Nachrichten-Spiegel der [ChatMessage]-Slots — der Ui-Spiegel
     * lebt in ChatWidgets.kt (1:1-Feldabbildung); dieses Objekt trägt die
     * FLACHE Drahtform, damit sie JVM-getestet wird.
     */
    data class Msg(
        val id: Long,
        val agentId: Int,
        val fromAgent: Boolean,
        val text: String,
        val time: String,
        val media: Media? = null,
        val quote: Quote? = null,
        val date: String = "",
        val call: Call? = null,
    )

    /** Media-Anhang (Spiegel ChatMediaInfo; mime gehört bewusst nicht dazu). */
    data class Media(
        val mediaId: String,
        val kind: String,
        val name: String,
        val size: Long,
        val localPath: String,
    )

    /** Zitat-Snapshot (Spiegel ChatQuote). */
    data class Quote(val fromAgent: Boolean, val senderName: String, val text: String)

    /** Call-Anhang (Spiegel ChatCallInfo; null = normale Bubble). */
    data class Call(val direction: String, val missed: Boolean, val durationSec: Long)

    /**
     * Flachliste ERZEUGEN (Save-Weg): Versions-Marker, danach je Nachricht die
     * feste 20er-Slot-Folge (Media-/Quote-/Date-/Call-lose Nachrichten tragen
     * die Slots leer — feste Größe = robustes Chunk-Restore).
     */
    fun flatList(messages: List<Msg>): List<Any> =
        listOf(VERSION_V4) + messages.flatMap { it.slotList() }

    /**
     * Flachliste ZURÜCKLESEN (Restore-Weg): Versions-Marker trennen (Legacy
     * beginnt mit der Long-id), je Version die RICHTIGE Chunk-Größe nehmen
     * und alle unsicheren Casts als `as?` mit Fallback — ein beschädigtes/
     * abgeschnittenes Bundle fällt ehrlich leer statt zu crashen.
     */
    fun fromFlat(flat: List<Any>): List<Msg> {
        val version = (flat.firstOrNull() as? Int) ?: 1
        val versioned = version >= 2
        val body = if (versioned) flat.drop(1) else flat
        val group = chunkSize(version)
        return body.chunked(group).mapNotNull { g ->
            // Unvollständiger Abschnitt (beschädigtes Bundle): fehlen ehrlich
            // statt beim Slot-Zugriff zu fliegen.
            if (g.size < group) return@mapNotNull null
            val media = if (g[5] == true) {
                Media(
                    mediaId = (g[6] as? String).orEmpty(),
                    kind = (g[7] as? String).orEmpty(),
                    name = (g[8] as? String).orEmpty(),
                    size = (g[9] as? Long) ?: 0L,
                    localPath = (g[10] as? String).orEmpty(),
                )
            } else null
            val quote = if (versioned && g.size > 11 && g[11] == true) {
                Quote(
                    fromAgent = g[12] == true,
                    senderName = (g[13] as? String).orEmpty(),
                    text = (g[14] as? String).orEmpty(),
                )
            } else null
            // v3/v4: date-Slot ist Index 15 (v3 = letzter der 16er-Gruppe,
            // v4 = vor den Call-Slots); v2/Legacy haben keinen → "" (kein
            // erfundener Datum-Chip).
            val date = if (version >= 3 && g.size > 15) (g[15] as? String).orEmpty() else ""
            val call = if (version >= VERSION_V4 && g.size > 16 && g[16] == true) {
                Call(
                    direction = (g[17] as? String).orEmpty(),
                    missed = g[18] == true,
                    durationSec = (g[19] as? Long) ?: 0L,
                )
            } else null
            Msg(
                id = (g[0] as? Long) ?: 0L,
                agentId = (g[1] as? Int) ?: 0,
                fromAgent = g[2] == true,
                text = (g[3] as? String).orEmpty(),
                time = (g[4] as? String).orEmpty(),
                media = media,
                quote = quote,
                date = date,
                call = call,
            )
        }
    }

    /** Die feste Slot-Folge je Nachricht (Save-Form — 20 Elemente bei v4). */
    private fun Msg.slotList(): List<Any> = listOf<Any>(
        id, agentId, fromAgent, text, time,
        media != null,
        media?.mediaId ?: "",
        media?.kind ?: "",
        media?.name ?: "",
        media?.size ?: 0L,
        media?.localPath ?: "",
        quote != null,
        quote?.fromAgent ?: false,
        quote?.senderName ?: "",
        quote?.text ?: "",
        date,
        call != null,
        call?.direction ?: "",
        call?.missed ?: false,
        call?.durationSec ?: 0L,
    )
}
