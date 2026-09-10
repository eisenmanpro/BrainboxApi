package com.afrithecus.brainbox.api.mastery.web

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.mastery.MasteryService
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Topic mastery (doc 03 §6). */
@RestController
@RequestMapping("/mastery")
class MasteryController(private val service: MasteryService) {

    @GetMapping("/user/{userId}")
    fun overview(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
    ): MasteryOverviewPayload = service.overview(requireSelf(current, userId))

    @GetMapping("/user/{userId}/subject/{subject}")
    fun subjectMastery(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
        @PathVariable subject: String,
    ): SubjectMasterySummaryPayload = service.subjectMastery(requireSelf(current, userId), subject)

    @GetMapping("/user/{userId}/weak-topics")
    fun weakTopics(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
    ): List<TopicMasteryPayload> = service.weakTopics(requireSelf(current, userId))

    @PostMapping("/user/{userId}/update")
    fun update(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
        @Valid @RequestBody request: MasteryUpdateRequest,
    ): TopicMasteryPayload = service.update(requireSelf(current, userId), request)

    private fun requireSelf(current: CurrentUser, userId: String): java.util.UUID {
        if (userId != current.userId.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot access another user's mastery")
        }
        return current.userId
    }
}
