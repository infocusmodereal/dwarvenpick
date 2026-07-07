package com.dwarvenpick.app.query

import com.dwarvenpick.app.auth.AuthAuditEventStore
import com.dwarvenpick.app.rbac.QueryAccessPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties = [
        "dwarvenpick.auth.password-policy.min-length=8",
        "dwarvenpick.query.require-write-justification=true",
        "dwarvenpick.query.max-justification-length=32",
    ],
)
class QueryJustificationGovernanceTests {
    @Autowired
    private lateinit var queryExecutionManager: QueryExecutionManager

    @Autowired
    private lateinit var queryHistoryRepository: QueryHistoryRepository

    @Autowired
    private lateinit var queryRuntimeRepository: QueryRuntimeRepository

    @Autowired
    private lateinit var authAuditEventStore: AuthAuditEventStore

    @BeforeEach
    fun resetState() {
        queryHistoryRepository.clear()
        queryRuntimeRepository.clear()
        authAuditEventStore.clear()
    }

    @Test
    fun `read-only query does not require justification when governance is enabled`() {
        val submitted =
            queryExecutionManager.submitQuery(
                actor = "analyst",
                ipAddress = "127.0.0.1",
                request = QueryExecutionRequest(datasourceId = "simulated", sql = "select 1"),
                policy = queryPolicy(readOnly = true),
            )

        val status = waitForTerminalStatus(submitted.executionId)

        assertThat(status.status).isEqualTo("SUCCEEDED")
        assertThat(status.justification).isNull()
    }

    @Test
    fun `non-read-only query requires nonblank justification when governance is enabled`() {
        assertThatThrownBy {
            queryExecutionManager.submitQuery(
                actor = "analyst",
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = "simulated",
                        sql = "select 1",
                        justification = "   ",
                    ),
                policy = queryPolicy(readOnly = false),
            )
        }.isInstanceOf(QueryJustificationRequiredException::class.java)
            .hasMessageContaining("justification is required")

        assertThat(authAuditEventStore.snapshot())
            .anyMatch { event ->
                event.type == "query.execute" &&
                    event.outcome == "denied" &&
                    event.details["reason"] == "missing_write_justification" &&
                    event.details["credentialProfile"] == "read-write"
            }
    }

    @Test
    fun `non-read-only justification is normalized and persisted to status history and audit`() {
        val submitted =
            queryExecutionManager.submitQuery(
                actor = "analyst",
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = "simulated",
                        sql = "select 1",
                        justification = "  Emergency window\nTOPS-123\t",
                    ),
                policy = queryPolicy(readOnly = false),
            )

        val status = waitForTerminalStatus(submitted.executionId)
        val history =
            queryHistoryRepository.list(
                QueryHistoryFilter(
                    actor = "analyst",
                    isSystemAdmin = false,
                    datasourceId = null,
                    status = null,
                    from = null,
                    to = null,
                    limit = 10,
                    offset = 0,
                    actorFilter = null,
                    sortOrder = QueryHistorySortOrder.NEWEST,
                ),
            )

        assertThat(status.status).isEqualTo("SUCCEEDED")
        assertThat(status.justification).isEqualTo("Emergency window TOPS-123")
        assertThat(history).hasSize(1)
        assertThat(history.single().justification).isEqualTo("Emergency window TOPS-123")
        assertThat(authAuditEventStore.snapshot())
            .anyMatch { event ->
                event.type == "query.execute" &&
                    event.outcome == "succeeded" &&
                    event.details["justification"] == "Emergency window TOPS-123"
            }
    }

    @Test
    fun `script mode request persists justification for governed non-read-only profiles`() {
        val submitted =
            queryExecutionManager.submitQuery(
                actor = "analyst",
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = "simulated",
                        sql = "select generate_series(1,2)",
                        justification = "Script window TOPS-123",
                        scriptMode = true,
                    ),
                policy = queryPolicy(readOnly = false),
            )

        val status = waitForTerminalStatus(submitted.executionId)
        val history =
            queryHistoryRepository.list(
                QueryHistoryFilter(
                    actor = "analyst",
                    isSystemAdmin = false,
                    datasourceId = null,
                    status = null,
                    from = null,
                    to = null,
                    limit = 10,
                    offset = 0,
                    actorFilter = null,
                    sortOrder = QueryHistorySortOrder.NEWEST,
                ),
            )

        assertThat(status.status).isEqualTo("SUCCEEDED")
        assertThat(status.justification).isEqualTo("Script window TOPS-123")
        assertThat(history.single().justification).isEqualTo("Script window TOPS-123")
    }

    @Test
    fun `over-length justification is rejected before execution`() {
        assertThatThrownBy {
            queryExecutionManager.submitQuery(
                actor = "analyst",
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = "simulated",
                        sql = "select 1",
                        justification = "x".repeat(33),
                    ),
                policy = queryPolicy(readOnly = false),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("32 characters or fewer")
    }

    @Test
    fun `justification does not bypass read-only enforcement`() {
        assertThatThrownBy {
            queryExecutionManager.submitQuery(
                actor = "analyst",
                ipAddress = "127.0.0.1",
                request =
                    QueryExecutionRequest(
                        datasourceId = "simulated",
                        sql = "delete from warehouse.customer",
                        justification = "Maintenance window TOPS-123",
                    ),
                policy = queryPolicy(readOnly = true),
            )
        }.isInstanceOf(QueryReadOnlyViolationException::class.java)
    }

    private fun queryPolicy(readOnly: Boolean): QueryAccessPolicy =
        QueryAccessPolicy(
            credentialProfile = if (readOnly) "read-only" else "read-write",
            readOnly = readOnly,
            maxRowsPerQuery = 100,
            maxRuntimeSeconds = 30,
            concurrencyLimit = 2,
        )

    private fun waitForTerminalStatus(executionId: String): QueryExecutionStatusResponse {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < 5_000) {
            val status =
                queryExecutionManager.getExecutionStatus(
                    actor = "analyst",
                    isSystemAdmin = false,
                    executionId = executionId,
                )
            if (status.status !in setOf("QUEUED", "RUNNING")) {
                return status
            }
            Thread.sleep(25)
        }
        error("Query $executionId did not reach a terminal status.")
    }
}
