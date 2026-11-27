package com.hermesandroid.bridge.client

import com.google.gson.JsonObject

/**
 * W26-App (Arbeits-Brief; Frame-Verträge je Backend-Lanes —
 * w26-brief-relay.md TEIL 2 + w26-brief-adapter.md TEIL 2): der cron.add-
 * Rückkanal — rein, JVM-testbar, KEINE Android-Imports (CronRemoveRules-
 * Muster).
 *
 * FRAME-VERTRAG:
 *
 * - VOM Phone ZUM Adapter:
 *     {"type": "cron.add", "v": 1, "agent_id": <gewählter Agent>,
 *      "job": {"name": <str>, "schedule": <str>,
 *              "prompt"?: <str>, "deliver"?: <bool>}}
 *
 *   W26c (Jäger-Blocker 3): der Frame trägt die Agent-Adresse der ANLAGE —
 *   der Add-Dialog wählt den Ziel-Agent je Dropdown (die Cap-Träger der
 *   Agenten-Zentrale); der Relay routet je GENANNTEM Agenten (fail-closed
 *   NACK bei unverortbarer Adresse), NICHT mehr an die frischeste Session.
 *   schedule ist ein String ODER-Modell: Cron-Expr (einfache Wiederholung)
 *   ODER einmalig ISO-8601 je Datum+Uhrzeit — [JobSpec.mode] ist NUR die
 *   UI-Form-Fibel, am Draht steht allein der schedule-String (der Agent
 *   interpretiert).
 *
 * - ZUM Phone VOM Adapter (W23-9-Muster <frame>_ok):
 *     {"type": "cron.add_ok", "ok": <bool>, "error"?: <…>,
 *      "cron_id"?: <neue Verbriefung>, "name"?: <Echo>}
 *
 *   Fail-soft: fremde/unvollständige Struktur → null; ok-Feld fehlt ⇒ nie ein
 *   Fake-„angelegt“. Der frische cron.status (Fresh-Feed nach Erfolg) ist das
 *   ECHTE Sicht-Signal (Parität remove/pause/resume).
 *
 * CAPS (Backend-Deckel je Brief — hier bereits app-seitig gefiltert, so dass
 * nie ein Oversized-Frame den Draht sucht): name ≤ 128, schedule ≤ 128,
 * prompt ≤ 4096, deliver ≤ 64 (STRING-Pass-through je Adapter-Lane 5aa0498 —
 * kein Bool, kein erfundener Kanal). PROMPT IST PFLICHT je Gateway-Welt
 * (create_job ohne Prompt = EMPTY_PAYLOAD → 'invalid' VOR dem API-Aufruf —
 * die UI verlangt ihn je Form-Faser statt stiller Abweisung). KEIN Fake-
 * „Erfolg“ bei Überschreitung — die UI zeigt die Form-Abweisung als ehrlichen
 * Hinweis.
 */
object CronAddRules {

    const val ADD_FRAME = "cron.add"
    /** Primäre Antwort-Schreibweise (W23-9-Verbands-Muster <frame>_ok). */
    const val OK_FRAME = "cron.add_ok"

    const val ERROR_NOT_SUPPORTED = "not-supported"

    /** Warten-Deckel je Lauf (kein UI-Schleudern) — Parität CronRemoveRules. */
    const val ACK_TIMEOUT_MS = 5_000L

    const val FRAME_VERSION = 1

    // Backend-Deckel (Parität w26-brief-adapter.md TEIL 2 — je Deckel
    // fail-soft VOR dem Senden statt stiller Backend-Abweisung).
    const val NAME_MAX_CHARS = 128
    const val SCHEDULE_MAX_CHARS = 128
    const val PROMPT_MAX_CHARS = 4096
    const val DELIVER_MAX_CHARS = 64

    /** Die zwei UI-Modi des Zeitplans (am Draht bleibt der String). */
    enum class ScheduleMode { REPEAT, ONCE }

    enum class Outcome { SUCCESS, NOT_SUPPORTED, ERROR }

    /**
     * EIN Anlagen-Wunsch der UI: [name]/[schedule] tragen die Backend-Caps
     * NICHT abgeprüft in diesen Datentrag — die Prüfung läuft in [addFrame]
     * (EINE Prüf-Faser, nie eine zweite im UI-Zweig). [prompt] ist PFLICHT je
     * Gateway-Welt (Adapter-Lane 5aa0498); [deliver] bleibt der STRING-Pass-
     * through (≤ 64, kein Bool) — die UI lässt das Feld bewusst WEG (kein
     * erfundener Kanal-Name), je Brief trägt nur den Prompt-Verband.
     */
    data class JobSpec(
        val name: String,
        val schedule: String,
        val mode: ScheduleMode? = null,
        val prompt: String,
        val deliver: String? = null,
    )

    /** Geparste Antwort ([cronId] null = Alter Adapter ohne Verbriefung). */
    data class AddAck(
        val cronId: String?,
        val name: String?,
        /** Server-Urteil: false = fehlgeschlagen (Feld fehlt = false, ehrlich). */
        val ok: Boolean,
        /** Abweisungs-Grund ("not-supported", …) — null = keiner. */
        val error: String?,
    )

    private fun strOf(json: JsonObject, field: String): String? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    private fun boolOf(json: JsonObject, field: String): Boolean? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asBoolean
    }.getOrNull()

    // ── Form-Fibel je Modus (pure — die UI disabled dann EHR den Senden-Knopf).

    /**
     * Cron-Expr-Sanity (KEINE Semantik-Erfindung): ein Trimmed-Strand aus
     * 5–7 Feldern (5 klassisch ×(Min Std Tag Mon WTag), 6 mit Sekunden,
     * 7 mit Jahr), je Feld Ziffern/Platzhalter des Common-Schatzes
     * (* , - / ? # L W) oder Monats-/Wochentags-Namen. Ein Feld-Strand oder
     * leerer Wert ⇒ false (die UI sendet dann NICHT statt stiller Backend-
     * Abweisung).
     */
    fun validRepeatExpr(raw: String?): Boolean {
        val expr = (raw ?: "").trim()
        if (expr.isEmpty() || expr.length > SCHEDULE_MAX_CHARS) return false
        if (!REPEAT_CHARS.matches(expr)) return false
        val fields = expr.split(Regex("\\s+"))
        return fields.size in 5..7 && fields.all { it.isNotEmpty() }
    }

    /** Ziffern + Platzhalter-Schatz der Cron-/ISO-Expr-Familie (kein `:` Feld). */
    private val REPEAT_CHARS = Regex("^[0-9A-Za-z*/,+?#.\\-\\s]+$")

    /**
     * Einmalig-ISO je Datum+Uhrzeit — NAIV (ohne Offset) je Gateway-Konvention
     * (Adapter-Lane 5aa0498: „die Gateway-Konvention macht naive ISO aware in
     * der konfigurierten Hermes-Zeitzone“ = Europe/Vienna je Backend-Welt).
     * "2006-01-02T15:04:00". Rückgabe null = Unform (Monat/Datum/Uhrzeit
     * außer Reichweite — Wochentage/Uhrzeiten nie raten, die UI zeigt den
     * ehrlichen Form-Hinweis statt stillen Sendens).
     */
    fun onceScheduleIso(
        year: Int,
        month: Int,
        dayOfMonth: Int,
        hour: Int,
        minute: Int,
    ): String? {
        if (year !in 1..9999) return null
        if (month !in 1..12) return null
        if (dayOfMonth !in 1..daysInMonth(year, month)) return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return String.format(
            java.util.Locale.ROOT,
            "%04d-%02d-%02dT%02d:%02d:00",
            year, month, dayOfMonth, hour, minute,
        )
    }

    /** Tagedichte je Monat (Schalt-Muster je 400/100/4-Jahr-Fibel — reine Mathematik, keine Zeit-Bibliothek). */
    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        else -> 0
    }

    /** Anzeige-Faser des once-Inputs — „J-M-T“ + „HH:MM“ je Feld-Zeile. */
    fun parseOnceDatePart(raw: String?): Triple<Int, Int, Int>? {
        val m = ONCE_DATE_RE.matchEntire((raw ?: "").trim()) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d = m.groupValues[3].toIntOrNull() ?: return null
        return Triple(y, mo, d)
    }

    fun parseOnceTimePart(raw: String?): Pair<Int, Int>? {
        val m = ONCE_TIME_RE.matchEntire((raw ?: "").trim()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val mi = m.groupValues[2].toIntOrNull() ?: return null
        return h to mi
    }

    private val ONCE_DATE_RE = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$")
    private val ONCE_TIME_RE = Regex("^(\\d{1,2}):(\\d{2})$")

    /**
     * W26d (F2, UX-Report): Eingabe-Helfer des Einmalig-Datums — die Ziffern-
     * Tastatur (KeyboardType.Number) bietet den verlangten Strich an Standard-
     * IMEs KEINERLEI verlässlich an (Report-Prüfkandidat P1-Falle: roter
     * Format-Hinweis, aber kein Weg zur Eingabe in-place). Der Helfer sammelt
     * aus dem Feld NUR Ziffern und formt das selbst ein: „202611“ → „2026-11“,
     * „20261201“ → „2026-12-01“ — der Nutzer tippt NUR Ziffern, den Strich
     * setzt die Faser. Rückgabe = der zu setzende Feld-Text (max 8 Ziffern);
     * Läsch-Welt funktioniert je hinten stehendem Zeichen (der geformte
     * Strich fliegt mit raus, nichts hängt im Text fest). Das Hint-Format
     * bleibt JJJJ-MM-TT — [parseOnceDatePart] liest DENSELBEN Formen.
     */
    fun formatOnceDateInput(raw: String?): String {
        val digits = (raw ?: "").filter { it.isDigit() }.take(8)
        return buildString {
            digits.forEachIndexed { index, ch ->
                if (index == 4 || index == 6) append('-')
                append(ch)
            }
        }
    }

    /**
     * W26d (F2): Eingabe-Helfer des Einmalig-Uhrzeit-Felds — derselbe Verband
     * je Datum ([formatOnceDateInput]): „900“ → „9:00“, „0900“ → „09:00“
     * (max 4 Ziffern); der Nutzer tippt NUR Ziffern, den Doppelpunkt setzt
     * die Faser. Das Hint-Format bleibt HH:MM — [parseOnceTimePart] liest
     * DENSELBEN Formen (Zweier-Sekunden-Gruppe rechts).
     */
    fun formatOnceTimeInput(raw: String?): String {
        val digits = (raw ?: "").filter { it.isDigit() }.take(4)
        // ZF2 (W27, Suite-Nachziehen): der Doppelpunkt sitzt RECHTSBÜNDIG vor
        // der Zweier-Minuten-Gruppe (KDoc: „900“ → „9:00“, „0900“ → „09:00“)
        // — der Erststand setzte starr nach 2 Ziffern („90:0”) und der
        // Parser-Runde-Test lief rot. 3 Ziffern = Stunde einspaltig → Spalt
        // nach Ziffer 1; 4 Ziffern → Spalt nach Ziffer 2.
        val colonAfter = if (digits.length >= 3) digits.length - 2 else -1
        return buildString {
            digits.forEachIndexed { index, ch ->
                if (index == colonAfter) append(':')
                append(ch)
            }
        }
    }

    /**
     * Send-Vertrag des Anlegen-Frames: [agentId] = die im Dialog GEWÄHLTE
     * Agent-Adresse (W26c — je GENANNTEM Agenten geroutet; blank = Frame
     * unvollständig, denn eine Anlage OHNE Ziel-Agent ist kein wirksames
     * Senden); die Caps deckeln VOR dem Bauen; der
     * PROMPT IST PFLICHT (leer = null — je Gateway-Welt wäre es eine stille
     * 'invalid'-Abweisung, die UI verlangt ihn je Form-Faser); deliver bleibt
     * der optionale STRING-Pass-through (leer = Feld WEG, Parität „optionale
     * Felder“, KEIN erfundener Flag-Wert). Name bleibt UI-Pflicht (die Zeilen
     * des cron.status-Feeds tragen ansonsten nur „?“).
     */
    fun addFrame(spec: JobSpec, agentId: String?): JsonObject? {
        val agent = (agentId ?: "").trim()
        if (agent.isEmpty()) return null
        val name = spec.name.trim()
        val schedule = spec.schedule.trim()
        val prompt = spec.prompt.trim()
        if (name.isEmpty() || name.length > NAME_MAX_CHARS) return null
        if (schedule.isEmpty() || schedule.length > SCHEDULE_MAX_CHARS) return null
        if (prompt.isEmpty() || prompt.length > PROMPT_MAX_CHARS) return null
        val deliver = spec.deliver?.trim()
        if (deliver != null && deliver.length > DELIVER_MAX_CHARS) return null
        // Form-Fibel je Modus: repeat = Expr-Sanity (5–7 Felder je Platzhalter-
        // Schatz), once = der naive ISO-Strand aus dem Dialog (Adapter-Konvention:
        // die Gateway-Welt macht naive ISO aware in ihrer konfigurierten Zone).
        if (spec.mode == ScheduleMode.REPEAT && !validRepeatExpr(schedule)) return null
        val job = JsonObject().apply {
            addProperty("name", name)
            addProperty("schedule", schedule)
            addProperty("prompt", prompt)
            if (!deliver.isNullOrEmpty()) addProperty("deliver", deliver)
        }
        return JsonObject().apply {
            addProperty("type", ADD_FRAME)
            addProperty("v", FRAME_VERSION)
            addProperty("agent_id", agent)
            add("job", job)
        }
    }

    /**
     * Ack-Verband: null = fremde/unvollständige Struktur (verwerfen —
     * fail-soft); ok-Feld fehlt ⇒ nie ein Fake-„angelegt“.
     */
    fun parse(json: JsonObject): AddAck? {
        val type = strOf(json, "type") ?: return null
        if (type != OK_FRAME) return null
        return AddAck(
            cronId = strOf(json, "cron_id")?.takeIf { it.isNotBlank() },
            name = strOf(json, "name")?.takeIf { it.isNotBlank() },
            ok = boolOf(json, "ok") ?: false,
            error = strOf(json, "error")?.takeIf { it.isNotBlank() },
        )
    }

    /** Ausgang → Snack-Staffel der UI (Parität remove/pause/resume). */
    fun outcome(ack: AddAck): Outcome = when {
        ack.ok -> Outcome.SUCCESS
        ack.error == ERROR_NOT_SUPPORTED -> Outcome.NOT_SUPPORTED
        else -> Outcome.ERROR
    }
}
