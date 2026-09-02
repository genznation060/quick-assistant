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
    private var selected: ScreenTextItem? = null
    private var overlayReady = false
    private var readyAt = 0L
    private var pendingStructure: AssistStructure? = null

    private lateinit var root: FrameLayout
    private lateinit var highlightView: HighlightView
    private lateinit var pill: LinearLayout
    private lateinit var chipsRow: LinearLayout
    private lateinit var hintView: TextView

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            buildOverlay()
            overlayReady = true
            readyAt = SystemClock.uptimeMillis() + 450
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

    private fun applyStructure(structure: AssistStructure?) {
        textItems.clear()
        selected = null
        structure?.let { struct ->
            for (i in 0 until struct.windowNodeCount) {
                val w = struct.getWindowNodeAt(i)
                traverse(w.rootViewNode, w.left, w.top)
            }
        }
        refreshChips()
        highlightView.invalidate()
        hidePill()
        hintView.text = if (textItems.isEmpty()) {
            "No on-screen text found."
        } else {
            "Tap a word on the screen to select it"
        }
    }

    private fun traverse(node: AssistStructure.ViewNode?, parentX: Int, parentY: Int) {
        if (node == null) return
        if (node.visibility != View.VISIBLE) return

        val x = parentX + node.left - node.scrollX
        val y = parentY + node.top - node.scrollY
        val raw = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val text = raw.ifBlank { desc }

        if (text.isNotBlank() && node.width > 8 && node.height > 8) {
            addTextPieces(text, Rect(x, y, x + node.width, y + node.height))
        }

        for (i in 0 until node.childCount) {
            traverse(node.getChildAt(i), x, y)
        }
    }

    private fun addTextPieces(text: String, rect: Rect) {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size <= 1) {
            val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size <= 1) {
                textItems.add(ScreenTextItem(text, Rect(rect)))
                return
            }
            val total = words.sumOf { it.length }.coerceAtLeast(1)
            var offset = 0
            for (w in words) {
                val share = w.length.toFloat() / total
                val wpx = (rect.width() * share).toInt().coerceAtLeast(24)
                val left = rect.left + offset
                textItems.add(ScreenTextItem(w, Rect(left, rect.top, left + wpx, rect.bottom)))
                offset += wpx
            }
            return
        }
        val h = (rect.height() / lines.size).coerceAtLeast(20)
        lines.forEachIndexed { i, line ->
            val top = rect.top + i * h
            addTextPieces(line, Rect(rect.left, top, rect.right, top + h))
        }
    }

    private fun buildOverlay() {
        val dp = context.resources.displayMetrics.density

        root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#66000000"))
        }

        highlightView = HighlightView(context) {
            selected
        }.apply {
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (36 * dp).toInt()
            rightMargin = (12 * dp).toInt()
        })

        hintView = TextView(context).apply {
            text = "Reading screen…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }

        chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2FFFFFF"))
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt())
            addView(TextView(context).apply {
                text = "Select text, then Google it"
                setTextColor(Color.parseColor("#202124"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, 0, 0, (8 * dp).toInt())
            })
            addView(chipsScroll)
        }
        root.addView(bottom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), (8 * dp).toInt())
            visibility = View.GONE
            elevation = 24f
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 60f
            }
            addView(actionBtn("Copy") {
                val item = selected ?: return@actionBtn
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Selected Text", item.text))
                Toast.makeText(context, "Copied: ${item.text}", Toast.LENGTH_SHORT).show()
            })
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(2, (28 * dp).toInt()).apply {
                    leftMargin = (8 * dp).toInt()
                    rightMargin = (8 * dp).toInt()
                }
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            })
            addView(actionBtn("Google") {
                val item = selected ?: return@actionBtn
                searchGoogle(item.text)
            })
        }
        root.addView(pill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
    }

    private fun actionBtn(label: String, onClick: () -> Unit): TextView {
        val dp = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            setTextColor(if (label == "Google") Color.parseColor("#1A73E8") else Color.parseColor("#3C4043"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun onTap(x: Int, y: Int) {
        val hit = textItems
            .filter { it.rect.contains(x, y) }
            .minByOrNull { it.rect.width() * it.rect.height().coerceAtLeast(1) }
        if (hit != null) {
            select(hit)
        } else {
            selected = null
            highlightView.invalidate()
            hidePill()
        }
    }

    private fun select(item: ScreenTextItem) {
        selected = item
        highlightView.invalidate()
        pill.visibility = View.VISIBLE
        pill.post {
            pill.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val pw = pill.measuredWidth
            val ph = pill.measuredHeight
            val screenW = root.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
            var tx = item.rect.centerX() - pw / 2
            var ty = item.rect.top - ph - 24
            tx = tx.coerceIn(16, (screenW - pw - 16).coerceAtLeast(16))
            if (ty < 80) ty = item.rect.bottom + 16
            pill.x = tx.toFloat()
            pill.y = ty.toFloat()
        }
        refreshChips()
    }

    private fun hidePill() {
        pill.visibility = View.GONE
    }

    private fun refreshChips() {
        if (!::chipsRow.isInitialized) return
        chipsRow.removeAllViews()
        val dp = context.resources.displayMetrics.density
        val unique = LinkedHashSet<String>()
        val chips = ArrayList<ScreenTextItem>()
        for (item in textItems) {
            val t = item.text.trim()
            if (t.length < 2 || t.length > 48) continue
            if (unique.add(t)) chips.add(item)
            if (chips.size >= 16) break
        }
        if (chips.isEmpty()) {
            chipsRow.addView(TextView(context).apply {
                text = "Nothing selectable on this screen"
                setTextColor(Color.parseColor("#5F6368"))
            })
            return
        }
        for (item in chips) {
            val chosen = selected?.text == item.text
            val chip = TextView(context).apply {
                text = item.text
                setTextColor(if (chosen) Color.WHITE else Color.parseColor("#202124"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(if (chosen) Color.parseColor("#1A73E8") else Color.parseColor("#E8F0FE"))
                    cornerRadius = 40f
                }
                setOnClickListener { select(item) }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = (8 * dp).toInt() }
            chipsRow.addView(chip, lp)
        }
    }

    private fun searchGoogle(query: String) {
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
        private val selectedProvider: () -> ScreenTextItem?
    ) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#662196F3")
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2196F3")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val item = selectedProvider() ?: return
            val r = item.rect
            canvas.drawRoundRect(
                r.left - 6f, r.top - 4f, r.right + 6f, r.bottom + 4f,
                12f, 12f, fill
            )
            canvas.drawRoundRect(
                r.left - 6f, r.top - 4f, r.right + 6f, r.bottom + 4f,
                12f, 12f, stroke
            )
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
            text = "1. Tap the button below\n2. Choose Quick Assistant as the default digital assistant\n3. Open any app, then long-press Home\n4. Tap a word, then tap Google"
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
