package com.afrithecus.brainbox.api.notification.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.notification.NotificationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Notification centre (doc 05 §5). */
@RestController
@RequestMapping("/notifications")
class NotificationController(private val service: NotificationService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam userId: String,
        @RequestParam(defaultValue = "false") unreadOnly: Boolean,
        @RequestParam(defaultValue = "false") includeArchived: Boolean,
    ): List<AppNotificationPayload> = service.list(current, userId, unreadOnly, includeArchived)

    @GetMapping("/unread-count")
    fun unreadCount(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam userId: String,
    ): Long = service.unreadCount(current, userId)

    @PostMapping
    fun create(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam userId: String,
        @Valid @RequestBody request: CreateNotificationRequest,
    ): AppNotificationPayload = service.create(current, userId, request)

    @PostMapping("/read-all")
    fun markAllRead(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam userId: String,
    ): ResponseEntity<Void> {
        service.markAllRead(current, userId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @PostMapping("/{notificationId}/read")
    fun markRead(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable notificationId: String,
    ): ResponseEntity<Void> {
        service.markRead(current, notificationId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @PostMapping("/{notificationId}/archive")
    fun archive(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable notificationId: String,
    ): ResponseEntity<Void> {
        service.archive(current, notificationId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @DeleteMapping("/{notificationId}")
    fun delete(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable notificationId: String,
    ): ResponseEntity<Void> {
        service.delete(current, notificationId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @DeleteMapping
    fun deleteAll(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam userId: String,
    ): ResponseEntity<Void> {
        service.deleteAll(current, userId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
