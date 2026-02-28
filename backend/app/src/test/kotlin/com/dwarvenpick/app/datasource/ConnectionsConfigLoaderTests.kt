package com.dwarvenpick.app.datasource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ConnectionsConfigLoaderTests {
    @Test
    fun `loads structured config and interpolates env vars`() {
        val yaml =
            """
            connections:
              - name: starrocks-adhoc-dev
                engine: STARROCKS
                host: dev.example.com
                port: 9030
                credentialProfiles:
                  read-only:
                    username: reader
                    password: ${'$'}{ENV:RO_PASSWORD}
                  read-write:
                    username: writer
                    password: ${'$'}{ENV:RW_PASSWORD:-fallback}
            """.trimIndent()

        val temp = Files.createTempFile("dwarvenpick-connections", ".yaml")
        Files.writeString(temp, yaml)

        val loader =
            ConnectionsConfigLoader(
                envInterpolator = EnvInterpolator(env = mapOf("RO_PASSWORD" to "secret-ro")),
            )

        val result = loader.load(temp, failOnMissingEnv = true)
        assertEquals(1, result.size)
        val spec = result.single()
        assertEquals("starrocks-adhoc-dev", spec.name)
        assertEquals(DatasourceEngine.STARROCKS, spec.engine)
        assertEquals("dev.example.com", spec.host)
        assertEquals(9030, spec.port)
        assertEquals("secret-ro", spec.credentialProfiles.getValue("read-only").password)
        assertEquals("fallback", spec.credentialProfiles.getValue("read-write").password)
    }

    @Test
    fun `fails fast when env var is missing and no default is provided`() {
        val yaml =
            """
            connections:
              - name: starrocks-adhoc-dev
                engine: STARROCKS
                host: dev.example.com
                port: 9030
                credentialProfiles:
                  read-only:
                    username: reader
                    password: ${'$'}{ENV:MISSING_PASSWORD}
            """.trimIndent()

        val temp = Files.createTempFile("dwarvenpick-connections", ".yaml")
        Files.writeString(temp, yaml)

        val loader = ConnectionsConfigLoader(envInterpolator = EnvInterpolator(env = emptyMap()))

        assertThrows(IllegalArgumentException::class.java) {
            loader.load(temp, failOnMissingEnv = true)
        }
    }

    @Test
    fun `replaces missing env var with blank string when failOnMissingEnv is false`() {
        val yaml =
            """
            connections:
              - name: starrocks-adhoc-dev
                engine: STARROCKS
                host: dev.example.com
                port: 9030
                credentialProfiles:
                  read-only:
                    username: reader
                    password: ${'$'}{ENV:MISSING_PASSWORD}
            """.trimIndent()

        val temp = Files.createTempFile("dwarvenpick-connections", ".yaml")
        Files.writeString(temp, yaml)

        val loader = ConnectionsConfigLoader(envInterpolator = EnvInterpolator(env = emptyMap()))

        val result = loader.load(temp, failOnMissingEnv = false)
        val password =
            result
                .single()
                .credentialProfiles
                .getValue("read-only")
                .password
        assertEquals("", password)
    }

    @Test
    fun `loads legacy config format`() {
        val yaml =
            """
            starrocks-adhoc-dev:
              read-only:
                host: dev.example.com
                port: 9030
                user: reader
                password: ${'$'}{ENV:RO_PASSWORD}
              read-write:
                host: dev.example.com
                port: 9030
                user: writer
                password: ${'$'}{ENV:RW_PASSWORD}
            """.trimIndent()

        val temp = Files.createTempFile("dwarvenpick-connections", ".yaml")
        Files.writeString(temp, yaml)

        val loader =
            ConnectionsConfigLoader(
                envInterpolator =
                    EnvInterpolator(
                        env =
                            mapOf(
                                "RO_PASSWORD" to "secret-ro",
                                "RW_PASSWORD" to "secret-rw",
                            ),
                    ),
            )

        val result = loader.load(temp, failOnMissingEnv = true)
        assertEquals(1, result.size)
        val spec = result.single()
        assertEquals("starrocks-adhoc-dev", spec.name)
        assertEquals(DatasourceEngine.STARROCKS, spec.engine)
        assertEquals("dev.example.com", spec.host)
        assertEquals(9030, spec.port)
        assertEquals("secret-ro", spec.credentialProfiles.getValue("read-only").password)
        assertEquals("secret-rw", spec.credentialProfiles.getValue("read-write").password)
    }
}
