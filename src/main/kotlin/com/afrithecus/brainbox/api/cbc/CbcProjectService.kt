package com.afrithecus.brainbox.api.cbc

import com.afrithecus.brainbox.api.cbc.entity.CbcProjectCommentEntity
import com.afrithecus.brainbox.api.cbc.entity.CbcProjectEntity
import com.afrithecus.brainbox.api.cbc.entity.CbcProjectViewEntity
import com.afrithecus.brainbox.api.cbc.entity.CbcProjectVoteEntity
import com.afrithecus.brainbox.api.cbc.model.ProjectStatus
import com.afrithecus.brainbox.api.cbc.model.VoteType
import com.afrithecus.brainbox.api.cbc.repository.CbcProjectCommentRepository
import com.afrithecus.brainbox.api.cbc.repository.CbcProjectRepository
import com.afrithecus.brainbox.api.cbc.repository.CbcProjectViewRepository
import com.afrithecus.brainbox.api.cbc.repository.CbcProjectVoteRepository
import com.afrithecus.brainbox.api.cbc.web.AddProjectCommentRequest
import com.afrithecus.brainbox.api.cbc.web.CbcProjectPayload
import com.afrithecus.brainbox.api.cbc.web.CbcVoteRequest
import com.afrithecus.brainbox.api.cbc.web.CommentListResponsePayload
import com.afrithecus.brainbox.api.cbc.web.ProjectCommentPayload
import com.afrithecus.brainbox.api.cbc.web.ProjectListResponsePayload
import com.afrithecus.brainbox.api.cbc.web.ProjectVotePayload
import com.afrithecus.brainbox.api.cbc.web.SubmitCbcProjectRequest
import com.afrithecus.brainbox.api.cbc.web.UpdateProjectStatusRequest
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
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

/**
 * CBC project showcase (doc 06 §3): listing/feeds, submission, moderation,
 * voting, comments and unique view counts. The server derives author identity,
 * media types and all counters; the client renders them read-only.
 */
@Service
class CbcProjectService(
    private val projectRepository: CbcProjectRepository,
    private val voteRepository: CbcProjectVoteRepository,
    private val commentRepository: CbcProjectCommentRepository,
    private val viewRepository: CbcProjectViewRepository,
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

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
        val page = pageRaw.coerceAtLeast(1)
        val limit = limitRaw.coerceIn(1, 100)
        val school = schoolId?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "school id") }
        val filtered = projectRepository.findAllByStatusOrderByCreatedAtDesc(status)
            .filter { gradeBand.isNullOrBlank() || it.gradeBand.equals(gradeBand, ignoreCase = true) }
            .filter { subject.isNullOrBlank() || it.subject.equals(subject, ignoreCase = true) }
            .filter { cbcStrand.isNullOrBlank() || it.cbcStrand.equals(cbcStrand, ignoreCase = true) }
            .filter { school == null || it.schoolId == school }
            .filter { search.isNullOrBlank() || it.title.contains(search, ignoreCase = true) || it.studentName.contains(search, ignoreCase = true) }
            .let { rows ->
                when (sort.trim().lowercase()) {
                    "popular" -> rows.sortedWith(compareByDescending<CbcProjectEntity> { it.upvotes - it.downvotes }.thenByDescending { it.createdAt })
                    "featured" -> rows.sortedWith(compareByDescending<CbcProjectEntity> { it.status == ProjectStatus.FEATURED }.thenByDescending { it.createdAt })
                    else -> rows.sortedByDescending { it.createdAt }
                }
            }
        val start = (page - 1) * limit
        val paged = filtered.drop(start).take(limit)
        val votes = currentVotes(current.userId, paged.map { it.id })
        return ProjectListResponsePayload(
            projects = paged.map { payload(it, votes) },
            total = filtered.size,
            page = page,
            hasMore = start + limit < filtered.size,
        )
    }

    @Transactional(readOnly = true)
    fun featured(limitRaw: Int): List<CbcProjectPayload> {
        val limit = limitRaw.coerceIn(1, 50)
        return projectRepository.findAllByStatusOrderByCreatedAtDesc(ProjectStatus.FEATURED).take(limit).map { payload(it, emptyMap()) }
    }

    @Transactional(readOnly = true)
    fun mine(current: CurrentUser): List<CbcProjectPayload> =
        projectRepository.findAllByStudentIdOrderByCreatedAtDesc(current.userId).map { payload(it, currentVotes(current.userId, listOf(it.id))) }

    @Transactional(readOnly = true)
    fun detail(current: CurrentUser, projectIdRaw: String): CbcProjectPayload {
        val project = requireProject(projectIdRaw)
        if (project.status == ProjectStatus.REMOVED && project.studentId != current.userId && !isStaff(current)) {
            throw notFound("Project not found")
        }
        return payload(project, currentVotes(current.userId, listOf(project.id)))
    }

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
        return payload(project, emptyMap())
    }

    @Transactional
    fun updateStatus(current: CurrentUser, projectIdRaw: String, request: UpdateProjectStatusRequest): CbcProjectPayload {
        if (!isStaff(current)) throw ApiException(ApiErrorCode.FORBIDDEN, "Only staff can moderate projects")
        val project = requireProject(projectIdRaw)
        project.status = runCatching { ProjectStatus.valueOf(request.status.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown project status: " + request.status)
        return payload(projectRepository.save(project), emptyMap())
    }

    @Transactional
    fun vote(current: CurrentUser, projectIdRaw: String, request: CbcVoteRequest): ProjectVotePayload {
        val project = requireProject(projectIdRaw)
        val type = runCatching { VoteType.valueOf(request.voteType.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown vote type: " + request.voteType)
        val existing = voteRepository.findByProjectIdAndUserId(project.id, current.userId)
        if (existing == null) {
            increment(project, type)
        } else if (existing.voteType != type) {
            decrement(project, existing.voteType)
            increment(project, type)
            existing.voteType = type
            existing.createdAt = clock.instant()
            voteRepository.save(existing)
        }
        val vote = existing ?: voteRepository.save(CbcProjectVoteEntity().apply {
            this.projectId = project.id
            this.userId = current.userId
            voteType = type
        })
        projectRepository.save(project)
        return ProjectVotePayload(project.id.toString(), current.userId.toString(), type.name, vote.createdAt.toEpochMilli())
    }

    @Transactional
    fun removeVote(current: CurrentUser, projectIdRaw: String) {
        val project = requireProject(projectIdRaw)
        val existing = voteRepository.findByProjectIdAndUserId(project.id, current.userId) ?: return
        decrement(project, existing.voteType)
        voteRepository.delete(existing)
        projectRepository.save(project)
    }

    @Transactional(readOnly = true)
    fun comments(projectIdRaw: String, pageRaw: Int, limitRaw: Int): CommentListResponsePayload {
        val project = requireProject(projectIdRaw)
        val page = pageRaw.coerceAtLeast(1)
        val limit = limitRaw.coerceIn(1, 100)
        val all = commentRepository.findAllByProjectIdOrderByCreatedAtDesc(project.id)
        val repliesByParent = all.filter { it.parentCommentId != null }.groupBy { it.parentCommentId!! }
        val topLevel = all.filter { it.parentCommentId == null }
        val start = (page - 1) * limit
        val paged = topLevel.drop(start).take(limit)
        return CommentListResponsePayload(
            comments = paged.map { payload(it, repliesByParent[it.id].orEmpty().sortedBy { reply -> reply.createdAt }.map { reply -> payload(reply, emptyList()) }) },
            total = topLevel.size,
            hasMore = start + limit < topLevel.size,
        )
    }

    @Transactional
    fun addComment(current: CurrentUser, projectIdRaw: String, request: AddProjectCommentRequest): ProjectCommentPayload {
        val project = requireProject(projectIdRaw)
        val parentId = request.parentCommentId?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "parent comment id") }
        if (parentId != null && commentRepository.findByProjectIdAndId(project.id, parentId) == null) {
            throw notFound("Parent comment not found")
        }
        val user = user(current.userId)
        val comment = commentRepository.save(CbcProjectCommentEntity().apply {
            this.projectId = project.id
            this.userId = user.id
            userName = user.name
            userRole = apiRole(user)
            content = request.content.trim()
            parentCommentId = parentId
            mentions = mapper.writeValueAsString(request.mentions)
        })
        project.commentCount += 1
        projectRepository.save(project)
        return payload(comment, emptyList())
    }

    @Transactional
    fun recordView(current: CurrentUser, projectIdRaw: String) {
        val project = requireProject(projectIdRaw)
        if (viewRepository.findByProjectIdAndUserId(project.id, current.userId) != null) return
        viewRepository.save(CbcProjectViewEntity().apply {
            this.projectId = project.id
            this.userId = current.userId
        })
        project.viewCount += 1
        projectRepository.save(project)
    }

    // ------------------------------------------------------------ internals

    private fun payload(project: CbcProjectEntity, votes: Map<UUID, VoteType>): CbcProjectPayload {
        val media = parseList(project.mediaUrls)
        val myVote = votes[project.id]
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
            isLiked = myVote == VoteType.UPVOTE,
            isDisliked = myVote == VoteType.DOWNVOTE,
            createdAt = project.createdAt.toEpochMilli(),
            updatedAt = project.updatedAt.toEpochMilli(),
        )
    }

    private fun payload(comment: CbcProjectCommentEntity, replies: List<ProjectCommentPayload>): ProjectCommentPayload =
        ProjectCommentPayload(
            id = comment.id.toString(),
            projectId = comment.projectId.toString(),
            userId = comment.userId.toString(),
            userName = comment.userName,
            userRole = comment.userRole,
            content = comment.content,
            parentCommentId = comment.parentCommentId?.toString(),
            replies = replies,
            mentions = parseList(comment.mentions),
            createdAt = comment.createdAt.toEpochMilli(),
        )

    private fun currentVotes(userId: UUID, projectIds: List<UUID>): Map<UUID, VoteType> {
        if (projectIds.isEmpty()) return emptyMap()
        return voteRepository.findAllByUserIdAndProjectIdIn(userId, projectIds).associate { it.projectId to it.voteType }
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
}
