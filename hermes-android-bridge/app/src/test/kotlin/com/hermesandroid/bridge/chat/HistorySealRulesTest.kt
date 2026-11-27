package com.hermesandroid.bridge.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * P24 (fixliste 24): Siegel-Verträge der Verlauf-Dateien — Seal/Verify/
 * Tamper-Erkennung/Migration/Torn-Recovery/Key-unavailable, JVM-rein über
 * einen JCE-Mock-Key (gleiche javax.crypto.Mac-Schnittstelle wie der echte
 * AndroidKeyStore-HMAC im ChatHistoryStore.AndroidHistorySealKey).
 *
 * Die Store-Verdrahtung (Guard je load/append, Befund-Zettel lastSealBreach)
 * deckt ChatHistoryStoreTest mit ab — hier liegt die Siegel-Semantik.
 */
class HistorySealRules {

    @get:Rule
    val tmp = TemporaryFolder()

    /** KeyStore-MOCK: JCE-HMAC mit festem 32-Byte-Key (Test-Only-Never-Prod). */
    private fun keySource(): HistorySeal.MacSource {
        val key = ByteArray(32) { it.toByte() }
        return HistorySeal.MacSource {
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        }
    }

    private val nullSource = HistorySeal.MacSource { null }

    private fun file(
        name: String = "alpha_c0ffee000001.jsonl",
        content: String = "l1\nl2\n",
    ): File {
        val f = File(tmp.root, name)
        f.writeText(content)
        return f
    }

    // ── Seal/Verify ──────────────────────────────────────────────────────────

    @Test
    fun sealThenVerifyRoundtrip() {
        val target = file()
        assertTrue(HistorySeal.sealFile(target, keySource()))

        val seal = HistorySeal.sealFileFor(target)
        assertTrue(seal.exists())
        assertFalse(seal.name.endsWith(".jsonl")) // nie Verlaufs-Parse-Futter
        assertTrue(seal.name.startsWith("."))      // versteckt neben dem Verlauf

        assertEquals(HistorySeal.Verdict.VALID, HistorySeal.verdictOf(target, keySource()))
        assertEquals(HistorySeal.Verdict.VALID, HistorySeal.readGuard(target, keySource()))
    }

    @Test
    fun sealFormatParsesStrictly() {
        val target = file()
        assertTrue(HistorySeal.sealFile(target, keySource()))
        val sealText = HistorySeal.sealFileFor(target).readText()
        val parsed = HistorySeal.parseSeal(sealText)
        assertEquals(32, parsed?.size) // SHA-256 = 32 Byte
        // Zeile 1 = Format-Marke, Zeile 2 = 64 Hex-Zeichen:
        val lines = sealText.trim().lines()
        assertEquals(HistorySeal.FORMAT_LINE, lines[0])
        assertEquals(64, lines[1].length)

        assertNull(HistorySeal.parseSeal("müll ohne marke"))
        assertNull(HistorySeal.parseSeal(HistorySeal.FORMAT_LINE + "\nzzzz")) // kein Hex
        assertNull(HistorySeal.parseSeal(HistorySeal.FORMAT_LINE + "\n" + "a".repeat(63)))
        assertNull(HistorySeal.parseSeal(HistorySeal.FORMAT_LINE + "\n" + "a".repeat(65)))
    }

    @Test
    fun verifyIsContentSensitive() {
        val target = file()
        assertTrue(HistorySeal.sealFile(target, keySource()))
        val expected = HistorySeal.parseSeal(HistorySeal.sealFileFor(target).readText())!!
        val mac = keySource().mac()!!
        assertTrue(HistorySeal.verify("l1\nl2\n".toByteArray(), expected, mac))
        assertFalse(HistorySeal.verify("l1\nl2\nGEFÄLSCHT\n".toByteArray(), expected, mac))
        assertNotEquals(
            HistorySeal.compute("l1\nl2\n".toByteArray(), mac),
            HistorySeal.compute("l1\nl3\n".toByteArray(), mac),
        )
    }

    // ── Tamper-Erkennung (fail-closed/fail-loud) ─────────────────────────────

    @Test
    fun middleTamperIsDetectedAndLoadIsRefused() {
        val target = file(content = "l1\nl2\nl3\n")
        assertTrue(HistorySeal.sealFile(target, keySource()))
        target.writeText("l1\nL2 GEÄNDERT\nl3\n") // Mitte verändert (kein Append-Reset)

        assertEquals(HistorySeal.Verdict.TAMPERED, HistorySeal.verdictOf(target, keySource()))
        val breach = runCatching { HistorySeal.readGuard(target, keySource()) }
            .exceptionOrNull()
        assertTrue(breach is HistorySeal.SealBreach)
        assertEquals(HistorySeal.Verdict.TAMPERED, (breach as HistorySeal.SealBreach).reason)
        assertEquals(target.name, breach.fileName)
    }

    @Test
    fun truncationTamperIsDetected() {
        val target = file(content = "l1\nl2\nl3\n")
        assertTrue(HistorySeal.sealFile(target, keySource()))
        target.writeText("l1\nl2\n") // Verlauf verkürzt = Manipulation

        assertEquals(HistorySeal.Verdict.TAMPERED, HistorySeal.verdictOf(target, keySource()))
    }

    @Test
    fun corruptSealFileIsTamperedClass() {
        val target = file()
        assertTrue(HistorySeal.sealFile(target, keySource()))
        HistorySeal.sealFileFor(target).writeText("kaputt")

        assertEquals(HistorySeal.Verdict.TAMPERED, HistorySeal.verdictOf(target, keySource()))
    }

    @Test
    fun fakeAppendedLinesAreDiscardedNotLoaded() {
        // Angehängte Fake-Zeilen mit altem Siegel: Torn-Recovery verwirft sie —
        // der Angreifer erreicht damit NUR Verwerfen des Appends (die Fake-
        // Zeilen laden nie), der Bestand bleibt unveränderbar.
        val target = file(content = "l1\nl2\n")
        assertTrue(HistorySeal.sealFile(target, keySource()))
        target.writeText("l1\nl2\nfake\nfake2\n")

        assertEquals(
            HistorySeal.Verdict.RECOVERED,
            HistorySeal.readGuard(target, keySource()),
        )
        assertEquals("l1\nl2\n", target.readText())
        assertEquals(HistorySeal.Verdict.VALID, HistorySeal.verdictOf(target, keySource()))
    }

    // ── Torn-Append (Stromausfall) ───────────────────────────────────────────

    @Test
    fun tornAppendRecoversToSealedPrefix() {
        val target = file(content = "l1\nl2\n")
        assertTrue(HistorySeal.sealFile(target, keySource()))
        // Gerissener Append (kein Zeilen-Ende, kein Neu-Siegel — z. B.
        // Stromausfall zwischen Inhalt-Write und Siegel-Write):
        target.writeText("l1\nl2\ntornline")

        assertEquals(HistorySeal.Verdict.RECOVERED, HistorySeal.readGuard(target, keySource()))
        assertEquals("l1\nl2\n", target.readText())
        assertEquals(HistorySeal.Verdict.VALID, HistorySeal.verdictOf(target, keySource()))
    }

    @Test
    fun tornAppendStripLimitBoundsRecovery() {
        val target = file(content = "l1\nl2\n")
        assertTrue(HistorySeal.sealFile(target, keySource()))
        // Mehr Fake-Zeilen als das Stripping-Limit heilt — echte Kompromittierung:
        target.writeText("l1\nl2\n" + "fake\n".repeat(9))

        val breach = runCatching { HistorySeal.readGuard(target, keySource()) }
            .exceptionOrNull()
        assertTrue(breach is HistorySeal.SealBreach)
        assertEquals(HistorySeal.Verdict.TAMPERED, (breach as HistorySeal.SealBreach).reason)
    }

    // ── Migration (UNSEALED → Siegel ohne Verlust) ───────────────────────────

    @Test
    fun unsealedHistoryMigratesWithoutLoss() {
        val target = file(content = "bestand\nalt\n")
        val bytesBefore = target.readBytes()

        assertEquals(HistorySeal.Verdict.VALID, HistorySeal.readGuard(target, keySource()))
        assertTrue(HistorySeal.sealFileFor(target).exists())
        assertEquals(bytesBefore.toList(), target.readBytes().toList()) // 0 Verlust
        assertEquals(HistorySeal.Verdict.VALID, HistorySeal.verdictOf(target, keySource()))
        // Idempotent: zweiter Guard re-siegelt nicht kaputt:
        assertEquals(HistorySeal.Verdict.VALID, HistorySeal.readGuard(target, keySource()))
        assertEquals(bytesBefore.toList(), target.readBytes().toList())
    }

    @Test
    fun unsealedWithoutKeyStaysOpenJvmConvention() {
        val target = file()
        assertEquals(
            HistorySeal.Verdict.UNSEALED,
            HistorySeal.readGuard(target, nullSource), // JVM-Testumgebung
        )
        assertFalse(HistorySeal.sealFileFor(target).exists())
    }

    // ── KEY_UNAVAILABLE (fail-closed) ────────────────────────────────────────

    @Test
    fun sealWithoutKeyRefusesLoad() {
        val target = file()
        assertTrue(HistorySeal.sealFile(target, keySource()))

        assertEquals(
            HistorySeal.Verdict.KEY_UNAVAILABLE,
            HistorySeal.verdictOf(target, nullSource),
        )
        val breach = runCatching { HistorySeal.readGuard(target, nullSource) }
            .exceptionOrNull()
        assertTrue(breach is HistorySeal.SealBreach)
        assertEquals(
            HistorySeal.Verdict.KEY_UNAVAILABLE,
            (breach as HistorySeal.SealBreach).reason,
        )
    }

    @Test
    fun missingTargetIsNoBreach() {
        val target = File(tmp.root, "does_not_exist.jsonl")
        assertEquals(HistorySeal.Verdict.VALID, HistorySeal.readGuard(target, keySource()))
        assertFalse(HistorySeal.sealFileFor(target).exists())
    }

    // ── Siegel-Räumung (Chat-löschen-Verband) ────────────────────────────────

    @Test
    fun removeSealClearsSealFileOnly() {
        val target = file()
        assertTrue(HistorySeal.sealFile(target, keySource()))
        assertTrue(HistorySeal.sealFileFor(target).exists())

        HistorySeal.removeSealFor(target)
        assertFalse(HistorySeal.sealFileFor(target).exists())
        assertEquals(HistorySeal.Verdict.UNSEALED, HistorySeal.verdictOf(target, keySource()))
    }

    @Test
    fun sealFileWithNullKeyIsNoOp() {
        val target = file()
        assertFalse(HistorySeal.sealFile(target, nullSource))
        assertFalse(HistorySeal.sealFileFor(target).exists())
    }

    @Test
    fun sealFileWithMissingTargetIsNoOp() {
        val target = File(tmp.root, "never.jsonl")
        assertFalse(HistorySeal.sealFile(target, keySource()))
        assertFalse(HistorySeal.sealFileFor(target).exists())
    }

    // ── W22 A2 (P5): Call-Einträge stehen MIT in der Siegel-Kalkulation ─────

    /**
     * Das Siegel läuft über den GESAMTEN Datei-Inhalt — die neue Eintrags-Art
     * kind=call (callInfo-Felder, leerer text) siegelt automatisch mit, ohne
     * dass HistorySeal die Art kennt. Beweis-Kanten mit Call-Zeilen:
     * - in-place veränderte Call-Zeile (Dauer-Doktor) = TAMPERED/SealBreach
     *   (Mittel-Manipulation, KEIN Append → keine Torn-Heilung),
     * - vollzeiliger Call-Anhang ohne Neu-Siegel = RECOVERED (Torn-Append-
     *   Vertrag: Append-artige Zufälle heilen auf das bewiesene Präfix),
     * - legitimer Neu-Siegel nach Call-Append = wieder VALID.
     */
    @Test
    fun callEntriesInSealCalculation() {
        val callLine = "{\"seq\":2,\"agentId\":\"alpha\",\"chatId\":\"c\",\"fromAgent\":false," +
            "\"text\":\"\",\"time\":\"12:01\"," +
            "\"callInfo\":{\"direction\":\"incoming\",\"missed\":false,\"durationSec\":161}}\n"
        val textLine = "{\"seq\":1,\"agentId\":\"alpha\",\"chatId\":\"c\"," +
            "\"fromAgent\":false,\"text\":\"Hallo Agent\",\"time\":\"12:00\"}\n"

        // Kante 1: in-place veränderte Call-Zeile = TAMPERED (fail-closed).
        val tampered = File(tmp.root, "alpha_c0ffee000010.jsonl")
        tampered.writeText(textLine + callLine)
        assertTrue(HistorySeal.sealFile(tampered, keySource()))
        tampered.writeText(
            textLine + callLine.replace("\"durationSec\":161", "\"durationSec\":999"),
        )
        assertEquals(HistorySeal.Verdict.TAMPERED, HistorySeal.verdictOf(tampered, keySource()))
        val breach = runCatching { HistorySeal.readGuard(tampered, keySource()) }
            .exceptionOrNull()
        assertTrue(breach is HistorySeal.SealBreach)
        assertEquals(HistorySeal.Verdict.TAMPERED, (breach as HistorySeal.SealBreach).reason)

        // Kante 2: vollzeiliger Call-Anhang ohne Neu-Siegel = RECOVERED
        // (Torn-Append-Vertrag — ein Anhang IST nicht vom Crash-Anhang
        // unterscheidbar; die Recovery verwirft ihn auf das bewiesene Präfix).
        val appended = File(tmp.root, "alpha_c0ffee000011.jsonl")
        appended.writeText(textLine)
        assertTrue(HistorySeal.sealFile(appended, keySource()))
        appended.appendText(callLine)
        assertEquals(
            HistorySeal.Verdict.RECOVERED,
            HistorySeal.readGuard(appended, keySource()),
        )

        // Kante 3: legitimer Weg — nach Call-Append NEU siegeln = VALID.
        val legit = File(tmp.root, "alpha_c0ffee000012.jsonl")
        legit.writeText(textLine)
        assertTrue(HistorySeal.sealFile(legit, keySource()))
        legit.appendText(callLine)
        assertTrue(HistorySeal.sealFile(legit, keySource()))
        assertEquals(HistorySeal.Verdict.VALID, HistorySeal.readGuard(legit, keySource()))
    }
}
