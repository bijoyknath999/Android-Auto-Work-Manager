package com.workmanager.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlin.concurrent.thread

/**
 * Runs the Google sign-in tap/type automation as a FOREGROUND service. This is
 * required because the sequence happens while our UI is in the background (Play
 * Store is foreground), and aggressive ROMs (MIUI/HyperOS) freeze background
 * processes — which would stall the `input tap`/`input text` commands mid-way.
 * A foreground service keeps the process running so all the ADB taps fire.
 */
class AutomationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AdbNotificationManager.createChannel(this)
        ServiceCompat.startForeground(
            this, FG_ID,
            AdbNotificationManager.foregroundNotification(this, "Running Google sign-in…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )

        val userId = intent?.getIntExtra(EXTRA_USER, -1) ?: -1
        val email = intent?.getStringExtra(EXTRA_EMAIL).orEmpty()

        Session.init(applicationContext)
        thread {
            try {
                if (userId > 0 && Session.connected) {
                    Session.profiles.addGoogleAccount(userId, email)
                } else {
                    AppLogger.log("AUTO", "not connected or no profile — skipping automation")
                }
            } catch (e: Exception) {
                AppLogger.log("AUTO", "automation error: ${e.message}")
            } finally {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val FG_ID = 1003
        const val EXTRA_USER = "user"
        const val EXTRA_EMAIL = "email"
    }
}
