package com.example.azdoreviewer.infrastructure.ai

import com.example.azdoreviewer.domain.RequirementFinding
import com.example.azdoreviewer.domain.WorkItemValidation
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object WorkItemValidationParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): WorkItemValidation {
        val cleaned = raw
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { s ->
                val start = s.indexOf('{')
                val end   = s.lastIndexOf('}')
                if (start >= 0 && end > start) s.substring(start, end + 1) else s
            }

        return runCatching {
            json.decodeFromString<ValidationDto>(cleaned).toDomain()
        }.getOrElse { e ->
            thisLogger().warn("Failed to parse work item validation: ${e.message}\nRaw: ${cleaned.take(200)}")
            WorkItemValidation(
                verdict  = "Unclear",
                findings = emptyList(),
                summary  = "Couldn't parse the AI's response. Raw reply: ${cleaned.take(300)}"
            )
        }
    }

    @Serializable
    private data class ValidationDto(
        val verdict: String = "Unclear",
        val findings: List<FindingDto> = emptyList(),
        val summary: String = ""
    ) {
        fun toDomain() = WorkItemValidation(verdict, findings.map { it.toDomain() }, summary)
    }

    @Serializable
    private data class FindingDto(
        val requirement: String = "",
        val status: String = "Unclear",
        val explanation: String = "",
        val file: String = "",
        val line: Int = 0,
        val friendlyComment: String = ""
    ) {
        fun toDomain() = RequirementFinding(requirement, status, explanation, file, line, friendlyComment)
    }
}
