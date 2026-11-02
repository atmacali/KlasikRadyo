package com.retroradyo.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.ParcelUuid

/** Decides which Bluetooth devices may trigger the generic (non device-selected) automations. */
object BluetoothDeviceFilter {
    const val PREFERENCES = "radio_automation"
    const val EXCLUDED_DEVICES = "bt_excluded_devices"
    const val IGNORE_NON_AUDIO = "bt_ignore_non_audio"

    private val AUDIO_UUIDS = setOf(
        "0000110b-0000-1000-8000-00805f9b34fb", // A2DP sink
        "00001108-0000-1000-8000-00805f9b34fb", // Headset
        "0000111e-0000-1000-8000-00805f9b34fb"  // Handsfree
    )

    fun excludedAddresses(context: Context): Set<String> =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(EXCLUDED_DEVICES, emptySet()).orEmpty()

    fun ignoreNonAudio(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(IGNORE_NON_AUDIO, true)

    fun isEligible(context: Context, device: BluetoothDevice?): Boolean {
        val address = device?.address ?: return true
        if (excludedAddresses(context).any { it.equals(address, ignoreCase = true) }) return false
        return !ignoreNonAudio(context) || isAudioOutput(device)
    }

    @SuppressLint("MissingPermission")
    fun isAudioOutput(device: BluetoothDevice): Boolean = try {
        val major = device.bluetoothClass?.majorDeviceClass
        when {
            major == BluetoothClass.Device.Major.WEARABLE -> false
            major == BluetoothClass.Device.Major.AUDIO_VIDEO -> true
            // Cheap speakers and car kits often report uncategorized/misc; stay permissive for them.
            major == BluetoothClass.Device.Major.UNCATEGORIZED ||
                major == BluetoothClass.Device.Major.MISC -> true
            major != null -> false
            else -> {
                val uuids: Array<ParcelUuid>? = device.uuids
                uuids == null || uuids.any { it.toString().lowercase() in AUDIO_UUIDS }
            }
        }
    } catch (e: SecurityException) {
        true
    }
}
