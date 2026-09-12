package com.afrithecus.brainbox.api.cbc

import com.afrithecus.brainbox.api.cbc.entity.CbcProjectCommentEntity
import com.afrithecus.brainbox.api.cbc.entity.CbcProjectEntity
import com.afrithecus.brainbox.api.cbc.entity.CbcProjectReportEntity
import com.afrithecus.brainbox.api.cbc.entity.CbcProjectTrackEntity
import com.afrithecus.brainbox.api.cbc.entity.CbcProjectViewEntity
import com.afrithecus.brainbox.api.cbc.entity.CbcProjectVoteEntity
import com.afrithecus.brainbox.api.cbc.model.ProjectStatus
import com.afrithecus.brainbox.api.cbc.model.VoteType
import com.afrithecus.brainbox.api.cbc.repository.CbcProjectCommentRepository
import com.afrithecus.brainbox.api.cbc.repository.CbcProjectReportRepository
import com.afrithecus.brainbox.api.cbc.repository.CbcProjectRepository
import com.afrithecus.brainbox.api.cbc.repository.CbcProjectTrackRepository
import com.afrithecus.brainbox.api.cbc.repository.CbcProjectViewRepository
import com.afrithecus.brainbox.api.cbc.repository.CbcProjectVoteRepository
import com.afrithecus.brainbox.api.cbc.web.AddProjectCommentRequest
import com.afrithecus.brainbox.api.cbc.web.CbcProjectPayload
import com.afrithecus.brainbox.api.cbc.web.CbcVoteRequest
import com.afrithecus.brainbox.api.cbc.web.CommentListResponsePayload
import com.afrithecus.brainbox.api.cbc.web.ProjectCommentPayload
import com.afrithecus.brainbox.api.cbc.web.ProjectListResponsePayload
import com.afrithecus.brainbox.api.cbc.web.ProjectVotePayload
import com.afrithecus.brainbox.api.cbc.web.PublicCommentRequestPayload
import com.afrithecus.brainbox.api.cbc.web.PublicReportRequestPayload
import com.afrithecus.brainbox.api.cbc.web.PublicReportResponsePayload
import com.afrithecus.brainbox.api.cbc.web.PublicTrackRequestPayload
import com.afrithecus.brainbox.api.cbc.web.PublicTrackResponsePayload
import com.afrithecus.brainbox.api.cbc.web.PublicVoteResponsePayload
import com.afrithecus.brainbox.api.cbc.web.SubmitCbcProjectRequest
import com.afrithecus.brainbox.api.cbc.web.UpdateProjectStatusRequest
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID
import kotlin.math.absoluteValue

/**
 * CBC project showcase (doc 06 §3 + docs/ongoing/api_cbc_public_changes.md):
 * authenticated and public (guest) surfaces. The server owns voter identity
 * ("user:<uuid>" / "guest:<guestId>"), media types, author attribution and all
 * counters.
 */
@Service
class CbcProjectService(
    private val projectRepository: CbcProjectRepository,
    private val voteRepository: CbcProjectVoteRepository,
    private val commentRepository: CbcProjectCommentRepository,
    private val viewRepository: CbcProjectViewRepository,
    private val trackRepository: CbcProjectTrackRepository,
    private val reportRepository: CbcProjectReportRepository,
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    // ------------------------------------------------------ authenticated reads

    @Transactional(readOnly = true)
    fun list(
        current: CurrentUser,
        gradeBand: String?,
        subject: String?,
        cbcStrand: String?,
        schoolId: String?,
        statusRaw: String,
        sort: String,
        pageRaw: Int,
        limitRaw: Int,
        search: String?,
    ): ProjectListResponsePayload {
        val status = runCatching { ProjectStatus.valueOf(statusRaw.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown project status: " + statusRaw)
        return paged(
            rows = projectRepository.findAllByStatusOrderByCreatedAtDesc(status),
            voterKey = keyForUser(current.userId),
            schoolId = schoolId,
            gradeBand = gradeBand,
            subject = subject,
            cbcStrand = cbcStrand,
            search = search,
            sort = sort,
            pageRaw = pageRaw,
            limitRaw = limitRaw,
        )
    }

    @Transactional(readOnly = true)
    fun featured(current: CurrentUser?, limitRaw: Int): List<CbcProjectPayload> {
        val limit = limitRaw.coerceIn(1, 50)
        val rows = projectRepository.findAllByStatusOrderByCreatedAtDesc(ProjectStatus.FEATURED).take(limit)
        val votes = votesFor(current?.let { keyForUser(it.userId) }, rows.map { it.id })
        return rows.map { payload(it, votes[it.id]) }
    }

    @Transactional(readOnly = true)
    fun mine(current: CurrentUser, pageRaw: Int, limitRaw: Int): ProjectListResponsePayload {
        val rows = projectRepository.findAllByStudentIdOrderByCreatedAtDesc(current.userId)
        val page = pageRaw.coerceAtLeast(1)
        val limit = limitRaw.coerceIn(1, 100)
        val start = (page - 1) * limit
        val paged = rows.drop(start).take(limit)
        val votes = votesFor(keyForUser(current.userId), paged.map { it.id })
        return ProjectListResponsePayload(
            projects = paged.map { payload(it, votes[it.id]) },
            total = rows.size,
            page = page,
            hasMore = start + limit < rows.size,
        )
    }

    @Transactional(readOnly = true)
    fun detail(current: CurrentUser, projectIdRaw: String): CbcProjectPayload {
        val project = requireProject(projectIdRaw)
        if (project.status == ProjectStatus.REMOVED && project.studentId != current.userId && !isStaff(current)) {
            throw notFound("Project not found")
        }
        return payload(project, votesFor(keyForUser(current.userId), listOf(project.id))[project.id])
    }

    // ------------------------------------------------------ authenticated writes

    @Transactional
    fun submit(current: CurrentUser, request: SubmitCbcProjectRequest): CbcProjectPayload {
        val user = user(current.userId)
        val school = user.schoolId?.let { schoolRepository.findById(it).orElse(null) }
        val media = request.mediaUrls.map { it.trim() }.filter { it.isNotEmpty() }
        val project = projectRepository.save(CbcProjectEntity().apply {
            studentId = user.id
            studentName = user.name
            schoolId = user.schoolId
            schoolName = school?.name ?: ""
            schoolLogoUrl = school?.logoUrl
            title = request.title.trim()
            description = request.description.trim()
            subject = request.subject.trim()
            cbcStrand = request.cbcStrand.trim()
            cbcSubStrand = request.cbcSubStrand.trim()
            gradeBand = request.gradeBand.trim().uppercase()
            mediaUrls = mapper.writeValueAsString(media)
            coverImageUrl = media.firstOrNull { isImage(it) }
            thumbnailUrl = media.firstOrNull { isImage(it) }
            status = ProjectStatus.PENDING
            rubricScores = mapper.writeValueAsString(request.rubricScores)
            tags = mapper.writeValueAsString(request.tags)
        })
        return payload(project, null)
    }

    @Transactional
    fun updateStatus(current: CurrentUser, projectIdRaw: String, request: UpdateProjectStatusRequest): CbcProjectPayload {
        if (!isStaff(current)) throw ApiException(ApiErrorCode.FORBIDDEN, "Only staff can moderate projects")
        val project = requireProject(projectIdRaw)
        project.status = runCatching { ProjectStatus.valueOf(request.status.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown project status: " + request.status)
        return payload(projectRepository.save(project), null)
    }

    @Transactional
    fun vote(current: CurrentUser, projectIdRaw: String, request: CbcVoteRequest): ProjectVotePayload {
        val project = requireProject(projectIdRaw)
        val type = runCatching { VoteType.valueOf(request.voteType.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown vote type: " + request.voteType)
        val voterKey = keyForUser(current.userId)
        val vote = applyVote(project, voterKey, type, current.userId)
        projectRepository.save(project)
        return ProjectVotePayload(project.id.toString(), current.userId.toString(), type.name, vote.createdAt.toEpochMilli())
    }

    @Transactional
    fun removeVote(current: CurrentUser, projectIdRaw: String) {
        applyRemoveVote(requireProject(projectIdRaw), keyForUser(current.userId))
    }

    @Transactional(readOnly = true)
    fun comments(projectIdRaw: String, pageRaw: Int, limitRaw: Int): CommentListResponsePayload =
        commentsPage(requireProject(projectIdRaw), pageRaw, limitRaw)

    @Transactional
    fun addComment(current: CurrentUser, projectIdRaw: String, request: AddProjectCommentRequest): ProjectCommentPayload {
        val project = requireProject(projectIdRaw)
        val user = user(current.userId)
        val comment = saveComment(project, request.content, request.parentCommentId, request.mentions) {
            userId = user.id
            userName = user.name
            userRole = apiRole(user)
        }
        return commentPayload(comment, emptyList())
    }

    @Transactional
    fun recordView(current: CurrentUser, projectIdRaw: String) {
        recordProjectView(requireProject(projectIdRaw), keyForUser(current.userId), current.userId)
    }

    // ------------------------------------------------------ public (guest) surface

    @Transactional(readOnly = true)
    fun listPublic(
        guestId: String?,
        gradeBand: String?,
        subject: String?,
        cbcStrand: String?,
        search: String?,
        sort: String,
        pageRaw: Int,
        limitRaw: Int,
    ): ProjectListResponsePayload {
        val rows = projectRepository.findAllByStatusOrderByCreatedAtDesc(ProjectStatus.APPROVED) +
            projectRepository.findAllByStatusOrderByCreatedAtDesc(ProjectStatus.FEATURED)
        return paged(
            rows = rows,
            voterKey = guestId?.let(::requireGuestId)?.let(::keyForGuest),
            schoolId = null,
            gradeBand = gradeBand,
            subject = subject,
            cbcStrand = cbcStrand,
            search = search,
            sort = sort,
            pageRaw = pageRaw,
            limitRaw = limitRaw,
        )
    }

    @Transactional(readOnly = true)
    fun featuredPublic(guestId: String?, limitRaw: Int): List<CbcProjectPayload> {
        val limit = limitRaw.coerceIn(1, 50)
        val rows = projectRepository.findAllByStatusOrderByCreatedAtDesc(ProjectStatus.FEATURED).take(limit)
        val votes = votesFor(guestId?.let(::requireGuestId)?.let(::keyForGuest), rows.map { it.id })
        return rows.map { payload(it, votes[it.id]) }
    }

    @Transactional(readOnly = true)
    fun detailPublic(guestId: String?, projectIdRaw: String): CbcProjectPayload {
        val project = requirePublicProject(projectIdRaw)
        val vote = guestId?.let(::requireGuestId)?.let { votesFor(keyForGuest(it), listOf(project.id))[project.id] }
        return payload(project, vote)
    }

    @Transactional
    fun votePublic(guestIdRaw: String, projectIdRaw: String, request: CbcVoteRequest): PublicVoteResponsePayload {
        val guestId = requireGuestId(guestIdRaw)
        val project = requirePublicProject(projectIdRaw)
        val type = runCatching { VoteType.valueOf(request.voteType.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown vote type: " + request.voteType)
        applyVote(project, keyForGuest(guestId), type, null)
        projectRepository.save(project)
        return PublicVoteResponsePayload(project.id.toString(), project.upvotes, project.downvotes, type.name)
    }

    @Transactional
    fun removeVotePublic(guestIdRaw: String, projectIdRaw: String): PublicVoteResponsePayload {
        val guestId = requireGuestId(guestIdRaw)
        val project = requirePublicProject(projectIdRaw)
        applyRemoveVote(project, keyForGuest(guestId))
        return PublicVoteResponsePayload(project.id.toString(), project.upvotes, project.downvotes, null)
    }

    @Transactional(readOnly = true)
    fun commentsPublic(projectIdRaw: String, pageRaw: Int, limitRaw: Int): CommentListResponsePayload =
        commentsPage(requirePublicProject(projectIdRaw), pageRaw, limitRaw)

    @Transactional
    fun addCommentPublic(guestIdRaw: String, projectIdRaw: String, request: PublicCommentRequestPayload): ProjectCommentPayload {
        val guestId = requireGuestId(guestIdRaw)
        val project = requirePublicProject(projectIdRaw)
        val comment = saveComment(project, request.content, request.parentCommentId, request.mentions) {
            this.guestId = guestId
            userName = guestName(guestId)
            userRole = null
        }
        return commentPayload(comment, emptyList())
    }

    @Transactional
    fun trackPublic(guestIdRaw: String, projectIdRaw: String, request: PublicTrackRequestPayload): PublicTrackResponsePayload {
        val guestId = requireGuestId(guestIdRaw)
        val project = requirePublicProject(projectIdRaw)
        val email = request.email.trim().lowercase()
        if (!EMAIL_REGEX.matches(email)) throw invalidArgument("A valid email is required")
        val existing = trackRepository.findByProjectIdAndEmail(project.id, email)
        if (existing != null) {
            return PublicTrackResponsePayload(project.id.toString(), email, true, "You are already tracking this project.")
        }
        trackRepository.save(CbcProjectTrackEntity().apply {
            this.projectId = project.id
            this.email = email
            this.guestId = guestId
        })
        return PublicTrackResponsePayload(project.id.toString(), email, true, "You will be notified about updates.")
    }

    @Transactional
    fun reportPublic(guestIdRaw: String, projectIdRaw: String, request: PublicReportRequestPayload): PublicReportResponsePayload {
        val guestId = requireGuestId(guestIdRaw)
        val project = requirePublicProject(projectIdRaw)
        val reason = request.reason.trim()
        if (reason !in CBC_REPORT_REASONS) throw invalidArgument("Unknown report reason: " + request.reason)
        if (reportRepository.findByProjectIdAndGuestIdAndReason(project.id, guestId, reason) != null) {
            return PublicReportResponsePayload(project.id.toString(), true, "This project was already reported for that reason.")
        }
        reportRepository.save(CbcProjectReportEntity().apply {
            this.projectId = project.id
            this.guestId = guestId
            this.reason = reason
            details = request.details?.trim()?.takeIf { it.isNotEmpty() }
        })
        return PublicReportResponsePayload(project.id.toString(), true, "Thank you. Our moderators will review this project.")
    }

    @Transactional
    fun recordViewPublic(guestIdRaw: String?, projectIdRaw: String) {
        val project = requirePublicProject(projectIdRaw)
        val guestId = guestIdRaw?.takeIf { it.isNotBlank() }?.let(::requireGuestId)
        if (guestId == null) {
            project.viewCount += 1
            projectRepository.save(project)
            return
        }
        recordProjectView(project, keyForGuest(guestId), null)
    }

    // ------------------------------------------------------------ internals

    private fun paged(
        rows: List<CbcProjectEntity>,
        voterKey: String?,
        schoolId: String?,
        gradeBand: String?,
        subject: String?,
        cbcStrand: String?,
        search: String?,
        sort: String,
        pageRaw: Int,
        limitRaw: Int,
    ): ProjectListResponsePayload {
        val page = pageRaw.coerceAtLeast(1)
        val limit = limitRaw.coerceIn(1, 50)
        val school = schoolId?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "school id") }
        val filtered = rows
            .filter { gradeBand.isNullOrBlank() || it.gradeBand.equals(gradeBand, ignoreCase = true) }
            .filter { subject.isNullOrBlank() || it.subject.equals(subject, ignoreCase = true) }
            .filter { cbcStrand.isNullOrBlank() || it.cbcStrand.equals(cbcStrand, ignoreCase = true) }
            .filter { school == null || it.schoolId == school }
            .filter {
                search.isNullOrBlank() || it.title.contains(search, ignoreCase = true) ||
                    it.studentName.contains(search, ignoreCase = true) || it.schoolName.contains(search, ignoreCase = true)
            }
            .let { list ->
                when (sort.trim().lowercase()) {
                    "popular" -> list.sortedWith(compareByDescending<CbcProjectEntity> { it.upvotes - it.downvotes }.thenByDescending { it.createdAt })
                    "views" -> list.sortedWith(compareByDescending<CbcProjectEntity> { it.viewCount }.thenByDescending { it.createdAt })
                    "featured" -> list.sortedWith(compareByDescending<CbcProjectEntity> { it.status == ProjectStatus.FEATURED }.thenByDescending { it.createdAt })
                    else -> list.sortedByDescending { it.createdAt }
                }
            }
        val start = (page - 1) * limit
        val paged = filtered.drop(start).take(limit)
        val votes = votesFor(voterKey, paged.map { it.id })
        return ProjectListResponsePayload(
            projects = paged.map { payload(it, votes[it.id]) },
            total = filtered.size,
            page = page,
            hasMore = start + limit < filtered.size,
        )
    }

    private fun commentsPage(project: CbcProjectEntity, pageRaw: Int, limitRaw: Int): CommentListResponsePayload {
        val page = pageRaw.coerceAtLeast(1)
        val limit = limitRaw.coerceIn(1, 100)
        val all = commentRepository.findAllByProjectIdOrderByCreatedAtDesc(project.id)
        val repliesByParent = all.filter { it.parentCommentId != null }.groupBy { it.parentCommentId!! }
        val topLevel = all.filter { it.parentCommentId == null }
        val start = (page - 1) * limit
        val paged = topLevel.drop(start).take(limit)
        return CommentListResponsePayload(
            comments = paged.map { parent ->
                commentPayload(parent, repliesByParent[parent.id].orEmpty().sortedBy { it.createdAt }.map { commentPayload(it, emptyList()) })
            },
            total = topLevel.size,
            hasMore = start + limit < topLevel.size,
        )
    }

    private fun saveComment(
        project: CbcProjectEntity,
        contentRaw: String,
        parentCommentIdRaw: String?,
        mentions: List<String>,
        author: CbcProjectCommentEntity.() -> Unit,
    ): CbcProjectCommentEntity {
        val content = contentRaw.trim()
        if (content.isEmpty()) throw invalidArgument("comment must not be blank")
        val parentId = parentCommentIdRaw?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "parent comment id") }
        if (parentId != null && commentRepository.findByProjectIdAndId(project.id, parentId) == null) {
            throw notFound("Parent comment not found")
        }
        val comment = commentRepository.save(CbcProjectCommentEntity().apply {
            this.projectId = project.id
            author()
            this.content = content
            this.parentCommentId = parentId
            this.mentions = mapper.writeValueAsString(mentions)
        })
        project.commentCount += 1
        projectRepository.save(project)
        return comment
    }

    private fun applyVote(project: CbcProjectEntity, voterKey: String, type: VoteType, userId: UUID?): CbcProjectVoteEntity {
        val existing = voteRepository.findByProjectIdAndVoterKey(project.id, voterKey)
        if (existing == null) {
            increment(project, type)
            return voteRepository.save(CbcProjectVoteEntity().apply {
                this.projectId = project.id
                this.userId = userId
                this.voterKey = voterKey
                voteType = type
            })
        }
        if (existing.voteType != type) {
            decrement(project, existing.voteType)
            increment(project, type)
            existing.voteType = type
            existing.createdAt = clock.instant()
            voteRepository.save(existing)
        }
        return existing
    }

    private fun applyRemoveVote(project: CbcProjectEntity, voterKey: String) {
        val existing = voteRepository.findByProjectIdAndVoterKey(project.id, voterKey) ?: return
        decrement(project, existing.voteType)
        voteRepository.delete(existing)
        projectRepository.save(project)
    }

    private fun recordProjectView(project: CbcProjectEntity, viewerKey: String, userId: UUID?) {
        if (viewRepository.findByProjectIdAndViewerKey(project.id, viewerKey) != null) return
        viewRepository.save(CbcProjectViewEntity().apply {
            this.projectId = project.id
            this.userId = userId
            this.viewerKey = viewerKey
        })
        project.viewCount += 1
        projectRepository.save(project)
    }

    private fun payload(project: CbcProjectEntity, myVote: String?): CbcProjectPayload {
        val media = parseList(project.mediaUrls)
        return CbcProjectPayload(
            id = project.id.toString(),
            title = project.title,
            description = project.description,
            subject = project.subject,
            cbcStrand = project.cbcStrand,
            cbcSubStrand = project.cbcSubStrand,
            gradeBand = project.gradeBand,
            studentId = project.studentId.toString(),
            studentName = project.studentName,
            schoolId = project.schoolId?.toString() ?: "",
            schoolName = project.schoolName,
            schoolLogoUrl = project.schoolLogoUrl,
            mediaUrls = media,
            mediaTypes = media.map { if (isImage(it)) "IMAGE" else "VIDEO" },
            thumbnailUrl = project.thumbnailUrl,
            coverImageUrl = project.coverImageUrl,
            upvotes = project.upvotes,
            downvotes = project.downvotes,
            commentCount = project.commentCount,
            viewCount = project.viewCount,
            status = project.status.name,
            rubricScores = parseIntMap(project.rubricScores),
            tags = parseList(project.tags),
            isLiked = myVote == VoteType.UPVOTE.name,
            isDisliked = myVote == VoteType.DOWNVOTE.name,
            createdAt = project.createdAt.toEpochMilli(),
            updatedAt = project.updatedAt.toEpochMilli(),
        )
    }

    private fun commentPayload(comment: CbcProjectCommentEntity, replies: List<ProjectCommentPayload>): ProjectCommentPayload =
        ProjectCommentPayload(
            id = comment.id.toString(),
            projectId = comment.projectId.toString(),
            userId = comment.userId?.toString() ?: comment.guestId ?: "",
            userName = comment.userName,
            userRole = comment.userRole,
            content = comment.content,
            parentCommentId = comment.parentCommentId?.toString(),
            replies = replies,
            mentions = parseList(comment.mentions),
            createdAt = comment.createdAt.toEpochMilli(),
        )

    private fun votesFor(voterKey: String?, projectIds: List<UUID>): Map<UUID, String> {
        if (voterKey == null || projectIds.isEmpty()) return emptyMap()
        return voteRepository.findAllByVoterKeyAndProjectIdIn(voterKey, projectIds).associate { it.projectId to it.voteType.name }
    }

    private fun increment(project: CbcProjectEntity, type: VoteType) {
        if (type == VoteType.UPVOTE) project.upvotes += 1 else project.downvotes += 1
    }

    private fun decrement(project: CbcProjectEntity, type: VoteType) {
        if (type == VoteType.UPVOTE) project.upvotes = (project.upvotes - 1).coerceAtLeast(0)
        else project.downvotes = (project.downvotes - 1).coerceAtLeast(0)
    }

    private fun requireProject(raw: String): CbcProjectEntity =
        projectRepository.findById(parseUuid(raw, "project id")).orElse(null) ?: throw notFound("Project not found")

    private fun requirePublicProject(raw: String): CbcProjectEntity {
        val project = requireProject(raw)
        if (project.status != ProjectStatus.APPROVED && project.status != ProjectStatus.FEATURED) {
            throw notFound("Project not found")
        }
        return project
    }

    private fun requireGuestId(raw: String): String {
        val guestId = raw.trim()
        if (!GUEST_ID_REGEX.matches(guestId)) throw invalidArgument("X-Guest-Id must be 'guest_' followed by a UUID")
        return guestId
    }

    private fun guestName(guestId: String): String = "Guest " + (1000 + guestId.hashCode().absoluteValue % 9000)

    private fun keyForUser(userId: UUID): String = "user:" + userId
    private fun keyForGuest(guestId: String): String = "guest:" + guestId

    private fun user(userId: UUID): UserEntity = userRepository.findById(userId).orElseThrow { notFound("User not found") }

    private fun isStaff(current: CurrentUser): Boolean =
        current.role == Role.TEACHER || current.role == Role.ADMIN

    /** The client UserRole enum only has STUDENT, PARENT, TEACHER. */
    private fun apiRole(user: UserEntity): String = when (user.role) {
        Role.STUDENT -> "STUDENT"
        Role.PARENT -> "PARENT"
        else -> "TEACHER"
    }

    private fun isImage(url: String): Boolean {
        val lower = url.lowercase()
        return !(lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("vimeo.com") || lower.endsWith(".mp4"))
    }

    private fun parseList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).map { node.get(it).asString() }
    }

    private fun parseIntMap(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyMap()
        if (!node.isObject) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (entry in node.properties()) out[entry.key] = entry.value.intValue()
        return out
    }

    private fun parseUuid(raw: String, label: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument(label + " is not a valid identifier")

    private companion object {
        val GUEST_ID_REGEX = Regex("^guest_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val CBC_REPORT_REASONS = setOf(
            "Inappropriate content",
            "Copyright or plagiarism",
            "Spam or misleading",
            "Personal information",
            "Other",
        )
    }
}
