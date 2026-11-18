package com.hermesandroid.bridge.chat

import android.content.Context
import com.google.gson.Gson
import com.hermesandroid.bridge.client.ChatStatusRank
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
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
        val msgId: String? = null,  // HAKEN-FIX: Broker-msg_id des eigenen Sende-
            // Ergebnisses (null bei Alt-Daten/Senden-Fehler). Kette Platte ↔
            // Side-Car: der Restore bindet die msg_id wieder (Live-Frames
            // treffen restaurierte Chips) und legt den Side-Car-Stand neben
            // den Sendezeit-Snapshot (dort ist der HÖCHSTE bekannte Stand).
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
            synchronized(fileLock) {
                // W4-FIX-JAG2 (C4): die Legacy-Adoption ZIEHT jetzt unter dem
                // Prozess-Lock. Vorher lief adoptLegacyFile außerhalb — zwei
                // nebenläufige Pfade (append-Executor vs. Load) konnten beide
                // „Legacy existiert, Ziel fehlt“ sehen und beide adoptieren
                // (copy+delete-Zweig konnte den frisch geschriebenen Stand des
                // ersten Pfades mit dem Legacy-Stand überbauen).
                val file = adoptLegacyFile(root, entry.agentId,
                    fileFor(root, entry.agentId))
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
        // W4-FIX-JAG2 (C4): Adoption + Read unter EINEM Lock-Sprung (siehe
        // append) — sonst konkurrierte die Adoption mit einem parallelen
        // append/load auf dieselbe Agent-Datei.
        val (file, lines) = synchronized(fileLock) {
            val f = adoptLegacyFile(root, agentId, fileFor(root, agentId))
            f to readLines(f).toMutableList()
        }
        val capped = capLines(lines)
        if (capped.size < lines.size) {
            runCatching { synchronized(fileLock) { writeAtomic(file, capped) } }
        }
        return parse(capped)
    }

    /** Alle Agenten-Verläufe, cross-Agent chronologisch (seq-Sortierung). */
    fun loadAll(root: File): List<Entry> =
        // W4-FIX-JAG2 (C5): der Voll-Load zieht unter demselben Prozess-Lock
        // wie append/load-Kürzung — vorher las er die .jsonl-Dateien
        // unverriegelt (torn-read: eine zwischen ListFiles und Read umbenannte
        // Datei + halbe Append-Zeile = still verschluckter Eintrag je Restore).
        synchronized(fileLock) {
            (root.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: emptyArray())
                .flatMap { parse(readLines(it)) }
                .sortedBy { it.seq }
        }

    /**
     * W16 (Sir-Fixliste 32): den Verlauf eines Agenten löschen — „Chat löschen“
     * im ⋮-Menü der Conversation. Es wird NUR die Agent-Verlaufsdatei geräumt
     * (gehashter Name + Legacy-Zwilling ohne Hash-Suffix, sonst importiert die
     * Adoption den alten Stand beim nächsten Zugriff zurück); die E2E-Keys
     * (IdentityStore/relay-keys) liegen in ANDEREN Dateien und bleiben
     * unangetastet. Aufrufer entscheiden über den Thread (ChatScreen: media-
     * Scope + Dispatchers.IO).
     */
    fun clearAgent(root: File, agentId: String) {
        if (agentId.isBlank()) return
        runCatching {
            synchronized(fileLock) {
                val safe = agentId.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .take(64).ifEmpty { "agent" }
                // Der Legacy-Zwilling wird vor der Adoption gelöscht —
                // adoptLegacyFile würde sonst den Alt-Stand wieder beleben.
                File(root, "$safe.jsonl").delete()
                fileFor(root, agentId)
                    .let { hashed ->
                        File(root, ".${hashed.name}.part").delete()
                        hashed.delete()
                    }
            }
        }
    }

    // ── B5/B6 (Multi-Gerät, Sir-General-GO 17.11.): Verlauf-Verband ──────────

    /**
     * B6: existiert ein persistenter Verlauf für agentId (≥1 lesbare Zeile)?
     * Grundlage des Backfill-Requests (Roadmap Kap. 4.1: frisches Gerät mit
     * fehlender/leerer ChatHistoryStore-Datei fordert an). Legacy-Adoption
     * unter demselben Prozess-Lock (C4-Parität zu load/append); runCatching
     * = nie eine Krypto-Session an einer IO-Scherbe kippen (false = ehrlich
     * „kein Bestand bekannt“).
     */
    fun hasHistory(root: File, agentId: String): Boolean = runCatching {
        synchronized(fileLock) {
            val file = adoptLegacyFile(root, agentId, fileFor(root, agentId))
            if (!file.exists()) false else readLines(file).isNotEmpty()
        }
    }.getOrDefault(false)

    /**
     * B6 BACKFILL-INGEST: Batch aus chat.backfill.enc CHRONOLOGISCH in die
     * Platte einsortieren — ältere Zeilen wandern VOR den bestehenden Bestand
     * (Roadmap Kap. 2.2 „Rest-Verlauf“ / Kap. 4.1 Merge über bestehenden
     * Vertrag). Einmaliger atomarer Rewrite (writeAtomic, Torn-Write-Parität
     * zu capLines-Kürzung) — der O(1)-Append des Alltags bleibt UNVERÄNDERT,
     * nur der Backfill überschreibt einmalig je Agent.
     *
     * Verträge:
     * - msg_id-Idempotenz (Kap. 2.3 #1): vorhandene msg_ids werden NICHT
     *   doppelt — die Platte-Wahrheit gewinnt gegen Batch-Dubletten (Reconnect
     *   kann denselben Batch spoolen; „restoredStatusById/Chips ziehen über die
     *   msg_id“ — eine zweite Zeile gleicher msg_id würde den Chip-Verband
     *   verzerren).
     * - Zeilen OHNE msg_id dedupen über (agentId, fromAgent, text, HH:mm)-
     *   Schlüssel (Parität ChatTimelineMerge.dedupeKey) — Alt-Haken-Daten und
     *   Backend-B4-lose Zeilen bleiben so trotzdem doppelfest.
     * - status = null (kein erfundener Sendezeit-Claim): die Chips kommen
     *   über die msg_id-Bindung (Side-Car ⊔ chat.status ⊔ snapshot, Chat-
     *   StatusRank-Monotonie) NACH — nie vom Backfill erfunden.
     * - time wird aus seq („HH:mm“, Locale.GERMANY) neu gebaut — Parität zum
     *   persistChatBubble-Formatter, damit Restore-Dedupe und Live-Anzeige
     *   sich nicht an einem Format verzählen.
     * - FIFO/Byte-Caps UNVERÄNDERT (capLines — 500/512 KiB, älteste zuerst).
     *
     * @return Anzahl NEU einsortierter Zeilen (0 = nichts Neues — der Aufrufer
     *   stößt die Prepend-Anzeige dann NICHT an).
     */
    fun mergeBackfill(root: File, agentId: String, incoming: List<Entry>): Int {
        if (agentId.isBlank() || incoming.isEmpty()) return 0
        return runCatching {
            synchronized(fileLock) {
                val file = adoptLegacyFile(root, agentId, fileFor(root, agentId))
                val existing = parse(readLines(file))
                val knownMsgIds = existing
                    .mapNotNull { it.msgId?.takeIf(String::isNotBlank) }
                    .toHashSet()
                val knownKeys = existing.map(::mergeDedupeKey).toHashSet()
                // Zeit FIRST normalisieren („HH:mm“ aus seq) — sonst verzählt
                // der Dedupe-Schlüssel Merge-Zeile ("") gegen Persist-Zeile
                // ("HH:mm") und derselbe Beitrag landet doppelt.
                val fresh = ArrayList<Entry>()
                for (candidate in incoming.map(::normalizedForDisk)) {
                    val msgId = candidate.msgId?.takeIf(String::isNotBlank)
                    if (msgId != null && !knownMsgIds.add(msgId)) continue
                    val key = mergeDedupeKey(candidate)
                    if (!knownKeys.add(key)) continue
                    fresh += candidate
                }
                if (fresh.isEmpty()) {
                    return@synchronized 0
                }
                // Chronologisch aufsteigend — Bestand vor Backfill bei Gleich-
                // stand (stable sort): dieselbe Platte-Wahrheit bleibt voran.
                val merged = (existing + fresh).sortedBy { it.seq }
                writeAtomic(file, capLines(merged.map(::normalizedForDisk)))
                fresh.size
            }
        }.getOrDefault(0)
    }

    /** Asynchroner Merge (IO-Executor serialisiert mit append/load — kein Lock-Stau). */
    fun mergeBackfillAsync(
        root: File?,
        agentId: String,
        incoming: List<Entry>,
        onComplete: (fresh: Int) -> Unit = {},
    ) {
        if (root == null) return
        ioExecutor.execute {
            val fresh = mergeBackfill(root, agentId, incoming)
            runCatching { onComplete(fresh) }
        }
    }

    /** Disk-Zeile für den Merge final bauen: msgId-bindbare Zeit „HH:mm“. */
    private fun normalizedForDisk(entry: Entry): Entry = entry.copy(
        time = SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date(entry.seq)),
    )

    /**
     * Merge-Dedupe-Schlüssel (Parität ChatTimelineMerge.dedupeKey — Längen-
     * Präfix gegen Trennzeichen-Gleichklang, ohne Agent-Hash: die Merge-Datei
     * ist je Agent EINE, der Agent ist implizit).
     */
    private fun mergeDedupeKey(entry: Entry): String =
        "${entry.agentId}\u0000${entry.fromAgent}\u0000${entry.text.length}:${entry.text}\u0000${entry.time}"

    // ── HAKEN-FIX (17.11.2026): Status-Side-Car ──────────────────────────────
    //
    // Wurzel des 2-Haken-Bugs: delivered/read lebten NUR im In-Memory-Map
    // (RelayClient._chatStatus, MAX_CHAT_STATUS = 500) — der App-Restart UND
    // die >500-Eviction (Medien-Chunks steuern je POST eine msg_id bei!)
    // kippten 2 grüne Haken auf 1. Dieser Side-Car hält je msg_id den
    // HÖCHSTEN bekannten Rang-Status auf der Platte:
    // - Schreiben: der RANG-GEWINNENDE chat.status-Frame (RelayClient.
    //   handleChatStatusFrame), die Evicted-Einträge VOR dem Rauswurf und die
    //   Session-Reset-Sweep-Diffs (queued/delivered → failed, Parität zur
    //   Live-Regel: read heilt später nach). Sendezeit-Snapshots (Entry.status)
    //   bleiben unangetastet — der Side-Car ist die ECHTE Status-Platte.
    // - Bewusst EINE globale Datei (kein je-Agent-Zwilling): der chat.status-
    //   Frame trägt keine agent_id (Beweis tools/android_relay.py
    //   _set_chat_status: Frame = type/v/chat_id/msg_id/status/ts) — je-Agent
    //   müsste die msg_id→Agent-Zuordnung RÄTEM (Fehl-Zwilling-Risiko). Die
    //   Bindung passiert beim Restore aus dem Zwilling (Entry.msgId), daher
    //   ist die agent-Freiheit hier korrekt.
    // - Endung .sidecar (NICHT .jsonl): loadAll kann die Datei NIE als
    //   Verlauf parsen.
    // - clearAgent räumt sie nicht: Rows ohne Zwilling werden beim Restore
    //   NIE befragt (Bindung läuft vom Eintrag aus), der Cap schneidet Ver-
    //   lorenes mit. Kein Ghost-Chip-Risiko: ein Chip braucht IMMER eine
    //   msg_id-Bindung aus einem bestehenden Disk-Zwilling oder Live-Send.
    // - Absturz-sicher wie der Verlauf (Append-Only-Kehrseite: eine gerissene
    //   Zeile kostet beim Falt-Lesen EINE Row); Trims ATOMAR (writeAtomic).

    /** EINE Datei für alle Agenten — bewusst NICHT .jsonl (loadAll-Filter). */
    internal val statusSidecarFile: (File) -> File = { root ->
        File(root, STATUS_SIDECAR_FILE)
    }

    // 128 KiB ≈ ~2500 Rows; der Trim holt den Bestand auf SIDECAR_KEEP_LINES
    // NEUESTE zurück (Amortisierung: nicht je Append ein Rewrite).
    const val SIDECAR_MAX_BYTES = 128L * 1024
    const val SIDECAR_KEEP_LINES = 1800

    private const val STATUS_SIDECAR_FILE = "chat_status.sidecar"

    /** Eine Zeile im Side-Car (JSON; gson-Konvention wie Entry). */
    private data class StatusRow(val msgId: String, val status: String)

    /**
     * Einen gewonnenen Status an die Platte schreiben (SYNCHRON, winzige
     * Append-Zeile auf dem Frame-Thread — die Vertrags-Kette „write-back VOR
     * dem Evict" kann nicht über einen Executor-Rückstau brechen). Korrupte
     * Status/Fremd-Werte werden verworfen (nie in die Faltung).
     */
    fun recordStatus(root: File, msgId: String, status: String) {
        if (msgId.isBlank() || !ChatStatusRank.isKnown(status)) return
        synchronized(fileLock) {
            runCatching {
                root.mkdirs()
                val file = statusSidecarFile(root)
                FileOutputStream(file, true).use {
                    it.write(
                        (gson.toJson(StatusRow(msgId, status)) + "\n")
                            .toByteArray(Charsets.UTF_8),
                    )
                }
                // Byte-Deckel bleibt harte Invariante (Parität zum Verlauf):
                // Trim holt den Bestand auf SIDECAR_KEEP_LINES NEUESTE zurück.
                if (file.length() > SIDECAR_MAX_BYTES) {
                    val rows = readLines(file)
                    if (rows.size > SIDECAR_KEEP_LINES) {
                        writeAtomic(file, rows.takeLast(SIDECAR_KEEP_LINES))
                    }
                }
            }
        }
    }

    /**
     * Side-Car lesen und FALTEN (ChatStatusRank.foldRows): je msg_id der
     * HÖCHSTE bekannte Stand — korrupte Zeilen still übersprungen, Unbekannte
     * Werte (ChatStatusRank.isKnown) nie in die Faltung. Restore-Aufrufer:
     * ChatScreen (restoredStatusById + msg_id-Bindung).
     */
    fun loadStatuses(root: File): Map<String, String> = synchronized(fileLock) {
        val rows = ArrayList<Pair<String, String>>()
        for (line in readLines(statusSidecarFile(root))) {
            val row = runCatching { gson.fromJson(line, StatusRow::class.java) }
                .getOrNull()
            if (row == null || row.msgId.isBlank()) continue
            if (ChatStatusRank.isKnown(row.status)) rows.add(row.msgId to row.status)
        }
        ChatStatusRank.foldRows(rows)
    }

    // ── Interna ──────────────────────────────────────────────────────────────

    /** Prozess-weites Lock: serialisiert append-Write und Load-Kürzung
     *  (beide schreiben dieselbe Datei; ohne Lock ginge eine Zeile zwischen
     *  Read und Rename der Kürzung verloren). */
    private val fileLock = Any()

    /** FIFO- + Byte-Kürzung (älteste zuerst, nie jünger verlieren). */
    private fun capLines(lines: List<String>): List<String> {
        val out = lines.toMutableList()
        // W4-FIX-JAG2 (C10): laufend mitgezogenes Byte-Total (O(n)) — das alte
        // While rief bytesOf(out) JE Runde neu (O(n²)). Mit der UTF-8-Anpassung
        // in bytesOf zieht hier removeAt nur den Beitrag der entfernten Zeile
        // ab; Semantik identisch (leere Liste: total=0 → Schleife endet wie
        // bisher über den isEmpty-Abbruch).
        var total = bytesOf(out)
        while (out.size > MAX_PER_AGENT || total > MAX_FILE_BYTES) {
            if (out.isEmpty()) break
            total -= out.removeAt(0).toByteArray(Charsets.UTF_8).size + 1L
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

    /**
     * W4-FIX-JAG2 (C10): Byte-Invariante zählt UTF-8-Bytes, nicht UTF-16-Chars.
     * Vorher `it.length` (UTF-16-Einheiten): Umlaute (2 UTF-8-Bytes je Char)
     * und Emoji (4) ließen die 512-KiB-Dateigrenze um >45 % unterlaufen.
     */
    private fun bytesOf(lines: List<String>): Long =
        lines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1L }

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