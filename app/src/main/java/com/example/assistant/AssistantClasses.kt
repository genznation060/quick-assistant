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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
            val intent = Intent(context, ClipboardActivity::class.java).apply {
                putExtra(EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }
}

data class ScreenTextItem(val text: String, val rect: Rect, val fromOcr: Boolean = false)

class CustomSession(context: Context) : VoiceInteractionSession(context) {

    private val textItems = ArrayList<ScreenTextItem>()
    private val selected = LinkedHashSet<ScreenTextItem>()
    private var overlayReady = false
    private var readyAt = 0L
    private var pendingStructure: AssistStructure? = null
    private var screenshot: Bitmap? = null
    private var ocrRunning = false
    private var navPad = 0
    private var statusPad = 0

    private lateinit var root: FrameLayout
    private lateinit var highlightView: HighlightView
    private lateinit var pill: LinearLayout
    private lateinit var chipsRow: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var statusHint: TextView

    private val chrome = setOf(
        "google", "search", "ai", "mode", "all", "images", "videos", "shopping",
        "forums", "news", "maps", "more", "show more", "copy", "share", "ok",
        "cancel", "settings", "home", "back", "rotate", "play video", "lens"
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
            screenshot?.let { runOcr(it) }
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

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot == null || screenshot.isRecycled) return
        this.screenshot = screenshot.copy(Bitmap.Config.ARGB_8888, false)
        if (overlayReady) runOcr(this.screenshot!!)
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
        textItems.removeAll { !it.fromOcr }
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
            if (!tooLow && !tooHigh) addTextPieces(text, rect, fromOcr = false)
        }

        for (i in 0 until node.childCount) {
            traverse(node.getChildAt(i), x, y)
        }
    }

    private fun addTextPieces(text: String, rect: Rect, fromOcr: Boolean) {
        val dp = context.resources.displayMetrics.density
        val singleLine = rect.height() <= (40 * dp)
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        if (!singleLine) {
            if (lines.size > 1) {
                val h = (rect.height() / lines.size).coerceAtLeast(20)
                lines.forEachIndexed { i, line ->
                    val top = rect.top + i * h
                    addTextPieces(line, Rect(rect.left, top, rect.right, top + h), fromOcr)
                }
            } else {
                textItems.add(ScreenTextItem(text, Rect(rect), fromOcr))
            }
            return
        }

        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 1) {
            textItems.add(ScreenTextItem(text, Rect(rect), fromOcr))
            return
        }
        val total = words.sumOf { it.length }.coerceAtLeast(1)
        var offset = 0
        for (w in words) {
            val wpx = (rect.width() * (w.length.toFloat() / total)).toInt().coerceAtLeast(28)
            val left = rect.left + offset
            textItems.add(ScreenTextItem(w, Rect(left, rect.top, left + wpx, rect.bottom), fromOcr))
            offset += wpx
        }
    }

    private fun runOcr(bmp: Bitmap) {
        if (ocrRunning) return
        ocrRunning = true
        if (::statusHint.isInitialized) statusHint.text = "Scanning photo text…"
        try {
            val image = InputImage.fromBitmap(bmp, 0)
            val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            client.process(image)
                .addOnSuccessListener { result ->
                    textItems.removeAll { it.fromOcr }
                    val dm = context.resources.displayMetrics
                    val sx = dm.widthPixels.toFloat() / bmp.width
                    val sy = dm.heightPixels.toFloat() / bmp.height
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox ?: continue
                            val mapped = Rect(
                                (box.left * sx).toInt(),
                                (box.top * sy).toInt(),
                                (box.right * sx).toInt(),
                                (box.bottom * sy).toInt()
                            )
                            val t = line.text.trim()
                            if (t.length < 2) continue
                            if (isChrome(t)) continue
                            val exists = textItems.any { it.text.equals(t, true) }
                            if (!exists) addTextPieces(t, mapped, fromOcr = true)
                        }
                    }
                    if (::statusHint.isInitialized) {
                        val n = textItems.count { it.fromOcr }
                        statusHint.text = if (n > 0) {
                            "Photo text found — tap to select"
                        } else {
                            "Tap words to multi-select, then Search"
                        }
                    }
                    refreshUi()
                    ocrRunning = false
                    client.close()
                }
                .addOnFailureListener {
                    if (::statusHint.isInitialized) {
                        statusHint.text = "Could not read photo text"
                    }
                    ocrRunning = false
                    client.close()
                }
        } catch (t: Throwable) {
            ocrRunning = false
            Toast.makeText(context, "OCR error", Toast.LENGTH_SHORT).show()
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

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            elevation = 20f
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 36f
            }
            setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
            addView(sideBtn("Lens") { onLens() })
            addView(sideBtn("Copy") { copySelected() })
            addView(sideBtn("G") { searchGoogle(selectedQuery()) })
        }
        root.addView(side, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = (8 * dp).toInt()
            bottomMargin = (80 * dp).toInt()
        })

        chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }

        statusHint = TextView(context).apply {
            text = "Tap words to multi-select, then Search"
            setTextColor(Color.parseColor("#5F6368"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (10 * dp).toInt(), 0, 0)
            addView(roundAction("Search", Color.parseColor("#1A73E8"), Color.WHITE) {
                searchGoogle(selectedQuery())
            })
            addView(space(dp))
            addView(roundAction("Copy", Color.parseColor("#E8F0FE"), Color.parseColor("#1A73E8")) {
                copySelected()
            })
            addView(space(dp))
            addView(roundAction("Lens", Color.parseColor("#E8F0FE"), Color.parseColor("#1A73E8")) {
                onLens()
            })
        }

        bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 16f
            addView(statusHint)
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
            addView(circleIcon("Copy") { copySelected() })
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(2, (36 * dp).toInt()).apply {
                    leftMargin = (4 * dp).toInt()
                    rightMargin = (4 * dp).toInt()
                    gravity = Gravity.CENTER_VERTICAL
                }
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            })
            addView(circleIcon("Google") { searchGoogle(selectedQuery()) })
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

    private fun sideBtn(label: String, onClick: () -> Unit): TextView {
        val dp = context.resources.displayMetrics.density
        val size = (44 * dp).toInt()
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1A73E8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            width = size
            height = size
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            setOnClickListener { onClick() }
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
            setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt())
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
            text = if (label == "Google") "G" else "Copy"
            gravity = Gravity.CENTER
            setTextColor(if (label == "Google") Color.parseColor("#1A73E8") else Color.parseColor("#3C4043"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label == "Google") 18f else 12f)
            minWidth = size
            height = size
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F3F4"))
                cornerRadius = size / 2f
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

        if (hit != null) toggle(hit)
    }

    private fun toggle(item: ScreenTextItem) {
        val existing = selected.find { it.text == item.text && it.rect == item.rect }
        if (existing != null) selected.remove(existing) else selected.add(item)
        refreshUi()
        if (selected.isEmpty()) hidePill() else showPill()
    }

    private fun selectFromChip(item: ScreenTextItem) {
        val already = selected.any { it.text == item.text }
        if (already) selected.removeAll { it.text == item.text } else selected.add(item)
        refreshUi()
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

    private fun refreshUi() {
        if (::highlightView.isInitialized) highlightView.invalidate()
        refreshChips()
    }

    private fun refreshChips() {
        if (!::chipsRow.isInitialized) return
        chipsRow.removeAllViews()
        val dp = context.resources.displayMetrics.density
        val unique = LinkedHashSet<String>()
        val chips = ArrayList<ScreenTextItem>()
        val ordered = textItems.sortedByDescending { it.fromOcr }
        for (item in ordered) {
            val t = item.text.trim()
            if (t.length < 2 || t.length > 42) continue
            if (isChrome(t)) continue
            if (!t.any { it.isLetter() }) continue
            if (unique.add(t)) chips.add(item)
            if (chips.size >= 16) break
        }
        if (chips.isEmpty()) {
            chipsRow.addView(TextView(context).apply {
                text = "Tap Lens to read photo text"
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

    private fun copySelected() {
        var q = selectedQuery()
        if (q.isBlank()) {
            q = textItems.filter { it.fromOcr }.joinToString(" ") { it.text }.trim()
        }
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

    private fun onLens() {
        val bmp = screenshot
        if (bmp != null && !bmp.isRecycled) {
            runOcr(bmp)
            openGoogleLens(bmp)
        } else {
            Toast.makeText(
                context,
                "No screenshot yet. Long-press Home again, or Xiaomi may block screen capture.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openGoogleLens(bmp: Bitmap) {
        try {
            val file = File(context.cacheDir, "qa_lens.png")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            val uri = FileProvider.getUriForFile(context, "com.example.assistant.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.google.android.googlequicksearchbox")
            }
            try {
                context.startActivity(send)
            } catch (_: Exception) {
                val chooser = Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Open with Lens"
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(chooser)
            }
        } catch (_: Exception) {
        }
    }

    private fun searchGoogle(query: String) {
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
            text = "1. Set as default assistant\n2. Long-press Home\n3. Tap words (or Lens to read a photo)\n4. Copy / Search"
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
