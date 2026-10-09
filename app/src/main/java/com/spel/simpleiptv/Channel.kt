package com.spel.simpleiptv

data class Channel(
    val name: String,
    val url: String,
    val category: String = "",
    val country: String = "",
    val id: String = "",
    val logo: String = "",
    val language: String = ""
)
