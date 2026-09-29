package com.spel.simpleiptv

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.mediarouter.app.MediaRouteButton
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var localPlayer: ExoPlayer
    private lateinit var castPlayer: CastPlayer
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private var activePlayer: Player? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        status = findViewById(R.id.status)
        val list = findViewById<RecyclerView>(R.id.channelList)
        list.layoutManager = LinearLayoutManager(this)

        localPlayer = ExoPlayer.Builder(this).build()
        val castContext = CastContext.getSharedInstance(this)
        castPlayer = CastPlayer.Builder(this, castContext)
            .setMediaItemConverter(DefaultMediaItemConverter())
            .build()

        castPlayer.setSessionAvailabilityListener(object : androidx.media3.cast.SessionAvailabilityListener {
            override fun onCastSessionAvailable() = switchPlayer(castPlayer)
            override fun onCastSessionUnavailable() = switchPlayer(localPlayer)
        })
        switchPlayer(if (castPlayer.isCastSessionAvailable) castPlayer else localPlayer)

        CastButtonFactory.setUpMediaRouteButton(applicationContext, findViewById<MediaRouteButton>(R.id.castButton))
        loadPlaylist { channels ->
            status.text = if (channels.isEmpty()) "No channels found" else "${channels.size} channels"
            list.adapter = ChannelAdapter(channels) { play(it) }
        }
    }

    private fun switchPlayer(target: Player) {
        val old = activePlayer
        if (old === target) return
        if (old != null && old.mediaItemCount > 0) {
            val item = old.currentMediaItem
            if (item != null) {
                target.setMediaItem(item)
                target.prepare()
                if (old.playWhenReady) target.play()
            }
            old.stop()
        }
        activePlayer = target
        playerView.player = target
    }

    private fun play(channel: Channel) {
        status.text = channel.name
        activePlayer?.apply {
            setMediaItem(MediaItem.Builder().setUri(channel.url).setMediaId(channel.name).build())
            prepare()
            play()
        }
    }

    private fun loadPlaylist(done: (List<Channel>) -> Unit) {
        thread {
            val channels = try {
                val c = URL(PlaylistConfig.M3U_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", "SimpleIPTV/1.0")
                val text = c.inputStream.bufferedReader().use { it.readText() }
                M3uParser.parse(text)
            } catch (_: Exception) { emptyList() }
            runOnUiThread { done(channels) }
        }
    }

    override fun onDestroy() {
        playerView.player = null
        localPlayer.release()
        castPlayer.release()
        super.onDestroy()
    }
}
