package com.workmanager.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * On every boot, kicks off [AdbAutoEnableService] which re-enables wireless
 * debugging and reconnects — but only once the app has been bootstrapped
 * (paired + granted WRITE_SECURE_SETTINGS). Before that there is nothing useful
 * to do without the user, so we skip.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        if (!AdbAutoConnect.canAutoConnect(context)) {
            AppLogger.log("BOOT", "Skipping auto-enable — not bootstrapped yet")
            return
        }

        AppLogger.log("BOOT", "Boot completed — starting auto-enable service")
        context.startForegroundService(Intent(context, AdbAutoEnableService::class.java))
    }
}
