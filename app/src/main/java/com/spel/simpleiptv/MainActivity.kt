package com.spel.simpleiptv

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        status = findViewById(R.id.status)

        val list = findViewById<RecyclerView>(R.id.channelList)
        list.layoutManager = LinearLayoutManager(this)

        // Create video player
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        status.text = "Loading channels..."

        // Load M3U playlist
        loadPlaylist { channels ->

            if (channels.isEmpty()) {
                status.text = "No channels found"
            } else {
                status.text = "${channels.size} channels"

                list.adapter = ChannelAdapter(channels) { channel ->
                    playChannel(channel)
                }
            }
        }
    }

    private fun playChannel(channel: Channel) {

        status.text = channel.name

        val mediaItem = MediaItem.Builder()
            .setUri(channel.url)
            .setMediaId(channel.name)
            .build()

        player.apply {
            stop()
            clearMediaItems()
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }

    private fun loadPlaylist(done: (List<Channel>) -> Unit) {

        thread {

            val channels = try {

                val connection =
                    URL(PlaylistConfig.M3U_URL).openConnection() as HttpURLConnection

                connection.connectTimeout = 10000
                connection.readTimeout = 15000

                connection.setRequestProperty(
                    "User-Agent",
                    "SimpleIPTV/1.0"
                )

                connection.connect()

                val text = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                connection.disconnect()

                M3uParser.parse(text)

            } catch (e: Exception) {

                e.printStackTrace()
                emptyList()
            }

            runOnUiThread {
                done(channels)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {

        playerView.player = null
        player.release()

        super.onDestroy()
    }
}
