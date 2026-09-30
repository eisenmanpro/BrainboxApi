package com.afrithecus.brainbox.api.classes

import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.StudentClassTransitionRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.classes.web.TransitionRequest
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Learner search + grade/stream transitions (docs/ongoing/product_ops_roadmap.md item 3):
 * a class teacher can find a learner in another grade and pull them into their own class,
 * but cannot write into a class they do not own. Every move is recorded.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ClassTransitionTests(
    @Autowired private val service: ClassTransitionService,
    @Autowired private val users: UserRepository,
    @Autowired private val classes: TeacherClassRepository,
    @Autowired private val memberships: ClassMembershipRepository,
    @Autowired private val transitions: StudentClassTransitionRepository,
    @Autowired private val schools: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    /** users.school_id and teacher_classes.school_id are FKs, so the school must exist. */
    private fun newSchool(): UUID =
        schools.save(SchoolEntity().apply { name = "Transition Test School " + (1000..9999).random() }).id

    private fun account(role: Role, sub: SubRole?, name: String, school: UUID): UserEntity =
        users.save(UserEntity().apply {
            val suffix = (100000..999999).random()
            phoneNumber = "0" + suffix
            email = "ct" + suffix + "@transition.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = name
            this.role = role
            this.subRole = sub
            this.schoolId = school
            isActive = true
            isVerified = true
        })

    private fun learner(name: String, grade: String?, school: UUID): UserEntity =
        users.save(UserEntity().apply {
            val suffix = (100000..999999).random()
            phoneNumber = "07" + suffix
            email = "l" + suffix + "@transition.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = name
            role = Role.STUDENT
            schoolId = school
            gradeLevel = grade
            isActive = true
            isVerified = true
        })

    private fun klass(owner: UserEntity, name: String, grade: String, stream: String?, subject: String) =
        classes.save(TeacherClassEntity().apply {
            teacherUserId = owner.id
            schoolId = owner.schoolId
            this.name = name
            gradeLevel = grade
            this.subject = subject
            this.stream = stream
            isActive = true
        })

    private fun join(clazz: TeacherClassEntity, student: UserEntity) =
        memberships.save(ClassMembershipEntity().apply { classId = clazz.id; studentId = student.id })

    @Test
    fun `search spans grades and a move into an owned class is recorded`() {
        val school = newSchool()
        val coordinator = account(Role.TEACHER, SubRole.GRADE_COORDINATOR, "Coordinator", school)
        val cteacher = account(Role.TEACHER, SubRole.CTEACHER, "Class Teacher", school)
        val amina = learner("Amina Yusuf", "Form 2", school)
        val form2 = klass(coordinator, "Form 2 North", "Form 2", "North", "Mathematics")
        val form3 = klass(cteacher, "Form 3 South", "Form 3", "South", "Mathematics")
        join(form2, amina)

        // Search is school-wide, so a Form 3 teacher can find a Form 2 learner.
        val found = service.search(cteacher, "Amina", null, null, null, 20)
        check(found.any { it.studentId == amina.id.toString() }) { "expected the Form 2 learner in results" }

        val result = service.transition(
            cteacher, amina.id.toString(),
            TransitionRequest(toClassId = form3.id.toString(), reason = "Stream change", mode = "PULL"),
        )
        check(result.toClassId == form3.id.toString())
        check(result.mode == "PULL")
        check(memberships.findByClassIdAndStudentId(form3.id, amina.id) != null) { "learner must join the target" }
        check(memberships.findByClassIdAndStudentId(form2.id, amina.id) == null) { "learner must leave the old grade" }
        check(transitions.findAllByStudentIdOrderByCreatedAtDesc(amina.id).size == 1)
        check(users.findById(amina.id).get().gradeLevel == "Form 3") { "grade label follows the move" }
    }

    @Test
    fun `a class teacher cannot move a learner into a class they do not own`() {
        val school = newSchool()
        val coordinator = account(Role.TEACHER, SubRole.GRADE_COORDINATOR, "Coordinator", school)
        val cteacher = account(Role.TEACHER, SubRole.CTEACHER, "Class Teacher", school)
        val amina = learner("Amina Yusuf", "Form 2", school)
        val other = klass(coordinator, "Form 3 South", "Form 3", "South", "Mathematics")

        val failure = runCatching {
            service.transition(cteacher, amina.id.toString(), TransitionRequest(toClassId = other.id.toString()))
        }.exceptionOrNull()
        check(failure is ApiException && failure.code == ApiErrorCode.FORBIDDEN) {
            "expected FORBIDDEN, got " + failure
        }
        check(transitions.findAllByStudentIdOrderByCreatedAtDesc(amina.id).isEmpty()) { "nothing must be recorded" }
    }

    @Test
    fun `a coordinator may move within the whole school and a same-grade move leaves the old class`() {
        val school = newSchool()
        val coordinator = account(Role.TEACHER, SubRole.GRADE_COORDINATOR, "Coordinator", school)
        val cteacher = account(Role.TEACHER, SubRole.CTEACHER, "Class Teacher", school)
        val brian = learner("Brian Otieno", "Form 3", school)
        val north = klass(cteacher, "Form 3 North", "Form 3", "North", "Mathematics")
        val south = klass(cteacher, "Form 3 South", "Form 3", "South", "Mathematics")
        join(north, brian)

        service.transition(
            coordinator, brian.id.toString(),
            TransitionRequest(toClassId = south.id.toString(), reason = "Rebalanced streams", mode = "PUSH"),
        )
        check(memberships.findByClassIdAndStudentId(south.id, brian.id) != null)
        check(memberships.findByClassIdAndStudentId(north.id, brian.id) == null) {
            "a same-grade move must leave the previous class"
        }
    }

    @Test
    fun `only a coordinator may flip the switch and switching it off blocks every move`() {
        val school = newSchool()
        val coordinator = account(Role.TEACHER, SubRole.GRADE_COORDINATOR, "Coordinator", school)
        val cteacher = account(Role.TEACHER, SubRole.CTEACHER, "Class Teacher", school)
        val amina = learner("Amina Yusuf", "Form 2", school)
        val target = klass(cteacher, "Form 3 South", "Form 3", "South", "Mathematics")

        // A class teacher cannot change the school-wide switch.
        val denied = runCatching { service.setTransitionsEnabled(cteacher, false) }.exceptionOrNull()
        check(denied is ApiException && denied.code == ApiErrorCode.FORBIDDEN) {
            "expected FORBIDDEN, got " + denied
        }

        check(service.setTransitionsEnabled(coordinator, false) == false)
        check(!service.transitionsEnabled(cteacher)) { "the switch must be off" }

        val blocked = runCatching {
            service.transition(cteacher, amina.id.toString(), TransitionRequest(toClassId = target.id.toString()))
        }.exceptionOrNull()
        check(blocked is ApiException && blocked.code == ApiErrorCode.FORBIDDEN) { "expected FORBIDDEN, got " + blocked }
        check(transitions.findAllByStudentIdOrderByCreatedAtDesc(amina.id).isEmpty()) { "nothing must be recorded" }

        // Switching it back on restores the move.
        check(service.setTransitionsEnabled(coordinator, true))
        service.transition(cteacher, amina.id.toString(), TransitionRequest(toClassId = target.id.toString()))
        check(transitions.findAllByStudentIdOrderByCreatedAtDesc(amina.id).size == 1)
    }

    @Test
    fun `stream filter narrows the search`() {
        val school = newSchool()
        val cteacher = account(Role.TEACHER, SubRole.CTEACHER, "Class Teacher", school)
        val north = klass(cteacher, "Form 3 North", "Form 3", "North", "Mathematics")
        val south = klass(cteacher, "Form 3 South", "Form 3", "South", "Mathematics")
        val n = learner("Njeri North", "Form 3", school)
        val s = learner("Njeri South", "Form 3", school)
        join(north, n)
        join(south, s)

        val onlySouth = service.search(cteacher, "Njeri", null, "South", null, 20)
        check(onlySouth.map { it.studentId } == listOf(s.id.toString())) {
            "stream filter returned " + onlySouth.map { it.name }
        }
    }
}
