package com.afrithecus.brainbox.api.homework.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.homework.TeacherHomeworkService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Teacher homework endpoints (web homework contract). */
@RestController
@RequestMapping("/teacher/homework")
@PreAuthorize("hasAnyRole('TEACHER','CTEACHER','GRADE_COORDINATOR','ICT_ADMIN')")
class TeacherHomeworkController(
    private val service: TeacherHomeworkService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun list(@AuthenticationPrincipal currentUser: CurrentUser): List<HomeworkPayload> =
        service.list(teacher(currentUser))

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: HomeworkUpsertRequest,
    ): HomeworkPayload = service.upsert(teacher(currentUser), request)

    @PutMapping("/{homeworkId}")
    fun update(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable homeworkId: String,
        @Valid @RequestBody request: HomeworkUpsertRequest,
    ): HomeworkPayload {
        val merged = request.copy(id = homeworkId)
        return service.upsert(teacher(currentUser), merged)
    }

    @GetMapping("/{homeworkId}")
    fun get(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable homeworkId: String,
    ): HomeworkPayload = service.get(teacher(currentUser), homeworkId)

    @DeleteMapping("/{homeworkId}")
    fun delete(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable homeworkId: String,
    ): ResponseEntity<Void> {
        service.delete(teacher(currentUser), homeworkId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @GetMapping("/{homeworkId}/submissions")
    fun submissions(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable homeworkId: String,
    ): List<SubmissionPayload> = service.submissions(teacher(currentUser), homeworkId)

    @PostMapping("/grade/{submissionId}")
    fun grade(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable submissionId: String,
        @Valid @RequestBody request: GradeSubmissionRequest,
    ): SubmissionPayload = service.grade(teacher(currentUser), submissionId, request)

    @PostMapping("/return/{submissionId}")
    fun returnSubmission(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable submissionId: String,
        @RequestBody(required = false) request: ReturnSubmissionRequest?,
    ): SubmissionPayload = service.returnSubmission(teacher(currentUser), submissionId, request ?: ReturnSubmissionRequest())

    @PostMapping("/{homeworkId}/grade-bulk")
    fun gradeBulk(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable homeworkId: String,
        @Valid @RequestBody items: List<BulkGradeItem>,
    ): List<SubmissionPayload> = service.gradeBulk(teacher(currentUser), homeworkId, items)

    @PostMapping("/{homeworkId}/archive")
    fun archive(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable homeworkId: String,
    ): ResponseEntity<Void> {
        service.archive(teacher(currentUser), homeworkId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @GetMapping("/progress")
    fun progress(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam ids: List<String>,
    ): List<HomeworkProgressItem> = service.progress(teacher(currentUser), ids)
}
