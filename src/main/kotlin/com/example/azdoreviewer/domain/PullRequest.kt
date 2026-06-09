package com.example.azdoreviewer.domain

data class PullRequest(
    val id: Int,
    val title: String,
    val description: String,
    val authorDisplayName: String,
    val status: PrStatus,
    val sourceBranch: String,
    val targetBranch: String,
    val repositoryId: String,
    val repositoryName: String,
    val projectName: String,
    val createdAt: String,
    val url: String,
    val authorImageUrl: String = "",
    val mergeStatus: String = "",                 // succeeded | conflicts | failure | queued | …
    val reviewers: List<Reviewer> = emptyList()
) {
    val canMerge: Boolean get() = mergeStatus.equals("succeeded", ignoreCase = true)
    override fun toString(): String = "#${id} — ${title}"
}

data class Reviewer(
    val id: String,
    val displayName: String,
    val vote: Int          // 10 approved, 5 approved w/ suggestions, 0 none, -5 waiting, -10 rejected
)
