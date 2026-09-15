package com.afrithecus.brainbox.api.content.web

import com.afrithecus.brainbox.api.content.batch.CONTENT_BATCH_DEFAULT_LANGUAGE
import com.afrithecus.brainbox.api.content.batch.CONTENT_BATCH_DEFAULT_LIMIT
import com.afrithecus.brainbox.api.content.batch.CONTENT_BATCH_DEFAULT_PRACTICE_PAPERS
import com.afrithecus.brainbox.api.content.batch.CONTENT_BATCH_DEFAULT_STANDARD_VERSION
import com.afrithecus.brainbox.api.content.batch.CONTENT_BATCH_DEFAULT_TASK_TYPES
import com.afrithecus.brainbox.api.content.batch.ContentBatchCandidates
import com.afrithecus.brainbox.api.content.batch.ContentBatchRequest
import com.afrithecus.brainbox.api.content.batch.ContentBatchService
import com.afrithecus.brainbox.api.content.batch.ContentBatchSummary
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Phase 7.5e Tier 1 batch producer admin surface. ADMIN only, matching the other
 * admin controllers. Enqueuing is idempotent per generation key, so re-posting the
 * same batch reuses the existing jobs rather than duplicating them.
 */
@RestController
@RequestMapping("/admin/content/batch")
@PreAuthorize("hasRole('ADMIN')")
class ContentBatchController(private val service: ContentBatchService) {

    /** Enqueues one generation job per selected Tier 0 topic x task type. */
    @PostMapping
    fun enqueue(@RequestBody request: BatchEnqueueRequest): ContentBatchSummary =
        service.enqueueBatch(
            ContentBatchRequest(
                gradeLevel = request.gradeLevel.orEmpty(),
                subject = request.subject,
                taskTypes = request.taskTypes ?: CONTENT_BATCH_DEFAULT_TASK_TYPES,
                language = request.language ?: CONTENT_BATCH_DEFAULT_LANGUAGE,
                standardVersion = request.standardVersion ?: CONTENT_BATCH_DEFAULT_STANDARD_VERSION,
                limit = request.limit ?: CONTENT_BATCH_DEFAULT_LIMIT,
                practicePapers = request.practicePapers ?: CONTENT_BATCH_DEFAULT_PRACTICE_PAPERS,
            )
        )

    /** Sizes a run before it is enqueued. */
    @GetMapping("/candidates")
    fun candidates(
        @RequestParam gradeLevel: String?,
        @RequestParam(required = false) subject: String?,
    ): ContentBatchCandidates = ContentBatchCandidates(
        gradeLevel = gradeLevel.orEmpty().trim(),
        subject = subject?.trim()?.takeIf { it.isNotEmpty() },
        conceptsMatched = service.countCandidates(gradeLevel.orEmpty(), subject),
    )
}
