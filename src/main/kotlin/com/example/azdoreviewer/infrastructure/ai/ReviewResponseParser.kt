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
        val cleaned = extractJsonArray(raw)
        return runCatching {
            json.decodeFromString<List<ReviewCommentDto>>(cleaned).map { it.toDomain(fallbackFile) }
        }.getOrElse { e ->
            thisLogger().warn("Failed to parse AI review for $fallbackFile: ${e.message}\nRaw: ${cleaned.take(200)}")
            emptyList()
        }
    }

    /** One verdict from the adversarial verify pass over an earlier finding. */
    data class Verdict(val index: Int, val verdict: String, val severity: Severity?, val reason: String = "")

    /** Parses the verify pass's JSON array. Never throws — returns empty on any parse failure. */
    fun parseVerdicts(raw: String): List<Verdict> {
        val cleaned = extractJsonArray(raw)
        return runCatching {
            json.decodeFromString<List<VerdictDto>>(cleaned).map {
                Verdict(it.index, it.verdict.trim().lowercase(), it.severity?.let(::parseSeverity), it.reason)
            }
        }.getOrElse { e ->
            thisLogger().warn("Failed to parse verify pass response: ${e.message}\nRaw: ${cleaned.take(200)}")
            emptyList()
        }
    }

    private fun extractJsonArray(raw: String): String = raw
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```")
        .trim()
        .let { s ->
            // Find the JSON array boundaries in case the model added prose before/after
            val start = s.indexOf('[')
            val end   = s.lastIndexOf(']')
            if (start >= 0 && end > start) s.substring(start, end + 1) else s
        }

    @Serializable
    private data class VerdictDto(
        val index: Int = -1,
        val verdict: String = "confirm",
        val severity: String? = null,
        val reason: String = ""
    )

    @Serializable
    private data class ReviewCommentDto(
        val file: String = "",
        val line: Int = 0,
        val endLine: Int = 0,
        val severity: String = "Info",
        val category: String = "CleanCode",
        val title: String = "",
        val comment: String = "",
        val suggestion: String = "",
        val suggestedCode: String = "",
        val originalCode: String = "",
        val friendlyComment: String = ""
    ) {
        fun toDomain(fallbackFile: String) = ReviewComment(
            file            = file.ifBlank { fallbackFile },
            line            = line,
            endLine         = endLine,
            severity        = parseSeverity(severity),
            category        = parseCategory(category),
            title           = title,
            comment         = comment,
            suggestion      = suggestion,
            suggestedCode   = suggestedCode,
            originalCode    = originalCode,
            friendlyComment = friendlyComment
        )
    }

    private fun parseSeverity(s: String): Severity {
        val v = s.trim().lowercase()
        return when {
            v.startsWith("crit") || v == "blocker" -> Severity.CRITICAL
            v.startsWith("high") || v == "major" || v == "error" -> Severity.HIGH
            v.startsWith("med")  || v == "moderate" || v == "warning" -> Severity.MEDIUM
            v.startsWith("low")  || v == "minor" -> Severity.LOW
            v == "info" || v == "informational" || v == "note" || v == "nit" || v == "trivial" -> Severity.INFO
            // Unknown/blank → MEDIUM (a safer default than INFO so real issues aren't hidden).
            else -> Severity.MEDIUM
        }
    }

    private fun parseCategory(s: String): ReviewCategory {
        val v = s.lowercase()
        return when {
            v.contains("security")                       -> ReviewCategory.SECURITY
            v.contains("perf")                           -> ReviewCategory.PERFORMANCE
            v.contains("async") || v.contains("await") ||
                v.contains("concurren") || v.contains("thread") -> ReviewCategory.CONCURRENCY
            v.contains("ddd")                            -> ReviewCategory.DDD
            v.contains("solid") || v.contains("dip") || v.contains("srp") -> ReviewCategory.SOLID
            v.contains("test")                           -> ReviewCategory.MISSING_TESTS
            v.contains("maintain")                       -> ReviewCategory.MAINTAINABILITY
            // Bugs: null safety, EF Core, DI lifetime, exception handling all describe defects.
            v.contains("null") || v.contains("ef ") || v.contains("efcore") ||
                v.contains("ef core") || v.contains("lifetime") || v.contains("dispos") ||
                v.contains("exception") || v.contains("bug")     -> ReviewCategory.BUG
            v.contains("naming") || v.contains("clean") || v.contains("style") -> ReviewCategory.CLEAN_CODE
            else                                         -> ReviewCategory.CLEAN_CODE
        }
    }
}
