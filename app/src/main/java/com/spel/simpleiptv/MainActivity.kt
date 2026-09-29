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
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.MediaRouteButtonFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.mediarouter.app.MediaRouteButton
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
    private lateinit var localPlayer: ExoPlayer
    private lateinit var player: CastPlayer
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var searchBox: EditText
    private lateinit var channelList: RecyclerView
    private lateinit var playlistButtons: LinearLayout
    private lateinit var favoriteButton: Button
    private lateinit var scanButton: Button
    private lateinit var showAllButton: Button

    private var allChannels: List<Channel> = emptyList()
    private var visibleChannels: List<Channel> = emptyList()
    private var currentChannel: Channel? = null
    private var currentChannelIndex = -1
    private var currentPlaylist = "Pakistan"
    private var fullscreen = false

    private val scanResults = mutableMapOf<String, ScanState>()
    private val favorites by lazy { getSharedPreferences("favorites", MODE_PRIVATE) }
    private enum class ScanState { WORKING, NOT_WORKING, UNCERTAIN }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        status = findViewById(R.id.status)
        searchBox = findViewById(R.id.searchBox)
        channelList = findViewById(R.id.channelList)
        playlistButtons = findViewById(R.id.playlistButtons)
        favoriteButton = findViewById(R.id.favoriteButton)
        scanButton = findViewById(R.id.scanButton)
        showAllButton = findViewById(R.id.showAllButton)
        val channelsTab = findViewById<Button>(R.id.channelsTab)
        val favoritesTab = findViewById<Button>(R.id.favoritesTab)
        val scanTab = findViewById<Button>(R.id.scanTab)
        channelList.layoutManager = LinearLayoutManager(this)

        localPlayer = ExoPlayer.Builder(this).build()
        player = CastPlayer.Builder(this).setLocalPlayer(localPlayer).build()
        playerView.player = player

        setupPlayerListener()
        setupCastButton()
        createPlaylistButtons()
        setupSearch()

        findViewById<Button>(R.id.previousButton).setOnClickListener { playPrevious() }
        findViewById<Button>(R.id.nextButton).setOnClickListener { playNext() }
        findViewById<Button>(R.id.fullscreenButton).setOnClickListener { toggleFullscreen() }
        favoriteButton.setOnClickListener { toggleCurrentFavorite() }
        scanButton.setOnClickListener { scanCurrentList() }
        showAllButton.setOnClickListener {
            scanResults.clear()
            applySearch()
        }
        channelsTab.setOnClickListener {
            val source = PlaylistConfig.playlists.firstOrNull { it.name == currentPlaylist }
                ?: PlaylistConfig.playlists.first { it.name == "Pakistan" }
            loadPlaylist(source.name, source.url)
        }
        favoritesTab.setOnClickListener { showFavorites() }
        scanTab.setOnClickListener { scanCurrentList() }

        loadPlaylist("Pakistan", PlaylistConfig.playlists.first { it.name == "Pakistan" }.url)
    }

    private fun setupCastButton() {
        val castButton = findViewById<MediaRouteButton>(R.id.castButton)
        MediaRouteButtonFactory.setUpMediaRouteButton(this, castButton)
    }

    private fun createPlaylistButtons() {
        addCategoryButton("⭐ Favorites") { showFavorites() }
        PlaylistConfig.playlists.forEach { playlist ->
            addCategoryButton(playlist.name) {
                searchBox.setText("")
                loadPlaylist(playlist.name, playlist.url)
            }
        }
    }

    private fun addCategoryButton(text: String, action: () -> Unit) {
        val button = Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
        }
        playlistButtons.addView(button)
    }

    private fun loadPlaylist(name: String, playlistUrl: String) {
        currentPlaylist = name
        scanResults.clear()
        status.text = "Loading $name channels..."
        allChannels = emptyList()
        showChannels(emptyList())

        thread {
            val channels = try {
                val connection = URL(playlistUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", "SimpleIPTV/2.1")
                connection.connect()
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                M3uParser.parse(text)
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                allChannels = channels
                currentChannelIndex = -1
                applySearch()
            }
        }
    }

    private fun showFavorites() {
        currentPlaylist = "Favorites"
        scanResults.clear()
        searchBox.setText("")
        allChannels = readFavorites()
        currentChannelIndex = -1
        applySearch()
    }

    private fun setupSearch() {
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applySearch()
            }
        })
    }

    private fun applySearch() {
        val query = searchBox.text.toString().trim()
        val filtered = allChannels.filter {
            query.isEmpty() || it.name.contains(query, ignoreCase = true)
        }
        showChannels(filtered)
        status.text = "$currentPlaylist • ${filtered.size} channels"
    }

    private fun showChannels(channels: List<Channel>) {
        visibleChannels = channels
        channelList.adapter = ChannelAdapter(
            channels,
            { channel -> isFavorite(channel) },
            { channel -> toggleFavorite(channel) },
            { channel ->
                currentChannelIndex = allChannels.indexOfFirst { it.url == channel.url }
                playChannel(channel)
            }
        )
    }

    private fun playChannel(channel: Channel) {
        currentChannel = channel
        updateFavoriteButton()
        status.text = "Loading: ${channel.name}"
        val item = MediaItem.Builder().setUri(channel.url).setMediaId(channel.name).build()
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    private fun playNext() {
        if (allChannels.isEmpty()) return
        currentChannelIndex = (currentChannelIndex + 1).mod(allChannels.size)
        playChannel(allChannels[currentChannelIndex])
    }

    private fun playPrevious() {
        if (allChannels.isEmpty()) return
        currentChannelIndex--
        if (currentChannelIndex < 0) currentChannelIndex = allChannels.lastIndex
        playChannel(allChannels[currentChannelIndex])
    }

    private fun toggleCurrentFavorite() {
        val channel = currentChannel ?: return
        toggleFavorite(channel)
    }

    private fun isFavorite(channel: Channel): Boolean =
        favorites.getStringSet("channels", emptySet())
            ?.any { it.substringBefore('\t') == channel.url } == true

    private fun toggleFavorite(channel: Channel) {
        val saved = favorites.getStringSet("channels", emptySet())?.toMutableSet() ?: mutableSetOf()
        val existing = saved.firstOrNull { it.substringBefore('\t') == channel.url }
        if (existing != null) saved.remove(existing) else saved.add(channel.url + "\t" + channel.name)
        favorites.edit().putStringSet("channels", saved).apply()
        updateFavoriteButton()
        if (currentPlaylist == "Favorites") {
            allChannels = readFavorites()
        }
        applySearch()
    }

    private fun updateFavoriteButton() {
        val channel = currentChannel
        if (channel == null) {
            favoriteButton.text = "☆ Favorite"
            return
        }
        val isFavorite = favorites.getStringSet("channels", emptySet())
            ?.any { it.substringBefore('\t') == channel.url } == true
        favoriteButton.text = if (isFavorite) "★ Favorite" else "☆ Favorite"
    }

    private fun readFavorites(): List<Channel> =
        favorites.getStringSet("channels", emptySet()).orEmpty()
            .mapNotNull {
                val p = it.split('\t', limit = 2)
                if (p.size == 2) Channel(p[1], p[0]) else null
            }
            .sortedBy { it.name.lowercase() }

    private fun scanCurrentList() {
        val targets = visibleChannels
        if (targets.isEmpty()) return
        val scanList = if (targets.size > 300) targets.take(300) else targets
        scanButton.isEnabled = false
        scanResults.clear()
        findViewById<View>(R.id.scanSummary).visibility = View.VISIBLE
        status.text = "Scanning 0/${scanList.size}..."

        val pool = Executors.newFixedThreadPool(8)
        val done = AtomicInteger(0)

        scanList.forEach { channel ->
            pool.execute {
                val result = testStream(channel.url)
                synchronized(scanResults) { scanResults[channel.url] = result }
                val completed = done.incrementAndGet()
                runOnUiThread {
                    val working = scanResults.values.count { it == ScanState.WORKING }
                    val bad = scanResults.values.count { it == ScanState.NOT_WORKING }
                    val uncertain = scanResults.values.count { it == ScanState.UNCERTAIN }
                    findViewById<TextView>(R.id.workingCount).text = "✓ $working Working"
                    findViewById<TextView>(R.id.notWorkingCount).text = "✕ $bad Not Working"
                    findViewById<TextView>(R.id.uncertainCount).text = "? $uncertain Uncertain"
                    status.text = "Scanning $completed/${scanList.size} • ✓ $working  ✕ $bad  ? $uncertain"
                    if (completed == scanList.size) {
                        scanButton.isEnabled = true
                        val workingChannels = scanList.filter { scanResults[it.url] == ScanState.WORKING }
                        showChannels(workingChannels)
                        status.text = "Scan complete • ✓ $working  ✕ $bad  ? $uncertain • showing working"
                        pool.shutdown()
                    }
                }
            }
        }
    }

    private fun testStream(url: String): ScanState {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.setRequestProperty("User-Agent", "SimpleIPTV/2.1")
            connection.setRequestProperty("Range", "bytes=0-1023")
            connection.requestMethod = "GET"
            when (connection.responseCode) {
                in 200..399 -> ScanState.WORKING
                in 400..599 -> ScanState.NOT_WORKING
                else -> ScanState.UNCERTAIN
            }
        } catch (_: Exception) {
            ScanState.UNCERTAIN
        } finally {
            connection?.disconnect()
        }
    }

    private fun setupPlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> status.text = "Loading channel..."
                    Player.STATE_READY -> {
                        val name = player.currentMediaItem?.mediaId ?: "Channel"
                        status.text = "Playing: $name"
                    }
                    Player.STATE_ENDED -> status.text = "Stream ended"
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val name = player.currentMediaItem?.mediaId ?: "Channel"
                status.text = "$name • Channel unavailable"
            }
        })
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        if (fullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            supportActionBar?.hide()
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            supportActionBar?.show()
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
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
        localPlayer.release()
        super.onDestroy()
    }
}
