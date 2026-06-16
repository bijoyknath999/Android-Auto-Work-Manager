package com.workmanager.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Reads the Android "Pair device with pairing code" dialog and pairs
 * automatically. The dialog shows a 6-digit code and the pairing IP:port, but
 * dismisses if you switch apps to type them — so instead we scrape that screen
 * via accessibility and pair without the user typing or leaving Settings.
 *
 * Only acts while [armed] (set when the user taps "Auto Pair"), so it never
 * pairs unexpectedly.
 */
class PairAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var armed = false
        @Volatile private var pairing = false
        @Volatile private var lastKey = ""

        // A standalone 6-digit pairing code, and an IPv4:port endpoint.
        private val CODE = Regex("""(?<!\d)\d{6}(?!\d)""")
        private val IP_PORT = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})""")

        /** Call when (re)starting an Auto Pair attempt. */
        fun arm() { armed = true; lastKey = "" }

        /** Live instance, so the floating button can ask it to perform gestures. */
        @Volatile var instance: PairAccessibilityService? = null
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** A real touch tap at (x,y) via the accessibility gesture API — works even
     *  where ADB `input tap` is blocked by the ROM. */
    fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        main.post {
            dispatchGesture(gesture, null, null)
            AppLogger.log("GESTURE", "tap ($x,$y)")
        }
    }

    /** Sets [text] on the currently focused input field (e.g. the email box). */
    fun typeIntoFocused(text: String) {
        main.post {
            val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (node != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                AppLogger.log("GESTURE", "set email text ok=$ok")
            } else {
                AppLogger.log("GESTURE", "no focused field to type into")
            }
        }
    }

    // ── Blocking primitives used by the script runner ─────────────────────────

    /** Taps (x,y) and waits for the gesture to complete. */
    fun tapBlocking(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchBlocking(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        )
    }

    /** Swipes from (x1,y1) to (x2,y2) over [durationMs] and waits. */
    fun swipeBlocking(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return dispatchBlocking(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50, 5000))).build()
        )
    }

    private fun dispatchBlocking(g: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(d: GestureDescription?) { ok = false; latch.countDown() }
            }, null)
        }
        latch.await(6, TimeUnit.SECONDS)
        return ok
    }

    /** Sets [text] on the focused field and waits briefly. */
    fun setFocusedTextBlocking(text: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ok = node?.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            ) ?: false
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    /** All visible text on the current screen (for `detect`). */
    fun currentScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        return StringBuilder().also { collectText(root, it) }.toString()
    }

    /** Dump all elements with their text, bounds, and class. */
    fun dumpElements(): String {
        val root = rootInActiveWindow ?: return "(no accessibility)"
        val sb = StringBuilder()
        dumpNode(root, sb, 0)
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        node ?: return
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val clickable = if (node.isClickable) " [clickable]" else ""
        val indent = "  ".repeat(depth)

        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
            sb.appendLine("$indent$cls \"$text\" desc=\"$desc\" bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}] center=(${rect.centerX()},${rect.centerY()})$clickable")
        }

        for (i in 0 until node.childCount) {
            dumpNode(node.getChild(i), sb, depth + 1)
        }
    }

    /** Find all clickable nodes and return their text/contentDescription. */
    fun findClickableLabels(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val labels = mutableListOf<String>()
        collectClickable(root, labels)
        return labels.distinct()
    }

    private fun collectClickable(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        node ?: return
        if (node.isClickable) {
            val label = node.text?.toString()?.take(60)
                ?: node.contentDescription?.toString()?.take(60)
            if (!label.isNullOrBlank()) out.add(label)
        }
        for (i in 0 until node.childCount) {
            collectClickable(node.getChild(i), out)
        }
    }

    /** Click a node whose text or contentDescription matches [label]. */
    fun clickByLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByLabel(root, label) ?: return false
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!ok) {
            // Fallback: tap its center coordinates
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            tap(rect.centerX(), rect.centerY())
        }
        return true
    }

    private fun findNodeByLabel(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        node ?: return null
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.contains(label, ignoreCase = true) || desc.contains(label, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findNodeByLabel(node.getChild(i), label)
            if (found != null) return found
        }
        return null
    }

    /** Runs the Google sign-in tap/type sequence using gestures. */
    fun runGoogleSignIn(email: String) {
        thread {
            try {
                // Taps hit whatever app is FOREGROUND (not a user id). Log it so
                // you can confirm it's the profile's Play Store, not the main user.
                val fg = rootInActiveWindow?.packageName ?: "unknown"
                AppLogger.log("GESTURE", "foreground app = $fg (taps land here)")
                tap(534, 1614); Thread.sleep(5000)   // Sign in
                tap(173, 503);  Thread.sleep(1200)   // email field
                if (email.isNotBlank()) { typeIntoFocused(email); Thread.sleep(800) }
                tap(832, 1919)                        // Next
            } catch (e: Exception) {
                AppLogger.log("GESTURE", "sign-in seq error: ${e.message}")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!armed || pairing) return
        val root = rootInActiveWindow ?: return

        val text = StringBuilder().also { collectText(root, it) }.toString()
        val code = CODE.find(text)?.value ?: return
        val endpoint = IP_PORT.find(text) ?: return
        val ip = endpoint.groupValues[1]
        val port = endpoint.groupValues[2].toIntOrNull() ?: return

        val key = "$ip:$port:$code"
        if (key == lastKey) return
        lastKey = key
        pairing = true
        armed = false

        AppLogger.log("AUTOPAIR", "Detected pairing dialog → $ip:$port (code $code)")
        thread {
            try {
                val ctx = applicationContext
                val adb = AdbHolder.connection ?: AdbConnection(ctx).also { AdbHolder.connection = it }
                if (adb.pair(ip, port, code)) {
                    AppLogger.log("AUTOPAIR", "Paired ✓ — connecting")
                    if (AdbAutoConnect.autoConnect(ctx, adb)) {
                        AdbNotificationManager.showConnected(ctx, "Connected (auto-paired)")
                    } else {
                        AppLogger.log("AUTOPAIR", "Paired, but couldn't find the main port")
                    }
                } else {
                    AppLogger.log("AUTOPAIR", "Pairing failed — code may have expired; tap Auto Pair again")
                }
            } finally {
                pairing = false
            }
        }
    }

    override fun onInterrupt() {}

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) collectText(node.getChild(i), sb)
    }
}
