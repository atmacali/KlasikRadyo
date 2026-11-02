package com.retroradyo.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Locks the screen like the power button (no biometric downgrade). It listens to no events
 * and reads no content; it exists only to call GLOBAL_ACTION_LOCK_SCREEN.
 */
class ScreenLockService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: ScreenLockService? = null

        fun lockNow(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(context, ScreenLockService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
