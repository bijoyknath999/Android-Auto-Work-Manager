package com.workmanager.app

import android.content.Context

/**
 * Shared app state across the bottom-nav pages: the single ADB connection, the
 * work-profile manager, and the currently selected profile.
 */
object Session {

    lateinit var adb: AdbConnection
        private set
    lateinit var profiles: WorkProfileManager
        private set

    @Volatile
    var selectedUserId: Int = -1

    fun init(context: Context) {
        if (!::adb.isInitialized) {
            adb = AdbConnection(context.applicationContext)
            AdbHolder.connection = adb
            profiles = WorkProfileManager(adb)
        }
    }

    val connected: Boolean get() = ::adb.isInitialized && adb.connected
}
