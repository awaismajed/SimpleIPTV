package com.spel.simpleiptv

import java.util.Locale

/** Country codes in the downloaded master M3U; never guesses that a country has channels. */
object CountryCatalog {
    fun codes(channels: List<Channel>): List<String> =
        channels.flatMap { countryCodes(it.country) }
            .distinct()
            .sortedBy { name(it) }

    fun countryCodes(raw: String): List<String> =
        raw.split(';', ',', '|').map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.length == 2 && it.all(Char::isLetter) }

    fun name(code: String): String = Locale("", code).displayCountry

    fun matches(channel: Channel, code: String): Boolean =
        code.isBlank() || countryCodes(channel.country).contains(code.uppercase(Locale.ROOT))

    fun filter(channels: List<Channel>, code: String): List<Channel> =
        channels.filter { matches(it, code) }
}
