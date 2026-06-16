package com.workmanager.app

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsFragment : Fragment(R.layout.fragment_apps) {

    private lateinit var selected: TextView
    private lateinit var appAdapter: AppAdapter
    private var entries = listOf<AppAdapter.Entry>()
    private var chosen: AppAdapter.Entry? = null

    private val pickApk = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { installApk(it) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        selected = view.findViewById(R.id.selectedProfile)
        val list = view.findViewById<ListView>(R.id.appList)

        appAdapter = AppAdapter(requireContext())
        list.adapter = appAdapter
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos < entries.size) chosen = entries[pos]
        }

        view.findViewById<Button>(R.id.btnAddGms).setOnClickListener { addGooglePlay() }
        view.findViewById<Button>(R.id.btnConfigureInstall).setOnClickListener { showInstallConfig() }
        view.findViewById<Button>(R.id.btnOpenInProfile).setOnClickListener { openInProfile() }
        view.findViewById<Button>(R.id.btnLoadApps).setOnClickListener { loadApps() }
        view.findViewById<Button>(R.id.btnInstallToProfile).setOnClickListener { installExisting() }
        view.findViewById<Button>(R.id.btnInstallApk).setOnClickListener {
            if (require()) pickApk.launch("*/*")
        }
    }

    override fun onResume() {
        super.onResume()
        updateSelected()
    }

    private fun updateSelected() {
        val id = Session.selectedUserId
        selected.text = if (id > 0) "Profile: $id  — tap Load Apps to see installed" else "No profile selected — pick one in Profiles"
    }

    private fun require(): Boolean {
        if (!Session.connected) { toast("Not connected"); return false }
        if (Session.selectedUserId <= 0) { toast("Select a profile in Profiles tab"); return false }
        return true
    }

    private fun loadApps() {
        if (!Session.connected) { toast("Not connected — connect first"); return }
        lifecycleScope.launch {
            selected.text = "Loading device apps…"
            val pkgs = withContext(Dispatchers.IO) {
                val raw = Session.adb.exec("pm list packages -3")
                raw.lines()
                    .map { it.removePrefix("package:").trim() }
                    .filter { it.isNotEmpty() && it.contains(".") }
                    .sorted()
            }
            if (pkgs.isEmpty()) {
                selected.text = "No third-party apps found"
                toast("No apps returned — check connection")
                return@launch
            }
            entries = withContext(Dispatchers.Default) { resolveAppInfos(pkgs) }
            appAdapter.setEntries(entries)
            updateSelected()
            toast("Loaded ${entries.size} apps")
        }
    }

    private fun installExisting() {
        if (!require()) return
        val entry = chosen ?: run { toast("Tap an app in the list first"); return }
        lifecycleScope.launch {
            selected.text = "Installing ${entry.label} → profile ${Session.selectedUserId}…"
            val out = withContext(Dispatchers.IO) {
                Session.profiles.installExisting(entry.pkg, Session.selectedUserId)
            }
            toast(if (out.contains("Success")) "Installed ${entry.label}!" else "Failed: ${out.take(80)}")
            updateSelected()
        }
    }

    private fun installApk(uri: Uri) {
        if (!require()) return
        lifecycleScope.launch {
            selected.text = "Installing APK to profile ${Session.selectedUserId}…"
            val out = withContext(Dispatchers.IO) {
                Session.profiles.installApk(
                    { requireContext().contentResolver.openInputStream(uri) },
                    Session.selectedUserId
                )
            }
            toast(if (out.contains("Success")) "Installed!" else "Failed: ${out.take(100)}")
            updateSelected()
        }
    }

    private fun addGooglePlay() {
        if (!require()) return
        val toInstall = InstallConfig.enabledPackages(requireContext()).toList()
        if (toInstall.isEmpty()) { toast("No apps selected — tap ⚙ to configure"); return }
        lifecycleScope.launch {
            selected.text = "Installing ${toInstall.size} apps to profile ${Session.selectedUserId}…"
            val out = withContext(Dispatchers.IO) {
                Session.profiles.installGoogleServices(Session.selectedUserId, toInstall)
            }
            AppLogger.log("APP", "Install result:\n$out")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Install Complete")
                .setMessage("$out\n\nOpen Play Store via 'Open app in profile', then sign in.")
                .setPositiveButton("OK", null)
                .show()
            updateSelected()
        }
    }

    private fun showInstallConfig() {
        if (entries.isEmpty()) {
            toast("Load apps first — tap 'Load Apps'")
            return
        }
        val saved = InstallConfig.enabledPackages(requireContext())
        val checked = HashSet<String>(saved)

        val dialogView = layoutInflater.inflate(R.layout.dialog_install_config, null)
        val searchInput = dialogView.findViewById<EditText>(R.id.searchInput)
        val listView = dialogView.findViewById<ListView>(R.id.configAppList)

        val adapter = ConfigAdapter(requireContext(), entries, checked)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            val entry = adapter.getItem(pos)
            if (entry.pkg in checked) checked.remove(entry.pkg) else checked.add(entry.pkg)
            adapter.notifyDataSetChanged()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Configure Default Apps")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                InstallConfig.save(requireContext(), checked)
                toast("Saved: ${checked.size} apps selected")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openInProfile() {
        if (!require()) return
        lifecycleScope.launch {
            val pkgs = withContext(Dispatchers.IO) {
                Session.profiles.listLaunchableApps(Session.selectedUserId)
            }
            if (pkgs.isEmpty()) { toast("No launchable apps in profile ${Session.selectedUserId}"); return@launch }
            val infos = withContext(Dispatchers.Default) { resolveAppInfos(pkgs) }
            val labels = infos.map { "${it.label}\n${it.pkg}" }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Open in profile ${Session.selectedUserId}")
                .setItems(labels) { _, which -> launchPkg(infos[which].pkg) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun launchPkg(pkg: String) {
        lifecycleScope.launch {
            selected.text = "Launching $pkg…"
            val out = withContext(Dispatchers.IO) {
                Session.profiles.launchApp(pkg, Session.selectedUserId)
            }
            if (out.startsWith("ERROR") || out.contains("Error")) toast("Couldn't launch: ${out.take(80)}")
            else toast("Launched in profile ${Session.selectedUserId}")
            updateSelected()
        }
    }

    private fun resolveAppInfos(pkgs: List<String>): List<AppAdapter.Entry> {
        val pm = requireContext().packageManager
        return pkgs.map { pkg ->
            try {
                // MATCH_UNINSTALLED_PACKAGES also returns info for packages visible only
                // to the ADB shell user (e.g. work-profile-only installs) that the local
                // PackageManager wouldn't normally resolve with flag 0.
                val ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                val raw = pm.getApplicationLabel(ai).toString()
                // Some MIUI system stubs return "Android" as their label; humanize instead.
                val label = if (raw.isBlank() || raw.equals("android", ignoreCase = true) || raw == pkg)
                    humanize(pkg) else raw
                val icon = try { pm.getApplicationIcon(ai) } catch (_: Exception) { pm.defaultActivityIcon }
                AppAdapter.Entry(pkg, label, icon)
            } catch (_: Exception) {
                AppAdapter.Entry(pkg, humanize(pkg), pm.defaultActivityIcon)
            }
        }.sortedBy { it.label.lowercase() }
    }

    private fun humanize(pkg: String): String {
        val last = pkg.split('.').lastOrNull { it.length > 2 } ?: pkg.substringAfterLast('.')
        return last.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").replaceFirstChar { it.uppercase() }
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    // ── Config adapter: searchable + checkable ────────────────────────────────

    private inner class ConfigAdapter(
        private val ctx: Context,
        allEntries: List<AppAdapter.Entry>,
        private val checked: HashSet<String>
    ) : BaseAdapter() {
        private val all = allEntries.toList()
        private var shown = all.toMutableList()

        fun filter(query: String) {
            val q = query.trim().lowercase()
            shown = if (q.isEmpty()) all.toMutableList()
                    else all.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }.toMutableList()
            notifyDataSetChanged()
        }

        override fun getCount() = shown.size
        override fun getItem(pos: Int) = shown[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(ctx).inflate(R.layout.item_check_app, parent, false)
            val entry = shown[pos]
            v.findViewById<CheckBox>(R.id.checkBox).isChecked = entry.pkg in checked
            v.findViewById<ImageView>(R.id.appIcon).setImageDrawable(
                entry.icon ?: ctx.packageManager.defaultActivityIcon
            )
            v.findViewById<TextView>(R.id.appName).text = entry.label
            v.findViewById<TextView>(R.id.appPkg).text = entry.pkg
            return v
        }
    }

    // ── Main app list adapter ─────────────────────────────────────────────────

    class AppAdapter(private val ctx: Context) : BaseAdapter() {

        data class Entry(val pkg: String, val label: String, val icon: Drawable?)

        private val items = mutableListOf<Entry>()

        fun setEntries(list: List<Entry>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(pos: Int): Entry = items[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(ctx).inflate(R.layout.item_app, parent, false)
            val entry = items[pos]
            v.findViewById<ImageView>(R.id.appIcon).setImageDrawable(
                entry.icon ?: ctx.packageManager.defaultActivityIcon
            )
            v.findViewById<TextView>(R.id.appName).text = entry.label
            v.findViewById<TextView>(R.id.appPkg).text = entry.pkg
            return v
        }
    }
}
