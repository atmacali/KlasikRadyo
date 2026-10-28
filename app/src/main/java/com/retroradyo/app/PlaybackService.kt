package com.retroradyo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val powerConnectionReceiver = PowerConnectionReceiver().apply {
        playbackService = this@PlaybackService
    }
    private var powerReceiverRegistered = false
    private var isCharging = false
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { stationId ->
                getSharedPreferences(PLAYBACK_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putString(SELECTED_STATION_KEY, stationId)
                    .apply()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateMonitorNotification(isPlaying, startForeground = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isCharging = isPowerConnected()
        createMonitorNotificationChannel()
        updateMonitorNotification(isPlaying = false, startForeground = true)

        try {
            val player = ExoPlayer.Builder(this)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .build()

            player.repeatMode = Player.REPEAT_MODE_ALL
            player.addListener(playerListener)
            mediaSession = MediaSession.Builder(this, player).build()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(
            this,
            powerConnectionReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        powerReceiverRegistered = true
        if (isCharging) resumeForPowerConnection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the user-requested charging monitor alive when its UI task is dismissed.
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        updateMonitorNotification(session.player.isPlaying, startForeground = true)
    }

    internal fun resumeForPowerConnection() {
        mediaSession?.player?.let { player ->
            if (player.mediaItemCount == 0) restoreSavedPlaylist(player)
            if (player.mediaItemCount > 0) {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        }
    }

    internal fun stopForPowerDisconnection() {
        mediaSession?.player?.stop()
    }

    internal fun onPowerConnected() {
        isCharging = true
        updateMonitorNotification(mediaSession?.player?.isPlaying == true)
    }

    internal fun onPowerDisconnected() {
        isCharging = false
        updateMonitorNotification(isPlaying = false)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private fun isPowerConnected(): Boolean {
        val batteryState = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pluggedStatus = batteryState?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return pluggedStatus?.let { it != 0 }
            ?: (getSystemService(BatteryManager::class.java)?.isCharging == true)
    }

    private fun restoreSavedPlaylist(player: Player) {
        val preferences = getSharedPreferences(PLAYBACK_PREFERENCES, MODE_PRIVATE)
        val savedPlaylist = preferences.getString(STATION_PLAYLIST_KEY, null) ?: return
        val selectedStationId = preferences.getString(SELECTED_STATION_KEY, "").orEmpty()
        val stations = JSONArray(savedPlaylist)
        val mediaItems = buildList {
            for (index in 0 until stations.length()) {
                val station = stations.optJSONObject(index) ?: continue
                val id = station.optString("id")
                val name = station.optString("name")
                val url = station.optString("url")
                if (id.isBlank() || name.isBlank() || url.isBlank()) continue
                add(
                    MediaItem.Builder()
                        .setMediaId(id)
                        .setUri(url)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
                        .build()
                )
            }
        }
        if (mediaItems.isEmpty()) return

        val selectedIndex = mediaItems.indexOfFirst { it.mediaId == selectedStationId }
        val startIndex = selectedIndex.takeIf { it >= 0 } ?: 0
        player.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
    }

    private fun createMonitorNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                MONITOR_CHANNEL_ID,
                getString(R.string.monitor_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun updateMonitorNotification(isPlaying: Boolean, startForeground: Boolean = false) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_FROM_MONITOR
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            MONITOR_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val message = when {
            isPlaying -> getString(R.string.monitor_notification_playing)
            isCharging -> getString(R.string.monitor_notification_charging)
            else -> getString(R.string.monitor_notification_waiting)
        }
        val notification: Notification = NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(message)
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (startForeground) {
            ServiceCompat.startForeground(
                this,
                MONITOR_NOTIFICATION_ID,
                notification,
                foregroundServiceTypes(isPlaying)
            )
        } else {
            try {
                getSystemService(NotificationManager::class.java)
                    .notify(MONITOR_NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }

    private fun foregroundServiceTypes(isPlaying: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        val specialUse = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val mediaPlayback = if (isPlaying) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        return specialUse or mediaPlayback
    }

    override fun onDestroy() {
        if (powerReceiverRegistered) {
            unregisterReceiver(powerConnectionReceiver)
            powerReceiverRegistered = false
        }
        try {
            mediaSession?.run {
                player.removeListener(playerListener)
                player.release()
                release()
                mediaSession = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    private companion object {
        const val PLAYBACK_PREFERENCES = "radio_playback"
        const val SELECTED_STATION_KEY = "selected_station_id"
        const val STATION_PLAYLIST_KEY = "station_playlist_json"
        const val MONITOR_CHANNEL_ID = "charging_monitor"
        const val MONITOR_NOTIFICATION_ID = 2001
    }
}
