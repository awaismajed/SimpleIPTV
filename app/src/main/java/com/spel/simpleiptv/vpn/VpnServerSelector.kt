package com.spel.simpleiptv.vpn

/**
 * Ranks eligible VPN endpoints. Measurements are supplied by the caller; this class
 * does not fetch or trust public relay lists and does not establish a VPN tunnel.
 */
data class VpnEndpoint(
    val id: String,
    val countryCode: String,
    val latencyMs: Long?,
    val uptimePercent: Double?,
    val reportedMbps: Double?,
    val lastFailureEpochMs: Long? = null,
    val trusted: Boolean = false
)

object VpnServerSelector {
    fun ranked(
        endpoints: List<VpnEndpoint>,
        nowEpochMs: Long,
        preferredCountryCode: String? = null
    ): List<VpnEndpoint> = endpoints
        .asSequence()
        .filter { it.trusted && it.id.isNotBlank() && it.countryCode.length == 2 }
        .filter { it.latencyMs != null && it.latencyMs in 1..5000 }
        .filter { it.uptimePercent != null && it.uptimePercent in 0.0..100.0 }
        .filter { it.reportedMbps == null || it.reportedMbps >= 0.0 }
        .filter { it.lastFailureEpochMs == null || nowEpochMs - it.lastFailureEpochMs >= 120_000 }
        .sortedWith(compareByDescending<VpnEndpoint> {
            val uptime = it.uptimePercent ?: 0.0
            val latency = it.latencyMs ?: 5000
            val speed = (it.reportedMbps ?: 0.0).coerceAtMost(100.0)
            val preferred = if (preferredCountryCode != null &&
                it.countryCode.equals(preferredCountryCode, ignoreCase = true)) 10.0 else 0.0
            uptime * 0.5 + speed * 0.2 - latency * 0.025 + preferred
        }.thenBy { it.id })
        .toList()
}
