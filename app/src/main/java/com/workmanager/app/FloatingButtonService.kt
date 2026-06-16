package com.workmanager.app

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button

/**
 * A draggable floating button that sits over other apps. Tapping it asks the
 * AccessibilityService to perform the Google sign-in tap/type sequence via
 * gestures — which work even on ROMs that block ADB `input` injection.
 *
 * Needs "Display over other apps" (overlay) + the accessibility service enabled.
 */
class FloatingButtonService : Service() {

    private lateinit var wm: WindowManager
    private var view: View? = null
    private var email: String = ""
    private var userId: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        email = intent?.getStringExtra(EXTRA_EMAIL).orEmpty()
        userId = intent?.getIntExtra(EXTRA_USER, -1) ?: -1
        if (view == null) addButton()
        return START_NOT_STICKY
    }

    private fun addButton() {
        wm = getSystemService(WindowManager::class.java)
        val btn = Button(this).apply {
            text = "▶ Run sign-in"
            setBackgroundColor(Color.parseColor("#03dac5"))
            setTextColor(Color.BLACK)
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
            gravity = Gravity.TOP or Gravity.START
            x = 24; y = 300
        }

        // Drag to move; a quick tap (no drag) triggers the sequence.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) dragged = true
                    params.x = startX + dx; params.y = startY + dy
                    try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) onTapButton(); true }
                else -> false
            }
        }

        try {
            wm.addView(btn, params)
            view = btn
        } catch (e: Exception) {
            AppLogger.log("FLOAT", "addView failed: ${e.message}")
            stopSelf()
        }
    }

    private fun onTapButton() {
        val acc = PairAccessibilityService.instance
        if (acc == null) {
            AppLogger.log("FLOAT", "Accessibility service not running — enable it")
            return
        }
        // Tap on the CURRENT screen — don't relaunch Play Store (that would reset
        // it to home). You navigate to the sign-in screen, then press this.
        AppLogger.log("FLOAT", "running sign-in gestures on current foreground screen (profile $userId)")
        acc.runGoogleSignIn(email)
    }

    override fun onDestroy() {
        super.onDestroy()
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    companion object {
        const val EXTRA_EMAIL = "email"
        const val EXTRA_USER = "user"
    }
}
