package com.example.assistant

import android.app.SearchManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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

data class ScreenTextItem(val text: String, val rect: Rect)

class CustomSession(context: Context) : VoiceInteractionSession(context) {

    private val textItems = ArrayList<ScreenTextItem>()
    private val selected = LinkedHashSet<ScreenTextItem>()
    private var overlayReady = false
    private var readyAt = 0L
    private var pendingStructure: AssistStructure? = null
    private var navPad = 0
    private var statusPad = 0

    private lateinit var root: FrameLayout
    private lateinit var highlightView: HighlightView
    private lateinit var pill: LinearLayout
    private lateinit var chipsRow: LinearLayout
    private lateinit var bottom: LinearLayout

    private val chrome = setOf(
        "google", "search", "ai", "mode", "all", "images", "videos", "shopping",
        "forums", "news", "maps", "more", "show more", "copy", "share", "ok",
        "cancel", "settings", "home", "back"
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
        } catch (t: Throwable) {
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
        navPad = maxOf(navPad, (28 * context.resources.displayMetrics.density).toInt())
    }

    private fun applyStructure(structure: AssistStructure?) {
        textItems.clear()
        selected.clear()
        structure?.let { struct ->
            for (i in 0 until struct.windowNodeCount) {
                val w = struct.getWindowNodeAt(i)
                traverse(w.rootViewNode, w.left, w.top)
            }
        }
        refreshChips()
        highlightView.invalidate()
        hidePill()
    }

    private fun traverse(node: AssistStructure.ViewNode?, parentX: Int, parentY: Int) {
        if (node == null) return
        if (node.visibility != View.VISIBLE) return

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

        for (i in 0 until node.childCount) {
            traverse(node.getChildAt(i), x, y)
        }
    }

    private fun addTextPieces(text: String, rect: Rect) {
        val dp = context.resources.displayMetrics.density
        val singleLine = rect.height() <= (40 * dp)
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        if (!singleLine) {
            if (lines.size > 1) {
                val h = (rect.height() / lines.size).coerceAtLeast(20)
                lines.forEachIndexed { i, line ->
                    val top = rect.top + i * h
                    addTextPieces(line, Rect(rect.left, top, rect.right, (top + h)))
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
        val dp = context.resources.displayMetrics.density

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
            val box = (40 * dp).toInt()
            width = box
            height = box
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#99000000"))
                cornerRadius = box / 2f
            }
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = statusPad + (8 * dp).toInt()
            rightMargin = (12 * dp).toInt()
        })

        chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (10 * dp).toInt(), 0, 0)
            addView(roundAction("Search", Color.parseColor("#1A73E8"), Color.WHITE) {
                searchGoogle(selectedQuery(), webOnly = false)
            })
            addView(space(dp))
            addView(roundAction("Explain", Color.parseColor("#E8F0FE"), Color.parseColor("#1A73E8")) {
                val q = selectedQuery()
                if (q.isNotBlank()) searchGoogle("what is $q", webOnly = false)
            })
        }

        bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 16f
            addView(TextView(context).apply {
                text = "Tap words to multi-select, then Search"
                setTextColor(Color.parseColor("#5F6368"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
            })
            addView(chipsScroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (12 * dp).toInt()
                rightMargin = (12 * dp).toInt()
            })
            addView(actions)
        }
        applyBottomPadding()
        root.addView(bottom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            elevation = 24f
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 80f
            }
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            addView(circleIcon("Copy") {
                val q = selectedQuery()
                if (q.isBlank()) return@circleIcon
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Selected Text", q))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            })
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(2, (36 * dp).toInt()).apply {
                    leftMargin = (4 * dp).toInt()
                    rightMargin = (4 * dp).toInt()
                    gravity = Gravity.CENTER_VERTICAL
                }
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            })
            addView(circleIcon("Google") {
                searchGoogle(selectedQuery(), webOnly = false)
            })
        }
        root.addView(pill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        root.requestApplyInsets()
    }

    private fun applyBottomPadding() {
        if (!::bottom.isInitialized) return
        val dp = context.resources.displayMetrics.density
        bottom.setPadding(
            (12 * dp).toInt(),
            (4 * dp).toInt(),
            (12 * dp).toInt(),
            navPad + (12 * dp).toInt()
        )
    }

    private fun space(dp: Float): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams((8 * dp).toInt(), 1)
        }
    }

    private fun roundAction(label: String, bg: Int, fg: Int, onClick: () -> Unit): TextView {
        val dp = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            minHeight = (44 * dp).toInt()
            setPadding((22 * dp).toInt(), (10 * dp).toInt(), (22 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = 40f
            }
            setOnClickListener { onClick() }
        }
    }

    private fun circleIcon(label: String, onClick: () -> Unit): TextView {
        val dp = context.resources.displayMetrics.density
        val size = (44 * dp).toInt()
        return TextView(context).apply {
            text = if (label == "Google") "G" else "⎘"
            gravity = Gravity.CENTER
            setTextColor(if (label == "Google") Color.parseColor("#1A73E8") else Color.parseColor("#3C4043"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label == "Google") 18f else 16f)
            width = size
            height = size
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F3F4"))
                shape = GradientDrawable.OVAL
            }
            contentDescription = label
            setOnClickListener { onClick() }
        }
    }

    private fun onTap(x: Int, y: Int) {
        val screenH = root.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        if (y > screenH - bottom.height) return

        val hit = textItems
            .filter { it.rect.contains(x, y) }
            .minByOrNull { it.rect.width() * it.rect.height().coerceAtLeast(1) }

        if (hit != null) {
            toggle(hit)
        }
    }

    private fun toggle(item: ScreenTextItem) {
        val existing = selected.find { it.text == item.text && it.rect == item.rect }
        if (existing != null) selected.remove(existing) else selected.add(item)
        highlightView.invalidate()
        refreshChips()
        if (selected.isEmpty()) hidePill() else showPill()
    }

    private fun selectFromChip(item: ScreenTextItem) {
        val already = selected.any { it.text == item.text }
        if (already) {
            selected.removeAll { it.text == item.text }
        } else {
            selected.add(item)
        }
        highlightView.invalidate()
        refreshChips()
        if (selected.isEmpty()) hidePill() else showPill()
    }

    private fun showPill() {
        pill.visibility = View.VISIBLE
        val anchor = selected.lastOrNull() ?: return
        pill.post {
            pill.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val pw = pill.measuredWidth
            val ph = pill.measuredHeight
            val screenW = root.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
            var tx = anchor.rect.centerX() - pw / 2
            var ty = anchor.rect.top - ph - 20
            tx = tx.coerceIn(16, (screenW - pw - 16).coerceAtLeast(16))
            if (ty < statusPad + 8) ty = anchor.rect.bottom + 16
            val maxY = (root.height - bottom.height - ph - 8).coerceAtLeast(statusPad)
            ty = ty.coerceAtMost(maxY)
            pill.x = tx.toFloat()
            pill.y = ty.toFloat()
        }
    }

    private fun hidePill() {
        pill.visibility = View.GONE
    }

    private fun selectedQuery(): String {
        return selected.joinToString(" ") { it.text }.trim()
    }

    private fun isChrome(text: String): Boolean {
        return text.lowercase() in chrome
    }

    private fun refreshChips() {
        if (!::chipsRow.isInitialized) return
        chipsRow.removeAllViews()
        val dp = context.resources.displayMetrics.density
        val unique = LinkedHashSet<String>()
        val chips = ArrayList<ScreenTextItem>()
        for (item in textItems) {
            val t = item.text.trim()
            if (t.length < 2 || t.length > 42) continue
            if (isChrome(t)) continue
            if (!t.any { it.isLetter() }) continue
            if (unique.add(t)) chips.add(item)
            if (chips.size >= 14) break
        }
        if (chips.isEmpty()) {
            chipsRow.addView(TextView(context).apply {
                text = "Tap text on the screen"
                setTextColor(Color.parseColor("#5F6368"))
                setPadding((8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt())
            })
            return
        }
        for (item in chips) {
            val chosen = selected.any { it.text == item.text }
            val chip = TextView(context).apply {
                text = item.text
                minHeight = (40 * dp).toInt()
                gravity = Gravity.CENTER
                setTextColor(if (chosen) Color.WHITE else Color.parseColor("#202124"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(if (chosen) Color.parseColor("#1A73E8") else Color.parseColor("#E8F0FE"))
                    cornerRadius = 40f
                }
                setOnClickListener { selectFromChip(item) }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = (8 * dp).toInt() }
            chipsRow.addView(chip, lp)
        }
    }

    private fun searchGoogle(query: String, webOnly: Boolean) {
        if (query.isBlank()) {
            Toast.makeText(context, "Select text first", Toast.LENGTH_SHORT).show()
            return
        }
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val web = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(flags)
        }
        try {
            context.startActivity(web)
        } catch (_: Exception) {
            val browser = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
            ).addFlags(flags)
            context.startActivity(browser)
        }
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
        val dp = resources.displayMetrics.density
        val title = TextView(this).apply {
            text = "Quick Assistant"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.parseColor("#202124"))
            gravity = Gravity.CENTER
        }
        val body = TextView(this).apply {
            text = "1. Set as default assistant\n2. Long-press Home in any app\n3. Tap several words to select them\n4. Tap Search or Google"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.parseColor("#3C4043"))
            setPadding(0, (16 * dp).toInt(), 0, (24 * dp).toInt())
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
            setPadding((28 * dp).toInt(), (48 * dp).toInt(), (28 * dp).toInt(), (28 * dp).toInt())
            setBackgroundColor(Color.WHITE)
            addView(title)
            addView(body)
            addView(button)
        }
        setContentView(layout)
    }
}
