package com.afrithecus.brainbox.api.classes

import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.classes.web.AdminClassPayload
import com.afrithecus.brainbox.api.classes.web.AssignClassTeacherRequest
import com.afrithecus.brainbox.api.classes.web.CreateAdminClassRequest
import com.afrithecus.brainbox.api.classes.web.UpdateAdminClassRequest
import com.afrithecus.brainbox.api.classchat.entity.ClassGroupEntity
import com.afrithecus.brainbox.api.classchat.entity.ClassGroupMemberEntity
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupMemberRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupRepository
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
    private val learnerCounts: ClassLearnerCountService,
    private val userRepository: UserRepository,
    private val schoolConfigService: SchoolConfigService,
    private val access: AdminSchoolAccess,
    private val auditLogService: AuditLogService,
    private val classGroupRepository: ClassGroupRepository,
    private val classGroupMemberRepository: ClassGroupMemberRepository,
) {

    @Transactional
    fun list(current: CurrentUser, schoolIdRaw: String): List<AdminClassPayload> {
        val schoolId = schoolId(schoolIdRaw)
        access.require(current, schoolId)
        // The live rows are the authoritative list; a catalogue-only entry has no teacher to
        // own a NOT NULL teacher_classes row, so it cannot be materialised on read.
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val teacherIds = classes.mapNotNull { it.teacherUserId }.distinct()
        val teachers = if (teacherIds.isEmpty()) emptyMap() else userRepository.findAllById(teacherIds).associateBy { it.id }
        val counts = learnerCounts.counts(classes.map { it.id })
        return classes.map { payload(it, teachers[it.teacherUserId], counts[it.id] ?: 0) }
    }

    @Transactional
    fun create(current: CurrentUser, schoolIdRaw: String, request: CreateAdminClassRequest): AdminClassPayload {
        val schoolId = schoolId(schoolIdRaw)
        val actor = access.require(current, schoolId)
        val name = requireName(request.name)
        val grade = requireGrade(request.gradeLevel)
        requireNameAvailable(schoolId, name, excludingId = null)
        // teacher_classes.teacher_user_id is a NOT NULL foreign key to users; a school id
        // cannot stand in for the owner (it used to, and class creation failed the FK). A
        // class therefore starts with its class teacher, which the admin can reassign later.
        val teacher = request.teacherUserId?.let { requireTeacher(schoolId, it) }
            ?: throw invalidArgument("Select the class teacher for the new class")
        val entity = classRepository.save(TeacherClassEntity().apply {
            this.schoolId = schoolId
            this.name = name
            this.gradeLevel = grade
            this.subject = request.subject?.trim()?.takeIf { it.isNotEmpty() } ?: "General"
            this.stream = request.stream?.trim()?.takeIf { it.isNotEmpty() }
            teacherUserId = teacher.id
            isActive = true
        })
        schoolConfigService.syncClassCatalogue(schoolId)
        auditLogService.record(schoolId, actor, "Created class " + name + " (" + grade + ")")
        return payload(entity, teacher, 0)
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
        val teacher = userRepository.findById(entity.teacherUserId).orElse(null)
        return payload(entity, teacher, learnerCounts.count(entity.id))
    }

    /** Reassign the class teacher; the previous teacher keeps their account but not the class. */
    @Transactional
    fun assignTeacher(current: CurrentUser, schoolIdRaw: String, classIdRaw: String, request: AssignClassTeacherRequest): AdminClassPayload {
        val schoolId = schoolId(schoolIdRaw)
        val actor = access.require(current, schoolId)
        val entity = ownClass(schoolId, classIdRaw)
        val teacher = requireTeacher(schoolId, request.teacherId)
        val previousTeacherId = entity.teacherUserId
        entity.teacherUserId = teacher.id
        classRepository.saveAndFlush(entity)
        // Learners and marks stay attached to the class row, so the incoming teacher
        // inherits the roster automatically. Class chat groups are creator-owned, but a
        // group that targets the class's learners/parents belongs to the role: both the
        // outgoing and incoming class teacher keep access (messages keep their real
        // sender, so the new teacher never posts as the previous one).
        val inheritedGroups = inheritClassGroups(previousTeacherId, teacher, entity.id)
        schoolConfigService.syncClassCatalogue(schoolId)
        auditLogService.record(
            schoolId,
            actor,
            "Assigned " + teacher.name + " to class " + entity.name +
                if (inheritedGroups > 0) " (inherited " + inheritedGroups + " class group" + (if (inheritedGroups == 1) "" else "s") + ")" else "",
        )
        return payload(entity, teacher, learnerCounts.count(entity.id))
    }

    /** Deletes an empty class. A class with learners is a 409 (move them first). */
    @Transactional
    fun delete(current: CurrentUser, schoolIdRaw: String, classIdRaw: String) {
        val schoolId = schoolId(schoolIdRaw)
        val actor = access.require(current, schoolId)
        val entity = ownClass(schoolId, classIdRaw)
        // Roster-only learners are learners too: deleting their class would orphan them.
        val learners = learnerCounts.count(entity.id)
        if (learners > 0) {
            throw conflict("This class still has " + learners + " learner(s). Move them to another class before deleting it.")
        }
        classRepository.delete(entity)
        schoolConfigService.syncClassCatalogue(schoolId, removedNames = listOf(entity.name))
        auditLogService.record(schoolId, actor, "Deleted class " + entity.name)
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

    /**
     * Gives the incoming class teacher a membership in the outgoing teacher's class groups
     * that are open to the class's learners/parents (STUDENTS or ALL). The previous teacher
     * stays the creator and keeps every group and message; both can now post, and each
     * message records its own sender. Groups aimed only at staff are not inherited.
     */
    private fun inheritClassGroups(previousTeacherId: UUID, teacher: UserEntity, classId: UUID): Int {
        if (previousTeacherId == teacher.id) return 0
        val groups = classGroupRepository.findAllByClassId(classId).filter { group ->
            group.teacherId == previousTeacherId &&
                !group.isArchived &&
                (group.audience == AUDIENCE_STUDENTS || group.audience == AUDIENCE_ALL)
        }
        var inherited = 0
        for (group in groups) {
            if (classGroupMemberRepository.findByGroupIdAndMemberId(group.id, teacher.id) != null) continue
            classGroupMemberRepository.save(ClassGroupMemberEntity().apply {
                this.groupId = group.id
                memberId = teacher.id
                memberName = teacher.name
                memberRole = "TEACHER"
            })
            inherited += 1
        }
        return inherited
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

    private fun payload(entity: TeacherClassEntity, teacher: UserEntity?, learners: Int) = AdminClassPayload(
        id = entity.id.toString(),
        name = entity.name,
        gradeLevel = entity.gradeLevel,
        stream = entity.stream,
        teacherUserId = teacher?.id?.toString(),
        teacherName = teacher?.name,
        // A removed/transferred teacher keeps the row's old owner id but no longer belongs
        // to the school; the admin must see the class as needing a teacher.
        teacherActive = teacher?.isActive == true && teacher.schoolId == entity.schoolId,
        subject = entity.subject,
        studentCount = learners,
        isActive = entity.isActive,
    )

    private companion object {
        const val MAX_NAME = 120
        const val MAX_GRADE = 64
        const val AUDIENCE_STUDENTS = "STUDENTS"
        const val AUDIENCE_ALL = "ALL"
    }
}
