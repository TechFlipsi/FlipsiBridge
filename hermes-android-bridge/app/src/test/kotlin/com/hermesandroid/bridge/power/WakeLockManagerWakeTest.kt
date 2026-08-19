package com.hermesandroid.bridge.power

import android.content.Context
import android.os.PowerManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression tests for WakeLockManager.wake() (PR #101).
 *
 * wake() must be a no-op when the screen is already interactive and must
 * acquire a (auto-releasing, 10s-timeout) wake lock when it is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakeLockManagerWakeTest {

    private lateinit var powerManager: PowerManager

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        WakeLockManager.init(context)
    }

    @After
    fun tearDown() {
        WakeLockManager.forceRelease()
    }

    @Test
    fun `wake is a no-op when screen already interactive`() {
        shadowOf(powerManager).setIsInteractive(true)

        assertTrue(WakeLockManager.wake())
        // No wake lock should have been created for an already-awake device.
        assertTrue(shadowOf(powerManager).wakeLocks.isEmpty())
    }

    @Test
    fun `wake acquires a wake lock when screen off`() {
        shadowOf(powerManager).setIsInteractive(false)

        assertTrue(WakeLockManager.wake())
        assertTrue(shadowOf(powerManager).wakeLocks.isNotEmpty())
    }

    @Test
    fun `forceRelease clears a held wake lock`() {
        shadowOf(powerManager).setIsInteractive(false)

        WakeLockManager.wake()
        WakeLockManager.forceRelease()

        val held = shadowOf(powerManager).wakeLocks.values.any { it.isHeld }
        assertFalse(held)
    }
}
