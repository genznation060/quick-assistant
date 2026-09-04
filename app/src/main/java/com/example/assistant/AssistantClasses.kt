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
import android.util.Log
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
import java.io.IOException

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
            showToast("Copied: ${text.take(48)}")
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

        private fun showToast(message: String) {
            Toast.makeText(null, message, Toast.LENGTH_SHORT).show()
        }
    }
}

data class ScreenTextItem(val text: String, val rect: Rect)

data class ActionButton(
    val label: String,
    val bgColor: String,
    val fgColor: String,
    val action: () -> Unit
)

object UiConstants {
    const val OVERLAY_BG = "#4D000000"
    const val CLOSE_BTN_BG = "#99000000"
    const val BUTTON_BG_LIGHT = "#E8F0FE"
    const val BUTTON_COLOR_PRIMARY = "#1A73E8"
    const val BUTTON_COLOR_WHITE = "#FFFFFF"
    const val TEXT_PRIMARY = "#202124"
    const val TEXT_SECONDARY = "#5F6368"
    const val HIGHLIGHT_FILL = "#802196F3"
    const val HIGHLIGHT_STROKE = "#1A73E8"

    val CHROME_KEYWORDS = setOf(
        "google", "search", "ai", "mode", "all", "images", "videos", "shopping",
        "forums", "news", "maps", "more", "show more", "copy", "share", "ok",
        "cancel", "settings", "home", "back", "lens"
    )

    const val TAG = "QuickAssistant"
    const val LENS_CACHE_FILE = "qa_lens.png"
    const val FILE_PROVIDER_AUTHORITY = "com.example.assistant.files"
    const val LENS_PACKAGE = "com.google.android.googlequicksearchbox"
}

class DpScaler(context: Context) {
    private val density = context.resources.displayMetrics.density

    fun toPx(dp: Float): Int = (dp * density).toInt()
}

class CustomSession(context: Context) : VoiceInteractionSession(context) {

    private val textItems = ArrayList<ScreenTextItem>()
    private val selected = LinkedHashSet<ScreenTextItem>()
    private var overlayReady = false
    private var readyAt = 0L
    private var pendingStructure: AssistStructure? = null
    private var screenshot: Bitmap? = null
    private var navPad = 0
    private var statusPad = 0

    private var root: FrameLayout? = null
    private var highlightView: HighlightView? = null
    private var chipsRow: LinearLayout? = null
    private var bottom: LinearLayout? = null
    private lateinit var scaler: DpScaler

    private val actionButtons by lazy {
        listOf(
            ActionButton("Copy", UiConstants.BUTTON_BG_LIGHT, UiConstants.BUTTON_COLOR_PRIMARY) {
                copySelected()
            },
            ActionButton("Lens", UiConstants.BUTTON_COLOR_PRIMARY, UiConstants.BUTTON_COLOR_WHITE) {
                openLensInFront()
            },
            ActionButton("Search", UiConstants.BUTTON_BG_LIGHT, UiConstants.BUTTON_COLOR_PRIMARY) {
                searchGoogle(selectedQuery())
            },
            ActionButton("Explain", UiConstants.BUTTON_BG_LIGHT, UiConstants.BUTTON_COLOR_PRIMARY) {
                explainSelected()
            },
            ActionButton("Summarize", UiConstants.BUTTON_BG_LIGHT, UiConstants.BUTTON_COLOR_PRIMARY) {
                summarizeSelected()
            },
            ActionButton("Search YT", UiConstants.BUTTON_BG_LIGHT, UiConstants.BUTTON_COLOR_PRIMARY) {
                searchYouTube()
            },
            ActionButton("Define", UiConstants.BUTTON_BG_LIGHT, UiConstants.BUTTON_COLOR_PRIMARY) {
                defineSelected()
            },
            ActionButton("Fact Check", UiConstants.BUTTON_BG_LIGHT, UiConstants.BUTTON_COLOR_PRIMARY) {
                factCheckSelected()
            },
            ActionButton("Translate", UiConstants.BUTTON_BG_LIGHT, UiConstants.BUTTON_COLOR_PRIMARY) {
                translateSelected()
            },
            ActionButton("Share", UiConstants.BUTTON_BG_LIGHT, UiConstants.BUTTON_COLOR_PRIMARY) {
                shareSelected()
            }
        )
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            scaler = DpScaler(context)
            computeSystemBars()
            buildOverlay()
            overlayReady = true
            readyAt = SystemClock.uptimeMillis() + 400
            pendingStructure?.let {
                applyStructure(it)
                pendingStructure = null
            }
        } catch (e: Throwable) {
            Log.e(UiConstants.TAG, "Assistant UI error", e)
            showToast("Assistant UI error")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        screenshot?.recycle()
        screenshot = null
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
        navPad = dimen("navigation_bar_height", scaler.toPx(48f))
        statusPad = dimen("status_bar_height", scaler.toPx(24f))
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
        } catch (e: Exception) {
            Log.w(UiConstants.TAG, "Error computing system bars", e)
        }
        navPad = maxOf(navPad, scaler.toPx(28f))
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
        val singleLine = rect.height() <= scaler.toPx(40f)
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
        root = createRootFrame()
        root?.apply {
            addView(createHighlightView())
            addView(createCloseButton())
            addView(createBottomPanel())
            setContentView(this)
            requestApplyInsets()
        }
    }

    private fun createRootFrame(): FrameLayout {
        return FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor(UiConstants.OVERLAY_BG))
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
    }

    private fun createHighlightView(): HighlightView {
        return HighlightView(context) { selected.toList() }.apply {
            highlightView = this
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
    }

    private fun createCloseButton(): TextView {
        return TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            val box = scaler.toPx(40f)
            width = box
            height = box
            background = GradientDrawable().apply {
                setColor(Color.parseColor(UiConstants.CLOSE_BTN_BG))
                cornerRadius = box / 2f
            }
            setOnClickListener { finish() }
        }
    }

    private fun createBottomPanel(): LinearLayout {
        chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val chipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }

        val actions = createActionsLayout()
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
                setTextColor(Color.parseColor(UiConstants.TEXT_SECONDARY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(
                    scaler.toPx(16f),
                    scaler.toPx(10f),
                    scaler.toPx(16f),
                    scaler.toPx(4f)
                )
            })
            addView(
                chipsScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = scaler.toPx(12f)
                    rightMargin = scaler.toPx(12f)
                }
            )
            addView(actionsScroll)
        }

        applyBottomPadding()
        return bottom!!
    }

    private fun createActionsLayout(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, scaler.toPx(10f), 0, 0)

            actionButtons.forEachIndexed { index, button ->
                if (index > 0) addView(gap())
                addView(roundAction(button.label, button.bgColor, button.fgColor, button.action))
            }
        }
    }

    private fun applyBottomPadding() {
        bottom?.setPadding(
            scaler.toPx(12f),
            scaler.toPx(4f),
            scaler.toPx(12f),
            navPad + scaler.toPx(12f)
        )
    }

    private fun gap(): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(scaler.toPx(8f), 1)
        }
    }

    private fun roundAction(label: String, bgColor: String, fgColor: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.parseColor(fgColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            minHeight = scaler.toPx(44f)
            setPadding(
                scaler.toPx(16f),
                scaler.toPx(10f),
                scaler.toPx(16f),
                scaler.toPx(10f)
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = 40f
            }
            setOnClickListener { onClick() }
        }
    }

    private fun onTap(x: Int, y: Int) {
        val screenH = root?.height?.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val bottomHeight = bottom?.height ?: 0
        if (y > screenH - bottomHeight) return
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

    private fun isChrome(text: String) = text.lowercase() in UiConstants.CHROME_KEYWORDS

    private fun refreshUi() {
        highlightView?.invalidate()
        refreshChips()
    }

    private fun refreshChips() {
        val chips = chipsRow ?: return
        chips.removeAllViews()
        val unique = LinkedHashSet<String>()
        val chipItems = ArrayList<ScreenTextItem>()
        for (item in textItems) {
            val t = item.text.trim()
            if (t.length < 2 || t.length > 42) continue
            if (isChrome(t)) continue
            if (!t.any { it.isLetter() }) continue
            if (unique.add(t)) chipItems.add(item)
            if (chipItems.size >= 16) break
        }
        if (chipItems.isEmpty()) {
            chips.addView(TextView(context).apply {
                text = "Tap text on the screen"
                setTextColor(Color.parseColor(UiConstants.TEXT_SECONDARY))
                setPadding(
                    scaler.toPx(8f),
                    scaler.toPx(12f),
                    scaler.toPx(8f),
                    scaler.toPx(12f)
                )
            })
            return
        }
        for (item in chipItems) {
            val chosen = selected.any { it.text == item.text }
            val chip = TextView(context).apply {
                text = item.text
                minHeight = scaler.toPx(40f)
                gravity = Gravity.CENTER
                setTextColor(
                    if (chosen) Color.WHITE else Color.parseColor(UiConstants.TEXT_PRIMARY)
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(
                    scaler.toPx(14f),
                    scaler.toPx(8f),
                    scaler.toPx(14f),
                    scaler.toPx(8f)
                )
                background = GradientDrawable().apply {
                    setColor(
                        if (chosen) Color.parseColor(UiConstants.BUTTON_COLOR_PRIMARY)
                        else Color.parseColor(UiConstants.BUTTON_BG_LIGHT)
                    )
                    cornerRadius = 40f
                }
                setOnClickListener { selectFromChip(item) }
            }
            chips.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = scaler.toPx(8f) }
            )
        }
    }

    // ==================== ACTIONS ====================

    private fun copySelected() {
        val q = selectedQuery()
        if (q.isBlank()) {
            showToast("Select text first")
            return
        }
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Quick Assistant", q))
        } catch (e: Exception) {
            Log.e(UiConstants.TAG, "Failed to copy to clipboard", e)
        }
        ClipboardActivity.copy(context, q)
    }

    private fun openLensInFront() {
        val bmp = screenshot
        val launched = if (bmp != null && !bmp.isRecycled) shareToLens(bmp) else openBareLens()
        if (!launched) {
            showToast("Could not open Google Lens")
            return
        }
        finish()
    }

    private fun shareToLens(bmp: Bitmap): Boolean {
        return try {
            val file = File(context.cacheDir, UiConstants.LENS_CACHE_FILE)
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
            val uri = FileProvider.getUriForFile(
                context,
                UiConstants.FILE_PROVIDER_AUTHORITY,
                file
            )
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(flags)
                setPackage(UiConstants.LENS_PACKAGE)
            }
            try {
                context.startActivity(send)
            } catch (e: Exception) {
                Log.d(UiConstants.TAG, "Direct lens share failed, trying chooser", e)
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
        } catch (e: IOException) {
            Log.e(UiConstants.TAG, "Failed to save lens image", e)
            openBareLens()
        } catch (e: SecurityException) {
            Log.e(UiConstants.TAG, "Permission denied for FileProvider", e)
            openBareLens()
        }
    }

    private fun openBareLens(): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("google://lens")).apply {
                setPackage(UiConstants.LENS_PACKAGE)
                addFlags(flags)
            },
            Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com")).addFlags(flags)
        )
        for (intent in tries) {
            try {
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.d(UiConstants.TAG, "Failed to open lens with intent", e)
            }
        }
        return false
    }

    private fun searchGoogle(query: String) {
        if (query.isBlank()) {
            showToast("Select text first")
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
        } catch (e: Exception) {
            Log.d(UiConstants.TAG, "Web search failed, trying fallback", e)
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
                ).addFlags(flags)
            )
        }
        finish()
    }

    private fun explainSelected() {
        val text = selectedQuery()
        if (text.isBlank()) {
            showToast("Select text first")
            return
        }
        searchGoogle("explain $text")
    }

    private fun summarizeSelected() {
        val text = selectedQuery()
        if (text.isBlank()) {
            showToast("Select text first")
            return
        }
        searchGoogle("summarize $text")
    }

    private fun searchYouTube() {
        val text = selectedQuery()
        if (text.isBlank()) {
            showToast("Select text first")
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
            showToast("Select text first")
            return
        }
        searchGoogle("define $text")
    }

    private fun factCheckSelected() {
        val text = selectedQuery()
        if (text.isBlank()) {
            showToast("Select text first")
            return
        }
        searchGoogle("fact check $text")
    }

    private fun translateSelected() {
        val text = selectedQuery()
        if (text.isBlank()) {
            showToast("Select text first")
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
            showToast("Select text first")
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

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    class HighlightView(
        context: Context,
        private val selectedProvider: () -> List<ScreenTextItem>
    ) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(UiConstants.HIGHLIGHT_FILL)
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(UiConstants.HIGHLIGHT_STROKE)
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
        val scaler = DpScaler(this)
        val title = TextView(this).apply {
            text = "Quick Assistant"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.parseColor(UiConstants.TEXT_PRIMARY))
            gravity = Gravity.CENTER
        }
        val body = TextView(this).apply {
            text = "1. Set as default assistant\n2. Long-press Home\n3. Tap words\n4. Use the action buttons"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.parseColor("#3C4043"))
            setPadding(0, scaler.toPx(16f), 0, scaler.toPx(24f))
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
            setPadding(
                scaler.toPx(28f),
                scaler.toPx(48f),
                scaler.toPx(28f),
                scaler.toPx(28f)
            )
            setBackgroundColor(Color.WHITE)
            addView(title)
            addView(body)
            addView(button)
        }
        setContentView(layout)
    }
}
