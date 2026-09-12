package com.afrithecus.brainbox.api.learning.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.learning.model.FileType
import com.afrithecus.brainbox.api.learning.model.LearningScope
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/** A downloadable reading material (doc 03 §3.1). */
@Entity
@Table(name = "readable_files")
class ReadableFileEntity : BaseEntity() {

    /** The client-supplied document id, for idempotent upload replays. */
    @Column(name = "client_id", length = 80)
    var clientId: String? = null

    @Column(nullable = false)
    var title: String = ""

    @Column(columnDefinition = "text")
    var description: String? = null

    @Column(name = "author_name", length = 160)
    var authorName: String? = null

    @Column(length = 255)
    var topic: String? = null

    /** Client document type (PDF/EPUB/PLAINTEXT); null for legacy/admin rows. */
    @Column(name = "doc_type", length = 16)
    var docType: String? = null

    @Column(nullable = false, length = 128)
    var subject: String = ""

    @Column(length = 128)
    var category: String? = null

    @Column(name = "file_url", nullable = false, length = 512)
    var fileUrl: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 16)
    var fileType: FileType = FileType.PDF

    @Column(name = "page_count", nullable = false)
    var pageCount: Int = 0

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long = 0

    @Column(name = "file_version", nullable = false)
    var fileVersion: Int = 1

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var scope: LearningScope = LearningScope.GLOBAL

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "grade_level", length = 64)
    var gradeLevel: String? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    @Column(name = "created_by", nullable = false)
    var createdBy: UUID = UUID.randomUUID()
}
