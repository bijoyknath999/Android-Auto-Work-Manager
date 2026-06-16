package com.workmanager.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object AdbNotificationManager {

    private const val CHANNEL_ID = "adb_connect"
    private const val NOTIF_ID = 1001

    /** Separate id so the boot foreground-service notification doesn't clobber
     *  the connect/disconnect status notification. */
    const val FOREGROUND_ID = 1002

    const val ACTION_DISCONNECT = "com.workmanager.app.ACTION_DISCONNECT"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ADB Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "ADB wireless debugging connection control"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /** Persistent "not connected" notification. A notification's inline reply
     *  only supports ONE text field, so we can't ask for code + port there —
     *  the Pair action opens the app, which has both fields. */
    fun showDisconnected(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val pairAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_add, "Pair", openAppIntent(context)
        ).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Work Manager — disconnected")
            .setContentText("Tap Pair to enter the code + pairing port")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Developer Options → Wireless Debugging → Pair device with pairing code.\n" +
                         "Tap \"Pair\" to open the app, then enter the 6-digit code and pairing port. " +
                         "The app finds the main port and connects automatically."))
            .setContentIntent(openAppIntent(context))
            .addAction(pairAction)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        nm.notify(NOTIF_ID, notification)
    }

    /** Persistent "connected" notification with a one-tap Disconnect control. */
    fun showConnected(context: Context, message: String) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val disconnectIntent = PendingIntent.getBroadcast(
            context, 3,
            Intent(ACTION_DISCONNECT).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectIntent
        ).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Work Manager — connected ✓")
            .setContentText(message)
            .setContentIntent(openAppIntent(context))
            .addAction(disconnectAction)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIF_ID, notification)
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** A plain ongoing notification suitable for startForeground(). */
    fun foregroundNotification(context: Context, text: String): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("ADB Work Manager")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun showConnecting(context: Context, message: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("ADB Work Manager")
            .setContentText(message)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIF_ID, notification)
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}