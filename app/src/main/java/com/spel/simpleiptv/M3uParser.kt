package com.spel.simpleiptv

object M3uParser {
    private fun attribute(line: String, name: String): String {
        val pattern = Regex("""(?:^|\s)""" + Regex.escape(name) + """="([^"]*)"""", RegexOption.IGNORE_CASE)
        return pattern.find(line)?.groupValues?.get(1)?.trim().orEmpty()
    }

    fun parse(text: String): List<Channel> {
        val result = ArrayList<Channel>()
        var name = ""
        var category = ""
        var country = ""
        var id = ""
        var logo = ""
        var language = ""
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    name = line.substringAfterLast(',').trim().ifBlank { attribute(line, "tvg-name").ifBlank { "Channel" } }
                    category = attribute(line, "group-title")
                    country = attribute(line, "tvg-country")
                    id = attribute(line, "tvg-id")
                    logo = attribute(line, "tvg-logo")
                    language = attribute(line, "tvg-language")
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    result.add(Channel(name.ifBlank { "Channel" }, line, category, country, id, logo, language))
                    name = ""; category = ""; country = ""; id = ""; logo = ""; language = ""
                }
            }
        }
        return result
    }
}
