package com.spel.simpleiptv.vpn

/**
 * Defensive parser for VPN Gate's public CSV API.
 * Discovery only: returned relay metadata does not establish or authenticate a tunnel.
 * Relay configs must not be executed without user consent and a vetted OpenVPN engine.
 */
data class VpnGateRelay(
    val hostName: String,
    val ip: String,
    val countryCode: String,
    val speedMbps: Double,
    val pingMs: Long,
    val openVpnConfigBase64: String
)

object VpnGateDirectory {
    const val DIRECTORY_URL = "https://www.vpngate.net/api/iphone/"
    private const val MAX_CSV_CHARS = 3_000_000
    private const val MAX_CONFIG_CHARS = 100_000
    private val ipv4 = Regex("""(?:[0-9]{1,3}\.){3}[0-9]{1,3}""")

    /**
     * Fetches relay metadata on a background thread. Never call from the UI thread.
     * The directory is untrusted: successful discovery is NOT a working VPN connection.
     */
    fun fetch(): List<VpnGateRelay> {
        val connection = (java.net.URL(DIRECTORY_URL).openConnection() as javax.net.ssl.HttpsURLConnection)
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "text/plain")
        try {
            if (connection.responseCode != 200) return emptyList()
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_CSV_CHARS) return emptyList()
            val csv = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                val text = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > MAX_CSV_CHARS) return emptyList()
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            return parse(csv)
        } finally {
            connection.disconnect()
        }
    }

    fun parse(csv: String): List<VpnGateRelay> {
        if (csv.length > MAX_CSV_CHARS) return emptyList()
        val lines = csv.lineSequence().filter { it.isNotBlank() && !it.startsWith("*") }
        val header = lines.firstOrNull { it.startsWith("#HostName,") } ?: return emptyList()
        val keys = header.removePrefix("#").split(",")
        val index = keys.withIndex().associate { it.value to it.index }
        val required = listOf("HostName", "IP", "CountryShort", "Speed", "Ping", "OpenVPN_ConfigData_Base64")
        if (required.any { it !in index }) return emptyList()
        return csv.lineSequence().dropWhile { it != header }.drop(1)
            .take(1000)
            .mapNotNull { line ->
                if (line.startsWith("*") || line.startsWith("#")) return@mapNotNull null
                val fields = line.split(",")
                fun value(key: String): String = fields.getOrNull(index.getValue(key)).orEmpty().trim()
                val ip = value("IP")
                val country = value("CountryShort").uppercase()
                val ping = value("Ping").toLongOrNull() ?: return@mapNotNull null
                val speed = value("Speed").toDoubleOrNull()?.div(1_000_000) ?: return@mapNotNull null
                val config = value("OpenVPN_ConfigData_Base64")
                if (!ipv4.matches(ip) || ip.split(".").any { it.toIntOrNull() !in 0..255 } ||
                    country.length != 2 || ping !in 1..5000 || speed < 0 ||
                    config.length !in 1..MAX_CONFIG_CHARS) return@mapNotNull null
                VpnGateRelay(value("HostName"), ip, country, speed, ping, config)
            }.toList()
    }

}
