package com.retroradyo.app

import android.app.Activity
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : Activity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var pendingVolume: Float? = null
    private var pendingPlayerAction: ((MediaController) -> Unit)? = null
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                        pendingVolume?.let { controller.volume = it }
                        pendingVolume = null
                        pendingPlayerAction?.invoke(controller)
                        pendingPlayerAction = null
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
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)
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
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
