package com.afrithecus.brainbox.api.learning.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.learning.MaterialsService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Readable-file authoring (ADMIN gate). */
@RestController
@RequestMapping("/admin/materials")
@PreAuthorize("hasRole('ADMIN')")
class MaterialsAdminController(private val service: MaterialsService) {

    @PostMapping("/readable")
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: CreateReadableRequest,
    ): ReadableFilePayload = service.createReadable(currentUser.userId, request)

    @GetMapping("/readable")
    fun list(): List<ReadableFilePayload> = service.adminList()

    @DeleteMapping("/readable/{id}")
    fun deactivate(@PathVariable id: String): ResponseEntity<Void> {
        service.deactivateReadable(id)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
