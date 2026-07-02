package com.example.azdoreviewer.infrastructure.ai

import com.example.azdoreviewer.domain.ReviewComment

interface AiReviewProvider {
    val providerName: String

    /** Reviews a single file in isolation (used by ping/back-compat). */
    suspend fun reviewFile(request: FileReviewRequest): List<ReviewComment>

    /**
     * Reviews a whole PR (or a batch of its files) in ONE call, so the model can reason across
     * files and use surrounding code to judge whether each change is actually correct.
     */
    suspend fun reviewPullRequest(request: PrReviewRequest): List<ReviewComment>

    /**
     * Sends a minimal prompt to verify the key/model/endpoint work.
     * Returns the raw model text on success; throws (AiException) with the real reason on failure.
     */
    suspend fun ping(): String

    /** Lists the model IDs this account can use. Empty if the provider has no list endpoint. */
    suspend fun listModels(): List<String> = emptyList()

    /** Sends a raw prompt and returns the model's raw text reply (used for the verify pass). */
    suspend fun complete(prompt: String, maxTokens: Int = 4096): String
}

data class FileReviewRequest(
    val filePath: String,
    val unifiedDiff: String,
    val fullFileContent: String?,
    val language: String
)

/** A whole-PR (or batched) review request — all changed files seen together with PR intent. */
data class PrReviewRequest(
    val prTitle: String,
    val prDescription: String,
    val language: String,
    val files: List<PrFile>
)

data class PrFile(
    val path: String,
    val unifiedDiff: String,
    val fullFileContent: String?
)
