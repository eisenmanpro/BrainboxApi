package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * Rich school profile rendered by the Explore Schools detail screen
 * (docs/ongoing/api_schools_changes.md). List/map fields are stored as JSON.
 */
@Entity
@Table(name = "school_details")
class SchoolDetailEntity {

    @Id
    @Column(name = "school_id", nullable = false, updatable = false)
    var schoolId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 64)
    var type: String = ""

    @Column(name = "grade_levels", nullable = false, length = 64)
    var gradeLevels: String = ""

    @Column(nullable = false, length = 120)
    var hours: String = ""

    @Column(name = "image_urls", columnDefinition = "text")
    var imageUrls: String? = null

    @Column(nullable = false, length = 64)
    var phone: String = ""

    @Column(nullable = false, length = 160)
    var email: String = ""

    @Column(nullable = false, length = 255)
    var website: String = ""

    @Column(name = "social_media", columnDefinition = "text")
    var socialMedia: String? = null

    @Column(name = "admission_contact", nullable = false, length = 160)
    var admissionContact: String = ""

    @Column(nullable = false, length = 255)
    var transportation: String = ""

    @Column(nullable = false, length = 160)
    var curriculum: String = ""

    @Column(name = "teacher_ratio", nullable = false, length = 64)
    var teacherRatio: String = ""

    @Column(name = "class_size", nullable = false, length = 64)
    var classSize: String = ""

    @Column(columnDefinition = "text")
    var programs: String? = null

    @Column(name = "graduation_requirements", columnDefinition = "text")
    var graduationRequirements: String? = null

    @Column(name = "test_scores", nullable = false, length = 160)
    var testScores: String = ""

    @Column(name = "college_acceptance", nullable = false, length = 160)
    var collegeAcceptance: String = ""

    @Column(name = "faculty_count", nullable = false)
    var facultyCount: Int = 0

    @Column(name = "advanced_degrees_percentage", nullable = false)
    var advancedDegreesPercentage: Int = 0

    @Column(name = "average_experience", nullable = false)
    var averageExperience: Int = 0

    @Column(name = "support_staff", columnDefinition = "text")
    var supportStaff: String? = null

    @Column(name = "total_enrollment", nullable = false)
    var totalEnrollment: Int = 0

    @Column(name = "gender_breakdown", nullable = false, length = 120)
    var genderBreakdown: String = ""

    @Column(name = "diversity_stats", nullable = false, length = 255)
    var diversityStats: String = ""

    @Column(name = "ell_population", nullable = false, length = 64)
    var ellPopulation: String = ""

    @Column(name = "special_needs_support", nullable = false, length = 160)
    var specialNeedsSupport: String = ""

    @Column(columnDefinition = "text")
    var extracurriculars: String? = null

    @Column(columnDefinition = "text")
    var facilities: String? = null

    @Column(columnDefinition = "text")
    var tuition: String? = null

    @Column(name = "parent_involvement", columnDefinition = "text")
    var parentInvolvement: String? = null

    @Column(name = "enrollment_deadlines", nullable = false, length = 160)
    var enrollmentDeadlines: String = ""

    @Column(name = "enrollment_documents", columnDefinition = "text")
    var enrollmentDocuments: String? = null

    @Column(name = "enrollment_exams", nullable = false, length = 200)
    var enrollmentExams: String = ""

    @Column(name = "enrollment_tour_link", nullable = false, length = 255)
    var enrollmentTourLink: String = ""

    @Column(name = "important_dates", columnDefinition = "text")
    var importantDates: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @Version
    @Column(nullable = false)
    var version: Long = 0
}
