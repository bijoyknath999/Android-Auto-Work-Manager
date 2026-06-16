package com.workmanager.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Bundle

/**
 * Invisible activity launched *inside* the new profile (via `am start --user`)
 * right after we become its profile owner. Running in that user's context it can
 * call setProfileEnabled() — the step that makes the work profile's apps appear
 * in the launcher. A no-op if we aren't the profile owner.
 */
class ProfileBootstrapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(this, WorkAdminReceiver::class.java)
            if (dpm.isProfileOwnerApp(packageName)) {
                dpm.setProfileName(admin, "Work")
                dpm.setProfileEnabled(admin)
                AppLogger.log("ADMIN", "Profile enabled via bootstrap")
            }
        } catch (e: Exception) {
            AppLogger.log("ADMIN", "bootstrap error: ${e.message}")
        }
        finish()
    }
}
