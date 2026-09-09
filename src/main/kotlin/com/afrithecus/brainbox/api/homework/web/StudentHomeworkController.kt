package com.afrithecus.brainbox.api.homework.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.homework.StudentHomeworkService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Student homework surface. */
@RestController
@RequestMapping("/homework")
class StudentHomeworkController(
    private val service: StudentHomeworkService,
    private val userRepository: UserRepository,
) {
    private fun student(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun list(@AuthenticationPrincipal currentUser: CurrentUser): List<HomeworkPayload> =
        service.myHomework(student(currentUser))

    @GetMapping("/{homeworkId}")
    fun detail(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable homeworkId: String,
    ): HomeworkPayload = service.detail(student(currentUser), homeworkId)

    @PostMapping("/{homeworkId}/submit")
    fun submit(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable homeworkId: String,
        @Valid @RequestBody request: StudentSubmitRequest,
    ): HomeworkPayload = service.submit(student(currentUser), homeworkId, request)
}
