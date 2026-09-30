package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.report.entity.ReportJobEntity
import com.afrithecus.brainbox.api.report.repository.ReportDownloadRepository
import com.afrithecus.brainbox.api.report.repository.ReportJobRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The lifetime student allowance must hold under concurrency: the owner-row lock in
 * [ReportDownloadService.reserve] serializes the count-and-insert, so exactly the
 * allowance can be reserved no matter how many downloads race. Deliberately not
 * transactional so the competing threads each open their own transaction and the
 * lock is exercised for real.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportQuotaConcurrencyTests(
    @Autowired private val downloads: ReportDownloadService,
    @Autowired private val users: UserRepository,
    @Autowired private val jobs: ReportJobRepository,
    @Autowired private val downloadRepository: ReportDownloadRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private var ownerId: UUID? = null
    private var jobId: UUID? = null

    @AfterTest
    fun cleanUp() {
        ownerId?.let { downloadRepository.deleteByOwnerId(it) }
        jobId?.let { jobs.deleteById(it) }
        ownerId?.let { users.deleteById(it) }
    }

    @Test
    fun concurrentDownloadsCannotExceedTheQuota() {
        val owner = users.save(
            UserEntity().apply {
                val suffix = (1000..9999).random()
                phoneNumber = "07559" + suffix
                email = "quota" + suffix + "@concurrency.test"
                passwordHash = passwordEncoder.encode("password123") ?: error("encode")
                name = "Quota Owner"
                role = Role.TEACHER
                subRole = SubRole.GRADE_COORDINATOR
                isActive = true
                isVerified = true
            }
        )
        ownerId = owner.id
        val job = jobs.save(
            ReportJobEntity().apply {
                this.ownerId = owner.id
                requestId = UUID.randomUUID().toString()
                reportType = "CBC_CLASS"
                title = "Quota concurrency"
                status = "READY"
                storageName = UUID.randomUUID().toString() + ".pdf"
            }
        )
        jobId = job.id

        val attempts = 14
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        val success = AtomicInteger()
        val limited = AtomicInteger()
        repeat(attempts) {
            Thread {
                ready.countDown()
                start.await()
                try {
                    downloads.reserve(owner.id, job.id, ReportDownloadScope.STUDENT, 1)
                    success.incrementAndGet()
                } catch (failure: ApiException) {
                    if (failure.code == ApiErrorCode.TOO_MANY_REQUESTS) {
                        limited.incrementAndGet()
                    } else {
                        throw failure
                    }
                }
            }.start()
        }
        ready.await(10, TimeUnit.SECONDS)
        start.countDown()
        val deadline = System.currentTimeMillis() + 30_000
        while (success.get() + limited.get() < attempts && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        check(success.get() == 10) { "exactly the lifetime allowance must be reservable, got " + success.get() }
        check(limited.get() == attempts - 10) { "the rest must be rate-limited, got " + limited.get() }
    }
}
