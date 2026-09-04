package com.example.assistant

import android.app.Activity
import android.app.SearchManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class MyAssistantService : VoiceInteractionService()

class MyAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return CustomSession(this)
    }
}

class MyRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

class ClipboardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        if (text.isNotBlank()) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Quick Assistant", text))
            Toast.makeText(this, "Copied: ${text.take(48)}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        const val EXTRA_TEXT = "text"
        fun copy(context: Context, text: String) {
            context.startActivity(
                Intent(context, ClipboardActivity::class.java).apply {
                    putExtra(EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        }
    }
}

data class ScreenTextItem(val text: String, val rect: Rect)

class CustomSession(context: Context) : VoiceInteractionSession(context) {

    private val textItems = ArrayList<ScreenTextItem>()
    private val selected = LinkedHashSet<ScreenTextItem>()
    private var overlayReady = false
    private var readyAt = 0L
    private var pendingStructure: AssistStructure? = null
    private var screenshot: Bitmap? = null
    private var navPad = 0
    private var statusPad = 0

    private lateinit var root: FrameLayout
    private lateinit var highlightView: HighlightView
    private lateinit var chipsRow: LinearLayout
    private lateinit var bottom: LinearLayout

    private val chrome = setOf(
        "google", "search", "ai", "mode", "all", "images", "videos", "shopping",
        "forums", "news", "maps", "more", "show more", "copy", "share", "ok",
        "cancel", "settings", "home", "back", "lens"
    )

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            computeSystemBars()
            buildOverlay()
            overlayReady = true
            readyAt = SystemClock.uptimeMillis() + 400
            pendingStructure?.let {
                applyStructure(it)
                pendingStructure = null
            }
        } catch (_: Throwable) {
            Toast.makeText(context, "Assistant UI error", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onHandleAssist(state: AssistState) {
        if (state.index == 0) ingest(state.assistStructure)
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        ingest(structure)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot == null || screenshot.isRecycled) return
        this.screenshot = screenshot.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun ingest(structure: AssistStructure?) {
        if (!overlayReady) {
            pendingStructure = structure
            return
        }
        applyStructure(structure)
    }

    private fun dimen(name: String, fallback: Int): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else fallback
    }

    private fun computeSystemBars() {
        val dp = context.resources.displayMetrics.density
        navPad = dimen("navigation_bar_height", (48 * dp).toInt())
        statusPad = dimen("status_bar_height", (24 * dp).toInt())
        try {
            val insets = window?.window?.decorView?.rootWindowInsets
            if (insets != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    if (bars.bottom > 0) navPad = bars.bottom
                    if (bars.top > 0) statusPad = bars.top
                } else {
                    @Suppress("DEPRECATION")
                    if (insets.stableInsetBottom > 0) navPad = insets.stableInsetBottom
                    @Suppress("DEPRECATION")
                    if (insets.stableInsetTop > 0) statusPad = insets.stableInsetTop
                }
            }
        } catch (_: Exception) {
        }
        navPad = maxOf(navPad, (28 * dp(context)).toInt())
    }

    private fun dp(ctx: Context) = ctx.resources.displayMetrics.density

    private fun applyStructure(structure: AssistStructure?) {
        textItems.clear()
        selected.clear()
        structure?.let { struct ->
            for (i in 0 until struct.windowNodeCount) {
                val w = struct.getWindowNodeAt(i)
                traverse(w.rootViewNode, w.left, w.top)
            }
        }
        refreshUi()
    }

    private fun traverse(node: AssistStructure.ViewNode?, parentX: Int, parentY: Int) {
        if (node == null || node.visibility != View.VISIBLE) return
        val x = parentX + node.left - node.scrollX
        val y = parentY + node.top - node.scrollY
        val raw = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val text = raw.ifBlank { desc }
        val screenH = context.resources.displayMetrics.heightPixels
        if (text.isNotBlank() && node.width > 12 && node.height > 12) {
            val rect = Rect(x, y, x + node.width, y + node.height)
            val tooLow = rect.bottom > screenH - navPad - 8
            val tooHigh = rect.bottom < statusPad
            if (!tooLow && !tooHigh) addTextPieces(text, rect)
        }
        for (i in 0 until node.childCount) traverse(node.getChildAt(i), x, y)
    }

    private fun addTextPieces(text: String, rect: Rect) {
        val singleLine = rect.height() <= (40 * dp(context))
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (!singleLine) {
            if (lines.size > 1) {
                val h = (rect.height() / lines.size).coerceAtLeast(20)
                lines.forEachIndexed { i, line ->
                    val top = rect.top + i * h
                    addTextPieces(line, Rect(rect.left, top, rect.right, top + h))
                }
            } else {
                textItems.add(ScreenTextItem(text, Rect(rect)))
            }
            return
        }
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 1) {
            textItems.add(ScreenTextItem(text, Rect(rect)))
            return
        }
        val total = words.sumOf { it.length }.coerceAtLeast(1)
        var offset = 0
        for (w in words) {
            val wpx = (rect.width() * (w.length.toFloat() / total)).toInt().coerceAtLeast(28)
            val left = rect.left + offset
            textItems.add(ScreenTextItem(w, Rect(left, rect.top, left + wpx, rect.bottom)))
            offset += wpx
        }
    }

    private fun buildOverlay() {
        val d = dp(context)

        root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#4D000000"))
            setOnApplyWindowInsetsListener { _, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    if (bars.bottom > 0) navPad = bars.bottom
                    if (bars.top > 0) statusPad = bars.top
                }
                applyBottomPadding()
                insets
            }
        }

        highlightView = HighlightView(context) { selected.toList() }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener { _, event ->
                if (event.action != MotionEvent.ACTION_DOWN) return@setOnTouchListener true
                if (SystemClock.uptimeMillis() < readyAt) return@setOnTouchListener true
                onTap(event.x.toInt(), event.y.toInt())
                true
            }
        }
        root.addView(highlightView)

        val close = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            val box = (40 * d).toInt()
            width = box
            height = box
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#99000000"))
                cornerRadius = box / 2f
            }
            setOnClickListener { finish() }
        }
        root.addView(
            close,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = statusPad + (8 * d).toInt()
                rightMargin = (12 * d).toInt()
            }
        )

        chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }

        // ===== BUTTONS =====
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (10 * d).toInt(), 0, 0)

            addView(roundAction("Copy", Color.parseColor("#E8F0FE"), Color.parseColor("#1A73E8")) {
                copySelected()
            })
            addView(gap(d))
            addView(roundAction("Lens", Color.parseColor("#1A73E8"), Color.WHITE) {
                openLensInFront()
            })
            addView(gap(d))
            addView(roundAction("Search", Color.parseColor("#E8F0FE"), Color.parseColor("#1A73E8")) {
                searchGoogle(selectedQuery())
            })
            addView(gap(d))
            addView(roundAction("Explain", Color.parseColor("#E8F0FE"), Color.parseColor("#1A73E8")) {
                explainSelected()
            })
            addView(gap(d))
            addView(roundAction("Summarize", Color.parseColor("#E8F0FE"), Color.parseColor("#1A73E8")) {
                summarizeSelected()
            })
            addView(gap(d))
            addView(roundAction("Search YT", Color.parseColor("#E8F0FE"), Color.parseColor("#1A73E8")) {
                searchYouTube()
            })
            addView(gap(d))
            addView(roundAction("Define", Color.parseColor("#E8F0FE"), Color.parseColor("#1A73E8")) {
                defineSelected()
            })
            addView(gap(d))
            addView(roundAction("Fact Check", Color.parseColor("#E8F0FE"), Color.parseColor("#1A73E8")) {
                factCheckSelected()
            })
            addView(gap(d))
            addView(roundAction("Translate", Color.parseColor("#E8F0FE"), Color.parseColor("#1A73E8")) {
                translateSelected()
            })
            addView(gap(d))
            addView(roundAction("Share", Color.parseColor("#E8F0FE"), Color.parseColor("#1A73E8")) {
                shareSelected()
            })
        }

        val actionsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        }

        bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 16f
            addView(TextView(context).apply {
                text = "Tap words → Copy / Lens / Search / Explain / Summarize / Search YT..."
                setTextColor(Color.parseColor("#5F6368"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
            })
            addView(
                chipsScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = (12 * d).toInt()
                    rightMargin = (12 * d).toInt()
                }
            )
            addView(actionsScroll)
        }
        applyBottomPadding()
        root.addView(
            bottom,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        )

        setContentView(root)
        root.requestApplyInsets()
    }

    private fun applyBottomPadding() {
        if (!::bottom.isInitialized) return
        val d = dp(context)
        bottom.setPadding(
            (12 * d).toInt(),
            (4 * d).toInt(),
            (12 * d).toInt(),
            navPad + (12 * d).toInt()
        )
    }

    private fun gap(d: Float) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams((8 * d).toInt(), 1)
    }

    private fun roundAction(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView {
        val d = dp(context)
        return TextView(context).apply {
            text = label
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            minHeight = (44 * d).toInt()
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = 40f
            }
            setOnClickListener { onClick() }
        }
    }

    private fun onTap(x: Int, y: Int) {
        val screenH = root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        if (y > screenH - bottom.height) return
        val hit = textItems
            .filter { it.rect.contains(x, y) }
            .minByOrNull { it.rect.width() * it.rect.height().coerceAtLeast(1) }
        if (hit != null) toggle(hit)
    }

    private fun toggle(item: ScreenTextItem) {
        val existing = selected.find { it.text == item.text && it.rect == item.rect }
        if (existing != null) selected.remove(existing) else selected.add(item)
        refreshUi()
    }

    private fun selectFromChip(item: ScreenTextItem) {
        if (selected.any { it.text == item.text }) selected.removeAll { it.text == item.text }
        else selected.add(item)
        refreshUi()
    }

    private fun selectedQuery() = selected.joinToString(" ") { it.text }.trim()

    private fun isChrome(text: String) = text.lowercase() in chrome

    private fun refreshUi() {
        if (::highlightView.isInitialized) highlightView.invalidate()
        refreshChips()
    }

    private fun refreshChips() {
        if (!::chipsRow.isInitialized) return
        chipsRow.removeAllViews()
        val d = dp(context)
        val unique = LinkedHashSet<String>()
        val chips = ArrayList<ScreenTextItem>()
        for (item in textItems) {
            val t = item.text.trim()
            if (t.length < 2 || t.length > 42) continue
            if (isChrome(t)) continue
            if (!t.any { it.isLetter() }) continue
            if (unique.add(t)) chips.add(item)
            if (chips.size >= 16) break
        }
        if (chips.isEmpty()) {
            chipsRow.addView(TextView(context).apply {
                text = "Tap text on the screen"
                setTextColor(Color.parseColor("#5F6368"))
                setPadding((8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt())
            })
            return
        }
        for (item in chips) {
            val chosen = selected.any { it.text == item.text }
            val chip = TextView(context).apply {
                text = item.text
                minHeight = (40 * d).toInt()
                gravity = Gravity.CENTER
                setTextColor(if (chosen) Color.WHITE else Color.parseColor("#202124"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
                background = GradientDrawable().apply {
                    setColor(if (chosen) Color.parseColor("#1A73E8") else Color.parseColor("#E8F0FE"))
                    cornerRadius = 40f
                }
                setOnClickListener { selectFromChip(item) }
            }
            chipsRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = (8 * d).toInt() }
            )
        }
    }

    // ==================== ACTIONS ====================

    private fun copySelected() {
        val q = selectedQuery()
        if (q.isBlank()) {
            Toast.makeText(context, "Select text first", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Quick Assistant", q))
        } catch (_: Exception) {
        }
        ClipboardActivity.copy(context, q)
    }

    private fun openLensInFront() {
        val bmp = screenshot
        val launched = if (bmp != null && !bmp.isRecycled) shareToLens(bmp) else openBareLens()
        if (!launched) {
            Toast.makeText(context, "Could not open Google Lens", Toast.LENGTH_SHORT).show()
            return
        }
        finish()
    }

    private fun shareToLens(bmp: Bitmap): Boolean {
        return try {
            val file = File(context.cacheDir, "qa_lens.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
            val uri = FileProvider.getUriForFile(context, "com.example.assistant.files", file)
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(flags)
                setPackage("com.google.android.googlequicksearchbox")
            }
            try {
                context.startActivity(send)
            } catch (_: Exception) {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "Open with Lens"
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
            true
        } catch (_: Exception) {
            openBareLens()
        }
    }

    private fun openBareLens(): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("google://lens")).apply {
                setPackage("com.google.android.googlequicksearchbox")
                addFlags(flags)
            },
            Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com")).addFlags(flags)
        )
        for (intent in tries) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun searchGoogle(query: String) {
        if (query.isBlank()) {
            Toast.makeText(context, "Select text first", Toast.LENGTH_SHORT).show()
            return
        }
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        try {
            context.startActivity(
                Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, query)
                    addFlags(flags)
                }
            )
        } catch (_: Exception) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
                    .addFlags(flags)
            )
        }
        finish()
    }

    // ========== NEW ACTIONS ==========

    private fun explainSelected() {
        val text = selectedQuery()
        if (text.isBlank()) {
            Toast.makeText(context, "Select text first", Toast.LENGTH_SHORT).show()
            return
        }
        searchGoogle("explain $text")
    }

    private fun summarizeSelected() {
        val text = selectedQuery()
        if (text.isBlank()) {
            Toast.makeText(context, "Select text first", Toast.LENGTH_SHORT).show()
            return
        }
        searchGoogle("summarize $text")
    }

    private fun searchYouTube() {
        val text = selectedQuery()
        if (text.isBlank()) {
            Toast.makeText(context, "Select text first", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(text)}")
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun defineSelected() {
        val text = selectedQuery()
        if (text.isBlank()) {
            Toast.makeText(context, "Select text first", Toast.LENGTH_SHORT).show()
            return
        }
        searchGoogle("define $text")
    }

    private fun factCheckSelected() {
        val text = selectedQuery()
        if (text.isBlank()) {
            Toast.makeText(context, "Select text first", Toast.LENGTH_SHORT).show()
            return
        }
        searchGoogle("fact check $text")
    }

    private fun translateSelected() {
        val text = selectedQuery()
        if (text.isBlank()) {
            Toast.makeText(context, "Select text first", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse(
            "https://translate.google.com/?sl=auto&tl=en&text=${Uri.encode(text)}&op=translate"
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun shareSelected() {
        val text = selectedQuery()
        if (text.isBlank()) {
            Toast.makeText(context, "Select text first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share text")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    class HighlightView(
        context: Context,
        private val selectedProvider: () -> List<ScreenTextItem>
    ) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#802196F3")
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A73E8")
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (item in selectedProvider()) {
                val r = item.rect
                canvas.drawRoundRect(
                    r.left - 6f, r.top - 4f, r.right + 6f, r.bottom + 4f,
                    14f, 14f, fill
                )
                canvas.drawRoundRect(
                    r.left - 6f, r.top - 4f, r.right + 6f, r.bottom + 4f,
                    14f, 14f, stroke
                )
            }
        }
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val title = TextView(this).apply {
            text = "Quick Assistant"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.parseColor("#202124"))
            gravity = Gravity.CENTER
        }
        val body = TextView(this).apply {
            text = "1. Set as default assistant\n2. Long-press Home\n3. Tap words\n4. Use the action buttons"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.parseColor("#3C4043"))
            setPadding(0, (16 * d).toInt(), 0, (24 * d).toInt())
        }
        val button = Button(this).apply {
            text = "Set as default assistant"
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * d).toInt(), (48 * d).toInt(), (28 * d).toInt(), (28 * d).toInt())
            setBackgroundColor(Color.WHITE)
            addView(title)
            addView(body)
            addView(button)
        }
        setContentView(layout)
    }
}
