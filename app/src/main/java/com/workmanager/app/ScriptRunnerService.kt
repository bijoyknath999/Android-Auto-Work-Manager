package com.workmanager.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.ServiceCompat
import kotlin.concurrent.thread

/**
 * Runs a simple automation script as a foreground service (so MIUI/HyperOS won't
 * freeze the process while the target app is foreground).
 *
 * Commands (one per line, '#' or '//' for comments):
 *   open <package>              launch app in selected work profile
 *   close <package>              force-stop an app
 *   link <url> <package>         open URL in a specific app
 *   scroll up|down [times] [px]  scroll screen (default: 1 time, 500px)
 *   tap <x> <y>                  gesture tap (works where ADB input is blocked)
 *   swipe <x1> <y1> <x2> <y2> [ms]
 *   text <words...>              set text into the focused field
 *   wait <ms>
 *   detect <words...>            wait (≤15s) until screen shows this text
 *   click <text>                 tap a button/element by its visible label
 *   unfocus                      dismiss keyboard and unfocus input field
 *   dump                         log current UI hierarchy text
 *   if <text>                    execute next block only if text is on screen
 *   else                         flip the if condition
 *   endif                        end of if block
 *   key back|home|enter
 */
class ScriptRunnerService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var floatStopView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopRequested = false
        isRunning = true

        AdbNotificationManager.createChannel(this)
        ServiceCompat.startForeground(
            this, FG_ID,
            AdbNotificationManager.foregroundNotification(this, "Running script…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        val script = intent?.getStringExtra(EXTRA_SCRIPT).orEmpty()
        val userId = intent?.getIntExtra(EXTRA_USER, -1) ?: -1

        showStopFloat()

        thread {
            try { run(script, userId) }
            catch (e: Exception) { AppLogger.log("SCRIPT", "stopped: ${e.message}") }
            finally {
                isRunning = false
                mainHandler.post { removeStopFloat() }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun showStopFloat() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(WindowManager::class.java)
        val btn = Button(this).apply {
            text = "■ Stop Script"
            setBackgroundColor(Color.parseColor("#ef5350"))
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(32, 12, 32, 12)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }
        btn.setOnClickListener { requestStop() }
        try {
            wm.addView(btn, params)
            floatStopView = btn
        } catch (e: Exception) {
            AppLogger.log("SCRIPT", "float stop overlay failed: ${e.message}")
        }
    }

    private fun removeStopFloat() {
        floatStopView?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        floatStopView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        mainHandler.post { removeStopFloat() }
    }

    private fun acc() = PairAccessibilityService.instance

    private fun run(script: String, userId: Int) {
        Session.init(applicationContext)
        AppLogger.log("SCRIPT", "── run (profile $userId) ──")

        val lines = script.lines()
        var i = 0
        // Stack of if-states: true = executing, false = skipping
        val ifStack = mutableListOf<Boolean>()
        // Depth of nested if/else/endif we're skipping
        var skipDepth = 0

        while (i < lines.size) {
            if (stopRequested) { AppLogger.log("SCRIPT", "stopped by user"); break }
            val raw = lines[i]; i++
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//")) continue
            val line = trimmed.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val t = line.split(Regex("\\s+"))
            val cmd = t[0].lowercase()

            // ── if / else / endif control flow ────────────────────────────────
            when (cmd) {
                "if" -> {
                    val text = line.substringAfter("if").trim()
                    if (skipDepth > 0) {
                        // Nested inside a skipped block — just track depth
                        skipDepth++
                        ifStack.add(false)
                        continue
                    }
                    val screenText = acc()?.currentScreenText() ?: ""
                    val found = screenText.contains(text, ignoreCase = true)
                    AppLogger.log("SCRIPT", "if \"$text\" -> $found")
                    ifStack.add(found)
                    if (!found) skipDepth = 1
                    continue
                }
                "else" -> {
                    if (ifStack.isEmpty()) { AppLogger.log("SCRIPT", "else without if"); continue }
                    if (skipDepth == 1 && !ifStack.last()) {
                        // We were skipping the if-block, now execute else-block
                        skipDepth = 0
                    } else if (skipDepth == 0 && ifStack.last()) {
                        // We executed the if-block, now skip else-block
                        skipDepth = 1
                    }
                    // else if skipDepth > 1: still inside a skipped nested block
                    continue
                }
                "endif" -> {
                    if (ifStack.isEmpty()) { AppLogger.log("SCRIPT", "endif without if"); continue }
                    if (skipDepth > 0) skipDepth--
                    ifStack.removeAt(ifStack.lastIndex)
                    continue
                }
            }

            // If we're inside a skipped if-block, don't execute
            if (skipDepth > 0) continue

            // ── regular commands ──────────────────────────────────────────────
            try {
                when (cmd) {
                    "open" -> {
                        val pkg = t[1]
                        val u = t.getOrNull(2)?.toIntOrNull() ?: userId
                        val r = if (u > 0) Session.profiles.launchApp(pkg, u)
                        else Session.adb.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                        AppLogger.log("SCRIPT", "open $pkg user=$u -> ${r.take(60)}")
                    }
                    "close" -> {
                        val pkg = t[1]
                        val u = userId
                        val userFlag = if (u > 0) " --user $u" else ""
                        val r = Session.adb.exec("am force-stop$userFlag $pkg")
                        AppLogger.log("SCRIPT", "close $pkg user=$u -> ${r.take(60)}")
                    }
                    "link" -> {
                        // link <url> <package>
                        val rest = line.substringAfter("link").trim()
                        val parts = rest.split(Regex("\\s+"))
                        val url = parts.getOrElse(0) { "" }
                        val pkg = parts.getOrElse(1) { "" }
                        val u = parts.getOrNull(2)?.toIntOrNull() ?: userId
                        if (url.isEmpty()) {
                            AppLogger.log("SCRIPT", "link: no URL provided")
                        } else {
                            // Quote URL for shell safety
                            val safeUrl = url.replace("'", "")
                            val cmd = buildString {
                                append("am start")
                                if (u > 0) append(" --user $u")
                                append(" -a android.intent.action.VIEW")
                                append(" -d '$safeUrl'")
                                if (pkg.isNotEmpty()) append(" -p $pkg")
                            }
                            val r = Session.adb.exec(cmd)
                            AppLogger.log("SCRIPT", "link $url pkg=$pkg user=$u -> ${r.take(80)}")
                        }
                    }
                    "scroll" -> {
                        val dir = t.getOrNull(1) ?: "down"
                        val times = t.getOrNull(2)?.toIntOrNull() ?: 1
                        val px = t.getOrNull(3)?.toIntOrNull() ?: 500
                        val cx = 540; val cy = 1200
                        val (y1, y2) = if (dir == "up") cy to (cy - px) else cy to (cy + px)
                        repeat(times) {
                            Session.adb.exec("input swipe $cx $y1 $cx $y2 300")
                            Thread.sleep(400)
                        }
                        AppLogger.log("SCRIPT", "scroll $dir x$times px=$px")
                    }
                    "tap" -> {
                        val ok = acc()?.tapBlocking(t[1].toInt(), t[2].toInt()) ?: false
                        AppLogger.log("SCRIPT", "tap ${t[1]},${t[2]} ok=$ok")
                    }
                    "swipe" -> {
                        val dur = t.getOrNull(5)?.toLongOrNull() ?: 300L
                        val ok = acc()?.swipeBlocking(t[1].toInt(), t[2].toInt(), t[3].toInt(), t[4].toInt(), dur) ?: false
                        AppLogger.log("SCRIPT", "swipe ok=$ok")
                    }
                    "text" -> {
                        val s = line.substringAfter("text").trim()
                        val r = Session.adb.exec("input text '${s.replace("'", "\\'")}'")
                        AppLogger.log("SCRIPT", "text \"$s\" -> ${r.take(60)}")
                    }
                    "wait" -> {
                        val ms = t[1].toLong()
                        AppLogger.log("SCRIPT", "wait $ms")
                        Thread.sleep(ms)
                    }
                    "detect" -> {
                        val needle = line.substringAfter("detect").trim()
                        val found = waitForText(needle, 15_000)
                        AppLogger.log("SCRIPT", "detect \"$needle\" found=$found")
                    }
                    "key" -> {
                        val a = acc()
                        when (t.getOrNull(1)?.lowercase()) {
                            "back"  -> a?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                            "home"  -> a?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                            "enter" -> Session.adb.exec("input keyevent 66")
                            else    -> AppLogger.log("SCRIPT", "key ${t.getOrNull(1)} unsupported")
                        }
                        AppLogger.log("SCRIPT", "key ${t.getOrNull(1)}")
                    }
                    "click" -> {
                        val label = line.substringAfter("click").trim()
                        val ok = acc()?.clickByLabel(label) ?: false
                        AppLogger.log("SCRIPT", "click \"$label\" ok=$ok")
                    }
                    "unfocus" -> {
                        // Dismiss keyboard + unfocus input
                        Session.adb.exec("input keyevent 111")  // ESC
                        acc()?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        AppLogger.log("SCRIPT", "unfocus")
                    }
                    "dump" -> {
                        val text = acc()?.dumpElements() ?: "(no accessibility)"
                        AppLogger.log("DUMP", "── UI dump ──\n$text\n── end dump ──")
                    }
                    else -> AppLogger.log("SCRIPT", "unknown command: $cmd")
                }
            } catch (e: Exception) {
                AppLogger.log("SCRIPT", "error on '$line': ${e.message}")
            }
        }
        AppLogger.log("SCRIPT", "── done ──")
    }

    private fun waitForText(needle: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (stopRequested) return false
            if (acc()?.currentScreenText()?.contains(needle, ignoreCase = true) == true) return true
            Thread.sleep(400)
        }
        return false
    }

    companion object {
        private const val FG_ID = 1004
        const val EXTRA_SCRIPT = "script"
        const val EXTRA_USER = "user"

        @Volatile var isRunning = false
            private set
        @Volatile private var stopRequested = false

        fun requestStop() { stopRequested = true }
    }
}
