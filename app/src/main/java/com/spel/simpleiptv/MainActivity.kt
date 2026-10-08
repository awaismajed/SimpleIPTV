package com.spel.simpleiptv

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.os.Bundle
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
    private lateinit var availabilityFilter: Spinner
    private lateinit var categoryFilter: Spinner
    private lateinit var countryFilter: Spinner
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
    private var statusFilter = "All"
    private var categorySelection = "All categories"
    private var countrySelection = "All countries"
    private var channelSelection = "Pakistan"
    private var updatingFilters = false
    private val preferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val playlistGeneration = AtomicInteger(0)
    private var scanExecutor: ExecutorService? = null
    private val scanGeneration = AtomicInteger(0)
    private val favorites by lazy { getSharedPreferences("favorites", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scanStore = ScanStore(applicationContext)
        scanResults.putAll(scanStore.load())
        statusFilter = preferences.getString("status_filter", "All") ?: "All"
        applySafeInsets()

        playerView = findViewById(R.id.playerView)
        status = findViewById(R.id.status)
        searchBox = findViewById(R.id.searchBox)
        channelList = findViewById(R.id.channelList)
        channelFilter = findViewById(R.id.channelFilter)
        availabilityFilter = findViewById(R.id.availabilityFilter)
        categoryFilter = findViewById(R.id.categoryFilter)
        countryFilter = findViewById(R.id.countryFilter)
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
        findViewById<Button>(R.id.channelsTab).setOnClickListener { showCurrentCategory() }
        findViewById<Button>(R.id.favoritesTab).setOnClickListener { showFavorites() }
        findViewById<Button>(R.id.scanTab).setOnClickListener { scanCurrentList() }

        val source = PlaylistConfig.playlists.firstOrNull { it.name == channelSelection } ?: PlaylistConfig.playlists.first()
        loadPlaylist(source.name, source.url)
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
        channelSelection = preferences.getString("playlist", "Pakistan") ?: "Pakistan"
        categorySelection = preferences.getString("category", "All categories") ?: "All categories"
        countrySelection = preferences.getString("country", "All countries") ?: "All countries"
        setupSpinner(channelFilter, PlaylistConfig.playlists.map { it.name }, channelSelection) { selected ->
            if (selected != channelSelection) {
                channelSelection = selected
                searchBox.setText("")
                val source = PlaylistConfig.playlists.first { it.name == selected }
                loadPlaylist(source.name, source.url)
            }
        }
        setupSpinner(availabilityFilter, listOf("All", "Working", "Offline", "Uncertain", "Not tested"), statusFilter) {
            statusFilter = it
            preferences.edit().putString("status_filter", it).apply()
            applySearch()
        }
        updateMetadataFilters()
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

    private fun updateMetadataFilters() {
        val categories = listOf("All categories") + allChannels.map { it.category }
            .filter { it.isNotBlank() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val countries = listOf("All countries") + allChannels.flatMap { it.country.split(';', ',') }
            .map { it.trim() }.filter { it.isNotBlank() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        updatingFilters = true
        setupSpinner(categoryFilter, categories, categorySelection) {
            categorySelection = it
            preferences.edit().putString("category", it).apply()
            applySearch()
        }
        setupSpinner(countryFilter, countries, countrySelection) {
            countrySelection = it
            preferences.edit().putString("country", it).apply()
            applySearch()
        }
        if (categorySelection !in categories) categorySelection = "All categories"
        if (countrySelection !in countries) countrySelection = "All countries"
        updatingFilters = false
    }

    private fun cancelScan() {
        scanGeneration.incrementAndGet()
        scanExecutor?.shutdownNow()
        scanExecutor = null
        scanButton.isEnabled = true
        if (::scanButton.isInitialized) scanButton.text = "Scan"
    }

    private fun loadPlaylist(name: String, playlistUrl: String) {
        cancelScan()
        currentPlaylist = name
        preferences.edit().putString("playlist", name).apply()
        val request = playlistGeneration.incrementAndGet()
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
                if (isFinishing || isDestroyed || request != playlistGeneration.get()) return@runOnUiThread
                allChannels = channels
                updateMetadataFilters()
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
        val filtered = allChannels.filter { channel ->
            (query.isEmpty() || channel.name.contains(query, true)) &&
            (categorySelection == "All categories" || channel.category.equals(categorySelection, true)) &&
            (countrySelection == "All countries" || channel.country.split(';', ',').any { it.trim().equals(countrySelection, true) }) &&
            when (statusFilter) {
                "Working" -> scanResults[channel.url] == ChannelScanStatus.WORKING
                "Offline" -> scanResults[channel.url] == ChannelScanStatus.NOT_WORKING
                "Uncertain" -> scanResults[channel.url] == ChannelScanStatus.UNCERTAIN
                "Not tested" -> !scanResults.containsKey(channel.url)
                else -> true
            }
        }
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
        if (allChannels.isEmpty()) return
        if (scanExecutor != null) {
            cancelScan()
            scanButton.text = "Scan"
            status.text = "Scan paused • results saved"
            return
        }
        val scanList = visibleChannels.distinctBy { it.url }
            .filter { !scanResults.containsKey(it.url) }
        if (scanList.isEmpty()) {
            status.text = "All selected channels already scanned"
            return
        }
        val generation = scanGeneration.incrementAndGet()
        findViewById<View>(R.id.scanSummary).visibility = View.VISIBLE
        scanButton.text = "Stop"
        status.text = "Scanning 0/${scanList.size} • saved automatically"
        val executor = Executors.newFixedThreadPool(4)
        scanExecutor = executor
        val next = AtomicInteger(0)
        val completed = AtomicInteger(0)
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
                    if (done % 50 == 0 || done == scanList.size) {
                        runOnUiThread {
                            if (generation != scanGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                            updateScanUi(done, scanList.size)
                            channelList.adapter?.notifyDataSetChanged()
                            if (done == scanList.size) {
                                executor.shutdown()
                                scanExecutor = null
                                scanButton.text = "Scan"
                                status.text = "Scan complete • ${scanList.size} new channels saved"
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
                    if (child is TextView) child.visibility = if (landscape) View.GONE else View.VISIBLE
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