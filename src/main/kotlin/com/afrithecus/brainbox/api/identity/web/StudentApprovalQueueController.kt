package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.identity.StudentApprovalQueueService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The trailing half of the learner approval flow: the two queue reads the roster screen
 * needs. The decision endpoints stay in `TeacherAuthController` (same paths, POST), so the
 * read and the write of one queue are one contract.
 */
@RestController
@RequestMapping("/auth/students")
class StudentApprovalQueueController(private val service: StudentApprovalQueueService) {

    @GetMapping("/pending")
    fun pending(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) teacherCode: String?,
    ): List<PendingStudentPayload> = service.pending(current, teacherCode)

    @GetMapping("/rejected")
    fun rejected(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) teacherCode: String?,
    ): List<PendingStudentPayload> = service.rejected(current, teacherCode)
}
