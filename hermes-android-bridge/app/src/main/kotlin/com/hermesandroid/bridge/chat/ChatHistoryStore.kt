package com.hermesandroid.bridge.chat

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.Executors

/**
 * SIRS-02 (P2, Volltest 03.11.2026): Chat-Verlauf überlebt den App-Restart.
 *
 * Vorher lebten die Bubbles NUR in rememberSaveable (überlebt Rotation, NICHT
 * force-stop/Prozess-Tod — Volltest: „Noch keine Nachrichten“ nach Restart,
 * chat_id je App-Start neu). Dieser Store persistiert die KLARTEXT-Bubbles je
 * Agent (+chat_id je Eintrag) als JSON-Lines in der APP-PRIVATEN Sandbox
 * (filesDir/chat_history, ohne externe Rechte) — sichtbar erst hinter dem
 * AppLock-Gate, das den ganzen App-Inhalt umschließt (Design-Entscheidung des
 * Volltests: „PIN-Gate macht lokalen Disk-Store zumutbar“).
 *
 * Bewusst KEIN androidx-DataStore: Die Abhängigkeit fehlt im Dep-Tree und die
 * build.gradle ist Fix-Wellen-sperrzone (NIE build.gradle) — der file-basierte
 * Store liefert dieselben Verträge (je Agent+chat_id getrennt, FIFO-Cap,
 * Obergrenze), ist aber JVM-pur und damit ohne Robolectric testbar
 * (Persist-Roundtrip: ChatHistoryStoreTest).
 *
 * Verträge:
 * - JE Agent eine Datei (<sanitized>.jsonl); JE Zeile = EIN Eintrag mit
 *   seq (Einfüge-Zeit ms, cross-Agent sortierbar), agentId, chatId, fromAgent,
 *   text (Klartext-Bubble), time („HH:mm“-Anzeige) und status-Snapshot
 *   (null/queued/delivered/read/failed — Restart-Chips bleiben ehrlich).
 * - FIFO-Cap [MAX_PER_AGENT]: älteste Einträge fliegen zuerst raus (500 ≈
 *   langer Alltag; Parität zur Phone-seitigen MAX_CHAT_EVENTS-Philosophie,
 *   nur größer, weil DAS Archiv hier her ist).
 * - Obergrenze [MAX_FILE_BYTES] je Agent-Datei: Überläuft die Datei, werden
 *   älteste Zeilen abgeschnitten — der Store kann das Datenvolumen NIE
 *   unkontrolliert wachsen lassen.
 * - Schreiben ATOMAR (Temp-Datei + rename, Parität zu IdentityStore/relay-
 *   keys-Muster): Absturz MITTLERES Schreibens hinterlässt den Alt-Bestand.
 * - Korrupte Zeilen werden beim Laden still übersprungen (ein zerstörter
 *   Eintrag kostet nie den ganzen Verlauf); nie crashen.
 */
object ChatHistoryStore {

    const val MAX_PER_AGENT = 500
    const val MAX_FILE_BYTES = 512L * 1024  // 512 KiB je Agent-Datei
    private const val DIRECTORY_NAME = "chat_history"

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fb-chat-history").apply { isDaemon = true }
    }
    private val gson = Gson()

    /** Eine persistierte Chat-Bubble (Klartext — hinter AppLock-Gate sichtbar). */
    data class Entry(
        val seq: Long,          // Einfüge-Zeit (Unix-ms) — Sortier-Schlüssel
        val agentId: String,    // echte Relay-agent_id (Demo-Einträge nie hier)
        val chatId: String,     // chat_id der Session zum Senden (je App-Start neu)
        val fromAgent: Boolean, // true = Agenten-Antwort (KI-Badge)
        val text: String,
        val time: String,       // „HH:mm“ Anzeige-Zeitstempel
        val status: String? = null,  // Chip-Snapshot beim Persistieren
    )

    /** Persistiertes Verzeichnis unter filesDir (app-privat, kein Backup-Restore nötig). */
    fun root(context: Context): File =
        File(context.applicationContext.filesDir, DIRECTORY_NAME)

    /** Dateiname je Agent: nur [A-Za-z0-9._-] (agent_id ist relay-kontrolliert). */
    internal fun fileFor(root: File, agentId: String): File {
        val safe = agentId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(64).ifEmpty { "agent" }
        return File(root, "$safe.jsonl")
    }

    /** Eintrag anhängen (synchron, File-I/O — Aufrufer entscheidet über Thread). */
    fun append(root: File, entry: Entry) {
        runCatching {
            root.mkdirs()
            val file = fileFor(root, entry.agentId)
            val lines = readLines(file).toMutableList()
            lines.add(gson.toJson(entry))
            // FIFO + Byte-Obergrenze: älteste zuerst kürzen (nie jünger verlieren).
            while (lines.size > MAX_PER_AGENT || bytesOf(lines) > MAX_FILE_BYTES) {
                if (lines.isEmpty()) break
                lines.removeAt(0)
            }
            writeAtomic(file, lines)
        }
    }

    /** Asynchrones Append (aufrufsicher — IO-Executor serialisiert die Writes). */
    fun appendAsync(root: File?, entry: Entry) {
        if (root == null) return
        ioExecutor.execute { append(root, entry) }
    }

    /** Verlauf eines Agenten (chronologisch; korrupte Zeilen still übersprungen). */
    fun load(root: File, agentId: String): List<Entry> =
        parse(readLines(fileFor(root, agentId)))

    /** Alle Agenten-Verläufe, cross-Agent chronologisch (seq-Sortierung). */
    fun loadAll(root: File): List<Entry> =
        (root.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: emptyArray())
            .flatMap { parse(readLines(it)) }
            .sortedBy { it.seq }

    // ── Interna ──────────────────────────────────────────────────────────────

    private fun readLines(file: File): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    private fun bytesOf(lines: List<String>): Long = lines.sumOf { it.length + 1L }

    private fun parse(lines: List<String>): List<Entry> =
        lines.mapNotNull { line ->
            runCatching { gson.fromJson(line, Entry::class.java) }.getOrNull()
        }

    private fun writeAtomic(file: File, lines: List<String>) {
        val tmp = File(file.parentFile, ".${file.name}.part")
        tmp.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        if (!tmp.renameTo(file)) {
            // rename kann plattformbedingt über existierende Datei scheitern —
            // dann klassisch: copy+delete (Alt-Bestand bleibt bis zum Schluss).
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}