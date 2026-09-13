package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.auth.AuthService
import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.auth.web.SchoolRegistrationRequest
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolRegistrationRequestEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.SchoolRegistrationRequestRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.SchoolRegistrationRequestView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * School registration moderation (docs/ongoing/api_teacher_roster_changes.md /
 * doc 08): a user requests a new school, an admin approves or rejects it. The
 * school row is created only on approval so a pending request can never accept
 * enrolments or appear in the public directory.
 */
@Service
class SchoolRegistrationService(
    private val requests: SchoolRegistrationRequestRepository,
    private val schoolRepository: SchoolRepository,
    private val userRepository: UserRepository,
    private val authService: AuthService,
    private val clock: Clock,
) {

    @Transactional
    fun register(current: CurrentUser, request: SchoolRegistrationRequest): AuthResponse {
        val user = userRepository.findById(current.userId).orElse(null)
            ?: throw ApiException(ApiErrorCode.UNAUTHORIZED, "Account no longer exists")
        val name = request.schoolName.trim()
        if (name.length < MIN_NAME) {
            return authService.accountResponse(user, "School name is too short.").copy(success = false)
        }
        val requestId = request.requestId.trim().ifEmpty { UUID.randomUUID().toString() }
        requests.findByRequestId(requestId)?.let { existing ->
            return authService.accountResponse(user, messageFor(existing)).copy(success = existing.status != "REJECTED")
        }
        if (schoolRepository.findByNameIgnoreCase(name)?.isActive == true) {
            return authService.accountResponse(user, "A school with this name is already registered.").copy(success = false)
        }
        requests.findBySchoolNameIgnoreCaseAndStatus(name, "PENDING")?.let {
            return authService.accountResponse(user, "A request for this school is already awaiting review.")
        }
        requests.saveAndFlush(
            SchoolRegistrationRequestEntity().apply {
                this.requestId = requestId
                schoolName = name
                address = request.address?.trim()?.takeIf { it.isNotEmpty() }
                submittedBy = user.id
                submittedAt = clock.instant()
                status = "PENDING"
            }
        )
        return authService.accountResponse(user, "Your school is pending verification. You will be notified once approved.")
    }

    @Transactional(readOnly = true)
    fun list(status: String?): List<SchoolRegistrationRequestView> {
        val normalized = status?.trim()?.uppercase().orEmpty()
        val rows = when (normalized) {
            "", "ALL" -> requests.findAllByOrderByCreatedAtDesc()
            "PENDING", "APPROVED", "REJECTED" -> requests.findAllByStatusOrderByCreatedAtDesc(normalized)
            else -> throw invalidArgument("status must be PENDING, APPROVED, REJECTED or ALL")
        }
        return rows.map(::view)
    }

    @Transactional
    fun approve(adminId: UUID, idRaw: String, note: String?): SchoolRegistrationRequestView {
        val entity = requireRequest(idRaw)
        if (entity.status != "PENDING") return view(entity)
        val school = schoolRepository.findByNameIgnoreCase(entity.schoolName)?.apply {
            isActive = true
            if (location == null) location = entity.address
        } ?: SchoolEntity().apply {
            name = entity.schoolName
            location = entity.address
            isActive = true
        }
        schoolRepository.save(school)
        return review(entity, "APPROVED", adminId, note)
    }

    @Transactional
    fun reject(adminId: UUID, idRaw: String, note: String?): SchoolRegistrationRequestView {
        val entity = requireRequest(idRaw)
        if (entity.status != "PENDING") return view(entity)
        return review(entity, "REJECTED", adminId, note)
    }

    // ------------------------------------------------------------ internals

    private fun review(
        entity: SchoolRegistrationRequestEntity,
        status: String,
        adminId: UUID,
        note: String?,
    ): SchoolRegistrationRequestView {
        entity.status = status
        entity.reviewedBy = adminId
        entity.reviewedAt = clock.instant()
        entity.reviewNote = note?.trim()?.takeIf { it.isNotEmpty() }
        requests.saveAndFlush(entity)
        return view(entity)
    }

    private fun requireRequest(raw: String): SchoolRegistrationRequestEntity {
        val id = runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("requestId is not a valid identifier")
        return requests.findById(id).orElse(null) ?: throw notFound("School registration request not found")
    }

    private fun messageFor(entity: SchoolRegistrationRequestEntity): String = when (entity.status) {
        "APPROVED" -> "This school registration was approved."
        "REJECTED" -> "This school registration was declined."
        else -> "Your school is pending verification. You will be notified once approved."
    }

    private fun view(entity: SchoolRegistrationRequestEntity) = SchoolRegistrationRequestView(
        id = entity.id.toString(),
        requestId = entity.requestId,
        schoolName = entity.schoolName,
        address = entity.address,
        submittedBy = entity.submittedBy?.toString(),
        submittedAt = entity.submittedAt.toEpochMilli(),
        status = entity.status,
        reviewedBy = entity.reviewedBy?.toString(),
        reviewedAt = entity.reviewedAt?.toEpochMilli(),
        reviewNote = entity.reviewNote,
        createdAt = entity.createdAt.toEpochMilli(),
    )

    private companion object {
        const val MIN_NAME = 3
    }
}
