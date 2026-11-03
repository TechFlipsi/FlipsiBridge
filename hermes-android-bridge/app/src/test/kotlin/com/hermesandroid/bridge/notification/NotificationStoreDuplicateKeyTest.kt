package com.hermesandroid.bridge.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JÄGER-J8 (P2, JAEGE-RFULL-20261102): NotificationStore-Duplikat-Keys.
 * Aktualisierte Notifications (gleicher sbn.key, z. B. jede neue WhatsApp-
 * Nachricht) erzeugten mehrere Einträge mit gleichem Key — markRemoved
 * flaggte nur den ERSTEN Treffer, ältere Duplikate blieben für immer
 * „aktiv“ und wurden als aktuelle Shade ausgeliefert (Geister-Notifications,
 * mehrdeutiges notify_reply).
 *
 * Vertrag (Fix): add() ersetzt existierende Einträge mit gleichem Key
 * (aktualisierte Notification = EIN Eintrag), markRemoved markiert ALLE
 * Treffer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationStoreDuplicateKeyTest {

    private fun entry(key: String, timestamp: Long, text: String = "text") = NotificationEntry(
        key = key,
        packageName = "com.example.app",
        title = "title-$key",
        text = text,
        subText = null,
        bigText = null,
        summaryText = null,
        category = null,
        timestamp = timestamp,
        isOngoing = false,
        isClearable = true,
    )

    @Before
    fun cleanStore() {
        NotificationStore.clear()
    }

    @Test
    fun `updated notification replaces the older entry with the same key`() {
        NotificationStore.add(entry("whatsapp|1", 1000L, text = "alte Version"))
        NotificationStore.add(entry("whatsapp|1", 2000L, text = "aktualisierte Version"))

        val active = NotificationStore.getActive(50)
        assertEquals("Ein Key = ein Eintrag", 1, active.size)
        assertEquals("aktualisierte Version", active.single().text)
    }

    @Test
    fun `different keys never collapse`() {
        NotificationStore.add(entry("whatsapp|1", 1000L))
        NotificationStore.add(entry("whatsapp|2", 1100L))
        NotificationStore.add(entry("telegram|1", 1200L))
        assertEquals(3, NotificationStore.getActive(50).size)
    }

    @Test
    fun `markRemoved flags every remaining entry of a key`() {
        // Robustheit auch gegen ältere Bestände (Pre-Fix-Zustand): alle
        // Treffer des Keys müssen die removed-Flagge tragen.
        NotificationStore.add(entry("k", 1000L))
        NotificationStore.add(entry("other", 1100L))

        NotificationStore.markRemoved("k")
        val all = NotificationStore.getAll(50, includeRemoved = true)
        all.filter { it.key == "k" }.forEach {
            assertNotNull("jeder k-Eintrag muss removedAt tragen", it.removedAt)
        }
        assertEquals("other bleibt aktiv", listOf("other"), NotificationStore.getActive(50).map { it.key })
    }
}