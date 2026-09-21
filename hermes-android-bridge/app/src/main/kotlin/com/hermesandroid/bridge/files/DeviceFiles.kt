package com.hermesandroid.bridge.files

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException

/**
 * FlipsiBridge Datei-Zugriff (Phase 4) — list/search/read von Dateien
 * im gemeinsamen Gerätespeicher, beschränkt auf /storage/emulated/0.
 *
 * Sicherheitsregeln:
 *  - Nur Lesezugriff. Keine Schreib-, Lösch- oder Umbenenn-Operationen.
 *  - Pfade sind immer RELATIV zu einem der erlaubten Roots (Download,
 *    Documents, Pictures, Music, Movies, DCIM) oder "root" für die
 *    oberste Ebene. Absolute Pfade, "..", Symlink-Escapes → 403/400.
 *  - Berechtigung READ_MEDIA_IMAGES ist im Manifest; für allgemeine
 *    Dateien (PDF, APK, …) reicht das auf Android 11+ NICHT immer —
 *    daher nutzt [searchPublic] die öffentliche Storage-Volumen-Pfade
 *    via java.io.File (funktioniert mit READ_EXTERNAL_STORAGE bzw. bei
 *    MANAGE-Settings ohne). Läuft ohne Berechtigung leer, liefert aber
 *    nie eine Exception — der Agent bekommt eine klare Meldung.
 */
object DeviceFiles {

    /** Erlaubte Wurzel-Verzeichnisse (relativer Name → realer Pfad). */
    fun roots(): Map<String, File> {
        val base = Environment.getExternalStorageDirectory()
        val map = linkedMapOf<String, File>()
        for (name in listOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_DCIM,
        )) {
            val f = File(base, name)
            if (f.exists()) map[name] = f
        }
        // Oberste Ebene erlauben (Übersicht), aber nicht rekursiv durchsuchen
        if (base.exists()) map["root"] = base
        return map
    }

    /** Bereinigt & validiert einen relativen Pfad; null wenn nicht erlaubt. */
    fun sanitize(relative: String?): Pair<File, String>? {
        val raw = relative?.trim().orEmpty().replace('\\', '/')
        if (raw.isEmpty()) return null
        if (raw.contains("..") || raw.startsWith("/")) return null
        val first = raw.substringBefore('/', missingDelimiterValue = "").let { seg ->
            if (seg.isEmpty()) raw else seg
        }
        val rootMap = roots()
        val root = rootMap[first] ?: rootMap["root"] ?: return null
        // "root/Download/x.pdf" erlauben (root-Prefix), sonst Root = Basisordner des Segments
        // Named roots: der Segmentname IST das Verzeichnis - nur Rest hinter dem Segment anhängen,
        // sonst verdoppelt sich der Pfad (base/Download/Download -> "existiert nicht") [v0.9.3-Fix].
        val rest = if (first == "root") {
            raw.removePrefix("root").removePrefix("/")
        } else {
            raw.removePrefix(first).removePrefix("/")
        }
        val target = if (rest.isEmpty()) root else File(root, rest)
        // Doppelte Sicherung: kein Escape aus dem External-Storage
        val basePath = Environment.getExternalStorageDirectory().canonicalPath
        val candidate = try { target.canonicalPath } catch (_: IOException) { return null }
        if (!candidate.startsWith(basePath)) return null
        return Pair(target, raw)
    }

    private fun target(f: File) = f

    data class Entry(
        val name: String,
        val path: String,      // relativ, wiederverwendbar für /file und /files
        val isDir: Boolean,
        val sizeBytes: Long,
        val modifiedMs: Long,
    )

    /** Ordner auflisten. relative wie "Download" oder "Download/Unterordner". */
    fun list(relative: String, showHidden: Boolean = false): Pair<List<Entry>?, String?> {
        val clean = sanitize(relative) ?: return Pair(null, "Ungültiger Pfad: nur relative Pfade unter Download/Documents/Pictures/Music/Movies/DCIM/root erlaubt")
        val dir = clean.first
        if (!dir.exists()) return Pair(null, "Pfad existiert nicht: ${clean.second}")
        if (!dir.isDirectory) return Pair(null, "Pfad ist keine Ordner: ${clean.second}")
        val children = dir.listFiles() ?: return Pair(null, "Ordner nicht lesbar (Berechtigung?): ${clean.second}")
        val entries = children.asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map {
                Entry(
                    name = it.name,
                    path = if (clean.second == "" || clean.second == "root") it.name
                           else "${clean.second}/${it.name}".removePrefix("root/"),
                    isDir = it.isDirectory,
                    sizeBytes = if (it.isDirectory) 0L else it.length(),
                    modifiedMs = it.lastModified(),
                )
            }
            .sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
            .toList()
        return Pair(entries, null)
    }

    /**
     * Rekursiv nach Dateinamen suchen (case-insensitive, LIKE).
     * Durchsucht alle erlaubten Roots; maxFiles begrenzt das Ergebnis.
     */
    fun search(query: String, maxFiles: Int = 200): Pair<List<Entry>?, String?> {
        val q = query.trim()
        if (q.isEmpty()) return Pair(null, "Leere Suchanfrage")
        val needle = q.lowercase()
        val out = mutableListOf<Entry>()
        val rootMap = roots().filterKeys { it != "root" } // root rekursiv doppelt vermeiden
        for ((rootName, rootDir) in rootMap) {
            walk(rootName, rootName, rootDir, needle, maxFiles, out)
            if (out.size >= maxFiles) break
        }
        return Pair(out, null)
    }

    private fun walk(
        rootName: String,
        relSoFar: String,
        dir: File,
        needle: String,
        maxFiles: Int,
        out: MutableList<Entry>,
    ) {
        if (out.size >= maxFiles) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            val rel = "$relSoFar/${child.name}"
            if (child.isDirectory) {
                walk(rootName, rel, child, needle, maxFiles, out)
            } else if (child.name.lowercase().contains(needle)) {
                out.add(
                    Entry(
                        name = child.name,
                        path = rel.removePrefix("root/"),
                        isDir = false,
                        sizeBytes = child.length(),
                        modifiedMs = child.lastModified(),
                    )
                )
                if (out.size >= maxFiles) return
            }
        }
    }

    /** Datei zum Streamen auflösen; null wenn nicht erlaubt/nicht vorhanden/zu groß. */
    fun resolveForRead(relative: String, maxBytes: Long = 512L * 1024 * 1024): Pair<File, String>? {
        val clean = sanitize(relative) ?: return null
        val f = clean.first
        if (!f.isFile) return null
        if (f.length() > maxBytes) return null
        return Pair(f, f.name)
    }

    /**
     * Schreibt (überschreibt nicht ohne Flag) eine Datei aus Bytes in den
     * gemeinsamen Speicher. Nur unter erlaubten Roots, kein "..", max 64 MB.
     */
    fun writeBytes(relative: String, data: ByteArray, allowOverwrite: Boolean): Pair<String, String?> {
        val clean = sanitize(relative) ?: return Pair("", "Ungültiger Pfad (nur relative Pfade unter Download/Documents/Pictures/Music/Movies/DCIM/root)")
        val f = clean.first
        if (f.isDirectory) return Pair("", "Ziel ist ein Ordner: ${clean.second}")
        if (f.exists() && !allowOverwrite) return Pair("", "Datei existiert bereits: ${clean.second} (allowOverwrite=true nötig)")
        if (data.size > 64L * 1024 * 1024) return Pair("", "Zu groß (Limit 64 MB)")
        val parent = f.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) return Pair("", "Ordner konnte nicht erstellt werden")
        return try {
            java.io.FileOutputStream(f).use { it.write(data) }
            Pair(clean.second, null)
        } catch (e: Exception) {
            Pair("", "Schreiben fehlgeschlagen: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Löscht eine Datei (keine Ordner). Sir-Freigabe 21.09.2026: generelles
     * Löschen gewünscht ("lösche alle PDFs am Gerät" muss funktionieren).
     * Verbleibende Gurte: Whitelist-Roots, kein "..", nur Dateien, Batch-Cap.
     */
    fun deleteFile(relative: String): Pair<String, String?> {
        val clean = sanitize(relative) ?: return Pair("", "Ungültiger Pfad")
        val f = clean.first
        if (f.isDirectory) return Pair("", "Ordner löschen ist gesperrt (nur Dateien)")
        if (!f.isFile) return Pair("", "Datei nicht gefunden: ${clean.second}")
        val size = f.length()
        val ok = f.delete()
        return if (ok) Pair("gelöscht: ${clean.second} ($size Bytes)", null)
        else Pair("", "Löschen fehlgeschlagen (Datei gesperrt?)")
    }

    /** Löscht mehrere Dateien in einem Aufruf; max 200 pro Call (Batch-Cap gegen Massen-Dummy-Zugriffe). */
    fun deleteMany(relatives: List<String>): List<Map<String, String?>> {
        require(relatives.size <= 200) { "max 200 Dateien pro Aufruf" }
        return relatives.map { rel ->
            val (msg, err) = deleteFile(rel)
            if (err == null) mapOf("path" to rel, "ok" to "true", "message" to msg)
            else mapOf("path" to rel, "ok" to "false", "error" to err)
        }
    }

    /** Zählt Dateien mit Endung rekursiv über alle Roots — für schnelle Übersichten. */
    fun countByExtension(extension: String, maxWalk: Int = 20000): Map<String, Int> {
        val ext = extension.trim().lowercase().removePrefix(".")
        val counts = mutableMapOf<String, Int>()
        var walked = 0
        for ((rootName, rootDir) in roots().filterKeys { it != "root" }) {
            val stack = ArrayDeque<File>()
            stack.add(rootDir)
            while (stack.isNotEmpty() && walked < maxWalk) {
                val dir = stack.removeFirst()
                val children = dir.listFiles() ?: continue
                for (child in children) {
                    walked++
                    if (walked >= maxWalk) break
                    if (child.isDirectory) stack.add(child)
                    else if (child.name.lowercase().endsWith(".$ext")) {
                        counts[rootName] = (counts[rootName] ?: 0) + 1
                    }
                }
            }
        }
        return counts
    }
}