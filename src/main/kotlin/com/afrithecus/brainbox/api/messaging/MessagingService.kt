package com.afrithecus.brainbox.api.messaging

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.domain.GradeNormalizer
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.messaging.entity.MessageEntity
import com.afrithecus.brainbox.api.messaging.model.AudienceType
import com.afrithecus.brainbox.api.messaging.model.Folder
import com.afrithecus.brainbox.api.messaging.repository.MessageRepository
import com.afrithecus.brainbox.api.messaging.web.AttachmentPayload
import com.afrithecus.brainbox.api.messaging.web.MessagePayload
import com.afrithecus.brainbox.api.messaging.web.MessageRecipientPayload
import com.afrithecus.brainbox.api.messaging.web.SchoolMemberPayload
import com.afrithecus.brainbox.api.messaging.web.SendMessageRequest
import com.afrithecus.brainbox.api.messaging.web.TeacherSendMessageRequest
import com.afrithecus.brainbox.api.notification.NotificationService
import com.afrithecus.brainbox.api.notification.model.NotificationPriority
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

/**
 * Messaging core (doc 05 section 2): server rows per delivery, unified inbox, direct
 * sends with school-staff authorization and teacher fan-out to owned classes.
 * Message ids are client-provided (msg_<ts>_<user>) and replays are no-ops.
 *
 * Findings2 NEW Message compose changes:
 *  - Eligibility is enforced here, not in the picker: a teacher may message other
 *    teachers/admins and the learners of the classes they own; a coordinator also their
 *    grade; the school ICT admin everyone in the school; a learner the staff of their school.
 *    recipients() exposes that same rule as a directory so the client can only offer what
 *    the server would accept.
 *  - Conversations: a reply records the root conversation (MessageEntity.threadRootGroup)
 *    so the client groups an inbox into one row per conversation and opens the whole history.
 *  - Receipts: MessagePayload.status is derived from the recipient's inbox copy, so an
 *    outgoing message is SENT -> DELIVERED -> READ instead of carrying a client guess.
 *  - Notifications: every received message writes a MESSAGE notification with a deep link
 *    to its conversation, so the notifications feed (and push) covers messages end-to-end.
 */
@Service
class MessagingService(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val notificationService: NotificationService,
    private val schoolRepository: com.afrithecus.brainbox.api.identity.repository.SchoolRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    private companion object {
        val MESSAGE_CATEGORIES = setOf("GENERAL", "ACADEMIC", "ADMIN", "SUPPORT", "COUNSELING")
        const val SCOPE_OTHER_SCHOOL_ADMINS = "OTHER_SCHOOL_ADMINS"
    }


    private data class EligibilityScope(
        val schoolWide: Boolean,
        val coordinator: Boolean,
        val coordinatorGrades: Set<String>,
        val ownedClassIds: Set<UUID>,
        val ownedLearnerIds: Set<UUID>,
        val childrenByParent: Map<UUID, List<UUID>>,
    )

    // ------------------------------------------------------------ direct send

    @Transactional
    fun send(sender: UserEntity, request: SendMessageRequest, clientMessageId: String?): MessagePayload {
        val recipient = resolveUser(request.recipientId, "recipientId")
        assertCanMessage(sender, recipient)
        val msgGroup = clientMessageId ?: generateMessageId(sender.id)
        replay(msgGroup, sender.id)?.let { return it }
        val threadId = resolveThreadId(msgGroup, request.parentMessageId)
        val category = normalizeCategory(request.category)
        val sent = saveRow(msgGroup, sender.id, recipient.id, request.subject, request.body, request.attachments, Folder.sent, threadId, request.parentMessageId, category = category, priority = request.priority)
        saveRow(msgGroup, sender.id, recipient.id, request.subject, request.body, request.attachments, Folder.inbox, threadId, request.parentMessageId, category = category, priority = request.priority)
        notifyRecipients(sender, listOf(recipient), threadId, sent.id, request.subject, request.body)
        return toPayload(sent, sender, recipient, statusOf(sent, emptyMap()))
    }

    // ---------------------------------------------------------- teacher send

    @Transactional
    fun teacherSend(teacher: UserEntity, request: TeacherSendMessageRequest, clientMessageId: String?): MessagePayload {
        val audience = runCatching { AudienceType.valueOf(request.audienceType.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("audienceType must be INDIVIDUAL, CLASS or CLASS_PARENTS")
        val msgGroup = clientMessageId ?: generateMessageId(teacher.id)
        replay(msgGroup, teacher.id)?.let { return it }
        val threadId = resolveThreadId(msgGroup, request.parentMessageId)
        val category = normalizeCategory(request.category)
        val priority = request.priority

        when (audience) {
            AudienceType.INDIVIDUAL -> {
                val recipient = request.recipientId?.let { resolveUser(it, "recipientId") }
                    ?: throw invalidArgument("recipientId is required for INDIVIDUAL")
                assertCanMessage(teacher, recipient)
                val sent = saveRow(msgGroup, teacher.id, recipient.id, request.subject, request.body, request.attachments, Folder.sent, threadId, request.parentMessageId, category = category, priority = priority)
                saveRow(msgGroup, teacher.id, recipient.id, request.subject, request.body, request.attachments, Folder.inbox, threadId, request.parentMessageId, category = category, priority = priority)
                notifyRecipients(teacher, listOf(recipient), threadId, sent.id, request.subject, request.body)
                return toPayload(sent, teacher, recipient, statusOf(sent, emptyMap()))
            }
            AudienceType.CLASS -> {
                val students = rosterOfOwnedClass(teacher, request.audienceId)
                val sent = saveRow(msgGroup, teacher.id, null, request.subject, request.body, request.attachments, Folder.sent, threadId, request.parentMessageId, category = category, priority = priority)
                students.forEach { student ->
                    saveRow(msgGroup, teacher.id, student.id, request.subject, request.body, request.attachments, Folder.inbox, threadId, request.parentMessageId, category = category, priority = priority)
                }
                notifyRecipients(teacher, students, threadId, sent.id, request.subject, request.body)
                return toPayload(sent, teacher, null, statusOf(sent, emptyMap()))
            }
            AudienceType.CLASS_PARENTS -> {
                val students = rosterOfOwnedClass(teacher, request.audienceId)
                val sent = saveRow(msgGroup, teacher.id, null, request.subject, request.body, request.attachments, Folder.sent, threadId, request.parentMessageId, category = category, priority = priority)
                students.forEach { student ->
                    saveRow(msgGroup, teacher.id, student.id, request.subject, request.body, request.attachments, Folder.inbox, threadId, request.parentMessageId, intendedForParent = true, category = category, priority = priority)
                }
                notifyRecipients(teacher, students, threadId, sent.id, request.subject, request.body)
                return toPayload(sent, teacher, null, statusOf(sent, emptyMap()))
            }
        }
    }

    // ---------------------------------------------------------------- reads

    private fun platformRoleOf(senderId: UUID, sender: UserEntity?): String? =
        if (senderId == PlatformSender.USER_ID) "SYSTEM" else sender?.role?.name

    @Transactional(readOnly = true)
    fun list(userId: UUID, folder: String): List<MessagePayload> {
        val f = parseFolder(folder)
        val rows = when (f) {
            Folder.inbox -> messageRepository.findAllByRecipientIdAndFolderOrderByCreatedAtDesc(userId, Folder.inbox)
            else -> messageRepository.findAllBySenderIdAndFolderOrderByCreatedAtDesc(userId, f)
        }
        if (rows.isEmpty()) return emptyList()
        val parties = userRepository.findAllById(
            rows.flatMap { listOfNotNull(it.senderId, it.recipientId) }.distinct(),
        ).associateBy { it.id }
        val receipts = receiptsFor(rows)
        return rows.map { row ->
            toPayload(row, parties[row.senderId], row.recipientId?.let { parties[it] }, statusOf(row, receipts))
        }
    }

    @Transactional(readOnly = true)
    fun thread(viewer: UserEntity, threadIdRaw: String): List<MessagePayload> {
        val threadId = threadIdRaw.trim().takeIf { it.isNotEmpty() }
            ?: throw invalidArgument("threadId is required")
        val rows = messageRepository.findThread(threadId, viewer.id, Folder.sent, Folder.inbox)
        if (rows.isEmpty()) return emptyList()
        val parties = userRepository.findAllById(
            rows.flatMap { listOfNotNull(it.senderId, it.recipientId) }.distinct(),
        ).associateBy { it.id }
        val receipts = receiptsFor(rows)
        return rows.map { row ->
            toPayload(row, parties[row.senderId], row.recipientId?.let { parties[it] }, statusOf(row, receipts))
        }
    }

    @Transactional(readOnly = true)
    fun platformInbox(limit: Int = 50): List<MessagePayload> {
        val rows = messageRepository
            .findAllByRecipientIdAndFolderOrderByCreatedAtDesc(PlatformSender.USER_ID, Folder.inbox)
            .take(limit.coerceIn(1, 200))
        val parties = userRepository.findAllById(
            rows.flatMap { listOfNotNull(it.senderId, it.recipientId) }.distinct(),
        ).associateBy { it.id }
        return rows.map { row -> toPayload(row, parties[row.senderId], row.recipientId?.let { parties[it] }, "DELIVERED") }
    }

    @Transactional
    fun markPlatformInboxRead(messageIdRaw: String): Boolean {
        val id = runCatching { UUID.fromString(messageIdRaw) }.getOrNull()
            ?: throw invalidArgument("message id is not a valid identifier")
        val message = messageRepository.findById(id).orElse(null) ?: return false
        if (message.folder != Folder.inbox || message.recipientId != PlatformSender.USER_ID) return false
        if (!message.isRead) {
            message.isRead = true
            message.readAt = clock.instant()
            messageRepository.save(message)
        }
        return true
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

    /** Archive or restore one copy the caller owns (sender's SENT copy or recipient's INBOX copy). */
    @Transactional
    fun setArchived(user: UserEntity, messageIdRaw: String, archived: Boolean) {
        val message = ownCopy(user, messageIdRaw)
        if (message.isArchived != archived) {
            message.isArchived = archived
            messageRepository.save(message)
        }
    }

    /** Flag or unflag one copy the caller owns. */
    @Transactional
    fun setFlagged(user: UserEntity, messageIdRaw: String, flagged: Boolean) {
        val message = ownCopy(user, messageIdRaw)
        if (message.isFlagged != flagged) {
            message.isFlagged = flagged
            messageRepository.save(message)
        }
    }

    /**
     * A message has two rows: the sender's SENT copy and the recipient's INBOX copy. State
     * endpoints may only touch a row the caller actually owns, so archiving a conversation on
     * one side can never hide it for the other participant.
     */
    private fun ownCopy(user: UserEntity, messageIdRaw: String): MessageEntity {
        val id = runCatching { UUID.fromString(messageIdRaw) }.getOrNull()
            ?: throw invalidArgument("message id is not a valid identifier")
        val message = messageRepository.findById(id).orElse(null) ?: throw notFound("Message not found")
        // Ownership is per COPY, not per participant: the sender owns the SENT row and
        // the recipient owns the INBOX row. Checking either id let a recipient archive
        // the sender's copy (and vice versa), hiding the conversation for the other side.
        val owns = when (message.folder) {
            Folder.sent -> message.senderId == user.id
            Folder.inbox -> message.recipientId == user.id
            Folder.outbox -> message.senderId == user.id
        }
        if (!owns) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your message")
        }
        return message
    }

    // ------------------------------------------------- recipient directory

    @Transactional(readOnly = true)
    fun recipients(
        viewer: UserEntity,
        roleRaw: String?,
        gradeRaw: String?,
        classIdRaw: String?,
        queryRaw: String?,
        scopeRaw: String? = null,
    ): List<MessageRecipientPayload> {
        if (scopeRaw?.trim()?.uppercase() == SCOPE_OTHER_SCHOOL_ADMINS) {
            return otherSchoolAdmins(viewer, queryRaw)
        }
        val schoolId = viewer.schoolId ?: return emptyList()
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val memberships = if (classes.isEmpty()) emptyList() else membershipRepository.findAllByClassIdIn(classes.map { it.id })
        val classById = classes.associateBy { it.id }
        val classesByUser = memberships.groupBy { it.studentId }
        val scope = eligibilityScope(viewer, classes, memberships)
        val requestedRole = roleRaw?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        if (requestedRole != null) {
            runCatching { Role.valueOf(requestedRole) }.getOrNull()
                ?: throw invalidArgument("role must be TEACHER, STUDENT or PARENT")
        }
        val askedGrade = gradeRaw?.trim()?.takeIf { it.isNotEmpty() }
        val askedClass = classIdRaw?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw invalidArgument("classId is not a valid identifier") }
        val needle = queryRaw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        return userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .filter { it.id != viewer.id }
            .filter { candidate -> isEligible(viewer, candidate, scope) }
            .filter { candidate -> requestedRole == null || candidate.role.name == requestedRole }
            .mapNotNull { candidate ->
                val userClasses = classesOf(candidate, classes, classById, classesByUser)
                val grade = candidate.gradeLevel?.takeIf { it.isNotBlank() } ?: userClasses.firstOrNull()?.gradeLevel
                val classIds = userClasses.map { it.id }
                if (askedGrade != null && userClasses.none { GradeNormalizer.sameGrade(it.gradeLevel, askedGrade) } &&
                    !GradeNormalizer.sameGrade(grade, askedGrade)
                ) {
                    return@mapNotNull null
                }
                if (askedClass != null && classIds.none { it == askedClass }) return@mapNotNull null
                if (needle != null && !candidate.name.lowercase().contains(needle) &&
                    !candidate.phoneNumber.orEmpty().contains(needle)
                ) {
                    return@mapNotNull null
                }
                MessageRecipientPayload(
                    id = candidate.id.toString(),
                    name = candidate.name,
                    role = candidate.role.name,
                    subRole = candidate.subRole?.name,
                    gradeLevel = grade,
                    classIds = classIds.map { it.toString() },
                    classNames = userClasses.map { it.name },
                    relationship = relationshipOf(candidate, scope),
                    isGuardianAccount = candidate.role == Role.STUDENT,
                )
            }
            .sortedWith(compareBy<MessageRecipientPayload>({ roleRank(it.role) }, { it.name.lowercase() }))
    }

    /**
     * The admin-only cross-school directory: active ICT admins of every OTHER school.
     * "Admins only" is enforced on both the read and the send, so a teacher can never
     * discover or message a peer through this path.
     */
    private fun otherSchoolAdmins(viewer: UserEntity, queryRaw: String?): List<MessageRecipientPayload> {
        if (!isAdmin(viewer)) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only school administrators can message other schools' admins")
        }
        val needle = queryRaw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val rows = userRepository.findAllByRoleAndSubRoleAndIsActiveTrue(Role.TEACHER, SubRole.ICT_ADMIN)
            .filter { it.id != viewer.id && it.schoolId != null && it.schoolId != viewer.schoolId }
        val schools = if (rows.isEmpty()) emptyMap() else
            schoolRepository.findAllById(rows.mapNotNull { it.schoolId }).associateBy { it.id }
        return rows.mapNotNull { candidate ->
            val schoolName = candidate.schoolId?.let { schools[it]?.name }
            if (needle != null && !candidate.name.lowercase().contains(needle) &&
                !schoolName.orEmpty().lowercase().contains(needle) &&
                !candidate.phoneNumber.orEmpty().contains(needle)
            ) {
                return@mapNotNull null
            }
            MessageRecipientPayload(
                id = candidate.id.toString(),
                name = candidate.name,
                role = candidate.role.name,
                subRole = candidate.subRole?.name,
                gradeLevel = null,
                classIds = emptyList(),
                classNames = emptyList(),
                relationship = "Admin, other school",
                schoolName = schoolName,
            )
        }.sortedBy { it.schoolName.orEmpty().lowercase() + it.name.lowercase() }
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
                    subRole = it.subRole?.name,
                )
            }
    }

    // ------------------------------------------------------------ eligibility

    private fun eligibilityScope(
        viewer: UserEntity,
        classes: List<com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity>,
        memberships: List<com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity>,
    ): EligibilityScope {
        val schoolWide = isSchoolWide(viewer)
        val coordinator = viewer.role == Role.TEACHER && viewer.subRole == SubRole.GRADE_COORDINATOR
        val ownedClassIds = if (schoolWide) {
            classes.map { it.id }.toSet()
        } else {
            classes.filter { it.teacherUserId == viewer.id }.map { it.id }.toSet()
        }
        val ownedLearnerIds = memberships.filter { it.classId in ownedClassIds }.map { it.studentId }.toSet()
        val coordinatorGrades = LinkedHashSet<String>()
        if (coordinator) {
            viewer.gradeLevel?.trim()?.takeIf { it.isNotEmpty() }?.let { coordinatorGrades += it }
            classes.filter { it.id in ownedClassIds || it.teacherUserId == viewer.id }
                .map { it.gradeLevel }.forEach { coordinatorGrades += it }
        }
        val childrenByParent = if (viewer.role == Role.TEACHER && !schoolWide && viewer.schoolId != null) {
            userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(viewer.schoolId!!)
                .filter { it.role == Role.STUDENT && it.parentUserId != null }
                .groupBy { it.parentUserId!! }
                .mapValues { (_, rows) -> rows.map { it.id } }
        } else {
            emptyMap()
        }
        return EligibilityScope(schoolWide, coordinator, coordinatorGrades, ownedClassIds, ownedLearnerIds, childrenByParent)
    }

    private fun isEligible(viewer: UserEntity, candidate: UserEntity, scope: EligibilityScope): Boolean {
        if (candidate.id == PlatformSender.USER_ID) return true
        if (viewer.role != Role.TEACHER && viewer.role != Role.ADMIN) {
            return candidate.role == Role.TEACHER
        }
        if (scope.schoolWide) return true
        return when (candidate.role) {
            Role.TEACHER -> true
            Role.STUDENT -> candidate.id in scope.ownedLearnerIds ||
                (scope.coordinator && coordinatorCovers(scope, candidate))
            Role.PARENT -> (scope.childrenByParent[candidate.id] ?: emptyList()).any { it in scope.ownedLearnerIds }
            else -> false
        }
    }

    private fun coordinatorCovers(scope: EligibilityScope, learner: UserEntity): Boolean =
        scope.coordinatorGrades.any { GradeNormalizer.sameGrade(it, learner.gradeLevel) }

    private fun assertCanMessage(sender: UserEntity, recipient: UserEntity) {
        if (recipient.id == PlatformSender.USER_ID) return
        if (sender.role == Role.ADMIN) return
        // School administrators may reach each other across schools (findings messages #3).
        // Everyone else keeps the same-school rule below.
        if (isAdmin(sender) && isAdmin(recipient)) return
        if (sender.schoolId == null || sender.schoolId != recipient.schoolId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Recipient is not in your school")
        }
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(sender.schoolId!!)
        val memberships = if (classes.isEmpty()) emptyList() else membershipRepository.findAllByClassIdIn(classes.map { it.id })
        val scope = eligibilityScope(sender, classes, memberships)
        if (!isEligible(sender, recipient, scope)) {
            throw ApiException(
                ApiErrorCode.FORBIDDEN,
                "You can only message teachers, administrators and learners in your own classes",
            )
        }
    }

    private fun isSchoolWide(user: UserEntity): Boolean = isAdmin(user)

    private fun isAdmin(user: UserEntity): Boolean =
        user.role == Role.ADMIN || (user.role == Role.TEACHER && user.subRole == SubRole.ICT_ADMIN)

    private fun classesOf(
        user: UserEntity,
        classes: List<com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity>,
        classById: Map<UUID, com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity>,
        classesByUser: Map<UUID, List<com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity>>,
    ): List<com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity> = when (user.role) {
        Role.STUDENT -> classesByUser[user.id].orEmpty().mapNotNull { classById[it.classId] }
        Role.TEACHER -> classes.filter { it.teacherUserId == user.id }
        else -> emptyList()
    }

    private fun relationshipOf(candidate: UserEntity, scope: EligibilityScope): String = when (candidate.role) {
        Role.TEACHER -> when {
            candidate.subRole == SubRole.ICT_ADMIN -> "School admin"
            candidate.subRole == SubRole.GRADE_COORDINATOR -> "Coordinator"
            else -> "Teacher"
        }
        Role.STUDENT -> when {
            // School-wide staff own every class by scope, so "Your class" would be misleading
            // on their directory; only a class owner/coordinator gets the relationship copy.
            scope.schoolWide -> "Learner"
            candidate.id in scope.ownedLearnerIds -> "Your class"
            scope.coordinator -> "Your grade"
            else -> "Learner"
        }
        Role.PARENT -> "Guardian"
        else -> candidate.role.name.lowercase()
    }

    private fun roleRank(role: String): Int = when (role) {
        "TEACHER" -> 0
        "STUDENT" -> 1
        "PARENT" -> 2
        else -> 3
    }

    // ------------------------------------------------------------ notifications

    private fun notifyRecipients(
        sender: UserEntity,
        recipients: List<UserEntity>,
        threadId: String,
        messageId: UUID,
        subject: String?,
        body: String,
    ) {
        if (recipients.isEmpty()) return
        val title = "New message from " + sender.name
        val preview = subject?.trim()?.takeIf { it.isNotEmpty() }
            ?: body.trim().let { if (it.length > 140) it.take(140) + "..." else it }
        recipients.map { it.id }.distinct().forEach { recipientId ->
            notificationService.notifyUser(
                userId = recipientId,
                title = title,
                message = preview,
                type = NotificationType.MESSAGE,
                urgency = NotificationUrgency.NORMAL,
                priority = NotificationPriority.NORMAL,
                // Android registers the route as message_thread/{threadId}; the web app
                // normalises this to its own hyphenated route. Emitting the web spelling
                // here made the Android notification action an unregistered route.
                actionRoute = "message_thread/" + threadId,
                actionLabel = "Open conversation",
                metadata = mapOf(
                    "threadId" to threadId,
                    "messageId" to messageId.toString(),
                    "senderId" to sender.id.toString(),
                ),
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

    private fun resolveThreadId(msgGroup: String, parentMessageRaw: String?): String {
        val parent = parentMessageRaw?.trim()?.takeIf { it.isNotEmpty() } ?: return msgGroup
        val parentRow = runCatching { UUID.fromString(parent) }.getOrNull()
            ?.let { messageRepository.findById(it).orElse(null) }
            ?: return parent
        return parentRow.threadRootGroup ?: parentRow.msgGroup
    }

    private fun replay(msgGroup: String, senderId: UUID): MessagePayload? {
        if (!messageRepository.existsByMsgGroupAndSenderId(msgGroup, senderId)) return null
        val row = messageRepository.findFirstByMsgGroupAndSenderIdOrderByCreatedAtAsc(msgGroup, senderId)
            ?: throw ApiException(ApiErrorCode.CONFLICT, "Message id replay")
        return toPayload(row)
    }

    private fun receiptsFor(rows: List<MessageEntity>): Map<Pair<String, UUID?>, Boolean> {
        val groups = rows.map { it.msgGroup }.distinct()
        if (groups.isEmpty()) return emptyMap()
        return messageRepository.findAllByMsgGroupInAndFolder(groups, Folder.inbox)
            .associateBy({ it.msgGroup to it.recipientId }, { it.isRead })
    }

    private fun statusOf(row: MessageEntity, receipts: Map<Pair<String, UUID?>, Boolean>): String {
        if (row.folder == Folder.inbox) return if (row.isRead) "READ" else "DELIVERED"
        val read = receipts[row.msgGroup to row.recipientId]
        return when (read) {
            true -> "READ"
            false -> "DELIVERED"
            null -> "SENT"
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
        threadRootGroup: String,
        parentMessageId: String? = null,
        intendedForParent: Boolean = false,
        category: String = "GENERAL",
        priority: Boolean = false,
    ): MessageEntity {
        val row = MessageEntity().apply {
            this.msgGroup = msgGroup
            this.threadRootGroup = threadRootGroup
            this.parentMessageId = parentMessageId?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            this.senderId = senderId
            this.recipientId = recipientId
            this.subject = subject?.trim()?.takeIf { it.isNotEmpty() }
            this.body = body.trim()
            this.attachments = attachments?.let { mapper.writeValueAsString(it) }
            this.folder = folder
            this.intendedForParent = intendedForParent
            this.category = category
            this.isPriority = priority
            createdAt = clock.instant()
        }
        messageRepository.save(row)
        return row
    }

    /** The composer's five categories, coerced so a hostile value can never reach the DB. */
    private fun normalizeCategory(raw: String?): String {
        val value = raw?.trim()?.uppercase() ?: return "GENERAL"
        return if (value in MESSAGE_CATEGORIES) value else "GENERAL"
    }

    private fun parseFolder(raw: String): Folder =
        runCatching { Folder.valueOf(raw.trim().lowercase()) }.getOrNull()
            ?: throw invalidArgument("folder must be inbox, sent or outbox")

    private fun generateMessageId(userId: UUID): String =
        "msg_" + clock.millis() + "_" + userId.toString()

    internal fun toPayload(row: MessageEntity): MessagePayload {
        val sender = userRepository.findById(row.senderId).orElse(null)
        val recipient = row.recipientId?.let { userRepository.findById(it).orElse(null) }
        val receipts = receiptsFor(listOf(row))
        return toPayload(row, sender, recipient, statusOf(row, receipts))
    }

    internal fun toPayload(
        row: MessageEntity,
        sender: UserEntity?,
        recipient: UserEntity?,
        status: String,
    ): MessagePayload = MessagePayload(
        id = row.id.toString(),
        senderId = row.senderId.toString(),
        senderName = sender?.name,
        senderRole = platformRoleOf(row.senderId, sender),
        recipientId = row.recipientId?.toString(),
        recipientName = recipient?.name,
        recipientRole = row.recipientId?.let { platformRoleOf(it, recipient) },
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
        msgGroup = row.msgGroup,
        threadId = row.threadRootGroup ?: row.msgGroup,
        parentMessageId = row.parentMessageId?.toString(),
        status = status,
        isArchived = row.isArchived,
        isFlagged = row.isFlagged,
        category = row.category,
        isPriority = row.isPriority,
    )
}
