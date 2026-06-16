package com.workmanager.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfilesFragment : Fragment(R.layout.fragment_profiles) {

    private lateinit var status: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private var users = listOf<WorkProfileManager.UserProfile>()

    /** Result of the system managed-profile provisioning flow. */
    private val provision = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        AppLogger.log("DPC", "provisioning result code = ${res.resultCode} (RESULT_OK = ${Activity.RESULT_OK})")
        if (res.resultCode == Activity.RESULT_OK)
            toast("Work profile setup started — finish the on-screen steps")
        else
            toast("Provisioning cancelled or blocked by ROM — see Logs")
        logCurrentUsers()
    }

    /** Logs every user/profile on the device (for diagnosing whether one was created). */
    private fun logCurrentUsers() {
        if (!Session.connected) { AppLogger.log("DPC", "not connected — can't list users"); return }
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) { Session.profiles.getUsers() }
            AppLogger.log("DPC", "Users now:\n$out")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        status = view.findViewById(R.id.profileStatus)
        val list = view.findViewById<ListView>(R.id.userList)
        adapter = ArrayAdapter(requireContext(), R.layout.item_list, R.id.text1, mutableListOf())
        list.adapter = adapter

        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos < users.size) {
                Session.selectedUserId = users[pos].id
                toast("Selected: ${users[pos].name} (ID: ${users[pos].id})")
            }
        }

        view.findViewById<Button>(R.id.btnProvisionDpc).setOnClickListener { provisionDpc() }
        view.findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        view.findViewById<Button>(R.id.btnCreateProfile).setOnClickListener { showCreateDialog() }
        view.findViewById<Button>(R.id.btnRemoveProfile).setOnClickListener {
            val id = Session.selectedUserId
            if (id > 0 && id != 999) showRemoveDialog(id)
            else toast("Select a profile first")
        }

        if (Session.connected) refresh() else status.text = "Not connected — connect first"
    }

    private fun refresh() {
        if (!Session.connected) { status.text = "Not connected"; return }
        lifecycleScope.launch {
            status.text = "Loading profiles…"
            val out = withContext(Dispatchers.IO) { Session.profiles.getUsers() }
            AppLogger.log("PROFILE", "refresh raw: ${out.take(400)}")
            updateUserList(out)
        }
    }

    private fun updateUserList(out: String) {
        users = Session.profiles.parseUsers(out).filter { it.id != 0 && it.id != 999 }
        adapter.clear()
        if (users.isEmpty()) {
            adapter.add("No profiles — tap + Create")
            status.text = "No profiles (besides owner / dual-apps)"
        } else {
            users.forEach { u ->
                adapter.add("💼 ${u.name} (ID: ${u.id})${if (u.isRunning) " • running" else ""}")
            }
            status.text = "${users.size} profile(s) — selected: ${if (Session.selectedUserId > 0) Session.selectedUserId else "none"}"
        }
    }

    /** Triggers the system managed-profile provisioning — the no-root DPC way.
     *  The system creates the work profile, makes us profile owner, and our
     *  WorkAdminReceiver enables it + Play Store so apps show in the launcher. */
    private fun provisionDpc() {
        val pm = requireContext().packageManager
        val supported = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MANAGED_USERS)
        AppLogger.log("DPC", "managed_users feature = $supported")

        val admin = ComponentName(requireContext(), WorkAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin)
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)
        }
        val resolvable = intent.resolveActivity(pm) != null
        AppLogger.log("DPC", "provisioning intent resolvable = $resolvable")

        if (!supported) {
            toast("This ROM reports no work-profile support (managed_users = false) — see Logs")
        }
        if (resolvable) {
            provision.launch(intent)
        } else {
            toast("Work-profile provisioning isn't available on this device")
        }
    }

    private fun showCreateDialog() {
        if (!Session.connected) { toast("Connect first"); return }
        val ctx = requireContext()

        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(56, 8, 56, 0)
        }

        fun label(text: String) = android.widget.TextView(ctx).apply {
            this.text = text
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(android.graphics.Color.parseColor("#00b4ff"))
            setPadding(2, 16, 0, 4)
        }

        val nameInput = hackerInput("Profile name  (e.g. Work)", "Work")
        val idInput = hackerInput("Desired ID  (e.g. 20)", "20", numeric = true)

        layout.addView(label("PROFILE NAME"))
        layout.addView(nameInput)
        layout.addView(label("DESIRED ID  —  Android may assign a different one"))
        layout.addView(idInput)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Create Work Profile")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { "Work" }
                val desiredId = idInput.text.toString().trim().toIntOrNull() ?: 20
                create(name, desiredId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hackerInput(hint: String, prefill: String = "", numeric: Boolean = false) =
        EditText(requireContext()).apply {
            this.hint = hint
            if (prefill.isNotEmpty()) setText(prefill)
            inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER
                        else android.text.InputType.TYPE_CLASS_TEXT
            setHintTextColor(android.graphics.Color.parseColor("#2a4060"))
            setTextColor(android.graphics.Color.parseColor("#c8d8e8"))
            setBackgroundResource(R.drawable.bg_input)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 14f
            setPadding(28, 20, 28, 20)
        }

    private fun create(name: String, desiredId: Int = 20) {
        lifecycleScope.launch {
            status.text = "Creating & provisioning: $name…"
            try {
                // Fetch users in the same IO block — reuses the live ADB connection
                // rather than calling refresh() which re-checks Session.connected and
                // can see a briefly-dropped state after the long provisioning chain.
                val (res, usersOut) = withContext(Dispatchers.IO) {
                    val r = Session.profiles.createAndProvision(name)
                    val u = if (r.userId != null) Session.profiles.getUsers() else ""
                    r to u
                }
                if (!isAdded) return@launch
                AppLogger.log("APP", res.log)
                val id = res.userId
                if (id != null) {
                    ProfileStore.add(requireContext(), id)
                    Session.selectedUserId = id
                    AppLogger.log("PROFILE", "users after create: ${usersOut.take(400)}")
                    updateUserList(usersOut)
                    val idNote = if (id != desiredId)
                        "\n\nYou wanted ID $desiredId — Android assigned ID $id.\nUpdate your scripts to use: $id"
                    else ""
                    val msg = if (res.provisioned)
                        "Profile '$name' created successfully (ID: $id)"
                    else
                        "Profile '$name' created (ID: $id) — basic mode"
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Profile Created")
                        .setMessage(msg + idNote)
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    status.text = "Failed — see Logs"
                    toast("Failed to create profile — see Logs")
                }
            } catch (e: Exception) {
                AppLogger.log("PROFILE", "create() exception: $e")
                if (!isAdded) return@launch
                status.text = "Error — see Logs"
                toast("Creation failed — see Logs")
            }
        }
    }

    private fun showRemoveDialog(id: Int) {
        val user = users.find { it.id == id } ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove Profile")
            .setMessage("Delete '${user.name}' (ID: $id)? Apps inside it are removed too.")
            .setPositiveButton("Delete") { _, _ -> remove(id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun remove(id: Int) {
        if (id == 0 || id == 999) { toast("Refusing to remove owner / dual-apps space"); return }
        lifecycleScope.launch {
            status.text = "Removing user $id…"
            withContext(Dispatchers.IO) { Session.profiles.removeUser(id) }
            ProfileStore.remove(requireContext(), id)
            if (Session.selectedUserId == id) Session.selectedUserId = -1
            refresh()
        }
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()
}
