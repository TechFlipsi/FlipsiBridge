package com.hermesandroid.bridge.chat

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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
 * - Schreiben J12 (JÄGER-RFULL): append() hängt NUR die neue Zeile an
 *   (O(1) statt Voll-Rewrite je Nachricht — bei 500 Einträgen waren das
 *   ~512 KiB Neuschrieb je Bubble). Die Kürzung (FIFO + Byte-Obergrenze)
 *   zieht beim LADEN; nur ein Byte-Überlauf zwischen zwei Läufen kürzt
 *   schon im append. Der Kürzungszeitpunkt verschiebt sich, die Obergrenzen
 *   bleiben Vertrag.
 * - Dateinamen J12: sanitize + SHA-256-Suffix je agent_id — „a b“ und „a/b“
 *   kollidierten vorher beide auf a_b.jsonl (Verläufe mischten sich).
 *   Alte kollisionsanfällige Dateien werden beim ersten Zugriff übernommen
 *   (adoptLegacyFile), Bestandsverläufe bleiben erhalten.
 * - Absturz MITTLERES Schreibens: append-only hängt die Zeile AN — ein
 *   Riss mitten in der Zeile kostet beim Laden EINEN Eintrag (korrupte
 *   Zeile, nie den ganzen Verlauf); Kürzungen schreiben ATOMAR (Temp-Datei
 *   + rename, Parität zu IdentityStore/relay-keys-Muster).
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

    /**
     * Eine persistierte Chat-Bubble (Klartext — hinter AppLock-Gate sichtbar).
     * MEDIA-T1: [mediaKind]/[mediaName]/[mediaMime]/[mediaSize]/[mediaPath]/
     * [mediaId] referenzieren eine Media-Nachricht — NUR Verweise, NIE die
     * Bytes (die Datei liegt im app-privaten chat_media-Dir). Alle Felder
     * null = normale Text-Bubble (alte Einträge parsen weiter sauber).
     */
    data class Entry(
        val seq: Long,          // Einfüge-Zeit (Unix-ms) — Sortier-Schlüssel
        val agentId: String,    // echte Relay-agent_id (Demo-Einträge nie hier)
        val chatId: String,     // chat_id der Session zum Senden (je App-Start neu)
        val fromAgent: Boolean, // true = Agenten-Antwort (KI-Badge)
        val text: String,
        val time: String,       // „HH:mm“ Anzeige-Zeitstempel
        val status: String? = null,  // Chip-Snapshot beim Persistieren
        val mediaKind: String? = null,   // image/video/file/voice
        val mediaName: String? = null,
        val mediaMime: String? = null,
        val mediaSize: Long? = null,
        val mediaPath: String? = null,   // lokale Datei (Verweis, kein Blob)
        val mediaId: String? = null,
    )

    /** Persistiertes Verzeichnis unter filesDir (app-privat, kein Backup-Restore nötig). */
    fun root(context: Context): File =
        File(context.applicationContext.filesDir, DIRECTORY_NAME)

    /** Dateiname je Agent: sanitize + SHA-256-Suffix (J12: „a b“ und „a/b“
     * kollidierten beide auf a_b.jsonl — Verläufe mischten sich). Der Hash
     * macht jeden agent_id-Namen eindeutig; lesbar bleibt der sanitize-Teil. */
    internal fun fileFor(root: File, agentId: String): File {
        val safe = agentId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(64).ifEmpty { "agent" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(agentId.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        return File(root, "${safe}_${hash.take(12)}.jsonl")
    }

    /** Eintrag anhängen (synchron, File-I/O — Aufrufer entscheidet über Thread). */
    fun append(root: File, entry: Entry) {
        runCatching {
            root.mkdirs()
            val file = adoptLegacyFile(root, entry.agentId,
                fileFor(root, entry.agentId))
            synchronized(fileLock) {
                // J12: O(1)-Append (eine Zeile anhängen) statt Voll-Rewrite.
                FileOutputStream(file, true).use {
                    it.write((gson.toJson(entry) + "\n").toByteArray(Charsets.UTF_8))
                }
                // Byte-Deckel bleibt harte Invariante: Überlauf zwischen zwei
                // Läufen kürzt sofort (selten — FIFO-500-Dateien liegen im
                // Alltag weit unter 512 KiB). FIFO/Byte-Kürzung sonst beim Load.
                if (file.length() > MAX_FILE_BYTES) {
                    writeAtomic(file, capLines(readLines(file)))
                }
            }
        }
    }

    /** Asynchrones Append (aufrufsicher — IO-Executor serialisiert die Writes). */
    fun appendAsync(root: File?, entry: Entry) {
        if (root == null) return
        ioExecutor.execute { append(root, entry) }
    }

    /** Verlauf eines Agenten (chronologisch; korrupte Zeilen still übersprungen).
     *  J12: Hier zieht die Kürzung (FIFO + Byte-Obergrenze) — persistiert,
     *  wenn tatsächlich gekürzt wurde. */
    fun load(root: File, agentId: String): List<Entry> {
        val file = adoptLegacyFile(root, agentId, fileFor(root, agentId))
        val lines = synchronized(fileLock) { readLines(file).toMutableList() }
        val capped = capLines(lines)
        if (capped.size < lines.size) {
            runCatching { synchronized(fileLock) { writeAtomic(file, capped) } }
        }
        return parse(capped)
    }

    /** Alle Agenten-Verläufe, cross-Agent chronologisch (seq-Sortierung). */
    fun loadAll(root: File): List<Entry> =
        (root.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: emptyArray())
            .flatMap { parse(readLines(it)) }
            .sortedBy { it.seq }

    // ── Interna ──────────────────────────────────────────────────────────────

    /** Prozess-weites Lock: serialisiert append-Write und Load-Kürzung
     *  (beide schreiben dieselbe Datei; ohne Lock ginge eine Zeile zwischen
     *  Read und Rename der Kürzung verloren). */
    private val fileLock = Any()

    /** FIFO- + Byte-Kürzung (älteste zuerst, nie jünger verlieren). */
    private fun capLines(lines: List<String>): List<String> {
        val out = lines.toMutableList()
        while (out.size > MAX_PER_AGENT || bytesOf(out) > MAX_FILE_BYTES) {
            if (out.isEmpty()) break
            out.removeAt(0)
        }
        return out
    }

    /**
     * J12-Migration: alte (kollisionsanfällige) Datei OHNE Hash-Suffix einmalig
     * auf den neuen Namen übernehmen — Bestandsverläufe bleiben erhalten.
     * No-op, wenn der neue Name schon existiert (nie Daten überdecken).
     */
    private fun adoptLegacyFile(root: File, agentId: String, file: File): File {
        if (file.exists()) return file
        val safe = agentId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(64).ifEmpty { "agent" }
        val legacy = File(root, "$safe.jsonl")
        if (!legacy.exists()) return file
        runCatching {
            if (!legacy.renameTo(file)) {
                // rename kann plattformbedingt scheitern — Fallback copy+delete
                // (Bestand bleibt bis zum Schluss lesbar).
                file.writeText(legacy.readText())
                legacy.delete()
            }
        }
        return file
    }

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