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
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    private var selectedItem: ScreenTextItem? = null

    private lateinit var rootLayout: FrameLayout
    private lateinit var highlightOverlay: View
    private lateinit var floatingPill: LinearLayout

    override fun onCreate() {
        super.onCreate()
        // Prevent session window from auto-closing or flashing away on touch
        window?.window?.let { win ->
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            win.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            win.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }
    }

    override fun onCreateContentView(): View {
        rootLayout = FrameLayout(context).apply {
            // Semi-transparent dark wash identical to Circle to Search
            setBackgroundColor(Color.parseColor("#33000000"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Canvas that paints blue rounded selection boxes over detected text
        highlightOverlay = object : View(context) {
            val bgPaint = Paint().apply {
                color = Color.parseColor("#4D3B82F6") // Translucent blue highlight
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val handlePaint = Paint().apply {
                color = Color.parseColor("#1D4ED8") // Solid blue pin
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                selectedItem?.let { item ->
                    val r = item.rect
                    // Draw highlight box
                    canvas.drawRoundRect(
                        (r.left - 8).toFloat(),
                        (r.top - 4).toFloat(),
                        (r.right + 8).toFloat(),
                        (r.bottom + 4).toFloat(),
                        10f, 10f, bgPaint
                    )
                    // Draw left and right selector teardrops/pins
                    canvas.drawCircle((r.left - 8).toFloat(), (r.bottom + 4).toFloat(), 12f, handlePaint)
                    canvas.drawCircle((r.right + 8).toFloat(), (r.bottom + 4).toFloat(), 12f, handlePaint)
                }
            }
        }
        rootLayout.addView(highlightOverlay)

        // Floating Action Pill: [ Copy | Google Search ]
        floatingPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 14, 28, 14)
            visibility = View.GONE
            elevation = 24f

            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 80f
            }

            // 1. Copy Icon
            val copyBtn = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_save)
                setColorFilter(Color.parseColor("#424242"))
                setPadding(12, 12, 20, 12)
                setOnClickListener {
                    selectedItem?.let { item ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", item.text))
                        Toast.makeText(context, "Copied: ${item.text}", Toast.LENGTH_SHORT).show()
                    }
                    hide()
                }
            }

            // Divider line
            val divider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(2, 44)
                setBackgroundColor(Color.parseColor("#E5E7EB"))
            }

            // 2. Google Search Icon
            val searchBtn = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_search)
                setColorFilter(Color.parseColor("#1A73E8")) // Official Google Search Blue
                setPadding(20, 12, 12, 12)
                setOnClickListener {
                    selectedItem?.let { item ->
                        val query = item.text
                        val googleAppIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                            putExtra(SearchManager.QUERY, query)
                            setPackage("com.google.android.googlequicksearchbox")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                        try {
                            context.startActivity(googleAppIntent)
                        } catch (e: Exception) {
                            val browserIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(browserIntent)
                        }
                    }
                    hide()
                }
            }

            addView(copyBtn)
            addView(divider)
            addView(searchBtn)
        }
        rootLayout.addView(floatingPill)

        // Listen for user taps anywhere on screen
        rootLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val touchX = event.rawX.toInt()
                val touchY = event.rawY.toInt()

                // Check if user touched an extracted word
                val hit = textItems.find { it.rect.contains(touchX, touchY) }

                if (hit != null) {
                    selectedItem = hit
                    highlightOverlay.invalidate()

                    // Position the floating menu directly above the word
                    floatingPill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                    val pillWidth = floatingPill.measuredWidth
                    val pillHeight = floatingPill.measuredHeight

                    val screenWidth = context.resources.displayMetrics.widthPixels
                    var targetX = hit.rect.centerX() - (pillWidth / 2)
                    var targetY = hit.rect.top - pillHeight - 24

                    // Constrain inside screen edges
                    targetX = targetX.coerceIn(24, screenWidth - pillWidth - 24)
                    if (targetY < 120) {
                        targetY = hit.rect.bottom + 28
                    }

                    floatingPill.x = targetX.toFloat()
                    floatingPill.y = targetY.toFloat()
                    floatingPill.visibility = View.VISIBLE
                } else {
                    // Tap on blank space dismisses session
                    hide()
                }
            }
            true
        }

        return rootLayout
    }

    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        textItems.clear()
        selectedItem = null
        highlightOverlay.invalidate()
        floatingPill.visibility = View.GONE

        // Parse visual coordinates of all text nodes across all windows
        structure?.let { struct ->
            for (i in 0 until struct.windowNodeCount) {
                val windowNode = struct.getWindowNodeAt(i)
                traverseNode(windowNode.rootViewNode, windowNode.left, windowNode.top)
            }
        }
    }

    private fun traverseNode(node: AssistStructure.ViewNode?, windowLeft: Int, windowTop: Int) {
        if (node == null || node.visibility != View.VISIBLE) return

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            val left = windowLeft + node.left
            val top = windowTop + node.top
            val right = left + node.width
            val bottom = top + node.height
            textItems.add(ScreenTextItem(text, Rect(left, top, right, bottom)))
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChildAt(i), windowLeft, windowTop)
        }
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val intent = Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Please select Quick Assistant in Default Apps", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
