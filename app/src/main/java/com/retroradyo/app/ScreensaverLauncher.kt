package com.retroradyo.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat

/** Opens the app in AMOLED (screensaver) mode from boot / Bluetooth events. */
object ScreensaverLauncher {
    fun launch(context: Context) {
        if (MainActivity.dispatchScreensaverToVisibleActivity()) return

        wakeScreen(context)
        try {
            context.startActivity(buildIntent(context))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        postFullScreenFallback(context)
    }

    // Background activity starts are often blocked (HyperOS); a full-screen intent still lights the screen.
    private fun postFullScreenFallback(context: Context) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(LAUNCH_CHANNEL_ID, "Ekran koruyucu", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                !manager.canUseFullScreenIntent()
            ) return
            val pending = PendingIntent.getActivity(
                context, LAUNCH_REQUEST, buildIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, LAUNCH_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("RetroRadyo")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .setAutoCancel(true)
                .setTimeoutAfter(10_000L)
                .build()
            manager.notify(LAUNCH_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "retroradyo:screensaver"
        ).acquire(10_000L)
    }

    private const val LAUNCH_CHANNEL_ID = "screensaver_launch"
    private const val LAUNCH_NOTIFICATION_ID = 2002
    private const val LAUNCH_REQUEST = 2020

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
}
