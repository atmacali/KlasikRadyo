package com.retroradyo.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Only grants the "lock screen" policy, used to switch the screen off when Bluetooth playback ends. */
class ScreenLockAdminReceiver : DeviceAdminReceiver() {
    companion object {
        private fun component(context: Context) = ComponentName(context, ScreenLockAdminReceiver::class.java)

        fun isActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(component(context)) == true

        fun lockNow(context: Context): Boolean = try {
            if (isActive(context)) {
                context.getSystemService(DevicePolicyManager::class.java)?.lockNow()
                true
            } else false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }

        fun enableIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Bluetooth ile çalma bitince ekranı kapatmak için gerekli."
                )
    }
}
