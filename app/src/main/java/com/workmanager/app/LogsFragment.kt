package com.workmanager.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment

class LogsFragment : Fragment(R.layout.fragment_logs) {

    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var searchBox: EditText
    private lateinit var searchBar: View
    private lateinit var searchInfo: TextView

    private val listener: (String) -> Unit = { text ->
        view?.post {
            logText.text = text
            logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        logText = view.findViewById(R.id.logText)
        logScroll = view.findViewById(R.id.logScroll)
        searchBox = view.findViewById(R.id.searchBox)
        searchBar = view.findViewById(R.id.searchBar)
        searchInfo = view.findViewById(R.id.searchInfo)

        view.findViewById<Button>(R.id.btnClearLog).setOnClickListener { AppLogger.clear() }

        // Copy All
        view.findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            copyToClipboard(logText.text.toString())
        }

        // Search toggle
        view.findViewById<Button>(R.id.btnSearch).setOnClickListener {
            searchBar.visibility = if (searchBar.isVisible) View.GONE else View.VISIBLE
            searchInfo.visibility = View.GONE
            if (searchBar.isVisible) searchBox.requestFocus()
        }

        // Search + Copy matching
        view.findViewById<Button>(R.id.btnCopyMatching).setOnClickListener {
            val query = searchBox.text.toString().trim()
            if (query.isEmpty()) {
                copyToClipboard(logText.text.toString())
                return@setOnClickListener
            }
            val allLines = logText.text.toString().lines()
            val matching = allLines.filter { it.contains(query, ignoreCase = true) }
            if (matching.isEmpty()) {
                searchInfo.text = "No matches for \"$query\""
                searchInfo.visibility = View.VISIBLE
            } else {
                searchInfo.text = "${matching.size} lines matched"
                searchInfo.visibility = View.VISIBLE
                copyToClipboard(matching.joinToString("\n"))
            }
        }

        logText.text = AppLogger.all()
        AppLogger.addListener(listener)

        // Long-press to copy a line
        // Text is selectable directly — long press starts native selection
        logText.setTextIsSelectable(true)
    }

    private fun copyToClipboard(text: String) {
        val clip = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("logs", text))
        Toast.makeText(requireContext(), "Copied ${text.length} chars", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        AppLogger.removeListener(listener)
    }
}
