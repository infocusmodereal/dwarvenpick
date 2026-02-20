package com.badgermole.app.datasource

import java.net.InetAddress
import java.net.UnknownHostException
import org.springframework.stereotype.Component

class ForbiddenNetworkTargetException(
    override val message: String,
) : RuntimeException(message)

@Component
class DatasourceNetworkGuard(
    private val properties: DatasourceNetworkGuardProperties,
) {
    fun validateHost(host: String) {
        if (!properties.enabled) {
            return
        }

        val normalizedHost = host.trim().lowercase()
        if (normalizedHost.isBlank()) {
            throw IllegalArgumentException("Datasource host is required.")
        }

        if (matchesAnyHostPattern(normalizedHost, properties.denyHostPatterns)) {
            throw ForbiddenNetworkTargetException("Datasource host '$host' is blocked by denylist rules.")
        }
        if (
            properties.allowHostPatterns.isNotEmpty() &&
            !matchesAnyHostPattern(normalizedHost, properties.allowHostPatterns)
        ) {
            throw ForbiddenNetworkTargetException("Datasource host '$host' is not allowed by hostname allowlist rules.")
        }

        val resolvedAddresses =
            try {
                InetAddress.getAllByName(normalizedHost).toList()
            } catch (_: UnknownHostException) {
                if (
                    !properties.allowPrivateNetworks ||
                    properties.allowCidrs.isNotEmpty() ||
                    properties.denyCidrs.isNotEmpty()
                ) {
                    throw IllegalArgumentException(
                        "Datasource host '$host' could not be resolved for network policy validation.",
                    )
                }
                emptyList()
            }

        resolvedAddresses.forEach { address ->
            val ipAddress = address.hostAddress.substringBefore('%')

            if (!properties.allowPrivateNetworks && isPrivateOrLocalAddress(address)) {
                throw ForbiddenNetworkTargetException(
                    "Datasource host '$host' resolves to private/local address '$ipAddress', which is blocked.",
                )
            }

            if (matchesAnyCidr(ipAddress, properties.denyCidrs)) {
                throw ForbiddenNetworkTargetException(
                    "Datasource host '$host' resolves to '$ipAddress', which is blocked by IP denylist rules.",
                )
            }

            if (properties.allowCidrs.isNotEmpty() && !matchesAnyCidr(ipAddress, properties.allowCidrs)) {
                throw ForbiddenNetworkTargetException(
                    "Datasource host '$host' resolves to '$ipAddress', which is outside allowed IP ranges.",
                )
            }
        }
    }

    private fun matchesAnyHostPattern(
        host: String,
        patterns: List<String>,
    ): Boolean =
        patterns
            .asSequence()
            .map { pattern -> pattern.trim() }
            .filter { pattern -> pattern.isNotBlank() }
            .any { pattern ->
                val regex =
                    Regex(
                        "^" + Regex.escape(pattern).replace("\\*", ".*") + "$",
                        RegexOption.IGNORE_CASE,
                    )
                regex.matches(host)
            }

    private fun matchesAnyCidr(
        ipAddress: String,
        cidrs: List<String>,
    ): Boolean =
        cidrs
            .asSequence()
            .map { cidr -> cidr.trim() }
            .filter { cidr -> cidr.isNotBlank() }
            .any { cidr -> matchesCidr(ipAddress, cidr) }

    private fun matchesCidr(
        ipAddress: String,
        cidr: String,
    ): Boolean {
        val parts = cidr.split("/", limit = 2)
        if (parts.size != 2) {
            return false
        }

        val networkAddress =
            runCatching { InetAddress.getByName(parts[0]) }
                .getOrElse { return false }
        val ip =
            runCatching { InetAddress.getByName(ipAddress) }
                .getOrElse { return false }

        val prefixLength = parts[1].toIntOrNull() ?: return false
        val networkBytes = networkAddress.address
        val ipBytes = ip.address
        if (networkBytes.size != ipBytes.size) {
            return false
        }

        val maxPrefixLength = networkBytes.size * 8
        if (prefixLength !in 0..maxPrefixLength) {
            return false
        }

        var remainingBits = prefixLength
        for (index in networkBytes.indices) {
            val mask =
                when {
                    remainingBits >= 8 -> 0xFF
                    remainingBits <= 0 -> 0x00
                    else -> (0xFF shl (8 - remainingBits)) and 0xFF
                }

            if ((networkBytes[index].toInt() and mask) != (ipBytes[index].toInt() and mask)) {
                return false
            }
            remainingBits -= 8
        }

        return true
    }

    private fun isPrivateOrLocalAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
}
