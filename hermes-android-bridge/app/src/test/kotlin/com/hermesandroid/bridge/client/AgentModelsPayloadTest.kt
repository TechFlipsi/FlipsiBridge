package com.hermesandroid.bridge.client

import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W19 (17.11.26): agent.models(-enc)-Empfang — 'current' + 'effort' werden
 * aus dem Payload gelesen (Klartext UND enc, exakt dieselben Regeln) und je
 * agent_id in eigene StateFlow-Maps veröffentlicht (REPLACE, update{-CAS wie
 * in RelayClient.applyAgentModelsFrame). Fehlernde/verletzte Keys ändern
 * NICHTS (fail-soft, kein Crash, kein Reset).
 *
 * Mock-Werte sind klar als Test-Mock markiert ('test-model-…'):
 * Generik-Regel — die App kennt KEINE Modell-Namen, alles kommt
 * dynamisch vom verbundenen Gateway.
 */
class AgentModelsPayloadTest {

    private fun frame(raw: String): AgentModelsPayload.Frame? =
        AgentModelsPayload.parse(JsonParser.parseString(raw).asJsonObject)

    @Test
    fun frameWithCurrentAndEffortPublishesBothStateFlows() {
        // Anwendungsmuster wie RelayClient.applyAgentModelsFrame: je
        // Komponente ein eigener Flow, publish nur bei vorhandenem Wert.
        val modelsFlows = MutableStateFlow<Map<String, List<String>>>(emptyMap())
        val currents = MutableStateFlow<Map<String, String>>(emptyMap())
        val efforts = MutableStateFlow<Map<String, String>>(emptyMap())
        val parsed = frame(
            "{agent_id:'test-agent-x', models:['test-model-a','test-model-b']," +
                "current:'test-model-a', effort:'max'}",
        )!!

        modelsFlows.update { it + ("test-agent-x" to parsed.models!!) }
        parsed.current?.let { c -> currents.update { it + ("test-agent-x" to c) } }
        parsed.effort?.let { e -> efforts.update { it + ("test-agent-x" to e) } }

        assertEquals(
            listOf("test-model-a", "test-model-b"),
            modelsFlows.value["test-agent-x"],
        )
        assertEquals("test-model-a", currents.value["test-agent-x"])
        assertEquals("max", efforts.value["test-agent-x"])

        // REPLACE-Semantik je agent_id: nächster Frame ersetzt den Stand.
        val next = frame(
            "{agent_id:'test-agent-x', models:['test-model-c'], current:'test-model-c'}",
        )!!
        modelsFlows.update { it + ("test-agent-x" to next.models!!) }
        next.current?.let { c -> currents.update { it + ("test-agent-x" to c) } }
        next.effort?.let { e -> efforts.update { it + ("test-agent-x" to e) } }
        assertEquals(listOf("test-model-c"), modelsFlows.value["test-agent-x"])
        assertEquals("test-model-c", currents.value["test-agent-x"])
        // 'effort' fehlt im Frame → kein Reset, Bestand bleibt (fail-soft).
        assertEquals("max", efforts.value["test-agent-x"])
    }

    @Test
    fun frameWithoutCurrentAndEffortChangesNothing() {
        val currents = MutableStateFlow<Map<String, String>>(emptyMap())
        val efforts = MutableStateFlow<Map<String, String>>(emptyMap())

        val parsed = frame("{agent_id:'test-agent-y', models:['test-model-a']}")!!
        assertNull(parsed.current)
        assertNull(parsed.effort)
        parsed.current?.let { c -> currents.update { it + ("test-agent-y" to c) } }
        parsed.effort?.let { e -> efforts.update { it + ("test-agent-y" to e) } }

        assertEquals(emptyMap<String, String>(), currents.value)
        assertEquals(emptyMap<String, String>(), efforts.value)
    }

    @Test
    fun wrongTypesAreStrictlyRejectedNoGsonCoercion() {
        // Zahlen/Booleans/Objekte/Arrays sind KEINE Strings — null = kein
        // Update (isString-Pflicht, GSON-ASBOOLEAN-KÖRZION-Verband).
        assertNull(frame("{agent_id:'a', current:42, effort:true}")!!.current)
        assertNull(frame("{agent_id:'a', current:42, effort:true}")!!.effort)
        assertNull(frame("{agent_id:'a', current:{x:1}, effort:[1]}")!!.current)
        assertNull(frame("{agent_id:'a', current:{x:1}, effort:[1]}")!!.effort)
        // Leere/überlange Maschinenwörter fliegen (Parität Modell-Deckel).
        assertNull(frame("{agent_id:'a', current:'', effort:'  '}")!!.current)
        val tooLong = frame(
            "{agent_id:'a', current:'${"x".repeat(65)}', effort:'  max  '}",
        )!!
        assertNull(tooLong.current)
        assertEquals("max", tooLong.effort) // Trim + Deckel je Feld unabhängig
    }

    @Test
    fun modelsListSemanticsUnchangedCatalogParity() {
        // Getrimmt, nicht-leer, Dedup, keine Fremd-Typen, leeres Array = REPLAC.
        val parsed = frame(
            "{agent_id:'a', models:[ 'test-model-a' , 'test-model-a', 5, null, " +
                "false, '', 'test-model-b' ]}",
        )
        assertEquals(listOf("test-model-a", "test-model-b"), parsed!!.models)

        // Leeres Array = REPLACE mit leerer Liste (Adapter sagt explizit „nichts“).
        assertEquals(emptyList<String>(), frame(
            "{agent_id:'a', models:[]}",
        )!!.models)

        // Key fehlt / kein Array = null (Flow-Update wird ausgespart).
        assertNull(frame("{agent_id:'a'}")!!.models)
        assertNull(frame("{agent_id:'a', models:'klartext-model'}")!!.models)
        assertNull(frame("{agent_id:'a', models:{m:'x'}}")!!.models)

        // Deckel MAX_MODELS (Parität Relay-Gate _agent_models_ok).
        val capped = (1..AgentModelsPayload.MAX_MODELS + 5)
            .joinToString(",") { "'test-mock-$it'" }
        val cappedFrame = frame("{agent_id:'a', models:[$capped]}")!!
        assertEquals(AgentModelsPayload.MAX_MODELS, cappedFrame.models!!.size)

        // Überlanges Element (>64) wird verworfen, Rest bleibt.
        val mixed = frame(
            "{agent_id:'a', models:['${"y".repeat(65)}', 'test-mock-short']}",
        )!!
        assertEquals(listOf("test-mock-short"), mixed.models)
    }

    @Test
    fun encAndPlainShareTheSameRules() {
        // Beide Pfade laufen in RelayClient über denselben Eintritt
        // (applyAgentModelsFrame(AgentModelsPayload.parse(payload))) — die
        // Regeln sind pfad-identisch; test-freigestellt an der Regel-Klasse.
        val plain = frame(
            "{agent_id:'a', models:['test-model-d'], current:'test-model-d', effort:'low'}",
        )!!
        val encPayload = frame(
            "{'agent_id':'a','models':['test-model-d'],'current':'test-model-d','effort':'low'}",
        )!!
        assertEquals(plain, encPayload)

        assertNull(AgentModelsPayload.parse(null))
        assertNull(AgentModelsPayload.parse(JsonParser.parseString("[]").takeIf { it.isJsonObject }?.asJsonObject))
    }
}
