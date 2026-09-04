package com.example.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent != null && context != null) {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                "com.vivo.intent.action.BOOT_COMPLETED" -> {
                    // Start your main activity or service here
                    val startIntent = Intent(context, MainActivity::class.java)
                    startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(startIntent)
                }
            }
        }
    }
}
