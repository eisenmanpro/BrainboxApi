package com.afrithecus.brainbox.api.notification.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.notification.NewsService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** News feed (doc 05 §6). */
@RestController
@RequestMapping("/news")
class NewsController(private val service: NewsService) {

    @GetMapping
    fun list(@RequestParam(required = false) category: String?): List<NewsItemPayload> = service.list(category)

    @GetMapping("/{newsId}")
    fun detail(@PathVariable newsId: String): NewsItemPayload = service.detail(newsId)
}

/** Admin news authoring. */
@RestController
@RequestMapping("/admin/news")
@PreAuthorize("hasRole('ADMIN')")
class NewsAdminController(private val service: NewsService) {

    @GetMapping
    fun list(): List<NewsItemPayload> = service.listAllForStaff()

    @PostMapping
    fun create(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: CreateNewsRequest,
    ): NewsItemPayload = service.create(current, request)
}
