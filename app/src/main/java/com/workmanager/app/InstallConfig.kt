package com.workmanager.app

import android.content.Context

object InstallConfig {

    private const val PREFS = "install_config"
    private const val KEY = "enabled_packages"

    val defaultPackages: Set<String> = setOf(
        "com.google.android.gsf",
        "com.google.android.gms",
        "com.android.vending",
        "com.android.chrome"
    )

    fun enabledPackages(ctx: Context): Set<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY, null) ?: defaultPackages
    }

    fun save(ctx: Context, pkgs: Set<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, pkgs).apply()
    }
}
