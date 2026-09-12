package com.afrithecus.brainbox.api.classchat

import com.afrithecus.brainbox.api.classchat.entity.ClassGroupEntity
import com.afrithecus.brainbox.api.classchat.entity.ClassGroupMemberEntity
import com.afrithecus.brainbox.api.classchat.entity.ClassGroupMessageEntity
import com.afrithecus.brainbox.api.classchat.entity.ClassGroupPollEntity
import com.afrithecus.brainbox.api.classchat.entity.ClassGroupPollVoteEntity
import com.afrithecus.brainbox.api.classchat.entity.ClassGroupReadEntity
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupMemberRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupMessageRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupPollRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupPollVoteRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupReadRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupRepository
import com.afrithecus.brainbox.api.classchat.web.ClassGroupMessagePayload
import com.afrithecus.brainbox.api.classchat.web.ClassGroupPayload
import com.afrithecus.brainbox.api.classchat.web.GradebookContributionPayload
import com.afrithecus.brainbox.api.classchat.web.GroupTeacherPayload
import com.afrithecus.brainbox.api.classchat.web.MessageAttachmentPayload
import com.afrithecus.brainbox.api.classchat.web.PollOptionPayload
import com.afrithecus.brainbox.api.classchat.web.SendMessageRequest
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.domain.GradeNormalizer
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.homework.repository.HomeworkRepository
import com.afrithecus.brainbox.api.homework.repository.HomeworkSubmissionRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.live.web.LivePollPayload
import com.afrithecus.brainbox.api.media.MediaService
import com.afrithecus.brainbox.api.notification.entity.NotificationEntity
import com.afrithecus.brainbox.api.notification.model.NotificationPriority
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import com.afrithecus.brainbox.api.notification.repository.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Class-group chat (doc 04 §12 + docs/ongoing/api_class_group_chat_changes.md).
 * Teacher, parent and student surfaces share one store; identity and role always
 * come from the token, membership is enforced server-side, sends are idempotent
 * on a client id, and unread is tracked per caller. Realtime transport is Phase 6.
 */
@Service
class ClassChatService(
    private val groupRepository: ClassGroupRepository,
    private val memberRepository: ClassGroupMemberRepository,
    private val messageRepository: ClassGroupMessageRepository,
    private val pollRepository: ClassGroupPollRepository,
    private val pollVoteRepository: ClassGroupPollVoteRepository,
    private val readRepository: ClassGroupReadRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val userRepository: UserRepository,
    private val homeworkRepository: HomeworkRepository,
    private val submissionRepository: HomeworkSubmissionRepository,
    private val notificationRepository: NotificationRepository,
    private val mediaService: MediaService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    // ------------------------------------------------------ teacher surface

    @Transactional(readOnly = true)
    fun groups(current: CurrentUser, teacherIdRaw: String): List<ClassGroupPayload> {
        requireSelf(current, teacherIdRaw)
        return groupRepository.findAllByTeacherIdOrderByUpdatedAtDesc(current.userId)
            .map { groupPayload(it, current.userId) }
    }

    @Transactional
    fun create(current: CurrentUser, teacherIdRaw: String, nameRaw: String, classIdRaw: String, memberIds: List<String>): ClassGroupPayload {
        requireSelf(current, teacherIdRaw)
        val name = nameRaw.trim()
        if (name.isEmpty()) throw invalidArgument("name must not be blank")
        val clazz = ownedClass(current, classIdRaw)
        val teacher = user(current.userId)
        val group = groupRepository.save(ClassGroupEntity().apply {
            classId = clazz.id
            teacherId = teacher.id
            teacherName = teacher.name
            this.name = name
            description = null
        })
        replaceMembers(group, memberIds)
        return groupPayload(group, current.userId)
    }

    @Transactional
    fun update(
        current: CurrentUser,
        groupIdRaw: String,
        nameRaw: String?,
        descriptionRaw: String?,
        isAnnouncementMode: Boolean?,
        memberIds: List<String>?,
    ): ClassGroupPayload {
        val group = ownedGroup(current, groupIdRaw)
        nameRaw?.let {
            val name = it.trim()
            if (name.isEmpty()) throw invalidArgument("name must not be blank")
            group.name = name
        }
        descriptionRaw?.let { group.description = it.trim().takeIf { text -> text.isNotEmpty() } }
        isAnnouncementMode?.let { group.isAnnouncementMode = it }
        if (memberIds != null) replaceMembers(group, memberIds)
        groupRepository.save(group)
        return groupPayload(group, current.userId)
    }

    @Transactional
    fun delete(current: CurrentUser, groupIdRaw: String) {
        groupRepository.delete(ownedGroup(current, groupIdRaw))
    }

    @Transactional
    fun messages(current: CurrentUser, groupIdRaw: String, limitRaw: Int, before: Long?): List<ClassGroupMessagePayload> =
        messagesFor(current, accessibleGroup(current, groupIdRaw), limitRaw, before)

    @Transactional
    fun send(
        current: CurrentUser,
        groupIdRaw: String,
        request: SendMessageRequest,
        replyTo: String?,
        isAnnouncement: Boolean,
        clientMessageId: String?,
    ): ClassGroupMessagePayload {
        val group = when (current.role) {
            Role.PARENT -> parentAccessibleGroup(current, groupIdRaw)
            Role.STUDENT -> studentAccessibleGroup(current, groupIdRaw)
            else -> ownedGroup(current, groupIdRaw)
        }
        return sendFor(current, group, request, replyTo, isAnnouncement, clientMessageId)
    }

    @Transactional
    fun setPinned(current: CurrentUser, groupIdRaw: String, messageIdRaw: String, pinned: Boolean) {
        val group = ownedGroup(current, groupIdRaw)
        val message = ownedMessage(group, messageIdRaw)
        message.isPinned = pinned
        messageRepository.save(message)
    }

    @Transactional
    fun deleteMessage(current: CurrentUser, groupIdRaw: String, messageIdRaw: String) {
        val group = ownedGroup(current, groupIdRaw)
        messageRepository.delete(ownedMessage(group, messageIdRaw))
    }

    @Transactional
    fun muteMember(current: CurrentUser, groupIdRaw: String, memberIdRaw: String, durationMinutes: Int) {
        val group = ownedGroup(current, groupIdRaw)
        val member = memberRepository.findByGroupIdAndMemberId(group.id, parseUuid(memberIdRaw, "member id"))
            ?: throw notFound("Member not found")
        member.mutedUntil = if (durationMinutes <= 0) null else clock.instant().plus(Duration.ofMinutes(durationMinutes.toLong()))
        memberRepository.save(member)
    }

    @Transactional
    fun createPoll(current: CurrentUser, groupIdRaw: String, questionRaw: String, options: List<String>): LivePollPayload {
        val group = ownedGroup(current, groupIdRaw)
        val question = questionRaw.trim()
        if (question.isEmpty()) throw invalidArgument("question must not be blank")
        val cleaned = options.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.size < 2) throw invalidArgument("A poll needs at least two options")
        val poll = pollRepository.save(ClassGroupPollEntity().apply {
            this.groupId = group.id
            createdBy = current.userId
            this.question = question
            this.options = mapper.writeValueAsString(cleaned)
        })
        return pollPayload(poll, current.userId)
    }

    @Transactional(readOnly = true)
    fun teachers(current: CurrentUser, groupIdRaw: String): List<GroupTeacherPayload> {
        val group = ownedGroup(current, groupIdRaw)
        val clazz = classRepository.findById(group.classId).orElse(null) ?: throw notFound("Class not found")
        return listOf(
            GroupTeacherPayload(
                id = clazz.id.toString(),
                name = clazz.name,
                grade = GradeNormalizer.canonicalKey(clazz.gradeLevel) ?: 0,
                section = null,
                subject = clazz.subject,
                teacherId = clazz.teacherUserId.toString(),
                teacherName = group.teacherName,
                schoolId = clazz.schoolId?.toString() ?: "",
                studentCount = membershipRepository.countByClassId(clazz.id).toInt(),
                createdAt = clazz.createdAt.toEpochMilli(),
                isActive = clazz.isActive,
            )
        )
    }

    @Transactional(readOnly = true)
    fun gradebookContributions(current: CurrentUser, groupIdRaw: String): List<GradebookContributionPayload> {
        val group = ownedGroup(current, groupIdRaw)
        return homeworkRepository.findAllByClassIdOrderByDueDateDesc(group.classId).flatMap { homework ->
            submissionRepository.findAllByHomeworkId(homework.id).mapNotNull { submission ->
                val grade = submission.grade ?: return@mapNotNull null
                GradebookContributionPayload(
                    id = "gb_" + submission.id,
                    classId = homework.classId.toString(),
                    teacherId = homework.teacherId.toString(),
                    assessmentId = homework.id,
                    assessmentType = "HOMEWORK",
                    studentId = submission.studentId.toString(),
                    studentName = userRepository.findById(submission.studentId).map { it.name }.orElse("Student"),
                    rawScore = grade,
                    maxScore = 100,
                    percentage = grade,
                    assessmentTitle = homework.title,
                    cbcStrandTag = submission.cbcStrandTag,
                    teacherNote = submission.feedback,
                    gradedAt = (submission.gradedAt ?: submission.updatedAt).toEpochMilli(),
                )
            }
        }
    }

    // ------------------------------------------------------ parent surface

    @Transactional(readOnly = true)
    fun groupsForChild(current: CurrentUser, childIdRaw: String): List<ClassGroupPayload> {
        val child = linkedChild(current, childIdRaw)
        return studentGroups(child.id).map { groupPayload(it, current.userId) }
    }

    @Transactional
    fun parentMessages(current: CurrentUser, groupIdRaw: String, limitRaw: Int, before: Long?): List<ClassGroupMessagePayload> =
        messagesFor(current, parentAccessibleGroup(current, groupIdRaw), limitRaw, before)

    @Transactional
    fun markRead(current: CurrentUser, groupIdRaw: String) {
        val group = accessibleGroup(current, groupIdRaw)
        markReadFor(group, current.userId)
    }

    @Transactional
    fun votePoll(current: CurrentUser, pollIdRaw: String, optionIndex: Int) {
        val poll = pollRepository.findById(parseUuid(pollIdRaw, "poll id")).orElse(null) ?: throw notFound("Poll not found")
        accessibleGroup(current, poll.groupId.toString())
        castVote(poll, current.userId, optionIndex)
    }

    // ------------------------------------------------------ student surface

    @Transactional(readOnly = true)
    fun groupsForStudent(current: CurrentUser): List<ClassGroupPayload> =
        studentGroups(current.userId).map { groupPayload(it, current.userId) }

    @Transactional
    fun studentMessages(current: CurrentUser, groupIdRaw: String, limitRaw: Int, before: Long?): List<ClassGroupMessagePayload> =
        messagesFor(current, studentAccessibleGroup(current, groupIdRaw), limitRaw, before)

    // ------------------------------------------------------ attachments

    fun attachment(file: MultipartFile): MessageAttachmentPayload {
        val stored = mediaService.store(file, allowDocuments = true)
        return MessageAttachmentPayload(
            url = stored.url,
            type = attachmentType(file.contentType ?: "", stored.mediaType),
            fileName = file.originalFilename,
            fileSize = file.size,
        )
    }

    // ------------------------------------------------------------ internals

    private fun messagesFor(current: CurrentUser, group: ClassGroupEntity, limitRaw: Int, before: Long?): List<ClassGroupMessagePayload> {
        val limit = limitRaw.coerceIn(1, 100)
        val rows = messageRepository.findAllByGroupIdOrderByCreatedAtDesc(group.id)
            .filter { before == null || it.createdAt.toEpochMilli() < before }
            .take(limit)
        markReadFor(group, current.userId)
        return rows.map(::messagePayload)
    }

    private fun sendFor(
        current: CurrentUser,
        group: ClassGroupEntity,
        request: SendMessageRequest,
        replyTo: String?,
        isAnnouncement: Boolean,
        clientMessageId: String?,
    ): ClassGroupMessagePayload {
        val sender = user(current.userId)
        if (group.isAnnouncementMode && sender.role != Role.TEACHER && sender.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "This group is announcements-only")
        }
        memberRepository.findByGroupIdAndMemberId(group.id, sender.id)?.let { member ->
            if (member.mutedUntil?.isAfter(clock.instant()) == true) {
                throw ApiException(ApiErrorCode.FORBIDDEN, "You are muted in this group")
            }
        }
        val stableId = clientMessageId?.trim()?.takeIf { it.isNotEmpty() }
        if (stableId != null) {
            messageRepository.findByGroupIdAndClientMessageId(group.id, stableId)?.let { return messagePayload(it) }
        }
        val text = request.text.trim()
        val attachments = request.attachments.orEmpty()
        if (text.isEmpty() && attachments.isEmpty()) throw invalidArgument("A message needs text or an attachment")
        val replyToId = replyTo?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "replyTo") }
        if (replyToId != null) {
            val parent = messageRepository.findById(replyToId).orElse(null)
            if (parent == null || parent.groupId != group.id) throw notFound("Reply target not found")
        }
        val saved = messageRepository.save(ClassGroupMessageEntity().apply {
            this.groupId = group.id
            senderId = sender.id
            senderName = sender.name
            senderRole = apiRole(sender)
            this.text = text
            this.replyToId = replyToId
            this.isAnnouncement = isAnnouncement
            this.attachments = mapper.writeValueAsString(attachments)
            this.clientMessageId = stableId
        })
        group.updatedAt = clock.instant()
        groupRepository.save(group)
        markReadFor(group, sender.id)
        fanOut(group, saved, sender)
        return messagePayload(saved)
    }

    private fun fanOut(group: ClassGroupEntity, message: ClassGroupMessageEntity, sender: UserEntity) {
        val recipients = linkedSetOf<UUID>()
        recipients += group.teacherId
        memberRepository.findAllByGroupIdOrderByMemberNameAsc(group.id).forEach { recipients += it.memberId }
        membershipRepository.findAllByClassId(group.classId).forEach { recipients += it.studentId }
        recipients.toList().forEach { studentId ->
            userRepository.findByParentUserId(studentId).forEach { recipients += it.id }
        }
        recipients.remove(sender.id)
        if (recipients.isEmpty()) return
        val route = "class_group_chat/" + group.id + "/" + URLEncoder.encode(group.name, StandardCharsets.UTF_8)
        val body = sender.name + ": " + (message.text.takeIf { it.isNotBlank() } ?: "Attachment")
        recipients.forEach { recipientId ->
            notificationRepository.save(NotificationEntity().apply {
                userId = recipientId
                title = group.name
                this.message = body
                type = NotificationType.MESSAGE
                urgency = NotificationUrgency.NORMAL
                priority = NotificationPriority.NORMAL
                actionRoute = route
                actionLabel = "Open chat"
                metadata = mapper.writeValueAsString(mapOf("groupId" to group.id.toString(), "messageId" to message.id.toString()))
                dedupeKey = "chat:" + message.id
            })
        }
    }

    private fun markReadFor(group: ClassGroupEntity, userId: UUID) {
        val row = readRepository.findByGroupIdAndUserId(group.id, userId)
        if (row == null) {
            readRepository.save(ClassGroupReadEntity().apply {
                groupId = group.id
                this.userId = userId
                lastReadAt = clock.instant()
            })
        } else {
            row.lastReadAt = clock.instant()
            readRepository.save(row)
        }
    }

    private fun castVote(poll: ClassGroupPollEntity, userId: UUID, optionIndex: Int) {
        val options = parseStringList(poll.options)
        if (optionIndex < 0 || optionIndex >= options.size) throw invalidArgument("optionIndex is out of range")
        val existing = pollVoteRepository.findByPollIdAndUserId(poll.id, userId)
        if (existing == null) {
            pollVoteRepository.save(ClassGroupPollVoteEntity().apply {
                pollId = poll.id
                this.userId = userId
                this.optionIndex = optionIndex
            })
        } else {
            existing.optionIndex = optionIndex
            existing.votedAt = clock.instant()
            pollVoteRepository.save(existing)
        }
    }

    private fun groupPayload(group: ClassGroupEntity, viewerId: UUID): ClassGroupPayload {
        val last = messageRepository.findFirstByGroupIdOrderByCreatedAtDesc(group.id)
        val since = readRepository.findByGroupIdAndUserId(group.id, viewerId)?.lastReadAt ?: Instant.EPOCH
        val unread = messageRepository.countByGroupIdAndCreatedAtAfterAndSenderIdNot(group.id, since, viewerId)
        val muted = if (viewerId == group.teacherId) {
            group.teacherMutedUntil?.isAfter(clock.instant()) == true
        } else {
            memberRepository.findByGroupIdAndMemberId(group.id, viewerId)?.mutedUntil?.isAfter(clock.instant()) == true
        }
        return ClassGroupPayload(
            id = group.id.toString(),
            name = group.name,
            memberCount = memberRepository.countByGroupId(group.id).toInt(),
            teacherName = group.teacherName,
            classId = group.classId.toString(),
            description = group.description,
            unreadCount = unread.toInt(),
            isMuted = muted,
            isAnnouncementMode = group.isAnnouncementMode,
            lastMessage = last?.text?.takeIf { it.isNotBlank() } ?: last?.attachments?.let { "Attachment" },
            lastMessageTime = last?.createdAt?.toEpochMilli(),
        )
    }

    private fun messagePayload(message: ClassGroupMessageEntity): ClassGroupMessagePayload =
        ClassGroupMessagePayload(
            id = message.id.toString(),
            groupId = message.groupId.toString(),
            senderId = message.senderId.toString(),
            senderName = message.senderName,
            senderRole = message.senderRole,
            text = message.text,
            timestamp = message.createdAt.toEpochMilli(),
            isPinned = message.isPinned,
            attachments = parseAttachments(message.attachments),
            replyToId = message.replyToId?.toString(),
            isAnnouncement = message.isAnnouncement,
        )

    private fun pollPayload(poll: ClassGroupPollEntity, viewerId: UUID): LivePollPayload {
        val options = parseStringList(poll.options)
        val votes = pollVoteRepository.findAllByPollId(poll.id)
        val counts = votes.groupingBy { it.optionIndex }.eachCount()
        val myVote = votes.firstOrNull { it.userId == viewerId }?.optionIndex
        val voteMap = LinkedHashMap<Int, Int>()
        options.indices.forEach { voteMap[it] = counts[it] ?: 0 }
        val resultMap = LinkedHashMap<String, Int>()
        options.forEachIndexed { index, option -> resultMap[option] = counts[index] ?: 0 }
        return LivePollPayload(
            id = poll.id.toString(),
            classId = poll.groupId.toString(),
            question = poll.question,
            options = options,
            votes = voteMap,
            results = resultMap,
            isActive = poll.isActive,
            createdAt = poll.createdAt.toEpochMilli(),
        )
    }

    private fun parseAttachments(json: String?): List<MessageAttachmentPayload> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).mapNotNull { index ->
            val item = node.get(index)
            val url = item.get("url")?.asString() ?: return@mapNotNull null
            val pollOptions = item.get("pollOptions")?.takeIf { it.isArray }?.let { array ->
                (0 until array.size()).map { optionIndex ->
                    val option = array.get(optionIndex)
                    PollOptionPayload(
                        id = option.get("id")?.asString() ?: optionIndex.toString(),
                        text = option.get("text")?.asString() ?: "",
                        votes = option.get("votes")?.intValue() ?: 0,
                        isSelectedByMe = option.get("isSelectedByMe")?.asBoolean() ?: false,
                    )
                }
            }
            MessageAttachmentPayload(
                url = url,
                type = item.get("type")?.asString() ?: "FILE",
                fileName = item.get("fileName")?.asString(),
                fileSize = item.get("fileSize")?.longValue(),
                durationMs = item.get("durationMs")?.longValue(),
                pollOptions = pollOptions,
            )
        }
    }

    private fun replaceMembers(group: ClassGroupEntity, memberIds: List<String>) {
        memberRepository.deleteAllByGroupId(group.id)
        memberIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { raw ->
            val member = user(parseUuid(raw, "member id"))
            memberRepository.save(ClassGroupMemberEntity().apply {
                groupId = group.id
                memberId = member.id
                memberName = member.name
                memberRole = apiRole(member)
            })
        }
    }

    /** Groups a student belongs to: their classes' groups plus explicit memberships. */
    private fun studentGroups(studentId: UUID): List<ClassGroupEntity> {
        val classIds = membershipRepository.findAllByStudentId(studentId).map { it.classId }.toSet()
        val explicitGroupIds = memberRepository.findAllByMemberId(studentId).map { it.groupId }.toSet()
        val byClass = classIds.flatMap { groupRepository.findAllByClassId(it) }
        val byMembership = explicitGroupIds.mapNotNull { groupRepository.findById(it).orElse(null) }
        return (byClass + byMembership).distinctBy { it.id }
    }

    private fun studentAccessibleGroup(current: CurrentUser, groupIdRaw: String): ClassGroupEntity {
        val group = groupRepository.findById(parseUuid(groupIdRaw, "group id")).orElse(null)
            ?: throw notFound("Class group not found")
        if (!studentCanAccess(current.userId, group)) throw ApiException(ApiErrorCode.FORBIDDEN, "Not a member of this group")
        return group
    }

    private fun parentAccessibleGroup(current: CurrentUser, groupIdRaw: String): ClassGroupEntity {
        val group = groupRepository.findById(parseUuid(groupIdRaw, "group id")).orElse(null)
            ?: throw notFound("Class group not found")
        val allowed = userRepository.findByParentUserId(current.userId).any { studentCanAccess(it.id, group) }
        if (!allowed) throw ApiException(ApiErrorCode.FORBIDDEN, "Not a member of this group")
        return group
    }

    private fun studentCanAccess(studentId: UUID, group: ClassGroupEntity): Boolean {
        if (memberRepository.findByGroupIdAndMemberId(group.id, studentId) != null) return true
        return membershipRepository.findAllByStudentId(studentId).any { it.classId == group.classId }
    }

    private fun accessibleGroup(current: CurrentUser, groupIdRaw: String): ClassGroupEntity = when (current.role) {
        Role.PARENT -> parentAccessibleGroup(current, groupIdRaw)
        Role.STUDENT -> studentAccessibleGroup(current, groupIdRaw)
        else -> ownedGroup(current, groupIdRaw)
    }

    private fun linkedChild(current: CurrentUser, childIdRaw: String): UserEntity {
        val child = user(parseUuid(childIdRaw, "child id"))
        if (current.role != Role.ADMIN && child.parentUserId != current.userId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your linked child")
        }
        return child
    }

    private fun ownedMessage(group: ClassGroupEntity, messageIdRaw: String): ClassGroupMessageEntity {
        val message = messageRepository.findById(parseUuid(messageIdRaw, "message id")).orElse(null)
            ?: throw notFound("Message not found")
        if (message.groupId != group.id) throw notFound("Message not found")
        return message
    }

    private fun ownedGroup(current: CurrentUser, groupIdRaw: String): ClassGroupEntity {
        val group = groupRepository.findById(parseUuid(groupIdRaw, "group id")).orElse(null)
            ?: throw notFound("Class group not found")
        val user = user(current.userId)
        if (group.teacherId != current.userId && user.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your class group")
        }
        return group
    }

    private fun ownedClass(current: CurrentUser, classIdRaw: String) = run {
        val clazz = classRepository.findById(parseUuid(classIdRaw, "class id")).orElse(null)
            ?: throw notFound("Class not found")
        val user = user(current.userId)
        if (clazz.teacherUserId != current.userId && user.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your class")
        }
        clazz
    }

    private fun attachmentType(contentType: String, mediaType: String): String = when {
        mediaType == "IMAGE" -> "IMAGE"
        contentType == "application/pdf" -> "PDF"
        contentType.startsWith("audio/") -> "AUDIO"
        else -> "FILE"
    }

    private fun user(userId: UUID): UserEntity = userRepository.findById(userId).orElseThrow { notFound("User not found") }

    private fun requireSelf(current: CurrentUser, teacherIdRaw: String) {
        if (teacherIdRaw != current.userId.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot access another teacher's groups")
        }
    }

    private fun apiRole(user: UserEntity): String = when (user.role) {
        Role.STUDENT -> "STUDENT"
        Role.PARENT -> "PARENT"
        else -> "TEACHER"
    }

    private fun parseStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).map { node.get(it).asString() }
    }

    private fun parseUuid(raw: String, label: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument(label + " is not a valid identifier")
}
