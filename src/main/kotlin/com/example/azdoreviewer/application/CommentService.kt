package com.example.azdoreviewer.application

import com.example.azdoreviewer.domain.ReviewComment
import com.example.azdoreviewer.infrastructure.azdo.dto.*
import com.example.azdoreviewer.infrastructure.cache.PrCacheService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
class CommentService {

    private val cache     get() = service<PrCacheService>()
    private val prService get() = service<PrService>()

    fun postLineComment(prId: Int, comment: ReviewComment) {
        postLineComment(prId, comment, formatComment(comment))
    }

    /** Posts a line comment using caller-supplied (possibly edited) body text. */
    fun postLineComment(prId: Int, comment: ReviewComment, bodyText: String) {
        prService.createCommentThread(prId, CommentThreadRequest(
            comments      = listOf(CommentRequest(content = bodyText)),
            status        = "active",
            threadContext = ThreadContext(
                filePath       = "/${comment.file.trimStart('/')}",
                rightFileStart = FilePosition(line = comment.line.coerceAtLeast(1), offset = 1),
                rightFileEnd   = FilePosition(line = comment.line.coerceAtLeast(1), offset = 1)
            )
        ))
        cache.invalidatePr(prId)
    }

    /** The default formatted body for a comment — used to seed the editable preview. */
    fun previewComment(comment: ReviewComment): String = formatComment(comment)

    fun postAllComments(prId: Int, comments: List<ReviewComment>) {
        comments.forEach { comment ->
            runCatching { postLineComment(prId, comment) }
        }
        postSummary(prId, comments)
    }

    /** Sets the current user's vote (10 approve, 5 approve+suggestions, -5 wait, -10 reject). */
    fun votePr(prId: Int, vote: Int) = prService.votePr(prId, vote)

    /** Completes (merges) the PR. */
    fun completePr(prId: Int) = prService.completePr(prId)

    fun postSummary(prId: Int, comments: List<ReviewComment>) {
        prService.createCommentThread(prId, CommentThreadRequest(
            comments = listOf(CommentRequest(content = buildSummary(comments))),
            status   = "active"
        ))
    }

    /**
     * Formats a comment to read like a friendly human reviewer note.
     * No severity/category tags, no AI/tool mention, no signature.
     */
    private fun formatComment(c: ReviewComment) = buildString {
        // Prefer the AI's warm, ready-to-post message; fall back to comment+suggestion.
        if (c.friendlyComment.isNotBlank()) {
            append(c.friendlyComment.trim())
        } else {
            append(c.comment.trim())
            if (c.suggestion.isNotBlank()) {
                append("\n\n")
                append(c.suggestion.trim())
            }
        }

        // Optional concrete code, introduced casually.
        if (c.suggestedCode.isNotBlank()) {
            append("\n\nSomething like this might work:\n\n")
            append("```\n")
            append(c.suggestedCode.trim())
            append("\n```")
        }
    }

    private fun buildSummary(comments: List<ReviewComment>): String = buildString {
        val n = comments.size
        if (n == 0) {
            append("Took a pass through the changes — looks good to me 👍")
            return@buildString
        }
        append("Left a few comments on the changes — ")
        append(if (n == 1) "one thing" else "$n things")
        append(" worth a look when you get a chance. Nothing blocking, just want to make sure we're on the same page. 🙂")
    }
}
