package com.afrithecus.brainbox.api.contract.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.contract.LearningContractService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Teacher learning contracts (doc 04 section 11). */
@RestController
@RequestMapping("/teacher/learning-contracts")
class LearningContractController(
    private val service: LearningContractService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun list(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<LearningContractPayload> = service.list(teacher(currentUser))

    @GetMapping("/templates")
    fun templates(): List<ContractTemplatePayload> = service.templates()

    @GetMapping("/{contractId}")
    fun get(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable contractId: String,
    ): LearningContractPayload = service.get(teacher(currentUser), contractId)

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody contract: LearningContractPayload,
    ): LearningContractPayload = service.create(teacher(currentUser), contract)

    @PutMapping("/{contractId}")
    fun update(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable contractId: String,
        @RequestBody contract: LearningContractPayload,
    ): LearningContractPayload = service.update(teacher(currentUser), contractId, contract)

    @DeleteMapping("/{contractId}")
    fun delete(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable contractId: String,
    ): ResponseEntity<Void> {
        service.delete(teacher(currentUser), contractId)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/{contractId}/commitment/{commitmentId}")
    fun updateCommitment(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable contractId: String,
        @PathVariable commitmentId: String,
        @RequestParam isCompleted: Boolean,
        @RequestParam(required = false) notes: String?,
    ): LearningContractPayload =
        service.updateCommitment(teacher(currentUser), contractId, commitmentId, isCompleted, notes)

    @PostMapping("/{contractId}/remind")
    fun remind(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable contractId: String,
        @RequestParam party: String,
    ): ResponseEntity<Void> {
        service.remind(teacher(currentUser), contractId, party)
        return ResponseEntity.noContent().build()
    }
}
