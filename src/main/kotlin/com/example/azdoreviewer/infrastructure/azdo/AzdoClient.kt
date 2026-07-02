package com.example.azdoreviewer.infrastructure.azdo

import com.example.azdoreviewer.domain.FileDiff
import com.example.azdoreviewer.domain.PullRequest
import com.example.azdoreviewer.domain.WorkItem
import com.example.azdoreviewer.infrastructure.azdo.dto.CommentThreadRequest

interface AzdoClient {
    fun fetchAssignedPrs(): List<PullRequest>
    fun fetchPrDiffs(prId: Int): List<FileDiff>
    fun fetchFileContent(repoId: String, path: String, commitSha: String): String?
    fun createCommentThread(prId: Int, request: CommentThreadRequest)
    fun getCurrentUserId(): String
    /** Fetches a work item (user story/task/bug) by its numeric ID. */
    fun fetchWorkItem(workItemId: Int): WorkItem
    /** IDs of work items linked to this PR, if any. */
    fun fetchLinkedWorkItemIds(prId: Int): List<Int>
    /** Sets the current user's vote on the PR (10 = approved, 5 = approved w/ suggestions, -10 = rejected). */
    fun votePr(prId: Int, vote: Int)
    /** Completes (merges) the PR. */
    fun completePr(prId: Int)
    fun testConnection(): Boolean
}
