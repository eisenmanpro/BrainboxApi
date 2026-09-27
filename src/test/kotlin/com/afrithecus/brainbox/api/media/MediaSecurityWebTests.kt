package com.afrithecus.brainbox.api.media

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.media.entity.MediaUploadEntity
import com.afrithecus.brainbox.api.media.repository.MediaUploadRepository
import com.afrithecus.brainbox.api.media.security.MediaQuarantineRepository
import com.afrithecus.brainbox.api.media.security.MediaScanLogRepository
import com.afrithecus.brainbox.api.media.security.MediaSecurityAlertRepository
import com.afrithecus.brainbox.api.media.security.web.MediaQuarantinePayload
import com.afrithecus.brainbox.api.media.security.web.MediaScanLogPayload
import com.afrithecus.brainbox.api.media.security.web.MediaSecurityAlertPayload
import com.afrithecus.brainbox.api.media.security.web.MediaSecuritySettingsPayload
import com.afrithecus.brainbox.api.media.security.web.MediaSecuritySummaryPayload
import com.afrithecus.brainbox.api.storage.ObjectStorage
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

/**
 * Media security end to end with real repositories: a scan verdict is logged, a refusal is
 * quarantined (or deleted, per the runtime setting) and alerted, an operator can restore or
 * destroy a quarantined object, and the runtime posture is editable from the console without
 * a redeploy. The scanner itself is a stub, because a real ClamAV is a deployment artifact.
 */
@SpringBootTest(properties = ["app.media.scan.provider=none"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MediaSecurityWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val users: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val uploads: MediaUploadRepository,
    @Autowired private val scanLogs: MediaScanLogRepository,
    @Autowired private val alerts: MediaSecurityAlertRepository,
    @Autowired private val quarantine: MediaQuarantineRepository,
    @Autowired private val policies: com.afrithecus.brainbox.api.content.ModerationPolicyService,
    @Autowired private val uploadService: MediaUploadService,
    @Autowired private val urlReputation: UrlReputationService,
    @Autowired private val clock: Clock,
    @Autowired @Qualifier("mediaObjectStorage") private val storage: ObjectStorage,
    @Autowired private val settings: MediaSecuritySettings,
    @Autowired private val mediaUrlValidator: com.afrithecus.brainbox.api.content.validation.MediaUrlValidator,
) {

    /** Replaces the ClamAV/no-op roster with a scanner the test can steer. */
    @TestConfiguration
    class StubScannerConfig {
        @Bean
        @Primary
        fun stubRegistry(properties: MediaProperties): MediaScannerRegistry =
            MediaScannerRegistry(
                mapOf(
                    "none" to NoOpMediaScanner(),
                    // The console's provider set stays the real one; the test swaps the
                    // ClamAV implementation for a steered stub.
                    "clamav" to StubScanner(),
                    "http" to HttpMediaScanner(properties, tools.jackson.databind.ObjectMapper()),
                ),
            )
    }

    @Test
    fun `an infected upload is quarantined, logged and alerted`() {
        val admin = account("0755060001", Role.ADMIN)
        policies.setMediaScanEnabled(true)
        policies.setMediaScanProvider("clamav")
        policies.setMediaOnInfection("QUARANTINE")
        StubScanner.verdict = MediaScanStatus.INFECTED
        StubScanner.detail = "Eicar-Test-Signature"

        val key = "quarantine-me.png"
        storage.put(key, bytes = PNG, contentType = "image/png")
        val ticket = pendingTicket(admin, key)

        val failure = runCatching { uploadService.confirm(admin, ticket.id.toString(), null) }
        check(failure.isFailure) { "an infected upload must be refused" }

        val row = uploads.findById(ticket.id).orElseThrow()
        check(row.status == "QUARANTINED") { "the ticket must say quarantined, was " + row.status }
        check(row.scanStatus == "INFECTED")
        check(row.scanAction == "QUARANTINED")
        check(row.quarantineId != null)

        // The bytes moved out of the serving key.
        check(storage.get(key) == null) { "the original key must no longer hold the object" }
        val held = quarantine.findById(row.quarantineId!!).orElseThrow()
        check(held.originalKey == key)
        check(storage.get(held.quarantineKey) != null) { "the bytes must be held in quarantine" }
        check(held.reason == "INFECTED")
        check(held.detail == "Eicar-Test-Signature")

        // The scan log records the verdict and what was done with it.
        val log = scanLogs.findAll().first { it.storageKey == key }
        check(log.status == "INFECTED")
        check(log.action == "QUARANTINED")
        check(log.provider == "clamav")

        // The alert queue is the console's working view.
        val token = token(admin)
        val queue = alerts("/admin/media/security/alerts?acknowledged=false", token)
        check(queue.any { it.kind == "INFECTED" && it.storageKey == key }) { "an infection must alert" }
        val infection = queue.first { it.kind == "INFECTED" && it.storageKey == key }
        check(infection.severity == "HIGH")

        // Acknowledging clears it from the open queue.
        val acked = objectMapper.readValue(
            mockMvc.perform(post("/admin/media/security/alerts/${infection.id}/ack").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            MediaSecurityAlertPayload::class.java,
        )
        check(acked.acknowledged)
        check(alerts("/admin/media/security/alerts?acknowledged=false", token).none { it.id == infection.id })

        // Restore puts the object back, so a false positive is recoverable.
        val restored = objectMapper.readValue(
            mockMvc.perform(post("/admin/media/security/quarantine/${held.id}/restore").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            MediaQuarantinePayload::class.java,
        )
        check(restored.status == "RESTORED")
        check(storage.get(key) != null) { "restore must put the bytes back at their key" }
        check(quarantine.findById(held.id).orElseThrow().status == "RESTORED")

        // A second entry can be destroyed instead.
        val destroyed = quarantineEntry(admin, "destroy-me.png")
        val deleted = objectMapper.readValue(
            mockMvc.perform(delete("/admin/media/security/quarantine/${destroyed.id}").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            MediaQuarantinePayload::class.java,
        )
        check(deleted.status == "DELETED")
        check(storage.get(destroyed.quarantineKey) == null)
    }

    @Test
    fun `the infection setting decides between quarantine and delete`() {
        val admin = account("0755060002", Role.ADMIN)
        policies.setMediaScanEnabled(true)
        policies.setMediaScanProvider("clamav")
        policies.setMediaOnInfection("DELETE")
        StubScanner.verdict = MediaScanStatus.INFECTED

        val key = "delete-me.png"
        storage.put(key, bytes = PNG, contentType = "image/png")
        val ticket = pendingTicket(admin, key)

        runCatching { uploadService.confirm(admin, ticket.id.toString(), null) }
        val row = uploads.findById(ticket.id).orElseThrow()
        check(row.status == "REJECTED") { "with DELETE the ticket is simply rejected, was " + row.status }
        check(row.scanAction == "DELETED")
        check(row.quarantineId == null)
        check(storage.get(key) == null) { "the object must be gone" }
        check(scanLogs.findAll().any { it.storageKey == key && it.action == "DELETED" })
    }

    @Test
    fun `a scanner failure fails closed and alerts, unless the deployment opts out`() {
        val admin = account("0755060003", Role.ADMIN)
        policies.setMediaScanEnabled(true)
        policies.setMediaScanProvider("clamav")
        policies.setMediaScanFailOpen(false)
        StubScanner.verdict = MediaScanStatus.ERROR
        StubScanner.detail = "The malware scanner is unavailable"

        val key = "unscannable.png"
        storage.put(key, bytes = PNG, contentType = "image/png")
        val ticket = pendingTicket(admin, key)
        runCatching { uploadService.confirm(admin, ticket.id.toString(), null) }
        check(uploads.findById(ticket.id).orElseThrow().scanStatus == "ERROR")
        check(alerts.findAll().any { it.kind == "SCAN_ERROR" && it.storageKey == key })

        // Fail-open records the error and accepts the object; it never reads as clean.
        policies.setMediaScanFailOpen(true)
        StubScanner.verdict = MediaScanStatus.ERROR
        val acceptedKey = "accepted-anyway.png"
        storage.put(acceptedKey, bytes = PNG, contentType = "image/png")
        val accepted = pendingTicket(admin, acceptedKey)
        uploadService.confirm(admin, accepted.id.toString(), null)
        val row = uploads.findById(accepted.id).orElseThrow()
        check(row.status == "VERIFIED")
        check(row.scanStatus == "ERROR") { "an accepted failure must not read as clean" }
    }

    @Test
    fun `the console reads and edits the posture without a redeploy`() {
        val admin = account("0755060004", Role.ADMIN)
        val token = token(admin)

        val initial = readSettings(token)
        check(!initial.scanEnabled) { "no scanner is configured for this test deployment" }
        check(initial.onInfection == "QUARANTINE")
        check(initial.alertsEnabled)
        check(initial.availableScanProviders.containsAll(listOf("clamav", "http", "none")))

        val updated = objectMapper.readValue(
            mockMvc.perform(
                put("/admin/media/security/settings").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"scanEnabled":true,"scanProvider":"clamav","onInfection":"DELETE","urlReputationEnabled":true,"urlReputationProvider":"deny_list"}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            MediaSecuritySettingsPayload::class.java,
        )
        check(updated.scanEnabled && updated.scanProvider == "clamav")
        check(updated.onInfection == "DELETE")
        check(updated.urlReputationEnabled && updated.urlReputationProvider == "deny_list")

        // A partial update leaves the rest alone.
        val partial = objectMapper.readValue(
            mockMvc.perform(
                put("/admin/media/security/settings").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON).content("""{"scanFailOpen":true}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            MediaSecuritySettingsPayload::class.java,
        )
        check(partial.scanFailOpen)
        check(partial.scanProvider == "clamav") { "a partial write must not reset the provider" }
        check(partial.onInfection == "DELETE")

        // An unknown provider is a client error, not a silent no-op.
        mockMvc.perform(
            put("/admin/media/security/settings").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content("""{"scanProvider":"antivirus-3000"}""")
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            put("/admin/media/security/settings").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content("""{"onInfection":"SHRED"}""")
        ).andExpect(status().isBadRequest)

        val summary = objectMapper.readValue(
            mockMvc.perform(get("/admin/media/security/summary").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            MediaSecuritySummaryPayload::class.java,
        )
        check(summary.scanProvider == "clamav")
        check(summary.onInfection == "DELETE")
        check(summary.scansByStatus.keys.containsAll(listOf("CLEAN", "INFECTED", "SKIPPED", "ERROR")))

        // A teacher cannot read or change any of it.
        val teacher = account("0755060005", Role.TEACHER)
        mockMvc.perform(get("/admin/media/security/summary").header("Authorization", auth(token(teacher))))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/admin/media/security/summary")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `the scan log is filterable and the url check is available on demand`() {
        val admin = account("0755060006", Role.ADMIN)
        val token = token(admin)
        policies.setMediaScanEnabled(true)
        policies.setMediaScanProvider("clamav")
        StubScanner.verdict = MediaScanStatus.CLEAN

        val key = "clean.png"
        storage.put(key, bytes = PNG, contentType = "image/png")
        uploadService.confirm(admin, pendingTicket(admin, key).id.toString(), null)

        val logs: List<MediaScanLogPayload> = objectMapper.readValue(
            mockMvc.perform(get("/admin/media/security/scans?status=CLEAN").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            objectMapper.typeFactory.constructCollectionType(List::class.java, MediaScanLogPayload::class.java),
        )
        check(logs.any { it.storageKey == key && it.status == "CLEAN" && it.action == "ACCEPTED" })
        check(logs.all { it.status == "CLEAN" }) { "the status filter must apply" }
        mockMvc.perform(get("/admin/media/security/scans?status=NONSENSE").header("Authorization", auth(token)))
            .andExpect(status().isBadRequest)

        // URL reputation: the deny-list provider needs no external service.
        policies.setMediaUrlReputationEnabled(true)
        policies.setMediaUrlReputationProvider("deny_list")
        check(urlReputation.enabled() && urlReputation.providerName() == "deny_list")

        val blocked = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/media/security/url-check").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON).content("""{"url":"http://127.0.0.1/secret"}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            com.afrithecus.brainbox.api.media.security.web.UrlCheckResponse::class.java,
        )
        check(blocked.status == "BLOCKED" && !blocked.allowed) { "a loopback URL must be refused" }
        check(blocked.provider == "deny_list")

        val allowed = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/media/security/url-check").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON).content("""{"url":"https://cdn.example.org/a.png"}""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            com.afrithecus.brainbox.api.media.security.web.UrlCheckResponse::class.java,
        )
        check(allowed.status == "CLEAN" && allowed.allowed)

        // The generated-content validator is off until URL reputation is enabled.
        policies.setMediaUrlReputationEnabled(false)
        check(!urlReputation.enabled())
        check(!settings.urlReputationEnabled())
    }

    /**
     * The content-pipeline half of URL reputation: a generated IMAGE URL is refused before a
     * learner sees it, and the refusal is visible to the console as an alert.
     */
    @Test
    fun `a refused image url blocks the content and alerts the console`() {
        val admin = account("0755060007", Role.ADMIN)
        policies.setMediaUrlReputationEnabled(true)
        policies.setMediaUrlReputationProvider("deny_list")

        val unit = com.afrithecus.brainbox.api.content.entity.ContentUnitEntity().apply {
            generationKey = "ke:cbc:grade4:mat-num-frac:lesson:url"
            subject = "Mathematics"
            gradeLevel = "Grade 4"
            language = "en"
            standardVersion = "v1"
            body = "Fractions."
        }
        val step = com.afrithecus.brainbox.api.content.entity.ContentUnitStepEntity().apply {
            unitId = unit.id
            orderIndex = 0
            body = "Look at the picture."
            figureSpec = """{"kind":"IMAGE","url":"http://127.0.0.1/private.png","alt":"A hidden picture"}"""
        }
        val findings = mediaUrlValidator.validate(
            com.afrithecus.brainbox.api.content.validation.ValidationContext(
                contentType = "CONTENT_UNIT",
                unit = unit,
                steps = listOf(step),
                questions = emptyList(),
                concept = null,
                curriculumMapping = null,
            )
        )
        check(findings.size == 1 && findings.single().code == "MEDIA_URL_BLOCKED") {
            "a private-network image URL must block the unit, was " + findings
        }
        check(findings.single().severity == com.afrithecus.brainbox.api.content.validation.FindingSeverity.BLOCKER)

        val blocked = alerts("/admin/media/security/alerts?acknowledged=false", token(admin))
            .firstOrNull { it.kind == "URL_BLOCKED" }
        check(blocked != null) { "a refused URL must reach the console alert queue" }
        check(blocked!!.url == "http://127.0.0.1/private.png")
        check(blocked.provider == "deny_list")
        check(blocked.severity == "MEDIUM")
    }

    // ---------------------------------------------------------------- helpers

    private fun readSettings(token: String): MediaSecuritySettingsPayload = objectMapper.readValue(
        mockMvc.perform(get("/admin/media/security/settings").header("Authorization", auth(token)))
            .andExpect(status().isOk).andReturn().response.contentAsString,
        MediaSecuritySettingsPayload::class.java,
    )

    private fun alerts(path: String, token: String): List<MediaSecurityAlertPayload> = objectMapper.readValue(
        mockMvc.perform(get(path).header("Authorization", auth(token)))
            .andExpect(status().isOk).andReturn().response.contentAsString,
        objectMapper.typeFactory.constructCollectionType(List::class.java, MediaSecurityAlertPayload::class.java),
    )

    /** A PENDING ticket pointing at an object already in storage, as a completed PUT leaves it. */
    private fun pendingTicket(owner: UserEntity, key: String): MediaUploadEntity = uploads.save(
        MediaUploadEntity().apply {
            ownerId = owner.id
            storageKey = key
            declaredKind = "PNG"
            purpose = "MEDIA"
            status = "PENDING"
            expiresAt = clock.instant().plusSeconds(600)
        }
    )

    /** A quarantined entry for the destroy path. */
    private fun quarantineEntry(owner: UserEntity, key: String): MediaQuarantineEntityRow {
        storage.put(key, bytes = PNG, contentType = "image/png")
        val ticket = pendingTicket(owner, key)
        StubScanner.verdict = MediaScanStatus.INFECTED
        policies.setMediaScanEnabled(true)
        policies.setMediaScanProvider("clamav")
        policies.setMediaOnInfection("QUARANTINE")
        runCatching { uploadService.confirm(owner, ticket.id.toString(), null) }
        val row = quarantine.findByUploadId(ticket.id) ?: error("expected a quarantine row")
        check(row.status == "QUARANTINED")
        storage.put(row.quarantineKey, bytes = PNG, contentType = "image/png")
        return row
    }

    private typealias MediaQuarantineEntityRow = com.afrithecus.brainbox.api.media.security.MediaQuarantineEntity

    /**
     * An operator account: this class tests media-security behaviour, so the accounts hold the
     * console permissions. What an *unprivileged* admin may do is asserted in
     * `ConsoleSecurityWebTests`.
     */
    private fun account(phone: String, role: Role): UserEntity = users.save(
        UserEntity().apply {
            phoneNumber = phone
            email = phone + "@mediasecurity.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Media " + role.name
            this.role = role
            isActive = true
            isVerified = true
            if (role == Role.ADMIN) {
                platformPermissions = com.afrithecus.brainbox.api.identity.PlatformPermission.format(
                    setOf(
                        com.afrithecus.brainbox.api.identity.PlatformPermission.CONSOLE_READ,
                        com.afrithecus.brainbox.api.identity.PlatformPermission.SECURITY_READ,
                        com.afrithecus.brainbox.api.identity.PlatformPermission.SECURITY_OPERATE,
                    ),
                )
            }
        }
    )

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${user.phoneNumber}","password":"password123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

/** A scanner the tests steer; the real providers are covered in [MediaScannerTests]. */
class StubScanner : MediaScanner {
    override val name: String = "stub"

    override fun scan(bytes: ByteArray, contentType: String?): MediaScanResult =
        MediaScanResult(verdict, detail)

    companion object {
        @Volatile
        var verdict: MediaScanStatus = MediaScanStatus.CLEAN

        @Volatile
        var detail: String? = null
    }
}
