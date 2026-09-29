package com.spel.simpleiptv

data class PlaylistSource(
    val name: String,
    val url: String
)

object PlaylistConfig {

    val playlists = listOf(

        PlaylistSource(
            "All",
            "https://iptv-org.github.io/iptv/index.m3u"
        ),

        PlaylistSource(
            "Pakistan",
            "https://iptv-org.github.io/iptv/countries/pk.m3u"
        ),

        PlaylistSource(
            "India",
            "https://iptv-org.github.io/iptv/countries/in.m3u"
        ),

        PlaylistSource(
            "English",
            "https://iptv-org.github.io/iptv/languages/eng.m3u"
        ),

        PlaylistSource(
            "News",
            "https://iptv-org.github.io/iptv/categories/news.m3u"
        )
    )

    const val M3U_URL =
        "https://iptv-org.github.io/iptv/countries/pk.m3u"
}
