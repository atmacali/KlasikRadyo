package com.retroradyo.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return

        val prefs = context.getSharedPreferences("radio_automation", Context.MODE_PRIVATE)
        val autoStartOnBoot = prefs.getBoolean("auto_start_on_boot", false)
        val bluetoothMonitor = prefs.getBoolean("auto_open_on_bluetooth", false) ||
            prefs.getBoolean("auto_play_on_bluetooth", false)
        if (!autoStartOnBoot && !bluetoothMonitor) return

        // Keeps the Bluetooth listener alive after a reboot without opening the app first.
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java)
                    .setAction(MainActivity.ACTION_START_CHARGING_MONITOR)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (autoStartOnBoot) {
            val pendingResult = goAsync()
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    ScreensaverLauncher.launch(context.applicationContext)
                } finally {
                    pendingResult.finish()
                }
            }, LAUNCH_DELAY_MS)
        }
    }

    private companion object {
        const val LAUNCH_DELAY_MS = 5_000L
    }
}
