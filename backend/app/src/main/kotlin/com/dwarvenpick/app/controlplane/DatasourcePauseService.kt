package com.dwarvenpick.app.controlplane

import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DatasourcePauseService(
    private val datasourcePauseRepository: DatasourcePauseRepository,
) {
    fun isPaused(datasourceId: String): Boolean = datasourcePauseRepository.isPaused(datasourceId.trim())

    fun pause(
        datasourceId: String,
        pausedBy: String? = null,
        reason: String? = null,
        expiresAt: Instant? = null,
    ) {
        datasourcePauseRepository.pause(
            datasourceId = datasourceId.trim(),
            pausedBy = pausedBy?.trim()?.ifBlank { null },
            reason = reason?.trim()?.ifBlank { null },
            expiresAt = expiresAt,
        )
    }

    fun resume(datasourceId: String) {
        datasourcePauseRepository.resume(datasourceId.trim())
    }

    fun clear() {
        datasourcePauseRepository.clear()
    }
}
