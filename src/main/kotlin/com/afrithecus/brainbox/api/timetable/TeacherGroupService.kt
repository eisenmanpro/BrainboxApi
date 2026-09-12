package com.afrithecus.brainbox.api.timetable

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.timetable.entity.CommunityServiceEntity
import com.afrithecus.brainbox.api.timetable.entity.HouseGroupEntity
import com.afrithecus.brainbox.api.timetable.entity.PeerCircleEntity
import com.afrithecus.brainbox.api.timetable.repository.CommunityServiceRepository
import com.afrithecus.brainbox.api.timetable.repository.HouseGroupRepository
import com.afrithecus.brainbox.api.timetable.repository.PeerCircleRepository
import com.afrithecus.brainbox.api.timetable.web.CommunityServicePayload
import com.afrithecus.brainbox.api.timetable.web.HouseGroupPayload
import com.afrithecus.brainbox.api.timetable.web.PeerCirclePayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * House groups, peer circles and community services (doc 04 section 9.3/9.4).
 * All three are teacher-owned lists of student ids; writes are idempotent on
 * the client-supplied id for offline replay.
 */
@Service
class TeacherGroupService(
    private val houseGroupRepository: HouseGroupRepository,
    private val peerCircleRepository: PeerCircleRepository,
    private val communityServiceRepository: CommunityServiceRepository,
    private val codec: QuestionCodec,
) {

    // ------------------------------------------------------------ house groups

    @Transactional(readOnly = true)
    fun houseGroups(teacher: UserEntity): List<HouseGroupPayload> {
        requireTeacher(teacher)
        return houseGroupRepository.findAllByTeacherIdOrderByCreatedAtDesc(teacher.id).map(::toHousePayload)
    }

    @Transactional
    fun createHouseGroup(teacher: UserEntity, request: HouseGroupPayload): HouseGroupPayload =
        saveHouseGroup(teacher, request.id.trim(), request)

    @Transactional
    fun updateHouseGroup(teacher: UserEntity, groupId: String, request: HouseGroupPayload): HouseGroupPayload {
        requireTeacher(teacher)
        val entity = resolveHouseGroup(teacher, groupId) ?: throw notFound("House group not found")
        applyHouse(entity, request, teacher)
        houseGroupRepository.saveAndFlush(entity)
        return toHousePayload(entity)
    }

    @Transactional
    fun deleteHouseGroup(teacher: UserEntity, groupId: String) {
        requireTeacher(teacher)
        val entity = resolveHouseGroup(teacher, groupId) ?: return
        houseGroupRepository.delete(entity)
    }

    // ------------------------------------------------------------ peer circles

    @Transactional(readOnly = true)
    fun peerCircles(teacher: UserEntity): List<PeerCirclePayload> {
        requireTeacher(teacher)
        return peerCircleRepository.findAllByTeacherIdOrderByCreatedAtDesc(teacher.id).map(::toPeerPayload)
    }

    @Transactional
    fun createPeerCircle(teacher: UserEntity, request: PeerCirclePayload): PeerCirclePayload =
        savePeerCircle(teacher, request.id.trim(), request)

    @Transactional
    fun updatePeerCircle(teacher: UserEntity, circleId: String, request: PeerCirclePayload): PeerCirclePayload {
        requireTeacher(teacher)
        val entity = resolvePeerCircle(teacher, circleId) ?: throw notFound("Peer circle not found")
        applyPeer(entity, request, teacher)
        peerCircleRepository.saveAndFlush(entity)
        return toPeerPayload(entity)
    }

    @Transactional
    fun deletePeerCircle(teacher: UserEntity, circleId: String) {
        requireTeacher(teacher)
        val entity = resolvePeerCircle(teacher, circleId) ?: return
        peerCircleRepository.delete(entity)
    }

    // -------------------------------------------------------- community services

    @Transactional(readOnly = true)
    fun communityServices(teacher: UserEntity): List<CommunityServicePayload> {
        requireTeacher(teacher)
        return communityServiceRepository.findAllByTeacherIdOrderByCreatedAtDesc(teacher.id).map(::toServicePayload)
    }

    @Transactional
    fun createCommunityService(teacher: UserEntity, request: CommunityServicePayload): CommunityServicePayload =
        saveCommunityService(teacher, request.id.trim(), request)

    @Transactional
    fun updateCommunityService(
        teacher: UserEntity,
        serviceId: String,
        request: CommunityServicePayload,
    ): CommunityServicePayload {
        requireTeacher(teacher)
        val entity = resolveCommunityService(teacher, serviceId) ?: throw notFound("Community service not found")
        applyService(entity, request, teacher)
        communityServiceRepository.saveAndFlush(entity)
        return toServicePayload(entity)
    }

    @Transactional
    fun deleteCommunityService(teacher: UserEntity, serviceId: String) {
        requireTeacher(teacher)
        val entity = resolveCommunityService(teacher, serviceId) ?: return
        communityServiceRepository.delete(entity)
    }

    // ------------------------------------------------------------ internals

    private fun saveHouseGroup(teacher: UserEntity, rawId: String, request: HouseGroupPayload): HouseGroupPayload {
        requireTeacher(teacher)
        val clientId = rawId.takeIf { it.isNotEmpty() } ?: "hg_" + UUID.randomUUID()
        val existing = houseGroupRepository.findByClientId(clientId)
        if (existing != null && existing.teacherId != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your house group")
        }
        val entity = existing ?: HouseGroupEntity().apply {
            this.clientId = clientId
            teacherId = teacher.id
        }
        applyHouse(entity, request, teacher)
        houseGroupRepository.saveAndFlush(entity)
        return toHousePayload(entity)
    }

    private fun savePeerCircle(teacher: UserEntity, rawId: String, request: PeerCirclePayload): PeerCirclePayload {
        requireTeacher(teacher)
        val clientId = rawId.takeIf { it.isNotEmpty() } ?: "pc_" + UUID.randomUUID()
        val existing = peerCircleRepository.findByClientId(clientId)
        if (existing != null && existing.teacherId != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your peer circle")
        }
        val entity = existing ?: PeerCircleEntity().apply {
            this.clientId = clientId
            teacherId = teacher.id
        }
        applyPeer(entity, request, teacher)
        peerCircleRepository.saveAndFlush(entity)
        return toPeerPayload(entity)
    }

    private fun saveCommunityService(
        teacher: UserEntity,
        rawId: String,
        request: CommunityServicePayload,
    ): CommunityServicePayload {
        requireTeacher(teacher)
        val clientId = rawId.takeIf { it.isNotEmpty() } ?: "cs_" + UUID.randomUUID()
        val existing = communityServiceRepository.findByClientId(clientId)
        if (existing != null && existing.teacherId != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your community service")
        }
        val entity = existing ?: CommunityServiceEntity().apply {
            this.clientId = clientId
            teacherId = teacher.id
        }
        applyService(entity, request, teacher)
        communityServiceRepository.saveAndFlush(entity)
        return toServicePayload(entity)
    }

    private fun applyHouse(entity: HouseGroupEntity, request: HouseGroupPayload, teacher: UserEntity) {
        val name = request.houseName.trim()
        if (name.isEmpty()) throw invalidArgument("houseName is required")
        val students = request.studentIds.filter { it.isNotBlank() }
        entity.houseId = request.houseId.trim().takeIf { it.isNotEmpty() }
        entity.houseName = name
        entity.houseColor = request.houseColor.trim().takeIf { it.isNotEmpty() }
        entity.classId = request.classId.trim().takeIf { it.isNotEmpty() }
        entity.studentIds = codec.toJson(students)
        entity.memberCount = if (request.memberCount > 0) request.memberCount else students.size
        entity.isActive = request.isActive
        entity.schoolId = teacher.schoolId
    }

    private fun applyPeer(entity: PeerCircleEntity, request: PeerCirclePayload, teacher: UserEntity) {
        val name = request.circleName.trim()
        if (name.isEmpty()) throw invalidArgument("circleName is required")
        entity.circleName = name
        entity.studentIds = codec.toJson(request.studentIds.filter { it.isNotBlank() })
        entity.isActive = request.isActive
        entity.schoolId = teacher.schoolId
    }

    private fun applyService(entity: CommunityServiceEntity, request: CommunityServicePayload, teacher: UserEntity) {
        val name = request.serviceName.trim()
        if (name.isEmpty()) throw invalidArgument("serviceName is required")
        entity.serviceName = name
        entity.studentsAssigned = codec.toJson(request.studentsAssigned.filter { it.isNotBlank() })
        entity.isActive = request.isActive
        entity.schoolId = teacher.schoolId
    }

    private fun resolveHouseGroup(teacher: UserEntity, raw: String): HouseGroupEntity? {
        val client = houseGroupRepository.findByClientId(raw)
        if (client != null) {
            if (client.teacherId != teacher.id) throw forbidden("Not your house group")
            return client
        }
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        val entity = houseGroupRepository.findById(id).orElse(null) ?: return null
        if (entity.teacherId != teacher.id) throw forbidden("Not your house group")
        return entity
    }

    private fun resolvePeerCircle(teacher: UserEntity, raw: String): PeerCircleEntity? {
        val client = peerCircleRepository.findByClientId(raw)
        if (client != null) {
            if (client.teacherId != teacher.id) throw forbidden("Not your peer circle")
            return client
        }
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        val entity = peerCircleRepository.findById(id).orElse(null) ?: return null
        if (entity.teacherId != teacher.id) throw forbidden("Not your peer circle")
        return entity
    }

    private fun resolveCommunityService(teacher: UserEntity, raw: String): CommunityServiceEntity? {
        val client = communityServiceRepository.findByClientId(raw)
        if (client != null) {
            if (client.teacherId != teacher.id) throw forbidden("Not your community service")
            return client
        }
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        val entity = communityServiceRepository.findById(id).orElse(null) ?: return null
        if (entity.teacherId != teacher.id) throw forbidden("Not your community service")
        return entity
    }

    private fun forbidden(message: String) = ApiException(ApiErrorCode.FORBIDDEN, message)

    private fun toHousePayload(entity: HouseGroupEntity) = HouseGroupPayload(
        id = entity.clientId,
        houseId = entity.houseId ?: "",
        houseName = entity.houseName,
        houseColor = entity.houseColor ?: "",
        teacherId = entity.teacherId.toString(),
        classId = entity.classId ?: "",
        memberCount = entity.memberCount,
        studentIds = codec.parseList(entity.studentIds) ?: emptyList(),
        isActive = entity.isActive,
    )

    private fun toPeerPayload(entity: PeerCircleEntity) = PeerCirclePayload(
        id = entity.clientId,
        circleName = entity.circleName,
        teacherId = entity.teacherId.toString(),
        studentIds = codec.parseList(entity.studentIds) ?: emptyList(),
        isActive = entity.isActive,
    )

    private fun toServicePayload(entity: CommunityServiceEntity) = CommunityServicePayload(
        id = entity.clientId,
        serviceName = entity.serviceName,
        teacherId = entity.teacherId.toString(),
        studentsAssigned = codec.parseList(entity.studentsAssigned) ?: emptyList(),
        isActive = entity.isActive,
    )

    private fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw ApiException(ApiErrorCode.FORBIDDEN, "Teacher access only")
    }
}
