package com.retroradyo.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : Activity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var pendingVolume: Float? = null
    private var pendingPlayerAction: ((MediaController) -> Unit)? = null
    private var pageReady = false
    private var pendingPowerAction: String? = null
    private var wasCharging = false
    private lateinit var webView: WebView
    private val powerConnectionReceiver = PowerConnectionReceiver()
    private var powerReceiverRegistered = false
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateKeepScreenOn()
            if (!::webView.isInitialized || !pageReady) return
            runOnUiThread {
                webView.evaluateJavascript("setPlaybackStateFromNative($isPlaying)", null)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val stationId = mediaItem?.mediaId ?: return
            if (!::webView.isInitialized || !pageReady) return
            runOnUiThread {
                webView.evaluateJavascript(
                    "selectStationFromNative(${org.json.JSONObject.quote(stationId)})",
                    null
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wasCharging = isDeviceCharging()
        if (intent?.action == ACTION_POWER_CONNECTED || wasCharging) {
            pendingPowerAction = START_RADIO_SCRIPT
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Android 13+ bildirim izni talebi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        try {
            val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
            
            controllerFuture?.addListener({
                try {
                    mediaController = controllerFuture?.get()
                    mediaController?.let { controller ->
                        controller.addListener(playerListener)
                        pendingVolume?.let { controller.volume = it }
                        pendingVolume = null
                        pendingPlayerAction?.invoke(controller)
                        pendingPlayerAction = null
                        syncPlaybackStateToWebView()
                        updateKeepScreenOn()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                    syncPlaybackStateToWebView()
                    runPendingPowerAction()
                }
            }
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent?.action) {
            ACTION_POWER_CONNECTED -> handlePowerConnected()
            ACTION_POWER_DISCONNECTED -> handlePowerDisconnected()
        }
    }

    override fun onResume() {
        super.onResume()
        isVisibleToUser = true
        val charging = isDeviceCharging()
        if (charging != wasCharging) {
            wasCharging = charging
            if (charging) handlePowerConnected() else handlePowerDisconnected()
        }
        if (!powerReceiverRegistered) {
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
        updateKeepScreenOn()
    }

    override fun onPause() {
        isVisibleToUser = false
        if (powerReceiverRegistered) {
            unregisterReceiver(powerConnectionReceiver)
            powerReceiverRegistered = false
        }
        super.onPause()
    }

    private fun isDeviceCharging(): Boolean {
        val batteryManager = getSystemService(BatteryManager::class.java)
        return batteryManager?.isCharging == true
    }

    private fun updateKeepScreenOn() {
        if (!::webView.isInitialized) return
        if (isDeviceCharging() || mediaController?.isPlaying == true) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun handlePowerConnected() {
        wasCharging = true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(android.app.NotificationManager::class.java).cancel(POWER_NOTIFICATION_ID)
        pendingPowerAction = START_RADIO_SCRIPT
        runPendingPowerAction()
    }

    private fun handlePowerDisconnected() {
        wasCharging = false
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            mediaController?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        pendingPowerAction = STOP_RADIO_SCRIPT
        runPendingPowerAction()
        stopService(Intent(this, PlaybackService::class.java))
        finish()
    }

    private fun runPendingPowerAction() {
        val script = pendingPowerAction
        if (!pageReady || script == null) return
        pendingPowerAction = null
        webView.evaluateJavascript(script, null)
    }

    private fun syncPlaybackStateToWebView() {
        val controller = mediaController ?: return
        if (!::webView.isInitialized || !pageReady) return
        webView.evaluateJavascript("setPlaybackStateFromNative(${controller.isPlaying})", null)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun playStream(stationsJson: String, stationId: String) {
            dispatchPlayerAction { controller ->
                setStationPlaylist(controller, stationsJson, stationId, startPlayback = true)
            }
        }

        @JavascriptInterface
        fun syncPlaylist(stationsJson: String, selectedStationId: String) {
            dispatchPlayerAction { controller ->
                setStationPlaylist(controller, stationsJson, selectedStationId, startPlayback = false)
            }
        }

        @JavascriptInterface
        fun getSelectedStationId(): String {
            return getSharedPreferences(PLAYBACK_PREFERENCES, MODE_PRIVATE)
                .getString(SELECTED_STATION_KEY, "")
                .orEmpty()
        }

        @JavascriptInterface
        fun pauseStream() {
            dispatchPlayerAction { it.pause() }
        }

        private fun setStationPlaylist(
            controller: MediaController,
            stationsJson: String,
            selectedStationId: String,
            startPlayback: Boolean
        ) {
            val mediaItems = buildStationMediaItems(stationsJson)
            if (mediaItems.isEmpty()) {
                if (startPlayback) controller.stop()
                return
            }

            val oldPlaybackState = controller.playbackState
            val oldPlayWhenReady = controller.playWhenReady
            val oldMediaId = controller.currentMediaItem?.mediaId
            val oldPosition = controller.currentPosition
            val currentIndex = mediaItems.indexOfFirst { it.mediaId == oldMediaId }
            val selectedIndex = mediaItems.indexOfFirst { it.mediaId == selectedStationId }
            val startIndex = when {
                selectedIndex >= 0 -> selectedIndex
                currentIndex >= 0 -> currentIndex
                else -> 0
            }
            val preserveCurrentPosition = !startPlayback && currentIndex == startIndex
            val startPosition = if (preserveCurrentPosition) oldPosition else C.TIME_UNSET

            controller.setMediaItems(mediaItems, startIndex, startPosition)
            val stationId = mediaItems[startIndex].mediaId
            getSharedPreferences(PLAYBACK_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(SELECTED_STATION_KEY, stationId)
                .apply()

            if (startPlayback || oldPlaybackState != Player.STATE_IDLE) {
                controller.prepare()
                if (startPlayback || oldPlayWhenReady) controller.play() else controller.pause()
            }
        }

        private fun buildStationMediaItems(stationsJson: String): List<MediaItem> {
            val stations = org.json.JSONArray(stationsJson)
            return buildList {
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
        }

        @JavascriptInterface
        fun stopStream() {
            dispatchPlayerAction { it.stop() }
        }

        @JavascriptInterface
        fun setVolume(volume: Float) {
            runOnUiThread {
                val normalizedVolume = volume.coerceIn(0f, 1f)
                val controller = mediaController
                if (controller != null) {
                    controller.volume = normalizedVolume
                } else {
                    pendingVolume = normalizedVolume
                }
            }
        }
    }

    private fun dispatchPlayerAction(action: (MediaController) -> Unit) {
        runOnUiThread {
            val controller = mediaController
            if (controller != null) {
                try {
                    action(controller)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                pendingPlayerAction = action
            }
        }
    }

    override fun onDestroy() {
        isVisibleToUser = false
        if (powerReceiverRegistered) {
            unregisterReceiver(powerConnectionReceiver)
            powerReceiverRegistered = false
        }
        mediaController?.removeListener(playerListener)
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    companion object {
        private const val PLAYBACK_PREFERENCES = "radio_playback"
        private const val SELECTED_STATION_KEY = "selected_station_id"

        @Volatile
        var isVisibleToUser: Boolean = false
            private set

        const val ACTION_POWER_CONNECTED = "com.retroradyo.app.POWER_CONNECTED"
        const val ACTION_POWER_DISCONNECTED = "com.retroradyo.app.POWER_DISCONNECTED"

        private const val START_RADIO_SCRIPT = "startRadioForPower()"
        private const val STOP_RADIO_SCRIPT = "stopRadio()"
        private const val POWER_NOTIFICATION_ID = 1001
    }
}
