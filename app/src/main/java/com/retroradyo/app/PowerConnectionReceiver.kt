package com.retroradyo.app

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class PowerConnectionReceiver : BroadcastReceiver() {
    internal var playbackService: PlaybackService? = null

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!shouldHandle(action)) return

        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                (playbackService ?: (context as? PlaybackService))?.resumeForPowerConnection()
                handlePowerConnected(context)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                (playbackService ?: (context as? PlaybackService))?.stopForPowerDisconnection()
                handlePowerDisconnected(context)
            }
        }
    }

    private fun handlePowerConnected(context: Context) {
        val activityIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_POWER_CONNECTED
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        if (MainActivity.isVisibleToUser) {
            try {
                context.startActivity(activityIntent)
                return
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
        showOpenAppNotification(context, activityIntent)
    }

    private fun handlePowerDisconnected(context: Context) {
        NotificationManagerCompat.from(context).cancel(POWER_NOTIFICATION_ID)

        if (MainActivity.isVisibleToUser) {
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_POWER_DISCONNECTED
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
                return
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
        context.stopService(Intent(context, PlaybackService::class.java))
        context.getSystemService(ActivityManager::class.java).appTasks.forEach { task ->
            try {
                task.finishAndRemoveTask()
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }

    private fun showOpenAppNotification(context: Context, activityIntent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    POWER_CHANNEL_ID,
                    context.getString(R.string.power_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            POWER_NOTIFICATION_ID,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, POWER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(context.getString(R.string.power_connected_title))
            .setContentText(context.getString(R.string.power_connected_message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(POWER_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private companion object {
        const val POWER_CHANNEL_ID = "power_connection"
        const val POWER_NOTIFICATION_ID = 1001
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
