package com.retroradyo.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
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
    private lateinit var webView: WebView
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateKeepScreenOn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == ACTION_POWER_CONNECTED || isDeviceCharging()) {
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
        updateKeepScreenOn()
    }

    override fun onPause() {
        isVisibleToUser = false
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
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(android.app.NotificationManager::class.java).cancel(POWER_NOTIFICATION_ID)
        pendingPowerAction = START_RADIO_SCRIPT
        runPendingPowerAction()
    }

    private fun handlePowerDisconnected() {
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

    inner class WebAppInterface {
        @JavascriptInterface
        fun playStream(url: String, title: String) {
            dispatchPlayerAction { controller ->
                val mediaItem = MediaItem.fromUri(url)
                controller.setMediaItem(
                    mediaItem.buildUpon()
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                        .build()
                )
                controller.prepare()
                controller.play()
            }
        }

        @JavascriptInterface
        fun pauseStream() {
            dispatchPlayerAction { it.pause() }
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
        mediaController?.removeListener(playerListener)
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    companion object {
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
