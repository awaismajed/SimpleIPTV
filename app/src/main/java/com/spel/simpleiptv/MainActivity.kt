package com.spel.simpleiptv

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.os.Bundle
import android.app.AlertDialog
import android.net.Uri
import android.content.Intent
import java.io.File
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
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
    private lateinit var channelFilter: Spinner
    private lateinit var favoriteButton: Button
    private lateinit var scanButton: Button

    private var allChannels: List<Channel> = emptyList()
    private var visibleChannels: List<Channel> = emptyList()
    private var currentChannel: Channel? = null
    private var currentChannelIndex = -1
    private var currentPlaylist = "Pakistan"
    private var fullscreen = false

    private val scanResults = ConcurrentHashMap<String, ChannelScanStatus>()
    private lateinit var scanStore: ScanStore
    private var channelSelection = "Pakistan"
    private var updatingFilters = false
    private var browseSelection = "All"
    private var scanCompleted = false
    private var showingSettings = false
    private var playlistCache: List<Channel> = emptyList()
    private var cachedPlaylistName = ""
    private val customSources: List<PlaylistSource>
        get() = preferences.getStringSet("custom_sources", emptySet()).orEmpty().mapNotNull {
            val p = it.split("\t", limit = 2)
            if (p.size == 2) PlaylistSource(p[0], p[1]) else null
        }.sortedBy { it.name }
    private val sources: List<PlaylistSource> get() = PlaylistConfig.playlists + customSources
    private val preferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val playlistGeneration = AtomicInteger(0)
    private var scanExecutor: ExecutorService? = null
    private val scanGeneration = AtomicInteger(0)
    private val favorites by lazy { getSharedPreferences("favorites", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scanStore = ScanStore(applicationContext)
        scanResults.putAll(scanStore.load(Long.MAX_VALUE))
        scanCompleted = preferences.getBoolean("scan_completed", false)
        applySafeInsets()

        playerView = findViewById(R.id.playerView)
        status = findViewById(R.id.status)
        searchBox = findViewById(R.id.searchBox)
        channelList = findViewById(R.id.channelList)
        channelFilter = findViewById(R.id.channelFilter)
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
        setupDropdowns()
        setupSearch()
        applyResponsiveLayout(resources.configuration.orientation)

        findViewById<Button>(R.id.previousButton).setOnClickListener { playPrevious() }
        findViewById<Button>(R.id.nextButton).setOnClickListener { playNext() }
        findViewById<Button>(R.id.fullscreenButton).setOnClickListener { toggleFullscreen() }
        favoriteButton.setOnClickListener { currentChannel?.let { toggleFavorite(it) } }
        scanButton.setOnClickListener { scanCurrentList() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings(true) }
        findViewById<Button>(R.id.backFromSettings).setOnClickListener { showSettings(false) }
        findViewById<Button>(R.id.addPlaylistButton).setOnClickListener { showAddPlaylist() }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { refreshCurrentPlaylist() }
        browseSelection = preferences.getString("browse", "All") ?: "All"
        setupBrowseFilter()
        loadBrowseSelection()
    }

    private fun showAddPlaylist() {
        val input = EditText(this).apply {
            hint = "https://example.com/channels.m3u"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Add M3U playlist URL")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                val parsed = runCatching { Uri.parse(url) }.getOrNull()
                if (parsed?.scheme !in listOf("https", "http") || parsed?.host.isNullOrBlank()) {
                    status.text = "Enter a valid HTTP(S) playlist URL"
                } else {
                    val name = "Custom " + (customSources.size + 1)
                    val updated = preferences.getStringSet("custom_sources", emptySet()).orEmpty().toMutableSet()
                    updated.add(name + "\t" + url)
                    preferences.edit().putStringSet("custom_sources", updated).apply()
                    channelSelection = name
                    setupSpinner(channelFilter, sources.map { it.name }, name) { selected ->
                        if (selected != channelSelection) {
                            channelSelection = selected
                            val source = sources.first { it.name == selected }
                            loadPlaylist(source.name, source.url)
                        }
                    }
                    loadPlaylist(name, url)
                }
            }.show()
    }

    private fun refreshCurrentPlaylist() {
        val source = sources.firstOrNull { it.name == channelSelection } ?: return
        loadPlaylist(source.name, source.url, forceRefresh = true)
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

    private fun setupDropdowns() {
        channelSelection = preferences.getString("playlist", "All") ?: "All"
    }


    private val categoryGroups = listOf("Sports", "Religious", "News", "Movies", "Entertainment", "Kids", "Music", "Documentary", "Education", "Lifestyle", "Other")
    private val browseOptions: List<String>
        get() = listOf("All", "Working", "Not working", "Uncertain", "Favorites") +
            sources.filter { it.name != "All" }.map { it.name } +
            categoryGroups.filter { group -> sources.none { it.name.equals(group, true) } }

    private fun categoryGroup(channel: Channel): String {
        val text = (channel.category + " " + channel.name).lowercase()
        return when {
            listOf("sport", "cricket", "football", "soccer", "tennis", "nba", "wrestling", "racing").any { it in text } -> "Sports"
            listOf("relig", "islam", "quran", "christ", "faith", "spiritual", "makkah", "madinah").any { it in text } -> "Religious"
            listOf("news", "current affairs", "politic", "weather").any { it in text } -> "News"
            listOf("movie", "cinema", "film").any { it in text } -> "Movies"
            listOf("kids", "cartoon", "children", "junior").any { it in text } -> "Kids"
            listOf("music", "song", "radio").any { it in text } -> "Music"
            listOf("document", "history", "nature", "science").any { it in text } -> "Documentary"
            listOf("educat", "learn", "school", "university").any { it in text } -> "Education"
            listOf("lifestyle", "fashion", "travel", "food", "cook").any { it in text } -> "Lifestyle"
            listOf("entertain", "general", "comedy", "series", "drama").any { it in text } -> "Entertainment"
            else -> "Other"
        }
    }

    private fun setupBrowseFilter() {
        val options = browseOptions
        updatingFilters = true
        setupSpinner(channelFilter, options, browseSelection) { selected ->
            if (selected != browseSelection) {
                browseSelection = selected
                preferences.edit().putString("browse", selected).apply()
                loadBrowseSelection()
            }
        }
        updatingFilters = false
    }

    private fun loadBrowseSelection() {
        val source = sources.firstOrNull { it.name == browseSelection }
        if (source != null) {
            channelSelection = source.name
            loadPlaylist(source.name, source.url)
        } else if (browseSelection == "Favorites") {
            showFavorites()
        } else {
            val base = sources.firstOrNull { it.name == "All" } ?: sources.first()
            if (currentPlaylist == "Favorites" || cachedPlaylistName != base.name) {
                channelSelection = base.name
                loadPlaylist(base.name, base.url)
            } else {
                allChannels = playlistCache
                currentPlaylist = base.name
                applySearch()
            }
        }
    }

    private fun showSettings(show: Boolean) {
        showingSettings = show
        findViewById<View>(R.id.contentArea).visibility = if (show) View.GONE else View.VISIBLE
        findViewById<View>(R.id.settingsPage).visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            findViewById<TextView>(R.id.settingsScanStatus).text =
                if (scanExecutor != null) "Scan in progress" else if (scanCompleted) "Last scan saved" else "No completed scan"
        }
    }

    override fun onBackPressed() {
        if (showingSettings) showSettings(false) else super.onBackPressed()
    }

    private fun setupSpinner(spinner: Spinner, values: List<String>, selected: String, onSelected: (String) -> Unit) {
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val label = super.getView(position, convertView, parent) as TextView
                label.setTextColor(Color.WHITE)
                label.textSize = 13f
                label.gravity = Gravity.CENTER_VERTICAL
                label.setPadding(10, 0, 24, 0)
                label.maxLines = 1
                label.ellipsize = android.text.TextUtils.TruncateAt.END
                return label
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val label = super.getDropDownView(position, convertView, parent) as TextView
                label.setTextColor(Color.WHITE)
                label.setBackgroundColor(Color.rgb(31, 49, 68))
                label.setPadding(16, 12, 16, 12)
                label.textSize = 15f
                return label
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(values.indexOf(selected).coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingFilters) onSelected(values[position])
            }
        }
    }

    private fun updateMetadataFilters() { /* Single browse selector owns filtering. */ }


    private fun cancelScan() {
        scanGeneration.incrementAndGet()
        scanExecutor?.shutdownNow()
        scanExecutor = null
        if (::scanButton.isInitialized) scanButton.text = "Scan all channels"
    }

    private fun loadPlaylist(name: String, playlistUrl: String, forceRefresh: Boolean = false) {
        cancelScan()
        currentPlaylist = name
        preferences.edit().putString("playlist", name).apply()
        val request = playlistGeneration.incrementAndGet()
        findViewById<View>(R.id.scanSummary).visibility = View.GONE
        status.text = "Loading $name channels..."
        if (!forceRefresh && cachedPlaylistName == name && playlistCache.isNotEmpty()) {
            allChannels = playlistCache
            updateMetadataFilters()
            applySearch()
            return
        }
        allChannels = emptyList()
        showChannels(emptyList())

        thread {
            val channels = try {
                val connection = URL(playlistUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", "SimpleIPTV/2.3")
                try {
                    connection.inputStream.bufferedReader().use { M3uParser.parse(it.readText()) }
                } finally { connection.disconnect() }
            } catch (_: Exception) { emptyList() }

            runOnUiThread {
                if (isFinishing || isDestroyed || request != playlistGeneration.get()) return@runOnUiThread
                if (channels.isNotEmpty()) {
                    cachedPlaylistName = name
                    playlistCache = channels
                }
                allChannels = channels
                updateMetadataFilters()
                currentChannelIndex = -1
                applySearch()
            }
        }
    }

    private fun showCurrentCategory() {
        if (currentPlaylist == "Favorites") {
            val source = sources.firstOrNull { it.name == channelSelection } ?: sources.first()
            loadPlaylist(source.name, source.url)
        } else {
            applySearch()
        }
    }

    private fun showFavorites() {
        cancelScan()
        currentPlaylist = "Favorites"
        playlistGeneration.incrementAndGet()
        findViewById<View>(R.id.scanSummary).visibility = View.GONE
        searchBox.setText("")
        allChannels = readFavorites()
        updateMetadataFilters()
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
        val mode = browseSelection
        val filtered = allChannels.filter { channel ->
            val matches = query.isBlank() || listOf(channel.name, channel.category, channel.country, channel.language, channel.id)
                .any { it.contains(query, true) }
            val modeMatches = when (mode) {
                "Working" -> scanResults[channel.url] == ChannelScanStatus.WORKING
                "Not working" -> scanResults[channel.url] == ChannelScanStatus.NOT_WORKING
                "Uncertain" -> scanResults[channel.url] == ChannelScanStatus.UNCERTAIN
                "Favorites" -> isFavorite(channel)
                in categoryGroups -> categoryGroup(channel) == mode
                else -> true
            }
            matches && modeMatches &&
                (!scanCompleted || mode in listOf("Not working", "Uncertain", "All", "Favorites") ||
                 scanResults[channel.url] == ChannelScanStatus.WORKING)
        }
        showChannels(filtered)
        status.text = "$mode • ${filtered.size} channels" +
            if (scanCompleted && mode == "All") " • all scan statuses" else ""
    }


    private fun showChannels(channels: List<Channel>) {
        val manager = channelList.layoutManager as? LinearLayoutManager
        val position = manager?.findFirstVisibleItemPosition() ?: 0
        val offset = manager?.findViewByPosition(position)?.top ?: 0
        visibleChannels = channels
        channelList.adapter = ChannelAdapter(
            channels,
            { isFavorite(it) },
            { scanResults[it.url] ?: ChannelScanStatus.NOT_TESTED },
            { toggleFavorite(it) },
            {
                currentChannelIndex = visibleChannels.indexOfFirst { c -> c.url == it.url }
                playChannel(it)
            }
        )
        if (channels.isNotEmpty()) manager?.scrollToPositionWithOffset(position.coerceAtMost(channels.lastIndex), offset)
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
        if (visibleChannels.isEmpty()) return
        currentChannelIndex = (visibleChannels.indexOfFirst { it.url == currentChannel?.url } + 1).mod(visibleChannels.size)
        playChannel(visibleChannels[currentChannelIndex])
    }

    private fun playPrevious() {
        if (visibleChannels.isEmpty()) return
        val index = visibleChannels.indexOfFirst { it.url == currentChannel?.url }
        currentChannelIndex = if (index <= 0) visibleChannels.lastIndex else index - 1
        playChannel(visibleChannels[currentChannelIndex])
    }

    private fun isFavorite(channel: Channel) =
        favorites.getStringSet("channels", emptySet())?.any { it.substringBefore('\t') == channel.url } == true

    private fun toggleFavorite(channel: Channel) {
        val saved = favorites.getStringSet("channels", emptySet())?.toMutableSet() ?: mutableSetOf()
        val existing = saved.firstOrNull { it.substringBefore('\t') == channel.url }
        if (existing == null) saved.add(listOf(channel.url, channel.name, channel.category, channel.country, channel.id, channel.logo, channel.language).joinToString("\t"))
        else saved.remove(existing)
        favorites.edit().putStringSet("channels", saved).apply()
        updateFavoriteButton()
        if (browseSelection == "Favorites") {
            allChannels = readFavorites()
            applySearch()
        } else {
            // Update only the visible star; do not rebuild the list or jump to its first row.
            channelList.adapter?.notifyItemRangeChanged(0, visibleChannels.size)
        }
    }


    private fun updateFavoriteButton() {
        favoriteButton.text = if (currentChannel?.let { isFavorite(it) } == true) "★ Favorite" else "☆ Favorite"
    }

    private fun readFavorites() = favorites.getStringSet("channels", emptySet()).orEmpty()
        .mapNotNull {
            val p = it.split('\t')
            if (p.size >= 2) Channel(p[1], p[0], p.getOrElse(2) { "" }, p.getOrElse(3) { "" }, p.getOrElse(4) { "" }, p.getOrElse(5) { "" }, p.getOrElse(6) { "" }) else null
        }.sortedBy { it.name.lowercase() }

    private fun scanCurrentList() {
        if (scanExecutor != null) {
            cancelScan()
            findViewById<TextView>(R.id.settingsScanStatus).text = "Scan stopped; previous completed results retained"
            return
        }
        if (allChannels.isEmpty()) {
            findViewById<TextView>(R.id.settingsScanStatus).text = "Load a playlist before scanning"
            return
        }
        val scanList = allChannels.distinctBy { it.url }
        val generation = scanGeneration.incrementAndGet()
        val executor = Executors.newFixedThreadPool(4)
        scanExecutor = executor
        scanButton.text = "Stop scan"
        val completed = AtomicInteger(0)
        val next = AtomicInteger(0)
        findViewById<TextView>(R.id.settingsScanStatus).text = "Scanning 0 / ${scanList.size}"
        repeat(4) {
            executor.execute {
                while (generation == scanGeneration.get() && !Thread.currentThread().isInterrupted) {
                    val index = next.getAndIncrement()
                    if (index >= scanList.size) break
                    val channel = scanList[index]
                    val result = testStream(channel.url)
                    if (generation != scanGeneration.get()) break
                    scanStore.save(channel.url, result)
                    scanResults[channel.url] = result
                    val done = completed.incrementAndGet()
                    if (done % 25 == 0 || done == scanList.size) {
                        runOnUiThread {
                            if (generation != scanGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                            findViewById<TextView>(R.id.settingsScanStatus).text = "Scanning $done / ${scanList.size}"
                            if (done == scanList.size) {
                                scanCompleted = true
                                preferences.edit().putBoolean("scan_completed", true).apply()
                                executor.shutdown()
                                scanExecutor = null
                                scanButton.text = "Scan all channels again"
                                browseSelection = "Working"
                                setupBrowseFilter()
                                loadBrowseSelection()
                                findViewById<TextView>(R.id.settingsScanStatus).text = "Scan complete: showing working channels"
                                showSettings(false)
                            }
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
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun applyResponsiveLayout(orientation: Int) {
        val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val container = findViewById<LinearLayout>(R.id.contentArea)
        val videoPane = findViewById<LinearLayout>(R.id.videoPane)
        val browserPane = findViewById<LinearLayout>(R.id.browserPane)
        container.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        videoPane.layoutParams = if (landscape)
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f)
        else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        browserPane.layoutParams = if (landscape)
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        playerView.layoutParams = if (landscape)
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190))
        findViewById<View>(R.id.topBar).visibility = View.VISIBLE
        val panel = findViewById<LinearLayout>(R.id.filterPanel)
        for (i in 0 until panel.childCount) {
            val row = panel.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val cell = row.getChildAt(j) as? LinearLayout ?: continue
                for (k in 0 until cell.childCount) {
                    val child = cell.getChildAt(k)
                    if (child is TextView && child !is Button) child.visibility = if (landscape) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsiveLayout(newConfig.orientation)
        ViewCompat.requestApplyInsets(findViewById(R.id.rootContainer))
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
        scanStore.close()
        super.onDestroy()
    }
}