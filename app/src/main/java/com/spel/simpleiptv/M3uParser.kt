package com.spel.simpleiptv

object M3uParser {
    fun parse(text: String): List<Channel> {
        val result = mutableListOf<Channel>()
        var pendingName: String? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> pendingName = line.substringAfterLast(',').trim().ifBlank { "Channel" }
                line.isNotBlank() && !line.startsWith("#") -> {
                    result += Channel(pendingName ?: line, line)
                    pendingName = null
                }
            }
        }
        return result
    }
}
