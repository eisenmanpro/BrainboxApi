package com.afrithecus.brainbox.api.identity.web

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Public school directory payloads (docs/ongoing/api_schools_changes.md). */

/**
 * School-authored public profile fields (findings round: admin School Config). Additive and
 * optional; a value the school has not filled in stays null/empty rather than being invented.
 * The landing card/dialog should render these over the platform editorial copy where present.
 */
data class SchoolProfilePayload(
  val motto: String? = null,
  val website: String? = null,
  val type: String? = null,
  val hours: String? = null,
  val phone: String? = null,
  val email: String? = null,
  val about: String? = null,
  val facilities: List<String> = emptyList(),
  val highlights: List<String> = emptyList(),
)

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
    /**
     * Up to four gallery images for the list card/spotlight, falling back to
     * [logoUrls] when the school has no gallery. Additive: older clients ignore it.
     */
    val imageUrls: List<String> = emptyList(),
    /** Moderator-set featured flag; only ever true when an operator set it (FEAT-06). */
    val isFeatured: Boolean = false,
    /** School-authored spotlight tagline, else null (never fabricated by the server). */
    val featuredTagline: String? = null,
    /** School-authored story, else null. */
    val story: String? = null,
    /** School-authored profile (motto/about/contact/facilities/highlights), else null when unset. */
    val profile: SchoolProfilePayload? = null,
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
    /** School-authored profile (admin School Config); null when the school has not filled it. */
    val profile: SchoolProfilePayload? = null,
    /**
     * Editorial + rating fields that already exist on the list payload. The
     * detail dialog used to lose them because this DTO omitted them, so the
     * rating, transition rate, featured badge, tagline and story all read as
     * unavailable even when the server had them.
     */
    val rating: Float = 0f,
    val reviewCount: Int = 0,
    val placementRate: Int = 0,
    val rank: Int = 0,
    val isFeatured: Boolean = false,
    val featuredTagline: String? = null,
    val story: String? = null,
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
