package com.dwarvenpick.app

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.concurrent.ThreadPoolExecutor

@Configuration
class WebMvcAsyncConfig(
    @Value("\${dwarvenpick.web.async.core-pool-size:4}")
    private val corePoolSize: Int,
    @Value("\${dwarvenpick.web.async.max-pool-size:16}")
    private val maxPoolSize: Int,
    @Value("\${dwarvenpick.web.async.queue-capacity:100}")
    private val queueCapacity: Int,
    @Value("\${dwarvenpick.web.async.request-timeout-ms:300000}")
    private val requestTimeoutMs: Long,
) : WebMvcConfigurer {
    @Bean
    fun mvcStreamingTaskExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = this@WebMvcAsyncConfig.corePoolSize.coerceAtLeast(1)
            maxPoolSize = this@WebMvcAsyncConfig.maxPoolSize.coerceAtLeast(this.corePoolSize)
            queueCapacity = this@WebMvcAsyncConfig.queueCapacity.coerceAtLeast(0)
            setThreadNamePrefix("mvc-stream-")
            setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        }

    override fun configureAsyncSupport(configurer: AsyncSupportConfigurer) {
        configurer.setTaskExecutor(mvcStreamingTaskExecutor())
        configurer.setDefaultTimeout(requestTimeoutMs.coerceAtLeast(1_000L))
    }
}
