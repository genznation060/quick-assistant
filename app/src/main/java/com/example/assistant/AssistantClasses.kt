package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

class AssistantService : AccessibilityService() {

    private val TAG = "QuickAssistant"
    private lateinit var windowManager: WindowManager
    private var chipBar: LinearLayout? = null
    private var overlayView: View? = null

    // Multi-select state
    private val selectedNodes = CopyOnWriteArrayList<AccessibilityNodeInfo>()
    private val selectedTexts = CopyOnWriteArrayList<String>()

    private val handler = Handler(Looper.getMainLooper())

    // Chips we never want to show
    private val junkKeywords = setOf(
        "google", "ai", "mode", "assistant", "bard", "gemini",
        "chatgpt", "copilot", "search with", "ask"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            notificationTimeout = 50
        }
        serviceInfo = info
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val source = event.source ?: return
                handleNodeTap(source)
                source.recycle()
            }
        }
    }

    private fun handleNodeTap(node: AccessibilityNodeInfo) {
        val text = extractCleanText(node)
        if (text.isBlank() || text.length > 120) return

        // Toggle selection
        val alreadySelected = selectedNodes.any { it == node || it.text == node.text }
        if (alreadySelected) {
            // Deselect
            val idx = selectedNodes.indexOfFirst { it.text == node.text }
            if (idx >= 0) {
                selectedNodes.removeAt(idx)
                selectedTexts.removeAt(idx)
            }
        } else {
            // Select
            selectedNodes.add(AccessibilityNodeInfo.obtain(node))
            selectedTexts.add(text)
        }

        updateChipBar()
    }

    private fun extractCleanText(node: AccessibilityNodeInfo): String {
        val raw = node.text?.toString()
            ?: node.contentDescription?.toString()
            ?: return ""

        // Avoid single-character junk and common UI labels
        if (raw.length <= 1) return ""
        if (raw.matches(Regex("^[\\d\\W]+$"))) return ""

        return raw.trim()
            .replace(Regex("\\s+"), " ")
            .take(200)
    }

    private fun getCombinedSelection(): String {
        return selectedTexts.joinToString(" ").trim()
    }

    private fun updateChipBar() {
        handler.post {
            removeChipBar()

            val combined = getCombinedSelection()
            if (combined.isBlank()) return@post

            val density = resources.displayMetrics.density
            val barHeight = (56 * density).toInt()

            // Root horizontal scroll so many chips fit
            val scroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(Color.TRANSPARENT)
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(
                    (12 * density).toInt(),
                    (8 * density).toInt(),
                    (12 * density).toInt(),
                    (8 * density).toInt()
                )
                setBackgroundColor(Color.parseColor("#F0F0F0"))
            }

            // Always show these primary actions
            addChip(container, "Search", Color.parseColor("#1A73E8")) {
                openGoogleSearch(combined)
            }
            addChip(container, "Explain", Color.parseColor("#0F9D58")) {
                openGoogleSearch("explain: $combined")
            }
            addChip(container, "Copy", Color.parseColor("#5F6368")) {
                copyToClipboard(combined)
            }

            // Optional extra useful chips (filtered)
            val extras = listOf("Translate", "Define", "Wiki")
            extras.forEach { label ->
                if (!isJunk(label)) {
                    addChip(container, label, Color.parseColor("#EA4335")) {
                        when (label) {
                            "Translate" -> openGoogleSearch("translate: $combined")
                            "Define" -> openGoogleSearch("define: $combined")
                            "Wiki" -> openGoogleSearch("$combined site:wikipedia.org")
                        }
                    }
                }
            }

            scroll.addView(container)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                // Critical: lift the bar above the system navigation bar
                y = getNavigationBarHeight() + (8 * density).toInt()
            }

            try {
                windowManager.addView(scroll, params)
                chipBar = container
                overlayView = scroll
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add chip bar", e)
            }
        }
    }

    private fun addChip(parent: LinearLayout, label: String, color: Int, onClick: () -> Unit) {
        val density = resources.displayMetrics.density

        val chip = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(
                (16 * density).toInt(),
                (10 * density).toInt(),
                (16 * density).toInt(),
                (10 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(color)
            }
            setOnClickListener {
                onClick()
                // Keep selection after action (user can clear by tapping again)
            }
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = (8 * density).toInt()
        }
        parent.addView(chip, lp)
    }

    private fun isJunk(label: String): Boolean {
        val lower = label.lowercase()
        return junkKeywords.any { lower.contains(it) }
    }

    private fun getNavigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun openGoogleSearch(query: String) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open search", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("selected", text))
        Toast.makeText(this, "Copied: \( {text.take(40)} \){if (text.length > 40) "…" else ""}", Toast.LENGTH_SHORT).show()
    }

    private fun removeChipBar() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        chipBar = null
    }

    fun clearSelection() {
        selectedNodes.forEach { it.recycle() }
        selectedNodes.clear()
        selectedTexts.clear()
        removeChipBar()
    }

    override fun onInterrupt() {
        clearSelection()
    }

    override fun onDestroy() {
        clearSelection()
        super.onDestroy()
    }
}

// Simple helper for any Activity that wants to force-clear selection
object AssistantHelper {
    fun clear(service: AssistantService?) {
        service?.clearSelection()
    }
}
