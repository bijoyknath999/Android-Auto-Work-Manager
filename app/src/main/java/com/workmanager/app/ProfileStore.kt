package com.workmanager.app

import android.content.Context

/**
 * Remembers which user/profile IDs *this app* created. We only ever list and
 * operate on these — never user 0 or other system/managed users — so the app
 * can't accidentally remove something the system depends on.
 */
object ProfileStore {
    private const val PREFS = "wm_profiles"
    private const val KEY = "created_ids"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ids(c: Context): Set<Int> =
        prefs(c).getStringSet(KEY, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()

    fun add(c: Context, id: Int) {
        val updated = ids(c).map { it.toString() }.toMutableSet().apply { add(id.toString()) }
        prefs(c).edit().putStringSet(KEY, updated).apply()
    }

    fun remove(c: Context, id: Int) {
        val updated = ids(c).map { it.toString() }.toMutableSet().apply { remove(id.toString()) }
        prefs(c).edit().putStringSet(KEY, updated).apply()
    }

    fun owns(c: Context, id: Int): Boolean = id in ids(c)
}
