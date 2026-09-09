package com.afrithecus.brainbox.api.contests.web

import com.afrithecus.brainbox.api.contests.ContestService
import com.afrithecus.brainbox.api.contests.ContestSessionService
import com.afrithecus.brainbox.api.contests.model.ContestStatus
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/** Student contest surface (doc 05 §1). */
@RestController
@RequestMapping("/contests")
class ContestController(
    private val service: ContestService,
    private val sessions: ContestSessionService,
) {

    @GetMapping("/upcoming")
    fun upcoming(@AuthenticationPrincipal currentUser: CurrentUser): List<ContestPayload> =
        service.listByWindow(currentUser.userId, ContestStatus.UPCOMING)

    @GetMapping("/ongoing")
    fun ongoing(@AuthenticationPrincipal currentUser: CurrentUser): List<ContestPayload> =
        service.listByWindow(currentUser.userId, ContestStatus.ONGOING)

    @GetMapping("/completed")
    fun completed(@AuthenticationPrincipal currentUser: CurrentUser): List<ContestPayload> =
        service.listByWindow(currentUser.userId, ContestStatus.COMPLETED)

    @GetMapping("/{contestId}")
    fun detail(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable contestId: String,
    ): ContestPayload = service.detail(currentUser.userId, contestId)

    @PostMapping("/{contestId}/register")
    fun register(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable contestId: String,
        @RequestParam(required = false) studentId: String?,
    ): RegistrationResponse {
        if (studentId != null && studentId != currentUser.userId.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot register another student")
        }
        return service.register(currentUser.userId, contestId)
    }

    @GetMapping("/{contestId}/session")
    fun session(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable contestId: String,
    ): ContestSessionResponse = sessions.start(currentUser.userId, contestId)

    @PostMapping("/{contestId}/session/sync")
    fun syncSession(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable contestId: String,
        @RequestBody body: JsonNode,
    ): Boolean = sessions.sync(currentUser.userId, contestId, body)

    @PostMapping("/{contestId}/submit")
    fun submit(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable contestId: String,
        @RequestBody answers: JsonNode,
    ): ContestResultPayload = sessions.submit(currentUser.userId, contestId, answers)

    @GetMapping("/{contestId}/leaderboard")
    fun leaderboard(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable contestId: String,
    ): LeaderboardPayload = service.leaderboard(currentUser.userId, contestId)
}
