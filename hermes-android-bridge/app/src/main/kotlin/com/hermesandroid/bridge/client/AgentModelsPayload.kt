package com.hermesandroid.bridge.client

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * W19 (17.11.26, Sir-Befund): agent.models / agent.models.enc-Payload-Regeln —
 * reine Logik-Einheit (JVM-getestet, gleiche Faser wie [AgentStatusRules]).
 * Der Payload trägt NEBEN dem Modell-Katalog zusätzlich 'current' (das
 * aktuell vom Gateway laufende Modell) und 'effort' (Denken-Stufe) — beide
 * blieben unverarbeitet, deshalb stand der Think-Slider bei Neuinstallation
 * dauerhaft auf Raste 0 („aus“) und das laufende Modell wurde nirgends
 * angezeigt. Der Adapter (und nur der Adapter) ist die Quelle — die App
 * kennt KEINE Modell-Namen, KEINE Fallback-Listen (Sir-Generik-Regel,
 * immer-on): was nicht im Frame kommt, gibt es app-seitig nicht.
 *
 * Fail-soft-Vertrag (Portieren der parseAgentModels-Disziplin):
 * - 'models' (JsonArray, Pflicht-Element Strings, getrimmt, nicht-leer,
 *   Dedup, Deckel [MAX_MODELS]) — Key fehlt / kein Array = null →
 *   Flow-Update AUSGESPART (Bestand bleibt); leeres Array = REPLACE mit
 *   „keine Modelle“ (ehrlich, so sendet es der Adapter).
 * - 'current' / 'effort': strikt EIN JSON-String (getrimmt, nicht-leer,
 *   ≤ [MAX_VALUE_LEN], Parität Modell-Deckel) — Key fehlt / falscher Typ =
 *   null → kein Update, kein Crash. Zahlen/Booleans/Objekte werden NICHT
 *   koerziert (isString-Pflicht — Gson fängt String-Primitive sonst still,
 *   GSON-ASBOOLEAN-KÖRZION-Verband).
 *
 * REPLACE-Semantik je agent_id: ein gültiger Wert ERSETZT den bisherigen
 * Stand (letzter Frame gewinnt); fehlender Key ändert NICHTS (kein Reset).
 */
object AgentModelsPayload {

    /** Parität zum Relay-Gate _agent_models_ok (Katalog-Deckel). */
    const val MAX_MODELS = 24

    /** Parität Modell-Deckel: current/effort bleiben kurze Maschinenwörter. */
    const val MAX_VALUE_LEN = 64

    /**
     * Geparste Payload-Auswirkung — JEDE Komponente unabhängig null
     * (Key fehlt oder verletzt): null = Flow-Update zu DIESEM Feld
     * ausgespart, nicht „leeren“.
     */
    data class Frame(
        val models: List<String>?,
        val current: String?,
        val effort: String?,
    )

    /** Payload (Klartext-Frame ODER enc-Entschlüsselung) streng lesen. */
    fun parse(payload: JsonObject?): Frame? {
        if (payload == null) return null
        return Frame(
            models = parseModelList(payload.get("models")),
            current = parseStrictString(payload.get("current")),
            effort = parseStrictString(payload.get("effort")),
        )
    }

    /**
     * models-Array streng lesen (V16-Disziplin unverändert): Strings,
     * getrimmt, nicht-leer, Dedup, Deckel [MAX_MODELS]. null (Feld fehlt) =
     * Update auslassen (fail-soft).
     */
    private fun parseModelList(raw: JsonElement?): List<String>? {
        val arr = raw as? JsonArray ?: return null
        val out = ArrayList<String>(arr.size())
        for (element in arr) {
            val s = runCatching {
                element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }.getOrNull()?.trim() ?: continue
            if (s.isEmpty() || s.length > MAX_VALUE_LEN) continue
            if (out.size >= MAX_MODELS) break
            if (s !in out) out.add(s)
        }
        return out
    }

    /**
     * Strikt EIN JSON-String (getrimmt, nicht-leer, ≤ [MAX_VALUE_LEN]).
     * Fehlend / falscher Typ / verletzt = null — Zahlen- und Boolean-Koerzion
     * bleiben ausgeschlossen (isJsonPrimitive.isString-Pflicht).
     */
    private fun parseStrictString(raw: JsonElement?): String? {
        val s = runCatching {
            (raw as? JsonPrimitive)?.takeIf { it.isString }?.asString
        }.getOrNull()?.trim() ?: return null
        return s.takeIf { it.isNotEmpty() && it.length <= MAX_VALUE_LEN }
    }
}
