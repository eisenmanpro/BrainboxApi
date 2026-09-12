package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.attendance.web.AttendanceRecordPayload
import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.web.AppNotificationPayload
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Teacher attendance register (doc 04 §4 / api_attendance_changes.md): idempotent
 * day-bucketed writes, mark-by-exception defensiveness, the CTEACHER role gate and
 * the parent absence fan-out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AttendanceWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val recordRepository: AttendanceRecordRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private val zone: ZoneId = ZoneId.of("Africa/Nairobi")
    private lateinit var schoolId: UUID

    @BeforeEach
    fun setUp() {
        val school = SchoolEntity()
        school.name = "Alliance High School"
        school.isActive = true
        schoolRepository.save(school)
        schoolId = school.id
    }

    private fun user(role: Role, name: String, phone: String, subRole: SubRole? = null, parentId: UUID? = null): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@attendance.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.subRole = subRole
        entity.schoolId = schoolId
        entity.parentUserId = parentId
        entity.isVerified = true
        entity.isActive = true
        return userRepository.save(entity)
    }

    private fun teacherClass(owner: UserEntity, name: String): TeacherClassEntity {
        val clazz = TeacherClassEntity()
        clazz.teacherUserId = owner.id
        clazz.schoolId = schoolId
        clazz.name = name
        clazz.gradeLevel = "Grade 4"
        clazz.subject = "Mathematics"
        clazz.isActive = true
        return classRepository.save(clazz)
    }

    private fun enroll(clazz: TeacherClassEntity, student: UserEntity) {
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = student.id
        })
    }

    private fun token(user: UserEntity): String {
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"${user.email}\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun midnightToday(): Long = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun record(clazz: TeacherClassEntity, student: UserEntity, status: String, date: Long, notes: String? = null): AttendanceRecordPayload =
        AttendanceRecordPayload(
            id = "att_" + clazz.id + "_" + student.id,
            classId = clazz.id.toString(),
            studentId = student.id.toString(),
            studentName = student.name,
            date = date,
            status = status,
            notes = notes,
        )

    @Test
    fun `register write is idempotent per day and fans an absence alert to the parent`() {
        val parent = user(Role.PARENT, "Parent One", "0722000100")
        val alice = user(Role.STUDENT, "Alice Mwangi", "0722000101", parentId = parent.id)
        val bob = user(Role.STUDENT, "Bob Otieno", "0722000102")
        val teacher = user(Role.TEACHER, "Class Teacher", "0722000103", subRole = SubRole.CTEACHER)
        val clazz = teacherClass(teacher, "Grade 4 South")
        enroll(clazz, alice)
        enroll(clazz, bob)

        val teacherToken = token(teacher)
        val parentToken = token(parent)
        val day = midnightToday()

        // Same local day, different time of day: both resolve to one register.
        val morning = day + 9 * 60 * 60 * 1000
        val body = objectMapper.writeValueAsString(listOf(record(clazz, alice, "ABSENT", morning, "Sick, parent called"), record(clazz, bob, "PRESENT", morning)))
        mockMvc.perform(
            post("/teacher/attendance").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk)

        val read = objectMapper.readValue(
            mockMvc.perform(
                get("/teacher/classes/${clazz.id}/attendance").param("date", day.toString()).header("Authorization", auth(teacherToken))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AttendanceRecordPayload>::class.java,
        )
        check(read.size == 2)
        check(read.first { it.studentId == alice.id.toString() }.status == "ABSENT")
        check(read.first { it.studentId == alice.id.toString() }.notes == "Sick, parent called")
        check(read.first { it.studentId == bob.id.toString() }.status == "PRESENT")

        // Replay the register at a later hour: no duplicates and no duplicate alert.
        val afternoon = day + 15 * 60 * 60 * 1000
        val replay = objectMapper.writeValueAsString(listOf(record(clazz, alice, "ABSENT", afternoon), record(clazz, bob, "PRESENT", afternoon)))
        mockMvc.perform(
            post("/teacher/attendance").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON).content(replay)
        ).andExpect(status().isOk)
        check(recordRepository.findAllByClassIdAndAttendanceDate(clazz.id, LocalDate.now(zone)).size == 2)

        val alerts = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", parent.id.toString()).header("Authorization", auth(parentToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        ).filter { it.type == "ATTENDANCE" }
        check(alerts.size == 1)
        check(alerts.single().actionRoute == "student_report/" + alice.id.toString())

        // Alice becomes PRESENT: no further alert is emitted.
        val corrected = objectMapper.writeValueAsString(listOf(record(clazz, alice, "PRESENT", day), record(clazz, bob, "PRESENT", day)))
        mockMvc.perform(
            post("/teacher/attendance").header("Authorization", auth(teacherToken))
                .contentType(MediaType.APPLICATION_JSON).content(corrected)
        ).andExpect(status().isOk)
        val afterCorrect = objectMapper.readValue(
            mockMvc.perform(get("/notifications").param("userId", parent.id.toString()).header("Authorization", auth(parentToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        ).filter { it.type == "ATTENDANCE" }
        check(afterCorrect.size == 1)
    }

    @Test
    fun `missing roster learners are marked absent and a plain teacher cannot file`() {
        val alice = user(Role.STUDENT, "Alice Mwangi", "0722000201")
        val bob = user(Role.STUDENT, "Bob Otieno", "0722000202")
        val classTeacher = user(Role.TEACHER, "Class Teacher", "0722000203", subRole = SubRole.CTEACHER)
        val plainTeacher = user(Role.TEACHER, "Subject Teacher", "0722000204")
        val clazz = teacherClass(classTeacher, "Grade 4 North")
        enroll(clazz, alice)
        enroll(clazz, bob)

        val classToken = token(classTeacher)
        val plainToken = token(plainTeacher)
        val day = midnightToday()

        // Only Alice posted: Bob (a roster member) is recorded ABSENT defensively.
        val body = objectMapper.writeValueAsString(listOf(record(clazz, alice, "PRESENT", day)))
        mockMvc.perform(
            post("/teacher/attendance").header("Authorization", auth(classToken))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk)

        val read = objectMapper.readValue(
            mockMvc.perform(
                get("/teacher/classes/${clazz.id}/attendance").param("date", day.toString()).header("Authorization", auth(classToken))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AttendanceRecordPayload>::class.java,
        )
        check(read.first { it.studentId == bob.id.toString() }.status == "ABSENT")

        // A plain TEACHER owning the class still cannot file a register.
        val own = teacherClass(plainTeacher, "Grade 5 East")
        val plainBody = objectMapper.writeValueAsString(listOf(record(own, alice, "PRESENT", day)))
        mockMvc.perform(
            post("/teacher/attendance").header("Authorization", auth(plainToken))
                .contentType(MediaType.APPLICATION_JSON).content(plainBody)
        ).andExpect(status().isForbidden)
    }
}
