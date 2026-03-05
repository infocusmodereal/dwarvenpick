package com.dwarvenpick.app.controlplane

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class DatasourcePauseService {
    private val pausedDatasources = ConcurrentHashMap.newKeySet<String>()

    fun isPaused(datasourceId: String): Boolean = pausedDatasources.contains(datasourceId.trim())

    fun pause(datasourceId: String) {
        pausedDatasources.add(datasourceId.trim())
    }

    fun resume(datasourceId: String) {
        pausedDatasources.remove(datasourceId.trim())
    }
}

