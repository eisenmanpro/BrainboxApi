package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.SchoolDetailEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolJoinRequestEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolReportEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolReviewEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.SchoolDetailRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolJoinRequestRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolReportRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolReviewRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.EnrollmentProcessPayload
import com.afrithecus.brainbox.api.identity.web.ImportantDatePayload
import com.afrithecus.brainbox.api.identity.web.JoinSchoolRequestPayload
import com.afrithecus.brainbox.api.identity.web.SchoolAcademicsPayload
import com.afrithecus.brainbox.api.identity.web.SchoolBasicInfoPayload
import com.afrithecus.brainbox.api.identity.web.SchoolContactPayload
import com.afrithecus.brainbox.api.identity.web.SchoolDetailPayload
import com.afrithecus.brainbox.api.identity.web.SchoolFacultyPayload
import com.afrithecus.brainbox.api.identity.web.SchoolListPayload
import com.afrithecus.brainbox.api.identity.web.SchoolReportRequestPayload
import com.afrithecus.brainbox.api.identity.web.SchoolReviewPayload
import com.afrithecus.brainbox.api.identity.web.SchoolReviewRequest
import com.afrithecus.brainbox.api.identity.web.SchoolStudentBodyPayload
import com.afrithecus.brainbox.api.identity.web.SubmissionResultPayload
import com.afrithecus.brainbox.api.identity.web.TuitionInfoPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Public school directory (docs/ongoing/api_schools_changes.md): list/detail plus
 * reviews, join requests and moderation reports. Reviewer identity and dates are
 * always server-derived.
 */
@Service
class SchoolDirectoryService(
    private val schoolRepository: SchoolRepository,
    private val detailRepository: SchoolDetailRepository,
    private val reviewRepository: SchoolReviewRepository,
    private val joinRequestRepository: SchoolJoinRequestRepository,
    private val reportRepository: SchoolReportRepository,
    private val userRepository: UserRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(): List<SchoolListPayload> =
        schoolRepository.findAllByOrderByNameAsc().filter { it.isActive }.map(::listPayload)

    @Transactional(readOnly = true)
    fun search(query: String): List<SchoolListPayload> =
        if (query.isBlank()) emptyList()
        else schoolRepository.findByNameContainingIgnoreCaseOrderByNameAsc(query.trim())
            .filter { it.isActive }.map(::listPayload)

    @Transactional(readOnly = true)
    fun trending(limit: Int): List<SchoolListPayload> =
        list().sortedWith(compareByDescending<SchoolListPayload> { it.rating }.thenBy { it.rank.takeIf { r -> r > 0 } ?: Int.MAX_VALUE })
            .take(limit.coerceIn(1, 50))

    @Transactional(readOnly = true)
    fun detail(schoolIdRaw: String): SchoolDetailPayload {
        val school = requireSchool(schoolIdRaw)
        val detail = detailRepository.findById(school.id).orElse(null)
        val reviews = reviewRepository.findAllBySchoolIdOrderByCreatedAtDesc(school.id).map {
            SchoolReviewPayload(it.userName, it.rating, it.comment, it.reviewDate.toString())
        }
        val imageUrls = detail?.let { parseList(it.imageUrls) } ?: emptyList()
        return SchoolDetailPayload(
            basicInfo = SchoolBasicInfoPayload(
                name = school.name,
                location = school.location ?: school.county ?: "",
                type = detail?.type ?: "",
                gradeLevels = detail?.gradeLevels ?: "",
                hours = detail?.hours ?: "",
                imageUrls = imageUrls,
            ),
            contact = SchoolContactPayload(
                phone = detail?.phone ?: "",
                email = detail?.email ?: "",
                website = detail?.website ?: "",
                socialMedia = detail?.let { parseMap(it.socialMedia) } ?: emptyMap(),
                admissionContact = detail?.admissionContact ?: "",
                transportation = detail?.transportation ?: "",
            ),
            academics = SchoolAcademicsPayload(
                curriculum = detail?.curriculum ?: "",
                teacherRatio = detail?.teacherRatio ?: "",
                classSize = detail?.classSize ?: "",
                programs = detail?.let { parseList(it.programs) } ?: emptyList(),
                graduationRequirements = detail?.graduationRequirements ?: "",
                testScores = detail?.testScores ?: "",
                collegeAcceptance = detail?.collegeAcceptance ?: "",
            ),
            faculty = SchoolFacultyPayload(
                count = detail?.facultyCount ?: 0,
                advancedDegreesPercentage = detail?.advancedDegreesPercentage ?: 0,
                averageExperience = detail?.averageExperience ?: 0,
                supportStaff = detail?.let { parseList(it.supportStaff) } ?: emptyList(),
            ),
            studentBody = SchoolStudentBodyPayload(
                totalEnrollment = detail?.totalEnrollment ?: 0,
                genderBreakdown = detail?.genderBreakdown ?: "",
                diversityStats = detail?.diversityStats ?: "",
                ellPopulation = detail?.ellPopulation ?: "",
                specialNeedsSupport = detail?.specialNeedsSupport ?: "",
            ),
            extracurriculars = detail?.let { parseList(it.extracurriculars) } ?: emptyList(),
            facilities = detail?.let { parseList(it.facilities) } ?: emptyList(),
            tuition = detail?.let { parseTuition(it.tuition) },
            parentInvolvement = detail?.let { parseList(it.parentInvolvement) } ?: emptyList(),
            reviews = reviews,
            enrollment = EnrollmentProcessPayload(
                deadlines = detail?.enrollmentDeadlines ?: "",
                documents = detail?.let { parseList(it.enrollmentDocuments) } ?: emptyList(),
                entranceExams = detail?.enrollmentExams ?: "",
                tourLink = detail?.enrollmentTourLink ?: "",
            ),
            importantDates = detail?.let { parseDates(it.importantDates) } ?: emptyList(),
        )
    }

    @Transactional
    fun submitReview(current: CurrentUser, schoolIdRaw: String, request: SchoolReviewRequest): SchoolReviewPayload {
        val school = requireSchool(schoolIdRaw)
        val comment = request.comment.trim()
        if (comment.isEmpty()) throw invalidArgument("comment must not be blank")
        if (request.rating !in 1..5) throw invalidArgument("rating must be between 1 and 5")
        val user = userRepository.findById(current.userId).orElseThrow { notFound("User not found") }
        val review = reviewRepository.save(SchoolReviewEntity().apply {
            this.schoolId = school.id
            userId = user.id
            userName = user.name
            rating = request.rating
            this.comment = comment
            reviewDate = LocalDate.now(clock)
        })
        val all = reviewRepository.findAllBySchoolIdOrderByCreatedAtDesc(school.id)
        school.reviewsCount = all.size
        school.rating = Math.round(all.map { it.rating }.average() * 10.0) / 10.0
        schoolRepository.save(school)
        return SchoolReviewPayload(review.userName, review.rating, review.comment, review.reviewDate.toString())
    }

    @Transactional
    fun submitJoinRequest(current: CurrentUser, schoolIdRaw: String, request: JoinSchoolRequestPayload): SubmissionResultPayload {
        val school = requireSchool(schoolIdRaw)
        if (joinRequestRepository.findBySchoolIdAndStudentIdAndStatus(school.id, current.userId, "PENDING") != null) {
            throw conflict("You already have a pending request for this school")
        }
        val saved = joinRequestRepository.save(SchoolJoinRequestEntity().apply {
            this.schoolId = school.id
            studentId = current.userId
            studentName = request.studentName.trim()
            gradeLevel = request.gradeLevel.trim()
            admissionNumber = request.admissionNumber?.trim()?.takeIf { it.isNotEmpty() }
            reference = reference("JOIN")
        })
        return SubmissionResultPayload(true, "Your request to join has been submitted for review.", saved.reference)
    }

    @Transactional
    fun submitReport(current: CurrentUser, schoolIdRaw: String, request: SchoolReportRequestPayload): SubmissionResultPayload {
        val school = requireSchool(schoolIdRaw)
        val reason = request.reason.trim()
        if (reason !in SCHOOL_REPORT_REASONS) throw invalidArgument("Unknown report reason: " + request.reason)
        if (reportRepository.findBySchoolIdAndUserIdAndReason(school.id, current.userId, reason) != null) {
            throw conflict("You have already reported this listing for that reason")
        }
        val saved = reportRepository.save(SchoolReportEntity().apply {
            this.schoolId = school.id
            userId = current.userId
            this.reason = reason
            detail = request.detail?.trim()?.takeIf { it.isNotEmpty() }
            reference = reference("RPT")
        })
        return SubmissionResultPayload(true, "Thank you. Our moderation team will review this listing.", saved.reference)
    }

    // ------------------------------------------------------------ internals

    private fun listPayload(school: SchoolEntity): SchoolListPayload {
        val logos = parseList(school.logoUrls).ifEmpty { listOfNotNull(school.logoUrl) }
        return SchoolListPayload(
            id = school.id.toString(),
            name = school.name,
            logoUrls = logos,
            rating = school.rating.toFloat(),
            reviews = school.reviewsCount,
            placementRate = school.placementRate,
            rank = school.schoolRank,
            location = school.location ?: school.county ?: "",
        )
    }

    private fun requireSchool(raw: String): SchoolEntity {
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument("school id is not a valid identifier")
        val school = schoolRepository.findById(id).orElse(null) ?: throw notFound("School not found")
        if (!school.isActive) throw notFound("School not found")
        return school
    }

    private fun reference(prefix: String): String = prefix + "-" + (100000 + SECURE_RANDOM.nextInt(900000))

    private fun parseList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).map { node.get(it).asString() }
    }

    private fun parseMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyMap()
        if (!node.isObject) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (entry in node.properties()) out[entry.key] = entry.value.asString()
        return out
    }

    private fun parseTuition(json: String?): List<TuitionInfoPayload>? {
        if (json.isNullOrBlank()) return null
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        if (!node.isArray) return null
        val out = (0 until node.size()).mapNotNull { index ->
            val item = node.get(index)
            val grade = item.get("gradeLevel")?.asString() ?: return@mapNotNull null
            val fee = item.get("fee")?.asString() ?: return@mapNotNull null
            TuitionInfoPayload(grade, fee)
        }
        return out.ifEmpty { null }
    }

    private fun parseDates(json: String?): List<ImportantDatePayload> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).mapNotNull { index ->
            val item = node.get(index)
            val event = item.get("event")?.asString() ?: return@mapNotNull null
            val date = item.get("date")?.asString() ?: return@mapNotNull null
            ImportantDatePayload(event, date)
        }
    }

    private companion object {
        val SECURE_RANDOM = SecureRandom()
        val SCHOOL_REPORT_REASONS = setOf(
            "Incorrect Information",
            "Inappropriate Content",
            "School Closed/Inactive",
            "Duplicate Entry",
            "Other",
        )
    }
}
