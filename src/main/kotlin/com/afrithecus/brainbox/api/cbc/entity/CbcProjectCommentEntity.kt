package com.afrithecus.brainbox.api.cbc.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A comment or reply on a CBC project. */
@Entity
@Table(name = "cbc_project_comments")
class CbcProjectCommentEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "project_id", nullable = false)
    var projectId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "user_name", nullable = false, length = 160)
    var userName: String = ""

    @Column(name = "user_role", nullable = false, length = 16)
    var userRole: String = "STUDENT"

    @Column(nullable = false, columnDefinition = "text")
    var content: String = ""

    @Column(name = "parent_comment_id")
    var parentCommentId: UUID? = null

    /** JSON array string of mentioned user names. */
    @Column(columnDefinition = "text")
    var mentions: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
