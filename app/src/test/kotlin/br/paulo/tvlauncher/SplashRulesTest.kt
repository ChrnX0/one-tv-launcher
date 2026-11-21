package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.SplashRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashRulesTest {
    @Test fun bootShowsOnlyRightAfterTheDeviceStarts() {
        assertTrue(SplashRules.onBoot(true, 45_000L))
        assertTrue(SplashRules.onBoot(true, SplashRules.BOOT_WINDOW_MS))
        // Launcher restarted hours after boot (update, REINICIAR): not "turning the TV on".
        assertFalse(SplashRules.onBoot(true, SplashRules.BOOT_WINDOW_MS + 1))
        assertFalse(SplashRules.onBoot(false, 45_000L))
    }

    @Test fun wakeNeedsARealSleepAndTheHomeInFront() {
        assertTrue(SplashRules.onWake(true, 8 * 3600_000L, homeInFront = true))
        assertTrue(SplashRules.onWake(true, SplashRules.MIN_SLEEP_MS, homeInFront = true))
        assertFalse(SplashRules.onWake(true, 30_000L, homeInFront = true))      // screen blinked
        assertFalse(SplashRules.onWake(true, 0L, homeInFront = true))           // first SCREEN_ON without a SCREEN_OFF
        assertFalse(SplashRules.onWake(true, 8 * 3600_000L, homeInFront = false))
        assertFalse(SplashRules.onWake(false, 8 * 3600_000L, homeInFront = true))
    }

    @Test fun lastsOneToTwoSeconds() {
        val total = SplashRules.HOLD_MS + SplashRules.FADE_MS
        assertTrue(total in 1000L..2000L)
    }
}
