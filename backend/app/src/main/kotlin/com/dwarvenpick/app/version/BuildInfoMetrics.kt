package com.dwarvenpick.app.version

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
class BuildInfoMetrics(
    meterRegistry: MeterRegistry,
    versionMetadataProvider: VersionMetadataProvider,
) {
    private val heartbeat = AtomicInteger(1)

    init {
        val metadata = versionMetadataProvider.current()
        Gauge
            .builder("dwarvenpick.build.info", heartbeat) { state -> state.get().toDouble() }
            .description("Dwarvenpick build identity and scrape heartbeat")
            .tag("service", metadata.service)
            .tag("version", metadata.version)
            .tag("source_ref", metadata.sourceRef)
            .tag("source_sha", metadata.sourceSha)
            .tag("image_tag", metadata.imageTag)
            .tag("build_tag", metadata.buildTag)
            .register(meterRegistry)
    }
}
