package com.workmanager.app

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Coordinate picker overlay — redesigned.
 *
 *  SHOWN:  semi-transparent capture overlay + vertical card panel + mini editor.
 *  HIDDEN: only a small restore icon in the corner.
 *
 *  Drag the "≡ Coords" header to reposition the panel.
 *  All buttons respond to taps without interference from the drag logic.
 */
class CoordinatePickerService : Service() {

    private lateinit var wm: WindowManager
    private var overlayView: View? = null
    private var panelView: View? = null
    private var miniIcon: View? = null
    private var editorPanel: View? = null
    private var miniEditor: EditText? = null
    private var coordLabel: TextView? = null
    private var editorVisible = false
    private var lastX = 0
    private var lastY = 0
    private var hidden = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null) buildUi()
        return START_NOT_STICKY
    }

    // ── Build all views ────────────────────────────────────────────────────────

    private fun buildUi() {
        wm = getSystemService(WindowManager::class.java)

        buildCaptureOverlay()
        buildPanel()
        buildEditorPanel()

        try {
            wm.addView(overlayView, captureParams())
            wm.addView(panelView, panelParams(200))
            showEditorPanel()
        } catch (e: Exception) {
            AppLogger.log("PICK", "overlay failed: ${e.message}")
            stopSelf()
        }
    }

    private fun buildCaptureOverlay() {
        val ctx = this
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(18, 0, 0, 0))
        }
        val hint = TextView(ctx).apply {
            text = "Tap anywhere to capture X,Y"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(20, 12, 20, 12)
        }
        root.addView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        root.setOnTouchListener { _, e ->
            if (hidden) return@setOnTouchListener false
            val x = e.rawX.toInt(); val y = e.rawY.toInt()
            when (e.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> hint.text = "X: $x   Y: $y"
                MotionEvent.ACTION_UP -> {
                    lastX = x; lastY = y
                    hint.text = "X: $x   Y: $y  ✓"
                    coordLabel?.text = "$x,  $y"
                    Toast.makeText(ctx, "X=$x  Y=$y", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        overlayView = root
    }

    private fun buildPanel() {
        val ctx = this
        val dp = resources.displayMetrics.density

        // Card background
        val cardBg = GradientDrawable().apply {
            setColor(Color.argb(235, 13, 13, 35))
            cornerRadius = 18f * dp
            setStroke((1 * dp).toInt(), Color.argb(100, 100, 150, 255))
        }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            minimumWidth = (120 * dp).toInt()
        }

        // ── Drag handle header ─────────────────────────────────────────────────
        val headerBg = GradientDrawable().apply {
            setColor(Color.argb(200, 30, 30, 80))
            // top corners rounded to match card
            cornerRadii = floatArrayOf(18f*dp, 18f*dp, 18f*dp, 18f*dp, 0f, 0f, 0f, 0f)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = headerBg
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (10*dp).toInt(), 0, (10*dp).toInt())
        }
        header.addView(TextView(ctx).apply {
            text = "≡  Coords"
            setTextColor(Color.rgb(160, 190, 255))
            textSize = 11f
            gravity = Gravity.CENTER
        })
        val cLabel = TextView(ctx).apply {
            text = "–,  –"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(16, 4, 16, 4)
        }
        coordLabel = cLabel
        header.addView(cLabel)
        panel.addView(header, lpFill())

        // ── Action buttons ─────────────────────────────────────────────────────
        panel.addView(divider(ctx, dp))

        panel.addView(panelBtn(ctx, dp, "📋  Copy", "#2196F3") {
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("coords", "$lastX $lastY"))
            Toast.makeText(ctx, "Copied  $lastX  $lastY", Toast.LENGTH_SHORT).show()
        })

        panel.addView(panelBtn(ctx, dp, "+  Tap", "#4CAF50") {
            val ed = miniEditor
            if (ed == null) {
                Toast.makeText(ctx, "Open editor first (📝)", Toast.LENGTH_SHORT).show()
                return@panelBtn
            }
            val line = "tap $lastX $lastY"
            val cur = ed.text.toString()
            ed.setText(if (cur.isEmpty()) line else "$cur\n$line")
            ed.setSelection(ed.text.length)
            Toast.makeText(ctx, "Added: $line", Toast.LENGTH_SHORT).show()
        })

        panel.addView(panelBtn(ctx, dp, "🔍  UI Dump", "#673AB7") {
            if (!Session.connected) {
                Toast.makeText(ctx, "Connect ADB first", Toast.LENGTH_SHORT).show()
                return@panelBtn
            }
            Toast.makeText(ctx, "Dumping UI…", Toast.LENGTH_SHORT).show()
            Thread {
                val result = Session.profiles.dumpUiElements()
                mainHandler.post { Toast.makeText(ctx, result, Toast.LENGTH_LONG).show() }
            }.start()
        })

        panel.addView(panelBtn(ctx, dp, "📝  Editor", "#FF9800") {
            toggleEditor()
        })

        panel.addView(divider(ctx, dp))

        panel.addView(panelBtn(ctx, dp, "👁  Hide", "#37474F") {
            hideOverlay()
        })

        panel.addView(panelBtn(ctx, dp, "✕  Close", "#C62828") {
            stopSelf()
        })

        // ── Drag: ONLY the header moves the panel ──────────────────────────────
        var dRawX = 0f; var dRawY = 0f
        header.setOnTouchListener { _, ev ->
            val lp = panelView?.layoutParams as? WindowManager.LayoutParams
                ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { dRawX = ev.rawX; dRawY = ev.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x += (ev.rawX - dRawX).toInt()
                    lp.y += (ev.rawY - dRawY).toInt()
                    dRawX = ev.rawX; dRawY = ev.rawY
                    try { wm.updateViewLayout(panelView, lp) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        panelView = panel
    }

    private fun buildEditorPanel() {
        val ctx = this
        val ep = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(242, 14, 14, 30))
            setPadding(12, 10, 12, 10)
        }

        val nameLabel = TextView(ctx).apply {
            text = ScriptStore.activeName(ctx)
            setTextColor(Color.rgb(130, 177, 255))
            textSize = 11f
            tag = "scriptName"
        }
        ep.addView(nameLabel)

        val editor = EditText(ctx).apply {
            setText(ScriptStore.load(ctx, ScriptStore.activeName(ctx)))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(80, 80, 80))
            setBackgroundColor(Color.rgb(20, 20, 40))
            textSize = 12f
            minLines = 4; maxLines = 8
            isSingleLine = false
            setPadding(10, 8, 10, 8)
            tag = "miniEditor"
        }
        miniEditor = editor
        ep.addView(editor, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = 6; bottomMargin = 6 })

        // Row 1: Run + Save
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(editorBtn(ctx, "▶ Run", "#4CAF50") {
            saveEditor()
            val script = editor.text.toString()
            if (script.isBlank()) { toast("Script empty"); return@editorBtn }
            if (!Session.connected) { toast("Connect ADB first"); return@editorBtn }
            if (Session.selectedUserId <= 0) { toast("Select a work profile first"); return@editorBtn }
            hideOverlay()
            ctx.startForegroundService(
                Intent(ctx, ScriptRunnerService::class.java)
                    .putExtra(ScriptRunnerService.EXTRA_SCRIPT, script)
                    .putExtra(ScriptRunnerService.EXTRA_USER, Session.selectedUserId)
            )
            toast("Running…")
        }, weight = 1f)
        row1.addView(editorBtn(ctx, "💾 Save", "#1565C0") {
            saveEditor(); toast("Saved")
        }, weight = 1f, start = 4)
        ep.addView(row1, lpFill())

        // Row 2: Comment / Uncomment
        val row2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }
        row2.addView(editorBtn(ctx, "// All", "#4E342E") {
            editor.setText(editor.text.toString().lines().joinToString("\n") { "// $it" })
            toast("Commented")
        }, weight = 1f)
        row2.addView(editorBtn(ctx, "Uncomment", "#33691E") {
            editor.setText(editor.text.toString().lines()
                .joinToString("\n") { it.replaceFirst(Regex("^\\s*//\\s?"), "") })
            toast("Uncommented")
        }, weight = 1f, start = 4)
        ep.addView(row2, lpFill())

        editorPanel = ep
    }

    // ── Overlay visibility helpers ─────────────────────────────────────────────

    private fun hideOverlay() {
        if (hidden) return
        hidden = true
        overlayView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            it.visibility = View.INVISIBLE
            try { wm.updateViewLayout(it, p) } catch (_: Exception) {}
        }
        panelView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        if (editorVisible) {
            editorPanel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            editorVisible = false
        }
        // Show tiny restore icon
        val icon = miniIcon ?: Button(this).apply {
            text = "📍"
            textSize = 20f
            setBackgroundColor(Color.argb(200, 20, 20, 60))
            setPadding(6, 6, 6, 6)
            setOnClickListener { showOverlay() }
            miniIcon = this
        }
        try {
            wm.addView(icon, WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.END; x = 8; y = 200 })
        } catch (_: Exception) {}
    }

    private fun showOverlay() {
        if (!hidden) return
        hidden = false
        miniIcon?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlayView?.let {
            val p = it.layoutParams as WindowManager.LayoutParams
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            it.visibility = View.VISIBLE
            try { wm.updateViewLayout(it, p) } catch (_: Exception) {}
        }
        panelView?.let {
            try { wm.addView(it, panelParams(200)) } catch (_: Exception) {}
        }
        editorVisible = false
        showEditorPanel()
        coordLabel?.text = if (lastX == 0 && lastY == 0) "–,  –" else "$lastX,  $lastY"
    }

    private fun toggleEditor() {
        if (editorVisible) {
            editorPanel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            editorVisible = false
        } else {
            showEditorPanel()
        }
    }

    private fun showEditorPanel() {
        val ep = editorPanel ?: return
        if (editorVisible) return
        miniEditor?.setText(ScriptStore.load(this, ScriptStore.activeName(this)))
        ep.findViewWithTag<TextView>("scriptName")?.text = ScriptStore.activeName(this)
        try {
            wm.addView(ep, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, 640,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            })
            editorVisible = true
        } catch (_: Exception) {}
    }

    private fun saveEditor() {
        miniEditor?.text?.toString()?.let { content ->
            ScriptStore.save(this, ScriptStore.activeName(this), content)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (editorVisible) saveEditor()
        listOfNotNull(overlayView, panelView, miniIcon, editorPanel).forEach {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null; panelView = null; miniIcon = null
        editorPanel = null; miniEditor = null; coordLabel = null
    }

    // ── Layout param helpers ───────────────────────────────────────────────────

    private fun captureParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    )

    private fun panelParams(y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.END; x = 8; this.y = y }

    private fun lpFill() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun divider(ctx: Context, dp: Float): View = View(ctx).apply {
        setBackgroundColor(Color.argb(50, 150, 180, 255))
    }.also { v ->
        v.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
        ).apply { setMargins(0, (4*dp).toInt(), 0, (4*dp).toInt()) }
    }

    private fun panelBtn(ctx: Context, dp: Float, label: String, hex: String, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor(hex))
                cornerRadius = 8f * dp
            }
            background = bg
            setOnClickListener { onClick() }
        }.also { btn ->
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((8*dp).toInt(), (3*dp).toInt(), (8*dp).toInt(), (3*dp).toInt())
            }
        }
    }

    private fun editorBtn(ctx: Context, label: String, hex: String, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            text = label
            textSize = 10f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setBackgroundColor(Color.parseColor(hex))
            setMinimumWidth(0); minimumWidth = 0
            setPadding(8, 0, 8, 0)
            setOnClickListener { onClick() }
        }
    }

    private fun LinearLayout.addView(view: Button, weight: Float, start: Int = 0) {
        addView(view, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, weight
        ).apply { marginStart = start })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}