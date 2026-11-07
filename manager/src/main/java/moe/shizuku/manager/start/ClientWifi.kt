package moe.shizuku.manager.start

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.manage.WifiControl
import moe.shizuku.manager.utils.Diag

/**
 * The device's own Wi-Fi connection, as far as the forced wireless start is concerned.
 *
 * A local-only hotspot is a second use of the one radio. Where the radio can do both at once
 * nothing here matters, but a head unit such as the FYT 7870 (Android 13, one `wlan0`) tears the
 * Wi-Fi client down to bring the hotspot up: the phone it was about to join disappears, the car
 * loses its internet for as long as the hotspot is held, and the framework still refuses wireless
 * debugging ("Not connected to any wireless network") because a hotspot is not a network the
 * device is connected to. The one thing that makes wireless debugging work there is the Wi-Fi the
 * hotspot just removed.
 *
 * So on a device where the hotspot displaces the client, the start gives the client the first go,
 * keeps the hotspot to short phases that alternate with Wi-Fi getting its chance back, and puts
 * Wi-Fi back if the platform does not. Nothing here changes a device whose radio does both.
 */
object ClientWifi {

    private const val POLL_MS = 500L

    /** How long the platform is given to bring the client back by itself once the hotspot is closed. */
    private const val RESTORE_GRACE_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun wifi(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /** Whether the Wi-Fi client is switched on, or null when the platform would not say. */
    fun isEnabled(context: Context): Boolean? =
        runCatching { wifi(context)?.isWifiEnabled }.getOrNull()

    /**
     * Whether the client is joined to a network. The connectivity service is asked rather than the
     * Wi-Fi one: it needs no location permission, and a hotspot is not a network in its list.
     */
    @Suppress("DEPRECATION")
    fun isConnected(context: Context): Boolean = runCatching {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }.getOrDefault(false)

    /**
     * Whether bringing a hotspot up takes the Wi-Fi client down, or null when that cannot be
     * known. The platform answers from Android 12 on; before it the answer is "unknown", which
     * leaves the start as it always was.
     */
    fun hotspotDisplacesClient(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching { wifi(context)?.isStaApConcurrencySupported?.not() }.getOrNull()
    }

    /** Waits up to [timeoutMs] for the client to join a network; true as soon as it has. */
    suspend fun awaitConnected(context: Context, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (isConnected(context)) return true
            if (System.currentTimeMillis() >= end) return false
            delay(POLL_MS)
        }
    }

    /**
     * Makes sure the Wi-Fi client is on again after a hotspot, in the background: the attempt
     * that raised the hotspot is over and has nothing left to wait on.
     *
     * The platform normally restarts the client when the hotspot goes ("SoftAp disabled, start
     * client mode"), so that is waited for first. If it does not, the API call is tried (it only
     * works for an app the platform still allows to toggle Wi-Fi), then the shell, which is
     * there once the server is up or the phone is rooted.
     */
    fun restoreAfterHotspot(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val end = System.currentTimeMillis() + RESTORE_GRACE_MS
            while (System.currentTimeMillis() < end) {
                if (isEnabled(appContext) != false) {
                    Diag.info(AppConstants.TAG, "Wi-Fi is on after the hotspot")
                    return@launch
                }
                delay(POLL_MS)
            }

            Diag.warn(AppConstants.TAG, "Wi-Fi did not come back after the hotspot closed; switching it on")
            @Suppress("DEPRECATION")
            val viaApi = runCatching { wifi(appContext)?.setWifiEnabled(true) == true }
                .getOrDefault(false)
            if (viaApi && isEnabled(appContext) != false) {
                Diag.info(AppConstants.TAG, "Wi-Fi switched back on through the Wi-Fi service")
                return@launch
            }
            val viaShell = WifiControl.enable()
            Diag.info(
                AppConstants.TAG,
                when (viaShell) {
                    true -> "Wi-Fi switched back on through the shell"
                    false -> "the shell could not switch Wi-Fi back on"
                    null -> "Wi-Fi is off and there is no shell yet to switch it on with"
                }
            )
        }
    }
}

/**
 * Whether a forced start should wait for the Wi-Fi client before it raises a hotspot: only when
 * Wi-Fi is on and the hotspot would take it down. An unknown answer is "no", so a device that
 * cannot say is treated as it always was.
 */
internal fun shouldWaitForClientWifi(wifiOn: Boolean?, hotspotDisplacesClient: Boolean?): Boolean =
    wifiOn == true && hotspotDisplacesClient == true

/**
 * Whether a hotspot that has been up for [hotspotAgeMs] without a port should be given up so that
 * Wi-Fi can have another go: Wi-Fi was on before the hotspot, is off now, and the phase is over.
 * A hotspot that left Wi-Fi on is never cut short.
 */
internal fun shouldGiveUpHotspotForWifi(
    wifiWasOn: Boolean,
    wifiOnNow: Boolean?,
    hotspotAgeMs: Long,
    phaseMs: Long
): Boolean = wifiWasOn && wifiOnNow == false && hotspotAgeMs >= phaseMs
