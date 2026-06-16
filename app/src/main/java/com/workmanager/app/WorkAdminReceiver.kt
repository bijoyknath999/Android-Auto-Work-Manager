package com.workmanager.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Device-admin / profile-owner component. Once our app is made profile owner of
 * a managed profile (via `dpm set-profile-owner`), this enables the profile so
 * the launcher shows its apps with work badges — turning a bare managed profile
 * into a real work profile.
 */
class WorkAdminReceiver : DeviceAdminReceiver() {

    /** System apps to surface in the work profile so the launcher shows them
     *  (Play Store + the Google stack it depends on). */
    private val systemAppsToEnable = listOf(
        "com.android.vending",          // Play Store
        "com.google.android.gms",       // Play Services
        "com.google.android.gsf"        // Services Framework
    )

    private fun enableProfile(context: Context) {
        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(context, WorkAdminReceiver::class.java)
            if (!dpm.isProfileOwnerApp(context.packageName)) return

            dpm.setProfileName(admin, "Work")

            // Make sure work apps (and ourselves) aren't cross-profile blocked.
            try { dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_APPS) } catch (_: Exception) {}

            // Surface Play Store + GMS in the profile so they appear in the launcher.
            systemAppsToEnable.forEach { pkg ->
                try { dpm.enableSystemApp(admin, pkg) } catch (_: Exception) {}
            }

            dpm.setProfileEnabled(admin)
            AppLogger.log("ADMIN", "Work profile enabled; Play Store enabled")
        } catch (e: Exception) {
            AppLogger.log("ADMIN", "enableProfile error: ${e.message}")
        }
    }

    override fun onEnabled(context: Context, intent: Intent) = enableProfile(context)

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) = enableProfile(context)
}
