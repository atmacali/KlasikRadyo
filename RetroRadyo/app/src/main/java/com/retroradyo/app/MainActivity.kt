package com.retroradyo.app

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.setPadding
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

data class Station(val name: String, val url: String, var favorite: Boolean = false)

class MainActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var status: TextView
    private lateinit var stationBox: LinearLayout
    private lateinit var playButton: Button
    private val stations = mutableListOf(
        Station("TRT FM", "https://trt.radyotvonline.net/trtfm"),
        Station("SomaFM Groove Salad", "https://ice.somafm.com/groovesalad"),
        Station("SomaFM Secret Agent", "https://ice.somafm.com/secretagent"),
        Station("SomaFM Drone Zone", "https://ice.somafm.com/dronezone")
    )
    private var selected = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
             View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)

        player = ExoPlayer.Builder(this).build()
        buildUi()
        renderStations()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton.text = if (isPlaying) "Ⅱ  DURAKLAT" else "▶  ÇAL"
                status.text = if (isPlaying) "Şimdi çalıyor" else "Hazır"
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                status.text = "Yayın açılamadı. Başka bir istasyon deneyin."
            }
        })
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF101216.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "RETRO RADYO"
            textSize = 14f; letterSpacing = .24f
            setTextColor(0xFF9DA7B3.toInt()); gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            textSize = 46f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        })
        status = TextView(this).apply {
            text = "Bir istasyon seç"; textSize = 16f
            setTextColor(0xFFB8C1CC.toInt()); gravity = Gravity.CENTER
        }
        root.addView(status)
        playButton = Button(this).apply {
            text = "▶  ÇAL"
            setOnClickListener {
                if (selected < 0) {
                    Toast.makeText(this@MainActivity, "Önce bir istasyon seç", Toast.LENGTH_SHORT).show()
                } else if (player.isPlaying) player.pause() else player.play()
            }
        }
        root.addView(playButton)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        controls.addView(Button(this).apply { text = "＋ İSTASYON EKLE"; setOnClickListener { addStationDialog() } })
        controls.addView(Button(this).apply { text = "DURDUR"; setOnClickListener { player.stop(); status.text = "Durduruldu" } })
        root.addView(controls)
        root.addView(TextView(this).apply {
            text = "İSTASYONLAR"; textSize = 13f
            setTextColor(0xFF9DA7B3.toInt()); setPadding(0, 18, 0, 8)
        })
        stationBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(stationBox)
        root.addView(TextView(this).apply {
            text = "Not: Yayın adresleri değişebilir. Açılmayan istasyon için çalışan bir HTTP(S) ses akışı adresi ekleyin."
            textSize = 12f; setTextColor(0xFF7F8995.toInt()); setPadding(0, 18, 0, 10)
        })
    }

    private fun renderStations() {
        stationBox.removeAllViews()
        stations.forEachIndexed { index, station ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8)
                setBackgroundColor(if (index == selected) 0xFF252B34.toInt() else 0xFF191D23.toInt())
            }
            val name = TextView(this).apply {
                text = (if (station.favorite) "★  " else "♪  ") + station.name
                textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
                setPadding(10)
                setOnClickListener { selected = index; status.text = station.name; renderStations() }
            }
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = if (station.favorite) "★" else "☆"
                setOnClickListener { station.favorite = !station.favorite; renderStations() }
            })
            row.addView(Button(this).apply {
                text = "▶"
                setOnClickListener { playStation(index) }
            })
            stationBox.addView(row)
            val spacer = View(this).apply { setBackgroundColor(0xFF101216.toInt()) }
            stationBox.addView(spacer, LinearLayout.LayoutParams(1, 6))
        }
    }

    private fun playStation(index: Int) {
        selected = index
        val s = stations[index]
        status.text = "Bağlanıyor: ${s.name}"
        player.setMediaItem(MediaItem.fromUri(s.url))
        player.prepare()
        player.playWhenReady = true
        renderStations()
    }

    private fun addStationDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24)
        }
        val name = EditText(this).apply { hint = "İstasyon adı" }
        val url = EditText(this).apply {
            hint = "Yayın URL'si (https://...)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        layout.addView(name)
        layout.addView(url)
        AlertDialog.Builder(this)
            .setTitle("İstasyon ekle")
            .setView(layout)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Ekle") { _, _ ->
                val n = name.text.toString().trim()
                val u = url.text.toString().trim()
                if (n.isNotEmpty() && (u.startsWith("https://") || u.startsWith("http://"))) {
                    stations.add(Station(n, u))
                    renderStations()
                } else Toast.makeText(this, "İsim ve geçerli http(s) adresi girin", Toast.LENGTH_LONG).show()
            }.show()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
