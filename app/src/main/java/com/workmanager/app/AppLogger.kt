package com.workmanager.app

import android.util.Log

object AppLogger {
    private const val MAX_LINES = 120
    private val lines = ArrayDeque<String>()
    private val listeners = mutableListOf<(String) -> Unit>()

    fun log(tag: String, msg: String) {
        Log.d(tag, msg)
        val line = "[$tag] $msg"
        synchronized(lines) {
            lines.addLast(line)
            if (lines.size > MAX_LINES) lines.removeFirst()
        }
        val full = synchronized(lines) { lines.joinToString("\n") }
        listeners.forEach { it(full) }
    }

    fun addListener(l: (String) -> Unit) = listeners.add(l)
    fun removeListener(l: (String) -> Unit) = listeners.remove(l)

    fun clear() {
        synchronized(lines) { lines.clear() }
        listeners.forEach { it("") }
    }

    fun all() = synchronized(lines) { lines.joinToString("\n") }
}
