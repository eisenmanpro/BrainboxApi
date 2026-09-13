package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
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
}
