package com.afrithecus.brainbox.api.timetable.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.timetable.TeacherGroupService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** House groups (doc 04 section 9.3). */
@RestController
@RequestMapping("/teacher/house-groups")
class HouseGroupController(
    private val service: TeacherGroupService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun list(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<HouseGroupPayload> = service.houseGroups(teacher(currentUser))

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody group: HouseGroupPayload,
    ): HouseGroupPayload = service.createHouseGroup(teacher(currentUser), group)

    @PutMapping("/{groupId}")
    fun update(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable groupId: String,
        @RequestBody group: HouseGroupPayload,
    ): HouseGroupPayload = service.updateHouseGroup(teacher(currentUser), groupId, group)

    @DeleteMapping("/{groupId}")
    fun delete(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable groupId: String,
    ): ResponseEntity<Void> {
        service.deleteHouseGroup(teacher(currentUser), groupId)
        return ResponseEntity.noContent().build()
    }
}

/** Peer circles (doc 04 section 9.3). */
@RestController
@RequestMapping("/teacher/peer-circles")
class PeerCircleController(
    private val service: TeacherGroupService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun list(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<PeerCirclePayload> = service.peerCircles(teacher(currentUser))

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody circle: PeerCirclePayload,
    ): PeerCirclePayload = service.createPeerCircle(teacher(currentUser), circle)

    @PutMapping("/{circleId}")
    fun update(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable circleId: String,
        @RequestBody circle: PeerCirclePayload,
    ): PeerCirclePayload = service.updatePeerCircle(teacher(currentUser), circleId, circle)

    @DeleteMapping("/{circleId}")
    fun delete(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable circleId: String,
    ): ResponseEntity<Void> {
        service.deletePeerCircle(teacher(currentUser), circleId)
        return ResponseEntity.noContent().build()
    }
}

/** Community services (doc 04 section 9.4). */
@RestController
@RequestMapping("/teacher/community-services")
class CommunityServiceController(
    private val service: TeacherGroupService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun list(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<CommunityServicePayload> = service.communityServices(teacher(currentUser))

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody body: CommunityServicePayload,
    ): CommunityServicePayload = service.createCommunityService(teacher(currentUser), body)

    @PutMapping("/{serviceId}")
    fun update(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable serviceId: String,
        @RequestBody body: CommunityServicePayload,
    ): CommunityServicePayload = service.updateCommunityService(teacher(currentUser), serviceId, body)

    @DeleteMapping("/{serviceId}")
    fun delete(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable serviceId: String,
    ): ResponseEntity<Void> {
        service.deleteCommunityService(teacher(currentUser), serviceId)
        return ResponseEntity.noContent().build()
    }
}
