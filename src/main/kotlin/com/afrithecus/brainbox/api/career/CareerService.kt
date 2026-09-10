package com.afrithecus.brainbox.api.career

import com.afrithecus.brainbox.api.analytics.AnalyticsService
import com.afrithecus.brainbox.api.career.entity.CareerGoalEntity
import com.afrithecus.brainbox.api.career.entity.ElectiveSubjectEntity
import com.afrithecus.brainbox.api.career.entity.MentorEntity
import com.afrithecus.brainbox.api.career.entity.MentorRequestEntity
import com.afrithecus.brainbox.api.career.entity.MatchingSchoolEntity
import com.afrithecus.brainbox.api.career.entity.ScholarshipEntity
import com.afrithecus.brainbox.api.career.entity.UserElectiveSubjectEntity
import com.afrithecus.brainbox.api.career.model.CbcGradeBand
import com.afrithecus.brainbox.api.career.model.CbcPathway
import com.afrithecus.brainbox.api.career.model.CareerGoal
import com.afrithecus.brainbox.api.career.repository.CareerGoalRepository
import com.afrithecus.brainbox.api.career.repository.ElectiveSubjectRepository
import com.afrithecus.brainbox.api.career.repository.MatchingSchoolRepository
import com.afrithecus.brainbox.api.career.repository.MentorRepository
import com.afrithecus.brainbox.api.career.repository.MentorRequestRepository
import com.afrithecus.brainbox.api.career.repository.ScholarshipRepository
import com.afrithecus.brainbox.api.career.repository.UserElectiveSubjectRepository
import com.afrithecus.brainbox.api.career.web.CareerAchievementPayload
import com.afrithecus.brainbox.api.career.web.CareerAlertPayload
import com.afrithecus.brainbox.api.career.web.CareerGoalPayload
import com.afrithecus.brainbox.api.career.web.CareerGoalRequest
import com.afrithecus.brainbox.api.career.web.CareerGoalUpdateRequest
import com.afrithecus.brainbox.api.career.web.CareerMilestonePayload
import com.afrithecus.brainbox.api.career.web.CareerPathNodePayload
import com.afrithecus.brainbox.api.career.web.CareerPathPayload
import com.afrithecus.brainbox.api.career.web.CareerPathStepPayload
import com.afrithecus.brainbox.api.career.web.CareerPathTrackPayload
import com.afrithecus.brainbox.api.career.web.CareerRecommendationPayload
import com.afrithecus.brainbox.api.career.web.DiscoverySignalPayload
import com.afrithecus.brainbox.api.career.web.ElectiveSubjectPayload
import com.afrithecus.brainbox.api.career.web.GrowthPlanStepPayload
import com.afrithecus.brainbox.api.career.web.MatchingSchoolPayload
import com.afrithecus.brainbox.api.career.web.MentorMatchPayload
import com.afrithecus.brainbox.api.career.web.MentorRequestResultPayload
import com.afrithecus.brainbox.api.career.web.OrientationAnalysisPayload
import com.afrithecus.brainbox.api.career.web.OrientationPillarPayload
import com.afrithecus.brainbox.api.career.web.SalaryInsightPayload
import com.afrithecus.brainbox.api.career.web.SchoolMatchRequest
import com.afrithecus.brainbox.api.career.web.ScholarshipPayload
import com.afrithecus.brainbox.api.career.web.SkillGapPayload
import com.afrithecus.brainbox.api.career.web.SubjectSaveResultPayload
import com.afrithecus.brainbox.api.common.domain.GradeNormalizer
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Career guidance, goals, elective subjects and school matching (doc 06 §1/§4).
 * Recommendations derive from the student's real exam performance plus the CBC
 * curriculum mapping; catalogs (mentors, scholarships, schools, subjects) are
 * reference data.
 */
@Service
class CareerService(
    private val goalRepository: CareerGoalRepository,
    private val mentorRepository: MentorRepository,
    private val mentorRequestRepository: MentorRequestRepository,
    private val scholarshipRepository: ScholarshipRepository,
    private val schoolRepository: MatchingSchoolRepository,
    private val subjectRepository: ElectiveSubjectRepository,
    private val userSubjectRepository: UserElectiveSubjectRepository,
    private val userRepository: UserRepository,
    private val analyticsService: AnalyticsService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun recommendations(current: CurrentUser, gradeBandRaw: String?): CareerRecommendationPayload =
        build(current, resolveBand(user(current), gradeBandRaw))

    @Transactional
    fun setGoal(current: CurrentUser, goalRaw: String, gradeBandRaw: String?): CareerRecommendationPayload {
        val user = user(current)
        val goal = CareerGoal.fromName(goalRaw) ?: throw invalidArgument("Unknown career goal: " + goalRaw)
        val existing = goalRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(user.id, "ACTIVE")
        if (existing == null) {
            goalRepository.save(CareerGoalEntity().apply {
                this.userId = user.id
                this.goal = goal.name
            })
        } else {
            existing.goal = goal.name
            goalRepository.save(existing)
        }
        return build(current, resolveBand(user, gradeBandRaw))
    }

    @Transactional
    fun requestMentor(current: CurrentUser, mentorIdRaw: String): MentorRequestResultPayload {
        val user = user(current)
        val mentor = mentorRepository.findById(parseUuid(mentorIdRaw, "mentor id")).orElse(null)
            ?: throw notFound("Mentor not found")
        val existing = mentorRequestRepository.findByMentorIdAndUserId(mentor.id, user.id)
        if (existing != null) return MentorRequestResultPayload("success", "Mentor request already sent.")
        mentorRequestRepository.save(MentorRequestEntity().apply {
            this.mentorId = mentor.id
            this.userId = user.id
        })
        return MentorRequestResultPayload("success", "Mentor request sent.")
    }

    @Transactional(readOnly = true)
    fun electiveSubjects(current: CurrentUser, gradeBandRaw: String?): List<ElectiveSubjectPayload> {
        val band = resolveBand(user(current), gradeBandRaw)
        return subjectRepository.findAll()
            .filter { bands(it.gradeBands).isEmpty() || band.name in bands(it.gradeBands) }
            .sortedWith(compareByDescending<ElectiveSubjectEntity> { it.isCore }.thenBy { it.name })
            .map { ElectiveSubjectPayload(it.id.toString(), it.name, it.category, it.isCore, it.description ?: "") }
    }

    @Transactional
    fun saveSubjects(current: CurrentUser, subjectIds: List<String>): SubjectSaveResultPayload {
        val user = user(current)
        val ids = subjectIds.map { parseUuid(it, "subject id") }.distinct()
        val found = subjectRepository.findAllById(ids)
        if (found.size != ids.size) throw invalidArgument("One or more subjects do not exist")
        userSubjectRepository.deleteAllByUserId(user.id)
        found.forEach { subject ->
            userSubjectRepository.save(UserElectiveSubjectEntity().apply {
                this.userId = user.id
                this.subjectId = subject.id
            })
        }
        return SubjectSaveResultPayload("success", found.size)
    }

    @Transactional(readOnly = true)
    fun matchingSchools(current: CurrentUser, type: String?, cluster: String?): List<MatchingSchoolPayload> {
        val user = user(current)
        val band = resolveBand(user, null)
        val orientation = CareerCurriculum.orientation(scoresFor(current, user).map { SubjectScore(it.key, it.value) })
        val performance = performanceOf(current, user)
        return schoolRepository.findAll()
            .filter { type.isNullOrBlank() || it.type.equals(type, ignoreCase = true) }
            .filter { cluster.isNullOrBlank() || it.cluster.equals(cluster, ignoreCase = true) }
            .map { school ->
                val (score, reason) = scoreSchool(school, orientation.predictedPathway, performance)
                toSchoolPayload(school, score, reason)
            }
            .sortedByDescending { it.matchScore }
    }

    @Transactional(readOnly = true)
    fun searchSchools(query: String?, location: String?, type: String?): List<MatchingSchoolPayload> =
        schoolRepository.findAll()
            .filter { query.isNullOrBlank() || it.name.contains(query, ignoreCase = true) || it.location.contains(query, ignoreCase = true) }
            .filter { location.isNullOrBlank() || it.location.contains(location, ignoreCase = true) }
            .filter { type.isNullOrBlank() || it.type.equals(type, ignoreCase = true) }
            .map { toSchoolPayload(it, 0, it.matchReason) }

    @Transactional(readOnly = true)
    fun schoolDetail(schoolIdRaw: String): MatchingSchoolPayload {
        val school = schoolRepository.findById(parseUuid(schoolIdRaw, "school id")).orElse(null)
            ?: throw notFound("School not found")
        return toSchoolPayload(school, 0, school.matchReason)
    }

    @Transactional(readOnly = true)
    fun matchSchools(request: SchoolMatchRequest): List<MatchingSchoolPayload> {
        val goal = CareerGoal.fromName(request.careerPath)
        val predicted = goal?.let { CareerCurriculum.pathwayFor(it) }
        val performance = request.academicPerformance ?: 0.0
        return schoolRepository.findAll()
            .filter { request.preferredLocation.isNullOrBlank() || it.location.contains(request.preferredLocation, ignoreCase = true) }
            .map { school ->
                val (score, reason) = scoreSchool(school, predicted, performance)
                toSchoolPayload(school, score, reason)
            }
            .sortedByDescending { it.matchScore }
    }

    @Transactional(readOnly = true)
    fun careerPath(careerId: String): CareerPathPayload {
        val goal = CareerGoal.fromName(careerId) ?: throw notFound("Career not found")
        val subjects = CareerCurriculum.requiredSubjects(goal)
        val steps = listOf(
            CareerPathStepPayload("Foundation", "Build strong grades in the core subjects.", "1-2 years", subjects.take(2).map { "Achieve 70%+ in " + it.displayName }),
            CareerPathStepPayload("Specialisation", "Deepen the career-critical subjects and practicals.", "2-3 years", subjects.drop(2).map { "Excel in " + it.displayName }),
            CareerPathStepPayload("Professional", "Qualification, internship and entry into the field.", "3-5 years", listOf("Meet university/college entry requirements", "Complete an internship or attachment")),
        )
        return CareerPathPayload(careerId = goal.name, steps = steps)
    }

    @Transactional(readOnly = true)
    fun listGoals(current: CurrentUser, userIdRaw: String): List<CareerGoalPayload> {
        requireSelf(current, userIdRaw)
        return goalRepository.findAllByUserIdOrderByCreatedAtDesc(current.userId).map(::toGoalPayload)
    }

    @Transactional
    fun createGoal(current: CurrentUser, request: CareerGoalRequest): CareerGoalPayload {
        requireSelf(current, request.userId)
        val goal = CareerGoal.fromName(request.careerId) ?: throw invalidArgument("Unknown career goal: " + request.careerId)
        val saved = goalRepository.save(CareerGoalEntity().apply {
            this.userId = current.userId
            this.goal = goal.name
            this.targetDate = request.targetDate?.let(Instant::ofEpochMilli)
            this.milestones = mapper.writeValueAsString(request.milestones)
        })
        return toGoalPayload(saved)
    }

    @Transactional
    fun updateGoal(current: CurrentUser, goalIdRaw: String, request: CareerGoalUpdateRequest): CareerGoalPayload {
        val goal = ownedGoal(current, goalIdRaw)
        request.careerId?.let { raw ->
            goal.goal = (CareerGoal.fromName(raw) ?: throw invalidArgument("Unknown career goal: " + raw)).name
        }
        request.targetDate?.let { goal.targetDate = Instant.ofEpochMilli(it) }
        request.milestones?.let { goal.milestones = mapper.writeValueAsString(it) }
        request.status?.let { status ->
            val normalized = status.trim().uppercase()
            if (normalized !in setOf("ACTIVE", "COMPLETED", "ARCHIVED")) throw invalidArgument("Unknown goal status: " + status)
            goal.status = normalized
        }
        return toGoalPayload(goalRepository.save(goal))
    }

    @Transactional
    fun deleteGoal(current: CurrentUser, goalIdRaw: String) {
        goalRepository.delete(ownedGoal(current, goalIdRaw))
    }

    // ------------------------------------------------------------ internals

    private fun build(current: CurrentUser, band: CbcGradeBand): CareerRecommendationPayload {
        val user = user(current)
        val goal = goalRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(user.id, "ACTIVE")
            ?.goal?.let(CareerGoal::fromName) ?: CareerGoal.GENERAL
        val scores = scoresFor(current, user)
        val orientation = CareerCurriculum.orientation(scores.map { SubjectScore(it.key, it.value) })
        val required = CareerCurriculum.requiredSubjects(goal)
        val bandSubjects = CareerCurriculum.subjectsByBand[band].orEmpty()
        val relevant = required.filter { it in bandSubjects }.ifEmpty { required }
        val currentIndex = relevant.indexOfFirst { subject -> (scores[subject.displayName] ?: 0.0) < 80.0 }
        val nodes = relevant.mapIndexed { index, subject ->
            val score = scores[subject.displayName]
            CareerPathNodePayload(
                id = "node_" + index,
                title = subject.displayName + " Mastery",
                subject = subject.name,
                cbcStrand = "Core Competencies",
                gradeRange = band.gradeRange,
                estimatedHours = 20 + index * 5,
                requiredExams = emptyList(),
                isLocked = index > 1 && !(score != null && score >= 80.0),
                isCompleted = score != null && score >= 80.0,
                isCurrent = index == currentIndex,
                order = index + 1,
            )
        }
        val scholarships = scholarshipRepository.findAll()
            .filter { it.deadline.isAfter(clock.instant()) }
            .filter { bands(it.gradeBands).isEmpty() || band.name in bands(it.gradeBands) }
            .sortedBy { it.deadline }
            .map(::toScholarshipPayload)
        val mentors = mentorRepository.findAll().sortedByDescending { it.rating }
            .map { mentor -> toMentorPayload(mentor, user.id) }
        val salary = CareerCurriculum.salaryFor(goal)
        val roleTracks = orientation.pillars.mapIndexed { index, pillar ->
            CareerPathTrackPayload(
                id = "pt_" + index,
                title = pillar.title,
                stage = "Possible",
                description = when (pillar.title) {
                    "STEM" -> "Science and Technology focus"
                    "Humanities" -> "Humanities focus"
                    "Linguistic" -> "Language and communication focus"
                    else -> "Practical and creative focus"
                },
                fitScore = pillar.score,
                highlight = if (pillar.score >= 70) "High Fit" else "Low Fit",
            )
        }
        return CareerRecommendationPayload(
            careerGoal = goal.name,
            gradeBand = band.name,
            pathway = if (band == CbcGradeBand.SENIOR_SCHOOL || band == CbcGradeBand.POST_SECONDARY) orientation.predictedPathway.name else null,
            learningPath = nodes,
            skillGaps = CareerCurriculum.skillGaps(goal, band, scores)
                .map { SkillGapPayload(it.skill, it.userScore, it.targetScore, it.importance) },
            scholarships = scholarships,
            mentorMatches = mentors,
            roleTracks = roleTracks,
            salaryInsights = listOf(SalaryInsightPayload(goal.displayName, salary.range, salary.demand, salary.trend, salary.growthPercent)),
            milestones = nodes.take(2).map { CareerMilestonePayload(it.title, "Complete " + it.subject, it.gradeRange, it.isCompleted) },
            alerts = scholarshipAlerts(scholarships) + goalAlert(goal),
            growthPlan = nodes.take(3).mapIndexed { index, node ->
                GrowthPlanStepPayload(growthPhases.getOrElse(index) { "Later" }, node.title, "Focus on " + node.subject, node.estimatedHours.toString() + " hours")
            },
            discoverySignals = discoverySignals(scores, goal, orientation),
            orientationAnalysis = OrientationAnalysisPayload(
                pillars = orientation.pillars.map { OrientationPillarPayload(it.title, it.score, it.subjects.map { s -> s.name }, it.color) },
                predictedPathway = orientation.predictedPathway.name,
                insight = orientation.insight,
                confidenceScore = orientation.confidenceScore,
                maturity = orientation.maturity,
            ),
            achievements = achievements(user, scores, goal),
            interviewFocus = CareerCurriculum.interviewFocusFor(goal),
        )
    }

    private fun scholarshipAlerts(scholarships: List<ScholarshipPayload>): List<CareerAlertPayload> {
        val now = clock.instant()
        return scholarships.mapNotNull { scholarship ->
            val days = Duration.between(now, Instant.ofEpochMilli(scholarship.deadline)).toDays()
            if (days > 30) null else CareerAlertPayload(
                id = scholarship.id,
                title = "Scholarship closing soon: " + scholarship.title,
                detail = if (days <= 0) "Closing today" else "Closes in " + days + " days",
                severity = if (days <= 7) "HIGH" else "MEDIUM",
            )
        }
    }

    private fun goalAlert(goal: CareerGoal): List<CareerAlertPayload> =
        if (goal == CareerGoal.GENERAL) {
            listOf(CareerAlertPayload("goal", "Set a career goal", "Pick a goal to personalise your learning path.", "LOW"))
        } else {
            emptyList()
        }

    private fun discoverySignals(scores: Map<String, Double>, goal: CareerGoal, orientation: OrientationResult): List<DiscoverySignalPayload> {
        val signals = mutableListOf<DiscoverySignalPayload>()
        scores.maxByOrNull { it.value }?.let { signals += DiscoverySignalPayload("Strongest Subject", it.key + " (" + it.value.roundToInt() + "%)") }
        scores.minByOrNull { it.value }?.let { signals += DiscoverySignalPayload("Area to Strengthen", it.key) }
        signals += DiscoverySignalPayload("Pathway Interest", orientation.predictedPathway.name)
        signals += DiscoverySignalPayload("Career Goal", goal.displayName)
        return signals
    }

    private fun achievements(user: UserEntity, scores: Map<String, Double>, goal: CareerGoal): List<CareerAchievementPayload> = listOf(
        CareerAchievementPayload("Goal Set", if (goal == CareerGoal.GENERAL) "Not set" else "Complete", goal != CareerGoal.GENERAL),
        CareerAchievementPayload("Subject Explorer", scores.size.toString() + " subjects", scores.size >= 3),
        CareerAchievementPayload("Strong Performer", (scores.values.maxOrNull()?.roundToInt() ?: 0).toString() + "%", scores.values.any { it >= 80.0 }),
        CareerAchievementPayload("Profile Complete", if (user.schoolId != null && user.gradeLevel != null) "Complete" else "Missing details", user.schoolId != null && user.gradeLevel != null),
    )

    private fun scoresFor(current: CurrentUser, user: UserEntity): Map<String, Double> =
        analyticsService.studentPerformance(current, user.id.toString()).subjects
            .associate { it.subject to it.percentage }

    private fun performanceOf(current: CurrentUser, user: UserEntity): Double =
        analyticsService.studentPerformance(current, user.id.toString()).overallPercentage

    private fun scoreSchool(school: MatchingSchoolEntity, predicted: CbcPathway?, performance: Double): Pair<Int, String> {
        val pathways = bands(school.pathways)
        val pathwayMatch = predicted != null && predicted.name in pathways
        val pathwayScore = if (pathwayMatch) 40 else 0
        val ratingScore = (school.rating / 5.0 * 30).roundToInt()
        val capacityScore = (school.slots.coerceAtMost(150) / 150.0 * 10).roundToInt()
        val required = school.requiredPoints
        val eligible = required == null || performance >= required
        val eligibilityScore = when {
            required == null -> 20
            eligible -> 20
            else -> (performance / required * 20).roundToInt().coerceIn(0, 19)
        }
        val reason = buildString {
            if (pathwayMatch) append("Matches your ") else append("Broadens beyond your ")
            append(predicted?.name ?: "pathway")
            append(" pathway. ")
            if (eligible) append("Your performance meets the entry requirement.") else append("Entry points may be above your current average.")
        }
        return (pathwayScore + ratingScore + capacityScore + eligibilityScore).coerceIn(0, 100) to reason
    }

    private fun toSchoolPayload(school: MatchingSchoolEntity, score: Int, reason: String): MatchingSchoolPayload =
        MatchingSchoolPayload(
            id = school.id.toString(),
            name = school.name,
            location = school.location,
            type = school.type,
            cluster = school.cluster,
            pathways = bands(school.pathways),
            slots = school.slots,
            matchReason = reason,
            matchScore = score,
        )

    private fun toScholarshipPayload(scholarship: ScholarshipEntity): ScholarshipPayload =
        ScholarshipPayload(
            id = scholarship.id.toString(),
            title = scholarship.title,
            provider = scholarship.provider,
            amount = scholarship.amount,
            deadline = scholarship.deadline.toEpochMilli(),
            externalUrl = scholarship.externalUrl,
            eligibilityLabel = scholarship.eligibilityLabel,
            thumbnailUrl = scholarship.thumbnailUrl,
        )

    private fun toMentorPayload(mentor: MentorEntity, userId: UUID): MentorMatchPayload =
        MentorMatchPayload(
            id = mentor.id.toString(),
            mentorName = mentor.name,
            university = mentor.university,
            course = mentor.course,
            rating = mentor.rating,
            availableSlots = mentor.availableSlots,
            educationBandLabel = mentor.educationBandLabel,
            isRequested = mentorRequestRepository.findByMentorIdAndUserId(mentor.id, userId) != null,
        )

    private fun toGoalPayload(goal: CareerGoalEntity): CareerGoalPayload =
        CareerGoalPayload(
            id = goal.id.toString(),
            userId = goal.userId.toString(),
            careerId = goal.goal,
            targetDate = goal.targetDate?.toEpochMilli(),
            milestones = parseList(goal.milestones),
            status = goal.status,
        )

    private fun ownedGoal(current: CurrentUser, goalIdRaw: String): CareerGoalEntity {
        val goal = goalRepository.findById(parseUuid(goalIdRaw, "goal id")).orElse(null) ?: throw notFound("Career goal not found")
        if (goal.userId != current.userId) throw notFound("Career goal not found")
        return goal
    }

    private fun resolveBand(user: UserEntity, raw: String?): CbcGradeBand {
        val fromUser = GradeNormalizer.canonicalKey(user.gradeLevel)?.let(CbcGradeBand::fromGrade)
        if (fromUser != null) return fromUser
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching { CbcGradeBand.valueOf(raw.trim().uppercase()) }.getOrNull()
            if (parsed != null) return parsed
        }
        return CbcGradeBand.JUNIOR_SCHOOL
    }

    private fun user(current: CurrentUser): UserEntity =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    private fun requireSelf(current: CurrentUser, userIdRaw: String) {
        if (userIdRaw != current.userId.toString()) throw notFound("Career goal not found")
    }

    private fun parseUuid(raw: String, label: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument(label + " is not a valid identifier")

    private fun bands(json: String?): List<String> = parseList(json)

    private fun parseList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).map { node.get(it).asString() }
    }

    private companion object {
        val growthPhases = listOf("This Term", "Next Term", "End of Year")
    }
}
