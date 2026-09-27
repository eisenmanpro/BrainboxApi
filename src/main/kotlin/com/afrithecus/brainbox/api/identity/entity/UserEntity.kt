package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Platform user (doc 01 §2.1). Follows the contract model closely; columns the
 * client never sees (password_hash, verification state) live here too.
 */
@Entity
@Table(name = "users")
class UserEntity : BaseEntity() {

    @Column(name = "phone_number", length = 32)
    var phoneNumber: String? = null

    @Column(name = "email", length = 255)
    var email: String? = null

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String = ""

    @Column(nullable = false)
    var name: String = ""

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var role: Role = Role.STUDENT

    @Enumerated(EnumType.STRING)
    @Column(name = "sub_role", length = 32)
    var subRole: SubRole? = null

    /**
     * Platform console permissions, comma-separated (see `PlatformPermission`). Empty for
     * every account by default: holding the ADMIN role alone buys no console operation.
     */
    @Column(name = "platform_permissions", length = 255)
    var platformPermissions: String? = null

    /** The account's console role, if it operates the platform console. */
    @Column(name = "console_role_id")
    var consoleRoleId: UUID? = null

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "student_admission_number", length = 64)
    var studentAdmissionNumber: String? = null

    /** Links a child user to a PARENT user (doc 01 §6). */
    @Column(name = "parent_user_id")
    var parentUserId: UUID? = null

    @Column(name = "referred_by_teacher_code", length = 64)
    var referredByTeacherCode: String? = null

    @Column(name = "joined_teacher_id")
    var joinedTeacherId: UUID? = null

    @Column(name = "grade_level", length = 32)
    var gradeLevel: String? = null

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    @Column(name = "is_verified", nullable = false)
    var isVerified: Boolean = false

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 24)
    var verificationStatus: AccountStatus = AccountStatus.VERIFIED

    @Column(name = "last_login")
    var lastLogin: Instant? = null

    /** FULL accounts can log in; ROSTER_ONLY records are teacher-provisioned and cannot. */
    @Enumerated(EnumType.STRING)
    @Column(name = "account_kind", nullable = false, length = 16)
    var accountKind: AccountKind = AccountKind.FULL

    /** The teacher/coordinator who created a ROSTER_ONLY record. */
    @Column(name = "provisioned_by")
    var provisionedBy: UUID? = null

    @Column(name = "provisioned_at")
    var provisionedAt: Instant? = null

    @Column(name = "guardian_name", length = 160)
    var guardianName: String? = null

    @Column(name = "guardian_phone", length = 32)
    var guardianPhone: String? = null

    /**
     * The class a ROSTER_ONLY learner was provisioned for. Deliberately not a
     * class_memberships row: membership feeds attendance, gradebook, homework,
     * CBC analytics and messaging, and a provisioned learner must not take part
     * in any of them. This is used only to tag the learner in traditional reports.
     */
    @Column(name = "provisioned_class_id")
    var provisionedClassId: UUID? = null
}
