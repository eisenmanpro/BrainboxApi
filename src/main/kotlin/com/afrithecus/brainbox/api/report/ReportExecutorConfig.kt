package com.afrithecus.brainbox.api.report

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/** Bounded executor for asynchronous report rendering. */
@Configuration
class ReportExecutorConfig {

    @Bean(name = ["reportExecutor"])
    fun reportExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 4
        queueCapacity = 64
        setThreadNamePrefix("report-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
        initialize()
    }
}
