package com.retroradyo.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Opens the app in AMOLED (screensaver) mode from boot / Bluetooth events.
 *
 * Android 10+ blocks activity launches from the background, so besides a direct
 * startActivity (works when the app is visible or has the "display over other apps"
 * permission) a full-screen-intent notification is posted as a fallback.
 */
object ScreensaverLauncher {
    const val NOTIFICATION_ID = 2002
    private const val CHANNEL_ID = "screensaver_launch"

    fun launch(context: Context) {
        if (MainActivity.dispatchScreensaverToVisibleActivity()) return

        val intent = buildIntent(context)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (!canStartFromBackground(context)) postFullScreenNotification(context, intent)
    }

    fun canStartFromBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    fun overlayPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    private fun buildIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_SCREENSAVER
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

    private fun postFullScreenNotification(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "RetroRadyo otomatik açılış", NotificationManager.IMPORTANCE_HIGH)
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_icon)
            .setContentTitle("RetroRadyo")
            .setContentText("Açmak için dokunun")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
