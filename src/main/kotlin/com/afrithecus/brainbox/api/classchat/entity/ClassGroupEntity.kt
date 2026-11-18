package com.afrithecus.brainbox.api.classchat.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A chat group owned by a teacher. A [scope] of CLASS covers one class; GRADE covers
 * every class of one grade level and has no class id. The [audience] decides who else
 * may see and join the group; an ICT admin can always join for moderation.
 */
@Entity
@Table(name = "class_groups")
class ClassGroupEntity : BaseEntity() {

    /** Null only for a GRADE-scoped group. */
    @Column(name = "class_id")
    var classId: UUID? = null

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "teacher_name", nullable = false, length = 160)
    var teacherName: String = ""

    @Column(nullable = false, length = 200)
    var name: String = ""

    @Column(length = 500)
    var description: String? = null

    /** CLASS | GRADE (see ClassGroupScope). */
    @Column(nullable = false, length = 16)
    var scope: String = "CLASS"

    /** The grade a GRADE-scoped group belongs to, canonical catalogue spelling. */
    @Column(name = "grade_level", length = 64)
    var gradeLevel: String? = null

    /** STUDENTS | TEACHERS | CLASS_TEACHERS | ALL (see ClassGroupAudience). */
    @Column(nullable = false, length = 24)
    var audience: String = "STUDENTS"

    /** The owning school; always derived server-side, never from the request. */
    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "is_announcement_mode", nullable = false)
    var isAnnouncementMode: Boolean = false

    @Column(name = "teacher_muted_until")
    var teacherMutedUntil: Instant? = null

    @Column(name = "is_frozen", nullable = false)
    var isFrozen: Boolean = false

    @Column(name = "frozen_reason", length = 500)
    var frozenReason: String? = null

    @Column(name = "frozen_by")
    var frozenBy: UUID? = null

    @Column(name = "frozen_at")
    var frozenAt: Instant? = null

    /** True for the two school-wide groups Brainbox maintains automatically. */
    @Column(name = "is_system", nullable = false)
    var isSystem: Boolean = false

    @Column(name = "is_archived", nullable = false)
    var isArchived: Boolean = false

    @Column(name = "archived_at")
    var archivedAt: Instant? = null

    @Column(name = "archive_term_label", length = 64)
    var archiveTermLabel: String? = null

    @Column(name = "archive_reason", length = 500)
    var archiveReason: String? = null
}
