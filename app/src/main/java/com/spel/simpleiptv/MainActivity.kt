package com.spel.simpleiptv

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
    private lateinit var searchBox: EditText
    private lateinit var channelList: RecyclerView
    private lateinit var playlistButtons: LinearLayout

    private var allChannels: List<Channel> = emptyList()
    private var visibleChannels: List<Channel> = emptyList()

    private var currentChannelIndex = -1
    private var currentPlaylist = "Pakistan"
    private var fullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        status = findViewById(R.id.status)
        searchBox = findViewById(R.id.searchBox)
        channelList = findViewById(R.id.channelList)
        playlistButtons = findViewById(R.id.playlistButtons)

        val previousButton =
            findViewById<Button>(R.id.previousButton)

        val nextButton =
            findViewById<Button>(R.id.nextButton)

        val fullscreenButton =
            findViewById<Button>(R.id.fullscreenButton)

        channelList.layoutManager =
            LinearLayoutManager(this)

        player = ExoPlayer.Builder(this).build()

        playerView.player = player

        setupPlayerListener()

        createPlaylistButtons()

        setupSearch()

        previousButton.setOnClickListener {
            playPrevious()
        }

        nextButton.setOnClickListener {
            playNext()
        }

        fullscreenButton.setOnClickListener {
            toggleFullscreen()
        }

        loadPlaylist(
            "Pakistan",
            PlaylistConfig.playlists.first {
                it.name == "Pakistan"
            }.url
        )
    }

    private fun createPlaylistButtons() {

        PlaylistConfig.playlists.forEach { playlist ->

            val button = Button(this)

            button.text = playlist.name
            button.isAllCaps = false

            button.setOnClickListener {

                searchBox.setText("")

                loadPlaylist(
                    playlist.name,
                    playlist.url
                )
            }

            playlistButtons.addView(button)
        }
    }

    private fun loadPlaylist(
        name: String,
        playlistUrl: String
    ) {

        currentPlaylist = name

        status.text = "Loading $name channels..."

        allChannels = emptyList()
        visibleChannels = emptyList()

        channelList.adapter =
            ChannelAdapter(emptyList()) { }

        thread {

            val channels = try {

                val connection =
                    URL(playlistUrl)
                        .openConnection() as HttpURLConnection

                connection.connectTimeout = 15000
                connection.readTimeout = 20000

                connection.setRequestProperty(
                    "User-Agent",
                    "SimpleIPTV/2.0"
                )

                connection.connect()

                val text =
                    connection.inputStream
                        .bufferedReader()
                        .use {
                            it.readText()
                        }

                connection.disconnect()

                M3uParser.parse(text)

            } catch (e: Exception) {

                e.printStackTrace()

                emptyList()
            }

            runOnUiThread {

                allChannels = channels
                visibleChannels = channels

                currentChannelIndex = -1

                showChannels(channels)

                status.text =
                    if (channels.isEmpty())
                        "$name: No channels found"
                    else
                        "$name: ${channels.size} channels"
            }
        }
    }

    private fun showChannels(
        channels: List<Channel>
    ) {

        visibleChannels = channels

        channelList.adapter =
            ChannelAdapter(channels) { channel ->

                currentChannelIndex =
                    allChannels.indexOfFirst {
                        it.url == channel.url
                    }

                playChannel(channel)
            }
    }

    private fun setupSearch() {

        searchBox.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {

                    val query =
                        s.toString().trim()

                    val filtered =
                        if (query.isEmpty()) {

                            allChannels

                        } else {

                            allChannels.filter {

                                it.name.contains(
                                    query,
                                    ignoreCase = true
                                )
                            }
                        }

                    showChannels(filtered)

                    status.text =
                        "$currentPlaylist: ${filtered.size} channels"
                }

                override fun afterTextChanged(
                    s: Editable?
                ) {
                }
            }
        )
    }

    private fun playChannel(
        channel: Channel
    ) {

        status.text =
            "Loading: ${channel.name}"

        val mediaItem =
            MediaItem.Builder()
                .setUri(channel.url)
                .setMediaId(channel.name)
                .build()

        player.apply {

            stop()

            clearMediaItems()

            setMediaItem(mediaItem)

            prepare()

            playWhenReady = true
        }
    }

    private fun playNext() {

        if (allChannels.isEmpty())
            return

        currentChannelIndex++

        if (currentChannelIndex >= allChannels.size)
            currentChannelIndex = 0

        playChannel(
            allChannels[currentChannelIndex]
        )
    }

    private fun playPrevious() {

        if (allChannels.isEmpty())
            return

        currentChannelIndex--

        if (currentChannelIndex < 0)
            currentChannelIndex =
                allChannels.size - 1

        playChannel(
            allChannels[currentChannelIndex]
        )
    }

    private fun setupPlayerListener() {

        player.addListener(
            object : Player.Listener {

                override fun onPlaybackStateChanged(
                    playbackState: Int
                ) {

                    when (playbackState) {

                        Player.STATE_BUFFERING ->
                            status.text =
                                "Loading channel..."

                        Player.STATE_READY -> {

                            val name =
                                player.currentMediaItem
                                    ?.mediaId
                                    ?: "Channel"

                            status.text =
                                "Playing: $name"
                        }

                        Player.STATE_ENDED ->
                            status.text =
                                "Stream ended"
                    }
                }

                override fun onPlayerError(
                    error: PlaybackException
                ) {

                    val name =
                        player.currentMediaItem
                            ?.mediaId
                            ?: "Channel"

                    status.text =
                        "$name - Channel unavailable"
                }
            }
        )
    }

    private fun toggleFullscreen() {

        fullscreen = !fullscreen

        if (fullscreen) {

            requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

            supportActionBar?.hide()

            if (android.os.Build.VERSION.SDK_INT >= 30) {

                window.insetsController?.hide(
                    WindowInsets.Type.statusBars() or
                            WindowInsets.Type.navigationBars()
                )

            } else {

                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }

        } else {

            requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            supportActionBar?.show()

            if (android.os.Build.VERSION.SDK_INT >= 30) {

                window.insetsController?.show(
                    WindowInsets.Type.statusBars() or
                            WindowInsets.Type.navigationBars()
                )
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
