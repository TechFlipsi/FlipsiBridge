package com.hermesandroid.bridge.ui.chat

import com.hermesandroid.bridge.chat.ChatHistoryStore

/**
 * W4 (App-Lane C) — Medien-Index für den Mediathek-Tab je Agent.
 *
 * Datenherkunft ist SOLO ChatHistoryStore (persistierte Media-VERWEISE —
 * MEDIA-T1: NUR Metadaten/Pfade, nie die Bytes; die Dateien liegen app-privat)
 * plus die LIVE-Media-Bubbles der offenen Session (ui.chat.ChatMediaInfo).
 * JVM-pur (nur ChatHistoryStore.Entry + exists-Lambda) und damit
 * unit-getestet (MediathekIndexTest).
 *
 * Verträge:
 * - STALE-REGEL (Platzhalter-Regel): mediaPath in den Store-Einträgen kann
 *   STALE sein (App-Update, Device-Wechsel, geräumtes chat_media-Dir) — der
 *   Item-Pfad bleibt dann leer, [Item.stale] = true. Die Mediathek rendert
 *   dafür eine Platzhalter-Karte und crashed NIE (Pfad-Prüfung nur über das
 *   exists-Lambda aus dem UI-Loader, das selbst runCatching-geschützt ist).
 * - EIGENE gesendete Medien haben KEINEN Pfad (Bytes laufen live aus Picker/
 *   Voice in den verschlüsselten Upload, kein Zwischen-Blob) — path = "",
 *   stale = false: „Nur Verweis“-Karte, kein Fehler.
 * - dedupe: Store-Eintrag + LIVE-Bubble desselben Mediums zählen EINMAL
 *   (Schlüssel [dedupeKeys]: media_id bevorzugt, Meta-Schlüssel zusätzlich;
 *   Store-Einträge zuerst, LIVE füllt Gaps — Reihenfolge bleibt chronologisch).
 * - Text-Bubbles (mediaKind = null) bleiben draußen; unbekannte Art fällt
 *   auf „file“ (Platzhalter-Verband, kein Crash).
 */
object MediathekIndex {

    /** Medien-Arten der Abschnitte (Anzeige-Reihenfolge im Tab). */
    val SECTION_KINDS = listOf("image", "video", "voice", "file")

    /**
     * Ein Medien-Eintrag der Mediathek. [path] leer = Datei existiert nicht
     * am Gerät ([stale] sagt: sie WAR referenziert und ist jetzt weg).
     */
    data class Item(
        val mediaId: String,
        val kind: String,      // image/video/file/voice (unbekannt → file)
        val name: String,
        val mime: String,
        val size: Long,
        val time: String,      // Anzeige-„HH:mm“ aus dem Store-Eintrag
        val path: String,      // app-privater Pfad; leer = nur Verweis/stale
        val stale: Boolean,    // Pfad war gesetzt, Datei fehlt jetzt (STALE-REGEL)
        val fromStore: Boolean,// false = LIVE-Bubble der offenen Session
    )

    /**
     * STALE-REGEL (reine Funktion): Pfad-Auflösung je Eintrag.
     * kein/leerer Pfad → ("" , false)  = „Nur Verweis“;
     * Pfad gesetzt + Datei fehlt → ("", true)  = Platzhalter-Karte (stale);
     * Pfad gesetzt + Datei da → (Pfad, false) = voll nutzbar.
     * Keine File-I/O IM Objekt — [exists] kommt vom Aufrufer (UI: File.isFile
     * auf IO-Thread; Test: Tempdatei-Erzeugung).
     */
    fun resolvePath(rawPath: String?, exists: (String) -> Boolean): Pair<String, Boolean> {
        val path = rawPath?.trim().orEmpty()
        if (path.isEmpty()) return "" to false
        return if (exists(path)) path to false else "" to true
    }

    /**
     * Store-Eintrag → Mediathek-Item. Fehlerfrei nach dem „nie crashen“-Vertrag:
     * fehlende name/kind-Felder haben Fallbacks (name = Bubble-Text, kind = file).
     */
    fun fromEntries(entries: List<ChatHistoryStore.Entry>, exists: (String) -> Boolean): List<Item> =
        entries.mapNotNull { entry ->
            val kind = (entry.mediaKind ?: "").trim().ifEmpty { "file" }
            // Text-Bubbles (mediaKind = null) bleiben draußen; BLANK-Kind
            // (""/„ “) BLEIBT — der file-Fallback oben deckt es (Kind-Blank-
            // Kontrakt: 3 Entries → 2 Items, das „“→„file“-Item ist dabei).
            if (entry.mediaKind == null) return@mapNotNull null
            val (path, stale) = resolvePath(entry.mediaPath, exists)
            Item(
                mediaId = entry.mediaId.orEmpty(),
                kind = kind,
                name = entry.mediaName ?: entry.text,
                mime = entry.mediaMime ?: "",
                size = entry.mediaSize ?: 0L,
                time = entry.time,
                path = path,
                stale = stale,
                fromStore = true,
            )
        }

    /**
     * LIVE-Bubbles der offenen Session ergänzen (chronologisch hinten —
     * frisch empfangene/gewählte Medien sind praktisch immer die neuesten).
     * Dubletten überschlagen sich NICHT: ein Item zählt als Dublette, sobald
     * IRGENDSEIN Schlüssel ([dedupeKeys], media_id ODER Meta) schon belegt ist.
     */
    fun merge(store: List<Item>, live: List<Item>): List<Item> {
        fun keysOf(item: Item) = dedupeKeys(item)
        val seen = LinkedHashMap<String, Boolean>()
        val out = ArrayList<Item>(store.size + live.size)
        for (item in store + live) {
            val keys = keysOf(item)
            if (keys.any { seen.containsKey(it) }) continue
            keys.forEach { seen[it] = true }
            out.add(item)
        }
        return out
    }

    /** Schlüsselsatz gegen Store+LIVE-Dubletten (media_id bevorzugt). */
    fun dedupeKeys(item: Item): Set<String> = buildSet {
        if (item.mediaId.isNotBlank()) add("id:${item.mediaId}")
        add("m:${item.kind}|${item.name}|${item.size}|${item.time}")
    }

    /** Items eines Abschnitts (je Medientyp). */
    fun byKind(items: List<Item>, kind: String): List<Item> = items.filter { it.kind == kind }
}
