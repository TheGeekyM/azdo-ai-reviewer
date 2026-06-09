package com.example.azdoreviewer.infrastructure.azdo.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.example.azdoreviewer.domain.*

@Serializable
data class PrListResponse(
    val value: List<PrDto> = emptyList(),
    val count: Int = 0
)

@Serializable
data class PrDto(
    @SerialName("pullRequestId") val id: Int,
    val title: String = "",
    val description: String = "",
    @SerialName("createdBy") val createdBy: IdentityDto = IdentityDto(),
    val status: String = "active",
    @SerialName("sourceRefName") val sourceRefName: String = "",
    @SerialName("targetRefName") val targetRefName: String = "",
    @SerialName("repository") val repository: RepositoryDto = RepositoryDto(),
    @SerialName("creationDate") val creationDate: String = "",
    @SerialName("mergeStatus") val mergeStatus: String = "",
    val reviewers: List<ReviewerDto> = emptyList(),
    val url: String = ""
) {
    fun toDomain() = PullRequest(
        id               = id,
        title            = title,
        description      = description,
        authorDisplayName = createdBy.displayName,
        status           = when (status.lowercase()) {
            "completed"  -> PrStatus.COMPLETED
            "abandoned"  -> PrStatus.ABANDONED
            else         -> PrStatus.ACTIVE
        },
        sourceBranch     = sourceRefName.removePrefix("refs/heads/"),
        targetBranch     = targetRefName.removePrefix("refs/heads/"),
        repositoryId     = repository.id,
        repositoryName   = repository.name,
        projectName      = repository.project?.name ?: "",
        createdAt        = creationDate,
        url              = url,
        authorImageUrl   = createdBy.imageUrl,
        mergeStatus      = mergeStatus,
        reviewers        = reviewers.map { Reviewer(it.id, it.displayName, it.vote) }
    )
}

@Serializable
data class ReviewerDto(
    val id: String = "",
    @SerialName("displayName") val displayName: String = "",
    val vote: Int = 0
)

@Serializable
data class IdentityDto(
    @SerialName("displayName") val displayName: String = "",
    val id: String = "",
    @SerialName("imageUrl") val imageUrl: String = ""
)

@Serializable
data class RepositoryDto(
    val id: String = "",
    val name: String = "",
    val project: ProjectRefDto? = null
)

@Serializable
data class ProjectRefDto(
    val id: String = "",
    val name: String = ""
)

@Serializable
data class ProfileResponse(
    val id: String = "",
    @SerialName("displayName") val displayName: String = ""
)

@Serializable
data class ConnectionDataResponse(
    @SerialName("authenticatedUser") val authenticatedUser: AuthenticatedUser? = null,
    @SerialName("instanceId") val instanceId: String = ""
)

@Serializable
data class AuthenticatedUser(
    val id: String = "",
    @SerialName("providerDisplayName") val displayName: String = ""
)

@Serializable
data class IterationListResponse(
    val value: List<IterationDto> = emptyList()
)

@Serializable
data class IterationDto(
    val id: Int,
    val description: String = "",
    @SerialName("sourceRefCommit") val sourceRefCommit: CommitRefDto? = null,
    @SerialName("targetRefCommit") val targetRefCommit: CommitRefDto? = null,
    @SerialName("commonRefCommit") val commonRefCommit: CommitRefDto? = null
)

@Serializable
data class CommitRefDto(
    @SerialName("commitId") val commitId: String = ""
)

@Serializable
data class IterationChangesResponse(
    @SerialName("changeEntries") val changeEntries: List<ChangeEntryDto> = emptyList()
)

@Serializable
data class ChangeEntryDto(
    @SerialName("changeType") val changeType: String = "edit",
    val item: ItemDto = ItemDto()
)

@Serializable
data class ItemDto(
    val path: String = "",
    @SerialName("objectId") val objectId: String = "",
    @SerialName("originalObjectId") val originalObjectId: String = ""
)

@Serializable
data class CommentThreadRequest(
    val comments: List<CommentRequest>,
    val status: String = "active",
    val threadContext: ThreadContext? = null
)

@Serializable
data class CommentRequest(
    val content: String,
    @SerialName("commentType") val commentType: Int = 1
)

@Serializable
data class ThreadContext(
    val filePath: String,
    @SerialName("rightFileStart") val rightFileStart: FilePosition,
    @SerialName("rightFileEnd") val rightFileEnd: FilePosition
)

@Serializable
data class FilePosition(
    val line: Int,
    val offset: Int = 1
)
