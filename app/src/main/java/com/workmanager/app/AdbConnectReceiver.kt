package com.workmanager.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the "Disconnect" notification control — drops the live ADB connection.
 * (Pairing is done in the app, since a notification inline reply can only show
 * one text field, not both the code and the port.)
 */
class AdbConnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AdbNotificationManager.ACTION_DISCONNECT) {
            AdbHolder.connection?.disconnect()
            AppLogger.log("APP", "Disconnected from notification")
            AdbNotificationManager.showDisconnected(context)
        }
    }
}
