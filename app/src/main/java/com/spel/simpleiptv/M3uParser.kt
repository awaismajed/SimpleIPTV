package com.spel.simpleiptv

object M3uParser {
    private fun attribute(line: String, name: String): String {
        val match = Regex("""(?:^|\s)""" + Regex.escape(name) + """="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)
        return match?.groupValues?.get(1)?.trim().orEmpty()
    }

    fun parse(text: String): List<Channel> {
        val result = ArrayList<Channel>()
        var name = ""
        var category = ""
        var country = ""
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> {
                    name = line.substringAfterLast(',').trim().ifBlank { "Channel" }
                    category = attribute(line, "group-title")
                    country = attribute(line, "tvg-country")
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    result.add(Channel(name.ifBlank { line }, line, category, country))
                    name = ""
                    category = ""
                    country = ""
                }
            }
        }
        return result
    }
}
