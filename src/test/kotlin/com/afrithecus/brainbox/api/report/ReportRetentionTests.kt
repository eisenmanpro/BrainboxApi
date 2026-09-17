package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.report.entity.ReportJobEntity
import com.afrithecus.brainbox.api.report.repository.ReportJobRepository
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.test.Test

/**
 * The retention sweep must expire job rows with their files, so a READY history
 * entry never outlives the file it points at.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportRetentionTests(
    @Autowired private val jobs: ReportJobRepository,
    @Autowired private val scheduler: ReportScheduler,
    @Autowired private val entityManager: EntityManager,
    @Autowired private val users: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val clock: Clock,
) {

    @Test
    fun retentionSweepRemovesExpiredJobsAndKeepsFreshOnes() {
        val owner = users.save(
            UserEntity().apply {
                phoneNumber = "0755900123"
                email = ownerEmail()
                passwordHash = passwordEncoder.encode("password123") ?: error("encode")
                name = "Retention Owner"
                role = Role.TEACHER
                isActive = true
                isVerified = true
            }
        )
        val expired = jobs.saveAndFlush(job(owner.id, "retention-old"))
        val fresh = jobs.saveAndFlush(job(owner.id, "retention-new"))

        // createdAt is immutable to JPA, so age the row directly past the window.
        entityManager.createNativeQuery("UPDATE report_jobs SET created_at = :ts WHERE id = :id")
            .setParameter("ts", clock.instant().minus(Duration.ofDays(200)))
            .setParameter("id", expired.id)
            .executeUpdate()
        entityManager.flush()
        entityManager.clear()

        scheduler.prune()

        check(jobs.findById(expired.id).isEmpty) { "the expired job must be pruned" }
        check(jobs.findById(fresh.id).isPresent) { "a fresh job must be kept" }
    }

    private fun job(ownerId: UUID, requestId: String): ReportJobEntity = ReportJobEntity().apply {
        this.requestId = requestId
        this.ownerId = ownerId
        reportType = "CBC_CLASS"
        title = "Retention test"
        status = "READY"
        storageName = UUID.randomUUID().toString() + ".pdf"
    }

    private fun ownerEmail(): String = "retention-" + UUID.randomUUID().toString().take(8) + "@report.test"
}
