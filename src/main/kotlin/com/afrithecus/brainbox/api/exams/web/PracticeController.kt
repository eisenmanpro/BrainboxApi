package com.afrithecus.brainbox.api.exams.web

import com.afrithecus.brainbox.api.exams.PracticeService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Personalised practice (§B7). A paper generated here is private to the learner: it is a
 * real PUBLISHED exam of `ExamScope.PERSONAL`, so the existing session/submit/result
 * routes serve it, while the catalog and other learners never see it.
 */
@RestController
@RequestMapping("/practice")
class PracticeController(private val service: PracticeService) {

    @PostMapping("/generate")
    fun generate(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: PracticeGenerateRequest,
    ): PracticePaperPayload = service.generate(currentUser, request)

    @GetMapping("/history")
    fun history(@AuthenticationPrincipal currentUser: CurrentUser): List<PracticePaperPayload> =
        service.history(currentUser)

    /** A teacher reads one of their school's learners. */
    @GetMapping("/student/{studentId}")
    fun forStudent(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable studentId: String,
    ): List<PracticePaperPayload> = service.forStudent(currentUser, studentId)

    /** A guardian reads their linked child. */
    @GetMapping("/child/{childId}")
    fun forChild(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable childId: String,
    ): List<PracticePaperPayload> = service.forChild(currentUser, childId)
}
