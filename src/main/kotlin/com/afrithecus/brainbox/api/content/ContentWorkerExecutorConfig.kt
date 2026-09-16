package com.afrithecus.brainbox.api.content

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * H4: the single bounded executor that drains one claimed generation batch.
 *
 * The pool is created once per JVM, sized from app.content.worker.concurrency, and
 * owned by the Spring context: graceful shutdown waits for in-flight jobs before
 * the process exits. The worker submits the claimed batch and waits for every task
 * before returning, so the fixed-delay poller never overlaps its own passes and the
 * queue can never grow beyond the current batch.
 *
 * The bean is always defined, even at the default concurrency of 1, where the
 * worker runs the batch inline on the poller thread and keeps the original
 * single-transaction semantics.
 *
 * Follows the project's existing executor style (see ReportExecutorConfig).
 */
@Configuration
class ContentWorkerExecutorConfig(
    private val properties: AppContentProperties,
) {

    @Bean(name = ["generationWorkerExecutor"])
    fun generationWorkerExecutor(): ThreadPoolTaskExecutor {
        val concurrency = properties.worker.concurrency.coerceAtLeast(1)
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = concurrency
            maxPoolSize = concurrency
            // One claimed batch is submitted per poll and fully waited on, so a
            // queue as deep as the batch (never below the pool) is sufficient and
            // nothing accumulates across polls.
            queueCapacity = maxOf(properties.worker.batchSize, concurrency)
            setThreadNamePrefix("generation-worker-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
            initialize()
        }
    }
}
