package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.identity.AdminSchoolService
import com.afrithecus.brainbox.api.identity.admin.IdentityAdminService
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The opt-in approval gate (docs/phone_verification.md): with require-for-approval on, an admin
 * cannot approve a pending account whose phone is unverified. Default is off.
 */
@SpringBootTest(properties = ["app.auth.phone-verification.require-for-approval=true"])
@ActiveProfiles("test")
@Transactional
class PhoneVerificationApprovalTests(
    @Autowired private val identityAdminService: IdentityAdminService,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var admin: UserEntity
    private lateinit var pending: UserEntity

    @BeforeEach
    fun setUp() {
        val school = schoolRepository.save(SchoolEntity().apply { name = "Approval School"; isActive = true })
        admin = userRepository.save(UserEntity().apply {
            phoneNumber = "0755600204"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "ICT Admin"
            role = Role.TEACHER
            subRole = SubRole.ICT_ADMIN
            schoolId = school.id
            isActive = true
            isVerified = true
        })
        pending = userRepository.save(UserEntity().apply {
            phoneNumber = "0755600205"
            passwordHash = passwordEncoder.encode("1234") ?: error("encode")
            name = "Pending Learner"
            role = Role.STUDENT
            schoolId = school.id
            isActive = true
            isVerified = false
            verificationStatus = AccountStatus.PENDING_VERIFICATION
        })
    }

    @Test
    fun `an unverified phone blocks approval while the gate is on`() {
        val actor = CurrentUser(admin.id, Role.TEACHER, SubRole.ICT_ADMIN)
        val failure = runCatching { identityAdminService.setVerified(pending.id.toString(), true, actor) }
        check(failure.exceptionOrNull() is ApiException)
        check((failure.exceptionOrNull() as ApiException).code == ApiErrorCode.CONFLICT)
        check(!userRepository.findById(pending.id).orElseThrow().isVerified)

        // Once the phone is verified the same approval succeeds.
        val refreshed = userRepository.findById(pending.id).orElseThrow()
        refreshed.phoneVerifiedAt = Instant.now()
        userRepository.saveAndFlush(refreshed)
        identityAdminService.setVerified(pending.id.toString(), true, actor)
        val approved = userRepository.findById(pending.id).orElseThrow()
        check(approved.isVerified)
        check(approved.verificationStatus == AccountStatus.VERIFIED)
    }
}
