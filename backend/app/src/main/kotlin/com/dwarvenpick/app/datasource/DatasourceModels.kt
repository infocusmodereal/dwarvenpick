package com.dwarvenpick.app.datasource

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.time.Instant

enum class DatasourceEngine {
    POSTGRESQL,
    MYSQL,
    MARIADB,
    TRINO,
    STARROCKS,
    VERTICA,
}

enum class TlsMode {
    DISABLE,
    REQUIRE,
}

data class PoolSettings(
    @field:Positive(message = "maximumPoolSize must be positive.")
    val maximumPoolSize: Int = 5,
    @field:Positive(message = "minimumIdle must be positive.")
    val minimumIdle: Int = 1,
    @field:Positive(message = "connectionTimeoutMs must be positive.")
    val connectionTimeoutMs: Long = 30_000,
    @field:Positive(message = "idleTimeoutMs must be positive.")
    val idleTimeoutMs: Long = 600_000,
)

data class TlsSettings(
    val mode: TlsMode = TlsMode.DISABLE,
    val verifyServerCertificate: Boolean = true,
    val allowSelfSigned: Boolean = false,
)

data class TlsCertificateRequest(
    val caCertificatePem: String? = null,
    val clientCertificatePem: String? = null,
    val clientKeyPem: String? = null,
)

data class TlsCertificateStatus(
    val hasCaCertificate: Boolean = false,
    val hasClientCertificate: Boolean = false,
    val hasClientKey: Boolean = false,
)

data class DriverDescriptorResponse(
    val driverId: String,
    val engine: String,
    val driverClass: String,
    val source: String,
    val available: Boolean,
    val description: String,
    val message: String,
    val version: String?,
)

data class ManagedDatasourceResponse(
    val id: String,
    val name: String,
    val engine: String,
    val host: String,
    val port: Int,
    val database: String?,
    val driverId: String,
    val driverClass: String,
    val pool: PoolSettings,
    val tls: TlsSettings,
    val tlsCertificates: TlsCertificateStatus = TlsCertificateStatus(),
    val options: Map<String, String>,
    val credentialProfiles: List<CredentialProfileResponse>,
)

data class CredentialProfileResponse(
    val profileId: String,
    val username: String,
    val description: String?,
    val encryptionKeyId: String,
    val updatedAt: String,
)

data class CreateDatasourceRequest(
    @field:NotBlank(message = "Datasource name is required.")
    val name: String = "",
    val engine: DatasourceEngine = DatasourceEngine.POSTGRESQL,
    @field:NotBlank(message = "Datasource host is required.")
    val host: String = "",
    @field:Min(1)
    @field:Max(65535)
    val port: Int = 5432,
    val database: String? = null,
    val driverId: String? = null,
    val pool: PoolSettings = PoolSettings(),
    val tls: TlsSettings = TlsSettings(),
    val tlsCertificates: TlsCertificateRequest? = null,
    val options: Map<String, String> = emptyMap(),
)

data class UpdateDatasourceRequest(
    val name: String? = null,
    val host: String? = null,
    @field:Min(1)
    @field:Max(65535)
    val port: Int? = null,
    val database: String? = null,
    val driverId: String? = null,
    val pool: PoolSettings? = null,
    val tls: TlsSettings? = null,
    val tlsCertificates: TlsCertificateRequest? = null,
    val options: Map<String, String>? = null,
)

data class UpsertCredentialProfileRequest(
    val username: String = "",
    val password: String? = null,
    val description: String? = null,
)

data class TestConnectionRequest(
    @field:NotBlank(message = "credentialProfile is required.")
    val credentialProfile: String = "",
    val tls: TlsSettings? = null,
    val validationQuery: String = "SELECT 1",
)

data class TestConnectionResponse(
    val success: Boolean,
    val datasourceId: String,
    val credentialProfile: String,
    val driverId: String,
    val driverClass: String,
    val message: String,
    val testedAt: String = Instant.now().toString(),
)

data class ReencryptCredentialsResponse(
    val updatedProfiles: Int,
    val activeKeyId: String,
    val message: String,
)

data class PoolMetricsResponse(
    val key: String,
    val datasourceId: String,
    val credentialProfile: String,
    val activeConnections: Int,
    val idleConnections: Int,
    val totalConnections: Int,
)
