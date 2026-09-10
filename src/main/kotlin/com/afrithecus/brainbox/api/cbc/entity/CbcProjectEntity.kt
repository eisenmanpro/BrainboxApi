package com.afrithecus.brainbox.api.cbc.entity

import com.afrithecus.brainbox.api.cbc.model.ProjectStatus
import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/** A student CBC project showcase entry (doc 06 §3). */
@Entity
@Table(name = "cbc_projects")
class CbcProjectEntity : BaseEntity() {

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "student_name", nullable = false, length = 160)
    var studentName: String = ""

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "school_name", nullable = false, length = 200)
    var schoolName: String = ""

    @Column(name = "school_logo_url", length = 512)
    var schoolLogoUrl: String? = null

    @Column(nullable = false, length = 220)
    var title: String = ""

    @Column(nullable = false, columnDefinition = "text")
    var description: String = ""

    @Column(nullable = false, length = 128)
    var subject: String = ""

    @Column(name = "cbc_strand", nullable = false, length = 32)
    var cbcStrand: String = ""

    @Column(name = "cbc_sub_strand", nullable = false, length = 64)
    var cbcSubStrand: String = ""

    @Column(name = "grade_band", nullable = false, length = 32)
    var gradeBand: String = ""

    /** JSON array string of media URLs. */
    @Column(name = "media_urls", columnDefinition = "text")
    var mediaUrls: String? = null

    @Column(name = "thumbnail_url", length = 512)
    var thumbnailUrl: String? = null

    @Column(name = "cover_image_url", length = 512)
    var coverImageUrl: String? = null

    @Column(nullable = false)
    var upvotes: Int = 0

    @Column(nullable = false)
    var downvotes: Int = 0

    @Column(name = "comment_count", nullable = false)
    var commentCount: Int = 0

    @Column(name = "view_count", nullable = false)
    var viewCount: Int = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ProjectStatus = ProjectStatus.PENDING

    /** JSON object string of rubric criterion -> score. */
    @Column(name = "rubric_scores", columnDefinition = "text")
    var rubricScores: String? = null

    /** JSON array string of tags. */
    @Column(columnDefinition = "text")
    var tags: String? = null
}
