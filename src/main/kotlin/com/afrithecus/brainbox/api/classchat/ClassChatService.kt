package com.afrithecus.brainbox.api.classchat

import com.afrithecus.brainbox.api.classchat.entity.ClassGroupEntity
import com.afrithecus.brainbox.api.classchat.entity.ClassGroupMemberEntity
import com.afrithecus.brainbox.api.classchat.entity.ClassGroupMessageEntity
import com.afrithecus.brainbox.api.classchat.entity.ClassGroupPollEntity
import com.afrithecus.brainbox.api.classchat.entity.ClassGroupPollVoteEntity
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupMemberRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupMessageRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupPollRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupPollVoteRepository
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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Teacher class-group chat (doc 04 §12). Groups belong to one of the teacher's
 * classes; only the owning teacher (or an admin) may create, moderate, poll or
 * read threads. Realtime WebSocket transport is Phase 6.
 */
@Service
class ClassChatService(
    private val groupRepository: ClassGroupRepository,
    private val memberRepository: ClassGroupMemberRepository,
    private val messageRepository: ClassGroupMessageRepository,
    private val pollRepository: ClassGroupPollRepository,
    private val pollVoteRepository: ClassGroupPollVoteRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val userRepository: UserRepository,
    private val homeworkRepository: HomeworkRepository,
    private val submissionRepository: HomeworkSubmissionRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun groups(current: CurrentUser, teacherIdRaw: String): List<ClassGroupPayload> {
        requireSelf(current, teacherIdRaw)
        return groupRepository.findAllByTeacherIdOrderByUpdatedAtDesc(current.userId).map(::groupPayload)
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
            teacherLastReadAt = clock.instant()
        })
        replaceMembers(group, memberIds)
        return groupPayload(group)
    }

    @Transactional
    fun update(current: CurrentUser, groupIdRaw: String, nameRaw: String?, descriptionRaw: String?, memberIds: List<String>?): ClassGroupPayload {
        val group = ownedGroup(current, groupIdRaw)
        nameRaw?.let {
            val name = it.trim()
            if (name.isEmpty()) throw invalidArgument("name must not be blank")
            group.name = name
        }
        descriptionRaw?.let { group.description = it.trim().takeIf { text -> text.isNotEmpty() } }
        if (memberIds != null) replaceMembers(group, memberIds)
        groupRepository.save(group)
        return groupPayload(group)
    }

    @Transactional
    fun delete(current: CurrentUser, groupIdRaw: String) {
        groupRepository.delete(ownedGroup(current, groupIdRaw))
    }

    @Transactional
    fun messages(current: CurrentUser, groupIdRaw: String, limitRaw: Int, before: Long?): List<ClassGroupMessagePayload> {
        val group = ownedGroup(current, groupIdRaw)
        val limit = limitRaw.coerceIn(1, 200)
        val rows = messageRepository.findAllByGroupIdOrderByCreatedAtDesc(group.id)
            .filter { before == null || it.createdAt.toEpochMilli() < before }
            .take(limit)
        group.teacherLastReadAt = clock.instant()
        groupRepository.save(group)
        return rows.map(::messagePayload)
    }

    @Transactional
    fun send(current: CurrentUser, groupIdRaw: String, request: SendMessageRequest, replyTo: String?, isAnnouncement: Boolean): ClassGroupMessagePayload {
        val group = ownedGroup(current, groupIdRaw)
        val text = request.text.trim()
        val attachments = request.attachments.orEmpty()
        if (text.isEmpty() && attachments.isEmpty()) throw invalidArgument("A message needs text or an attachment")
        val replyToId = replyTo?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "replyTo") }
        if (replyToId != null) {
            val parent = messageRepository.findById(replyToId).orElse(null)
            if (parent == null || parent.groupId != group.id) throw notFound("Reply target not found")
        }
        val sender = user(current.userId)
        val saved = messageRepository.save(ClassGroupMessageEntity().apply {
            this.groupId = group.id
            senderId = sender.id
            senderName = sender.name
            senderRole = apiRole(sender)
            this.text = text
            this.replyToId = replyToId
            this.isAnnouncement = isAnnouncement
            this.attachments = mapper.writeValueAsString(attachments)
        })
        if (isAnnouncement) group.isAnnouncementMode = true
        group.teacherLastReadAt = clock.instant()
        groupRepository.save(group)
        return messagePayload(saved)
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
        return pollPayload(poll)
    }

    @Transactional
    fun votePoll(current: CurrentUser, pollIdRaw: String, optionIndex: Int) {
        val poll = pollRepository.findById(parseUuid(pollIdRaw, "poll id")).orElse(null) ?: throw notFound("Poll not found")
        val options = parseStringList(poll.options)
        if (optionIndex < 0 || optionIndex >= options.size) throw invalidArgument("optionIndex is out of range")
        val existing = pollVoteRepository.findByPollIdAndUserId(poll.id, current.userId)
        if (existing == null) {
            pollVoteRepository.save(ClassGroupPollVoteEntity().apply {
                pollId = poll.id
                userId = current.userId
                this.optionIndex = optionIndex
            })
        } else {
            existing.optionIndex = optionIndex
            existing.votedAt = clock.instant()
            pollVoteRepository.save(existing)
        }
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

    // ------------------------------------------------------------ internals

    private fun groupPayload(group: ClassGroupEntity): ClassGroupPayload {
        val last = messageRepository.findFirstByGroupIdOrderByCreatedAtDesc(group.id)
        val unread = group.teacherLastReadAt?.let { since ->
            messageRepository.countByGroupIdAndCreatedAtAfterAndSenderIdNot(group.id, since, group.teacherId)
        } ?: messageRepository.countByGroupIdAndCreatedAtAfterAndSenderIdNot(group.id, Instant.EPOCH, group.teacherId)
        return ClassGroupPayload(
            id = group.id.toString(),
            name = group.name,
            memberCount = memberRepository.countByGroupId(group.id).toInt(),
            teacherName = group.teacherName,
            classId = group.classId.toString(),
            description = group.description,
            unreadCount = unread.toInt(),
            isMuted = group.teacherMutedUntil?.isAfter(clock.instant()) == true,
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

    private fun pollPayload(poll: ClassGroupPollEntity): LivePollPayload {
        val options = parseStringList(poll.options)
        val counts = pollVoteRepository.findAllByPollId(poll.id).groupingBy { it.optionIndex }.eachCount()
        val votes = LinkedHashMap<Int, Int>()
        options.indices.forEach { votes[it] = counts[it] ?: 0 }
        val results = LinkedHashMap<String, Int>()
        options.forEachIndexed { index, option -> results[option] = counts[index] ?: 0 }
        return LivePollPayload(
            id = poll.id.toString(),
            classId = poll.groupId.toString(),
            question = poll.question,
            options = options,
            votes = votes,
            results = results,
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
            val type = item.get("type")?.asString() ?: "FILE"
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
                type = type,
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
