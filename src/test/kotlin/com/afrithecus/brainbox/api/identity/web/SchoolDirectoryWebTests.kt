package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolDetailEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.repository.SchoolDetailRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Public school directory (docs/ongoing/api_schools_changes.md): list/detail
 * shape, reviews with server-derived author + aggregates, join requests and
 * reports. Reads are public; writes require a session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SchoolDirectoryWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val detailRepository: SchoolDetailRepository,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"Directory Student ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun seedSchool(name: String): UUID {
        val school = schoolRepository.save(SchoolEntity().apply {
            this.name = name
            county = "Kiambu"
            location = "Kiambu, Kenya"
            logoUrls = """["https://cdn.brainbox.com/schools/1.jpg"]"""
            rating = 4.5
            reviewsCount = 2
            placementRate = 95
            schoolRank = 1
        })
        detailRepository.save(SchoolDetailEntity().apply {
            schoolId = school.id
            type = "Public"
            gradeLevels = "9-12"
            hours = "08:00 AM - 04:30 PM"
            imageUrls = """["https://cdn.brainbox.com/schools/d1.jpg"]"""
            phone = "+254700000000"
            email = "info@example.ac.ke"
            website = "www.example.ac.ke"
            socialMedia = """{"Twitter":"@All"}"""
            admissionContact = "admissions@example.ac.ke"
            transportation = "Bus routes available"
            curriculum = "8-4-4 / CBC"
            teacherRatio = "1:25"
            classSize = "40"
            programs = """["STEM","Music"]"""
            graduationRequirements = "Standard high school curriculum"
            testScores = "Mean Grade: A-"
            collegeAcceptance = "95% transition"
            facultyCount = 60
            advancedDegreesPercentage = 40
            averageExperience = 12
            supportStaff = """["Counselors"]"""
            totalEnrollment = 1200
            genderBreakdown = "Single-gender"
            diversityStats = "Nationwide representation"
            ellPopulation = "5%"
            specialNeedsSupport = "Available"
            extracurriculars = """["Rugby"]"""
            facilities = """["Science Labs"]"""
            parentInvolvement = """["PTA Meetings"]"""
            enrollmentDeadlines = "November 30th"
            enrollmentDocuments = """["Birth Certificate"]"""
            enrollmentExams = "Required for Form 1"
            enrollmentTourLink = "Schedule a Tour"
            importantDates = """[{"event":"Term 1 Start","date":"2024-01-08"}]"""
        })
        return school.id
    }

    @Test
    fun `public list and detail render the hardened shape`() {
        val schoolId = seedSchool("Alliance High School")
        val all = objectMapper.readValue(
            mockMvc.perform(get("/schools/all")).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<SchoolListPayload>::class.java,
        )
        val card = all.first { it.id == schoolId.toString() }
        check(card.name == "Alliance High School")
        check(card.logoUrls.isNotEmpty())
        check(card.rating == 4.5f)
        check(card.placementRate == 95)
        check(card.rank == 1)

        val detail = objectMapper.readValue(
            mockMvc.perform(get("/schools/${schoolId}")).andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolDetailPayload::class.java,
        )
        check(detail.basicInfo.name == "Alliance High School")
        check(detail.basicInfo.type == "Public")
        check(detail.contact.socialMedia["Twitter"] == "@All")
        check(detail.academics.programs.contains("STEM"))
        check(detail.faculty.count == 60)
        check(detail.studentBody.totalEnrollment == 1200)
        check(detail.importantDates.single().event == "Term 1 Start")
        check(detail.reviews.isEmpty())

        val trending = objectMapper.readValue(
            mockMvc.perform(get("/landing/trending-schools")).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<SchoolListPayload>::class.java,
        )
        check(trending.any { it.id == schoolId.toString() })
    }

    @Test
    fun `reviews are authored by the server and update aggregates`() {
        val schoolId = seedSchool("Reviewed High")
        val student = signup("0779100001")

        // writes require a session even though reads are public
        mockMvc.perform(
            post("/schools/${schoolId}/reviews")
                .contentType(MediaType.APPLICATION_JSON).content("""{"rating":5,"comment":"Excellent standards."}""")
        ).andExpect(status().isUnauthorized)

        val review = objectMapper.readValue(
            mockMvc.perform(
                post("/schools/${schoolId}/reviews").header("Authorization", auth(student.sessionToken!!))
                    .contentType(MediaType.APPLICATION_JSON).content("""{"rating":4,"comment":"Great sports facilities."}""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
            SchoolReviewPayload::class.java,
        )
        check(review.user == student.user.name)
        check(review.rating == 4)
        check(review.date.isNotBlank())

        val detail = objectMapper.readValue(
            mockMvc.perform(get("/schools/${schoolId}")).andReturn().response.contentAsString,
            SchoolDetailPayload::class.java,
        )
        check(detail.reviews.single().comment == "Great sports facilities.")

        val card = objectMapper.readValue(
            mockMvc.perform(get("/schools/all")).andReturn().response.contentAsString,
            Array<SchoolListPayload>::class.java,
        ).first { it.id == schoolId.toString() }
        check(card.reviews == 1)
        check(card.rating == 4.0f)

        mockMvc.perform(
            post("/schools/${schoolId}/reviews").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"rating":6,"comment":"bad"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `join requests and reports are deduplicated`() {
        val schoolId = seedSchool("Joinable High")
        val student = signup("0779100002")

        val join = objectMapper.readValue(
            mockMvc.perform(
                post("/schools/${schoolId}/join-requests").header("Authorization", auth(student.sessionToken!!))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"studentName":"Amina Otieno","gradeLevel":"Grade 9","admissionNumber":"ADM-1023"}""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
            SubmissionResultPayload::class.java,
        )
        check(join.success)
        check(join.reference!!.startsWith("JOIN-"))
        mockMvc.perform(
            post("/schools/${schoolId}/join-requests").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"studentName":"Amina Otieno","gradeLevel":"Grade 9"}""")
        ).andExpect(status().isConflict)

        val report = objectMapper.readValue(
            mockMvc.perform(
                post("/schools/${schoolId}/reports").header("Authorization", auth(student.sessionToken!!))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"Incorrect Information","detail":"Phone outdated."}""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
            SubmissionResultPayload::class.java,
        )
        check(report.reference!!.startsWith("RPT-"))
        mockMvc.perform(
            post("/schools/${schoolId}/reports").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"Incorrect Information"}""")
        ).andExpect(status().isConflict)
        mockMvc.perform(
            post("/schools/${schoolId}/reports").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"Aliens"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `school identifiers are validated`() {
        val student = signup("0779100003")
        mockMvc.perform(get("/schools/not-a-uuid")).andExpect(status().isBadRequest)
        mockMvc.perform(get("/schools/${UUID.randomUUID()}")).andExpect(status().isNotFound)
        mockMvc.perform(
            post("/schools/not-a-uuid/reviews").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"rating":5,"comment":"x"}""")
        ).andExpect(status().isBadRequest)
    }
}
