package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.SchoolBrandingUploadPayload
import com.afrithecus.brainbox.api.identity.web.SchoolConfigPayload
import com.afrithecus.brainbox.api.media.MediaObjectService
import com.afrithecus.brainbox.api.media.MediaService
import com.afrithecus.brainbox.api.media.repository.MediaObjectRepository
import com.afrithecus.brainbox.api.report.render.ReportImages
import org.apache.pdfbox.pdmodel.PDDocument
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D16/A23 follow-up: ICT admins upload a school logo/stamp through the media service
 * instead of pasting a URL, and the PDF renderer embeds the stored image without an
 * outbound fetch (a local/dev origin is not a public host).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SchoolBrandingUploadTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val configService: SchoolConfigService,
    @Autowired private val mediaObjectService: MediaObjectService,
    @Autowired private val mediaObjects: MediaObjectRepository,
    @Autowired private val mediaService: MediaService,
    @Autowired private val clock: java.time.Clock,
) {

    private lateinit var school: SchoolEntity
    private lateinit var otherSchool: SchoolEntity
    private lateinit var admin: UserEntity
    private lateinit var otherAdmin: UserEntity

    private fun user(name: String, phone: String, schoolId: UUID, subRole: SubRole?): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@branding.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = Role.TEACHER
        entity.subRole = subRole
        entity.schoolId = schoolId
        entity.isActive = true
        entity.isVerified = true
        return userRepository.save(entity)
    }

    private fun token(entity: UserEntity): String {
        val response = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("identifier" to entity.email, "password" to "password123"))),
        ).andReturn().response
        check(response.status == 200) { "login failed: " + response.status }
        return objectMapper.readValue(response.contentAsString, AuthResponse::class.java).sessionToken!!
    }

    private fun png(width: Int = 64, height: Int = 32, color: Color = Color(0x1F, 0x2A, 0x44)): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRoundRect(0, 0, width, height, 8, 8)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun upload(schoolId: UUID, kind: String, bytes: ByteArray, fileName: String = "brand.png") =
        mockMvc.perform(
            multipart("/admin/schools/" + schoolId + "/config/branding/" + kind)
                .file(MockMultipartFile("file", fileName, "image/png", bytes))
                .header("Authorization", "Bearer " + token(admin)),
        )

    private fun configBody(logo: String?, stamp: String?): String = objectMapper.writeValueAsString(
        SchoolConfigPayload(
            schoolId = school.id.toString(),
            schoolName = school.name,
            logoUrl = logo,
            stampUrl = stamp,
        ),
    )

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Milimani Primary School"; isActive = true })
        otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Moi Avenue School"; isActive = true })
        admin = user("School ICT Admin", "0766610001", school.id, SubRole.ICT_ADMIN)
        otherAdmin = user("Other ICT Admin", "0766610002", otherSchool.id, SubRole.ICT_ADMIN)
    }

    @Test
    fun `admin uploads a logo and the renderer embeds the stored image`() {
        val bytes = png()
        val response = upload(school.id, "logo", bytes)
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val payload = objectMapper.readValue(response, SchoolBrandingUploadPayload::class.java)
        assertEquals("logo", payload.kind)
        assertTrue(payload.url.contains("/media/"), "uploaded branding must be served from /media/: " + payload.url)
        assertTrue(payload.url.endsWith(".png"), payload.url)

        // The media URL is public (reports fetch it without a bearer header) and serves the bytes.
        val path = URI(payload.url).path
        val served = mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.IMAGE_PNG))
            .andReturn().response.contentAsByteArray
        assertTrue(served.isNotEmpty())

        // The admin then saves the URL through the existing config PUT.
        mockMvc.perform(
            put("/admin/schools/" + school.id + "/config")
                .header("Authorization", "Bearer " + token(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(configBody(payload.url, null)),
        ).andExpect(status().isOk)
        assertEquals(payload.url, configService.branding(school.id).logoUrl)

        // No outbound HTTP: the renderer resolves the media object from the store itself.
        PDDocument().use { document ->
            assertNotNull(ReportImages.brandingLogo(document, payload.url, embed = true))
            assertNotNull(ReportImages.brandingStamp(document, payload.url))
        }
    }

    @Test
    fun `stamp upload is accepted and the logo slot can be removed by saving null`() {
        val stamp = objectMapper.readValue(
            upload(school.id, "stamp", png(color = Color(0x88, 0x22, 0x22))).andExpect(status().isOk)
                .andReturn().response.contentAsString,
            SchoolBrandingUploadPayload::class.java,
        )
        assertEquals("stamp", stamp.kind)
        mockMvc.perform(
            put("/admin/schools/" + school.id + "/config")
                .header("Authorization", "Bearer " + token(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(configBody(null, stamp.url)),
        ).andExpect(status().isOk)
        assertEquals(stamp.url, configService.branding(school.id).stampUrl)
    }

    @Test
    fun `non-image oversized and unknown-slot uploads are rejected`() {
        upload(school.id, "logo", "%PDF-1.4 not an image".toByteArray())
            .andExpect(status().isBadRequest)
        upload(school.id, "logo", "plain text".toByteArray())
            .andExpect(status().isBadRequest)
        // A bare PNG signature has the right magic bytes but no decodable image.
        upload(school.id, "logo", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            .andExpect(status().isBadRequest)
        upload(school.id, "logo", ByteArray(2 * 1024 * 1024 + 1))
            .andExpect(status().isBadRequest)
        upload(school.id, "logo", ByteArray(0))
            .andExpect(status().isBadRequest)
        upload(school.id, "banner", png())
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `only an admin of the school can upload branding`() {
        // Another school's ICT admin cannot write this school's branding.
        mockMvc.perform(
            multipart("/admin/schools/" + school.id + "/config/branding/logo")
                .file(MockMultipartFile("file", "logo.png", "image/png", png()))
                .header("Authorization", "Bearer " + token(otherAdmin)),
        ).andExpect(status().isForbidden)
        // An ordinary teacher is not an admin surface at all.
        val teacher = user("Ordinary Teacher", "0766610003", school.id, SubRole.CTEACHER)
        mockMvc.perform(
            multipart("/admin/schools/" + school.id + "/config/branding/logo")
                .file(MockMultipartFile("file", "logo.png", "image/png", png()))
                .header("Authorization", "Bearer " + token(teacher)),
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            multipart("/admin/schools/" + school.id + "/config/branding/logo")
                .file(MockMultipartFile("file", "logo.png", "image/png", png())),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `an upload stays unreferenced until the config save claims it`() {
        val payload = objectMapper.readValue(
            upload(school.id, "logo", png()).andExpect(status().isOk)
                .andReturn().response.contentAsString,
            SchoolBrandingUploadPayload::class.java,
        )
        val key = assertNotNull(mediaService.managedKey(payload.url))
        assertEquals(null, mediaObjects.findById(key).orElse(null)?.referencedAt)

        mockMvc.perform(
            put("/admin/schools/" + school.id + "/config")
                .header("Authorization", "Bearer " + token(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(configBody(payload.url, null)),
        ).andExpect(status().isOk)
        assertNotNull(mediaObjects.findById(key).orElse(null)?.referencedAt)
    }

    @Test
    fun `replacing a stamp deletes the previous object immediately`() {
        val first = objectMapper.readValue(
            upload(school.id, "stamp", png(color = Color(0x88, 0x22, 0x22))).andExpect(status().isOk)
                .andReturn().response.contentAsString,
            SchoolBrandingUploadPayload::class.java,
        )
        mockMvc.perform(
            put("/admin/schools/" + school.id + "/config")
                .header("Authorization", "Bearer " + token(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(configBody(null, first.url)),
        ).andExpect(status().isOk)

        val second = objectMapper.readValue(
            upload(school.id, "stamp", png(color = Color(0x22, 0x22, 0x88))).andExpect(status().isOk)
                .andReturn().response.contentAsString,
            SchoolBrandingUploadPayload::class.java,
        )
        mockMvc.perform(
            put("/admin/schools/" + school.id + "/config")
                .header("Authorization", "Bearer " + token(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(configBody(null, second.url)),
        ).andExpect(status().isOk)

        mockMvc.perform(get(URI(first.url).path)).andExpect(status().isNotFound)
        mockMvc.perform(get(URI(second.url).path)).andExpect(status().isOk)
        val firstKey = assertNotNull(mediaService.managedKey(first.url))
        assertTrue(mediaObjects.findById(firstKey).isEmpty)
    }

    @Test
    fun `the scheduled sweep reaps an abandoned upload after the grace period`() {
        val payload = objectMapper.readValue(
            upload(school.id, "logo", png()).andExpect(status().isOk)
                .andReturn().response.contentAsString,
            SchoolBrandingUploadPayload::class.java,
        )
        val key = assertNotNull(mediaService.managedKey(payload.url))
        assertTrue(mediaObjects.findById(key).isPresent)

        // The real scheduler uses clock.instant() - 24h; a future cutoff proves the
        // unclaimed upload would be reaped without waiting a day.
        assertEquals(1, mediaObjectService.sweep(clock.instant().plusSeconds(25 * 60 * 60)))

        mockMvc.perform(get(URI(payload.url).path)).andExpect(status().isNotFound)
        assertTrue(mediaObjects.findById(key).isEmpty)
    }

    @Test
    fun `the scheduled entrypoint works through the spring proxy`() {
        // Regression: the scheduler calls the bean through its CGLIB proxy. With a Kotlin
        // default argument the call went through `sweep$default`, which reads fields on the
        // uninitialised proxy (clock == null) and made every scheduled sweep fail with an NPE.
        mediaObjectService.sweep()
    }
}
