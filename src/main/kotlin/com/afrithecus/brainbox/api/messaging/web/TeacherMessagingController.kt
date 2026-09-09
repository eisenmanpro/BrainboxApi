package com.afrithecus.brainbox.api.messaging.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.messaging.MessagingService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Teacher sends incl. class fan-out (doc 05 §2.4/§2.6). */
@RestController
@RequestMapping("/messages/teacher/send")
@PreAuthorize("hasAnyRole('TEACHER','CTEACHER','GRADE_COORDINATOR','ICT_ADMIN')")
class TeacherMessagingController(
    private val service: MessagingService,
    private val userRepository: UserRepository,
) {
    @PostMapping
    fun send(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestHeader(value = "X-Message-Id", required = false) messageId: String?,
        @Valid @RequestBody request: TeacherSendMessageRequest,
    ): MessagePayload {
        val teacher = userRepository.findById(currentUser.userId)
            .orElseThrow { notFound("User not found") }
        return service.teacherSend(teacher, request, messageId)
    }
}
