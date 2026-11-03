package com.hermesandroid.bridge.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PH-FIX-BACKEND-B (Bug-1-Härtung): hello_ack-Verify-Fehler sind NIE mehr
 * still — max. HelloAckFailurePolicy.MAX_RETRIES Neu-Handshakes, danach
 * sichtbarer Fehler (Banner + Status). policy shouldRetry = Retry-Kappe.
 */
class HelloAckFailurePolicyTest {

    @Test
    fun erste_und_zweite_fehlschlaege_wiederholen_noch() {
        assertTrue("1. Fehlschlag → Retry 1", HelloAckFailurePolicy.shouldRetry(1))
        assertTrue("2. Fehlschlag → Retry 2 (Kappe)", HelloAckFailurePolicy.shouldRetry(2))
    }

    @Test
    fun ab_drittem_fehlschlag_banner_statt_retry() {
        assertFalse(
            "3. Fehlschlag = Budget aufgebraucht → Banner/Status statt Endlos-Retry",
            HelloAckFailurePolicy.shouldRetry(3),
        )
        assertFalse("negative/ungültige Zähler = kein Retry", HelloAckFailurePolicy.shouldRetry(0))
        assertFalse("weit jenseits des Budgets = kein Retry", HelloAckFailurePolicy.shouldRetry(99))
    }

    @Test
    fun banner_und_status_sind_user_sichtbar_geschnitten() {
        assertTrue(HelloAckFailurePolicy.BANNER.contains("E2E-Handshake fehlgeschlagen"))
        assertTrue(HelloAckFailurePolicy.STATUS.contains("neu verbinden"))
    }
}