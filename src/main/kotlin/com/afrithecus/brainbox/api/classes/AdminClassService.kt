package com.afrithecus.brainbox.api.classes

import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.classes.web.AdminClassPayload
import com.afrithecus.brainbox.api.classes.web.AssignClassTeacherRequest
import com.afrithecus.brainbox.api.classes.web.CreateAdminClassRequest
import com.afrithecus.brainbox.api.classes.web.UpdateAdminClassRequest
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.AdminSchoolAccess
import com.afrithecus.brainbox.api.identity.AuditLogService
import com.afrithecus.brainbox.api.identity.SchoolConfigService
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Admin class management (findings "ADMIN SCHOOL CONFIG 3"): the School Config redesign adds
 * classes under an existing grade, renames/re-grades them, reassigns the class teacher and
 * deletes an empty class — all without typing a grade name into a free-text box.
 *
 * **Authoritative model:** a class is a `teacher_classes` row (it owns the teacher, the learner
 * memberships and every downstream exam/attendance tag). `school_configs.classes` is only the
 * signup picker's snapshot, so every mutation rewrites that snapshot from the live rows
 * ([SchoolConfigService.syncClassCatalogue]). Classes are school-scoped: an ICT admin manages
 * their own school, a platform admin any school.
 */
@Service
class AdminClassService(
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val userRepository: UserRepository,
    private val schoolConfigService: SchoolConfigService,
    private val access: AdminSchoolAccess,
    private val auditLogService: AuditLogService,
) {

    @Transactional
    fun list(current: CurrentUser, schoolIdRaw: String): List<AdminClassPayload> {
        val schoolId = schoolId(schoolIdRaw)
        access.require(current, schoolId)
        // Unify the two halves of the model on read: every class the school has configured but
        // not yet turned into a teacher_classes row becomes one (unassigned), so the admin sees
        // and can act on the complete list instead of a subset.
        materialiseCatalogueClasses(schoolId)
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val teacherIds = classes.mapNotNull { it.teacherUserId }.distinct()
        val teachers = if (teacherIds.isEmpty()) emptyMap() else userRepository.findAllById(teacherIds).associateBy { it.id }
        return classes.map { payload(it, teachers[it.teacherUserId]) }
    }

    @Transactional
    fun create(current: CurrentUser, schoolIdRaw: String, request: CreateAdminClassRequest): AdminClassPayload {
        val schoolId = schoolId(schoolIdRaw)
        val actor = access.require(current, schoolId)
        val name = requireName(request.name)
        val grade = requireGrade(request.gradeLevel)
        requireNameAvailable(schoolId, name, excludingId = null)
        val teacher = request.teacherUserId?.let { requireTeacher(schoolId, it) }
        val entity = classRepository.save(TeacherClassEntity().apply {
            this.schoolId = schoolId
            this.name = name
            this.gradeLevel = grade
            this.subject = request.subject?.trim()?.takeIf { it.isNotEmpty() } ?: "General"
            this.stream = request.stream?.trim()?.takeIf { it.isNotEmpty() }
            // A class without a teacher is a real state (a newly added class before an admin
            // assigns the teacher); the placeholder keeps the NOT NULL ownership column valid
            // and the payload reports the class as unassigned (teacherUserId = null).
            teacherUserId = teacher?.id ?: schoolId
            isActive = true
        })
        schoolConfigService.syncClassCatalogue(schoolId)
        auditLogService.record(schoolId, actor, "Created class " + name + " (" + grade + ")")
        return payload(entity, teacher)
    }

    @Transactional
    fun update(current: CurrentUser, schoolIdRaw: String, classIdRaw: String, request: UpdateAdminClassRequest): AdminClassPayload {
        val schoolId = schoolId(schoolIdRaw)
        val actor = access.require(current, schoolId)
        val entity = ownClass(schoolId, classIdRaw)
        val previousName = entity.name
        request.name?.takeIf { it.isNotBlank() }?.let { name ->
            val clean = requireName(name)
            if (!clean.equals(entity.name, ignoreCase = true)) requireNameAvailable(schoolId, clean, excludingId = entity.id)
            entity.name = clean
        }
        request.gradeLevel?.takeIf { it.isNotBlank() }?.let { entity.gradeLevel = requireGrade(it) }
        request.stream?.let { entity.stream = it.trim().takeIf { value -> value.isNotEmpty() } }
        request.subject?.takeIf { it.isNotBlank() }?.let { entity.subject = it.trim() }
        request.teacherUserId?.let { raw -> entity.teacherUserId = requireTeacher(schoolId, raw).id }
        classRepository.saveAndFlush(entity)
        schoolConfigService.syncClassCatalogue(
            schoolId,
            removedNames = if (previousName.equals(entity.name, ignoreCase = true)) emptyList() else listOf(previousName),
        )
        auditLogService.record(schoolId, actor, "Updated class " + entity.name)
        val teacher = entity.teacherUserId?.let { userRepository.findById(it).orElse(null) }
        return payload(entity, teacher)
    }

    /** Reassign the class teacher; the previous teacher keeps their account but not the class. */
    @Transactional
    fun assignTeacher(current: CurrentUser, schoolIdRaw: String, classIdRaw: String, request: AssignClassTeacherRequest): AdminClassPayload {
        val schoolId = schoolId(schoolIdRaw)
        val actor = access.require(current, schoolId)
        val entity = ownClass(schoolId, classIdRaw)
        val teacher = requireTeacher(schoolId, request.teacherId)
        entity.teacherUserId = teacher.id
        classRepository.saveAndFlush(entity)
        schoolConfigService.syncClassCatalogue(schoolId)
        auditLogService.record(schoolId, actor, "Assigned " + teacher.name + " to class " + entity.name)
        return payload(entity, teacher)
    }

    /** Deletes an empty class. A class with learners is a 409 (move them first). */
    @Transactional
    fun delete(current: CurrentUser, schoolIdRaw: String, classIdRaw: String) {
        val schoolId = schoolId(schoolIdRaw)
        val actor = access.require(current, schoolId)
        val entity = ownClass(schoolId, classIdRaw)
        val learners = membershipRepository.countByClassId(entity.id)
        if (learners > 0) {
            throw conflict("This class still has " + learners + " learner(s). Move them to another class before deleting it.")
        }
        classRepository.delete(entity)
        schoolConfigService.syncClassCatalogue(schoolId, removedNames = listOf(entity.name))
        auditLogService.record(schoolId, actor, "Deleted class " + entity.name)
    }

    /**
     * Creates a `teacher_classes` row for each catalogue class that has none yet (unassigned:
     * the placeholder owner is the school id, and the payload reports `teacherUserId = null`).
     * Existing rows are untouched, so this is safe to run on every list read.
     */
    private fun materialiseCatalogueClasses(schoolId: UUID) {
        val catalogue = schoolConfigService.configuredClasses(schoolId)
        if (catalogue.isEmpty()) return
        val existing = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .map { it.name.trim().lowercase() }.toSet()
        val missing = catalogue.filter { it.name.trim().lowercase() !in existing }
        if (missing.isEmpty()) return
        missing.forEach { row ->
            classRepository.save(TeacherClassEntity().apply {
                this.schoolId = schoolId
                name = row.name.trim()
                gradeLevel = row.gradeLevel.trim()
                stream = row.stream
                subject = "General"
                teacherUserId = schoolId
                isActive = true
            })
        }
        schoolConfigService.syncClassCatalogue(schoolId)
    }

    // ------------------------------------------------------------ internals

    private fun schoolId(raw: String): UUID {
        val id = runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("schoolId is not a valid identifier")
        return id
    }

    private fun ownClass(schoolId: UUID, raw: String): TeacherClassEntity {
        val id = runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("classId is not a valid identifier")
        val entity = classRepository.findById(id).orElse(null) ?: throw notFound("Class not found")
        if (entity.schoolId != null && entity.schoolId != schoolId) throw notFound("Class not found")
        return entity
    }

    private fun requireName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty()) throw invalidArgument("class name is required")
        if (name.length > MAX_NAME) throw invalidArgument("class name must be at most " + MAX_NAME + " characters")
        return name
    }

    private fun requireGrade(raw: String): String {
        val grade = raw.trim()
        if (grade.isEmpty()) throw invalidArgument("gradeLevel is required")
        if (grade.length > MAX_GRADE) throw invalidArgument("gradeLevel must be at most " + MAX_GRADE + " characters")
        return grade
    }

    private fun requireNameAvailable(schoolId: UUID, name: String, excludingId: UUID?) {
        val taken = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .any { it.id != excludingId && it.name.trim().equals(name, ignoreCase = true) }
        if (taken) throw conflict("A class named " + name + " already exists at this school")
    }

    private fun requireTeacher(schoolId: UUID, raw: String): UserEntity {
        val id = runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("teacherUserId is not a valid identifier")
        val teacher = userRepository.findById(id).orElse(null)
        if (teacher == null || teacher.role != Role.TEACHER || teacher.schoolId != schoolId) {
            throw notFound("Teacher not found")
        }
        if (!teacher.isActive) throw conflict("That teacher's account is not active")
        return teacher
    }

    private fun payload(entity: TeacherClassEntity, teacher: UserEntity?) = AdminClassPayload(
        id = entity.id.toString(),
        name = entity.name,
        gradeLevel = entity.gradeLevel,
        stream = entity.stream,
        teacherUserId = teacher?.id?.toString(),
        teacherName = teacher?.name,
        subject = entity.subject,
        studentCount = membershipRepository.countByClassId(entity.id).toInt(),
        isActive = entity.isActive,
    )

    private companion object {
        const val MAX_NAME = 120
        const val MAX_GRADE = 64
    }
}
