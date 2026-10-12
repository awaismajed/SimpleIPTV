package com.spel.simpleiptv.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardConfigValidatorTest {
    private val key = "A".repeat(43) + "="
    private fun config(endpoint: String = "example.com:51820") =
        "[Interface]\nPrivateKey = $key\nAddress = 10.0.0.2/32\n" +
        "[Peer]\nPublicKey = $key\nAllowedIPs = 0.0.0.0/0\nEndpoint = $endpoint\n"

    @Test fun acceptsStructurallyValidConfiguration() {
        assertTrue(WireGuardConfigValidator.validate(config()).valid)
    }

    @Test fun rejectsMissingPrivateKey() {
        assertFalse(WireGuardConfigValidator.validate(config().replace("PrivateKey", "Unknown")).valid)
    }

    @Test fun rejectsBadEndpointPort() {
        assertFalse(WireGuardConfigValidator.validate(config("example.com:99999")).valid)
    }

    @Test fun rejectsDuplicateSections() {
        assertFalse(WireGuardConfigValidator.validate(config() + "[Peer]\nPublicKey = $key\n").valid)
    }

    @Test fun rejectsUnrecognizedSections() {
        assertFalse(WireGuardConfigValidator.validate(config() + "[Scripts]\nPostUp = echo unsafe\n").valid)
    }
}
