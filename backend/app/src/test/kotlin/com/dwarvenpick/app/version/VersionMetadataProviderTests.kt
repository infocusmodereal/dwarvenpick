package com.dwarvenpick.app.version

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.mock.env.MockEnvironment
import java.util.Properties

class VersionMetadataProviderTests {
    @Test
    fun `environment metadata overrides build properties`() {
        val environment =
            MockEnvironment()
                .withProperty("DWARVENPICK_SOURCE_SHA", " env-sha ")
                .withProperty("DWARVENPICK_SOURCE_REF", "v0.16.0")
                .withProperty("DWARVENPICK_IMAGE_TAG", "image-1234")
                .withProperty("DWARVENPICK_BUILD_TAG", "gitlab-1234")

        val metadata = provider(buildProperties(), environment).current()

        assertThat(metadata)
            .isEqualTo(
                VersionResponse(
                    service = "dwarvenpick-backend",
                    version = "0.16.0",
                    artifact = "app",
                    group = "com.dwarvenpick",
                    buildTime = "2026-07-13T12:00:00Z",
                    sourceSha = "env-sha",
                    sourceRef = "v0.16.0",
                    imageTag = "image-1234",
                    buildTag = "gitlab-1234",
                ),
            )
    }

    @Test
    fun `missing metadata uses existing unknown defaults`() {
        assertThat(provider(null, MockEnvironment()).current())
            .isEqualTo(
                VersionResponse(
                    service = "dwarvenpick-backend",
                    version = "unknown",
                    artifact = "unknown",
                    group = "unknown",
                    buildTime = "unknown",
                    sourceSha = "unknown",
                    sourceRef = "unknown",
                    imageTag = "unknown",
                    buildTag = "unknown",
                ),
            )
    }

    @Test
    fun `build info gauge retains value and immutable identity tags`() {
        val registry = SimpleMeterRegistry()
        val metrics = BuildInfoMetrics(registry, provider(buildProperties(), MockEnvironment()))

        System.gc()

        val gauge = registry.find("dwarvenpick.build.info").gauge()
        assertThat(metrics).isNotNull
        assertThat(gauge).isNotNull
        assertThat(gauge!!.value()).isEqualTo(1.0)
        assertThat(gauge.id.tags.associate { tag -> tag.key to tag.value })
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "service" to "dwarvenpick-backend",
                    "version" to "0.16.0",
                    "source_ref" to "v0.16.0-build",
                    "source_sha" to "build-sha",
                    "image_tag" to "build-image",
                    "build_tag" to "build-job",
                ),
            )
    }

    private fun provider(
        buildProperties: BuildProperties?,
        environment: MockEnvironment,
    ): VersionMetadataProvider {
        @Suppress("UNCHECKED_CAST")
        val buildPropertiesProvider = mock(ObjectProvider::class.java) as ObjectProvider<BuildProperties>
        `when`(buildPropertiesProvider.getIfAvailable()).thenReturn(buildProperties)
        return VersionMetadataProvider(buildPropertiesProvider, environment)
    }

    private fun buildProperties(): BuildProperties =
        BuildProperties(
            Properties().apply {
                setProperty("name", "dwarvenpick-backend")
                setProperty("version", "0.16.0")
                setProperty("artifact", "app")
                setProperty("group", "com.dwarvenpick")
                setProperty("time", "2026-07-13T12:00:00Z")
                setProperty("source.sha", "build-sha")
                setProperty("source.ref", "v0.16.0-build")
                setProperty("image.tag", "build-image")
                setProperty("build.tag", "build-job")
            },
        )
}
