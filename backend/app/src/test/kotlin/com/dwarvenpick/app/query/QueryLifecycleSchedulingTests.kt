package com.dwarvenpick.app.query

import com.dwarvenpick.app.datasource.DatasourcePoolManager
import com.dwarvenpick.app.resource.ResourceRetentionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SpringBootTest
class QueryLifecycleSchedulingTests {
    @Autowired
    @Qualifier("taskScheduler")
    private lateinit var defaultScheduler: ThreadPoolTaskScheduler

    @Autowired
    @Qualifier("queryLifecycleTaskScheduler")
    private lateinit var lifecycleScheduler: ThreadPoolTaskScheduler

    @Test
    fun `lifecycle jobs use an isolated scheduler while existing jobs keep the conventional scheduler`() {
        assertThat(defaultScheduler).isNotSameAs(lifecycleScheduler)
        assertThat(defaultScheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(1)
        assertThat(lifecycleScheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(2)

        assertThat(scheduled(QueryExecutionManager::class.java, "pollRemoteControlRequests").scheduler)
            .isEqualTo("queryLifecycleTaskScheduler")
        assertThat(scheduled(QueryLifecycleMetrics::class.java, "refresh").scheduler)
            .isEqualTo("queryLifecycleTaskScheduler")

        assertThat(scheduled(QueryExecutionManager::class.java, "sendSseHeartbeat").scheduler).isEmpty()
        assertThat(scheduled(QueryExecutionManager::class.java, "cleanupExpiredSessions").scheduler).isEmpty()
        assertThat(scheduled(DatasourcePoolManager::class.java, "publishPoolMetricsSnapshot").scheduler).isEmpty()
        assertThat(scheduled(QueryRetentionService::class.java, "pruneHistoryAndAudit").scheduler).isEmpty()
        assertThat(scheduled(ResourceRetentionService::class.java, "pruneResourceVersions").scheduler).isEmpty()
    }

    @Test
    fun `blocked lifecycle workers do not delay the default scheduler`() {
        val lifecycleStarted = CountDownLatch(2)
        val releaseLifecycle = CountDownLatch(1)
        repeat(2) {
            lifecycleScheduler.execute {
                lifecycleStarted.countDown()
                releaseLifecycle.await(5, TimeUnit.SECONDS)
            }
        }

        try {
            assertThat(lifecycleStarted.await(2, TimeUnit.SECONDS)).isTrue()
            val defaultRan = CountDownLatch(1)
            defaultScheduler.execute { defaultRan.countDown() }
            assertThat(defaultRan.await(1, TimeUnit.SECONDS)).isTrue()
        } finally {
            releaseLifecycle.countDown()
        }
    }

    private fun scheduled(
        type: Class<*>,
        methodName: String,
    ): Scheduled =
        requireNotNull(type.getDeclaredMethod(methodName).getAnnotation(Scheduled::class.java)) {
            "$methodName must remain scheduled."
        }
}
