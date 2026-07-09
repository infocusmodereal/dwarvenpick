package com.dwarvenpick.app.datasource

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.UnknownHostException

class ForbiddenNetworkTargetException(
    override val message: String,
) : RuntimeException(message)

class UnresolvedNetworkTargetException(
    override val message: String,
) : IllegalArgumentException(message)

@Component
class DatasourceNetworkGuard(
    private val properties: DatasourceNetworkGuardProperties,
) {
    private val logger = LoggerFactory.getLogger(DatasourceNetworkGuard::class.java)

    fun validateHost(
        host: String,
        allowUnresolvedHost: Boolean = false,
    ) {
        if (!properties.enabled) {
            return
        }

        val normalizedHost = host.trim().lowercase()
        if (normalizedHost.isBlank()) {
            throw IllegalArgumentException("Datasource host is required.")
        }

        if (matchesAnyHostPattern(normalizedHost, properties.denyHostPatterns)) {
            throw forbidden(
                "Datasource host is blocked by network guard policy.",
                host,
                "denyHostPatterns",
            )
        }
        if (
            properties.allowHostPatterns.isNotEmpty() &&
            !matchesAnyHostPattern(normalizedHost, properties.allowHostPatterns)
        ) {
            throw forbidden(
                "Datasource host is not allowed by network guard policy.",
                host,
                "allowHostPatterns",
            )
        }

        val resolvedAddresses =
            try {
                InetAddress.getAllByName(normalizedHost).toList()
            } catch (_: UnknownHostException) {
                logger.warn(
                    "Datasource host could not be resolved for network guard validation: host={}",
                    sanitizeLogValue(host),
                )
                if (allowUnresolvedHost) {
                    return
                }
                if (
                    !properties.allowPrivateNetworks ||
                    properties.allowCidrs.isNotEmpty() ||
                    properties.denyCidrs.isNotEmpty()
                ) {
                    throw UnresolvedNetworkTargetException(
                        "Datasource host could not be resolved for network guard validation.",
                    )
                }
                emptyList()
            }

        resolvedAddresses.forEach { address ->
            val ipAddress = address.hostAddress.substringBefore('%')

            if (!properties.allowPrivateNetworks && isPrivateOrLocalAddress(address)) {
                throw forbidden(
                    "Datasource host resolves to a private or local address blocked by network guard policy.",
                    host,
                    "privateOrLocal:$ipAddress",
                )
            }

            if (matchesAnyCidr(ipAddress, properties.denyCidrs)) {
                throw forbidden(
                    "Datasource host resolves to an address blocked by network guard policy.",
                    host,
                    "denyCidrs:$ipAddress",
                )
            }

            if (isRestrictedLocalAddress(address)) {
                throw forbidden(
                    "Datasource host resolves to a restricted local address blocked by network guard policy.",
                    host,
                    "restrictedLocal:$ipAddress",
                )
            }

            if (properties.allowCidrs.isNotEmpty() && !matchesAnyCidr(ipAddress, properties.allowCidrs)) {
                throw forbidden(
                    "Datasource host resolves outside allowed network guard ranges.",
                    host,
                    "allowCidrs:$ipAddress",
                )
            }
        }
    }

    private fun forbidden(
        message: String,
        host: String,
        detail: String,
    ): ForbiddenNetworkTargetException {
        logger.warn("Datasource host rejected by network guard: host={} detail={}", sanitizeLogValue(host), detail)
        return ForbiddenNetworkTargetException(message)
    }

    private fun sanitizeLogValue(value: String): String = value.filterNot { character -> character.isISOControl() }

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
                        "^" + pattern.split("*").joinToString(".*") { segment -> Regex.escape(segment) } + "$",
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

    private fun isRestrictedLocalAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isMulticastAddress
}
