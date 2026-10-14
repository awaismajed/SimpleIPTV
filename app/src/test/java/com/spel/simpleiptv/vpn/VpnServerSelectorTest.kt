package com.spel.simpleiptv.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServerSelectorTest {
    private val now = 1_000_000L
    private fun server(id: String, latency: Long, uptime: Double = 99.0,
                       trusted: Boolean = true, failure: Long? = null) =
        VpnEndpoint(id, "SG", latency, uptime, 25.0, failure, trusted)

    @Test fun fasterHealthyServerWins() {
        val ranked = VpnServerSelector.ranked(
            listOf(server("slow", 400), server("fast", 40)), now)
        assertEquals(listOf("fast", "slow"), ranked.map { it.id })
    }

    @Test fun untrustedServersAreNeverSelected() {
        val ranked = VpnServerSelector.ranked(
            listOf(server("unknown", 10, trusted = false), server("known", 100)), now)
        assertEquals(listOf("known"), ranked.map { it.id })
    }

    @Test fun recentlyFailedServersAreExcluded() {
        val ranked = VpnServerSelector.ranked(
            listOf(server("failed", 10, failure = now - 5_000),
                   server("healthy", 100)), now)
        assertEquals(listOf("healthy"), ranked.map { it.id })
    }

    @Test fun noEligibleServersMeansNoConnection() {
        assertTrue(VpnServerSelector.ranked(
            listOf(server("untrusted", 30, trusted = false)), now).isEmpty())
    }
}
