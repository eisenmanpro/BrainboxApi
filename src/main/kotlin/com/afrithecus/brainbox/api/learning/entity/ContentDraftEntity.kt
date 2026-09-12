package com.afrithecus.brainbox.api.learning.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A teacher's offline-first content draft (client-supplied id). */
@Entity
@Table(name = "content_drafts")
class ContentDraftEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "c_type", nullable = false, length = 16)
    var contentType: String = "NOTES"

    @Column(nullable = false, length = 255)
    var title: String = ""

    @Column(columnDefinition = "text")
    var description: String? = null

    @Column(length = 64)
    var subject: String? = null

    @Column(name = "custom_subject_name", length = 128)
    var customSubjectName: String? = null

    @Column(name = "grade_level", length = 64)
    var gradeLevel: String? = null

    @Column(length = 255)
    var topic: String? = null

    @Column(columnDefinition = "text")
    var body: String? = null

    /** JSON array strings. */
    @Column(name = "media_urls", columnDefinition = "text")
    var mediaUrls: String? = null

    @Column(columnDefinition = "text")
    var tags: String? = null

    @Column(name = "cbc_strands", columnDefinition = "text")
    var cbcStrands: String? = null

    @Column(nullable = false)
    var difficulty: Int = 1

    @Column(name = "estimated_minutes", nullable = false)
    var estimatedMinutes: Int = 0

    @Column(name = "last_modified", nullable = false)
    var lastModified: Instant = Instant.now()

    @Column(name = "author_name", length = 160)
    var authorName: String? = null
}
