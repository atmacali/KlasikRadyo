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
            prefs.getBoolean("auto_play_on_bluetooth", false) ||
            prefs.getBoolean("charge_bluetooth_play", false)
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
            val handler = Handler(Looper.getMainLooper())
            val appContext = context.applicationContext
            LAUNCH_DELAYS_MS.forEachIndexed { index, delay ->
                handler.postDelayed({
                    // Retries only while the app is still not on screen.
                    if (index == 0 || !MainActivity.isVisible()) ScreensaverLauncher.launch(appContext)
                }, delay)
            }
        }
    }

    private companion object {
        val LAUNCH_DELAYS_MS = longArrayOf(30_000L, 36_000L, 44_000L, 55_000L, 70_000L, 90_000L)
    }
}
