package com.workmanager.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Autonomous ADB bring-up, modelled on github.com/mouldybread/adb-auto-enable.
 *
 * After a ONE-TIME manual pairing (the existing pair/connect flow in
 * [MainActivity]) the app self-grants [PERMISSION] via `pm grant`. From then on
 * it no longer needs the user to toggle anything or type a port:
 *
 *   1. Turn wireless debugging on itself — Settings.Global "adb_wifi_enabled"=1
 *      (only possible once we hold WRITE_SECURE_SETTINGS).
 *   2. Find adbd's randomised port — mDNS `_adb-tls-connect._tcp`, falling back
 *      to the last port that worked.
 *   3. Connect with the already-paired key — the device remembers the key, so
 *      there is no "Allow wireless debugging" prompt.
 *
 * All methods here block; call them off the main thread.
 */
object AdbAutoConnect {
    private const val TAG = "AUTO"

    const val PACKAGE = "com.workmanager.app"
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    private const val SETTING_WIFI_DEBUG = "adb_wifi_enabled"
    private const val MDNS_SERVICE = "_adb-tls-connect._tcp"

    private const val PREFS = "adb_auto"
    private const val KEY_LAST_PORT = "last_port"
    private const val KEY_USB_PORT = "usb_port"

    // adbd takes a moment to bind its TLS port after the setting flips.
    private const val ADBD_BIND_WAIT_MS = 4_000L
    private const val MDNS_TIMEOUT_SEC = 8L

    private fun log(m: String) = AppLogger.log(TAG, m)

    fun hasSecureSettingsPermission(c: Context): Boolean =
        c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun isWifiDebugEnabled(c: Context): Boolean =
        Settings.Global.getInt(c.contentResolver, SETTING_WIFI_DEBUG, 0) == 1

    /** True if we can bring ADB up without any user interaction. */
    fun canAutoConnect(c: Context): Boolean =
        hasSecureSettingsPermission(c) || isWifiDebugEnabled(c)

    /** Turns wireless debugging on programmatically. Needs WRITE_SECURE_SETTINGS. */
    fun enableWifiDebug(c: Context): Boolean = try {
        Settings.Global.putInt(c.contentResolver, SETTING_WIFI_DEBUG, 1)
        log("adb_wifi_enabled = 1")
        true
    } catch (e: SecurityException) {
        log("Cannot enable wireless debugging — WRITE_SECURE_SETTINGS not granted yet")
        false
    } catch (e: Exception) {
        log("enableWifiDebug error: ${e.message}")
        false
    }

    /** Grants ourselves WRITE_SECURE_SETTINGS over an existing ADB connection so
     *  every later boot can auto-enable. Returns true only if it actually stuck.
     *  Safe to call repeatedly. */
    fun selfGrant(adb: AdbConnection): Boolean {
        if (!adb.connected) return false
        log("Self-granting $PERMISSION via pm grant …")
        val out = adb.exec("pm grant $PACKAGE $PERMISSION")
        return when {
            // pm grant prints nothing on success.
            out.isBlank() -> { log("WRITE_SECURE_SETTINGS granted"); true }
            out.contains("SecurityException") || out.contains("Exception occurred") -> {
                log("Could not self-grant WRITE_SECURE_SETTINGS: this ROM blocks `pm grant` " +
                    "from ADB shell. On MIUI/HyperOS enable Developer options → " +
                    "'USB debugging (Security settings)' (or 'Install via USB'), then reconnect. " +
                    "Only auto-reconnect-after-reboot needs this; all other features work without it.")
                false
            }
            else -> { log("pm grant: ${out.lineSequence().firstOrNull()}"); false }
        }
    }

    fun wifiIp(c: Context): String {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return "127.0.0.1"
        return cm.getLinkProperties(net)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address?.hostAddress ?: "127.0.0.1"
    }

    /**
     * Full autonomous bring-up: enable wireless debugging, discover the port and
     * connect [adb]. Returns true once CONNECTED. Blocking.
     */
    fun autoConnect(c: Context, adb: AdbConnection): Boolean {
        if (adb.connected) return true

        if (!isWifiDebugEnabled(c)) {
            if (!enableWifiDebug(c)) return false
            Thread.sleep(ADBD_BIND_WAIT_MS)
        }

        val host = wifiIp(c)
        if (host == "127.0.0.1") {
            log("No WiFi IPv4 address yet — can't auto-connect")
            return false
        }

        var port = discoverPortViaMdns(c, host)
        if (port == -1) {
            val last = lastPort(c)
            if (last != -1) { log("mDNS failed — trying last known port $last"); port = last }
        }
        if (port == -1) {
            log("Could not find adbd port (mDNS unavailable, no saved port)")
            return false
        }

        log("Auto-connecting to $host:$port …")
        val ok = adb.connect(host, port)
        if (ok) { saveLastPort(c, port); return true }

        // First port failed — try all remaining mDNS ports.
        for (p in allMdnsPorts) {
            if (p == port) continue
            log("Trying $host:$p …")
            if (adb.connect(host, p)) { saveLastPort(c, p); return true }
        }

        // Try last known port as final fallback.
        val last2 = lastPort(c)
        if (last2 != -1 && last2 != port && last2 !in allMdnsPorts) {
            log("Trying last known port $host:$last2 …")
            if (adb.connect(host, last2)) { saveLastPort(c, last2); return true }
        }

        return false
    }

    // ── mDNS discovery (_adb-tls-connect._tcp) ─────────────────────────────────

    // Keep a reference to stop stale discovery sessions.
    @Volatile private var activeListener: NsdManager.DiscoveryListener? = null
    // All ports discovered by mDNS in the latest scan.
    private val allMdnsPorts = mutableListOf<Int>()

    private fun discoverPortViaMdns(c: Context, deviceIp: String): Int {
        val nsd = c.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return -1
        val result = intArrayOf(-1)
        val latch = CountDownLatch(1)
        allMdnsPorts.clear()

        // Stop any previous discovery that might still be running.
        activeListener?.let {
            try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {}
            activeListener = null
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(s: String) = log("mDNS discovery started")
            override fun onServiceFound(info: NsdServiceInfo) {
                log("mDNS service found: ${info.serviceName}")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                        log("mDNS resolve failed: errorCode=$e")
                    }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val host = i.host?.hostAddress ?: return
                        log("mDNS resolved: $host:${i.port}")
                        synchronized(allMdnsPorts) { allMdnsPorts.add(i.port) }
                        if (host == deviceIp || host.startsWith("127.")) {
                            result[0] = i.port
                            latch.countDown() // Found it — unblock early.
                        }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                log("mDNS service lost: ${info.serviceName}")
            }
            override fun onDiscoveryStopped(s: String) { log("mDNS discovery stopped") }
            override fun onStartDiscoveryFailed(s: String, e: Int) {
                log("mDNS discovery FAILED to start: errorCode=$e (FAILURE_ALREADY_ACTIVE=3)")
                latch.countDown()
            }
            override fun onStopDiscoveryFailed(s: String, e: Int) {
                log("mDNS stop failed: errorCode=$e")
            }
        }

        activeListener = listener

        return try {
            nsd.discoverServices(MDNS_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(MDNS_TIMEOUT_SEC, TimeUnit.SECONDS)
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
            activeListener = null
            result[0]
        } catch (e: Exception) {
            log("mDNS error: ${e.message}")
            activeListener = null
            -1
        }
    }

    // ── Last-known-port persistence ────────────────────────────────────────────

    private fun lastPort(c: Context): Int =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LAST_PORT, -1)

    private fun saveLastPort(c: Context, port: Int) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LAST_PORT, port).apply()
    }

    // ── USB / localhost ADB ────────────────────────────────────────────────────

    fun saveUsbPort(c: Context, port: Int) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_USB_PORT, port).apply()
        log("USB port saved: $port")
    }

    private fun lastUsbPort(c: Context): Int =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_USB_PORT, -1)

    fun canAutoConnectUsb(c: Context): Boolean = lastUsbPort(c) != -1

    /**
     * Tries to connect to 127.0.0.1:<saved USB port>. Succeeds when the device
     * has adbd in TCP mode (i.e. `adb tcpip <port>` was run via USB cable).
     */
    fun autoConnectUsb(c: Context, adb: AdbConnection): Boolean {
        if (adb.connected) return true
        val port = lastUsbPort(c)
        if (port == -1) return false
        log("USB auto-connect: 127.0.0.1:$port …")
        return adb.connect("127.0.0.1", port)
    }
}
