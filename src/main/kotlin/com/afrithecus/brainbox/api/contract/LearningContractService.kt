package com.afrithecus.brainbox.api.contract

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.contract.entity.LearningContractCommitmentEntity
import com.afrithecus.brainbox.api.contract.entity.LearningContractEntity
import com.afrithecus.brainbox.api.contract.repository.ContractTemplateRepository
import com.afrithecus.brainbox.api.contract.repository.LearningContractCommitmentRepository
import com.afrithecus.brainbox.api.contract.repository.LearningContractRepository
import com.afrithecus.brainbox.api.contract.web.ContractCommitmentPayload
import com.afrithecus.brainbox.api.contract.web.ContractTemplatePayload
import com.afrithecus.brainbox.api.contract.web.LearningContractPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.NotificationService
import com.afrithecus.brainbox.api.notification.model.NotificationType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Teacher learning contracts (doc 04 section 11): CRUD, per-commitment
 * completion, student/parent reminders and the template catalogue. Writes are
 * idempotent on the client id for the offline outbox.
 */
@Service
class LearningContractService(
    private val contractRepository: LearningContractRepository,
    private val commitmentRepository: LearningContractCommitmentRepository,
    private val templateRepository: ContractTemplateRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(teacher: UserEntity): List<LearningContractPayload> {
        requireTeacher(teacher)
        return contractRepository.findAllByTeacherIdOrderByLastUpdatedDesc(teacher.id).map(::payload)
    }

    @Transactional(readOnly = true)
    fun get(teacher: UserEntity, contractIdRaw: String): LearningContractPayload {
        requireTeacher(teacher)
        return payload(requireOwned(teacher, contractIdRaw))
    }

    @Transactional
    fun create(teacher: UserEntity, request: LearningContractPayload): LearningContractPayload {
        requireTeacher(teacher)
        val clientId = request.id.trim().takeIf { it.isNotEmpty() } ?: "lc_" + UUID.randomUUID()
        val existing = contractRepository.findByClientId(clientId)
        if (existing != null && existing.teacherId != teacher.id) throw forbidden("Not your learning contract")
        val entity = existing ?: LearningContractEntity().apply {
            this.clientId = clientId
            teacherId = teacher.id
        }
        apply(entity, request, teacher)
        contractRepository.saveAndFlush(entity)
        replaceCommitments(entity, request.commitments)
        return payload(entity)
    }

    @Transactional
    fun update(teacher: UserEntity, contractIdRaw: String, request: LearningContractPayload): LearningContractPayload {
        requireTeacher(teacher)
        val entity = requireOwned(teacher, contractIdRaw)
        apply(entity, request, teacher)
        contractRepository.saveAndFlush(entity)
        replaceCommitments(entity, request.commitments)
        return payload(entity)
    }

    /** Repeat-safe: an unknown contract is treated as already deleted. */
    @Transactional
    fun delete(teacher: UserEntity, contractIdRaw: String) {
        requireTeacher(teacher)
        val entity = resolveOwned(teacher, contractIdRaw) ?: return
        contractRepository.delete(entity)
    }

    @Transactional
    fun updateCommitment(
        teacher: UserEntity,
        contractIdRaw: String,
        commitmentIdRaw: String,
        isCompleted: Boolean,
        notes: String?,
    ): LearningContractPayload {
        requireTeacher(teacher)
        val contract = requireOwned(teacher, contractIdRaw)
        val commitment = resolveCommitment(contract.id, commitmentIdRaw)
            ?: throw notFound("Commitment not found")
        commitment.isCompleted = isCompleted
        if (notes != null) commitment.notes = notes.trim().takeIf { it.isNotEmpty() }
        commitment.completionDate = if (isCompleted) clock.instant() else null
        commitmentRepository.saveAndFlush(commitment)
        contract.lastUpdated = clock.instant()
        contractRepository.saveAndFlush(contract)
        return payload(contract)
    }

    @Transactional
    fun remind(teacher: UserEntity, contractIdRaw: String, partyRaw: String) {
        requireTeacher(teacher)
        val contract = requireOwned(teacher, contractIdRaw)
        val party = partyRaw.trim().lowercase()
        if (party != "student" && party != "parent") throw invalidArgument("party must be student or parent")
        val child = userRepository.findById(contract.childId).orElse(null)
            ?: throw notFound("Learner not found")
        val recipient = if (party == "parent") {
            child.parentUserId ?: throw invalidArgument("Learner has no linked parent")
        } else {
            child.id
        }
        notificationService.notifyUser(
            userId = recipient,
            title = "Learning contract reminder",
            message = "Please review the learning contract for " + child.name + " (" + contract.term + ").",
            type = NotificationType.SYSTEM,
            actionRoute = "learning_contracts",
            actionLabel = "View",
            metadata = mapOf("contractId" to contract.id.toString(), "party" to party),
        )
    }

    @Transactional(readOnly = true)
    fun templates(): List<ContractTemplatePayload> =
        templateRepository.findAllByOrderByCategoryAscTitleAsc().map { template ->
            ContractTemplatePayload(
                id = template.id.toString(),
                title = template.title,
                description = template.description,
                category = template.category,
                defaultCommitments = parseCommitments(template.defaultCommitments),
            )
        }

    // ------------------------------------------------------------ internals

    private fun apply(entity: LearningContractEntity, request: LearningContractPayload, teacher: UserEntity) {
        entity.teacherId = teacher.id
        entity.childId = parseUuid(request.childId, "childId")
        entity.term = request.term.trim().ifEmpty { "TERM_1" }
        entity.status = validateStatus(request.status)
        entity.startDate = instant(request.startDate)
        entity.endDate = instant(request.endDate)
        entity.lastUpdated = if (request.lastUpdated > 0) Instant.ofEpochMilli(request.lastUpdated) else clock.instant()
    }

    private fun replaceCommitments(contract: LearningContractEntity, commitments: List<ContractCommitmentPayload>) {
        val kept = mutableSetOf<String>()
        commitments.forEachIndexed { index, commitment ->
            val key = commitment.id.trim().takeIf { it.isNotEmpty() } ?: "cm_" + UUID.randomUUID()
            kept += key
            val entity = commitmentRepository.findByContractIdAndClientId(contract.id, key)
                ?: LearningContractCommitmentEntity().apply {
                    contractId = contract.id
                    clientId = key
                }
            entity.party = commitment.party.trim().uppercase().ifEmpty { "STUDENT" }
            entity.text = commitment.text.trim()
            entity.isCompleted = commitment.isCompleted
            entity.dueDate = commitment.dueDate?.let(Instant::ofEpochMilli)
            entity.notes = commitment.notes?.trim()?.takeIf { it.isNotEmpty() }
            entity.completionDate = commitment.completionDate?.let(Instant::ofEpochMilli)
            entity.sortOrder = index
            commitmentRepository.save(entity)
        }
        commitmentRepository.findAllByContractIdOrderBySortOrderAsc(contract.id)
            .filter { it.clientId !in kept }
            .forEach { commitmentRepository.delete(it) }
    }

    private fun payload(entity: LearningContractEntity) = LearningContractPayload(
        id = entity.clientId,
        childId = entity.childId.toString(),
        teacherId = entity.teacherId.toString(),
        term = entity.term,
        status = entity.status,
        startDate = entity.startDate.toEpochMilli(),
        endDate = entity.endDate.toEpochMilli(),
        lastUpdated = entity.lastUpdated.toEpochMilli(),
        commitments = commitmentRepository.findAllByContractIdOrderBySortOrderAsc(entity.id)
            .map(::commitmentPayload),
    )

    private fun commitmentPayload(entity: LearningContractCommitmentEntity) = ContractCommitmentPayload(
        id = entity.clientId,
        party = entity.party,
        text = entity.text,
        isCompleted = entity.isCompleted,
        dueDate = entity.dueDate?.toEpochMilli(),
        notes = entity.notes,
        completionDate = entity.completionDate?.toEpochMilli(),
    )

    private fun resolveOwned(teacher: UserEntity, raw: String): LearningContractEntity? {
        val byClient = contractRepository.findByClientId(raw)
        if (byClient != null) {
            if (byClient.teacherId != teacher.id) throw forbidden("Not your learning contract")
            return byClient
        }
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        val entity = contractRepository.findById(id).orElse(null) ?: return null
        if (entity.teacherId != teacher.id) throw forbidden("Not your learning contract")
        return entity
    }

    private fun requireOwned(teacher: UserEntity, raw: String): LearningContractEntity =
        resolveOwned(teacher, raw) ?: throw notFound("Learning contract not found")

    private fun resolveCommitment(contractId: UUID, raw: String): LearningContractCommitmentEntity? {
        commitmentRepository.findByContractIdAndClientId(contractId, raw)?.let { return it }
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        val entity = commitmentRepository.findById(id).orElse(null) ?: return null
        return if (entity.contractId == contractId) entity else null
    }

    private fun parseCommitments(json: String?): List<ContractCommitmentPayload> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            mapper.readValue(json, Array<ContractCommitmentPayload>::class.java).toList()
        }.getOrDefault(emptyList())
    }

    private fun validateStatus(raw: String): String {
        val value = raw.trim().uppercase().ifEmpty { "ACTIVE" }
        if (value !in setOf("ACTIVE", "COMPLETED", "EXPIRED")) {
            throw invalidArgument("Unknown contract status: " + raw)
        }
        return value
    }

    private fun instant(millis: Long): Instant =
        if (millis > 0) Instant.ofEpochMilli(millis) else clock.instant()

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")

    private fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw forbidden("Teacher access only")
    }

    private fun forbidden(message: String) = ApiException(ApiErrorCode.FORBIDDEN, message)
}
