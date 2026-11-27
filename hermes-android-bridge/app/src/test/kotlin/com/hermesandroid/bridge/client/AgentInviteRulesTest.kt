package com.hermesandroid.bridge.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W26-App: JVM-Tests des agent.invite-Verbands (Admin-Gerät-Welt — Sende-
 * Verträge, fail-soft-Parse je Familie, NIE-WRONG Admin-Gate; Vertrags-Sync
 * je LANE-R: Revoke je invite_id, Listen-Zeilen NUR öffentliche Felder).
 */
class AgentInviteRulesTest {

    private fun json(raw: String): JsonObject =
        JsonParser.parseString(raw).asJsonObject

    // ── Sende-Verträge ────────────────────────────────────────────────────────

    @Test
    fun createAndListCarryTypeAndVersion() {
        assertEquals(
            AgentInviteRules.REQUEST_CREATE,
            AgentInviteRules.createFrame().get("type").asString,
        )
        assertEquals(1, AgentInviteRules.createFrame().get("v").asInt)
        assertEquals(
            AgentInviteRules.REQUEST_LIST,
            AgentInviteRules.listFrame().get("type").asString,
        )
    }

    @Test
    fun revokeFrameCarriesInviteId() {
        // Relay-Verband je LANE-R: PRIMARY-Zielkennung des Revoke = invite_id
        // (der Code selbst ist in Listen NICHT öffentlich, PII-Regel).
        val frame = AgentInviteRules.revokeFrame(" abc123xyz ")
        assertEquals("agent.invite.revoke", frame?.get("type")?.asString)
        assertEquals(1, frame?.get("v")?.asInt)
        assertEquals("abc123xyz", frame?.get("invite_id")?.asString)
        assertNull(frame?.get("invite_code")) // kein Code am Revoke-Draht
        assertNull(AgentInviteRules.revokeFrame(null))
        assertNull(AgentInviteRules.revokeFrame("   "))
    }

    // ── parseCreate(): Vertragsfamilie + fail-soft ────────────────────────────

    @Test
    fun parsesCreateOkWithResult() {
        val ack = AgentInviteRules.parseCreate(
            json(
                """{"type":"agent.invite.create_ok","v":1,"ok":true,
                    "invite_id":"a1b2c3d4e5f6",
                    "invite_code":"aB3xY9cD2eF4hJ5kQQQQWWWW",
                    "ttl_min":60,"uses":1,"ts":1700000000000}""",
            ),
        )!!
        assertTrue(ack.ok)
        assertEquals("aB3xY9cD2eF4hJ5kQQQQWWWW", ack.result?.inviteCode)
        assertEquals("a1b2c3d4e5f6", ack.result?.inviteId)
        assertEquals(60L, ack.result?.ttlMin)
        assertEquals(1L, ack.result?.uses)
        assertNull(ack.error)
    }

    @Test
    fun errorFamiliesStayHonest() {
        // „denied“ je NON-Admin-Gerät / „too-many“ je offenen-Cap — KEIN Code.
        val denied = AgentInviteRules.parseCreate(
            json("""{"type":"agent.invite.create_ok","v":1,"ok":false,"error":"denied"}"""),
        )!!
        assertFalse(denied.ok)
        assertEquals("denied", denied.error)
        assertNull(denied.result)
        val tooMany = AgentInviteRules.parseCreate(
            json("""{"type":"agent.invite.create_ok","v":1,"ok":false,"error":"too-many"}"""),
        )!!
        assertEquals("too-many", tooMany.error)
        assertNull(tooMany.result)
    }

    @Test
    fun toleratesCreatedWritingVariant() {
        // Schreib-Spiegel (defensive Familie): gleicher Inhalt, andere Form.
        val ack = AgentInviteRules.parseCreate(
            json("""{"type":"agent.invite.created","invite_code":"c1","ok":true}"""),
        )!!
        assertEquals("c1", ack.result?.inviteCode)
    }

    @Test
    fun createWithoutCodeStaysHonest() {
        // ok-Feld ohne Code-Feld: ehrlich kein Fake-Code-Angebot.
        val ack = AgentInviteRules.parseCreate(
            json("""{"type":"agent.invite.create_ok","ok":true,"invite_id":"i1"}"""),
        )!!
        assertTrue(ack.ok)
        assertNull(ack.result)
    }

    @Test
    fun foreignFramesAreDroppedCreate() {
        assertNull(AgentInviteRules.parseCreate(json("""{"ok":true}""")))
        assertNull(AgentInviteRules.parseCreate(
            json("""{"type":"agent.invite.list_ok","invites":[]}"""),
        ))
    }

    // ── parseList(): NUR öffentliche Felder je Zeile (PII-Regel) ──────────────

    @Test
    fun parsesListPublicRows() {
        val list = AgentInviteRules.parseList(
            json(
                """{"type":"agent.invite.list_ok","ok":true,"invites":[
                      {"invite_id":"i1","hint":"ab12cd••••••••","ttl_min":60,
                       "uses_left":1,"created_at":1700000000000,"expires_at":1700003600000,
                       "label":"Einsatz-Agent"},
                      {"invite_id":"i2","hint":"xy90ef••••••••"}]}""",
            ),
        )!!
        assertEquals(2, list.size)
        assertEquals("i1", list[0].inviteId)
        assertEquals("ab12cd••••••••", list[0].hint)
        assertEquals(60L, list[0].ttlMin)
        assertEquals(1L, list[0].usesLeft)
        assertEquals(1700000000000L, list[0].createdAtMs)
        assertEquals(1700003600000L, list[0].expiresAtMs)
        assertEquals("Einsatz-Agent", list[0].label)
        // i2 ohne Nebenfelder = ehrliche null/leer (kein Fake).
        assertNull(list[1].ttlMin)
        assertNull(list[1].usesLeft)
        assertEquals("", list[1].label)
    }

    @Test
    fun listNeverCarriesAPlainCode() {
        // PII-Regel-Fibel am Test bewiesen: eine Zeile MIT `invite_code` liest
        // der Parser als EHRLEICHEN Eintrag OHNE Code (kein Feld-Drift-Wunsch —
        // die UI zeigt NUR hint; ein Volle-Code in Listen wäre ein Verstoß).
        val list = AgentInviteRules.parseList(
            json(
                """{"type":"agent.invite.list_ok","invites":[
                      {"invite_id":"i1","hint":"ab••••••••","invite_code":"SECRET"}]}""",
            ),
        )!!
        assertEquals("ab••••••••", list[0].hint)
        assertEquals(1, list.size) // der Zeile-Verband ignoriert Extra-Slots
    }

    @Test
    fun toleratesListedAndEchoWritingVariants() {
        assertTrue(
            AgentInviteRules.parseList(
                json("""{"type":"agent.invite.listed","invites":[]}"""),
            ) != null,
        )
        assertTrue(
            AgentInviteRules.parseList(
                json("""{"type":"agent.invite.list","invites":[]}"""),
            ) != null,
        )
    }

    @Test
    fun emptyInviteArrayIsHonestEmpty() {
        assertEquals(listOf<AgentInviteRules.InviteEntry>(), AgentInviteRules.parseList(
            json("""{"type":"agent.invite.list_ok","invites":[]}"""),
        ))
        // invites-Feld fehlt (Alter Relay): KEIN Crash — ehrliche Leer-Liste.
        assertEquals(listOf<AgentInviteRules.InviteEntry>(), AgentInviteRules.parseList(
            json("""{"type":"agent.invite.list_ok"}"""),
        ))
    }

    @Test
    fun listDropsEntriesWithoutHandle() {
        val list = AgentInviteRules.parseList(
            json(
                """{"type":"agent.invite.list_ok","invites":[
                      {"hint":"ab••••••••"},
                      {"invite_id":""},
                      "junk",
                      {"invite_id":"c9","hint":"x1••••••••"}]}""",
            ),
        )!!
        assertEquals(listOf("c9"), list.map { it.inviteId })
    }

    @Test
    fun foreignFramesAreDroppedList() {
        assertNull(AgentInviteRules.parseList(json("""{"invites":[]}""")))
        assertNull(AgentInviteRules.parseList(
            json("""{"type":"agent.invite.create_ok","invite_id":"x"}"""),
        ))
    }

    // ── parseRevoke(): ok-Feld-Ehrlichkeit ────────────────────────────────────

    @Test
    fun parsesRevokeOk() {
        val ack = AgentInviteRules.parseRevoke(
            json("""{"type":"agent.invite.revoke_ok","ok":true,"invite_id":"i1"}"""),
        )!!
        assertTrue(ack.ok)
        assertEquals("i1", ack.inviteId)
        val failing = AgentInviteRules.parseRevoke(
            json("""{"type":"agent.invite.revoke_ok","ok":false,"error":"not-found"}"""),
        )!!
        assertFalse(failing.ok)
        assertEquals("not-found", failing.error)
        // ok-Feld fehlt = nie ein Fake-Erfolg.
        val silent = AgentInviteRules.parseRevoke(
            json("""{"type":"agent.invite.revoked","invite_id":"i1"}"""),
        )!!
        assertFalse(silent.ok)
    }

    @Test
    fun foreignFramesAreDroppedRevoke() {
        assertNull(AgentInviteRules.parseRevoke(json("""{"ok":true}""")))
        assertNull(AgentInviteRules.parseRevoke(
            json("""{"type":"agent.invite.created","invite_code":"x"}"""),
        ))
    }

    // ── isAdminDevice(): NIE-WRONG Admin-Welt ─────────────────────────────────

    @Test
    fun adminGateRequiresUniqueOwnResolution() {
        val devices = listOf(
            AgentInviteRules.AdminDeviceRef("<masked:36>", admin = false),
            AgentInviteRules.AdminDeviceRef("<masked:36b>", admin = true),
        )
        assertTrue(AgentInviteRules.isAdminDevice(devices, "<masked:36b>"))
        // Eigene Maske trifft die NICHT-Admin-Kachel ⇒ false (nicht die Karte
        // eines anderen als die eigene lesen).
        assertFalse(AgentInviteRules.isAdminDevice(devices, "<masked:36>"))
        // MEHRDEUTIG/ohne Beweis (null) ⇒ false — die Karte bleibt verborgen
        // (never-wrong vor always-right, KEIN Fake-Admin-Angebot).
        assertFalse(AgentInviteRules.isAdminDevice(devices, null))
        assertFalse(AgentInviteRules.isAdminDevice(devices, "  "))
        assertFalse(AgentInviteRules.isAdminDevice(emptyList(), "<masked:36>"))
    }

    // ── W26d (F3): Code-Ende je TTL/Revoke + Anlegung-Uhr ─────────────────────

    @Test
    fun createAckCarriesItsClock() {
        val ack = AgentInviteRules.parseCreate(
            json("""{"type":"agent.invite.create_ok","v":1,"ok":true,
                    "invite_id":"a1b2c3d4e5f6","invite_code":"aB3xY9cD2eF4hJ5kQQQQWWWW",
                    "ttl_min":60,"uses":1,"ts":1700000000000}"""),
        )!!
        assertEquals(1700000000000L, ack.result?.createdAtMs)
        // fehlt die Uhr (Alt-Relay) = ehrlich 0 (kein erfundeter Anlegung-Zeitpunkt).
        val bare = AgentInviteRules.parseCreate(
            json("""{"type":"agent.invite.create_ok","ok":true,"invite_code":"c1","ttl_min":60}"""),
        )!!
        assertEquals(0L, bare.result?.createdAtMs)
    }

    @Test
    fun inviteLapsedFollowsTheRealClock() {
        val code = AgentInviteRules.CreateResult(
            inviteCode = "c1", inviteId = "i1",
            ttlMin = 5L, createdAtMs = 1000L,
        )
        assertFalse(AgentInviteRules.inviteLapsed(code, nowMs = 4_000L))
        assertTrue(AgentInviteRules.inviteLapsed(code, nowMs = 301_000L))
        // Ohne Anlegung-Uhr (createdAtMs 0) NIE lapsed — keine erfundene Frist.
        assertFalse(
            AgentInviteRules.inviteLapsed(
                AgentInviteRules.CreateResult(inviteCode = "c2", ttlMin = 5L, createdAtMs = 0L),
                nowMs = 99_999_999_999L,
            ),
        )
        // Ohne TTL (null) NIE lapsed.
        assertFalse(
            AgentInviteRules.inviteLapsed(
                AgentInviteRules.CreateResult(inviteCode = "c3", ttlMin = null, createdAtMs = 1L),
                nowMs = 99_999_999_999L,
            ),
        )
        // Kein Code-Verband = nie abgelaufen.
        assertFalse(AgentInviteRules.inviteLapsed(null, nowMs = 1L))
    }

    @Test
    fun revokeMatchesOnlyTheShownCode() {
        val code = AgentInviteRules.CreateResult(
            inviteCode = "c1", inviteId = "i1", ttlMin = 60L, createdAtMs = 1L,
        )
        assertTrue(AgentInviteRules.revokeMatchesCode(code, "i1"))
        assertTrue(AgentInviteRules.revokeMatchesCode(code, "  i1  "))
        // Abweichendes Handle (eine ANDERE Einladung widerrufen) räumt den
        // LEBENDEN Code NICHT (never-wrong, kein falsch-positives Räumen).
        assertFalse(AgentInviteRules.revokeMatchesCode(code, "i2"))
        // Blank-Verbande (Alt-Relay ohne Handles) widersprechen nie.
        assertFalse(AgentInviteRules.revokeMatchesCode(code, null))
        assertFalse(
            AgentInviteRules.revokeMatchesCode(
                AgentInviteRules.CreateResult(inviteCode = "c2"),
                "i1",
            ),
        )
        assertFalse(AgentInviteRules.revokeMatchesCode(null, "i1"))
    }

    // ── W26d (F4): Restzeit statt Anlage-TTL ─────────────────────────────────

    @Test
    fun timeWordShowsRemainingBreadth() {
        // Frisch angelegt (60 Min Rest): das Rest-Wort, nicht urgent.
        val fresh = AgentInviteRules.invitationTimeWord(
            expiresAtMs = 100_000_000L + 60 * 60_000L,
            ttlMin = 60L, nowMs = 100_000_000L,
            resRemaining = 11, resRemainingSoon = 12, resExpired = 13, resTtl = 14,
        )!!
        assertEquals(11, fresh.res)
        assertEquals(60, fresh.minutes)
        assertFalse(fresh.urgent)
        // 55 Minuten alt (5 Min Rest): noch das normale Wort …
        val atEdge = AgentInviteRules.invitationTimeWord(
            expiresAtMs = 100_000_000L + 5 * 60_000L,
            ttlMin = 60L, nowMs = 100_000_000L,
            resRemaining = 11, resRemainingSoon = 12, resExpired = 13, resTtl = 14,
        )!!
        assertEquals(11, atEdge.res)
        // … UNTER 5 Min Rest: das ehrlich kritische Wort.
        val urgent = AgentInviteRules.invitationTimeWord(
            expiresAtMs = 100_000_000L + 4 * 60_000L,
            ttlMin = 60L, nowMs = 100_000_000L,
            resRemaining = 11, resRemainingSoon = 12, resExpired = 13, resTtl = 14,
        )!!
        assertEquals(12, urgent.res)
        assertTrue(urgent.urgent)
        // Abgelaufener Snapshot (der Relay prunt je list — das UI-Fenster
        // zeigt es ehrlich): das expired-Wort.
        val expired = AgentInviteRules.invitationTimeWord(
            expiresAtMs = 100_000_000L,
            ttlMin = 60L, nowMs = 100_000_001L,
            resRemaining = 11, resRemainingSoon = 12, resExpired = 13, resTtl = 14,
        )!!
        assertEquals(13, expired.res)
        assertTrue(expired.urgent)
    }

    @Test
    fun timeWordFallsBackToTtlHonest() {
        // Alter Relay ohne expiry: das Anlage-TTL-Wort bleibt (Bestand).
        val ttl = AgentInviteRules.invitationTimeWord(
            expiresAtMs = null, ttlMin = 60L, nowMs = 1_000L,
            resRemaining = 11, resRemainingSoon = 12, resExpired = 13, resTtl = 14,
        )!!
        assertEquals(14, ttl.res)
        assertEquals(60, ttl.minutes)
        assertFalse(ttl.urgent)
        // Beide fehlen: kein Zeit-Wort (kein Platzhalter-Geraffel).
        assertNull(
            AgentInviteRules.invitationTimeWord(
                expiresAtMs = null, ttlMin = null, nowMs = 0L,
                resRemaining = 11, resRemainingSoon = 12, resExpired = 13, resTtl = 14,
            ),
        )
        assertNull(
            AgentInviteRules.invitationTimeWord(
                expiresAtMs = 0L, ttlMin = 0L, nowMs = 0L,
                resRemaining = 11, resRemainingSoon = 12, resExpired = 13, resTtl = 14,
            ),
        )
    }
}
