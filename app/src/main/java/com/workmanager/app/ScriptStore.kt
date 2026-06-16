package com.workmanager.app

import android.content.Context

/**
 * Manages multiple named scripts stored in SharedPreferences.
 * Each script is stored under key "script_<name>".
 * A separate key "script_names" holds the comma-separated list of names.
 * "script_active" holds the currently selected script name.
 */
object ScriptStore {

    private const val PREFS = "wm_scripts"
    private const val KEY_NAMES = "script_names"
    private const val KEY_ACTIVE = "script_active"
    private const val DEFAULT_NAME = "default"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** All saved script names. */
    fun list(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_NAMES, DEFAULT_NAME) ?: DEFAULT_NAME
        return raw.split(",").filter { it.isNotEmpty() }
    }

    /** Currently active script name. */
    fun activeName(ctx: Context): String =
        prefs(ctx).getString(KEY_ACTIVE, DEFAULT_NAME) ?: DEFAULT_NAME

    /** Set the active script name. */
    fun setActive(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, name).apply()
    }

    /** Load script content by name. */
    fun load(ctx: Context, name: String): String =
        prefs(ctx).getString("script_$name", "") ?: ""

    /** Save script content by name. Adds name to the list if new. */
    fun save(ctx: Context, name: String, content: String) {
        val p = prefs(ctx)
        val names = list(ctx).toMutableList()
        if (name !in names) {
            names.add(name)
            p.edit().putString(KEY_NAMES, names.joinToString(",")).apply()
        }
        p.edit().putString("script_$name", content).apply()
    }

    /** Delete a script by name. Returns remaining names. */
    fun delete(ctx: Context, name: String): List<String> {
        val p = prefs(ctx)
        p.edit().remove("script_$name").apply()
        val names = list(ctx).toMutableList()
        names.remove(name)
        if (names.isEmpty()) names.add(DEFAULT_NAME)
        p.edit().putString(KEY_NAMES, names.joinToString(",")).apply()
        if (activeName(ctx) == name) setActive(ctx, names.first())
        return names
    }

    /** Rename a script. */
    fun rename(ctx: Context, oldName: String, newName: String) {
        val content = load(ctx, oldName)
        delete(ctx, oldName)
        save(ctx, newName, content)
        setActive(ctx, newName)
    }

    /** Append a line to the active script. */
    fun appendLine(ctx: Context, line: String) {
        val name = activeName(ctx)
        val current = load(ctx, name)
        val newContent = if (current.isEmpty()) line else "$current\n$line"
        save(ctx, name, newContent)
    }
}
