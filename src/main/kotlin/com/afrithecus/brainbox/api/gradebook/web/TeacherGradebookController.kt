package com.afrithecus.brainbox.api.gradebook.web

import com.afrithecus.brainbox.api.gradebook.GradebookService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Teacher gradebook (docs/ongoing/api_gradebook_changes.md). Exam/homework rows
 * are aggregated client-side; this surface owns manual assessments and grades.
 */
@RestController
@RequestMapping("/teacher")
class TeacherGradebookController(
    private val service: GradebookService,
) {

    @GetMapping("/classes/{classId}/gradebook")
    fun gradebook(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
    ): List<GradebookEntryPayload> = service.gradebook(currentUser, classId)

    @PostMapping("/gradebook")
    fun submitGrade(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody entry: GradebookEntryPayload,
    ): GradebookEntryPayload = service.submitGrade(currentUser, entry)

    @PutMapping("/gradebook/{entryId}")
    fun updateGrade(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable entryId: String,
        @RequestBody entry: GradebookEntryPayload,
    ): GradebookEntryPayload = service.updateGrade(currentUser, entryId, entry)

    @DeleteMapping("/gradebook/{entryId}")
    fun deleteGrade(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable entryId: String,
    ): ResponseEntity<Void> {
        service.deleteGrade(currentUser, entryId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/gradebook/bulk")
    fun submitBulkGrades(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody entries: List<GradebookEntryPayload>,
    ): List<GradebookEntryPayload> = service.bulkGrades(currentUser, entries)

    @GetMapping("/classes/{classId}/assessments")
    fun assessments(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
    ): List<GradebookAssessmentPayload> = service.assessments(currentUser, classId)

    @PostMapping("/classes/{classId}/assessments")
    fun createAssessment(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
        @RequestBody assessment: GradebookAssessmentPayload,
    ): GradebookAssessmentPayload = service.createAssessment(currentUser, classId, assessment)

    @PutMapping("/classes/{classId}/assessments/{assessmentId}")
    fun updateAssessment(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
        @PathVariable assessmentId: String,
        @RequestBody assessment: GradebookAssessmentPayload,
    ): GradebookAssessmentPayload = service.updateAssessment(currentUser, classId, assessmentId, assessment)

    @DeleteMapping("/classes/{classId}/assessments/{assessmentId}")
    fun deleteAssessment(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
        @PathVariable assessmentId: String,
    ): ResponseEntity<Void> {
        service.deleteAssessment(currentUser, classId, assessmentId)
        return ResponseEntity.noContent().build()
    }
}
