package com.retroradyo.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.ArrayDeque

class MainActivity : Activity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var pendingVolume: Float? = null
    private val pendingPlayerActions = ArrayDeque<(MediaController) -> Unit>()
    private var pageReady = false
    private var pendingPowerAction: String? = null
    private var wasCharging = false
    @Volatile
    private var pendingBluetoothPermissionRequest = false
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
        wasCharging = isPowerConnected()
        if ((intent?.action == ACTION_POWER_CONNECTED || wasCharging) &&
            (getAutomationPreference(AUTO_START_ON_CHARGE, true) ||
                getAutomationPreference(CHARGE_SCREENSAVER, false))
        ) {
            pendingPowerAction = powerConnectedScript()
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, PlaybackService::class.java).setAction(ACTION_START_CHARGING_MONITOR)
        )

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
                        while (pendingPlayerActions.isNotEmpty()) {
                            pendingPlayerActions.removeFirst().invoke(controller)
                        }
                        syncPlaybackStateToWebView()
                        syncVolumeToWebView()
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
                    syncVolumeToWebView()
                    syncAutomationSettingsToWebView()
                    if (pendingPowerAction != null) {
                        runPendingPowerAction()
                    } else {
                        resumeLastStationInWebView()
                    }
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
            ACTION_OPEN_FROM_MONITOR -> {
                if (isPowerConnected() && shouldHandleCharging()) {
                    handlePowerConnected()
                } else {
                    resumeLastStationInWebView()
                }
            }
            Intent.ACTION_MAIN -> {
                if (isPowerConnected() && shouldHandleCharging()) {
                    handlePowerConnected()
                } else {
                    resumeLastStationInWebView()
                }
            }
        }
    }

    @Deprecated("Deprecated in Android, retained for runtime Bluetooth permission result")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH_CONNECT) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (pendingBluetoothPermissionRequest && granted) {
            setAutomationPreference(AUTO_PLAY_ON_BLUETOOTH, true)
            refreshBluetoothMonitor()
        }
        pendingBluetoothPermissionRequest = false
        syncAutomationSettingsToWebView()
    }

    override fun onResume() {
        super.onResume()
        synchronized(foregroundActivityLock) {
            foregroundActivity = this
        }
        val charging = isPowerConnected()
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
        synchronized(foregroundActivityLock) {
            if (foregroundActivity === this) foregroundActivity = null
        }
        if (powerReceiverRegistered) {
            unregisterReceiver(powerConnectionReceiver)
            powerReceiverRegistered = false
        }
        super.onPause()
    }

    private fun isPowerConnected(): Boolean {
        val batteryState = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pluggedStatus = batteryState?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return pluggedStatus?.let { it != 0 }
            ?: (getSystemService(BatteryManager::class.java)?.isCharging == true)
    }

    private fun updateKeepScreenOn() {
        if (!::webView.isInitialized) return
        val keepAlways = getAutomationPreference(KEEP_SCREEN_ON_ALWAYS, false)
        if (keepAlways || (wasCharging && (getAutomationPreference(AUTO_START_ON_CHARGE, true) ||
                    getAutomationPreference(CHARGE_SCREENSAVER, false))) ||
            mediaController?.isPlaying == true
        ) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun handlePowerConnected() {
        wasCharging = true
        if (!shouldHandleCharging()) return
        if (getAutomationPreference(AUTO_START_ON_CHARGE, true) ||
            getAutomationPreference(CHARGE_SCREENSAVER, false)
        ) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        pendingPowerAction = powerConnectedScript()
        runPendingPowerAction()
    }

    private fun handlePowerDisconnected() {
        wasCharging = false
        updateKeepScreenOn()

        val stopAndClose = getAutomationPreference(STOP_ON_UNPLUG, true)
        val stopOnly = getAutomationPreference(STOP_ONLY_ON_UNPLUG, false)

        if (!stopAndClose && !stopOnly) {
            updateKeepScreenOn()
            return
        }

        try {
            mediaController?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        pendingPowerAction = STOP_RADIO_SCRIPT
        runPendingPowerAction()

        if (stopAndClose && !stopOnly) {
            finish()
        } else {
            updateKeepScreenOn()
        }
    }

    private fun shouldHandleCharging(): Boolean =
        getAutomationPreference(AUTO_START_ON_CHARGE, true) ||
            getAutomationPreference(CHARGE_SCREENSAVER, false)

    private fun powerConnectedScript(): String = buildList {
        if (getAutomationPreference(AUTO_START_ON_CHARGE, true)) {
            add(RESUME_LAST_STATION_SCRIPT)
        }
        if (getAutomationPreference(CHARGE_SCREENSAVER, false)) {
            add("toggleScreensaver(true)")
        }
    }.joinToString(";")

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

    private fun syncVolumeToWebView() {
        val controller = mediaController ?: return
        if (!::webView.isInitialized || !pageReady) return
        webView.evaluateJavascript(
            "setVolumeFromNative(${controller.volume.coerceIn(0f, 1f)})",
            null
        )
    }

    private fun resumeLastStationInWebView() {
        if (!::webView.isInitialized || !pageReady) return
        webView.evaluateJavascript(RESUME_LAST_STATION_SCRIPT, null)
    }

    private fun syncAutomationSettingsToWebView() {
        if (!::webView.isInitialized || !pageReady) return
        val autoStart = getAutomationPreference(AUTO_START_ON_CHARGE, true)
        val stopOnUnplug = getAutomationPreference(STOP_ON_UNPLUG, true)
        val bluetoothAutoPlay = isBluetoothAutoPlayEnabled()
        val chargeScreensaver = getAutomationPreference(CHARGE_SCREENSAVER, false)
        val keepScreenOnAlways = getAutomationPreference(KEEP_SCREEN_ON_ALWAYS, false)
        val stopOnlyOnUnplug = getAutomationPreference(STOP_ONLY_ON_UNPLUG, false)

        webView.evaluateJavascript(
            "applyAutomationSettingsFromNative($autoStart,$stopOnUnplug,$bluetoothAutoPlay,$chargeScreensaver,$keepScreenOnAlways,$stopOnlyOnUnplug)",
            null
        )
    }

    private fun refreshBluetoothMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasBluetoothConnectPermission()) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, PlaybackService::class.java)
                    .setAction(ACTION_REFRESH_AUTOMATION_RECEIVERS)
            )
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun isBluetoothAutoPlayEnabled(): Boolean =
        getAutomationPreference(AUTO_PLAY_ON_BLUETOOTH, false) && hasBluetoothConnectPermission()

    private fun getAutomationPreference(key: String, defaultValue: Boolean): Boolean =
        getSharedPreferences(AUTOMATION_PREFERENCES, MODE_PRIVATE)
            .getBoolean(key, defaultValue)

    private fun setAutomationPreference(key: String, enabled: Boolean) {
        getSharedPreferences(AUTOMATION_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(key, enabled)
            .apply()
    }

    private fun applyImmersive(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (enabled) {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, !enabled)
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun setImmersiveMode(enabled: Boolean) {
            runOnUiThread { applyImmersive(enabled) }
        }

        @JavascriptInterface
        fun getKeepScreenOnAlwaysEnabled(): Boolean =
            getAutomationPreference(KEEP_SCREEN_ON_ALWAYS, false)

        @JavascriptInterface
        fun setKeepScreenOnAlwaysEnabled(enabled: Boolean) {
            setAutomationPreference(KEEP_SCREEN_ON_ALWAYS, enabled)
            runOnUiThread { updateKeepScreenOn() }
        }

        @JavascriptInterface
        fun getStopOnlyOnUnplugEnabled(): Boolean =
            getAutomationPreference(STOP_ONLY_ON_UNPLUG, false)

        @JavascriptInterface
        fun setStopOnlyOnUnplugEnabled(enabled: Boolean) {
            setAutomationPreference(STOP_ONLY_ON_UNPLUG, enabled)
        }

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
        fun stopAndSyncPlaylist(stationsJson: String, selectedStationId: String) {
            dispatchPlayerAction { controller ->
                controller.pause()
                controller.stop()
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
        fun getSavedVolume(): Float =
            getSharedPreferences(PLAYBACK_PREFERENCES, MODE_PRIVATE)
                .getFloat(VOLUME_KEY, DEFAULT_VOLUME)
                .coerceIn(0f, 1f)

        @JavascriptInterface
        fun getAutoStartOnChargeEnabled(): Boolean =
            getAutomationPreference(AUTO_START_ON_CHARGE, true)

        @JavascriptInterface
        fun getStopOnUnplugEnabled(): Boolean =
            getAutomationPreference(STOP_ON_UNPLUG, true)

        @JavascriptInterface
        fun getBluetoothAutoPlayEnabled(): Boolean = isBluetoothAutoPlayEnabled()

        @JavascriptInterface
        fun getChargeScreensaverEnabled(): Boolean =
            getAutomationPreference(CHARGE_SCREENSAVER, false)

        @JavascriptInterface
        fun setAutoStartOnChargeEnabled(enabled: Boolean) {
            setAutomationPreference(AUTO_START_ON_CHARGE, enabled)
        }

        @JavascriptInterface
        fun setStopOnUnplugEnabled(enabled: Boolean) {
            setAutomationPreference(STOP_ON_UNPLUG, enabled)
        }

        @JavascriptInterface
        fun setChargeScreensaverEnabled(enabled: Boolean) {
            setAutomationPreference(CHARGE_SCREENSAVER, enabled)
            runOnUiThread {
                if (wasCharging && ::webView.isInitialized && pageReady) {
                    webView.evaluateJavascript("toggleScreensaver($enabled)", null)
                }
                updateKeepScreenOn()
            }
        }

        @JavascriptInterface
        fun setBluetoothAutoPlayEnabled(enabled: Boolean): Boolean {
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !hasBluetoothConnectPermission()
            ) {
                pendingBluetoothPermissionRequest = true
                runOnUiThread {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT),
                        REQUEST_BLUETOOTH_CONNECT
                    )
                }
                return false
            }
            setAutomationPreference(AUTO_PLAY_ON_BLUETOOTH, enabled)
            refreshBluetoothMonitor()
            return true
        }

        @JavascriptInterface
        fun resumeLastStation(stationsJson: String, stationId: String) {
            dispatchPlayerAction { controller ->
                if (!controller.isPlaying) {
                    setStationPlaylist(controller, stationsJson, stationId, startPlayback = true)
                }
            }
        }

        @JavascriptInterface
        fun registerStationForUpload(id: String, name: String, streamUrl: String): Boolean {
            val stationName = name.trim()
            val stationUri = runCatching { Uri.parse(streamUrl.trim()) }.getOrNull()
            if (!id.startsWith("custom-") || id.length > 100 || stationName.isBlank() ||
                stationName.length > 120 || stationName.any { Character.isISOControl(it) } ||
                stationUri == null || stationUri.scheme !in setOf("http", "https") ||
                stationUri.host.isNullOrBlank() || streamUrl.length > 2048 ||
                streamUrl.any { Character.isISOControl(it) || it.isWhitespace() }
            ) {
                return false
            }
            val preferences = getSharedPreferences(STATION_UPLOAD_PREFERENCES, MODE_PRIVATE)
            val pending = runCatching {
                org.json.JSONArray(preferences.getString(PENDING_STATION_UPLOADS_KEY, "[]"))
            }.getOrElse { org.json.JSONArray() }
            val existing = (0 until pending.length())
                .mapNotNull { pending.optJSONObject(it) }
                .firstOrNull { it.optString("id") == id }
            val updated = org.json.JSONArray()
            (0 until pending.length())
                .mapNotNull { pending.optJSONObject(it) }
                .filterNot { it.optString("id") == id }
                .forEach(updated::put)
            updated.put(
                org.json.JSONObject()
                    .put("id", id)
                    .put("name", stationName)
                    .put("url", streamUrl.trim())
                    .put("lastAttemptAt", existing?.optLong("lastAttemptAt", 0L) ?: 0L)
            )
            preferences.edit().putString(PENDING_STATION_UPLOADS_KEY, updated.toString()).apply()
            return true
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
                controller.clearMediaItems()
                getSharedPreferences(PLAYBACK_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putString(SELECTED_STATION_KEY, "")
                    .putString(STATION_PLAYLIST_KEY, "[]")
                    .apply()
                return
            }

            val storedPlaylist = getSharedPreferences(PLAYBACK_PREFERENCES, MODE_PRIVATE)
                .getString(STATION_PLAYLIST_KEY, null)
            if (controller.isPlaying &&
                controller.currentMediaItem?.mediaId == selectedStationId &&
                storedPlaylist == stationsJson
            ) {
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
                .putString(STATION_PLAYLIST_KEY, stationsJson)
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
                    val mediaItemBuilder = MediaItem.Builder()
                        .setMediaId(id)
                        .setUri(url)
                    if (Uri.parse(url).lastPathSegment?.endsWith(".m3u8", ignoreCase = true) == true) {
                        mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                    }
                    add(
                        mediaItemBuilder
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
                getSharedPreferences(PLAYBACK_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putFloat(VOLUME_KEY, normalizedVolume)
                    .apply()
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
                pendingPlayerActions.addLast(action)
            }
        }
    }

    override fun onDestroy() {
        synchronized(foregroundActivityLock) {
            if (foregroundActivity === this) foregroundActivity = null
        }
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
        private const val STATION_PLAYLIST_KEY = "station_playlist_json"
        private const val VOLUME_KEY = "volume"
        private const val DEFAULT_VOLUME = 0.7f
        const val STATION_UPLOAD_PREFERENCES = "station_upload_state"
        const val PENDING_STATION_UPLOADS_KEY = "pending_station_uploads"
        const val SENT_STATION_FINGERPRINTS_KEY = "sent_station_fingerprints"
        private const val AUTOMATION_PREFERENCES = "radio_automation"
        private const val AUTO_START_ON_CHARGE = "auto_start_on_charge"
        private const val STOP_ON_UNPLUG = "stop_on_unplug"
        private const val AUTO_PLAY_ON_BLUETOOTH = "auto_play_on_bluetooth"
        private const val CHARGE_SCREENSAVER = "charge_screensaver"
        private const val REQUEST_BLUETOOTH_CONNECT = 102
        private const val KEEP_SCREEN_ON_ALWAYS = "keep_screen_on_always"
        private const val STOP_ONLY_ON_UNPLUG = "stop_only_on_unplug"

        private val foregroundActivityLock = Any()
        @Volatile
        private var foregroundActivity: MainActivity? = null

        fun dispatchPowerActionToVisibleActivity(action: String): Boolean {
            val activity = synchronized(foregroundActivityLock) { foregroundActivity } ?: return false
            if (activity.isFinishing || activity.isDestroyed) {
                return false
            }

            val handleAction = Runnable {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    when (action) {
                        ACTION_POWER_CONNECTED -> activity.handlePowerConnected()
                        ACTION_POWER_DISCONNECTED -> activity.handlePowerDisconnected()
                        else -> Unit
                    }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                handleAction.run()
            } else {
                activity.runOnUiThread(handleAction)
            }
            return true
        }

        fun dispatchStationUploadStatus(message: String, isError: Boolean) {
            val activity = synchronized(foregroundActivityLock) { foregroundActivity } ?: return
            if (activity.isFinishing || activity.isDestroyed) return
            val script = "showStationUploadStatus(" +
                "${org.json.JSONObject.quote(message)},$isError)"
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed && activity.pageReady) {
                    activity.webView.evaluateJavascript(script, null)
                }
            }
        }

        const val ACTION_POWER_CONNECTED = "com.retroradyo.app.POWER_CONNECTED"
        const val ACTION_POWER_DISCONNECTED = "com.retroradyo.app.POWER_DISCONNECTED"
        const val ACTION_OPEN_FROM_MONITOR = "com.retroradyo.app.OPEN_FROM_MONITOR"
        const val ACTION_START_CHARGING_MONITOR = "com.retroradyo.app.START_CHARGING_MONITOR"
        const val ACTION_REFRESH_AUTOMATION_RECEIVERS = "com.retroradyo.app.REFRESH_AUTOMATION_RECEIVERS"

        private const val STOP_RADIO_SCRIPT = "stopRadio()"
        private const val RESUME_LAST_STATION_SCRIPT = "resumeLastStationOnLaunch()"
    }
}
