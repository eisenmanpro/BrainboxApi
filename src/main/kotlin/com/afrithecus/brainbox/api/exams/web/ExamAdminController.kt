package com.afrithecus.brainbox.api.exams.web

import com.afrithecus.brainbox.api.exams.admin.ExamAuthoringService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
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

/** Exam authoring (doc 02 §2.4/§4.1). ADMIN-gated until TEACHER content flows land. */
@RestController
@RequestMapping("/admin/exams")
@PreAuthorize("hasRole('ADMIN')")
class ExamAdminController(private val authoring: ExamAuthoringService) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: CreateExamRequest,
    ): ExamDetail = authoring.create(request, currentUser.userId)

    @GetMapping
    fun list(): List<ExamSummary> = authoring.list()

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ExamDetail = authoring.get(id)

    @PostMapping("/{id}/publish")
    fun publish(@PathVariable id: String): ResponseEntity<Void> {
        authoring.publish(id)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @DeleteMapping("/{id}")
    fun archive(@PathVariable id: String): ResponseEntity<Void> {
        authoring.archive(id)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
