package com.spel.simpleiptv.vpn

/**
 * Strict preflight validation for user-supplied WireGuard configurations.
 * This parser does not initiate a VPN connection or store secret keys.
 */
object WireGuardConfigValidator {
    data class Result(val valid: Boolean, val error: String? = null)

    fun validate(config: String): Result {
        if (config.length !in 30..32_768) return Result(false, "Configuration size is invalid")
        val sections = mutableMapOf<String, MutableMap<String, String>>()
        var current: String? = null
        for (raw in config.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length - 1).trim()
                if (current !in setOf("Interface", "Peer")) return Result(false, "Unsupported section")
                if (sections.containsKey(current)) return Result(false, "Duplicate section")
                sections[current!!] = mutableMapOf()
                continue
            }
            val separator = line.indexOf('=')
            if (current == null || separator <= 0) return Result(false, "Malformed configuration line")
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (key.isEmpty() || value.isEmpty()) return Result(false, "Missing configuration value")
            if (sections.getValue(current).put(key, value) != null) return Result(false, "Duplicate key")
        }
        val iface = sections["Interface"] ?: return Result(false, "Missing Interface")
        val peer = sections["Peer"] ?: return Result(false, "Missing Peer")
        fun validKey(value: String?): Boolean {
            if (value == null || value.length != 44 || !value.endsWith("=")) return false
            return value.dropLast(1).all { it.isLetterOrDigit() || it == '+' || it == '/' }
        }
        if (!validKey(iface["PrivateKey"])) return Result(false, "Invalid interface private key")
        if (!validKey(peer["PublicKey"])) return Result(false, "Invalid peer public key")
        if (iface["Address"].isNullOrBlank()) return Result(false, "Missing interface address")
        if (peer["AllowedIPs"].isNullOrBlank()) return Result(false, "Missing allowed IP ranges")
        val endpoint = peer["Endpoint"] ?: return Result(false, "Missing endpoint")
        val port = endpoint.substringAfterLast(':', "").toIntOrNull()
        if (port == null || port !in 1..65535 || endpoint.substringBeforeLast(':').isBlank())
            return Result(false, "Invalid endpoint")
        return Result(true)
    }
}
