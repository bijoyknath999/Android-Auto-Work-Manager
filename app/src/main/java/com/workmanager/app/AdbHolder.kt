package com.workmanager.app

/**
 * Process-wide handle to the live ADB connection so notification actions
 * (handled in a BroadcastReceiver, outside the Activity) can control it —
 * e.g. the "Disconnect" button.
 */
object AdbHolder {
    @Volatile
    var connection: AdbConnection? = null
}
