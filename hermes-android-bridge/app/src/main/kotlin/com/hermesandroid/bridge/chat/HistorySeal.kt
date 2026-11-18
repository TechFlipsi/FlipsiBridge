package com.hermesandroid.bridge.chat

import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac

/**
 * P24 (fixliste 24): At-rest-Siegel für Verlaufs-Dateien.
 *
 * Jede ChatHistoryStore-Verlaufs-Datei (<agent>_<hash>.jsonl) trägt eine
 * Siegel-Datei („.<name>.seal“): HMAC-SHA256 über den Datei-Inhalt mit einem
 * ANDROID-KEYSTORE-Schlüssel (nicht exportierbar — KeyStore „AndroidKeyStore“,
 * HMAC 256-bit, keine User-Auth nötig: Hintergrund-Lese-Pfad). Diese Datei ist
 * JVM-REIN: Keystore-Bindung + Store-Verdrahtung leben im
 * ChatHistoryStore/AndroidHistorySealKey; hier Format + Verify-Semantik
 * (JVM-testbar mit JCE-Mock-Key, HistorySealRulesTest).
 *
 * Verträge (fail-closed + fail-loud):
 * - UNSEALED (kein Siegel, z. B. Erstbefund nach Update/Alt-Bestand):
 *   MIGRATION — mit Schlüssel sofort SIGELN, Inhalt byte-genau UNVERÄNDERT
 *   („kein Verlauf-Verlust“); ohne Schlüssel (JVM-Testumgebung) bleibt das
 *   Lesen offen (dort gibt es keine AndroidKeyStore-Welt).
 * - TAMPERED (Siegel ≠ Inhalt / Siegel-Datei unlesbar): [SealBreach] — ehr-
 *   licher Fehler statt stilles Laden. Recovery = Chat löschen (clearAgent
 *   räumt das Siegel mit); NIEMALS auto-löschen/Silence.
 * - KEY_UNAVAILABLE (Siegel vorhanden, Keystore-Schlüssel nicht erreichbar):
 *   ebenfalls fail-closed — unverifizierbar gilt als unvertrauenswürdig.
 * - TORN-APPEND-RECOVERY: Der EINZIGE tolerierte Sonderfall — fällt das
 *   Siegel erst nach dem Runterschneiden VOLLSTÄNDIGER Zeilen (max
 *   [TORN_APPEND_STRIP_LIMIT]) mit dem Inhalt zusammen, war es ein abge-
 *   stürzter Append (bestehender Vertrag: torn line kostet EINEN Eintrag):
 *   Inhalt wird kryptografisch bewiesen auf das gesiegelte Präfix zurückge-
 *   schnitten, neu gesiegelt, als RECOVERED gemeldet (laut). Ein Angreifer
 *   erzeugt damit NUR Verwerfen des Appends (angehängte Fake-Zeilen laden
 *   nie) — Kürzen/Um-/Einfügen im Bestand bleibt TAMPERED.
 * - Siegel-Schreibpfad ATOMAR (.part → rename, Parität zum Store-
 *   writeAtomic) — kein torn seal (alte oder neue Siegel-Datei, nie Müll),
 *   daher ist ein Scheitern der Neu-Siegelung krypto-konsistent: alter
 *   Siegel-Stand passt weiter zum unveränderten Bestand.
 * - Side-Car (chat_status.sidecar) bewusst NICHT Teil von P24 (kein
 *   Verlaufs-Text, nur Chip-Rang-Zeilen) — bewusster Scope-Schnitt.
 */
object HistorySeal {

    /** Format-Marke (Zeile 1 der Siegel-Datei). */
    const val FORMAT_LINE = "flipsi-p24-seal v1"

    /** EIN Algorithmus, bewusst einfach (AndroidKeyStore-HMAC). */
    const val SEAL_ALGORITHM = "HmacSHA256"

    /** Obergrenze des Torn-Append-Recovery-Strippings (missbräuchssicher). */
    const val TORN_APPEND_STRIP_LIMIT = 8

    /** Schlüssel-Quelle; null = kein Siegel-Schlüssel verfügbar
     *  (JVM-Tests / AndroidKeyStore nicht auflösbar). */
    fun interface MacSource {
        fun mac(): Mac?
    }

    enum class Verdict { UNSEALED, VALID, TAMPERED, KEY_UNAVAILABLE, RECOVERED }

    /** Ehrliche Verweigerung (fail-loud) — Aufrufer (UI-Lane) sollte sie
     *  fangen und einen Verlauf-Fehlerzustand anzeigen (String-Bedarf Notiz
     *  im Wellen-Report), statt den Prozess kippen zu lassen. */
    class SealBreach(val fileName: String, val reason: Verdict) :
        IllegalStateException("P24 Siegelmismatch ($reason): $fileName")

    /** Siegel-Datei zu einem Verlauf (verstecktes Dot-File, nie .jsonl). */
    fun sealFileFor(target: File): File =
        File(target.parentFile, "." + target.name + ".seal")

    // ── Krypto-Kern (reine Byte-Ebene) ───────────────────────────────────────

    fun compute(content: ByteArray, mac: Mac): ByteArray = mac.doFinal(content)

    /** Konstantzeit-Vergleich (MessageDigest.isEqual) — kein Timing-Orakel. */
    fun verify(content: ByteArray, expected: ByteArray, mac: Mac): Boolean =
        MessageDigest.isEqual(compute(content, mac), expected)

    // ── Siegel-Datei-Format (2 Zeilen: Marke + 64 Hex-Chars) ─────────────────

    fun formatSeal(digest: ByteArray): String =
        FORMAT_LINE + "\n" + digest.joinToString(separator = "") { byte ->
            "%02x".format(java.util.Locale.ROOT, byte)
        } + "\n"

    /** Strenges Parse (null = kaputt → TAMPERED-Klasse, nicht Falt-Rateff). */
    fun parseSeal(text: String): ByteArray? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size != 2 || lines[0] != FORMAT_LINE) return null
        val hex = lines[1]
        if (hex.length != 64) return null // SHA-256 = 32 Byte = 64 Hex-Zeichen
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    // ── Datei-Ebene ──────────────────────────────────────────────────────────

    /** Siegel schreiben (ATOMAR: .part → rename, Parität writeAtomic).
     *  true = gesiegelt; false = kein Schlüssel (P24-Ausnahme). */
    fun sealFile(target: File, source: MacSource): Boolean {
        if (!target.exists()) return false
        val mac = source.mac() ?: return false
        val digest = compute(target.readBytes(), mac)
        val seal = sealFileFor(target)
        val tmp = File(target.parentFile, "." + seal.name + ".part")
        tmp.writeText(formatSeal(digest))
        if (!tmp.renameTo(seal)) {
            // rename kann plattformbedingt über existierende Datei scheitern —
            // klassischer Fallback (Alt-Siegel bleibt bis zum Schluss lesbar).
            seal.writeText(tmp.readText())
            tmp.delete()
        }
        return true
    }

    /**
     * Verdict für eine existierende Verlauf-Datei (ohne Nebeneffekt —
     * Migration/Ausnahmen passieren nur in [readGuard]).
     * Existiert die Verlauf-Datei nicht: VALID (Guard gilt nur für Bestand).
     */
    fun verdictOf(target: File, source: MacSource): Verdict {
        if (!target.exists()) return Verdict.VALID
        val seal = sealFileFor(target)
        if (!seal.exists()) return Verdict.UNSEALED
        val mac = source.mac() ?: return Verdict.KEY_UNAVAILABLE
        val expected = parseSeal(seal.readText()) ?: return Verdict.TAMPERED
        return if (verify(target.readBytes(), expected, mac)) Verdict.VALID else Verdict.TAMPERED
    }

    /**
     * READ-GUARD (Pflicht VOR jedem Lesen einer Verlauf-Datei):
     * - VALID → durchlassen.
     * - UNSEALED → Migration (sofort siegeln, Inhalt unverändert) und
     *   erneut bewerten (mit Schlüssel: VALID; ohne: UNSEALED = offen).
     * - TAMPERED → EIN Torn-Append-Recovery (stripping bewiesen), sonst
     *   [SealBreach] (fail-closed, fail-loud).
     * - KEY_UNAVAILABLE → [SealBreach] (fail-closed).
     */
    fun readGuard(target: File, source: MacSource): Verdict {
        if (!target.exists()) return Verdict.VALID
        when (verdictOf(target, source)) {
            Verdict.VALID -> return Verdict.VALID
            Verdict.UNSEALED -> {
                if (source.mac() != null) sealFile(target, source)
                return verdictOf(target, source)
            }
            Verdict.KEY_UNAVAILABLE -> throw SealBreach(target.name, Verdict.KEY_UNAVAILABLE)
            Verdict.TAMPERED -> {
                val mac = source.mac() ?: throw SealBreach(target.name, Verdict.KEY_UNAVAILABLE)
                val expected = parseSeal(sealFileFor(target).readText())
                    ?: throw SealBreach(target.name, Verdict.TAMPERED)
                return tornAppendRecovery(target, source, mac, expected)
            }
            Verdict.RECOVERED -> return Verdict.VALID // nicht erreichbar (nur readGuard-Lieferwert)
        }
    }

    /** Siegel (inkl. .part-Relikt) zu einer Verlauf-Datei räumen. */
    fun removeSealFor(target: File) {
        val seal = sealFileFor(target)
        File(target.parentFile, "." + seal.name + ".part").delete()
        seal.delete()
    }

    // ── Torn-Append-Recovery ─────────────────────────────────────────────────

    /**
     * Nur das kryptografisch BEWIESENE Zurücksetzen auf ein gesiegeltes
     * Präfix heilt: Zeile für Zeile von hinten strippen, jeden Kandidaten
     * verifizieren; kein Match im Limit → echte Kompromittierung (throw).
     * Beweis gegen Manipulation: Stripping kann NIE etwas erhalten, was
     * nicht exakt dem gesiegelten Präfix entspricht — angehängte Fake-Zeilen
     * werden verworfen, Bestand bleibt unveränderbar.
     */
    private fun tornAppendRecovery(
        target: File,
        source: MacSource,
        mac: Mac,
        expected: ByteArray,
    ): Verdict {
        val lines = target.readLines().toMutableList()
        var stripped = 0
        while (lines.isNotEmpty() && stripped < TORN_APPEND_STRIP_LIMIT) {
            lines.removeAt(lines.size - 1)
            stripped++
            val candidate = lines.joinToString(separator = "\n", postfix = "\n")
            val bytes = candidate.toByteArray(Charsets.UTF_8)
            if (verify(bytes, expected, mac)) {
                // Crash-sicher: das bestehende (alte) Siegel passt PER DEFINI-
                // TION zum Präfix — auch ein Absturz zwischen den beiden
                // Writes lässt einen konsistenten Zustand zurück.
                writeBytesAtomic(target, bytes)
                sealFile(target, source)
                return Verdict.RECOVERED
            }
        }
        throw SealBreach(target.name, Verdict.TAMPERED)
    }

    private fun writeBytesAtomic(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, "." + target.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }
}
