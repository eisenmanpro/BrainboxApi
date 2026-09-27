package com.afrithecus.brainbox.api.parent

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.messaging.MessagingService
import com.afrithecus.brainbox.api.messaging.web.SendMessageRequest
import com.afrithecus.brainbox.api.parent.web.ParentMessagePayload
import com.afrithecus.brainbox.api.parent.web.SendParentMessageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The guardian's message thread about a child.
 *
 * It reads the messaging rows that already exist instead of inventing a second inbox: the
 * child's inbox holds the teacher notes flagged `intendedForParent`, and the guardian's own
 * inbox holds the replies. A guardian writing to the school writes to the child's class
 * teacher, which is the person they actually know.
 */
@Service
class ParentMessagesService(
    private val portal: ParentPortalService,
    private val messaging: MessagingService,
    private val memberships: ClassMembershipRepository,
    private val classes: TeacherClassRepository,
    private val users: UserRepository,
) {

    /** Messages about the child, newest first. */
    @Transactional(readOnly = true)
    fun messages(current: CurrentUser, childId: String): List<ParentMessagePayload> {
        val child = portal.requireLinkedChild(current, childId)
        val rows = messaging.list(child.id, "inbox").filter { it.intendedForParent } +
            messaging.list(current.userId, "inbox")
        val senderIds = rows.mapNotNull { runCatching { UUID.fromString(it.senderId) }.getOrNull() }.distinct()
        val senders = users.findAllById(senderIds).associateBy { it.id.toString() }
        return rows
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
            .map { row ->
                ParentMessagePayload(
                    id = row.id,
                    fromTeacher = senders[row.senderId]?.name ?: "School",
                    subject = row.subject.orEmpty(),
                    message = row.body,
                    timestamp = row.createdAt,
                    isRead = row.isRead,
                )
            }
    }

    /** Sends the guardian's message to the child's class teacher. */
    @Transactional
    fun send(current: CurrentUser, request: SendParentMessageRequest): ParentMessagePayload {
        val parent = users.findById(current.userId).orElseThrow { notFound("User not found") }
        val childIdRaw = request.childId.trim().takeIf { it.isNotEmpty() }
            ?: throw invalidArgument("childId is required")
        val body = request.message.trim().takeIf { it.isNotEmpty() }
            ?: throw invalidArgument("message is required")
        val child = portal.requireLinkedChild(current, childIdRaw)

        val teacherId = classTeacherOf(child.id)
            ?: throw notFound("No class teacher to message for this child")
        val teacher = users.findById(teacherId).orElse(null) ?: throw notFound("Teacher not found")

        val sent = messaging.send(
            sender = parent,
            request = SendMessageRequest(
                recipientId = teacher.id.toString(),
                subject = request.subject?.trim()?.takeIf { it.isNotEmpty() } ?: "About " + child.name,
                body = body,
            ),
            clientMessageId = null,
        )
        return ParentMessagePayload(
            id = sent.id,
            fromTeacher = teacher.name,
            subject = sent.subject.orEmpty(),
            message = sent.body,
            timestamp = sent.createdAt,
            isRead = sent.isRead,
        )
    }

    /** The teacher of record for the child's first active class. */
    private fun classTeacherOf(childId: UUID): UUID? =
        memberships.findAllByStudentId(childId)
            .mapNotNull { membership -> classes.findById(membership.classId).orElse(null) }
            .firstOrNull { it.isActive }
            ?.teacherUserId

    private fun UserEntity.name(): String = name
}
