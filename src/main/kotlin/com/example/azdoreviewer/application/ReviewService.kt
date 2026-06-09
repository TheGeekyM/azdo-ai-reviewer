package com.example.azdoreviewer.application

import com.example.azdoreviewer.domain.ChangeType
import com.example.azdoreviewer.domain.ReviewComment
import com.example.azdoreviewer.infrastructure.ai.AiProviderFactory
import com.example.azdoreviewer.infrastructure.ai.FileReviewRequest
import com.example.azdoreviewer.infrastructure.cache.PrCacheService
import com.example.azdoreviewer.settings.AzdoSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

@Service(Service.Level.APP)
class ReviewService {

    private val settings  get() = AzdoSettings.getInstance()
    private val prService get() = service<PrService>()
    private val cache     get() = service<PrCacheService>()

    fun reviewPr(prId: Int, forceRefresh: Boolean = false): List<ReviewComment> {
        if (forceRefresh) cache.invalidatePr(prId)

        // NOTE: not cached here — we want errors to surface, and caching empty error
        // results would hide a broken key on the next run.
        val provider = AiProviderFactory.create(settings)
        val diffs = prService.getPrDiffs(prId)
            .filter { it.changeType != ChangeType.DELETE }

        if (diffs.isEmpty()) {
            throw IllegalStateException(
                "No changed files found for PR #$prId.\n" +
                "The diff fetch returned nothing — open the 'Files Changed' tab first to confirm files load."
            )
        }

        val errors = mutableListOf<String>()
        val results = runBlocking {
            diffs.map { diff ->
                async {
                    runCatching {
                        provider.reviewFile(
                            FileReviewRequest(
                                filePath        = diff.path,
                                unifiedDiff     = diff.toUnifiedDiff(),
                                // Pass the full new file so the AI understands how the change fits
                                // (the prompt instructs it to review ONLY the changed +lines).
                                fullFileContent = diff.newContent.ifBlank { null },
                                language        = detectLanguage(diff.path)
                            )
                        )
                    }.getOrElse { e ->
                        thisLogger().warn("Review failed for ${diff.path}: ${e.message}")
                        synchronized(errors) { errors.add("${diff.path.substringAfterLast('/')}: ${e.message}") }
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        // If EVERY file failed, the API/key is broken — surface the first error instead of "no issues".
        if (results.isEmpty() && errors.isNotEmpty()) {
            throw IllegalStateException("AI review failed on all files.\n\nFirst error:\n${errors.first()}")
        }

        val sorted = results.sortedWith(compareBy({ it.severity.ordinal }, { it.file }, { it.line }))
        cache.invalidatePr(prId) // ensure fresh next time
        return sorted
    }

    /** Sends a tiny request to verify the AI key/model works. Throws with the real reason on failure. */
    fun testAiConnection(): String {
        val provider = AiProviderFactory.create(settings)
        return runBlocking {
            provider.ping()
            "✓ ${settings.state.aiProvider} key works (model: ${settings.state.aiModel.ifBlank { "default" }})"
        }
    }

    private fun detectLanguage(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "cs"   -> "csharp"
        "fs"   -> "fsharp"
        "vb"   -> "vb"
        "ts"   -> "typescript"
        "tsx"  -> "typescript"
        "js"   -> "javascript"
        "jsx"  -> "javascript"
        "py"   -> "python"
        "go"   -> "go"
        "java" -> "java"
        "kt"   -> "kotlin"
        "rs"   -> "rust"
        "cpp", "cc", "cxx" -> "cpp"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "xml"  -> "xml"
        "sql"  -> "sql"
        else   -> "text"
    }
}
