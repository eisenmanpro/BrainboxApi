package com.afrithecus.brainbox.api.cbc.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

/** CBC project payloads matching the Android CbcProjectModels exactly. */

data class CbcProjectPayload(
    val id: String,
    val title: String,
    val description: String,
    val subject: String,
    val cbcStrand: String,
    val cbcSubStrand: String,
    val gradeBand: String,
    val studentId: String,
    val studentName: String,
    val studentAvatarUrl: String? = null,
    val schoolId: String,
    val schoolName: String,
    val schoolLogoUrl: String? = null,
    val mediaUrls: List<String> = emptyList(),
    val mediaTypes: List<String> = emptyList(),
    val thumbnailUrl: String? = null,
    val coverImageUrl: String? = null,
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    val commentCount: Int = 0,
    val viewCount: Int = 0,
    val status: String = "PENDING",
    val rubricScores: Map<String, Int> = emptyMap(),
    val tags: List<String> = emptyList(),
    val isLiked: Boolean = false,
    val isDisliked: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

data class ProjectListResponsePayload(
    val projects: List<CbcProjectPayload>,
    val total: Int,
    val page: Int,
    val hasMore: Boolean,
)

data class ProjectCommentPayload(
    val id: String,
    val projectId: String,
    val userId: String,
    val userName: String,
    val userAvatarUrl: String? = null,
    /** Null for anonymous (guest) commenters. */
    val userRole: String? = null,
    val content: String,
    val parentCommentId: String? = null,
    val replies: List<ProjectCommentPayload> = emptyList(),
    val mentions: List<String> = emptyList(),
    val createdAt: Long = 0L,
)

data class CommentListResponsePayload(
    val comments: List<ProjectCommentPayload>,
    val total: Int,
    val hasMore: Boolean,
)

data class ProjectVotePayload(
    val projectId: String,
    val userId: String,
    val voteType: String,
    val createdAt: Long,
)

data class SubmitCbcProjectRequest(
    @field:NotBlank val title: String,
    @field:NotBlank val description: String,
    @field:NotBlank val subject: String,
    val cbcStrand: String = "",
    val cbcSubStrand: String = "",
    @field:NotBlank val gradeBand: String,
    val mediaUrls: List<String> = emptyList(),
    val rubricScores: Map<String, Int> = emptyMap(),
    val tags: List<String> = emptyList(),
)

data class UpdateProjectStatusRequest(@field:NotBlank val status: String)

data class CbcVoteRequest(@field:NotBlank val voteType: String)

data class AddProjectCommentRequest(
    @field:NotBlank val content: String,
    val parentCommentId: String? = null,
    val mentions: List<String> = emptyList(),
    /** Accepted for client compatibility but ignored: the server uses the JWT identity. */
    val userId: String? = null,
    val userName: String? = null,
    val userRole: String? = null,
)

/** Public (guest) engagement payloads (docs/ongoing/api_cbc_public_changes.md). */
data class PublicVoteResponsePayload(
    val projectId: String,
    val upvotes: Int,
    val downvotes: Int,
    val userVote: String? = null,
)

data class PublicCommentRequestPayload(
    @field:NotBlank @field:Size(max = 2000) val content: String,
    val parentCommentId: String? = null,
    val mentions: List<String> = emptyList(),
)

data class PublicTrackRequestPayload(
    @field:NotBlank @field:Size(max = 200) val email: String,
)

data class PublicTrackResponsePayload(
    val projectId: String,
    val email: String,
    val tracked: Boolean,
    val message: String? = null,
)

data class PublicReportRequestPayload(
    @field:NotBlank @field:Size(max = 64) val reason: String,
    @field:Size(max = 2000) val details: String? = null,
)

data class PublicReportResponsePayload(
    val projectId: String,
    val reported: Boolean,
    val message: String? = null,
)

data class MediaUploadResponsePayload(
    val url: String,
    val mediaType: String = "IMAGE",
)
