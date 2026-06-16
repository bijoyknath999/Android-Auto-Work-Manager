package com.workmanager.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectFragment : Fragment(R.layout.fragment_connect) {

    private lateinit var statusText: TextView
    private var autoTried = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        statusText = view.findViewById(R.id.statusText)
        val codeInput = view.findViewById<EditText>(R.id.pairCodeInput)
        val portInput = view.findViewById<EditText>(R.id.pairPortInput)

        view.findViewById<Button>(R.id.btnPair).setOnClickListener {
            val code = codeInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull()
            if (code.length != 6 || port == null) {
                toast("Enter 6-digit code and pairing port"); return@setOnClickListener
            }
            pairAndConnect(code, port)
        }
        view.findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val port = portInput.text.toString().trim().toIntOrNull()
            if (port == null) {
                toast("Enter the connect port (not pairing port)"); return@setOnClickListener
            }
            manualConnect(port)
        }
        view.findViewById<Button>(R.id.btnFloatingPair).setOnClickListener { startAutoPair() }
        view.findViewById<Button>(R.id.btnBootHelp).setOnClickListener { showBootHelp() }

        val usbPortInput = view.findViewById<EditText>(R.id.usbPortInput)
        view.findViewById<Button>(R.id.btnUsbConnect).setOnClickListener {
            val port = usbPortInput.text.toString().trim().toIntOrNull() ?: 5555
            usbConnect(port)
        }

        statusText.setOnClickListener { showBootHelp() }

        if (Session.connected) setStatus("Connected ✓", "#66bb6a")
    }

    override fun onResume() {
        super.onResume()
        if (!Session.connected && !autoTried) {
            val canWifi = AdbAutoConnect.canAutoConnect(requireContext())
            val canUsb = AdbAutoConnect.canAutoConnectUsb(requireContext())
            if (canWifi || canUsb) {
                autoTried = true
                setStatus("Auto-connecting…", "#ff9800")
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        (canWifi && AdbAutoConnect.autoConnect(requireContext(), Session.adb)) ||
                        (canUsb && AdbAutoConnect.autoConnectUsb(requireContext(), Session.adb))
                    }
                    if (ok) onConnected("Auto-connected") else setStatus("Tap Pair to connect", "#ff9800")
                }
            }
        }
    }

    private fun pairAndConnect(code: String, port: Int) {
        setStatus("Pairing…", "#ff9800")
        lifecycleScope.launch {
            val ip = AdbAutoConnect.wifiIp(requireContext())
            val paired = withContext(Dispatchers.IO) { Session.adb.pair(ip, port, code) }
            if (!paired) {
                setStatus("Pairing failed — wrong code or port?", "#ef5350")
                AdbNotificationManager.showDisconnected(requireContext())
                return@launch
            }
            setStatus("Paired ✓ — connecting…", "#ff9800")
            val ok = withContext(Dispatchers.IO) { AdbAutoConnect.autoConnect(requireContext(), Session.adb) }
            if (ok) onConnected("Connected after pairing")
            else setStatus("Paired, but couldn't find the port. Enter the connect port and tap Connect.", "#ef5350")
        }
    }

    private fun usbConnect(port: Int) {
        setStatus("Connecting via USB to 127.0.0.1:$port…", "#ff9800")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { Session.adb.connect("127.0.0.1", port) }
            if (ok) {
                AdbAutoConnect.saveUsbPort(requireContext(), port)
                onConnected("Connected via USB :$port")
            } else {
                setStatus("USB failed — run on PC: adb tcpip $port", "#ef5350")
            }
        }
    }

    private fun manualConnect(port: Int) {
        setStatus("Connecting…", "#ff9800")
        lifecycleScope.launch {
            val ip = AdbAutoConnect.wifiIp(requireContext())
            val ok = withContext(Dispatchers.IO) { Session.adb.connect(ip, port) }
            if (ok) onConnected("Connected to $ip:$port")
            else setStatus("Connection failed — wrong port?", "#ef5350")
        }
    }

    private fun onConnected(msg: String) {
        setStatus("$msg ✓", "#66bb6a")
        AdbNotificationManager.showConnected(requireContext(), msg)
        (activity as? MainActivity)?.updateConnectionDot()
        ensureSecureSettings()
    }

    private fun ensureSecureSettings() {
        if (AdbAutoConnect.hasSecureSettingsPermission(requireContext())) return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { AdbAutoConnect.selfGrant(Session.adb) }
            if (!ok && !AdbAutoConnect.hasSecureSettingsPermission(requireContext())) {
                toast("Auto-reconnect after reboot needs 'USB debugging (Security settings)' (MIUI/HyperOS).")
            }
        }
    }

    // ── Auto pair (accessibility) ──────────────────────────────────────────────

    private fun startAutoPair() {
        if (!isAutoPairEnabled()) {
            toast("Enable 'Work Manager' under Accessibility, then tap Auto Pair again")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        PairAccessibilityService.arm()
        toast("Open Wireless Debugging → Pair device with code. It pairs automatically.")
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun isAutoPairEnabled(): Boolean {
        val expected = "${requireContext().packageName}/${PairAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            requireContext().contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun showBootHelp() {
        val perm = if (AdbAutoConnect.hasSecureSettingsPermission(requireContext())) "GRANTED ✓" else "NOT granted ✗"
        val wifi = if (AdbAutoConnect.isWifiDebugEnabled(requireContext())) "ON ✓" else "OFF ✗"
        val msg = """
            Current state
            • WRITE_SECURE_SETTINGS: $perm
            • Wireless debugging: $wifi

            To auto-connect after a reboot (Xiaomi/HyperOS):
            1. Settings → Apps → Work Manager → enable Autostart.
            2. Developer options → 'USB debugging (Security settings)': ON, then connect once.
            3. Keep 'Wireless debugging' ON.
        """.trimIndent()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Auto-connect after reboot")
            .setMessage(msg)
            .setPositiveButton("Developer options") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_: Exception) {}
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setStatus(text: String, color: String) {
        statusText.text = text
        statusText.setTextColor(android.graphics.Color.parseColor(color))
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()
}
