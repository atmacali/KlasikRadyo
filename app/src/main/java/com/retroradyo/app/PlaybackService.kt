package com.retroradyo.app

import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val powerConnectionReceiver = PowerConnectionReceiver().apply {
        playbackService = this@PlaybackService
    }
    private var powerReceiverRegistered = false
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { stationId ->
                getSharedPreferences(PLAYBACK_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putString(SELECTED_STATION_KEY, stationId)
                    .apply()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
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
    }

    internal fun resumeForPowerConnection() {
        mediaSession?.player?.let { player ->
            if (player.mediaItemCount > 0) player.play()
        }
    }

    internal fun stopForPowerDisconnection() {
        mediaSession?.player?.stop()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
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
    }
}
