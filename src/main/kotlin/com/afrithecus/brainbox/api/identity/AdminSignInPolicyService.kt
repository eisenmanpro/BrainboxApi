package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.identity.entity.SchoolSystemSettingsEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.RefreshTokenRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolSystemSettingsRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.repository.UserSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * N4: the school administrator sign-in restriction.
 *
 * The switch is open by default. When a school closes it, every administrator of
 * that school except the pinned "first administrator" is refused at sign-in with
 * the owner's sentence, and their live sessions are revoked so an access token
 * issued before the switch cannot keep working. The first administrator is
 * exempt on purpose: the brief requires that someone can always turn the
 * restriction back off, otherwise the switch would brick the school.
 */
@Service
class AdminSignInPolicyService(
    private val settingsRepository: SchoolSystemSettingsRepository,
    private val userRepository: UserRepository,
    private val sessionRepository: UserSessionRepository,
    private val refreshRepository: RefreshTokenRepository,
    private val auditLogService: AuditLogService,
    private val clock: Clock,
) {

    fun isSchoolAdministrator(user: UserEntity): Boolean =
        user.role == Role.TEACHER && user.subRole == SubRole.ICT_ADMIN

    /** Throws the owner's refusal when [user] is a restricted school administrator. */
    @Transactional
    fun enforceAllowed(user: UserEntity) {
        if (!isSchoolAdministrator(user)) return
        val schoolId = user.schoolId ?: return
        val settings = settingsRepository.findById(schoolId).orElse(null) ?: return
        if (settings.adminSignInOpen) return
        if (ownerOf(schoolId, persist = true) == user.id) return
        throw ApiException(
            ApiErrorCode.FORBIDDEN,
            RESTRICTED_MESSAGE,
            mapOf("reason" to "ADMIN_SIGN_IN_RESTRICTED"),
        )
    }

    /**
     * The school's first administrator: the pinned account while it is still an
     * active administrator of the school, otherwise the earliest-created active
     * administrator. [persist] pins that answer for later calls (a login or a
     * settings update may write; a read-only settings GET must not).
     */
    @Transactional
    fun ownerOf(
        schoolId: UUID,
        persist: Boolean,
        settingsOverride: SchoolSystemSettingsEntity? = null,
    ): UUID? {
        // A fresh school has no settings row yet; the owner still exists (the earliest
        // administrator) and must be reported so the client knows who may unlock. The row
        // is created lazily only when a write is allowed to persist the pin.
        val settings = settingsOverride ?: settingsRepository.findById(schoolId).orElse(null)
        val pinned = settings?.adminOwnerId
        if (pinned != null) {
            val user = userRepository.findById(pinned).orElse(null)
            if (user != null && isSchoolAdministrator(user) && user.isActive && user.schoolId == schoolId) {
                return user.id
            }
        }
        val earliest = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .filter { isSchoolAdministrator(it) }
            .minWithOrNull(compareBy({ it.createdAt }, { it.id }))
            ?: return null
        if (persist) {
            val row = settings ?: SchoolSystemSettingsEntity().apply { this.schoolId = schoolId }
            row.adminOwnerId = earliest.id
            row.updatedAt = clock.instant()
            settingsRepository.save(row)
            auditLogService.record(schoolId, earliest, "Pinned first school administrator (" + earliest.name + ")")
        }
        return earliest.id
    }

    /**
     * Revokes every non-owner administrator's sessions and refresh tokens when the
     * restriction is switched on. Returns how many sessions were signed out so the
     * caller can audit the real number, never an estimate.
     */
    @Transactional
    fun revokeOtherAdministratorSessions(schoolId: UUID, ownerId: UUID?): Int {
        val administrators = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .filter { isSchoolAdministrator(it) && it.id != ownerId }
        var revoked = 0
        administrators.forEach { admin ->
            sessionRepository.findAllByUserIdAndIsActiveTrue(admin.id).forEach { session ->
                session.isActive = false
                sessionRepository.save(session)
                revoked++
            }
            val tokens = refreshRepository.findAllByUserIdAndRevokedFalse(admin.id)
            if (tokens.isNotEmpty()) {
                tokens.forEach { it.revoked = true }
                refreshRepository.saveAll(tokens)
            }
        }
        return revoked
    }

    fun isOpen(schoolId: UUID): Boolean =
        settingsRepository.findById(schoolId).orElse(null)?.adminSignInOpen ?: true

    fun storedSettings(schoolId: UUID): SchoolSystemSettingsEntity? =
        settingsRepository.findById(schoolId).orElse(null)

    companion object {
        /**
         * The owner's required copy, kept as one server constant: both clients render
         * an API error message verbatim, so the refusal must be authored here and not
         * duplicated as client resources.
         */
        const val RESTRICTED_MESSAGE =
            "Administrator sign-in is currently restricted for this school. Contact the school admin for more information."
    }
}
