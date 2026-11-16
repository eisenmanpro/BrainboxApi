package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolDetailEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolDetailRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.SchoolConfigPayload
import com.afrithecus.brainbox.api.identity.web.SchoolListPayload
import com.afrithecus.brainbox.api.media.MediaUploadResponsePayload
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * School-owned landing gallery (findings "ADMIN SCHOOL CONFIG 2"): an ICT admin uploads an
 * image, saves the URL list in the config, and the public school payloads prefer it over the
 * platform editorial images while it is non-empty.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SchoolGalleryWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val detailRepository: SchoolDetailRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private lateinit var school: SchoolEntity
    private lateinit var admin: UserEntity

    private fun png(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 0x336699)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun token(entity: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.phoneNumber + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun saveGallery(token: String, gallery: List<String>?): SchoolConfigPayload {
        val current = objectMapper.readValue(
            mockMvc.perform(get("/admin/schools/" + school.id + "/config").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolConfigPayload::class.java,
        )
        val body = current.copy(galleryUrls = gallery)
        return objectMapper.readValue(
            mockMvc.perform(
                put("/admin/schools/" + school.id + "/config").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolConfigPayload::class.java,
        )
    }

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply {
            name = "Alliance High School"
            isActive = true
            imageUrls = "[\"http://editorial.test/one.png\"]"
        })
        detailRepository.save(SchoolDetailEntity().apply {
            schoolId = school.id
            imageUrls = "[\"http://editorial.test/detail.png\"]"
        })
        admin = userRepository.save(UserEntity().apply {
            name = "ICT Admin"
            phoneNumber = "0733300001"
            email = "admin@schoolgallery.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            role = Role.TEACHER
            subRole = SubRole.ICT_ADMIN
            schoolId = school.id
            isActive = true
            isVerified = true
        })
    }

    @Test
    fun `an uploaded gallery overrides the editorial images until it is cleared`() {
        val t = token(admin)
        val uploaded = objectMapper.readValue(
            mockMvc.perform(
                multipart("/admin/schools/" + school.id + "/config/gallery")
                    .file(MockMultipartFile("file", "gallery.png", "image/png", png()))
                    .header("Authorization", "Bearer " + t),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            MediaUploadResponsePayload::class.java,
        )
        check(uploaded.mediaType == "IMAGE" && uploaded.url.startsWith("http")) { "upload=" + uploaded }

        val saved = saveGallery(t, listOf(uploaded.url, "/media/second.png"))
        check(saved.galleryUrls == listOf(uploaded.url, "/media/second.png"))

        // Public list + detail prefer the school gallery over the editorial images.
        val list = objectMapper.readValue(
            mockMvc.perform(get("/schools/all")).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<SchoolListPayload>::class.java,
        ).toList()
        check(list.single { it.id == school.id.toString() }.imageUrls.first() == uploaded.url)
        val detail = mockMvc.perform(get("/schools/" + school.id)).andExpect(status().isOk)
            .andReturn().response.contentAsString
        check(detail.contains(uploaded.url)) { "detail did not prefer the school gallery" }

        // An empty list clears it and the editorial images show again.
        saveGallery(t, emptyList())
        val after = objectMapper.readValue(
            mockMvc.perform(get("/schools/all")).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<SchoolListPayload>::class.java,
        ).toList()
        check(after.single { it.id == school.id.toString() }.imageUrls == listOf("http://editorial.test/one.png"))
    }

    @Test
    fun `gallery validation and school scoping`() {
        val t = token(admin)
        // More than four images, and a non-http(s) URL, are rejected.
        val current = objectMapper.readValue(
            mockMvc.perform(get("/admin/schools/" + school.id + "/config").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolConfigPayload::class.java,
        )
        mockMvc.perform(
            put("/admin/schools/" + school.id + "/config").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(current.copy(galleryUrls = (1..5).map { "http://x.test/$it.png" }))),
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            put("/admin/schools/" + school.id + "/config").header("Authorization", "Bearer " + t)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(current.copy(galleryUrls = listOf("javascript:alert(1)")))),
        ).andExpect(status().isBadRequest)

        val otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Other School"; isActive = true })
        val otherAdmin = userRepository.save(UserEntity().apply {
            name = "Other Admin"
            phoneNumber = "0733300002"
            email = "other@schoolgallery.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            role = Role.TEACHER
            subRole = SubRole.ICT_ADMIN
            schoolId = otherSchool.id
            isActive = true
            isVerified = true
        })
        mockMvc.perform(
            multipart("/admin/schools/" + school.id + "/config/gallery")
                .file(MockMultipartFile("file", "gallery.png", "image/png", png()))
                .header("Authorization", "Bearer " + token(otherAdmin)),
        ).andExpect(status().isForbidden)
    }
}
