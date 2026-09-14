package com.afrithecus.brainbox.api.content.web

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.content.GenerationJobService
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Phase 7.5a generation submit/poll surface. A teacher submits a request and
 * polls the durable job; the worker (this JVM in both/worker mode, or a separate
 * deployment) drains it. Teacher-only, matching the review surface roles.
 */
@RestController
@RequestMapping("/teacher/content")
@PreAuthorize("hasAnyRole('TEACHER','CTEACHER','GRADE_COORDINATOR','ICT_ADMIN')")
class GenerationJobController(
    private val service: GenerationJobService,
    private val contentUnits: ContentUnitRepository,
    private val users: UserRepository,
) {

    /** Enqueues a generation request and returns the job in its current state. */
    @PostMapping("/generate")
    fun generate(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody request: GenerateContentRequest,
    ): GenerationJobPayload {
        actor(currentUser)
        val job = service.enqueue(request.toGenerationRequest())
        return payload(job)
    }

    /** Polls one generation job plus the unit it produced, when it exists. */
    @GetMapping("/jobs/{jobId}")
    fun job(@PathVariable jobId: String): GenerationJobPayload {
        val id = runCatching { UUID.fromString(jobId) }
            .getOrElse { throw invalidArgument("jobId is not a UUID: " + jobId) }
        val job = service.find(id) ?: throw notFound("Generation job not found")
        return payload(job)
    }

    private fun actor(currentUser: CurrentUser) =
        users.findById(currentUser.userId).orElseThrow { notFound("User not found") }

    private fun payload(job: GenerationJobEntity) = GenerationJobPayload(
        id = job.id.toString(),
        generationKey = job.generationKey,
        taskType = job.taskType,
        status = job.status,
        attempts = job.attempts,
        maxAttempts = job.maxAttempts,
        lastError = job.lastError,
        runId = job.runId?.toString(),
        unitId = contentUnits.findByGenerationKey(job.generationKey)?.id?.toString(),
        createdAt = job.createdAt.toEpochMilli(),
        updatedAt = job.updatedAt.toEpochMilli(),
    )

    private fun GenerateContentRequest.toGenerationRequest() = GenerationRequest(
        generationKey = required(generationKey, "generationKey"),
        taskType = required(taskType, "taskType"),
        conceptCode = conceptCode?.trim()?.takeIf { it.isNotEmpty() },
        subject = required(subject, "subject"),
        gradeLevel = required(gradeLevel, "gradeLevel"),
        language = required(language, "language"),
        standardVersion = required(standardVersion, "standardVersion"),
        difficulty = difficulty,
        notes = notes?.trim()?.takeIf { it.isNotEmpty() },
    )

    private fun required(value: String?, name: String): String =
        value?.trim()?.takeIf { it.isNotEmpty() } ?: throw invalidArgument(name + " is required")
}
