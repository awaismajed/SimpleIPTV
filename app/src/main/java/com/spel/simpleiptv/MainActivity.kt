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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
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

    private var allChannels: List<Channel> = emptyList()
    private var visibleChannels: List<Channel> = emptyList()
    private var currentChannel: Channel? = null
    private var currentChannelIndex = -1
    private var currentPlaylist = "Pakistan"
    private var fullscreen = false

    private val scanResults = ConcurrentHashMap<String, ChannelScanStatus>()
    private var scanExecutor: ExecutorService? = null
    private val scanGeneration = AtomicInteger(0)
    private val favorites by lazy { getSharedPreferences("favorites", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySafeInsets()

        playerView = findViewById(R.id.playerView)
        status = findViewById(R.id.status)
        searchBox = findViewById(R.id.searchBox)
        channelList = findViewById(R.id.channelList)
        playlistButtons = findViewById(R.id.playlistButtons)
        favoriteButton = findViewById(R.id.favoriteButton)
        scanButton = findViewById(R.id.scanButton)
        channelList.layoutManager = LinearLayoutManager(this)

        // Initialize the Google Cast framework first so receiver discovery starts reliably.
        CastContext.getSharedInstance(this)
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
        favoriteButton.setOnClickListener { currentChannel?.let { toggleFavorite(it) } }
        scanButton.setOnClickListener { scanCurrentList() }
        findViewById<Button>(R.id.channelsTab).setOnClickListener { showCurrentCategory() }
        findViewById<Button>(R.id.favoritesTab).setOnClickListener { showFavorites() }
        findViewById<Button>(R.id.scanTab).setOnClickListener { scanCurrentList() }

        loadPlaylist("Pakistan", PlaylistConfig.playlists.first { it.name == "Pakistan" }.url)
    }

    private fun applySafeInsets() {
        val root = findViewById<View>(R.id.rootContainer)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (!fullscreen) view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupCastButton() {
        // Use the Google Cast framework button setup. This was the discovery path
        // used by the earlier working build and remains compatible with CastPlayer.
        CastButtonFactory.setUpMediaRouteButton(
            applicationContext,
            findViewById<MediaRouteButton>(R.id.castButton)
        )
    }

    private fun createPlaylistButtons() {
        PlaylistConfig.playlists.forEach { source ->
            addCategoryButton(source.name) {
                searchBox.setText("")
                loadPlaylist(source.name, source.url)
            }
        }
    }

    private fun addCategoryButton(label: String, action: () -> Unit) {
        playlistButtons.addView(Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(22, 0, 22, 0)
            setOnClickListener { action() }
        })
    }

    private fun cancelScan() {
        scanGeneration.incrementAndGet()
        scanExecutor?.shutdownNow()
        scanExecutor = null
        scanButton.isEnabled = true
    }

    private fun loadPlaylist(name: String, playlistUrl: String) {
        cancelScan()
        currentPlaylist = name
        scanResults.clear()
        findViewById<View>(R.id.scanSummary).visibility = View.GONE
        status.text = "Loading $name channels..."
        allChannels = emptyList()
        showChannels(emptyList())

        thread {
            val channels = try {
                val connection = URL(playlistUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", "SimpleIPTV/2.3")
                connection.inputStream.bufferedReader().use { M3uParser.parse(it.readText()) }
                    .also { connection.disconnect() }
            } catch (_: Exception) { emptyList() }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allChannels = channels
                currentChannelIndex = -1
                applySearch()
            }
        }
    }

    private fun showCurrentCategory() {
        if (currentPlaylist == "Favorites") {
            val source = PlaylistConfig.playlists.first { it.name == "Pakistan" }
            loadPlaylist(source.name, source.url)
        } else {
            applySearch()
        }
    }

    private fun showFavorites() {
        cancelScan()
        currentPlaylist = "Favorites"
        scanResults.clear()
        findViewById<View>(R.id.scanSummary).visibility = View.GONE
        searchBox.setText("")
        allChannels = readFavorites()
        currentChannelIndex = -1
        applySearch()
    }

    private fun setupSearch() {
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { applySearch() }
        })
    }

    private fun applySearch() {
        val query = searchBox.text.toString().trim()
        val filtered = allChannels.filter { query.isEmpty() || it.name.contains(query, true) }
        showChannels(filtered)
        status.text = "$currentPlaylist • ${filtered.size} channels"
    }

    private fun showChannels(channels: List<Channel>) {
        visibleChannels = channels
        channelList.adapter = ChannelAdapter(
            channels,
            { isFavorite(it) },
            { scanResults[it.url] ?: ChannelScanStatus.NOT_TESTED },
            { toggleFavorite(it) },
            {
                currentChannelIndex = allChannels.indexOfFirst { c -> c.url == it.url }
                playChannel(it)
            }
        )
    }

    private fun playChannel(channel: Channel) {
        currentChannel = channel
        updateFavoriteButton()
        status.text = "Loading: ${channel.name}"
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.Builder().setUri(channel.url).setMediaId(channel.name).build())
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

    private fun isFavorite(channel: Channel) =
        favorites.getStringSet("channels", emptySet())?.any { it.substringBefore('\t') == channel.url } == true

    private fun toggleFavorite(channel: Channel) {
        val saved = favorites.getStringSet("channels", emptySet())?.toMutableSet() ?: mutableSetOf()
        val existing = saved.firstOrNull { it.substringBefore('\t') == channel.url }
        if (existing == null) saved.add(channel.url + "\t" + channel.name) else saved.remove(existing)
        favorites.edit().putStringSet("channels", saved).apply()
        updateFavoriteButton()
        if (currentPlaylist == "Favorites") allChannels = readFavorites()
        applySearch()
    }

    private fun updateFavoriteButton() {
        favoriteButton.text = if (currentChannel?.let { isFavorite(it) } == true) "★ Favorite" else "☆ Favorite"
    }

    private fun readFavorites() = favorites.getStringSet("channels", emptySet()).orEmpty()
        .mapNotNull {
            val p = it.split('\t', limit = 2)
            if (p.size == 2) Channel(p[1], p[0]) else null
        }.sortedBy { it.name.lowercase() }

    private fun scanCurrentList() {
        if (visibleChannels.isEmpty() || scanExecutor != null) return
        val scanList = visibleChannels.take(300)
        val generation = scanGeneration.incrementAndGet()
        scanResults.clear()
        findViewById<View>(R.id.scanSummary).visibility = View.VISIBLE
        scanButton.isEnabled = false
        status.text = "Scanning 0/${scanList.size}..."

        val executor = Executors.newFixedThreadPool(6)
        scanExecutor = executor
        val completed = AtomicInteger(0)

        scanList.forEach { channel ->
            executor.execute {
                if (generation != scanGeneration.get()) return@execute
                scanResults[channel.url] = testStream(channel.url)
                val done = completed.incrementAndGet()

                if (done % 5 == 0 || done == scanList.size) {
                    runOnUiThread {
                        if (generation != scanGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                        updateScanUi(done, scanList.size)
                        channelList.adapter?.notifyDataSetChanged()
                        if (done == scanList.size) {
                            scanButton.isEnabled = true
                            scanExecutor?.shutdown()
                            scanExecutor = null
                            status.text = "Scan complete • ${scanList.size} channels tested"
                        }
                    }
                }
            }
        }
    }

    private fun updateScanUi(done: Int, total: Int) {
        val working = scanResults.values.count { it == ChannelScanStatus.WORKING }
        val offline = scanResults.values.count { it == ChannelScanStatus.NOT_WORKING }
        val uncertain = scanResults.values.count { it == ChannelScanStatus.UNCERTAIN }
        findViewById<TextView>(R.id.workingCount).text = "✓ $working Working"
        findViewById<TextView>(R.id.notWorkingCount).text = "✕ $offline Offline"
        findViewById<TextView>(R.id.uncertainCount).text = "Uncertain $uncertain"
        status.text = "Scanning $done/$total"
    }

    private fun testStream(url: String): ChannelScanStatus {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as? HttpURLConnection
                ?: return ChannelScanStatus.UNCERTAIN
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 3500
            connection.readTimeout = 3500
            connection.setRequestProperty("User-Agent", "SimpleIPTV/2.3")
            connection.setRequestProperty("Range", "bytes=0-1023")
            val code = connection.responseCode
            when (code) {
                in 200..399 -> ChannelScanStatus.WORKING
                in 400..599 -> ChannelScanStatus.NOT_WORKING
                else -> ChannelScanStatus.UNCERTAIN
            }
        } catch (_: Exception) { ChannelScanStatus.UNCERTAIN }
        finally { connection?.disconnect() }
    }

    private fun setupPlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> status.text = "Loading channel..."
                    Player.STATE_READY -> status.text = "Playing: ${player.currentMediaItem?.mediaId ?: "Channel"}"
                    Player.STATE_ENDED -> status.text = "Stream ended"
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                status.text = "${player.currentMediaItem?.mediaId ?: "Channel"} • Channel unavailable"
            }
        })
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val root = findViewById<View>(R.id.rootContainer)
        if (fullscreen) {
            root.setPadding(0, 0, 0, 0)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            supportActionBar?.hide()
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.hide(WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            supportActionBar?.show()
            if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.systemBars())
            ViewCompat.requestApplyInsets(root)
        }
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        cancelScan()
        playerView.player = null
        player.release()
        localPlayer.release()
        super.onDestroy()
    }
}