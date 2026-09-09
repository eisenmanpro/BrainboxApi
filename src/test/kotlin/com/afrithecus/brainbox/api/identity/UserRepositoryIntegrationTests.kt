package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies entity mappings against the Flyway schema (Hibernate validate) and
 * persistence invariants: unique login identifiers, optimistic versioning.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserRepositoryIntegrationTests(
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
) {

    private fun newUser(phone: String, role: Role = Role.STUDENT) =
        UserEntity().apply {
            phoneNumber = phone
            email = phone + "@example.com"
            passwordHash = "not-a-real-hash"
            name = "Test User"
            this.role = role
            isActive = true
            isVerified = false
        }

    @Test
    fun `persists a user and looks it up by phone, email and admission number`() {
        val user = newUser("0700000001").apply {
            studentAdmissionNumber = "ADM-2026-001"
            role = Role.TEACHER
            subRole = SubRole.CTEACHER
        }
        userRepository.save(user)

        val byPhone = userRepository.findByPhoneNumber("0700000001")
        val byEmail = userRepository.findByEmail("0700000001@example.com")
        val byAdm = userRepository.findByStudentAdmissionNumber("ADM-2026-001")

        assertNotNull(byPhone)
        assertEquals(Role.TEACHER, byPhone.role)
        assertEquals(SubRole.CTEACHER, byPhone.subRole)
        assertEquals(byPhone.id, byEmail?.id)
        assertEquals(byPhone.id, byAdm?.id)
    }

    @Test
    fun `parent-child links resolve linked children`() {
        val parent = newUser("0700000002", role = Role.PARENT)
        userRepository.save(parent)
        val child = newUser("0700000003").apply { parentUserId = parent.id }
        userRepository.save(child)

        val children = userRepository.findByParentUserId(parent.id)
        assertEquals(listOf(child.id), children.map { it.id })
    }

    @Test
    fun `optimistic version increments on update`() {
        val user = userRepository.save(newUser("0700000004"))
        val versionBefore = user.version
        user.name = "Renamed User"
        userRepository.saveAndFlush(user)
        val reloaded = userRepository.findById(user.id).orElseThrow()
        assertTrue(reloaded.version > versionBefore)
    }

    @Test
    fun `duplicate phone number violates the unique constraint`() {
        userRepository.saveAndFlush(newUser("0700000005"))
        assertFailsWith<DataIntegrityViolationException> {
            userRepository.saveAndFlush(newUser("0700000005"))
        }
    }

    @Test
    fun `unknown login identifiers resolve to null`() {
        assertNull(userRepository.findByPhoneNumber("0999999999"))
        assertNull(userRepository.findByEmail("nobody@example.com"))
    }
}
