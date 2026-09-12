package com.afrithecus.brainbox.api.study.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.study.StudyService
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Study tools (doc 03 §9): sessions and server-computed insights. */
@RestController
@RequestMapping("/study")
class StudyController(private val service: StudyService) {

    @GetMapping("/sessions/{userId}")
    fun sessions(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<StudySessionPayload> = service.sessions(current, userId, limit)

    @PostMapping("/sessions")
    fun record(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: RecordStudySessionRequest,
    ): StudySessionPayload = service.record(current, request)

    @GetMapping("/insights/{userId}")
    fun insights(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
    ): StudyInsightsPayload = service.insights(current, userId)
}
