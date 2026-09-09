package com.afrithecus.brainbox.api.exams.web

import com.afrithecus.brainbox.api.exams.ExamCatalogService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Student exam hub & catalog (doc 02 §2). Authenticated; scope-filtered and
 * answer-key-stripped by the service.
 */
@RestController
@RequestMapping("/exams")
class ExamController(private val catalog: ExamCatalogService) {

    @GetMapping("/hub/state")
    fun hubState(@AuthenticationPrincipal currentUser: CurrentUser): HubState =
        catalog.hubState(currentUser.userId)

    @GetMapping("/hub/list")
    fun hubList(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam tab: String,
    ): List<ExamCard> = catalog.listByTab(currentUser.userId, tab)

    @GetMapping("/hub/all")
    fun hubAll(@AuthenticationPrincipal currentUser: CurrentUser): List<ExamCard> =
        catalog.allCards(currentUser.userId)

    @GetMapping
    fun listExams(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) subject: String?,
        @RequestParam(required = false) difficulty: Int?,
    ): List<ExamSummary> = catalog.listExams(currentUser.userId, subject, difficulty)

    @GetMapping("/{id}")
    fun examDetail(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): ExamDetail = catalog.detail(currentUser.userId, id)
}
