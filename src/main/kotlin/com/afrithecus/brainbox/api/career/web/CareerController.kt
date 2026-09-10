package com.afrithecus.brainbox.api.career.web

import com.afrithecus.brainbox.api.career.CareerService
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
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

/** Career guidance, goals, elective subjects and school matching (doc 06 §1/§4). */
@RestController
@RequestMapping("/career")
class CareerController(private val service: CareerService) {

    @GetMapping("/recommendations/{userId}")
    fun recommendations(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
        @RequestParam(required = false) gradeBand: String?,
    ): CareerRecommendationPayload = service.recommendations(requireSelf(current, userId), gradeBand)

    @PostMapping("/set-goal")
    fun setGoal(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam userId: String,
        @RequestParam goal: String,
        @RequestParam(required = false) gradeBand: String?,
    ): CareerRecommendationPayload = service.setGoal(requireSelf(current, userId), goal, gradeBand)

    @PostMapping("/mentor/request/{mentorId}")
    fun requestMentor(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable mentorId: String,
        @RequestParam userId: String,
    ): MentorRequestResultPayload = service.requestMentor(requireSelf(current, userId), mentorId)

    @GetMapping("/subjects")
    fun electiveSubjects(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam userId: String,
        @RequestParam(required = false) gradeBand: String?,
    ): List<ElectiveSubjectPayload> = service.electiveSubjects(requireSelf(current, userId), gradeBand)

    @PostMapping("/subjects/save")
    fun saveSubjects(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam userId: String,
        @RequestBody subjectIds: List<String>,
    ): SubjectSaveResultPayload = service.saveSubjects(requireSelf(current, userId), subjectIds)

    @GetMapping("/schools/matching")
    fun matchingSchools(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam userId: String,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) cluster: String?,
    ): List<MatchingSchoolPayload> = service.matchingSchools(requireSelf(current, userId), type, cluster)

    @GetMapping("/path/{careerId}")
    fun careerPath(@PathVariable careerId: String): CareerPathPayload = service.careerPath(careerId)

    @GetMapping("/goals/{userId}")
    fun goals(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
    ): List<CareerGoalPayload> = service.listGoals(requireSelf(current, userId), userId)

    @PostMapping("/goals")
    fun createGoal(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: CareerGoalRequest,
    ): CareerGoalPayload = service.createGoal(current, request)

    @PutMapping("/goals/{goalId}")
    fun updateGoal(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable goalId: String,
        @RequestBody request: CareerGoalUpdateRequest,
    ): CareerGoalPayload = service.updateGoal(current, goalId, request)

    @DeleteMapping("/goals/{goalId}")
    fun deleteGoal(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable goalId: String,
    ): ResponseEntity<Void> {
        service.deleteGoal(current, goalId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @GetMapping("/schools/search")
    fun searchSchools(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) location: String?,
        @RequestParam(required = false) type: String?,
    ): List<MatchingSchoolPayload> = service.searchSchools(query, location, type)

    @GetMapping("/schools/{schoolId}")
    fun schoolDetail(@PathVariable schoolId: String): MatchingSchoolPayload = service.schoolDetail(schoolId)

    @PostMapping("/schools/match")
    fun matchSchools(@Valid @RequestBody request: SchoolMatchRequest): List<MatchingSchoolPayload> =
        service.matchSchools(request)

    private fun requireSelf(current: CurrentUser, userId: String): CurrentUser {
        if (userId != current.userId.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot access another user's career profile")
        }
        return current
    }
}
