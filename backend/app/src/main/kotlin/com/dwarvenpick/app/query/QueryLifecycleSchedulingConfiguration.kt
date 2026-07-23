package com.dwarvenpick.app.query

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration
class QueryLifecycleSchedulingConfiguration {
    @Bean(name = ["taskScheduler"])
    fun taskScheduler(): ThreadPoolTaskScheduler = scheduler(poolSize = 1, threadNamePrefix = "scheduling-")

    @Bean(name = ["queryLifecycleTaskScheduler"])
    fun queryLifecycleTaskScheduler(): ThreadPoolTaskScheduler = scheduler(poolSize = 2, threadNamePrefix = "dwarvenpick-query-lifecycle-")

    private fun scheduler(
        poolSize: Int,
        threadNamePrefix: String,
    ): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            setPoolSize(poolSize)
            setThreadNamePrefix(threadNamePrefix)
            setWaitForTasksToCompleteOnShutdown(false)
            setRemoveOnCancelPolicy(true)
        }
}
