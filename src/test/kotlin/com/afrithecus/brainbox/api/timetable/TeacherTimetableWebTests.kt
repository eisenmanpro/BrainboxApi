package com.afrithecus.brainbox.api.timetable

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.timetable.web.CommunityServicePayload
import com.afrithecus.brainbox.api.timetable.web.HouseGroupPayload
import com.afrithecus.brainbox.api.timetable.web.PeerCirclePayload
import com.afrithecus.brainbox.api.timetable.web.RoomBookingPayload
import com.afrithecus.brainbox.api.timetable.web.ScheduleChangePayload
import com.afrithecus.brainbox.api.timetable.web.TimetableEntryPayload
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Teacher timetable CRUD + Kenyan export, room bookings with clash detection,
 * house/peer/community groups and coordinator-reviewed schedule changes
 * (doc 04 section 9).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TeacherTimetableWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private lateinit var schoolId: UUID

    @BeforeEach
    fun setUp() {
        val school = SchoolEntity()
        school.name = "Alliance High School"
        school.isActive = true
        schoolRepository.save(school)
        schoolId = school.id
    }

    private fun user(role: Role, name: String, phone: String, subRole: SubRole? = null): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@tt.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.subRole = subRole
        entity.schoolId = schoolId
        entity.isVerified = true
        entity.isActive = true
        return userRepository.save(entity)
    }

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + user.email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun teachClass(teacher: UserEntity, name: String, subject: String): TeacherClassEntity =
        classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            this.schoolId = this@TeacherTimetableWebTests.schoolId
            this.name = name
            gradeLevel = "Grade 4"
            this.subject = subject
            isActive = true
        })

    private fun entry() = TimetableEntryPayload(
        id = "entry_1",
        classId = "",
        className = "Grade 4 South",
        subject = "Mathematics",
        dayOfWeek = 1,
        startTime = "08:00",
        endTime = "09:00",
        roomId = "room1",
        roomName = "Room 101",
        colorHex = "#4CC9F0",
    )

    @Test
    fun `timetable entries are idempotent and self scoped`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755020001")
        val other = user(Role.TEACHER, "Other Teacher", "0755020002")
        val clazz = teachClass(teacher, "Grade 4 South", "Mathematics")
        val t = token(teacher)
        val o = token(other)
        val body = objectMapper.writeValueAsString(entry().copy(classId = clazz.id.toString()))

        val created = objectMapper.readValue(
            mockMvc.perform(post("/teacher/timetable/entries").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TimetableEntryPayload::class.java,
        )
        check(created.id == "entry_1")
        check(created.practicalBlockType == "NONE")

        // Replaying the same client id upserts rather than duplicating.
        mockMvc.perform(post("/teacher/timetable/entries").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk)
        val listed = objectMapper.readValue(
            mockMvc.perform(get("/teacher/timetable?teacherId=" + teacher.id).header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TimetableEntryPayload>::class.java,
        )
        check(listed.size == 1)

        val updated = objectMapper.readValue(
            mockMvc.perform(put("/teacher/timetable/entries/entry_1").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(entry().copy(subject = "Algebra"))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TimetableEntryPayload::class.java,
        )
        check(updated.subject == "Algebra")
        check(updated.practicalBlockType == "NONE")

        // A different teacher cannot touch it and sees nothing.
        mockMvc.perform(put("/teacher/timetable/entries/entry_1").header("Authorization", auth(o))
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden)
        val otherList = objectMapper.readValue(
            mockMvc.perform(get("/teacher/timetable?teacherId=" + other.id).header("Authorization", auth(o)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TimetableEntryPayload>::class.java,
        )
        check(otherList.isEmpty())

        // Delete is repeat safe.
        mockMvc.perform(delete("/teacher/timetable/entries/entry_1").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/teacher/timetable/entries/entry_1").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `auto schedule is idempotent and the kenyan export round trips`() {
        val teacher = user(Role.TEACHER, "Science Teacher", "0755020010")
        teachClass(teacher, "Grade 4 North", "Science")
        teachClass(teacher, "Grade 4 South", "Agriculture")
        val t = token(teacher)

        val first = objectMapper.readValue(
            mockMvc.perform(post("/teacher/timetable/auto-schedule?teacherId=" + teacher.id)
                .header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TimetableEntryPayload>::class.java,
        )
        check(first.size == 2)
        check(first.map { it.practicalBlockType }.toSet() == setOf("LAB_PERIOD", "FIELD_WORK"))
        check(first.any { it.entryType == "COMMUNITY_SERVICE" }.not())

        // Re-running the generator must not duplicate slots.
        val second = objectMapper.readValue(
            mockMvc.perform(post("/teacher/timetable/auto-schedule?teacherId=" + teacher.id)
                .header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TimetableEntryPayload>::class.java,
        )
        check(second.size == 2)
        check(second.map { it.id }.toSet() == first.map { it.id }.toSet())

        val kenyan = objectMapper.readValue(
            mockMvc.perform(get("/teacher/timetable/kenyan?teacherId=" + teacher.id).header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TimetableEntryPayload>::class.java,
        )
        check(kenyan.size == 2)
    }

    @Test
    fun `room bookings detect clashes and are repeat safe`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755020020")
        val t = token(teacher)
        val base = System.currentTimeMillis() + 86_400_000L

        fun booking(id: String, room: String, start: Long, end: Long) = RoomBookingPayload(
            id = id, roomId = room, roomName = room, teacherName = "Class Teacher",
            startTime = start, endTime = end, purpose = "Revision",
        )

        val first = objectMapper.readValue(
            mockMvc.perform(post("/teacher/rooms/book").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(booking("booking_1", "room1", base, base + 3_600_000))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            RoomBookingPayload::class.java,
        )
        check(first.id == "booking_1")
        check(first.teacherId == teacher.id.toString())

        // Replaying the same client id is not a self-clash.
        mockMvc.perform(post("/teacher/rooms/book").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(booking("booking_1", "room1", base, base + 3_600_000))))
            .andExpect(status().isOk)

        // An overlapping window for the same room is rejected.
        mockMvc.perform(post("/teacher/rooms/book").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                booking("booking_2", "room1", base + 1_800_000, base + 5_400_000))))
            .andExpect(status().isConflict)

        // A different room at the same time is fine.
        mockMvc.perform(post("/teacher/rooms/book").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                booking("booking_3", "room2", base + 1_800_000, base + 5_400_000))))
            .andExpect(status().isOk)

        val room1 = objectMapper.readValue(
            mockMvc.perform(get("/teacher/rooms/bookings?roomId=room1").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<RoomBookingPayload>::class.java,
        )
        check(room1.size == 1)
        check(room1.single().id == "booking_1")

        mockMvc.perform(delete("/teacher/rooms/bookings/booking_1").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/teacher/rooms/bookings/booking_1").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `house groups peer circles and community services round trip`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755020030")
        val clazz = teachClass(teacher, "Grade 4 South", "Mathematics")
        val t = token(teacher)

        val house = HouseGroupPayload(
            id = "hg_1", houseId = "house1", houseName = "Mchanganyiko House", houseColor = "#4CC9F0",
            classId = clazz.id.toString(), memberCount = 24, studentIds = listOf("s1", "s2"),
        )
        val savedHouse = objectMapper.readValue(
            mockMvc.perform(post("/teacher/house-groups").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(house)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            HouseGroupPayload::class.java,
        )
        check(savedHouse.id == "hg_1")
        check(savedHouse.memberCount == 24)
        check(savedHouse.studentIds == listOf("s1", "s2"))

        // Repeated create upserts.
        mockMvc.perform(post("/teacher/house-groups").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(house)))
            .andExpect(status().isOk)
        val houses = objectMapper.readValue(
            mockMvc.perform(get("/teacher/house-groups?teacherId=" + teacher.id).header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<HouseGroupPayload>::class.java,
        )
        check(houses.size == 1)

        val renamed = objectMapper.readValue(
            mockMvc.perform(put("/teacher/house-groups/hg_1").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(house.copy(houseName = "Nguvu House"))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            HouseGroupPayload::class.java,
        )
        check(renamed.houseName == "Nguvu House")

        val circle = PeerCirclePayload(id = "pc_1", circleName = "Math Masters", studentIds = listOf("s1", "s3"))
        mockMvc.perform(post("/teacher/peer-circles").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(circle)))
            .andExpect(status().isOk)
        val circles = objectMapper.readValue(
            mockMvc.perform(get("/teacher/peer-circles").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<PeerCirclePayload>::class.java,
        )
        check(circles.single().circleName == "Math Masters")

        val service = CommunityServicePayload(id = "cs_1", serviceName = "Community Garden", studentsAssigned = listOf("s1", "s4"))
        mockMvc.perform(post("/teacher/community-services").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(service)))
            .andExpect(status().isOk)
        val services = objectMapper.readValue(
            mockMvc.perform(get("/teacher/community-services").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<CommunityServicePayload>::class.java,
        )
        check(services.single().studentsAssigned == listOf("s1", "s4"))

        mockMvc.perform(delete("/teacher/house-groups/hg_1").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/teacher/house-groups/hg_1").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/teacher/peer-circles/pc_1").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/teacher/community-services/cs_1").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `schedule changes are reviewed by a coordinator`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755020040")
        val coordinator = user(Role.TEACHER, "Grade Coordinator", "0755020041", subRole = SubRole.GRADE_COORDINATOR)
        val t = token(teacher)
        val c = token(coordinator)

        fun change(id: String) = ScheduleChangePayload(
            id = id, day = "Monday", className = "Grade 4 South", subject = "Mathematics",
            startTime = "08:00", endTime = "09:00", reason = "Medical appointment",
        )

        val submitted = objectMapper.readValue(
            mockMvc.perform(post("/teacher/timetable/schedule-change").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(change("sch_1"))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ScheduleChangePayload::class.java,
        )
        check(submitted.status == "PENDING")
        check(submitted.teacherId == teacher.id.toString())
        check(submitted.teacherName == "Class Teacher")

        val own = objectMapper.readValue(
            mockMvc.perform(get("/teacher/timetable/schedule-changes?teacherId=" + teacher.id)
                .header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ScheduleChangePayload>::class.java,
        )
        check(own.size == 1)

        // A plain teacher cannot review.
        mockMvc.perform(post("/teacher/timetable/schedule-changes/sch_1/approve").header("Authorization", auth(t)))
            .andExpect(status().isForbidden)

        // The coordinator sees the whole school and approves.
        val school = objectMapper.readValue(
            mockMvc.perform(get("/teacher/timetable/schedule-changes").header("Authorization", auth(c)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ScheduleChangePayload>::class.java,
        )
        check(school.any { it.id == "sch_1" })
        val approved = objectMapper.readValue(
            mockMvc.perform(post("/teacher/timetable/schedule-changes/sch_1/approve").header("Authorization", auth(c)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ScheduleChangePayload::class.java,
        )
        check(approved.status == "APPROVED")

        // A replayed offline submit must not reopen a decided request.
        val replayed = objectMapper.readValue(
            mockMvc.perform(post("/teacher/timetable/schedule-change").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(change("sch_1"))))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ScheduleChangePayload::class.java,
        )
        check(replayed.status == "APPROVED")

        // A second request can be rejected.
        mockMvc.perform(post("/teacher/timetable/schedule-change").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(change("sch_2"))))
            .andExpect(status().isOk)
        val rejected = objectMapper.readValue(
            mockMvc.perform(post("/teacher/timetable/schedule-changes/sch_2/reject").header("Authorization", auth(c)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ScheduleChangePayload::class.java,
        )
        check(rejected.status == "REJECTED")
    }
}
