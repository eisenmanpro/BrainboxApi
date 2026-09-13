package com.afrithecus.brainbox.api.cbcratings.web

import com.afrithecus.brainbox.api.cbcratings.CbcAnalyticsService
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Teacher CBC analytics (doc 04 CBC analytics). */
@RestController
@RequestMapping("/teacher/cbc")
class CbcAnalyticsController(
    private val service: CbcAnalyticsService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping("/analytics")
    fun classAnalytics(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
        @RequestParam classId: String,
        @RequestParam(defaultValue = "TERM_1") term: String,
    ): CbcClassReportPayload = service.classReport(teacher(currentUser), classId, term)

    @GetMapping("/student/{studentId}")
    fun studentReport(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
        @PathVariable studentId: String,
        @RequestParam(defaultValue = "TERM_1") term: String,
    ): CbcReportCardPayload = service.studentReport(teacher(currentUser), studentId, term)

    @GetMapping("/strand/{strandCode}")
    fun strandDetail(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
        @PathVariable strandCode: String,
        @RequestParam classId: String,
    ): StrandMasteryDetailPayload = service.strandDetail(teacher(currentUser), strandCode, classId)

    @PostMapping("/rating")
    fun inputRating(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
        @RequestParam studentId: String,
        @RequestParam strandCode: String,
        @RequestParam(defaultValue = "TERM_1") term: String,
        @RequestParam rating: String,
        @RequestParam(required = false) evidence: String?,
        @RequestParam(required = false) comments: String?,
    ): ResponseEntity<Void> {
        service.inputRating(teacher(currentUser), studentId, strandCode, term, rating, evidence, comments)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/curriculum-map")
    fun curriculumMap(): CbcCurriculumMapPayload = service.curriculumMap()
}
