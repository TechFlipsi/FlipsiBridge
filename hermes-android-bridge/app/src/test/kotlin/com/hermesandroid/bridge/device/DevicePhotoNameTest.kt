package com.hermesandroid.bridge.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JÄGER-J2 (JAEGE-RFULL-20261102): /photo filename-Pfad-Traversal —
 * `File(dir, "../../Download/x.jpg")` löste auf und schrieb ÜBERALL unter
 * /storage/emulated/0; die Route ist nur hinter der harmlosen `device`-
 * Capability gegated, nicht hinter files_write.
 *
 * Vertrag: safePhotoFileName lässt NUR reine Dateinamen durch
 * ([A-Za-z0-9._-], ≤128) — kein Pfad-Separator, kein "..", keine
 * Verweise wie "."; blank/leer → null (Aufrufer vergibt den Default).
 */
class DevicePhotoNameTest {

    // ── gut: normale Namen ──────────────────────────────────────────────

    @Test
    fun plainNamesPass() {
        assertEquals("agent_foto.jpg", DeviceHardware.safePhotoFileName("agent_foto.jpg"))
        assertEquals("Bild-2026.png", DeviceHardware.safePhotoFileName("Bild-2026.png"))
        assertEquals("a", DeviceHardware.safePhotoFileName("a"))
        assertEquals("x.tar.gz", DeviceHardware.safePhotoFileName("x.tar.gz"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("foto.jpg", DeviceHardware.safePhotoFileName("  foto.jpg "))
    }

    // ── der Fund: Traversal-Vektoren ────────────────────────────────────

    @Test
    fun traversalVectorsAreRejected() {
        assertNull(DeviceHardware.safePhotoFileName("../../Download/x.jpg"))
        assertNull(DeviceHardware.safePhotoFileName("..\\..\\Windows\\x.jpg"))
        assertNull(DeviceHardware.safePhotoFileName("/data/data/com.app/x.jpg"))
        assertNull(DeviceHardware.safePhotoFileName("sub/dir/x.jpg"))
        assertNull(DeviceHardware.safePhotoFileName("x.jpg\u0000.jpg"))
        assertNull(DeviceHardware.safePhotoFileName("../x.jpg"))
        assertNull(DeviceHardware.safePhotoFileName(".."))
        assertNull(DeviceHardware.safePhotoFileName("."))
        assertNull(DeviceHardware.safePhotoFileName(""))
        assertNull(DeviceHardware.safePhotoFileName("   "))
    }

    @Test
    fun nonFilesystemCharactersAreRejected() {
        assertNull(DeviceHardware.safePhotoFileName("foto:.jpg"))
        assertNull(DeviceHardware.safePhotoFileName("foto\\.jpg"))
        assertNull(DeviceHardware.safePhotoFileName("foo bar.jpg"))
        assertNull(DeviceHardware.safePhotoFileName("foto?.jpg"))
    }

    @Test
    fun lengthIsCapped() {
        assertNull(DeviceHardware.safePhotoFileName("a".repeat(129)))
        assertEquals(128, DeviceHardware.safePhotoFileName("a".repeat(128))?.length)
    }
}