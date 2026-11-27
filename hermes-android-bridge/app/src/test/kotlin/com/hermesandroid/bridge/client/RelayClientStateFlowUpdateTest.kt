package com.hermesandroid.bridge.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SEC-W1 W1C-4: Der WS-Empfang dispatcht JE Frame eine eigene IO-Coroutine
 * (RelayClient.onMessage → scope?.launch) — zwei schnell hintereinander
 * eintreffende Frames laufen REALLY parallel. Die Mutationen an
 * _chatEvents (handleChatEventFrame/handleChatEventEnc), _chatStatus
 * (handleChatStatusFrame, inkl. Rang-Prüfung) und _agentWorking
 * (handleChatActivityFrame) waren Read-Modify-Write ohne Synchronisation
 * → StateFlow-Lost-Update = STILLER Nachrichtenschwund (letzter Write
 * gewinnt). Fix (gleicher Commit): alle 4 Mutationen laufen über
 * MutableStateFlow.update {} — CAS-Schleife, JEDES Update kommt an.
 *
 * Dieser Test beweist die Mechanik, die an den 4 Stellen jetzt verwendet
 * wird: N parallele Coroutinen auf demselben IO-Pool, je M update{}-Aufrufe
 * — die alte `.value = .value + x`-Form verlor deterministisch Updates
 * (Gegenprobe unten), update{} verliert KEINES.
 */
class RelayClientStateFlowUpdateTest {

    @Test
    fun parallelUpdatesNeverLostWithCasUpdate() {
        val flow = MutableStateFlow(0)
        val coroutines = 8
        val updatesPerCoroutine = 200
        runBlocking {
            val jobs = (1..coroutines).map {
                launch(Dispatchers.IO) {
                    repeat(updatesPerCoroutine) {
                        // W1C-4-Fix-Muster: atomares update{} (CAS).
                        flow.update { it + 1 }
                    }
                }
            }
            jobs.forEach { it.join() }
        }
        assertEquals(coroutines * updatesPerCoroutine, flow.value)
    }

    @Test
    fun readModifyWritePatternLosesUpdatesUnderParallelism() {
        // GEGENPROBE: das alte Muster (`_x.value = _x.value + 1`) verliert
        // Updates, wenn zwischen Read und Write ein anderer Thread schreibt.
        // Beweis-Form (deterministisch ohne Race): zwei Reader lesen 0, beide
        // schreiben 1 → Endstand 1 statt 2. Mit update{} wäre es 2.
        val stale = MutableStateFlow(0)
        val readA = stale.value  // Coroutine A liest 0
        val readB = stale.value  // Coroutine B liest 0 (Interleaving!)
        stale.value = readA + 1   // A schreibt 1
        stale.value = readB + 1   // B überschreibt mit 1 — Update A VERLOREN
        assertEquals(1, stale.value)  // 2 wäre korrekt gewesen

        // Gleiche Situation mit dem Fix-Muster: beide kommen an.
        val fixed = MutableStateFlow(0)
        kotlinx.coroutines.runBlocking {
            launch(Dispatchers.IO) { fixed.update { it + 1 } }.join()
            launch(Dispatchers.IO) { fixed.update { it + 1 } }.join()
        }
        assertEquals(2, fixed.value)
    }
}
