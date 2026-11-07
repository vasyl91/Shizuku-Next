package moe.shizuku.manager.start

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a forced start lets Wi-Fi go first, and when a hotspot is given up for it.
 *
 * Both exist for a radio that cannot be a client and a hotspot at once (the FYT 7870 head unit),
 * and both must stay out of the way of a phone whose radio can: that is the phone the forced start
 * was written on, and the one where a hotspot is the whole answer.
 */
class ClientWifiTest {

    @Test
    fun `wifi goes first only when it is on and the hotspot would take it down`() {
        assertTrue(shouldWaitForClientWifi(wifiOn = true, hotspotDisplacesClient = true))
    }

    @Test
    fun `a radio that does both is left to the hotspot`() {
        assertFalse(shouldWaitForClientWifi(wifiOn = true, hotspotDisplacesClient = false))
    }

    @Test
    fun `wifi that is off has nothing to wait for`() {
        assertFalse(shouldWaitForClientWifi(wifiOn = false, hotspotDisplacesClient = true))
    }

    @Test
    fun `a platform that cannot say behaves as it always did`() {
        assertFalse(shouldWaitForClientWifi(wifiOn = null, hotspotDisplacesClient = true))
        assertFalse(shouldWaitForClientWifi(wifiOn = true, hotspotDisplacesClient = null))
        assertFalse(shouldWaitForClientWifi(wifiOn = null, hotspotDisplacesClient = null))
    }

    @Test
    fun `a hotspot that took wifi down is given up once its phase is over`() {
        assertTrue(shouldGiveUpHotspotForWifi(true, false, hotspotAgeMs = 30_000, phaseMs = 30_000))
        assertTrue(shouldGiveUpHotspotForWifi(true, false, hotspotAgeMs = 45_000, phaseMs = 30_000))
    }

    @Test
    fun `not before its phase is over`() {
        assertFalse(shouldGiveUpHotspotForWifi(true, false, hotspotAgeMs = 29_999, phaseMs = 30_000))
    }

    @Test
    fun `a hotspot that left wifi on is never cut short`() {
        assertFalse(shouldGiveUpHotspotForWifi(true, true, hotspotAgeMs = 120_000, phaseMs = 30_000))
    }

    @Test
    fun `wifi that was off before the hotspot is not the hotspot's doing`() {
        assertFalse(shouldGiveUpHotspotForWifi(false, false, hotspotAgeMs = 120_000, phaseMs = 30_000))
    }

    @Test
    fun `an unreadable wifi state does not end a hotspot`() {
        assertFalse(shouldGiveUpHotspotForWifi(true, null, hotspotAgeMs = 120_000, phaseMs = 30_000))
    }
}
