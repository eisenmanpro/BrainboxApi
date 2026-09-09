package com.afrithecus.brainbox.api.messaging

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.messaging.entity.MessageEntity
import com.afrithecus.brainbox.api.messaging.model.AudienceType
import com.afrithecus.brainbox.api.messaging.model.Folder
import com.afrithecus.brainbox.api.messaging.repository.MessageRepository
import com.afrithecus.brainbox.api.messaging.web.AttachmentPayload
import com.afrithecus.brainbox.api.messaging.web.MessagePayload
import com.afrithecus.brainbox.api.messaging.web.SchoolMemberPayload
import com.afrithecus.brainbox.api.messaging.web.SendMessageRequest
import com.afrithecus.brainbox.api.messaging.web.TeacherSendMessageRequest
import com.afrithecus.brainbox.api.exams.QuestionCodec
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

/**
 * Messaging core (doc 05 §2): server rows per delivery, unified inbox, direct
 * sends with school-staff authorization and teacher fan-out to owned classes.
 * Message ids are client-provided (msg_<ts>_<user>) and replays are no-ops.
 */
@Service
class MessagingService(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val codec: QuestionCodec,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    // ------------------------------------------------------------ direct send

    @Transactional
    fun send(sender: UserEntity, request: SendMessageRequest, clientMessageId: String?): MessagePayload {
        val recipient = resolveUser(request.recipientId, "recipientId")
        authorizeDirectSender(sender, recipient)
        val msgGroup = clientMessageId ?: generateMessageId(sender.id)
        if (messageRepository.existsByMsgGroupAndSenderId(msgGroup, sender.id)) {
            return messageRepository.findFirstByMsgGroupAndSenderIdOrderByCreatedAtAsc(msgGroup, sender.id)
                ?.let(::toPayload) ?: throw ApiException(ApiErrorCode.CONFLICT, "Message id replay")
        }
        val sent = saveRow(msgGroup, sender.id, recipient.id, request.subject, request.body, request.attachments, Folder.sent)
        saveRow(msgGroup, sender.id, recipient.id, request.subject, request.body, request.attachments, Folder.inbox)
        return toPayload(sent)
    }

    // ---------------------------------------------------------- teacher send

    @Transactional
    fun teacherSend(teacher: UserEntity, request: TeacherSendMessageRequest, clientMessageId: String?): MessagePayload {
        val audience = runCatching { AudienceType.valueOf(request.audienceType.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("audienceType must be INDIVIDUAL, CLASS or CLASS_PARENTS")
        val msgGroup = clientMessageId ?: generateMessageId(teacher.id)
        if (messageRepository.existsByMsgGroupAndSenderId(msgGroup, teacher.id)) {
            return messageRepository.findFirstByMsgGroupAndSenderIdOrderByCreatedAtAsc(msgGroup, teacher.id)
                ?.let(::toPayload) ?: throw ApiException(ApiErrorCode.CONFLICT, "Message id replay")
        }

        when (audience) {
            AudienceType.INDIVIDUAL -> {
                val recipient = request.recipientId?.let { resolveUser(it, "recipientId") }
                    ?: throw invalidArgument("recipientId is required for INDIVIDUAL")
                sameSchoolOrThrow(teacher, recipient)
                val sent = saveRow(msgGroup, teacher.id, recipient.id, request.subject, request.body, request.attachments, Folder.sent)
                saveRow(msgGroup, teacher.id, recipient.id, request.subject, request.body, request.attachments, Folder.inbox)
                return toPayload(sent)
            }
            AudienceType.CLASS -> {
                val students = rosterOfOwnedClass(teacher, request.audienceId)
                val sent = saveRow(msgGroup, teacher.id, null, request.subject, request.body, request.attachments, Folder.sent)
                students.forEach { student ->
                    saveRow(msgGroup, teacher.id, student.id, request.subject, request.body, request.attachments, Folder.inbox)
                }
                return toPayload(sent)
            }
            AudienceType.CLASS_PARENTS -> {
                // Unified inbox: copies land in each student's inbox flagged for the parent.
                val students = rosterOfOwnedClass(teacher, request.audienceId)
                val sent = saveRow(msgGroup, teacher.id, null, request.subject, request.body, request.attachments, Folder.sent)
                students.forEach { student ->
                    saveRow(msgGroup, teacher.id, student.id, request.subject, request.body, request.attachments, Folder.inbox, intendedForParent = true)
                }
                return toPayload(sent)
            }
        }
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    fun list(userId: UUID, folder: String): List<MessagePayload> {
        val f = parseFolder(folder)
        return when (f) {
            Folder.inbox -> messageRepository.findAllByRecipientIdAndFolderOrderByCreatedAtDesc(userId, Folder.inbox)
            else -> messageRepository.findAllBySenderIdAndFolderOrderByCreatedAtDesc(userId, f)
        }.map(::toPayload)
    }

    @Transactional
    fun markRead(user: UserEntity, messageIdRaw: String) {
        val id = runCatching { UUID.fromString(messageIdRaw) }.getOrNull()
            ?: throw invalidArgument("message id is not a valid identifier")
        val message = messageRepository.findById(id).orElse(null) ?: throw notFound("Message not found")
        if (message.folder != Folder.inbox || message.recipientId != user.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your message")
        }
        if (!message.isRead) {
            message.isRead = true
            message.readAt = clock.instant()
            messageRepository.save(message)
        }
    }

    // ------------------------------------------------- school member dir

    @Transactional(readOnly = true)
    fun schoolMembers(viewer: UserEntity, schoolIdRaw: String, roleRaw: String?): List<SchoolMemberPayload> {
        val schoolId = runCatching { UUID.fromString(schoolIdRaw) }.getOrNull()
            ?: throw invalidArgument("school id is not a valid identifier")
        if (viewer.role != Role.ADMIN && viewer.schoolId != schoolId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your school")
        }
        val requestedRole = roleRaw?.let { runCatching { Role.valueOf(it.trim().uppercase()) }.getOrNull() }
        return userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .filter { requestedRole == null || it.role == requestedRole }
            .map {
                SchoolMemberPayload(
                    id = it.id.toString(),
                    name = it.name,
                    role = it.role.name,
                )
            }
    }

    // ------------------------------------------------------------ internals

    private fun rosterOfOwnedClass(teacher: UserEntity, audienceId: String?): List<UserEntity> {
        val classId = runCatching { UUID.fromString(audienceId) }.getOrNull()
            ?: throw invalidArgument("audienceId (classId) is required for class audiences")
        val clazz = classRepository.findById(classId).orElse(null) ?: throw notFound("Class not found")
        if (clazz.teacherUserId != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only classes you own can be targeted")
        }
        val memberIds = membershipRepository.findAllByClassId(clazz.id).map { it.studentId }
        if (memberIds.isEmpty()) return emptyList()
        return userRepository.findAllById(memberIds).filter { it.isActive }
    }

    private fun authorizeDirectSender(sender: UserEntity, recipient: UserEntity) {
        if (sender.role == Role.STUDENT) {
            val staff = recipient.role == Role.TEACHER
            if (!staff || recipient.schoolId == null || recipient.schoolId != sender.schoolId) {
                throw ApiException(ApiErrorCode.FORBIDDEN, "Students may only message staff of their own school")
            }
            return
        }
        sameSchoolOrThrow(sender, recipient)
    }

    private fun sameSchoolOrThrow(a: UserEntity, b: UserEntity) {
        if (a.role != Role.ADMIN && (a.schoolId == null || a.schoolId != b.schoolId)) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Recipient is not in your school")
        }
    }

    private fun resolveUser(raw: String, field: String): UserEntity {
        val id = runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")
        val user = userRepository.findById(id).orElse(null) ?: throw notFound("Recipient not found")
        if (!user.isActive) throw notFound("Recipient not found")
        return user
    }

    private fun saveRow(
        msgGroup: String,
        senderId: UUID,
        recipientId: UUID?,
        subject: String?,
        body: String,
        attachments: List<AttachmentPayload>?,
        folder: Folder,
        intendedForParent: Boolean = false,
    ): MessageEntity {
        val row = MessageEntity().apply {
            this.msgGroup = msgGroup
            this.senderId = senderId
            this.recipientId = recipientId
            this.subject = subject?.trim()?.takeIf { it.isNotEmpty() }
            this.body = body.trim()
            this.attachments = attachments?.let { mapper.writeValueAsString(it) }
            this.folder = folder
            this.intendedForParent = intendedForParent
            createdAt = clock.instant()
        }
        messageRepository.save(row)
        return row
    }

    private fun parseFolder(raw: String): Folder =
        runCatching { Folder.valueOf(raw.trim().lowercase()) }.getOrNull()
            ?: throw invalidArgument("folder must be inbox, sent or outbox")

    private fun generateMessageId(userId: UUID): String =
        "msg_" + clock.millis() + "_" + userId.toString()

    internal fun toPayload(row: MessageEntity): MessagePayload = MessagePayload(
        id = row.id.toString(),
        senderId = row.senderId.toString(),
        recipientId = row.recipientId?.toString(),
        subject = row.subject,
        body = row.body,
        attachments = row.attachments?.let {
            runCatching { mapper.readTree(it) }.getOrNull()?.let { node ->
                if (node.isArray) {
                    (0 until node.size()).map { i ->
                        val item = node.get(i)
                        AttachmentPayload(
                            name = item.get("name")?.asString(),
                            url = item.get("url")?.asString(),
                        )
                    }
                } else emptyList()
            }
        },
        folder = row.folder.name,
        isRead = row.isRead,
        readAt = row.readAt?.toEpochMilli(),
        intendedForParent = row.intendedForParent,
        createdAt = row.createdAt.toEpochMilli(),
    )
}
