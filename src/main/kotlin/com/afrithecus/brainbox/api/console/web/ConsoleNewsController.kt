package com.afrithecus.brainbox.api.console.web

import com.afrithecus.brainbox.api.identity.PlatformAccessService
import com.afrithecus.brainbox.api.identity.PlatformPermission
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.notification.NewsService
import com.afrithecus.brainbox.api.notification.web.CreateNewsRequest
import com.afrithecus.brainbox.api.notification.web.NewsItemPayload
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public news management for the console.
 *
 * The public feed (`GET /news`) already serves published articles to everyone; this is the
 * write side, gated on `NEWS_MANAGE` rather than the ADMIN role, so "can publish to the public
 * feed" is a capability an operator holds deliberately. The news service itself keeps its
 * admin check, which the console's caller satisfies.
 */
@RestController
@RequestMapping("/admin/console/news")
@PreAuthorize("hasRole(\'ADMIN\')")
class ConsoleNewsController(
    private val news: NewsService,
    private val access: PlatformAccessService,
) {

    /** Every article, drafts included. */
    @GetMapping
    fun list(@AuthenticationPrincipal current: CurrentUser): List<NewsItemPayload> {
        access.requirePermission(current, PlatformPermission.NEWS_MANAGE)
        return news.listAllForStaff()
    }

    /** Creates an article; `status` decides whether it is public immediately. */
    @PostMapping
    fun create(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: CreateNewsRequest,
    ): NewsItemPayload {
        access.requirePermission(current, PlatformPermission.NEWS_MANAGE)
        return news.create(current, request)
    }

    @PostMapping("/{newsId}")
    fun update(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable newsId: String,
        @Valid @RequestBody request: CreateNewsRequest,
    ): NewsItemPayload {
        access.requirePermission(current, PlatformPermission.NEWS_MANAGE)
        return news.update(current, newsId, request)
    }

    /** Deletes an article; repeat-safe. */
    @DeleteMapping("/{newsId}")
    fun delete(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable newsId: String,
    ): Map<String, String> {
        access.requirePermission(current, PlatformPermission.NEWS_MANAGE)
        news.delete(current, newsId)
        return mapOf("status" to "DELETED", "newsId" to newsId)
    }
}
