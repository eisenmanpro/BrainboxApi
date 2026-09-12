package com.afrithecus.brainbox.api.gradebook.web

import com.afrithecus.brainbox.api.gradebook.GradebookService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Learner- and parent-facing published grades (docs/ongoing/api_gradebook_changes.md
 * section 9). Only published assessments are returned and the band is computed
 * server-side with the school's grading config. Read-only.
 */
@RestController
class PublishedGradesController(
    private val service: GradebookService,
) {

    @GetMapping("/student/grades")
    fun studentGrades(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) term: String?,
    ): List<PublishedGradePayload> = service.studentGrades(currentUser, term)

    @GetMapping("/parent/child/{childId}/grades")
    fun childGrades(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable childId: String,
        @RequestParam(required = false) term: String?,
    ): List<PublishedGradePayload> = service.childGrades(currentUser, childId, term)
}
