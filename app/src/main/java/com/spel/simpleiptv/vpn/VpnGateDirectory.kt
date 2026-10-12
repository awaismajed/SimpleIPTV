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
