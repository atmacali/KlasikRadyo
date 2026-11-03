package com.retroradyo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val powerConnectionReceiver = PowerConnectionReceiver().apply {
        playbackService = this@PlaybackService
    }
    private var powerReceiverRegistered = false
    private var bluetoothReceiverRegistered = false
    private var isCharging = false
    private var notificationStatusMessage: String? = null
    private var notificationStatusGeneration = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectedBluetoothAddresses = mutableSetOf<String>()
    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            device?.address?.let { address ->
                connectedBluetoothAddresses.remove(address)
                eligibleBluetoothAddresses.remove(address)
                endScreensaverIfNoBluetooth()
                if (bluetoothAutoPlayDevices.remove(address)) stopPlayback()
                stopIfChargeBluetoothLost(excludedAddress = address)
            }
            return
        }
        if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val address = device?.address
            address?.let { connectedBluetoothAddresses.add(it) }
            closeScreensaverAfterBluetoothLoss = false
            if (address != null && BluetoothDeviceFilter.isEligible(context, device)) {
                eligibleBluetoothAddresses.add(address)
            }
            if (isBluetoothAutoPlayEnabled() && BluetoothDeviceFilter.isEligible(context, device)) {
                address?.let { bluetoothAutoPlayDevices.add(it) }
                resumeLastStation()
                scheduleAutoPlayRetries {
                    isBluetoothAutoPlayEnabled() && (address == null || address in connectedBluetoothAddresses)
                }
            }
            maybeAutoPlayForChargeAndBluetooth()

            if (isBluetoothAutoOpenEnabled() && BluetoothDeviceFilter.isEligible(context, device)) {
                ScreensaverLauncher.launch(context.applicationContext)
            }
        }
    }
}
    private val bluetoothAutoPlayDevices = mutableSetOf<String>()
    private val eligibleBluetoothAddresses = mutableSetOf<String>()
    private var screensaverOpenedByPlayback = false
    private val screensaverCloseRunnable: Runnable = Runnable {
        if (shouldCloseScreensaver() && (closeScreensaverAfterBluetoothLoss || mediaSession?.player?.isPlaying != true)) {
            screensaverOpenedByPlayback = false
            closeScreensaverAfterBluetoothLoss = false
            MainActivity.dispatchScreensaverOff()
        }
    }

    private fun shouldCloseScreensaver(): Boolean = screensaverOpenedByPlayback || isCarModeEnabled()

    // Opens AMOLED mode when playback starts over an eligible Bluetooth device and locks the
    // screen (after a short debounce for rebuffering) when playback stops.
    private fun handleScreensaverForPlayback(isPlaying: Boolean) {
        mainHandler.removeCallbacks(screensaverCloseRunnable)
        if (isPlaying) {
            val repeating = shouldRepeatScreensaver()
            if (repeating ||
                (isBluetoothPlayScreensaverEnabled() && !screensaverOpenedByPlayback && hasEligibleBluetoothConnection())
            ) {
                screensaverOpenedByPlayback = true
                ScreensaverLauncher.launch(applicationContext)
            }
            startScreensaverRepeat()
        } else {
            mainHandler.removeCallbacks(screensaverRepeatRunnable)
            if (shouldCloseScreensaver()) {
                mainHandler.postDelayed(screensaverCloseRunnable, SCREENSAVER_CLOSE_DELAY_MS)
            }
        }
    }

    // While charging and connected to a selected device, brings AMOLED mode back periodically
    // so it returns even after the user left it to use the phone.
    private val screensaverRepeatRunnable: Runnable = Runnable {
        if (!shouldRepeatScreensaver() || mediaSession?.player?.isPlaying != true) return@Runnable
        screensaverOpenedByPlayback = true
        ScreensaverLauncher.launch(applicationContext)
        mainHandler.postDelayed(screensaverRepeatRunnable, SCREENSAVER_REPEAT_MS)
    }

    private fun shouldRepeatScreensaver(): Boolean =
        isCarModeEnabled() || (isChargeBluetoothScreensaverRepeatEnabled() && chargeBluetoothConditionMet())

    private fun isCarModeEnabled(): Boolean = getAutomationPreference(CAR_MODE, false)

    private fun startScreensaverRepeat() {
        mainHandler.removeCallbacks(screensaverRepeatRunnable)
        if (shouldRepeatScreensaver()) {
            mainHandler.postDelayed(screensaverRepeatRunnable, SCREENSAVER_REPEAT_MS)
        }
    }

    private fun isChargeBluetoothScreensaverRepeatEnabled(): Boolean =
        getAutomationPreference(CHARGE_BLUETOOTH_SCREENSAVER_REPEAT, false)

    // The Bluetooth condition itself is gone: end AMOLED mode and lock even if the music keeps playing.
    private fun endScreensaverIfNoBluetooth() {
        if (eligibleBluetoothAddresses.isNotEmpty() || !screensaverOpenedByPlayback) return
        mainHandler.removeCallbacks(screensaverRepeatRunnable)
        mainHandler.removeCallbacks(screensaverCloseRunnable)
        mainHandler.postDelayed(screensaverCloseRunnable, SCREENSAVER_CLOSE_DELAY_MS)
        closeScreensaverAfterBluetoothLoss = true
    }
    private var closeScreensaverAfterBluetoothLoss = false

    private fun hasEligibleBluetoothConnection(): Boolean {
        if (eligibleBluetoothAddresses.isNotEmpty()) return true
        val excluded = BluetoothDeviceFilter.excludedAddresses(this)
        return currentlyConnectedBluetoothAddresses().any { it !in excluded && it !in connectedBluetoothAddresses }
    }

    private fun isBluetoothPlayScreensaverEnabled(): Boolean {
        if (!getAutomationPreference(BLUETOOTH_PLAY_SCREENSAVER, false)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
    private var chargeBluetoothActive = false
    private var retryAttempt = 0
    private var retryCondition: (() -> Boolean)? = null
    private val retryRunnable: Runnable = Runnable {
        val condition = retryCondition ?: return@Runnable
        val player = mediaSession?.player
        if (player == null || player.isPlaying || !condition()) {
            retryCondition = null
            return@Runnable
        }
        resumeLastStation()
        retryAttempt++
        if (retryAttempt < AUTO_PLAY_RETRY_DELAYS_MS.size) {
            mainHandler.postDelayed(retryRunnable, AUTO_PLAY_RETRY_DELAYS_MS[retryAttempt])
        } else {
            retryCondition = null
        }
    }
    private val chargeLossRunnable = Runnable {
        if (!isPowerConnected()) {
            isCharging = false
            stopIfChargeBluetoothLost()
        }
    }

    // Car head units set up A2DP seconds after ACL connects, so the first play() can be lost.
    private fun scheduleAutoPlayRetries(condition: () -> Boolean) {
        mainHandler.removeCallbacks(retryRunnable)
        retryCondition = condition
        retryAttempt = 0
        mainHandler.postDelayed(retryRunnable, AUTO_PLAY_RETRY_DELAYS_MS[0])
    }

    private fun stopPlayback() {
        mainHandler.removeCallbacks(retryRunnable)
        retryCondition = null
        mediaSession?.player?.let {
            it.stop()
            updateMonitorNotification(isPlaying = false)
        }
    }

    private fun chargeBluetoothConditionMet(excludedAddress: String? = null): Boolean {
        if (!isChargeBluetoothPlayEnabled() || !isCharging) return false
        val selected = getSharedPreferences(AUTOMATION_PREFERENCES, MODE_PRIVATE)
            .getStringSet(CHARGE_BLUETOOTH_DEVICES, emptySet()).orEmpty()
        val connected = currentlyConnectedBluetoothAddresses()
            .filterNot { it.equals(excludedAddress, ignoreCase = true) }
        return selected.any { saved -> connected.any { it.equals(saved, ignoreCase = true) } }
    }

    // The charge + Bluetooth rule requires both conditions; losing either one ends playback it started.
    private fun stopIfChargeBluetoothLost(excludedAddress: String? = null) {
        if (!chargeBluetoothActive) return
        if (chargeBluetoothConditionMet(excludedAddress)) return
        chargeBluetoothActive = false
        stopPlayback()
    }
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { stationId ->
                getSharedPreferences(PLAYBACK_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putString(SELECTED_STATION_KEY, stationId)
                    .apply()
            }
            // --- YENİ EKLENEN KISIM ---
            // Bluetooth kulaklık donanım tuşlarıyla (veya atlama ile) kanal değiştiğinde
            // radyo donmuş veya durmuşsa tekrar çalmaya zorlar.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                mediaSession?.player?.let { player ->
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                    }
                    player.play()
                }
            }
            // --------------------------
            if (mediaSession?.player?.isPlaying == true) {
                submitCurrentStationIfPending()
            }
            updateMonitorNotification(mediaSession?.player?.isPlaying == true)
        }

        // A live radio paused for a while (phone call, focus loss) would otherwise resume from old buffered audio.
        private var pausedAtMs = 0L

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val player = mediaSession?.player ?: return
            if (!playWhenReady) {
                pausedAtMs = android.os.SystemClock.elapsedRealtime()
            } else if (pausedAtMs != 0L) {
                val pausedFor = android.os.SystemClock.elapsedRealtime() - pausedAtMs
                pausedAtMs = 0L
                if (pausedFor > STALE_PAUSE_MS) {
                    mainHandler.post {
                        player.stop()
                        player.prepare()
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateMonitorNotification(isPlaying, startForeground = true)
            if (isPlaying) submitCurrentStationIfPending()
            handleScreensaverForPlayback(isPlaying)
        }

        override fun onVolumeChanged(volume: Float) {
            getSharedPreferences(PLAYBACK_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putFloat(VOLUME_KEY, volume.coerceIn(0f, 1f))
                .apply()
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

            player.volume = getSharedPreferences(PLAYBACK_PREFERENCES, MODE_PRIVATE)
                .getFloat(VOLUME_KEY, DEFAULT_VOLUME)
                .coerceIn(0f, 1f)
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
        refreshBluetoothReceiver()
        if (isCharging) resumeForPowerConnection()
        maybeAutoPlayForChargeAndBluetooth()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_NOTIFICATION_TOGGLE -> mediaSession?.player?.let { player ->
                ensurePlaylistLoaded(player)
                if (player.isPlaying) {
                    player.pause()
                } else if (player.mediaItemCount > 0) {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
                updateMonitorNotification(player.isPlaying)
            }
            ACTION_NOTIFICATION_STOP -> mediaSession?.player?.let { player ->
                player.stop()
                updateMonitorNotification(isPlaying = false)
            }
// --- GÜNCELLENEN KISIM ---
            ACTION_NOTIFICATION_PREVIOUS -> mediaSession?.player?.let { player ->
                ensurePlaylistLoaded(player)
                if (player.mediaItemCount > 1) {
                    player.seekToPreviousMediaItem()
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
            }
            ACTION_NOTIFICATION_NEXT -> mediaSession?.player?.let { player ->
                ensurePlaylistLoaded(player)
                if (player.mediaItemCount > 1) {
                    player.seekToNextMediaItem()
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
            }
            // -------------------------
            MainActivity.ACTION_REFRESH_AUTOMATION_RECEIVERS -> refreshBluetoothReceiver()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the user-requested charging monitor alive when its UI task is dismissed.
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        updateMonitorNotification(session.player.isPlaying, startForeground = true)
    }

    internal fun resumeForPowerConnection() {
        if (!getAutomationPreference(AUTO_START_ON_CHARGE, true)) return
        resumeLastStation()
    }

    private fun resumeLastStation() {
        mediaSession?.player?.let { player ->
            if (player.isPlaying) return
            if (player.mediaItemCount == 0) restoreSavedPlaylist(player)
            if (player.mediaItemCount > 0) {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        }
    }

    internal fun stopForPowerDisconnection() {
        if (getAutomationPreference(STOP_ON_UNPLUG, true) || getAutomationPreference(STOP_ONLY_ON_UNPLUG, false)) {
            mediaSession?.player?.stop()
        }
    }

    internal fun shouldStopOnPowerDisconnection(): Boolean =
        getAutomationPreference(STOP_ON_UNPLUG, true) || getAutomationPreference(STOP_ONLY_ON_UNPLUG, false)

    internal fun onPowerConnected() {
        isCharging = true
        mainHandler.removeCallbacks(chargeLossRunnable)
        maybeAutoPlayForChargeAndBluetooth()
        updateMonitorNotification(mediaSession?.player?.isPlaying == true)
    }

    internal fun onPowerDisconnected() {
        isCharging = false
        // Car USB power can flicker briefly (ignition/cranking), so confirm the loss before stopping.
        mainHandler.removeCallbacks(chargeLossRunnable)
        if (chargeBluetoothActive) mainHandler.postDelayed(chargeLossRunnable, CHARGE_LOSS_GRACE_MS)
        updateMonitorNotification(isPlaying = mediaSession?.player?.isPlaying == true)
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

    private fun isChargeBluetoothPlayEnabled(): Boolean {
        if (!getAutomationPreference(CHARGE_BLUETOOTH_PLAY, false)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun currentlyConnectedBluetoothAddresses(): Set<String> {
        val result = connectedBluetoothAddresses.toMutableSet()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getSystemService(android.media.AudioManager::class.java)
                ?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                ?.filter {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                ?.forEach { result.add(it.address) }
        }
        return result
    }

    // Plays only while charging and connected to one of the user-selected devices.
    private fun maybeAutoPlayForChargeAndBluetooth() {
        if (!chargeBluetoothConditionMet()) return
        chargeBluetoothActive = true
        resumeLastStation()
        scheduleAutoPlayRetries { chargeBluetoothConditionMet() }
    }

    private fun refreshBluetoothReceiver() {
    val shouldRegister = isBluetoothAutoPlayEnabled() || isBluetoothAutoOpenEnabled() || isChargeBluetoothPlayEnabled() || isBluetoothPlayScreensaverEnabled()
    if (shouldRegister && !bluetoothReceiverRegistered) {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, bluetoothReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        bluetoothReceiverRegistered = true
    } else if (!shouldRegister && bluetoothReceiverRegistered) {
        unregisterReceiver(bluetoothReceiver)
        bluetoothReceiverRegistered = false
    }
}

    private fun isBluetoothAutoPlayEnabled(): Boolean {
        if (!getAutomationPreference(AUTO_PLAY_ON_BLUETOOTH, false)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }
    private fun isBluetoothAutoOpenEnabled(): Boolean {
    if (!getAutomationPreference(AUTO_OPEN_ON_BLUETOOTH, false)) return false
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

    private fun getAutomationPreference(key: String, defaultValue: Boolean): Boolean =
        getSharedPreferences(AUTOMATION_PREFERENCES, MODE_PRIVATE)
            .getBoolean(key, defaultValue)

    private fun ensurePlaylistLoaded(player: Player) {
        if (player.mediaItemCount == 0) {
            runCatching { restoreSavedPlaylist(player) }.onFailure { it.printStackTrace() }
        }
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

    private fun submitCurrentStationIfPending() {
        val currentMediaId = mediaSession?.player?.currentMediaItem?.mediaId ?: return
        val preferences = getSharedPreferences(
            MainActivity.STATION_UPLOAD_PREFERENCES,
            MODE_PRIVATE
        )
        val pending = runCatching {
            JSONArray(preferences.getString(MainActivity.PENDING_STATION_UPLOADS_KEY, "[]"))
        }.getOrElse { return }
        val index = (0 until pending.length()).firstOrNull {
            pending.optJSONObject(it)?.optString("id") == currentMediaId
        } ?: return
        val candidate = pending.optJSONObject(index) ?: return
        val stationName = candidate.optString("name").trim()
        val streamUrl = candidate.optString("url").trim()
        if (stationName.isBlank() || streamUrl.isBlank()) return

        val fingerprint = stationFingerprint(stationName, streamUrl)
        val sent = runCatching {
            JSONArray(preferences.getString(MainActivity.SENT_STATION_FINGERPRINTS_KEY, "[]"))
        }.getOrElse { JSONArray() }
        if ((0 until sent.length()).any { sent.optString(it) == fingerprint }) {
            removePendingUpload(preferences, currentMediaId)
            return
        }

        val now = System.currentTimeMillis()
        val lastAttemptAt = candidate.optLong("lastAttemptAt", 0L)
        if (now - lastAttemptAt < UPLOAD_RETRY_DELAY_MS) return
        candidate.put("lastAttemptAt", now)
        preferences.edit().putString(MainActivity.PENDING_STATION_UPLOADS_KEY, pending.toString())
            .apply()

        Thread({
            var connection: HttpURLConnection? = null
            try {
                connection = URL(STATION_SUGGESTION_ENDPOINT).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = UPLOAD_TIMEOUT_MS
                connection.readTimeout = UPLOAD_TIMEOUT_MS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val requestBody = JSONObject()
                    .put("stationName", stationName)
                    .put("streamUrl", streamUrl)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(requestBody.size)
                connection.outputStream.use { it.write(requestBody) }

                val statusCode = connection.responseCode
                val responseStream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val responseText = responseStream?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(MAX_RESPONSE_CHARS)
                    val count = reader.read(buffer)
                    if (count > 0) String(buffer, 0, count) else ""
                }.orEmpty()
                val response = runCatching { JSONObject(responseText) }.getOrNull()
                val success = statusCode == 201 && response?.optBoolean("ok") == true
                val message = if (success) {
                    "Kanal ilk kez çaldı; adı ve adresi herkese açık inceleme dosyasına eklendi."
                } else {
                    val base = response?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: "Kanal paylaşımı başarısız oldu."
                    val github = response?.optInt("githubStatus", 0) ?: 0
                    val detail = if (github > 0) " GitHub HTTP $github." else ""
                    "$base (HTTP $statusCode).$detail Yeniden denenecek."
                }
                mainHandler.post {
                    completeStationUpload(
                        currentMediaId,
                        fingerprint,
                        success,
                        message
                    )
                }
            } catch (error: Exception) {
                val message = "Kanal paylaşımı başarısız oldu: " +
                    "${error.message ?: "ağ hatası"}. Yeniden denenecek."
                mainHandler.post {
                    completeStationUpload(
                        currentMediaId,
                        fingerprint,
                        false,
                        message
                    )
                }
            } finally {
                connection?.disconnect()
            }
        }, "station-upload").apply {
            isDaemon = true
            start()
        }
    }

    private fun completeStationUpload(
        stationId: String,
        fingerprint: String,
        success: Boolean,
        message: String
    ) {
        val preferences = getSharedPreferences(
            MainActivity.STATION_UPLOAD_PREFERENCES,
            MODE_PRIVATE
        )
        if (success) {
            removePendingUpload(preferences, stationId)
            val sent = runCatching {
                JSONArray(preferences.getString(MainActivity.SENT_STATION_FINGERPRINTS_KEY, "[]"))
            }.getOrElse { JSONArray() }
            if ((0 until sent.length()).none { sent.optString(it) == fingerprint }) {
                sent.put(fingerprint)
            }
            preferences.edit()
                .putString(MainActivity.SENT_STATION_FINGERPRINTS_KEY, sent.toString())
                .apply()
        }
        notificationStatusMessage = message
        val generation = ++notificationStatusGeneration
        updateMonitorNotification(mediaSession?.player?.isPlaying == true)
        MainActivity.dispatchStationUploadStatus(message, !success)
        mainHandler.postDelayed({
            if (notificationStatusGeneration == generation) {
                notificationStatusMessage = null
                updateMonitorNotification(mediaSession?.player?.isPlaying == true)
            }
        }, NOTIFICATION_STATUS_DURATION_MS)
    }

    private fun removePendingUpload(preferences: android.content.SharedPreferences, stationId: String) {
        val pending = runCatching {
            JSONArray(preferences.getString(MainActivity.PENDING_STATION_UPLOADS_KEY, "[]"))
        }.getOrElse { return }
        val remaining = JSONArray()
        (0 until pending.length())
            .mapNotNull { pending.optJSONObject(it) }
            .filterNot { it.optString("id") == stationId }
            .forEach(remaining::put)
        preferences.edit().putString(MainActivity.PENDING_STATION_UPLOADS_KEY, remaining.toString())
            .apply()
    }

    private fun stationFingerprint(name: String, url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$name\n$url".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun getPlaceholderAlbumArt(): android.graphics.Bitmap? {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_stat_icon) ?: return null
        val size = 256
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#1a1a1a")
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        
        val iconSize = 144
        val offset = (size - iconSize) / 2
        drawable.setBounds(offset, offset, offset + iconSize, offset + iconSize)
        drawable.draw(canvas)
        
        return bitmap
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
        val message = notificationStatusMessage ?: when {
            isPlaying -> getString(R.string.monitor_notification_playing)
            isCharging && getAutomationPreference(AUTO_START_ON_CHARGE, true) ->
                getString(R.string.monitor_notification_charging)
            isCharging && getAutomationPreference(CHARGE_SCREENSAVER, false) ->
                getString(R.string.monitor_notification_screensaver)
            isCharging -> getString(R.string.monitor_notification_charge_disabled)
            else -> getString(R.string.monitor_notification_waiting)
        }
        val player = mediaSession?.player
        val notificationBuilder = NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_icon)
            .setLargeIcon(getPlaceholderAlbumArt())
            .setContentTitle(
                player?.currentMediaItem?.mediaMetadata?.title?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.monitor_notification_title)
            )
            .setContentText(message)
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(notificationAction(
                android.R.drawable.ic_media_previous,
                "Önceki kanal",
                ACTION_NOTIFICATION_PREVIOUS,
                NOTIFICATION_PREVIOUS_REQUEST
            ))
            .addAction(notificationAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Duraklat" else "Oynat",
                ACTION_NOTIFICATION_TOGGLE,
                NOTIFICATION_TOGGLE_REQUEST
            ))
            .addAction(notificationAction(
                android.R.drawable.ic_media_next,
                "Sonraki kanal",
                ACTION_NOTIFICATION_NEXT,
                NOTIFICATION_NEXT_REQUEST
            ))
            .addAction(notificationAction(
                R.drawable.ic_notification_stop,
                "Durdur",
                ACTION_NOTIFICATION_STOP,
                NOTIFICATION_STOP_REQUEST
            ))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionCompatToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        val notification: Notification = notificationBuilder.build()

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

    private fun notificationAction(
        icon: Int,
        label: String,
        action: String,
        requestCode: Int
    ): NotificationCompat.Action {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        val pendingIntent = PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(icon, label, pendingIntent).build()
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
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(chargeLossRunnable)
        mainHandler.removeCallbacks(screensaverCloseRunnable)
        mainHandler.removeCallbacks(screensaverRepeatRunnable)
        if (shouldCloseScreensaver()) {
            screensaverOpenedByPlayback = false
            MainActivity.dispatchScreensaverOff()
        }
        if (bluetoothReceiverRegistered) {
            unregisterReceiver(bluetoothReceiver)
            bluetoothReceiverRegistered = false
        }
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
        val AUTO_PLAY_RETRY_DELAYS_MS = longArrayOf(2_000L, 4_000L, 6_000L)
        const val CHARGE_LOSS_GRACE_MS = 4_000L
        const val PLAYBACK_PREFERENCES = "radio_playback"
        const val SELECTED_STATION_KEY = "selected_station_id"
        const val STATION_PLAYLIST_KEY = "station_playlist_json"
        const val VOLUME_KEY = "volume"
        const val DEFAULT_VOLUME = 0.7f
        const val MONITOR_CHANNEL_ID = "charging_monitor"
        const val MONITOR_NOTIFICATION_ID = 2001
        const val ACTION_NOTIFICATION_PREVIOUS = "com.retroradyo.action.NOTIFICATION_PREVIOUS"
        const val ACTION_NOTIFICATION_TOGGLE = "com.retroradyo.action.NOTIFICATION_TOGGLE"
        const val ACTION_NOTIFICATION_NEXT = "com.retroradyo.action.NOTIFICATION_NEXT"
        const val ACTION_NOTIFICATION_STOP = "com.retroradyo.action.NOTIFICATION_STOP"
        const val NOTIFICATION_PREVIOUS_REQUEST = 2011
        const val NOTIFICATION_TOGGLE_REQUEST = 2012
        const val NOTIFICATION_NEXT_REQUEST = 2013
        const val NOTIFICATION_STOP_REQUEST = 2014
        const val AUTOMATION_PREFERENCES = "radio_automation"
        const val AUTO_START_ON_CHARGE = "auto_start_on_charge"
        const val STOP_ON_UNPLUG = "stop_on_unplug"
        const val AUTO_PLAY_ON_BLUETOOTH = "auto_play_on_bluetooth"
        const val AUTO_OPEN_ON_BLUETOOTH = "auto_open_on_bluetooth"
        const val BLUETOOTH_PLAY_SCREENSAVER = "bluetooth_play_screensaver"
        const val CHARGE_BLUETOOTH_SCREENSAVER_REPEAT = "charge_bluetooth_screensaver_repeat"
        const val CAR_MODE = "car_mode"
        const val SCREENSAVER_REPEAT_MS = 60 * 1000L
        const val STALE_PAUSE_MS = 15_000L
        const val SCREENSAVER_CLOSE_DELAY_MS = 3_000L
        const val CHARGE_BLUETOOTH_PLAY = "charge_bluetooth_play"
        const val CHARGE_BLUETOOTH_DEVICES = "charge_bluetooth_devices"
        const val CHARGE_SCREENSAVER = "charge_screensaver"
        const val STOP_ONLY_ON_UNPLUG = "stop_only_on_unplug"
        private const val STATION_SUGGESTION_ENDPOINT =
            "https://retroradyo-station-suggestions.klasikradyo-ali.workers.dev/submit"
        private const val UPLOAD_TIMEOUT_MS = 10_000
        private const val MAX_RESPONSE_CHARS = 8_192
        private const val UPLOAD_RETRY_DELAY_MS = 5 * 60 * 1_000L
        private const val NOTIFICATION_STATUS_DURATION_MS = 15_000L
    }
}
