package com.example.azdoreviewer.infrastructure.ai

import com.example.azdoreviewer.domain.ReviewComment

interface AiReviewProvider {
    val providerName: String
    suspend fun reviewFile(request: FileReviewRequest): List<ReviewComment>

    /**
     * Sends a minimal prompt to verify the key/model/endpoint work.
     * Returns the raw model text on success; throws (AiException) with the real reason on failure.
     */
    suspend fun ping(): String
}

data class FileReviewRequest(
    val filePath: String,
    val unifiedDiff: String,
    val fullFileContent: String?,
    val language: String
)
