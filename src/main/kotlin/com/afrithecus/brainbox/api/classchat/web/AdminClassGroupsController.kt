package com.afrithecus.brainbox.api.classchat.web

import com.afrithecus.brainbox.api.classchat.ClassChatService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * School-admin moderation of class groups (Android AdminGroupsScreen / admin web console).
 * The school is a resource id; access is derived from the token by AdminSchoolAccess
 * (platform ADMIN any school, ICT_ADMIN only their own).
 */
@RestController
@RequestMapping("/admin/schools/{schoolId}/class-groups")
@PreAuthorize("hasAnyRole('ADMIN','ICT_ADMIN')")
class AdminClassGroupsController(private val service: ClassChatService) {

    /** Every group of the school, for the moderation list. */
    @GetMapping
    fun list(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
    ): List<ClassGroupPayload> = service.adminGroups(current, schoolId)

    /** Freeze with a required reason, or unfreeze. Frozen groups are read-only. */
    @PostMapping("/{groupId}/freeze")
    fun freeze(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @PathVariable groupId: String,
        @RequestBody request: AdminFreezeGroupRequest,
    ): ClassGroupPayload = service.adminFreeze(current, schoolId, groupId, request.isFrozen, request.reason)

    /** Term archive/unarchive: read-only while archived, reversible. */
    @PostMapping("/{groupId}/archive")
    fun archive(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @PathVariable groupId: String,
        @RequestBody request: AdminArchiveGroupRequest,
    ): ClassGroupPayload = service.adminArchive(current, schoolId, groupId, request.isArchived, request.termLabel, request.reason)
}
