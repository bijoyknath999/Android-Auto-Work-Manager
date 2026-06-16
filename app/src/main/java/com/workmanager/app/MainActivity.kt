package com.workmanager.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/** Hosts the bottom-nav pages: Connect, Profiles, Apps, Logs. */
class MainActivity : AppCompatActivity() {

    private lateinit var connectionDot: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionDot = findViewById(R.id.headerConnectionDot)

        Session.init(this)
        AdbNotificationManager.createChannel(this)
        requestNotificationPermission()

        // Auto-connect on app start if WiFi debugging is enabled.
        if (!Session.connected && AdbAutoConnect.canAutoConnect(this)) {
            Thread {
                val ok = AdbAutoConnect.autoConnect(this, Session.adb)
                runOnUiThread {
                    if (ok) AdbNotificationManager.showConnected(this, "Auto-connected on start")
                }
            }.start()
        }

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            showFragment(
                when (item.itemId) {
                    R.id.nav_profiles -> ProfilesFragment()
                    R.id.nav_apps -> AppsFragment()
                    R.id.nav_script -> ScriptFragment()
                    R.id.nav_logs -> LogsFragment()
                    else -> ConnectFragment()
                }
            )
            true
        }
        if (savedInstanceState == null) nav.selectedItemId = R.id.nav_connect
    }

    private fun showFragment(f: Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.container, f).commit()
    }

    override fun onStart() {
        super.onStart()
        if (!Session.connected) AdbNotificationManager.showDisconnected(this)
        updateConnectionDot()
    }

    fun updateConnectionDot() {
        if (Session.connected) {
            connectionDot.text = "● ONLINE"
            connectionDot.setTextColor(0xFF00e676.toInt())
            connectionDot.setBackgroundColor(0xFF0a2010.toInt())
        } else {
            connectionDot.text = "● OFFLINE"
            connectionDot.setTextColor(0xFFff8c00.toInt())
            connectionDot.setBackgroundColor(0xFF1a1000.toInt())
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        }
    }
}
