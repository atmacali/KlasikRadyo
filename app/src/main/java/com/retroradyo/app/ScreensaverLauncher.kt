package com.retroradyo.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

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
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "retroradyo:screensaver"
        ).acquire(5_000L)
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
}
