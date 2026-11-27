package com.hermesandroid.bridge.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4-B (Swipe-to-Reply) — Geste-Verband als JVM-Vertrag:
 *
 * - NUR die NACH-RECHTS-Wisch löst aus ([ChatSwipeRules.triggered]): exakt ab
 *   der 32-dp-Schwelle (Grenzfall ≥), darunter nicht, Links-Fahrt nie.
 * - Übersetzung klemmt auf [0, MAX_TRAVEL_DP] ([ChatSwipeRules.clampTravel]):
 *   negative Fahrt zieht auf 0 (keine Rückübersetzung), Deckel nach oben.
 * - Reduced-Pass-Faser ([ChatMotionRules]): Animator-Scale 0 = Reduced →
 *   Settle 0 ms (sofort), sonst der 120-ms-Tween; die Skalen-Wahrheit liest
 *   [ChatMotionRules.reduced].
 */
class ChatSwipeRulesTest {

    private fun px(dp: Float, density: Float = 1f): Float = dp * density

    // ── triggered: Schwelle + Richtung ──────────────────────────────────────

    @Test
    fun swipeBelowThresholdDoesNotFire() {
        val trigger = px(ChatSwipeRules.TRIGGER_DP)
        assertFalse("kein Start-Puls", ChatSwipeRules.triggered(0f, trigger))
        assertFalse("knapp unter der Schwelle", ChatSwipeRules.triggered(px(31.9f), trigger))
        assertFalse("halbe Schwelle", ChatSwipeRules.triggered(px(16f), trigger))
    }

    @Test
    fun swipeExactlyAtThresholdFires() {
        val trigger = px(ChatSwipeRules.TRIGGER_DP)
        assertTrue("Grenzfall ≥ = ausgelöst", ChatSwipeRules.triggered(trigger, trigger))
        assertTrue("dahinter erst recht", ChatSwipeRules.triggered(trigger + 1f, trigger))
    }

    @Test
    fun leftwardSwipeNeverFires() {
        val trigger = px(ChatSwipeRules.TRIGGER_DP)
        assertFalse("ganze Links-Fahrt", ChatSwipeRules.triggered(-px(10f), trigger))
        assertFalse("kleiner Links-Puls", ChatSwipeRules.triggered(-0.5f, trigger))
        assertFalse("deckschwelliger Links-Betrag", ChatSwipeRules.triggered(-px(200f), trigger))
    }

    @Test
    fun thresholdHonoursDensityScaledPixel() {
        // Density-Wandler liegt in der UI; die Regel bekommt px — Grenzfall
        // densitätunabhängig: 32 dp bei density 2 → 64 px.
        assertEquals(true, ChatSwipeRules.triggered(64f, px(ChatSwipeRules.TRIGGER_DP, 2f)))
        assertEquals(false, ChatSwipeRules.triggered(63.9f, px(ChatSwipeRules.TRIGGER_DP, 2f)))
    }

    // ── clampTravel: Klemmung der Finger-Übersetzung ────────────────────────

    @Test
    fun travelClampsToZeroAndMax() {
        val max = px(ChatSwipeRules.MAX_TRAVEL_DP)
        assertEquals(0f, ChatSwipeRules.clampTravel(0f, max), 0.0001f)
        assertEquals(max, ChatSwipeRules.clampTravel(max, max), 0.0001f)
        assertEquals(
            px(ChatSwipeRules.MAX_TRAVEL_DP) * 1f,
            ChatSwipeRules.clampTravel(px(500f), max),
            0.0001f,
        )
    }

    @Test
    fun leftwardPullClampsToZeroNeverNegative() {
        val max = px(ChatSwipeRules.MAX_TRAVEL_DP)
        assertEquals(0f, ChatSwipeRules.clampTravel(-px(5f), max), 0.0001f)
        assertEquals(0f, ChatSwipeRules.clampTravel(-px(500f), max), 0.0001f)
    }

    @Test
    fun travelMatchesDensityIndependently() {
        // Die Regel kennt keine Density — Klemmung verhält sich identisch.
        for (density in listOf(1f, 1.5f, 2f, 3f)) {
            val max = px(ChatSwipeRules.MAX_TRAVEL_DP, density)
            assertEquals(max, ChatSwipeRules.clampTravel(max + 1000f, max), 0.0001f)
        }
    }

    // ── Reduced-Pass-Faser (ChatMotionRules) ────────────────────────────────

    @Test
    fun reducedMotionReadsAnimScaleZeroAsInstant() {
        assertTrue("Scala 0 = Reduced", ChatMotionRules.reduced(0f))
        assertTrue("Negative Scala = Reduced", ChatMotionRules.reduced(-0.5f))
        assertFalse("Halbe Scala = normale Anim", ChatMotionRules.reduced(0.5f))
        assertFalse("Voll-Scala = normale Anim", ChatMotionRules.reduced(1f))
    }

    @Test
    fun settleDurationCollapsesToZeroWithReducedMotion() {
        assertEquals(0, ChatMotionRules.settleTweenMs(reducedMotion = true))
        assertEquals(
            ChatSwipeRules.SETTLE_MS,
            ChatMotionRules.settleTweenMs(reducedMotion = false),
        )
        assertTrue("Settle-Tween klein genug fürs Handgefühl", ChatSwipeRules.SETTLE_MS in 60..250)
    }
}
