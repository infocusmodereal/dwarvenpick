package com.dwarvenpick.app.datasource

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DatasourcePoolCapacityTests {
    @Test
    fun `one credential profile owns one configured pool`() {
        assertThat(DatasourcePoolCapacity.calculate(maximumPoolSizePerProfile = 5, credentialProfileCount = 1))
            .isEqualTo(
                DatasourcePoolCapacity(
                    credentialProfileCount = 1,
                    maximumPoolSizePerProfile = 5,
                    maximumConnectionsPerInstance = 5,
                ),
            )
    }

    @Test
    fun `two credential profiles double the per instance pool ceiling`() {
        val capacity = DatasourcePoolCapacity.calculate(maximumPoolSizePerProfile = 5, credentialProfileCount = 2)

        assertThat(capacity)
            .isEqualTo(
                DatasourcePoolCapacity(
                    credentialProfileCount = 2,
                    maximumPoolSizePerProfile = 5,
                    maximumConnectionsPerInstance = 10,
                ),
            )
        assertThat(capacity.maximumConnections(backendReplicas = 2)).isEqualTo(20)
        assertThat(capacity.maximumConnections(backendReplicas = 3)).isEqualTo(30)
    }
}
