package com.example.azdoreviewer.infrastructure.ai

import com.example.azdoreviewer.domain.ReviewCategory
import com.example.azdoreviewer.domain.ReviewComment
import com.example.azdoreviewer.domain.Severity
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object ReviewResponseParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String, fallbackFile: String): List<ReviewComment> {
        val cleaned = raw
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { s ->
                // Find the JSON array boundaries in case the model added prose before/after
                val start = s.indexOf('[')
                val end   = s.lastIndexOf(']')
                if (start >= 0 && end > start) s.substring(start, end + 1) else s
            }

        return runCatching {
            json.decodeFromString<List<ReviewCommentDto>>(cleaned).map { it.toDomain(fallbackFile) }
        }.getOrElse { e ->
            thisLogger().warn("Failed to parse AI review for $fallbackFile: ${e.message}\nRaw: ${cleaned.take(200)}")
            emptyList()
        }
    }

    @Serializable
    private data class ReviewCommentDto(
        val file: String = "",
        val line: Int = 0,
        val endLine: Int = 0,
        val severity: String = "Info",
        val category: String = "CleanCode",
        val comment: String = "",
        val suggestion: String = "",
        val suggestedCode: String = "",
        val friendlyComment: String = ""
    ) {
        fun toDomain(fallbackFile: String) = ReviewComment(
            file            = file.ifBlank { fallbackFile },
            line            = line,
            endLine         = endLine,
            severity        = parseSeverity(severity),
            category        = parseCategory(category),
            comment         = comment,
            suggestion      = suggestion,
            suggestedCode   = suggestedCode,
            friendlyComment = friendlyComment
        )
    }

    private fun parseSeverity(s: String): Severity = when (s.lowercase()) {
        "critical" -> Severity.CRITICAL
        "high"     -> Severity.HIGH
        "medium"   -> Severity.MEDIUM
        "low"      -> Severity.LOW
        else       -> Severity.INFO
    }

    private fun parseCategory(s: String): ReviewCategory = when (s.lowercase()) {
        "bug"           -> ReviewCategory.BUG
        "security"      -> ReviewCategory.SECURITY
        "performance"   -> ReviewCategory.PERFORMANCE
        "cleancode", "clean_code" -> ReviewCategory.CLEAN_CODE
        "solid"         -> ReviewCategory.SOLID
        "ddd"           -> ReviewCategory.DDD
        "maintainability" -> ReviewCategory.MAINTAINABILITY
        "missingtests", "missing_tests" -> ReviewCategory.MISSING_TESTS
        "concurrency"   -> ReviewCategory.CONCURRENCY
        else            -> ReviewCategory.CLEAN_CODE
    }
}
