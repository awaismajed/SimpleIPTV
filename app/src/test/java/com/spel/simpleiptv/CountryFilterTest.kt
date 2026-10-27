package com.spel.simpleiptv

import org.junit.Assert.*
import org.junit.Test

class CountryFilterTest {
    @Test fun parsesTvgCountry() {
        val channels = M3uParser.parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="PTVNews.pk@SD" tvg-country="PK" group-title="News",PTV News
            https://example.com/pk
            #EXTINF:-1 tvg-id="BBCNews.uk@SD" group-title="News",BBC News
            https://example.com/uk
            #EXTINF:-1 tvg-id="WorldNews.us" tvg-country="US;CA" group-title="News",World News
            https://example.com/world
        """.trimIndent())
        assertEquals(3, channels.size)
        assertEquals("PK", channels[0].country)
        assertEquals("UK", channels[1].country)
        assertEquals(listOf("CA", "PK", "UK", "US"), CountryCatalog.codes(channels))
        assertEquals(1, CountryCatalog.filter(channels, "PK").size)
        assertEquals("PTV News", CountryCatalog.filter(channels, "PK")[0].name)
        assertEquals(1, CountryCatalog.filter(channels, "CA").size)
        assertEquals(3, CountryCatalog.filter(channels, "").size)
    }

    @Test fun untaggedChannelsDoNotGetIncorrectCountry() {
        val channels = M3uParser.parse("""
            #EXTM3U
            #EXTINF:-1 tvg-id="Unknown",Unknown
            https://example.com/unknown
        """.trimIndent())
        assertTrue(CountryCatalog.codes(channels).isEmpty())
        assertTrue(CountryCatalog.filter(channels, "PK").isEmpty())
    }
}
