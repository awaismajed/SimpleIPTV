package com.spel.simpleiptv.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnGateDirectoryTest {
    private val header = "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64"
    private val good = "vpn.example,1.2.3.4,100,45,25000000,Singapore,SG,4,1000,5,500,0,owner,message,YWJj"

    @Test fun parsesValidRelay() {
        val relays = VpnGateDirectory.parse("$header\n$good\n*")
        assertEquals(1, relays.size)
        assertEquals("SG", relays.first().countryCode)
        assertEquals(25.0, relays.first().speedMbps, 0.01)
    }

    @Test fun rejectsInvalidIpAndPing() {
        val badIp = good.replace("1.2.3.4", "999.2.3.4")
        val badPing = good.replace(",45,", ",0,")
        assertTrue(VpnGateDirectory.parse("$header\n$badIp\n$badPing").isEmpty())
    }

    @Test fun rejectsMalformedAndOversizedResponse() {
        assertTrue(VpnGateDirectory.parse("invalid").isEmpty())
        assertTrue(VpnGateDirectory.parse("x".repeat(3_000_001)).isEmpty())
    }
}
