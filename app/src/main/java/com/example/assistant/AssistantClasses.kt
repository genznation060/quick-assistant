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

    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        textItems.clear()

        // Extract every word along with its exact pixel position on screen
        structure?.let { struct ->
            for (i in 0 until struct.windowNodeCount) {
                val windowNode = struct.getWindowNodeAt(i)
                traverseNode(windowNode.rootViewNode, windowNode.left, windowNode.top)
            }
        }

        // Build UI fresh every time to prevent lifecycle crashes
        showInteractiveOverlay()
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

    private fun showInteractiveOverlay() {
        // Ensure the window doesn't dismiss on random touches and stays fullscreen
        window?.window?.let { win ->
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            win.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#44000000")) // Dark dim
        }

        var selectedItem: ScreenTextItem? = null

        // Floating Action Pill (Created first to reference it, added later)
        val popupPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 12, 24, 12)
            visibility = View.GONE
            elevation = 20f

            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 60f
            }

            // 1. Copy Action
            val copyIcon = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_save)
                setColorFilter(Color.DKGRAY)
                setPadding(16, 16, 24, 16)
                setOnClickListener {
                    selectedItem?.let {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Selected Text", it.text))
                        Toast.makeText(context, "Copied: ${it.text}", Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
            }

            // Divider Line
            val divider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(2, 40)
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            }

            // 2. Google Search Action
            val searchIcon = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_search)
                setColorFilter(Color.parseColor("#4285F4")) // Google Blue
                setPadding(24, 16, 16, 16)
                setOnClickListener {
                    selectedItem?.let {
                        val query = it.text
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
                    finish()
                }
            }

            addView(copyIcon)
            addView(divider)
            addView(searchIcon)
        }

        // Custom canvas view to draw blue highlights over detected text
        val highlightView = object : View(context) {
            val paint = Paint().apply {
                color = Color.parseColor("#4D2196F3") // Soft blue selection highlight
                style = Paint.Style.FILL
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                selectedItem?.let {
                    canvas.drawRoundRect(
                        it.rect.left.toFloat() - 6,
                        it.rect.top.toFloat() - 4,
                        it.rect.right.toFloat() + 6,
                        it.rect.bottom.toFloat() + 4,
                        12f, 12f, paint
                    )
                }
            }
        }

        root.addView(highlightView)
        root.addView(popupPill)

        // Handle touch on screen to select items
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val touchX = event.x.toInt()
                val touchY = event.y.toInt()

                // Find the exact text element tapped
                val hit = textItems.find { it.rect.contains(touchX, touchY) }

                if (hit != null) {
                    selectedItem = hit
                    highlightView.invalidate()

                    // Position the floating pill right above the selected word
                    popupPill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                    val pillWidth = popupPill.measuredWidth
                    val pillHeight = popupPill.measuredHeight

                    var targetX = hit.rect.centerX() - (pillWidth / 2)
                    var targetY = hit.rect.top - pillHeight - 20

                    // Keep pill inside screen boundaries
                    val screenWidth = context.resources.displayMetrics.widthPixels
                    targetX = targetX.coerceIn(20, screenWidth - pillWidth - 20)
                    if (targetY < 80) targetY = hit.rect.bottom + 20

                    popupPill.x = targetX.toFloat()
                    popupPill.y = targetY.toFloat()
                    popupPill.visibility = View.VISIBLE
                } else {
                    // Tapping empty space closes the assistant
                    finish()
                }
            }
            true // Return true so touches aren't passed to the background
        }

        // Lock in the UI dynamically exactly like the working version did
        setContentView(root)
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
