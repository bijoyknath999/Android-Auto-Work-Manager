package com.workmanager.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Foreground service that performs the autonomous bring-up on boot:
 * wait for WiFi → enable wireless debugging → discover port → connect → ensure
 * WRITE_SECURE_SETTINGS is granted for next time. One-shot: stops when done.
 */
class AdbAutoEnableService : Service() {

    private val TAG = "AUTOSVC"
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AdbNotificationManager.createChannel(this)
        ServiceCompat.startForeground(
            this,
            AdbNotificationManager.FOREGROUND_ID,
            AdbNotificationManager.foregroundNotification(this, "Auto-enabling ADB…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )

        if (running) return START_NOT_STICKY
        running = true

        Thread {
            try {
                if (!waitForWifi()) {
                    AppLogger.log(TAG, "WiFi never came up — giving up")
                    finish("Auto-enable failed — no WiFi")
                    return@Thread
                }

                val adb = AdbConnection(this)
                val ok = AdbAutoConnect.autoConnect(this, adb)

                if (ok) {
                    AdbAutoConnect.selfGrant(adb)
                    AdbNotificationManager.showConnected(this, "Auto-connected after boot")
                    AppLogger.log(TAG, "Auto-enable succeeded")
                } else {
                    AdbNotificationManager.showDisconnected(this)
                    AppLogger.log(TAG, "Auto-enable failed")
                }
                adb.disconnect()
            } catch (e: Exception) {
                AppLogger.log(TAG, "Error: ${e.message}")
            } finally {
                finish(null)
            }
        }.start()

        return START_NOT_STICKY
    }

    /** Polls for a usable WiFi IPv4 address for up to ~60s. */
    private fun waitForWifi(): Boolean {
        repeat(60) {
            if (AdbAutoConnect.wifiIp(this) != "127.0.0.1") return true
            Thread.sleep(1_000)
        }
        return AdbAutoConnect.wifiIp(this) != "127.0.0.1"
    }

    private fun finish(failNote: String?) {
        running = false
        if (failNote != null) AppLogger.log(TAG, failNote)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }
}
