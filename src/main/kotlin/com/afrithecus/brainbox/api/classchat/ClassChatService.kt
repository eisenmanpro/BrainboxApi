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
import com.afrithecus.brainbox.api.classchat.ws.ClassChatSocketHandler
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupReadRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupRepository
import com.afrithecus.brainbox.api.classchat.web.ClassGroupMessagePayload
import com.afrithecus.brainbox.api.classchat.web.ClassGroupPayload
import com.afrithecus.brainbox.api.classchat.web.GradebookContributionPayload
import com.afrithecus.brainbox.api.classchat.web.GroupClassOptionPayload
import com.afrithecus.brainbox.api.classchat.web.GroupCreateOptionsPayload
import com.afrithecus.brainbox.api.classchat.web.GroupTeacherPayload
import com.afrithecus.brainbox.api.classchat.web.MessageAttachmentPayload
import com.afrithecus.brainbox.api.classchat.web.PollOptionPayload
import com.afrithecus.brainbox.api.classchat.web.SendMessageRequest
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.domain.GradeNormalizer
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.homework.repository.HomeworkRepository
import com.afrithecus.brainbox.api.homework.repository.HomeworkSubmissionRepository
import com.afrithecus.brainbox.api.identity.AdminSchoolAccess
import com.afrithecus.brainbox.api.identity.AuditLogService
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
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
 *
 * A group is either CLASS-scoped (one class) or GRADE-scoped (one grade level, no class),
 * and its creator picks an audience (STUDENTS / TEACHERS / CLASS_TEACHERS / ALL) that decides
 * who else may see and join it. An ICT admin of the school can list every group, join and
 * contribute for moderation, and freeze/archive it with a reason; a frozen or archived group
 * is read-only for everyone until an admin reverses it. Identity, role, school and
 * membership always come from the token and the stored row, never from the request body.
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
    private val learnerCounts: com.afrithecus.brainbox.api.classes.ClassLearnerCountService,
    private val userRepository: UserRepository,
    private val homeworkRepository: HomeworkRepository,
    private val submissionRepository: HomeworkSubmissionRepository,
    private val notificationRepository: NotificationRepository,
    private val schoolRepository: SchoolRepository,
    private val mediaService: MediaService,
    private val adminAccess: AdminSchoolAccess,
    private val auditLogService: AuditLogService,
    private val socketHandler: ClassChatSocketHandler,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    // ------------------------------------------------------ teacher surface

    /**
     * The groups a teacher may see: their own, the ones they joined, and the school groups
     * whose scope + audience open them to this teacher (grade groups and class groups the
     * creator explicitly opened to staff). The legacy client shape is preserved: a teacher
     * with no school on record still gets only the groups they created.
     */
    @Transactional(readOnly = true)
    fun groups(current: CurrentUser, teacherIdRaw: String): List<ClassGroupPayload> {
        requireSelf(current, teacherIdRaw)
        val viewer = user(current.userId)
        val schoolId = viewer.schoolId
            ?: return groupRepository.findAllByTeacherIdOrderByUpdatedAtDesc(current.userId)
                .map { groupPayload(it, viewer) }
        ensureSchoolGroups(schoolId)
        return groupRepository.findAllBySchoolIdOrderByUpdatedAtDesc(schoolId)
            .filter { teacherCanSee(viewer, it) }
            .map { groupPayload(it, viewer) }
    }

    /** Classes and grades the create-group dialog may offer this teacher. */
    @Transactional(readOnly = true)
    fun createOptions(current: CurrentUser): GroupCreateOptionsPayload {
        val actor = user(current.userId)
        requireGroupCreator(actor)
        val schoolId = actor.schoolId
            ?: throw ApiException(ApiErrorCode.FORBIDDEN, "A teacher must belong to a school to create groups")
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val creatable = classes.filter { canCreateClassGroupFor(actor, it) }
        return GroupCreateOptionsPayload(
            classes = creatable.map {
                GroupClassOptionPayload(id = it.id.toString(), name = it.name, gradeLevel = it.gradeLevel)
            },
            grades = classes.map { it.gradeLevel.trim() }.filter { it.isNotEmpty() }.distinct().sorted(),
        )
    }

    @Transactional
    fun create(
        current: CurrentUser,
        teacherIdRaw: String,
        nameRaw: String,
        classIdRaw: String?,
        scopeRaw: String?,
        gradeLevelRaw: String?,
        audienceRaw: String?,
        memberIds: List<String>,
    ): ClassGroupPayload {
        requireSelf(current, teacherIdRaw)
        val teacher = user(current.userId)
        requireGroupCreator(teacher)
        val name = nameRaw.trim()
        if (name.isEmpty()) throw invalidArgument("name must not be blank")
        if (name.length > 200) throw invalidArgument("name must be at most 200 characters")
        val scope = parseScope(scopeRaw)
        val audience = parseAudience(audienceRaw)
        val group = ClassGroupEntity().apply {
            this.scope = scope
            this.audience = audience
            this.teacherId = teacher.id
            teacherName = teacher.name
            this.name = name
            description = null
        }
        if (scope == SCOPE_CLASS) {
            val clazz = ownedClassForGroup(teacher, classIdRaw)
            group.classId = clazz.id
            group.gradeLevel = clazz.gradeLevel
            // A class carries its school; a legacy/seed class without one keeps the group school-less.
            group.schoolId = clazz.schoolId ?: teacher.schoolId
        } else {
            val schoolId = teacher.schoolId
                ?: throw ApiException(ApiErrorCode.FORBIDDEN, "A teacher must belong to a school to create a grade group")
            group.schoolId = schoolId
            group.gradeLevel = canonicalSchoolGrade(schoolId, gradeLevelRaw)
        }
        val saved = groupRepository.save(group)
        replaceMembers(saved, memberIds)
        return groupPayload(saved, teacher)
    }

    @Transactional
    fun update(
        current: CurrentUser,
        groupIdRaw: String,
        nameRaw: String?,
        descriptionRaw: String?,
        isAnnouncementMode: Boolean?,
        audienceRaw: String?,
        memberIds: List<String>?,
    ): ClassGroupPayload {
        val actor = user(current.userId)
        val group = creatorGroup(actor, groupIdRaw)
        if (group.isSystem) throw ApiException(ApiErrorCode.FORBIDDEN, "Brainbox maintains this school group")
        requireWritable(group)
        nameRaw?.let {
            val name = it.trim()
            if (name.isEmpty()) throw invalidArgument("name must not be blank")
            if (name.length > 200) throw invalidArgument("name must be at most 200 characters")
            group.name = name
        }
        descriptionRaw?.let { group.description = it.trim().takeIf { text -> text.isNotEmpty() } }
        isAnnouncementMode?.let { group.isAnnouncementMode = it }
        audienceRaw?.let { group.audience = parseAudience(it) }
        if (memberIds != null) replaceMembers(group, memberIds)
        groupRepository.save(group)
        return groupPayload(group, actor)
    }

    @Transactional
    fun delete(current: CurrentUser, groupIdRaw: String) {
        val actor = user(current.userId)
        val group = groupById(groupIdRaw)
        if (group.isSystem) throw ApiException(ApiErrorCode.FORBIDDEN, "Brainbox maintains this school group")
        // An ICT admin moderates by freezing; deletion stays the creator's decision only.
        if (group.teacherId != actor.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only the group's creator can delete it")
        }
        groupRepository.delete(group)
    }

    // ------------------------------------------------------ membership

    /**
     * Joins the caller as an explicit member when the group's audience (or the caller being
     * an ICT admin) allows it. Idempotent: joining twice keeps one membership row.
     */
    @Transactional
    fun join(current: CurrentUser, groupIdRaw: String): ClassGroupPayload {
        val viewer = user(current.userId)
        val group = groupById(groupIdRaw)
        if (group.teacherId == viewer.id) return groupPayload(group, viewer)
        requireWritable(group)
        when (viewer.role) {
            Role.ADMIN -> Unit // moderation: may join any school's group
            Role.STUDENT -> if (!studentCanSee(viewer, group)) {
                throw ApiException(ApiErrorCode.FORBIDDEN, "This group is not open to you")
            }
            Role.TEACHER -> {
                if (!isModerator(viewer, group)) {
                    if (group.schoolId == null || viewer.schoolId != group.schoolId) {
                        throw ApiException(ApiErrorCode.FORBIDDEN, "Not a group of your school")
                    }
                    if (!staffAudienceAllows(viewer, group.audience)) {
                        throw ApiException(ApiErrorCode.FORBIDDEN, "This group's audience does not include you")
                    }
                }
            }
            else -> throw ApiException(ApiErrorCode.FORBIDDEN, "Only staff and learners can join groups")
        }
        if (memberRepository.findByGroupIdAndMemberId(group.id, viewer.id) == null) {
            memberRepository.save(memberRow(group.id, viewer))
        }
        return groupPayload(group, viewer)
    }

    /** Leaves a group; the creator cannot leave their own group. Repeat-safe. */
    @Transactional
    fun leave(current: CurrentUser, groupIdRaw: String) {
        val viewer = user(current.userId)
        val group = groupById(groupIdRaw)
        if (group.teacherId == viewer.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "The creator cannot leave their own group")
        }
        memberRepository.deleteByGroupIdAndMemberId(group.id, viewer.id)
    }

    // ------------------------------------------------------ messages

    @Transactional
    fun messages(current: CurrentUser, groupIdRaw: String, limitRaw: Int, before: Long?): List<ClassGroupMessagePayload> =
        messagesFor(current, readableGroup(current, groupIdRaw), limitRaw, before)

    /**
     * Socket-handshake authorization (ClassChatHandshakeInterceptor). A browser cannot set
     * an Authorization header on a WebSocket, so the handshake passes the bearer as a query
     * parameter; this method reuses the exact REST read gate so a socket can never widen
     * access. Inactive accounts are refused.
     */
    @Transactional(readOnly = true)
    fun mayRead(userId: UUID, groupId: UUID): Boolean {
        val viewer = userRepository.findById(userId).orElse(null) ?: return false
        if (!viewer.isActive) return false
        return runCatching {
            readableGroup(CurrentUser(userId = userId, role = viewer.role, subRole = viewer.subRole), groupId.toString())
        }.isSuccess
    }

    @Transactional
    fun send(
        current: CurrentUser,
        groupIdRaw: String,
        request: SendMessageRequest,
        replyTo: String?,
        isAnnouncement: Boolean,
        clientMessageId: String?,
    ): ClassGroupMessagePayload {
        val group = readableGroup(current, groupIdRaw)
        requireWritable(group)
        val saved = sendFor(current, group, request, replyTo, isAnnouncement, clientMessageId)
        socketHandler.broadcastMessage(group.id, saved)
        return saved
    }

    @Transactional
    fun setPinned(current: CurrentUser, groupIdRaw: String, messageIdRaw: String, pinned: Boolean) {
        val group = creatorGroup(user(current.userId), groupIdRaw)
        requireWritable(group)
        val message = ownedMessage(group, messageIdRaw)
        message.isPinned = pinned
        messageRepository.save(message)
    }

    @Transactional
    fun deleteMessage(current: CurrentUser, groupIdRaw: String, messageIdRaw: String) {
        val group = creatorGroup(user(current.userId), groupIdRaw)
        requireWritable(group)
        // Repeat-safe: an offline delete replay of an already-deleted message is a no-op.
        val message = runCatching { ownedMessage(group, messageIdRaw) }.getOrNull() ?: return
        messageRepository.delete(message)
        socketHandler.broadcastDeleted(group.id, messageIdRaw)
    }

    @Transactional
    fun muteMember(current: CurrentUser, groupIdRaw: String, memberIdRaw: String, durationMinutes: Int) {
        val group = creatorGroup(user(current.userId), groupIdRaw)
        requireWritable(group)
        val member = memberRepository.findByGroupIdAndMemberId(group.id, parseUuid(memberIdRaw, "member id"))
            ?: throw notFound("Member not found")
        member.mutedUntil = if (durationMinutes <= 0) null else clock.instant().plus(Duration.ofMinutes(durationMinutes.toLong()))
        memberRepository.save(member)
    }

    @Transactional
    fun createPoll(
        current: CurrentUser,
        groupIdRaw: String,
        questionRaw: String,
        options: List<String>,
        clientPollId: String? = null,
    ): LivePollPayload {
        val group = creatorGroup(user(current.userId), groupIdRaw)
        requireWritable(group)
        val stableId = clientPollId?.trim()?.takeIf { it.isNotEmpty() }
        // Idempotent per clientPollId: an offline poll-create replay returns the original poll.
        if (stableId != null) {
            pollRepository.findByClientId(stableId)?.let { return pollPayload(it, current.userId) }
        }
        val question = questionRaw.trim()
        if (question.isEmpty()) throw invalidArgument("question must not be blank")
        val cleaned = options.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.size < 2) throw invalidArgument("A poll needs at least two options")
        val poll = pollRepository.save(ClassGroupPollEntity().apply {
            this.groupId = group.id
            createdBy = current.userId
            this.question = question
            this.options = mapper.writeValueAsString(cleaned)
            this.clientId = stableId
        })
        return pollPayload(poll, current.userId)
    }

    @Transactional(readOnly = true)
    fun teachers(current: CurrentUser, groupIdRaw: String): List<GroupTeacherPayload> {
        val group = readableGroup(current, groupIdRaw)
        if (group.scope == SCOPE_GRADE) {
            val rows = linkedSetOf<UUID>()
            rows += group.teacherId
            memberRepository.findAllByGroupIdOrderByMemberNameAsc(group.id)
                .mapNotNull { userRepository.findById(it.memberId).orElse(null) }
                .filter { it.role == Role.TEACHER }
                .forEach { rows += it.id }
            return rows.mapNotNull { userRepository.findById(it).orElse(null) }
                .map { teacherRow(it, group) }
        }
        val clazz = group.classId?.let { classRepository.findById(it).orElse(null) } ?: throw notFound("Class not found")
        val joinedTeachers = memberRepository.findAllByGroupIdOrderByMemberNameAsc(group.id)
            .mapNotNull { userRepository.findById(it.memberId).orElse(null) }
            .filter { it.role == Role.TEACHER && it.id != group.teacherId }
        val rows = listOf(
            GroupTeacherPayload(
                id = clazz.id.toString(),
                name = clazz.name,
                grade = GradeNormalizer.canonicalKey(clazz.gradeLevel) ?: 0,
                section = null,
                subject = clazz.subject,
                teacherId = clazz.teacherUserId.toString(),
                teacherName = group.teacherName,
                schoolId = clazz.schoolId?.toString() ?: "",
                studentCount = learnerCounts.count(clazz.id),
                createdAt = clazz.createdAt.toEpochMilli(),
                isActive = clazz.isActive,
            ),
        ) + joinedTeachers.map { teacherRow(it, group) }
        return rows.distinctBy { it.teacherId }
    }

    @Transactional(readOnly = true)
    fun gradebookContributions(current: CurrentUser, groupIdRaw: String): List<GradebookContributionPayload> {
        val group = readableGroup(current, groupIdRaw)
        val classIds = when (group.scope) {
            SCOPE_GRADE -> {
                val schoolId = group.schoolId ?: return emptyList()
                classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
                    .filter { sameGrade(it.gradeLevel, group.gradeLevel) }
                    .map { it.id }
            }
            else -> listOfNotNull(group.classId)
        }
        return classIds.flatMap { classId ->
            homeworkRepository.findAllByClassIdOrderByDueDateDesc(classId).flatMap { homework ->
                submissionRepository.findAllByHomeworkId(homework.id).mapNotNull { submission ->
                    val grade = submission.grade ?: return@mapNotNull null
                    GradebookContributionPayload(
                        id = "contrib_" + submission.id,
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
    }

    // ------------------------------------------------------ parent surface

    @Transactional(readOnly = true)
    fun groupsForChild(current: CurrentUser, childIdRaw: String): List<ClassGroupPayload> {
        val child = linkedChild(current, childIdRaw)
        val viewer = user(current.userId)
        return studentGroups(child.id).map { groupPayload(it, viewer) }
    }

    @Transactional
    fun parentMessages(current: CurrentUser, groupIdRaw: String, limitRaw: Int, before: Long?): List<ClassGroupMessagePayload> =
        messagesFor(current, readableGroup(current, groupIdRaw), limitRaw, before)

    @Transactional
    fun markRead(current: CurrentUser, groupIdRaw: String) {
        val group = readableGroup(current, groupIdRaw)
        markReadFor(group, current.userId)
    }

    @Transactional
    fun votePoll(current: CurrentUser, pollIdRaw: String, optionIndex: Int) {
        val poll = pollRepository.findById(parseUuid(pollIdRaw, "poll id")).orElse(null) ?: throw notFound("Poll not found")
        readableGroup(current, poll.groupId.toString())
        castVote(poll, current.userId, optionIndex)
    }

    // ------------------------------------------------------ student surface

    @Transactional(readOnly = true)
    fun groupsForStudent(current: CurrentUser): List<ClassGroupPayload> {
        val viewer = user(current.userId)
        viewer.schoolId?.let { ensureSchoolGroups(it) }
        return studentGroups(current.userId).map { groupPayload(it, viewer) }
    }

    @Transactional
    fun studentMessages(current: CurrentUser, groupIdRaw: String, limitRaw: Int, before: Long?): List<ClassGroupMessagePayload> =
        messagesFor(current, readableGroup(current, groupIdRaw), limitRaw, before)

    // ------------------------------------------------------ admin moderation

    /** Every group of the school, for the ICT admin's moderation screen. */
    @Transactional(readOnly = true)
    fun adminGroups(current: CurrentUser, schoolIdRaw: String): List<ClassGroupPayload> {
        val schoolId = requireSchoolId(schoolIdRaw)
        val actor = adminAccess.require(current, schoolId)
        ensureSchoolGroups(schoolId)
        return groupRepository.findAllBySchoolIdOrderByUpdatedAtDesc(schoolId)
            .map { groupPayload(it, actor) }
    }

    /** Freeze/unfreeze a group. A freeze requires a reason and is reversible. */
    @Transactional
    fun adminFreeze(current: CurrentUser, schoolIdRaw: String, groupIdRaw: String, isFrozen: Boolean, reasonRaw: String?): ClassGroupPayload {
        val schoolId = requireSchoolId(schoolIdRaw)
        val actor = adminAccess.require(current, schoolId)
        val group = schoolGroup(schoolId, groupIdRaw)
        val reason = reasonRaw?.trim()?.takeIf { it.isNotEmpty() }
        if (isFrozen && reason == null) throw invalidArgument("A freeze reason is required")
        group.isFrozen = isFrozen
        group.frozenReason = if (isFrozen) reason else null
        group.frozenBy = if (isFrozen) actor.id else null
        group.frozenAt = if (isFrozen) clock.instant() else null
        groupRepository.save(group)
        auditLogService.record(
            schoolId,
            actor,
            (if (isFrozen) "Froze group \"" else "Unfroze group \"") + group.name + "\"" +
                (if (isFrozen) ": " + reason else ""),
        )
        return groupPayload(group, actor)
    }

    /** Term archive/unarchive: read-only while archived, reversible by the admin. */
    @Transactional
    fun adminArchive(
        current: CurrentUser,
        schoolIdRaw: String,
        groupIdRaw: String,
        isArchived: Boolean,
        termLabelRaw: String?,
        reasonRaw: String?,
    ): ClassGroupPayload {
        val schoolId = requireSchoolId(schoolIdRaw)
        val actor = adminAccess.require(current, schoolId)
        val group = schoolGroup(schoolId, groupIdRaw)
        val termLabel = termLabelRaw?.trim()?.takeIf { it.isNotEmpty() }
        if (isArchived && termLabel == null) throw invalidArgument("A term label is required to archive a group")
        group.isArchived = isArchived
        group.archivedAt = if (isArchived) clock.instant() else null
        group.archiveTermLabel = if (isArchived) termLabel else null
        group.archiveReason = if (isArchived) reasonRaw?.trim()?.takeIf { it.isNotEmpty() } else null
        groupRepository.save(group)
        auditLogService.record(
            schoolId,
            actor,
            (if (isArchived) "Archived group \"" else "Unarchived group \"") + group.name + "\"" +
                (if (isArchived && termLabel != null) " for " + termLabel else ""),
        )
        return groupPayload(group, actor)
    }

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
        when (group.scope) {
            SCOPE_CLASS -> group.classId?.let { classId ->
                membershipRepository.findAllByClassId(classId).forEach { recipients += it.studentId }
            }
            SCOPE_GRADE -> {
                val schoolId = group.schoolId
                val grade = group.gradeLevel
                if (schoolId != null && grade != null) {
                    val staffInAudience = group.audience == AUDIENCE_TEACHERS || group.audience == AUDIENCE_CLASS_TEACHERS || group.audience == AUDIENCE_ALL
                    val studentsInAudience = group.audience == AUDIENCE_STUDENTS || group.audience == AUDIENCE_ALL
                    val schoolUsers = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
                    if (studentsInAudience) {
                        val gradeClassIds = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
                            .filter { sameGrade(it.gradeLevel, grade) }
                            .map { it.id }
                            .toSet()
                        val studentIdsInGrade = gradeClassIds
                            .flatMap { classId -> membershipRepository.findAllByClassId(classId).map { it.studentId } }
                            .toSet()
                        schoolUsers
                            .filter { it.role == Role.STUDENT && (it.id in studentIdsInGrade || sameGrade(it.gradeLevel, grade)) }
                            .forEach { recipients += it.id }
                    }
                    if (staffInAudience) {
                        schoolUsers
                            .filter { it.role == Role.TEACHER && staffAudienceAllows(it, group.audience) }
                            .forEach { recipients += it.id }
                    }
                }
            }
        }
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

    private fun groupPayload(group: ClassGroupEntity, viewer: UserEntity): ClassGroupPayload {
        val last = messageRepository.findFirstByGroupIdOrderByCreatedAtDesc(group.id)
        val since = readRepository.findByGroupIdAndUserId(group.id, viewer.id)?.lastReadAt ?: Instant.EPOCH
        val unread = messageRepository.countByGroupIdAndCreatedAtAfterAndSenderIdNot(group.id, since, viewer.id)
        val muted = if (viewer.id == group.teacherId) {
            group.teacherMutedUntil?.isAfter(clock.instant()) == true
        } else {
            memberRepository.findByGroupIdAndMemberId(group.id, viewer.id)?.mutedUntil?.isAfter(clock.instant()) == true
        }
        val member = viewer.id != group.teacherId && memberRepository.findByGroupIdAndMemberId(group.id, viewer.id) != null
        val moderator = isModerator(viewer, group)
        val canJoin = viewer.id != group.teacherId && !member && !moderator &&
            !group.isFrozen && !group.isArchived && canJoinAudience(viewer, group)
        return ClassGroupPayload(
            id = group.id.toString(),
            name = group.name,
            memberCount = if (group.isSystem) {
                userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(group.schoolId!!).count {
                    if (group.audience == AUDIENCE_TEACHERS) it.role == Role.TEACHER else it.role != Role.TEACHER
                }
            } else {
                memberRepository.countByGroupId(group.id).toInt()
            },
            teacherName = group.teacherName,
            classId = group.classId?.toString() ?: "",
            description = group.description,
            unreadCount = unread.toInt(),
            isMuted = muted,
            isAnnouncementMode = group.isAnnouncementMode,
            lastMessage = last?.text?.takeIf { it.isNotBlank() } ?: last?.attachments?.let { "Attachment" },
            lastMessageTime = last?.createdAt?.toEpochMilli(),
            scope = group.scope,
            gradeLevel = group.gradeLevel,
            audience = group.audience,
            teacherId = group.teacherId.toString(),
            isFrozen = group.isFrozen,
            frozenReason = group.frozenReason,
            frozenAt = group.frozenAt?.toEpochMilli(),
            isArchived = group.isArchived,
            archivedAt = group.archivedAt?.toEpochMilli(),
            termLabel = group.archiveTermLabel,
            isCreator = group.teacherId == viewer.id,
            isMember = member,
            canJoin = canJoin,
            canDelete = group.teacherId == viewer.id && !group.isSystem,
            canModerate = moderator,
            isSystem = group.isSystem,
            systemNote = if (group.isSystem) group.description else null,
            messageCount = messageRepository.countByGroupId(group.id).toInt(),
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

    /** Replaces the explicit member rows; every id is re-validated against the group's scope. */
    private fun replaceMembers(group: ClassGroupEntity, memberIds: List<String>) {
        memberRepository.deleteAllByGroupId(group.id)
        memberIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { raw ->
            val member = user(parseUuid(raw, "member id"))
            requireMemberEligible(group, member)
            memberRepository.save(memberRow(group.id, member))
        }
    }

    private fun memberRow(groupId: UUID, member: UserEntity) = ClassGroupMemberEntity().apply {
        this.groupId = groupId
        memberId = member.id
        memberName = member.name
        memberRole = apiRole(member)
    }

    private fun requireMemberEligible(group: ClassGroupEntity, member: UserEntity) {
        if (group.schoolId != null && member.schoolId != null && group.schoolId != member.schoolId) {
            throw invalidArgument(member.name + " is not in this school")
        }
        when (member.role) {
            Role.STUDENT -> {
                if (group.scope == SCOPE_CLASS) {
                    if (group.classId == null || !studentInClass(member.id, group.classId!!)) {
                        throw invalidArgument(member.name + " is not a learner in this class")
                    }
                } else if (!sameGrade(gradeOfStudent(member.id), group.gradeLevel)) {
                    throw invalidArgument(member.name + " is not a learner in this grade")
                }
            }
            Role.TEACHER -> {
                val moderator = member.id == group.teacherId || (member.role == Role.TEACHER && member.subRole == SubRole.ICT_ADMIN)
                if (!moderator && !staffAudienceAllows(member, group.audience)) {
                    throw invalidArgument(member.name + " is outside this group's audience")
                }
            }
            else -> throw invalidArgument("Only learners and teachers can be group members")
        }
    }

    /** Groups a student belongs to: their class/grade groups plus explicit memberships. */
    private fun studentGroups(studentId: UUID): List<ClassGroupEntity> {
        val student = user(studentId)
        val schoolId = student.schoolId ?: return memberRepository.findAllByMemberId(studentId)
            .mapNotNull { groupRepository.findById(it.groupId).orElse(null) }
            .distinctBy { it.id }
        return groupRepository.findAllBySchoolIdOrderByUpdatedAtDesc(schoolId)
            .filter { studentCanSee(student, it) }
    }

    private fun studentCanSee(student: UserEntity, group: ClassGroupEntity): Boolean {
        if (memberRepository.findByGroupIdAndMemberId(group.id, student.id) != null) return true
        return when (group.scope) {
            SCOPE_CLASS -> group.classId != null && studentInClass(student.id, group.classId!!)
            SCOPE_GRADE -> {
                (group.audience == AUDIENCE_STUDENTS || group.audience == AUDIENCE_ALL) &&
                    sameGrade(gradeOfStudent(student.id), group.gradeLevel)
            }
            SCOPE_SCHOOL -> (group.audience == AUDIENCE_STUDENTS || group.audience == AUDIENCE_ALL) &&
                group.schoolId != null && group.schoolId == student.schoolId
            else -> false
        }
    }

    private fun readableGroup(current: CurrentUser, groupIdRaw: String): ClassGroupEntity {
        val group = groupById(groupIdRaw)
        val viewer = user(current.userId)
        val allowed = when (viewer.role) {
            Role.ADMIN -> true
            Role.STUDENT -> studentCanSee(viewer, group)
            Role.PARENT -> userRepository.findByParentUserId(viewer.id).any { studentCanSee(it, group) }
            else -> group.teacherId == viewer.id ||
                isModerator(viewer, group) ||
                memberRepository.findByGroupIdAndMemberId(group.id, viewer.id) != null ||
                (group.scope == SCOPE_SCHOOL && teacherCanSee(viewer, group))
        }
        if (!allowed) throw ApiException(ApiErrorCode.FORBIDDEN, "Not a member of this group")
        return group
    }

    /** The creator-only handle used for edit/delete/pin/mute/poll actions. */
    private fun creatorGroup(actor: UserEntity, groupIdRaw: String): ClassGroupEntity {
        val group = groupById(groupIdRaw)
        if (group.teacherId != actor.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only the group's creator can do that")
        }
        return group
    }

    private fun teacherCanSee(viewer: UserEntity, group: ClassGroupEntity): Boolean {
        if (isModerator(viewer, group)) return true
        if (group.teacherId == viewer.id) return true
        if (memberRepository.findByGroupIdAndMemberId(group.id, viewer.id) != null) return true
        if (viewer.role != Role.TEACHER) return false
        if (group.isArchived) return false
        if (group.schoolId == null || viewer.schoolId != group.schoolId) return false
        if (group.scope == SCOPE_SCHOOL) {
            return group.audience == AUDIENCE_TEACHERS || group.audience == AUDIENCE_ALL
        }
        return staffAudienceAllows(viewer, group.audience)
    }

    /** Whether the caller may join right now (used by the payload's canJoin flag). */
    private fun canJoinAudience(viewer: UserEntity, group: ClassGroupEntity): Boolean = when (viewer.role) {
        Role.ADMIN -> true
        Role.TEACHER -> isModerator(viewer, group) ||
            (group.schoolId != null && viewer.schoolId == group.schoolId && staffAudienceAllows(viewer, group.audience))
        Role.STUDENT -> studentCanSee(viewer, group)
        else -> false
    }

    private fun staffAudienceAllows(actor: UserEntity, audience: String): Boolean = when (audience) {
        AUDIENCE_TEACHERS -> true
        AUDIENCE_CLASS_TEACHERS -> actor.subRole == SubRole.CTEACHER || actor.subRole == SubRole.GRADE_COORDINATOR
        AUDIENCE_ALL -> true
        else -> false // STUDENTS: staff join only as the creator or an admin moderator
    }

    private fun isModerator(actor: UserEntity, group: ClassGroupEntity): Boolean {
        if (actor.role == Role.ADMIN) return true
        return actor.role == Role.TEACHER &&
            actor.subRole == SubRole.ICT_ADMIN &&
            actor.schoolId != null &&
            actor.schoolId == group.schoolId
    }

    private fun requireGroupCreator(actor: UserEntity) {
        if (actor.role != Role.TEACHER || actor.subRole == SubRole.ICT_ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only teachers can create groups")
        }
    }

    private fun requireWritable(group: ClassGroupEntity) {
        if (group.isFrozen) {
            val reason = group.frozenReason?.trim().orEmpty()
            throw ApiException(
                ApiErrorCode.FORBIDDEN,
                if (reason.isEmpty()) "This group is frozen by a school admin" else "This group is frozen: " + reason,
            )
        }
        if (group.isArchived) {
            val label = group.archiveTermLabel.orEmpty()
            throw ApiException(
                ApiErrorCode.FORBIDDEN,
                if (label.isEmpty()) "This group is archived and read-only" else "This group is archived (" + label + ") and read-only",
            )
        }
    }

    /**
     * Brainbox keeps two school-wide groups per school: a community for learners
     * and guardians, and a staff room for every teacher. They are created lazily
     * the first time someone lists groups (school registration may have no staff
     * yet), and access is by school membership so transfers need no cleanup.
     */
    private fun ensureSchoolGroups(schoolId: UUID) {
        val school = schoolRepository.findById(schoolId).orElse(null) ?: return
        val staff = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val owner = staff.firstOrNull { it.role == Role.TEACHER && it.subRole == SubRole.ICT_ADMIN }
            ?: staff.firstOrNull { it.role == Role.TEACHER }
            ?: return
        val specs = listOf(
            Triple(AUDIENCE_ALL, school.name + " Community", "This is " + school.name + " community for all parents, guardians and learners. Please follow the school code of conduct."),
            Triple(AUDIENCE_TEACHERS, school.name + " Staff Community", "This is " + school.name + " staff community for all teachers. Please follow the school code of conduct."),
        )
        for ((audience, name, note) in specs) {
            if (groupRepository.findFirstBySchoolIdAndScopeAndAudience(schoolId, SCOPE_SCHOOL, audience) != null) continue
            groupRepository.save(ClassGroupEntity().apply {
                this.scope = SCOPE_SCHOOL
                this.audience = audience
                this.schoolId = schoolId
                this.teacherId = owner.id
                teacherName = owner.name
                this.name = name
                description = note
                isSystem = true
            })
        }
    }

    private fun groupById(groupIdRaw: String): ClassGroupEntity =
        groupRepository.findById(parseUuid(groupIdRaw, "group id")).orElse(null) ?: throw notFound("Class group not found")

    private fun schoolGroup(schoolId: UUID, groupIdRaw: String): ClassGroupEntity {
        val group = groupById(groupIdRaw)
        if (group.schoolId != schoolId) throw notFound("Class group not found")
        return group
    }

    private fun requireSchoolId(raw: String): UUID {
        val id = runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("schoolId is not a valid identifier")
        if (schoolRepository.findById(id).isEmpty) throw notFound("School not found")
        return id
    }

    private fun ownedClassForGroup(teacher: UserEntity, classIdRaw: String?): TeacherClassEntity {
        val classId = parseUuid(classIdRaw?.trim().orEmpty(), "class id")
        val clazz = classRepository.findById(classId).orElse(null) ?: throw notFound("Class not found")
        if (!clazz.isActive) throw invalidArgument("That class is no longer active")
        if (!canCreateClassGroupFor(teacher, clazz)) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your class")
        }
        return clazz
    }

    /** A teacher owns their classes; a coordinator also covers their grade's classes. */
    private fun canCreateClassGroupFor(actor: UserEntity, clazz: TeacherClassEntity): Boolean {
        if (clazz.teacherUserId == actor.id) return true
        if (actor.subRole != SubRole.GRADE_COORDINATOR) return false
        if (clazz.schoolId != null && actor.schoolId != null && clazz.schoolId != actor.schoolId) return false
        return sameGrade(clazz.gradeLevel, actor.gradeLevel)
    }

    private fun canonicalSchoolGrade(schoolId: UUID, gradeLevelRaw: String?): String {
        val wanted = gradeLevelRaw?.trim().orEmpty()
        if (wanted.isEmpty()) throw invalidArgument("Select the grade this group belongs to")
        val grades = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .map { it.gradeLevel.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return grades.firstOrNull { sameGrade(it, wanted) } ?: throw notFound("Grade not found")
    }

    private fun parseScope(raw: String?): String {
        val scope = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: SCOPE_CLASS
        if (scope !in SCOPES) throw invalidArgument("scope must be CLASS or GRADE")
        return scope
    }

    private fun parseAudience(raw: String?): String {
        val audience = raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: AUDIENCE_STUDENTS
        if (audience !in AUDIENCES) throw invalidArgument("audience must be STUDENTS, TEACHERS, CLASS_TEACHERS or ALL")
        return audience
    }

    private fun sameGrade(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val ka = GradeNormalizer.canonicalKey(a)
        val kb = GradeNormalizer.canonicalKey(b)
        if (ka != null && kb != null) return ka == kb
        return a.trim().equals(b.trim(), ignoreCase = true)
    }

    private fun studentInClass(studentId: UUID, classId: UUID): Boolean =
        membershipRepository.findAllByStudentId(studentId).any { it.classId == classId }

    private fun gradeOfStudent(studentId: UUID): String? {
        val grades = membershipRepository.findAllByStudentId(studentId)
            .mapNotNull { classRepository.findById(it.classId).orElse(null)?.gradeLevel }
            .filter { it.isNotBlank() }
            .distinct()
        if (grades.size == 1) return grades.first()
        return grades.firstOrNull() ?: userRepository.findById(studentId).orElse(null)?.gradeLevel
    }

    private fun linkedChild(current: CurrentUser, childIdRaw: String): UserEntity {
        val child = user(parseUuid(childIdRaw, "child id"))
        // F9/N6: a learner account is its own guardian context.
        if (current.role != Role.ADMIN && child.parentUserId != current.userId && child.id != current.userId) {
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

    private fun teacherRow(teacher: UserEntity, group: ClassGroupEntity) = GroupTeacherPayload(
        id = teacher.id.toString(),
        name = teacher.name,
        grade = GradeNormalizer.canonicalKey(teacher.gradeLevel) ?: 0,
        section = null,
        subject = "",
        teacherId = teacher.id.toString(),
        teacherName = teacher.name,
        schoolId = teacher.schoolId?.toString() ?: "",
        studentCount = 0,
        createdAt = teacher.createdAt.toEpochMilli(),
        isActive = teacher.isActive,
    )

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

    private companion object {
        const val SCOPE_CLASS = "CLASS"
        const val SCOPE_GRADE = "GRADE"
        const val SCOPE_SCHOOL = "SCHOOL"
        const val AUDIENCE_STUDENTS = "STUDENTS"
        const val AUDIENCE_TEACHERS = "TEACHERS"
        const val AUDIENCE_CLASS_TEACHERS = "CLASS_TEACHERS"
        const val AUDIENCE_ALL = "ALL"
        val SCOPES = listOf(SCOPE_CLASS, SCOPE_GRADE)
        val AUDIENCES = listOf(AUDIENCE_STUDENTS, AUDIENCE_TEACHERS, AUDIENCE_CLASS_TEACHERS, AUDIENCE_ALL)
    }
}
