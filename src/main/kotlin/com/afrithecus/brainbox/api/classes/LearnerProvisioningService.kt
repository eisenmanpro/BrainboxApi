package com.afrithecus.brainbox.api.classes

import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.classes.web.BulkProvisionLearnersRequest
import com.afrithecus.brainbox.api.classes.web.BulkProvisionLearnersResult
import com.afrithecus.brainbox.api.classes.web.EnableAppAccessRequest
import com.afrithecus.brainbox.api.classes.web.EnableAppAccessResult
import com.afrithecus.brainbox.api.classes.web.ProvisionLearnerError
import com.afrithecus.brainbox.api.classes.web.ProvisionLearnerRequest
import com.afrithecus.brainbox.api.classes.web.ProvisionedLearnerPayload
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * Roster-only learner provisioning. A pupil without a smartphone still needs a
 * real student row so the traditional exam engine can store marks keyed on a
 * student id, rank them, and print reports. This service creates that record
 * with account_kind = ROSTER_ONLY, which makes it login-ineligible (see
 * AuthService) while leaving it a normal STUDENT everywhere a teacher or
 * coordinator works: class rosters, grade-wide exam lists, combined/class
 * reports and analysis.
 *
 * Ownership mirrors the rest of the teacher surface: the class owner, or a grade
 * coordinator / ICT admin of the same school, may provision for a class.
 */
@Service
class LearnerProvisioningService(
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val clock: Clock,
) {

    @Transactional
    fun provision(actor: UserEntity, classIdRaw: String, request: ProvisionLearnerRequest): ProvisionedLearnerPayload {
        val clazz = manageableClass(actor, classIdRaw)
        return provisionInto(actor, clazz, request).first
    }

    /**
     * Bulk provisioning is row-resilient: a learner that cannot be created is
     * reported in errors instead of failing the whole paste, so a 40-name list
     * with one bad row still lands the other 39.
     */
    @Transactional
    fun provisionBulk(
        actor: UserEntity,
        classIdRaw: String,
        request: BulkProvisionLearnersRequest,
    ): BulkProvisionLearnersResult {
        val clazz = manageableClass(actor, classIdRaw)
        val learners = mutableListOf<ProvisionedLearnerPayload>()
        val errors = mutableListOf<ProvisionLearnerError>()
        var created = 0
        var existing = 0
        request.learners.forEachIndexed { index, learner ->
            try {
                val (payload, wasCreated) = provisionInto(actor, clazz, learner)
                learners += payload
                if (wasCreated) created++ else existing++
            } catch (failure: ApiException) {
                errors += ProvisionLearnerError(index, learner.name, failure.message ?: "could not provision learner")
            }
        }
        return BulkProvisionLearnersResult(created = created, existing = existing, learners = learners, errors = errors)
    }

    /**
     * Takes a learner off the class and out of the active grade lists. The row and
     * every mark it owns are kept, so a report can still be produced later.
     */
    @Transactional
    fun deactivate(actor: UserEntity, classIdRaw: String, studentIdRaw: String) {
        val clazz = manageableClass(actor, classIdRaw)
        val student = learner(parseUuid(studentIdRaw, "studentId"))
        requireSameSchool(student, clazz.schoolId)
        membershipRepository.deleteByClassIdAndStudentId(clazz.id, student.id)
        student.isActive = false
        userRepository.save(student)
    }

    /**
     * Upgrades a roster-only record to a real account so the learner can use the
     * app. The record is kept, so all historical marks and reports stay attached.
     * When no password is supplied the server generates a one-time one.
     */
    @Transactional
    fun enableAppAccess(actor: UserEntity, studentIdRaw: String, request: EnableAppAccessRequest): EnableAppAccessResult {
        val student = learner(parseUuid(studentIdRaw, "studentId"))
        requireManagesLearner(actor, student)
        if (student.accountKind != AccountKind.ROSTER_ONLY) {
            throw invalidArgument("This learner already has app access")
        }
        val phone = request.phoneNumber.trim()
        if (userRepository.existsByPhoneNumber(phone)) {
            throw conflict("An account with this phone number already exists")
        }
        val requested = request.password?.takeIf { it.isNotBlank() }
        val password = requested ?: generatePassword()
        student.phoneNumber = phone
        student.passwordHash = passwordEncoder.encode(password)
            ?: throw IllegalStateException("Password encoding failed")
        student.accountKind = AccountKind.FULL
        student.isActive = true
        student.isVerified = true
        student.verificationStatus = AccountStatus.VERIFIED
        userRepository.save(student)
        return EnableAppAccessResult(
            userId = student.id.toString(),
            phoneNumber = phone,
            accountKind = AccountKind.FULL.name,
            temporaryPassword = if (requested == null) password else null,
        )
    }

    // ------------------------------------------------------------ internals

    private fun provisionInto(
        actor: UserEntity,
        clazz: TeacherClassEntity,
        request: ProvisionLearnerRequest,
    ): Pair<ProvisionedLearnerPayload, Boolean> {
        val name = request.name.trim()
        if (name.isEmpty()) throw invalidArgument("name is required")
        val schoolId = clazz.schoolId
            ?: throw ApiException(ApiErrorCode.FORBIDDEN, "A class must belong to a school to provision learners")
        val suppliedAdmission = request.admissionNumber?.trim()?.takeIf { it.isNotEmpty() }

        // Idempotency: an explicit admission number matches globally; otherwise an
        // identical name already provisioned into this class is the same learner.
        val matched = if (suppliedAdmission != null) {
            userRepository.findByStudentAdmissionNumber(suppliedAdmission)?.also { requireSameSchool(it, schoolId) }
        } else {
            inClassByName(clazz, name)
        }

        val guardianName = request.guardianName?.trim()?.takeIf { it.isNotEmpty() }
        val guardianPhone = request.guardianPhone?.trim()?.takeIf { it.isNotEmpty() }

        val student: UserEntity
        val created: Boolean
        if (matched != null) {
            if (matched.role != Role.STUDENT) throw conflict("That identity belongs to a non-student account")
            student = matched
            created = false
            guardianName?.let { student.guardianName = it }
            guardianPhone?.let { student.guardianPhone = it }
            if (student.gradeLevel.isNullOrBlank()) student.gradeLevel = clazz.gradeLevel
            userRepository.save(student)
        } else {
            student = UserEntity().apply {
                this.name = name
                phoneNumber = null
                passwordHash = ""
                role = Role.STUDENT
                this.schoolId = schoolId
                gradeLevel = clazz.gradeLevel
                studentAdmissionNumber = suppliedAdmission ?: generateAdmissionNumber()
                accountKind = AccountKind.ROSTER_ONLY
                provisionedBy = actor.id
                provisionedAt = clock.instant()
                this.guardianName = guardianName
                this.guardianPhone = guardianPhone
                isActive = true
                isVerified = true
                verificationStatus = AccountStatus.VERIFIED
            }
            userRepository.save(student)
            created = true
        }

        if (membershipRepository.findByClassIdAndStudentId(clazz.id, student.id) == null) {
            membershipRepository.save(
                ClassMembershipEntity().apply {
                    classId = clazz.id
                    studentId = student.id
                }
            )
        }
        return toPayload(student, clazz, created) to created
    }

    private fun toPayload(student: UserEntity, clazz: TeacherClassEntity, created: Boolean) = ProvisionedLearnerPayload(
        id = student.id.toString(),
        name = student.name,
        admissionNumber = student.studentAdmissionNumber,
        grade = clazz.gradeLevel,
        classId = clazz.id.toString(),
        accountKind = student.accountKind.name,
        guardianName = student.guardianName,
        guardianPhone = student.guardianPhone,
        created = created,
    )

    private fun inClassByName(clazz: TeacherClassEntity, name: String): UserEntity? {
        val memberIds = membershipRepository.findAllByClassId(clazz.id).map { it.studentId }
        if (memberIds.isEmpty()) return null
        return userRepository.findAllById(memberIds).firstOrNull {
            it.role == Role.STUDENT && it.accountKind == AccountKind.ROSTER_ONLY && it.name.equals(name, ignoreCase = true)
        }
    }

    private fun manageableClass(actor: UserEntity, classIdRaw: String): TeacherClassEntity {
        val clazz = classRepository.findById(parseUuid(classIdRaw, "classId")).orElse(null)
            ?: throw notFound("Class not found")
        if (actor.role == Role.ADMIN) return clazz
        val owns = clazz.teacherUserId == actor.id
        val coordinates = isCoordinator(actor) && actor.schoolId != null && actor.schoolId == clazz.schoolId
        if (!owns && !coordinates) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your class")
        return clazz
    }

    private fun requireManagesLearner(actor: UserEntity, student: UserEntity) {
        if (actor.role == Role.ADMIN) return
        val ownsTheClass = membershipRepository.findAllByStudentId(student.id).any { membership ->
            classRepository.findById(membership.classId).orElse(null)?.teacherUserId == actor.id
        }
        val coordinates = isCoordinator(actor) && actor.schoolId != null && actor.schoolId == student.schoolId
        if (!ownsTheClass && !coordinates) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your learner")
    }

    private fun isCoordinator(actor: UserEntity): Boolean =
        actor.role == Role.TEACHER && (actor.subRole == SubRole.GRADE_COORDINATOR || actor.subRole == SubRole.ICT_ADMIN)

    private fun requireSameSchool(student: UserEntity, schoolId: UUID?) {
        if (schoolId != null && student.schoolId != null && student.schoolId != schoolId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Learner is not in your school")
        }
    }

    private fun learner(id: UUID): UserEntity {
        val student = userRepository.findById(id).orElse(null) ?: throw notFound("Learner not found")
        if (student.role != Role.STUDENT) throw invalidArgument("Not a learner")
        return student
    }

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument(field + " is not a valid identifier")

    /** Globally unique, server-issued so two classes never collide on a blank entry. */
    private fun generateAdmissionNumber(): String {
        val epoch = clock.millis() / 1000
        repeat(MAX_ADMISSION_ATTEMPTS) {
            val candidate = "BB-" + epoch + "-" + "%04d".format(random.nextInt(10000))
            if (userRepository.findByStudentAdmissionNumber(candidate) == null) return candidate
        }
        throw ApiException(ApiErrorCode.CONFLICT, "Could not allocate an admission number, retry")
    }

    private fun generatePassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        return (1..TEMP_PASSWORD_LENGTH).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    private companion object {
        const val MAX_ADMISSION_ATTEMPTS = 20
        const val TEMP_PASSWORD_LENGTH = 10
        val random = SecureRandom()
    }
}
