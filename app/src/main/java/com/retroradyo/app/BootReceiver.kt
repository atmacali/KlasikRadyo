package com.retroradyo.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = context.getSharedPreferences("radio_automation", Context.MODE_PRIVATE)
            val autoStartOnBoot = prefs.getBoolean("auto_start_on_boot", false)
            
            if (autoStartOnBoot) {
                Handler(Looper.getMainLooper()).postDelayed({
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                                 Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                                 Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        // Telefon açıldığında AMOLED ekran koruyucu modunu direkt tetiklemek için ekledik:
                        putExtra("start_screensaver", true)
                    }
                    try {
                        context.startActivity(launchIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, 15000)
            }
        }
    }
}
