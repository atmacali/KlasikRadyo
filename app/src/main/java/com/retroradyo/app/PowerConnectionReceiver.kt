package com.retroradyo.app

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

class PowerConnectionReceiver : BroadcastReceiver() {
    internal var playbackService: PlaybackService? = null

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_POWER_CONNECTED -> playbackService?.onPowerConnected()
            Intent.ACTION_POWER_DISCONNECTED -> {
                playbackService?.onPowerDisconnected()
                playbackService?.stopForPowerDisconnection()
            }
        }

        if (!shouldHandle(action)) return

        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                handlePowerConnected()
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                handlePowerDisconnected(context)
            }
        }
    }

    private fun handlePowerConnected() {
        val activityIsVisible =
            MainActivity.dispatchPowerActionToVisibleActivity(MainActivity.ACTION_POWER_CONNECTED)
        if (!activityIsVisible) {
            playbackService?.resumeForPowerConnection()
        }
    }

    private fun handlePowerDisconnected(context: Context) {
        if (playbackService?.shouldStopOnPowerDisconnection() == false) return
        if (MainActivity.dispatchPowerActionToVisibleActivity(MainActivity.ACTION_POWER_DISCONNECTED)) {
            return
        }
        val settings = context.getSharedPreferences("radio_automation", Context.MODE_PRIVATE)
        if (!settings.getBoolean("stop_on_unplug", true)) return
        context.getSystemService(ActivityManager::class.java).appTasks.forEach { task ->
            try {
                task.finishAndRemoveTask()
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }

    private companion object {
        private const val DUPLICATE_EVENT_WINDOW_MS = 1_000L

        private var lastAction: String? = null
        private var lastEventTime = 0L

        @Synchronized
        fun shouldHandle(action: String): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (action == lastAction && now - lastEventTime < DUPLICATE_EVENT_WINDOW_MS) {
                return false
            }
            lastAction = action
            lastEventTime = now
            return true
        }
    }
}
