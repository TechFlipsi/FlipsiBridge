package com.hermesandroid.bridge.client

import com.google.gson.JsonObject

/**
 * W26-App (Arbeits-Brief TEIL 2 „Agent hinzufügen“; Relay-Vertrag
 * je LANE-R — W26-Self-Enrollment, gelesen am Live-Verband des Relays): der
 * agent.invite-Verband (Admin-Gerät) — rein, JVM-testbar, KEINE Android-Imports
 * (CronRemoveRules-Muster, gson-parsing, fail-soft).
 *
 * FRAME-VERTRAG (Phone → Relay, NUR Admin-Geräte — Auth-Welt je
 * relay-devices.json admin-Flag, sonst „denied“ je Ack):
 *
 *   {type:"agent.invite.create", v:1, label?}
 *     → {type:"agent.invite.create_ok", v:1, ok, invite_id, invite_code,
 *        ttl_min, uses, request_id?, ts?} — Fehlerfamilien „denied“ (kein
 *        Admin) / „too-many“ (Cap offener Einladungen)
 *
 *   {type:"agent.invite.list", v:1}
 *     → {type:"agent.invite.list_ok", v:1, ok, invites:[…]} — je Zeile NUR
 *        öffentliche Felder (PII-Regel des Relays: der CODE bleibt im
 *        create-Ack + dem 0600-File, nie in Listen): {invite_id, hint
 *        (maskierter Code-Blick), created_at (ms), expires_at (ms), ttl_min,
 *        uses_left, label?, revoked?, used_at?, redeemed_agent?}
 *
 *   {type:"agent.invite.revoke", v:1, invite_id}
 *     → {type:"agent.invite.revoke_ok", v:1, ok, invite_id, request_id?, ts?}
 *        — Fehlerfamilien „denied“ / „not-found“
 *
 * Fail-soft: fremde/unvollständige Struktur → null (verwerfen); ok-Feld fehlt
 * ⇒ nie ein Fake-Erfolg. Der frische agent.invite.list nach Create/Revoke ist
 * das ECHTE Sicht-Signal (Parität cron.status Fresh-Feed-Muster) — die UI
 * holt sie je Erfolg nach ([Op.LIST]).
 */
object AgentInviteRules {

    const val REQUEST_CREATE = "agent.invite.create"
    const val REQUEST_LIST = "agent.invite.list"
    const val REQUEST_REVOKE = "agent.invite.revoke"

    const val FRAME_VERSION = 1

    /** Warten-Deckel je Lauf (kein UI-Schleudern) — Parität DeviceAliasRules. */
    const val ACK_TIMEOUT_MS = 5_000L

    const val ERROR_NOT_SUPPORTED = "not-supported"

    /** Die drei Admin-Wege (der Ack trägt den Op-Weg zurück an die UI). */
    enum class Op { CREATE, LIST, REVOKE }

    /**
     * EIN aktiver Einladungs-Eintrag der list_ok-Zeilen (PII-Regel: KEIN
     * Volle-Code — nur [inviteId] + [hint], das maskierte Code-Blik).
     */
    data class InviteEntry(
        val inviteId: String,
        /** Maskierter Code-Blick des Relays („ab12cd••••••••“); leer = fehlt. */
        val hint: String,
        val ttlMin: Long? = null,
        /** Verbleibende Verwendungen (Frame-Rohstand uses_left). */
        val usesLeft: Long? = null,
        /** Anlegung (Unix-ms je Frame; 0/fehlt = unbekannt → null). */
        val createdAtMs: Long? = null,
        /** Ablauf (Unix-ms je Frame; fehlt = null). */
        val expiresAtMs: Long? = null,
        val label: String = "",
        val revoked: Boolean = false,
    )

    /** Ergiebiges Create-Ergebnis (Code-Anzeige groß + TTL-Hinweis). */
    data class CreateResult(
        val inviteCode: String,
        /** Relay-Handle der Zeilen-Welt (die Revoke-Adresse der Liste). */
        val inviteId: String = "",
        val ttlMin: Long? = null,
        val uses: Long? = null,
        /**
         * W26d (F3): Anlegung-Zeitpunkt je create-Ack (Relay trägt `ts`
         * Unix-ms) — DIE Uhr macht die TTL-Welt lebendig: ohne sie stünde
         * „Gültig 60 Min" noch nach Stunden, ewig lebendig (Report-F3).
         */
        val createdAtMs: Long = 0L,
    )

    /** Geparstes Create-Ergebnis ([result] null bei error/denied/too-many). */
    data class CreateAck(
        val ok: Boolean,
        val error: String? = null,
        val result: CreateResult? = null,
    )

    /** Geparstes Revoke-Ergebnis. */
    data class RevokeAck(
        val inviteId: String?,
        /** Server-Urteil: false = fehlgeschlagen (Feld fehlt = false, ehrlich). */
        val ok: Boolean,
        val error: String? = null,
    )

    // ── Sende-Verträge (pure) ─────────────────────────────────────────────────

    fun createFrame(): JsonObject = JsonObject().apply {
        addProperty("type", REQUEST_CREATE)
        addProperty("v", FRAME_VERSION)
    }

    fun listFrame(): JsonObject = JsonObject().apply {
        addProperty("type", REQUEST_LIST)
        addProperty("v", FRAME_VERSION)
    }

    /**
     * Revoke je Relay-Verband: PRIMARY-Zielkennung = [inviteId] (das Handle
     * der list_ok-Zeilen — der Code selbst ist in Listen NICHT öffentlich,
     * PII-Regel). null = unvollständig (Blank-Handle) — der Aufrufer sendet
     * dann NICHT.
     */
    fun revokeFrame(inviteId: String?): JsonObject? {
        val id = (inviteId ?: "").trim()
        if (id.isEmpty()) return null
        return JsonObject().apply {
            addProperty("type", REQUEST_REVOKE)
            addProperty("v", FRAME_VERSION)
            addProperty("invite_id", id)
        }
    }

    // ── Parse-Verbände (je Weg exakt eine Familie, fail-soft) ─────────────────

    private fun strOf(json: JsonObject, field: String): String? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

    private fun boolOf(json: JsonObject, field: String): Boolean? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asBoolean
    }.getOrNull()

    private fun longOf(json: JsonObject, field: String): Long? = runCatching {
        json.get(field)?.takeIf { it.isJsonPrimitive }?.asLong
    }.getOrNull()

    private fun typeOf(json: JsonObject): String? =
        strOf(json, "type")?.trim()

    /** Parse je CREATE-Antwort — null = fremder/unvollständiger Frame. */
    fun parseCreate(json: JsonObject): CreateAck? {
        val type = typeOf(json) ?: return null
        if (type != "agent.invite.create_ok" && type != "agent.invite.created") return null
        val error = strOf(json, "error")?.takeIf { it.isNotBlank() }
        val code = strOf(json, "invite_code")?.trim()?.takeIf { it.isNotEmpty() }
        val inviteId = strOf(json, "invite_id")?.trim()?.takeIf { it.isNotEmpty() }
        val result = code?.let {
            CreateResult(
                inviteCode = it,
                inviteId = inviteId.orEmpty(),
                ttlMin = longOf(json, "ttl_min")?.takeIf { t -> t > 0L },
                uses = longOf(json, "uses")?.takeIf { u -> u > 0L },
                // W26d (F3): die Anlegung-Uhr kommt je Ack mit (fail-soft: fehlt
                // sie, bleibt die Anzeige ohne Uhr — nie eine erfundene).
                createdAtMs = longOf(json, "ts")?.takeIf { t -> t > 0L } ?: 0L,
            )
        }
        return CreateAck(ok = boolOf(json, "ok") ?: false, error = error, result = result)
    }

    /** Parse je LIST-Antwort — null = fremder Frame; Zeilen ohne Handle fallen. */
    fun parseList(json: JsonObject): List<InviteEntry>? {
        val type = typeOf(json) ?: return null
        if (type != "agent.invite.list_ok" &&
            type != "agent.invite.listed" &&
            type != "agent.invite.list"
        ) {
            return null
        }
        val arr = runCatching {
            json.get("invites")?.takeIf { it.isJsonArray }?.asJsonArray
        }.getOrNull() ?: return emptyList() // leere Liste ist ein EHRLICHES Ergebnis
        val out = ArrayList<InviteEntry>(arr.size())
        for (element in arr) {
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val inviteId = strOf(row, "invite_id")?.trim() ?: continue
            if (inviteId.isEmpty()) continue
            out += InviteEntry(
                inviteId = inviteId,
                hint = strOf(row, "hint")?.trim().orEmpty(),
                ttlMin = longOf(row, "ttl_min")?.takeIf { it > 0L },
                usesLeft = longOf(row, "uses_left")?.takeIf { it >= 0L },
                createdAtMs = longOf(row, "created_at")?.takeIf { it > 0L },
                expiresAtMs = longOf(row, "expires_at")?.takeIf { it > 0L },
                label = strOf(row, "label")?.trim().orEmpty(),
                revoked = boolOf(row, "revoked") ?: false,
            )
        }
        return out
    }

    /** Parse je REVOKE-Antwort — null = fremder/unvollständiger Frame. */
    fun parseRevoke(json: JsonObject): RevokeAck? {
        val type = typeOf(json) ?: return null
        if (type != "agent.invite.revoke_ok" && type != "agent.invite.revoked") return null
        return RevokeAck(
            inviteId = strOf(json, "invite_id")?.takeIf { it.isNotBlank() },
            ok = boolOf(json, "ok") ?: false,
            error = strOf(json, "error")?.takeIf { it.isNotBlank() },
        )
    }

    // ── Admin-Welt (NIE-WRONG, Parität DeviceRules.ownMaskedDeviceId-Gate) ────

    /**
     * Der Agenten-hinzufügen-Flow existiert NUR je Admin-Geräte-Welt (fail-
     * closed je Relay: kein Admin = „denied“ je Ack): ein NIE-WRONG aufgelöster
     * EIGEN Geräteträger ([ownMaskedDeviceId] — DeviceRules.ownMaskedDeviceId:
     * null = mehrdeutig/kein Beweis) MUSS die admin-Flag tragen. MEHRDEUTIG/
     * unbekannt (null) ⇒ false (die Karte bleibt verborgen — never-wrong vor
     * always-right, KEIN Fake-Admin-Angebot).
     */
    fun isAdminDevice(devices: List<AdminDeviceRef>, ownMaskedDeviceId: String?): Boolean {
        val mask = ownMaskedDeviceId?.trim().orEmpty()
        if (mask.isEmpty()) return false
        return devices.any { it.deviceId == mask && it.admin }
    }

    /** Anzeige-Einheit verengt: Maske + Admin-Merker (Spiegel DeviceEntry-Felder). */
    data class AdminDeviceRef(
        val deviceId: String,
        val admin: Boolean,
    )

    // ── W26d (F3): Code-Anzeige je TTL/Revoke leben — nie ein Toten-Code ─────

    /** Ende-Zustand des zuletzt angezeigten Codes (kein Ende = null). */
    enum class InviteCodeEnd { LAPSED, REVOKED }

    /** Abgelaufen? [result.lapsed] je TTL-Uhr (fail-soft ohne Uhr = nie lapsed). */
    fun inviteLapsed(result: CreateResult?, nowMs: Long): Boolean {
        val ttl = result?.ttlMin ?: return false
        if (ttl < 0L) return false
        val ts = result.createdAtMs
        if (ts <= 0L) return false // ohne Anlegung-Uhr keine erfundene Frist
        return nowMs >= ts + ttl * 60_000L
    }

    /**
     * Widerruf trifft DEN angezeigten Code? TRUE erfordert einen Beweis an
     * BEIDEN Seiten: der Code-Verband trägt sein Relay-Handle ([CreateResult.
     * inviteId]) UND die revoke_ok-Antwort nennt DASSELBE [ackInviteId] —
     * blank/abweichende Handles widersprechen NIEMALS ein Lebenden-Code (der
     * Code-Verband bleibt, nie ein falsch-positives Räumen).
     */
    fun revokeMatchesCode(result: CreateResult?, ackInviteId: String?): Boolean {
        val shown = result?.inviteId?.trim().orEmpty()
        val revoked = ackInviteId?.trim().orEmpty()
        if (shown.isEmpty() || revoked.isEmpty()) return false
        return shown == revoked
    }

    // ── W26d (F4): Restzeit statt Anlage-TTL — die Daten sind DA ─────────────

    /**
     * Zeit-Wort einer Einladungs-Zeile: Restzeit je [InviteEntry.expiresAtMs]
     * (der Relay trägt sie je list_ok-Zeile — das UI las sie NIE, Report-F4),
     * unter 5 Min Rest = [urgent] (ehrlich kritisch). Ohne expiry (alter
     * Relay) bleibt das Anläge-TTL-Wort ([ttlMin], der Bestand — nie eine
     * erfundene Restzeit). Beides fehlt → null (kein Zeit-Wort).
     */
    data class InviteTimeWord(
        val res: Int,
        val minutes: Int,
        val urgent: Boolean,
    )

    fun invitationTimeWord(
        expiresAtMs: Long?,
        ttlMin: Long?,
        nowMs: Long,
        resRemaining: Int,
        resRemainingSoon: Int,
        resExpired: Int,
        resTtl: Int,
    ): InviteTimeWord? {
        if (expiresAtMs != null && expiresAtMs > 0L) {
            val remainMin = (expiresAtMs - nowMs) / 60_000L
            if (remainMin <= 0L) return InviteTimeWord(resExpired, 0, urgent = true)
            val minutes = remainMin.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return InviteTimeWord(
                res = if (remainMin < 5L) resRemainingSoon else resRemaining,
                minutes = minutes,
                urgent = remainMin < 5L,
            )
        }
        // Anlage-TTL als Bestand (alter Relay ohne expiry — ehrlich Form):
        val ttl = ttlMin?.takeIf { it > 0L } ?: return null
        return InviteTimeWord(resTtl, ttl.toInt(), urgent = false)
    }
}
