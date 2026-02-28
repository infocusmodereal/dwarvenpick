package com.dwarvenpick.app.datasource

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

internal data class ConnectionsBootstrapFile(
    val connections: List<ConnectionBootstrapSpec> = emptyList(),
)

internal data class ConnectionBootstrapSpec(
    val name: String = "",
    val engine: DatasourceEngine = DatasourceEngine.POSTGRESQL,
    val host: String = "",
    val port: Int = 5432,
    val database: String? = null,
    val driverId: String? = null,
    val pool: PoolSettings = PoolSettings(),
    val tls: TlsSettings = TlsSettings(),
    val options: Map<String, String> = emptyMap(),
    val credentialProfiles: Map<String, ConnectionBootstrapCredentialProfile> = emptyMap(),
    val access: List<ConnectionBootstrapAccessRule> = emptyList(),
)

internal data class ConnectionBootstrapCredentialProfile(
    val username: String = "",
    val password: String = "",
    val description: String? = null,
)

internal data class ConnectionBootstrapAccessRule(
    val groupId: String = "",
    val credentialProfile: String = "",
    val canQuery: Boolean = true,
    val canExport: Boolean = false,
    val readOnly: Boolean = true,
    val maxRowsPerQuery: Int? = null,
    val maxRuntimeSeconds: Int? = null,
    val concurrencyLimit: Int? = null,
)

internal class ConnectionsConfigLoader(
    private val yamlMapper: ObjectMapper =
        ObjectMapper(YAMLFactory())
            .registerKotlinModule()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true),
    private val envInterpolator: EnvInterpolator = EnvInterpolator(),
) {
    fun load(
        path: Path,
        failOnMissingEnv: Boolean,
    ): List<ConnectionBootstrapSpec> {
        val root = Files.readString(path)
        val parsedRoot = yamlMapper.readTree(root)
        val interpolated = envInterpolator.interpolate(parsedRoot, failOnMissing = failOnMissingEnv)
        return parseConnections(interpolated)
    }

    private fun parseConnections(root: JsonNode): List<ConnectionBootstrapSpec> {
        if (root.isObject && root.has("connections")) {
            val file = yamlMapper.treeToValue(root, ConnectionsBootstrapFile::class.java)
            return file.connections
        }

        if (root.isObject) {
            return parseLegacyConnections(root)
        }

        throw IllegalArgumentException("Connections config root must be a YAML mapping.")
    }

    private fun parseLegacyConnections(root: JsonNode): List<ConnectionBootstrapSpec> {
        val rootObject = root as? ObjectNode ?: return emptyList()
        if (rootObject.size() == 0) {
            return emptyList()
        }

        data class LegacyProfile(
            val host: String = "",
            val port: Int = 0,
            val user: String = "",
            val password: String = "",
        )

        val result = mutableListOf<ConnectionBootstrapSpec>()
        rootObject.fields().forEach { (connectionName, profilesNode) ->
            val profilesObject =
                profilesNode as? ObjectNode
                    ?: throw IllegalArgumentException(
                        "Legacy connections config entry '$connectionName' must be a mapping of credential profiles.",
                    )

            val profiles =
                yamlMapper.convertValue(
                    profilesObject,
                    object : TypeReference<Map<String, LegacyProfile>>() {},
                )

            require(profiles.isNotEmpty()) { "Legacy connections config entry '$connectionName' must define profiles." }

            val first = profiles.values.first()
            val inconsistent =
                profiles.entries.firstOrNull { (_, profile) -> profile.host != first.host || profile.port != first.port }
            require(inconsistent == null) {
                "Legacy connections config entry '$connectionName' must use a consistent host/port across profiles."
            }

            val credentialProfiles =
                profiles.mapValues { (_, profile) ->
                    ConnectionBootstrapCredentialProfile(
                        username = profile.user,
                        password = profile.password,
                        description = null,
                    )
                }

            result.add(
                ConnectionBootstrapSpec(
                    name = connectionName,
                    engine = DatasourceEngine.STARROCKS,
                    host = first.host,
                    port = first.port,
                    driverId = "starrocks-mysql",
                    options =
                        mapOf(
                            "allowPublicKeyRetrieval" to "true",
                            "serverTimezone" to "UTC",
                        ),
                    credentialProfiles = credentialProfiles,
                    access = emptyList(),
                ),
            )
        }

        return result
    }
}

internal class EnvInterpolator(
    private val env: Map<String, String> = System.getenv(),
) {
    private val logger = LoggerFactory.getLogger(EnvInterpolator::class.java)
    private val nodeFactory = JsonNodeFactory.instance
    private val pattern = Regex("\\$\\{ENV:([A-Za-z_][A-Za-z0-9_]*)(?::-(.*?))?\\}")

    fun interpolate(
        root: JsonNode,
        failOnMissing: Boolean,
    ): JsonNode {
        val missing = linkedSetOf<String>()
        val updated = interpolateNode(root, missing, failOnMissing)
        if (!failOnMissing && missing.isNotEmpty()) {
            logger.warn(
                "Connections config referenced missing environment variables: {}",
                missing.joinToString(", "),
            )
        }
        return updated
    }

    private fun interpolateNode(
        node: JsonNode,
        missing: MutableSet<String>,
        failOnMissing: Boolean,
    ): JsonNode =
        when {
            node.isTextual -> TextNode.valueOf(interpolateText(node.asText(), missing, failOnMissing))
            node.isArray -> {
                val array = ArrayNode(nodeFactory)
                node.forEach { child -> array.add(interpolateNode(child, missing, failOnMissing)) }
                array
            }
            node.isObject -> {
                val obj = ObjectNode(nodeFactory)
                node.fields().forEachRemaining { (key, value) ->
                    obj.set<JsonNode>(key, interpolateNode(value, missing, failOnMissing))
                }
                obj
            }
            else -> node
        }

    private fun interpolateText(
        input: String,
        missing: MutableSet<String>,
        failOnMissing: Boolean,
    ): String =
        pattern.replace(input) { match ->
            val name = match.groupValues[1]
            val defaultValue = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
            val resolved = env[name]?.takeIf { it.isNotBlank() } ?: defaultValue

            if (resolved == null) {
                missing.add(name)
                if (failOnMissing) {
                    throw IllegalArgumentException(
                        "Missing required environment variable '$name' referenced by connections config.",
                    )
                }
                ""
            } else {
                resolved
            }
        }
}
