package com.dwarvenpick.app.datasource

import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

data class TlsCertificatePaths(
    val caCertificatePem: Path?,
    val clientCertificatePem: Path?,
    val clientKeyPem: Path?,
    val trustStore: Path?,
    val keyStore: Path?,
)

@Service
class TlsCertificateStore(
    private val driverRegistryProperties: DriverRegistryProperties,
) {
    private val baseDir: Path = Path.of(driverRegistryProperties.externalDir).resolve("tls")
    private val storePassword = "dwarvenpick"

    fun status(datasourceId: String): TlsCertificateStatus {
        val paths = resolvePaths(datasourceId)
        return TlsCertificateStatus(
            hasCaCertificate = paths.caCertificatePem != null,
            hasClientCertificate = paths.clientCertificatePem != null,
            hasClientKey = paths.clientKeyPem != null,
        )
    }

    fun resolvePaths(datasourceId: String): TlsCertificatePaths {
        val dir = baseDir.resolve(datasourceId)
        return TlsCertificatePaths(
            caCertificatePem = dir.resolve("ca.pem").takeIf { Files.exists(it) },
            clientCertificatePem = dir.resolve("client.pem").takeIf { Files.exists(it) },
            clientKeyPem = dir.resolve("client.key").takeIf { Files.exists(it) },
            trustStore = dir.resolve("truststore.p12").takeIf { Files.exists(it) },
            keyStore = dir.resolve("keystore.p12").takeIf { Files.exists(it) },
        )
    }

    fun apply(
        datasourceId: String,
        request: TlsCertificateRequest,
    ) {
        Files.createDirectories(baseDir)
        val dir = baseDir.resolve(datasourceId)
        Files.createDirectories(dir)

        applyPemUpdate(
            targetPath = dir.resolve("ca.pem"),
            pem = request.caCertificatePem,
            validate = ::validateCertificatePem,
        )
        applyPemUpdate(
            targetPath = dir.resolve("client.pem"),
            pem = request.clientCertificatePem,
            validate = ::validateCertificatePem,
        )
        applyPemUpdate(
            targetPath = dir.resolve("client.key"),
            pem = request.clientKeyPem,
            validate = ::validatePrivateKeyPem,
        )

        rebuildTrustStore(dir)
        rebuildKeyStore(dir)
    }

    fun clear(datasourceId: String) {
        val dir = baseDir.resolve(datasourceId)
        deleteIfExists(dir.resolve("ca.pem"))
        deleteIfExists(dir.resolve("client.pem"))
        deleteIfExists(dir.resolve("client.key"))
        deleteIfExists(dir.resolve("truststore.p12"))
        deleteIfExists(dir.resolve("keystore.p12"))
    }

    fun storePassword(): String = storePassword

    private fun applyPemUpdate(
        targetPath: Path,
        pem: String?,
        validate: (String) -> Unit,
    ) {
        if (pem == null) {
            return
        }

        val normalized = pem.trim()
        if (normalized.isBlank()) {
            deleteIfExists(targetPath)
            return
        }

        validate(normalized)
        Files.writeString(targetPath, normalized + "\n", StandardCharsets.UTF_8)
        tryMakeOwnerReadWriteOnly(targetPath)
    }

    private fun validateCertificatePem(pem: String) {
        if (!pem.contains("BEGIN CERTIFICATE")) {
            throw IllegalArgumentException("Certificate must be PEM encoded and include 'BEGIN CERTIFICATE'.")
        }

        // Ensure it parses as at least one X509 certificate.
        parseCertificates(pem).firstOrNull()
            ?: throw IllegalArgumentException("Certificate PEM does not contain a valid X.509 certificate.")
    }

    private fun validatePrivateKeyPem(pem: String) {
        if (pem.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
            throw IllegalArgumentException(
                "Encrypted private keys are not supported. Provide an unencrypted PKCS#8 private key PEM.",
            )
        }
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw IllegalArgumentException(
                "PKCS#1 RSA private keys are not supported. Convert to PKCS#8 (BEGIN PRIVATE KEY).",
            )
        }
        if (!pem.contains("BEGIN PRIVATE KEY")) {
            throw IllegalArgumentException("Private key must be PEM encoded and include 'BEGIN PRIVATE KEY'.")
        }

        parsePrivateKey(pem)
    }

    private fun rebuildTrustStore(dir: Path) {
        val caPemPath = dir.resolve("ca.pem")
        val trustStorePath = dir.resolve("truststore.p12")

        if (!Files.exists(caPemPath)) {
            deleteIfExists(trustStorePath)
            return
        }

        val caPem = Files.readString(caPemPath)
        val certs = parseCertificates(caPem)
        if (certs.isEmpty()) {
            deleteIfExists(trustStorePath)
            return
        }

        val trustStore = KeyStore.getInstance("PKCS12")
        trustStore.load(null, storePassword.toCharArray())
        certs.forEachIndexed { index, certificate ->
            trustStore.setCertificateEntry("ca-${index + 1}", certificate)
        }
        writeKeyStore(trustStorePath, trustStore)
    }

    private fun rebuildKeyStore(dir: Path) {
        val clientCertPath = dir.resolve("client.pem")
        val clientKeyPath = dir.resolve("client.key")
        val keyStorePath = dir.resolve("keystore.p12")

        if (!Files.exists(clientCertPath) || !Files.exists(clientKeyPath)) {
            deleteIfExists(keyStorePath)
            return
        }

        val certificatePem = Files.readString(clientCertPath)
        val keyPem = Files.readString(clientKeyPath)
        val certificates = parseCertificates(certificatePem)
        if (certificates.isEmpty()) {
            deleteIfExists(keyStorePath)
            return
        }

        val privateKey = parsePrivateKey(keyPem)
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, storePassword.toCharArray())
        keyStore.setKeyEntry(
            "client",
            privateKey,
            storePassword.toCharArray(),
            certificates.toTypedArray(),
        )
        writeKeyStore(keyStorePath, keyStore)
    }

    private fun writeKeyStore(
        path: Path,
        keyStore: KeyStore,
    ) {
        Files.createDirectories(path.parent)
        Files.newOutputStream(path).use { stream ->
            keyStore.store(stream, storePassword.toCharArray())
        }
        tryMakeOwnerReadWriteOnly(path)
    }

    private fun parseCertificates(pem: String): List<X509Certificate> {
        val blocks = extractPemBlocks(pem, "CERTIFICATE")
        if (blocks.isEmpty()) {
            return emptyList()
        }
        val factory = CertificateFactory.getInstance("X.509")
        return blocks.mapNotNull { block ->
            val decoded = Base64.getMimeDecoder().decode(block)
            factory.generateCertificate(ByteArrayInputStream(decoded)) as? X509Certificate
        }
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        val blocks = extractPemBlocks(pem, "PRIVATE KEY")
        if (blocks.isEmpty()) {
            throw IllegalArgumentException("Private key PEM does not contain a PKCS#8 private key block.")
        }

        val decoded = Base64.getMimeDecoder().decode(blocks.first())
        val spec = PKCS8EncodedKeySpec(decoded)
        val candidates = listOf("RSA", "EC", "DSA")
        candidates.forEach { algorithm ->
            runCatching {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec)
            }
        }
        throw IllegalArgumentException("Unable to parse PKCS#8 private key. Supported algorithms: RSA, EC, DSA.")
    }

    private fun extractPemBlocks(
        pem: String,
        label: String,
    ): List<String> {
        val begin = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        val blocks = mutableListOf<String>()
        val lines = pem.lines()
        var inBlock = false
        val buffer = StringBuilder()

        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed == begin) {
                inBlock = true
                buffer.clear()
                return@forEach
            }
            if (trimmed == end) {
                if (inBlock && buffer.isNotBlank()) {
                    blocks.add(buffer.toString())
                }
                inBlock = false
                buffer.clear()
                return@forEach
            }
            if (inBlock) {
                buffer.append(trimmed)
            }
        }

        return blocks
    }

    private fun deleteIfExists(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }

    private fun tryMakeOwnerReadWriteOnly(path: Path) {
        runCatching {
            val permissions =
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                )
            Files.setPosixFilePermissions(path, permissions)
        }
    }
}
