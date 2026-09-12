package com.afrithecus.brainbox.api.learning.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.learning.model.LearningScope
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A learning post (doc 03 content model). */
@Entity
@Table(name = "learning_posts")
class LearningPostEntity : BaseEntity() {

    @Column(nullable = false)
    var title: String = ""

    @Column(nullable = false, length = 64)
    var subject: String = ""

    @Column(length = 255)
    var topic: String? = null

    @Column(length = 255)
    var subtopic: String? = null

    @Column(name = "image_url", length = 512)
    var imageUrl: String? = null

    @Column(columnDefinition = "text")
    var description: String? = null

    @Column(name = "estimated_minutes", nullable = false)
    var estimatedMinutes: Int = 5

    @Column(nullable = false)
    var difficulty: Int = 3

    /** JSON array string of tags. */
    @Column(columnDefinition = "text")
    var tags: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var scope: LearningScope = LearningScope.GLOBAL

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "grade_level", length = 64)
    var gradeLevel: String? = null

    @Column(name = "teacher_id")
    var teacherId: UUID? = null

    @Column(name = "cbc_strand", length = 128)
    var cbcStrand: String? = null

    @Column(name = "cbc_sub_strand", length = 128)
    var cbcSubStrand: String? = null

    @Column(name = "custom_subject_name", length = 128)
    var customSubjectName: String? = null

    @Column(name = "is_featured", nullable = false)
    var isFeatured: Boolean = false

    @Column(name = "is_published", nullable = false)
    var isPublished: Boolean = true

    /** PUBLISHED | SCHEDULED | ARCHIVED (docs/ongoing/api_content_changes.md). */
    @Column(nullable = false, length = 16)
    var status: String = "PUBLISHED"

    /** When a SCHEDULED post becomes visible; null otherwise. */
    @Column(name = "publish_at")
    var publishAt: Instant? = null

    @Column(name = "view_count", nullable = false)
    var viewCount: Int = 0

    @Column(name = "like_count", nullable = false)
    var likeCount: Int = 0

    @Column(name = "created_by", nullable = false)
    var createdBy: UUID = UUID.randomUUID()
}
