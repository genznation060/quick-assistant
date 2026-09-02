package com.example.assistant

import android.app.assist.AssistStructure
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MyAssistantService : VoiceInteractionService()

class MyAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return CustomSession(this)
    }
}

class CustomSession(context: Context) : VoiceInteractionSession(context) {

    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        val extractedText = StringBuilder()
        
        // Extract all readable text nodes currently on screen
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
            if (it.isNotBlank()) builder.append(it).append("\n")
        }
        for (i in 0 until node.childCount) {
            traverseNode(node.getChildAt(i), builder)
        }
    }

    private fun showAssistantSheet(text: String) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(-0x1) // White background
        }

        val copyBtn = Button(context).apply {
            this.text = "Copy Screen Text"
            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Screen Text", text))
                Toast.makeText(context, "Copied all screen text!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        val searchBtn = Button(context).apply {
            this.text = "Google Search"
            setOnClickListener {
                val query = Uri.encode(text.take(200)) // Take first part of text
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$query")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                finish()
            }
        }

        layout.addView(copyBtn)
        layout.addView(searchBtn)
        setContentView(layout)
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Directs the user to set this app as Default Assistant
        val intent = Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
        startActivity(intent)
        finish()
    }
}
