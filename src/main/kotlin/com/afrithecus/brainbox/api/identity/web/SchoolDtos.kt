package com.afrithecus.brainbox.api.identity.web

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Public school directory payloads (docs/ongoing/api_schools_changes.md). */

data class SchoolListPayload(
    val id: String,
    val name: String,
    val logoUrls: List<String>,
    val rating: Float,
    val reviews: Int,
    val placementRate: Int,
    val rank: Int,
    val location: String,
    val isSaved: Boolean = false,
)

data class SchoolBasicInfoPayload(
    val name: String,
    val location: String,
    val type: String,
    val gradeLevels: String,
    val hours: String,
    val imageUrls: List<String> = emptyList(),
)

data class SchoolContactPayload(
    val phone: String,
    val email: String,
    val website: String,
    val socialMedia: Map<String, String>,
    val admissionContact: String,
    val transportation: String,
)

data class SchoolAcademicsPayload(
    val curriculum: String,
    val teacherRatio: String,
    val classSize: String,
    val programs: List<String>,
    val graduationRequirements: String,
    val testScores: String,
    val collegeAcceptance: String,
)

data class SchoolFacultyPayload(
    val count: Int,
    val advancedDegreesPercentage: Int,
    val averageExperience: Int,
    val supportStaff: List<String>,
)

data class SchoolStudentBodyPayload(
    val totalEnrollment: Int,
    val genderBreakdown: String,
    val diversityStats: String,
    val ellPopulation: String,
    val specialNeedsSupport: String,
)

data class TuitionInfoPayload(val gradeLevel: String, val fee: String)

data class SchoolReviewPayload(val user: String, val rating: Int, val comment: String, val date: String)

data class EnrollmentProcessPayload(
    val deadlines: String,
    val documents: List<String>,
    val entranceExams: String,
    val tourLink: String,
)

data class ImportantDatePayload(val event: String, val date: String)

data class SchoolDetailPayload(
    val basicInfo: SchoolBasicInfoPayload,
    val contact: SchoolContactPayload,
    val academics: SchoolAcademicsPayload,
    val faculty: SchoolFacultyPayload,
    val studentBody: SchoolStudentBodyPayload,
    val extracurriculars: List<String>,
    val facilities: List<String>,
    val tuition: List<TuitionInfoPayload>? = null,
    val parentInvolvement: List<String>,
    val reviews: List<SchoolReviewPayload>,
    val enrollment: EnrollmentProcessPayload,
    val importantDates: List<ImportantDatePayload>,
)

/** Generic acknowledgement for join-request and report submissions. */
data class SubmissionResultPayload(
    val success: Boolean,
    val message: String? = null,
    val reference: String? = null,
)

data class SchoolReviewRequest(
    @field:Min(1) @field:Max(5) val rating: Int,
    @field:NotBlank @field:Size(max = 2000) val comment: String,
)

data class JoinSchoolRequestPayload(
    @field:NotBlank @field:Size(max = 160) val studentName: String,
    @field:NotBlank @field:Size(max = 64) val gradeLevel: String,
    @field:Size(max = 64) val admissionNumber: String? = null,
)

data class SchoolReportRequestPayload(
    @field:NotBlank @field:Size(max = 64) val reason: String,
    @field:Size(max = 2000) val detail: String? = null,
)
