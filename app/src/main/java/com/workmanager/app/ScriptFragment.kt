package com.workmanager.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScriptFragment : Fragment(R.layout.fragment_script) {

    private lateinit var editor: EditText
    private lateinit var scriptNameLabel: TextView
    private lateinit var runBtn: Button
    private lateinit var stopBtn: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        editor = view.findViewById(R.id.scriptText)
        scriptNameLabel = view.findViewById(R.id.scriptNameLabel)
        runBtn = view.findViewById(R.id.btnRunScript)
        stopBtn = view.findViewById(R.id.btnStopScript)

        loadActiveScript()

        scriptNameLabel.setOnClickListener { showScriptManager() }
        view.findViewById<Button>(R.id.btnSaveScript).setOnClickListener { saveCurrent() }
        runBtn.setOnClickListener { run() }
        stopBtn.setOnClickListener {
            ScriptRunnerService.requestStop()
            stopBtn.isEnabled = false
            stopBtn.text = "Stopping…"
        }

        if (ScriptRunnerService.isRunning) attachRunningState()

        view.findViewById<Button>(R.id.btnCoordOverlay).setOnClickListener { showCoords() }
        view.findViewById<Button>(R.id.btnCommands).setOnClickListener { showCommands() }
        view.findViewById<Button>(R.id.btnHelp).setOnClickListener { showHelp() }

        view.findViewById<Button>(R.id.btnCommentAll).setOnClickListener {
            val lines = editor.text.toString().lines()
            editor.setText(lines.joinToString("\n") { "// $it" })
            toast("All lines commented")
        }
        view.findViewById<Button>(R.id.btnUncommentAll).setOnClickListener {
            val lines = editor.text.toString().lines()
            editor.setText(lines.joinToString("\n") { it.replaceFirst(Regex("^\\s*//\\s?"), "") })
            toast("All lines uncommented")
        }
    }

    private fun loadActiveScript() {
        val ctx = requireContext()
        val name = ScriptStore.activeName(ctx)
        scriptNameLabel.text = name
        editor.setText(ScriptStore.load(ctx, name))
    }

    private fun saveCurrent() {
        val ctx = requireContext()
        val name = ScriptStore.activeName(ctx)
        ScriptStore.save(ctx, name, editor.text.toString())
        toast("Saved: $name")
    }

    // ── Script manager ────────────────────────────────────────────────────────

    private fun showScriptManager() {
        val ctx = requireContext()
        val names = ScriptStore.list(ctx)
        val active = ScriptStore.activeName(ctx)
        val items = names.map { if (it == active) "★ $it" else "  $it" }.toMutableList()
        items.add("+ New script")

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Scripts")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < names.size) {
                    val name = names[which]
                    ScriptStore.setActive(ctx, name)
                    loadActiveScript()
                    showScriptOptions(name)
                } else {
                    promptNewScript()
                }
            }
            .show()
    }

    private fun showScriptOptions(name: String) {
        val options = arrayOf("Load", "Rename", "Delete")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> loadActiveScript()
                    1 -> promptRename(name)
                    2 -> promptDelete(name)
                }
            }
            .show()
    }

    private fun promptNewScript() {
        val container = hackerInput("Script name")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("New Script")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = inputText(container).ifEmpty { "script_${System.currentTimeMillis()}" }
                ScriptStore.save(requireContext(), name, "")
                ScriptStore.setActive(requireContext(), name)
                loadActiveScript()
                toast("Created: $name")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptRename(oldName: String) {
        val container = hackerInput("New name", prefill = oldName)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename Script")
            .setView(container)
            .setPositiveButton("Rename") { _, _ ->
                val newName = inputText(container)
                if (newName.isNotEmpty() && newName != oldName) {
                    ScriptStore.rename(requireContext(), oldName, newName)
                    loadActiveScript()
                    toast("Renamed to $newName")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptDelete(name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete '$name'?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                ScriptStore.delete(requireContext(), name)
                loadActiveScript()
                toast("Deleted: $name")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Run script ────────────────────────────────────────────────────────────

    private fun run() {
        val script = editor.text.toString()
        if (script.isBlank()) { toast("Write a script first"); return }
        if (PairAccessibilityService.instance == null) {
            toast("Enable 'Work Manager' Accessibility first (taps use gestures)")
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!Session.connected) {
            toast("Connect to ADB first (use the Connect tab)")
            return
        }
        if (Session.selectedUserId <= 0) {
            toast("Select a work profile first (Profiles tab)")
            return
        }
        saveCurrent()

        val ctx = requireContext().applicationContext
        val userId = Session.selectedUserId

        runBtn.isEnabled = false
        runBtn.text = "Connecting…"

        lifecycleScope.launch {
            val alive = withContext(Dispatchers.IO) {
                Session.adb.isAlive()
                    || Session.adb.tryReconnect()
                    || AdbAutoConnect.autoConnectUsb(ctx, Session.adb)
            }
            if (!alive) {
                Toast.makeText(ctx, "ADB disconnected — go to Connect tab and reconnect", Toast.LENGTH_SHORT).show()
                runBtn.isEnabled = true
                runBtn.text = "▶ Run"
                return@launch
            }

            ctx.startForegroundService(
                Intent(ctx, ScriptRunnerService::class.java)
                    .putExtra(ScriptRunnerService.EXTRA_SCRIPT, script)
                    .putExtra(ScriptRunnerService.EXTRA_USER, userId)
            )

            kotlinx.coroutines.delay(300)
            attachRunningState()
        }
    }

    private fun attachRunningState() {
        runBtn.isEnabled = false
        runBtn.text = "Running…"
        stopBtn.visibility = View.VISIBLE
        stopBtn.isEnabled = true
        stopBtn.text = "■ Stop Script"

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                while (ScriptRunnerService.isRunning) Thread.sleep(300)
            }
            if (!isAdded) return@launch
            stopBtn.visibility = View.GONE
            runBtn.isEnabled = true
            runBtn.text = "▶ Run"
        }
    }

    private fun showCoords() {
        if (!android.provider.Settings.canDrawOverlays(requireContext())) {
            toast("Grant 'Display over other apps', then tap again")
            startActivity(Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${requireContext().packageName}")
            ))
            return
        }
        requireContext().startService(Intent(requireContext(), CoordinatePickerService::class.java))
        toast("Tap 👁 to hide overlay, 📝 for mini editor")
    }

    // ── Commands panel ────────────────────────────────────────────────────────

    private fun showCommands() {
        val items = arrayOf(
            "open  — Launch app",
            "close — Force-stop app",
            "link  — Open URL in app",
            "scroll — Scroll up/down",
            "tap   — Tap at X,Y",
            "click — Tap button by label",
            "swipe — Swipe from X1,Y1 to X2,Y2",
            "text  — Type into focused field",
            "unfocus — Dismiss keyboard",
            "wait  — Pause (ms)",
            "detect — Wait for text on screen",
            "dump  — Log UI hierarchy",
            "if    — If text on screen",
            "else  — Else branch",
            "endif — End if block",
            "key   — Press back/home/enter"
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Insert command")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickAppForOpen()
                    1 -> pickAppForClose()
                    2 -> pickAppForLink()
                    3 -> promptScroll()
                    4 -> promptTap()
                    5 -> promptClick()
                    6 -> promptSwipe()
                    7 -> promptText()
                    8 -> insertAtCursor("unfocus")
                    9 -> promptWait()
                    10 -> promptDetect()
                    11 -> insertAtCursor("dump")
                    12 -> promptIf()
                    13 -> insertAtCursor("else")
                    14 -> insertAtCursor("endif")
                    15 -> promptKey()
                }
            }
            .show()
    }

    // ── App pickers ───────────────────────────────────────────────────────────

    private fun pickAppForOpen() {
        if (!Session.connected) { toast("Connect to ADB first"); return }
        val userId = Session.selectedUserId
        if (userId <= 0) { toast("Select a work profile first (Profiles tab)"); return }
        lifecycleScope.launch {
            toast("Loading apps…")
            val pkgs = withContext(Dispatchers.IO) { Session.profiles.listLaunchableApps(userId) }
            if (pkgs.isEmpty()) { toast("No apps found in profile $userId"); return@launch }
            val entries = withContext(Dispatchers.Default) { resolveAppInfos(pkgs) }
            showAppPicker("Open in profile $userId", entries) { pkg -> insertAtCursor("open $pkg") }
        }
    }

    private fun pickAppForClose() {
        if (!Session.connected) { toast("Connect to ADB first"); return }
        val userId = Session.selectedUserId
        if (userId <= 0) { toast("Select a work profile first (Profiles tab)"); return }
        lifecycleScope.launch {
            toast("Loading apps…")
            val pkgs = withContext(Dispatchers.IO) { Session.profiles.listLaunchableApps(userId) }
            if (pkgs.isEmpty()) { toast("No apps found in profile $userId"); return@launch }
            val entries = withContext(Dispatchers.Default) { resolveAppInfos(pkgs) }
            showAppPicker("Close (force-stop) in profile $userId", entries) { pkg -> insertAtCursor("close $pkg") }
        }
    }

    private fun pickAppForLink() {
        if (!Session.connected) { toast("Connect to ADB first"); return }
        val userId = Session.selectedUserId
        if (userId <= 0) { toast("Select a work profile first"); return }
        lifecycleScope.launch {
            toast("Loading apps…")
            val pkgs = withContext(Dispatchers.IO) { Session.profiles.listLaunchableApps(userId) }
            if (pkgs.isEmpty()) { toast("No apps found"); return@launch }
            val entries = withContext(Dispatchers.Default) { resolveAppInfos(pkgs) }
            showAppPicker("Pick app for link  (profile $userId)", entries) { pkg -> promptLinkUrl(pkg) }
        }
    }

    // ── Searchable icon+name app picker dialog ────────────────────────────────

    private fun showAppPicker(title: String, entries: List<AppEntry>, onPick: (String) -> Unit) {
        val pm = requireContext().packageManager
        var shown = entries.toMutableList()
        val dialogView = layoutInflater.inflate(R.layout.dialog_install_config, null)
        val search = dialogView.findViewById<EditText>(R.id.searchInput)
        val list = dialogView.findViewById<ListView>(R.id.configAppList)

        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(pos: Int) = shown[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = cv ?: layoutInflater.inflate(R.layout.item_app, parent, false)
                val e = shown[pos]
                v.findViewById<ImageView>(R.id.appIcon).setImageDrawable(e.icon ?: pm.defaultActivityIcon)
                v.findViewById<TextView>(R.id.appName).text = e.label
                v.findViewById<TextView>(R.id.appPkg).text = e.pkg
                return v
            }
        }
        list.adapter = adapter

        var dialog: AlertDialog? = null
        list.setOnItemClickListener { _, _, pos, _ ->
            onPick(shown[pos].pkg)
            dialog?.dismiss()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                shown = if (q.isEmpty()) entries.toMutableList()
                        else entries.filter { e -> e.label.lowercase().contains(q) || e.pkg.lowercase().contains(q) }.toMutableList()
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── App info resolution ───────────────────────────────────────────────────

    private data class AppEntry(val pkg: String, val label: String, val icon: Drawable?)

    private fun resolveAppInfos(pkgs: List<String>): List<AppEntry> {
        val pm = requireContext().packageManager
        return pkgs.map { pkg ->
            try {
                val ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                val raw = pm.getApplicationLabel(ai).toString()
                val label = if (raw.isBlank() || raw.equals("android", ignoreCase = true) || raw == pkg)
                    humanize(pkg) else raw
                val icon = try { pm.getApplicationIcon(ai) } catch (_: Exception) { null }
                AppEntry(pkg, label, icon)
            } catch (_: Exception) {
                AppEntry(pkg, humanize(pkg), null)
            }
        }.sortedBy { it.label.lowercase() }
    }

    private fun humanize(pkg: String): String {
        val last = pkg.split('.').lastOrNull { it.length > 2 } ?: pkg.substringAfterLast('.')
        return last.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").replaceFirstChar { it.uppercase() }
    }

    // ── Other command prompts ─────────────────────────────────────────────────

    private fun promptLinkUrl(pkg: String) {
        val container = hackerInput("https://example.com/path")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("URL for  $pkg")
            .setView(container)
            .setPositiveButton("Insert") { _, _ ->
                val url = inputText(container)
                if (url.isNotEmpty()) insertAtCursor("link $url $pkg")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptTap() {
        val container = hackerInput("x  y   (e.g. 534 1614)")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Tap Coordinates")
            .setView(container)
            .setPositiveButton("Insert") { _, _ ->
                val v = inputText(container)
                if (v.isNotEmpty()) insertAtCursor("tap $v")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptClick() {
        val acc = PairAccessibilityService.instance
        if (acc == null) { toast("Enable Accessibility first"); return }
        val labels = acc.findClickableLabels()
        if (labels.isEmpty()) { toast("No clickable elements found on screen"); return }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pick button to click")
            .setItems(labels.toTypedArray()) { _, which -> insertAtCursor("click ${labels[which]}") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptSwipe() {
        val container = hackerInput("x1 y1 x2 y2  [ms]")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Swipe Coordinates")
            .setView(container)
            .setPositiveButton("Insert") { _, _ ->
                val v = inputText(container)
                if (v.isNotEmpty()) insertAtCursor("swipe $v")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptText() {
        val container = hackerInput("Text to type")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Type Text")
            .setView(container)
            .setPositiveButton("Insert") { _, _ ->
                val v = inputText(container)
                if (v.isNotEmpty()) insertAtCursor("text $v")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptWait() {
        val container = hackerInput("Milliseconds  (e.g. 3000)", numeric = true)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Wait Duration")
            .setView(container)
            .setPositiveButton("Insert") { _, _ ->
                val v = inputText(container)
                if (v.isNotEmpty()) insertAtCursor("wait $v")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptDetect() {
        val container = hackerInput("Text to wait for  (max 15 s)")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Detect Text on Screen")
            .setView(container)
            .setPositiveButton("Insert") { _, _ ->
                val v = inputText(container)
                if (v.isNotEmpty()) insertAtCursor("detect $v")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptKey() {
        val keys = arrayOf("back", "home", "enter")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Press key")
            .setItems(keys) { _, which -> insertAtCursor("key ${keys[which]}") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptIf() {
        val container = hackerInput("Text to check on screen")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("If Text on Screen")
            .setView(container)
            .setPositiveButton("Insert") { _, _ ->
                val v = inputText(container)
                if (v.isNotEmpty()) {
                    insertAtCursor("if $v\n  // do this if found\nelse\n  // do this if not found\nendif")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptScroll() {
        val dirs = arrayOf("down", "up")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Scroll Direction")
            .setItems(dirs) { _, which ->
                val dir = dirs[which]
                val container = hackerInput("Times  (default 1)", numeric = true)
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Scroll $dir — how many times?")
                    .setView(container)
                    .setPositiveButton("Insert") { _, _ ->
                        val times = inputText(container).ifEmpty { "1" }
                        insertAtCursor("scroll $dir $times")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Help ──────────────────────────────────────────────────────────────────

    private fun showHelp() {
        val view = layoutInflater.inflate(R.layout.dialog_help, null)
        MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy All") { _, _ ->
                val help = """
SCRIPT COMMANDS GUIDE

open com.app.package     Opens app in work profile
close com.app.package    Force-stops app
link https://url com.app Opens URL in specific app
tap 534 1614             Tap at X,Y coordinates
swipe 100 500 100 1500   Swipe (x1 y1 x2 y2 ms)
click Install            Tap button by visible text
scroll down              Scroll down once
scroll up 3              Scroll up 3 times
text hello world         Type text + press Enter
unfocus                  Dismiss keyboard
wait 3000                Pause 3 seconds
detect Sign in           Wait up to 15s for text
dump                     Log elements with coordinates
if Install               Check if text on screen
  tap 534 1614
else
  tap 534 800
endif
key back                 Press back button
key home                 Press home button
# comment               Comment line
// comment              Comment line
                """.trimIndent()
                val clip = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("help", help))
                toast("Help copied to clipboard")
            }
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun insertAtCursor(text: String) {
        val pos = editor.selectionStart
        val current = editor.text.toString()
        val needsNewline = pos > 0 && current.getOrNull(pos - 1) != '\n'
        val insert = if (needsNewline) "\n$text\n" else "$text\n"
        editor.text.insert(pos, insert)
    }

    private fun hackerInput(hint: String, prefill: String = "", numeric: Boolean = false) =
        android.widget.FrameLayout(requireContext()).apply {
            setPadding(56, 0, 56, 0)
            addView(EditText(requireContext()).apply {
                tag = "input"
                this.hint = hint
                if (prefill.isNotEmpty()) setText(prefill)
                inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER
                            else android.text.InputType.TYPE_CLASS_TEXT or
                                 android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setHintTextColor(android.graphics.Color.parseColor("#2a4060"))
                setTextColor(android.graphics.Color.parseColor("#c8d8e8"))
                setBackgroundResource(R.drawable.bg_input)
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 14f
                setPadding(28, 20, 28, 20)
            })
        }

    private fun inputText(container: android.widget.FrameLayout): String =
        (container.findViewWithTag<EditText>("input"))?.text?.toString()?.trim().orEmpty()

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()
}
