package com.example.assistant

import android.app.SearchManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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

class CustomSession(context: Context) : VoiceInteractionSession(context) {

    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        val extractedText = StringBuilder()
        
        structure?.let { struct ->
            for (i in 0 until struct.windowNodeCount) {
                traverseNode(struct.getWindowNodeAt(i).rootViewNode, extractedText)
            }
        }

        val allText = extractedText.toString().trim()
        showAssistantSheet(allText)
    }

    private fun traverseNode(node: AssistStructure.ViewNode?, builder: StringBuilder) {
        if (node == null) return
        node.text?.let {
            if (it.isNotBlank()) builder.append(it).append(" ")
        }
        for (i in 0 until node.childCount) {
            traverseNode(node.getChildAt(i), builder)
        }
    }

    private fun showAssistantSheet(text: String) {
        // Root container - dim background so your screen stays visible
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(Color.parseColor("#66000000")) // Semi-transparent overlay
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { finish() } // Tap outside dialog to dismiss
        }

        // Bottom sheet card
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E")) // Dark modern sheet
                cornerRadii = floatArrayOf(40f, 40f, 40f, 40f, 0f, 0f, 0f, 0f)
            }
            background = bg
            isClickable = true // Prevent clicks from closing sheet
        }

        // Editable text area: allows highlighting, cursor movement, and selective copying
        val editText = EditText(context).apply {
            setText(text)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "No readable text found on screen"
            textSize = 14f
            maxLines = 6
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(24, 24, 24, 24)
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                350 // Max height for the text area
            )
            addView(editText)
        }

        // Action Buttons Row
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 24, 0, 0)
        }

        val copyBtn = Button(context).apply {
            this.text = "Copy Selection"
            setOnClickListener {
                // If user highlighted specific text, copy that; otherwise copy entire box
                val selected = if (editText.hasSelection()) {
                    editText.text.subSequence(editText.selectionStart, editText.selectionEnd).toString()
                } else {
                    editText.text.toString()
                }

                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Selected Text", selected))
                Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        val searchBtn = Button(context).apply {
            this.text = "Search Google"
            setOnClickListener {
                val query = if (editText.hasSelection()) {
                    editText.text.subSequence(editText.selectionStart, editText.selectionEnd).toString()
                } else {
                    editText.text.toString().take(200)
                }

                // 1. Try launching directly into the official Google App
                val googleAppIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, query)
                    setPackage("com.google.android.googlequicksearchbox")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    context.startActivity(googleAppIntent)
                } catch (e: Exception) {
                    // 2. Fallback: Browser search if the official Google App isn't installed
                    val browserIntent = Intent(
                        Intent.ACTION_VIEW, 
                        Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                }
                finish()
            }
        }

        buttonRow.addView(copyBtn)
        buttonRow.addView(searchBtn)

        card.addView(scroll)
        card.addView(buttonRow)
        root.addView(card)

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
