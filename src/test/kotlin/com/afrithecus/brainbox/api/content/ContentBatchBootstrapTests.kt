package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.batch.ContentBatchBootstrap
import com.afrithecus.brainbox.api.content.batch.ContentBatchRequest
import com.afrithecus.brainbox.api.content.batch.ContentBatchService
import com.afrithecus.brainbox.api.content.batch.ContentBatchSummary
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

/**
 * Phase 7.5e startup runner. The runner must be a no-op unless
 * app.content.batch.run-on-startup is true, and when it is true it must pass the
 * configured settings through to the producer exactly once.
 */
class ContentBatchBootstrapTests {

    @Test
    fun `run-on-startup false never enqueues`() {
        val service = mock(ContentBatchService::class.java)
        val properties = AppContentProperties(batch = AppContentProperties.Batch(runOnStartup = false))

        ContentBatchBootstrap(service, properties).onApplicationReady()

        verifyNoInteractions(service)
    }

    @Test
    fun `run-on-startup true enqueues the configured batch`() {
        val service = mock(ContentBatchService::class.java)
        val settings = AppContentProperties.Batch(
            runOnStartup = true,
            gradeLevel = "Grade 4",
            subject = "Mathematics",
            taskTypes = listOf("NOTES", "QUIZ"),
            language = "en",
            standardVersion = "v1",
            limit = 50,
        )
        val expected = ContentBatchRequest(
            gradeLevel = "Grade 4",
            subject = "Mathematics",
            taskTypes = listOf("NOTES", "QUIZ"),
            language = "en",
            standardVersion = "v1",
            limit = 50,
        )
        org.mockito.Mockito.`when`(service.enqueueBatch(expected)).thenReturn(
            ContentBatchSummary(
                subject = "Mathematics",
                gradeLevel = "Grade 4",
                taskTypes = listOf("NOTES", "QUIZ"),
                conceptsMatched = 2,
                conceptsQueued = 2,
                jobsEnqueued = 4,
                jobsAlreadyPresent = 0,
                truncated = false,
            )
        )

        ContentBatchBootstrap(service, AppContentProperties(batch = settings)).onApplicationReady()

        verify(service).enqueueBatch(expected)
    }
}
