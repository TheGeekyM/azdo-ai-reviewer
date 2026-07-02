package com.example.azdoreviewer.application

import com.example.azdoreviewer.domain.ChangeType
import com.example.azdoreviewer.domain.ReviewComment
import com.example.azdoreviewer.infrastructure.ai.AiProviderFactory
import com.example.azdoreviewer.infrastructure.ai.AiReviewProvider
import com.example.azdoreviewer.infrastructure.ai.PrFile
import com.example.azdoreviewer.infrastructure.ai.PrReviewRequest
import com.example.azdoreviewer.infrastructure.ai.PromptBuilder
import com.example.azdoreviewer.infrastructure.ai.ReviewResponseParser
import com.example.azdoreviewer.infrastructure.ai.WorkItemValidationParser
import com.example.azdoreviewer.domain.WorkItem
import com.example.azdoreviewer.domain.WorkItemValidation
import com.example.azdoreviewer.infrastructure.analyzer.RoslynAnalyzerRunner
import com.example.azdoreviewer.infrastructure.cache.PrCacheService
import com.example.azdoreviewer.settings.AzdoSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File

@Service(Service.Level.APP)
class ReviewService {

    private val settings  get() = AzdoSettings.getInstance()
    private val prService get() = service<PrService>()
    private val cache     get() = service<PrCacheService>()

    fun reviewPr(prId: Int, forceRefresh: Boolean = false): List<ReviewComment> {
        if (forceRefresh) cache.invalidatePr(prId)

        val provider = AiProviderFactory.create(settings)
        val pr = prService.getPr(prId)
        val diffs = prService.getPrDiffs(prId)
            .filter { it.changeType != ChangeType.DELETE }

        if (diffs.isEmpty()) {
            throw IllegalStateException(
                "No changed files found for PR #$prId.\n" +
                "The diff fetch returned nothing — open the 'Files Changed' tab first to confirm files load."
            )
        }

        val language = dominantLanguage(diffs.map { it.path })

        // Build PrFile list, then chunk so each request stays within a safe size budget.
        val files = diffs.map { d ->
            PrFile(
                path            = d.path,
                unifiedDiff     = d.toUnifiedDiff(),
                fullFileContent = d.newContent.ifBlank { null }
            )
        }
        val batches = chunkBySize(files)

        val errors = mutableListOf<String>()
        val results = runBlocking {
            batches.map { batch ->
                async {
                    runCatching {
                        provider.reviewPullRequest(
                            PrReviewRequest(
                                prTitle       = pr?.title ?: "PR #$prId",
                                prDescription = pr?.description ?: "",
                                language      = language,
                                files         = batch
                            )
                        )
                    }.getOrElse { e ->
                        thisLogger().warn("PR review batch failed: ${e.message}")
                        synchronized(errors) { errors.add(e.message ?: "unknown error") }
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        // If everything failed, surface the real error instead of "no issues".
        if (results.isEmpty() && errors.isNotEmpty()) {
            throw IllegalStateException("AI review failed.\n\n${errors.first()}")
        }

        val verified = if (settings.state.verifyFindings) {
            verifyFindings(provider, pr?.title ?: "PR #$prId", results, files)
        } else results

        val sorted = verified.sortedWith(compareBy({ it.severity.ordinal }, { it.file }, { it.line }))
        cache.invalidatePr(prId)
        return sorted
    }

    /**
     * Adversarial second pass: shows the first pass's findings back to the model with the diff
     * evidence and asks it to confirm/downgrade/reject each one. Fails open — if the pass errors
     * or the response doesn't parse, findings are kept unverified rather than dropped.
     */
    private fun verifyFindings(
        provider: AiReviewProvider,
        prTitle: String,
        findings: List<ReviewComment>,
        files: List<PrFile>
    ): List<ReviewComment> {
        if (findings.isEmpty()) return findings
        val filesByPath = files.associateBy { it.path }
        val prompt = PromptBuilder.buildVerify(prTitle, findings, filesByPath)

        val verdicts = runCatching {
            runBlocking { ReviewResponseParser.parseVerdicts(provider.complete(prompt)) }
        }.getOrElse { e ->
            thisLogger().warn("Verify pass failed, keeping findings unverified: ${e.message}")
            emptyList()
        }.associateBy { it.index }

        return findings.mapIndexedNotNull { i, finding ->
            when (val v = verdicts[i]) {
                null -> finding
                else -> when (v.verdict) {
                    "reject"    -> null
                    "downgrade" -> finding.copy(severity = v.severity ?: finding.severity)
                    else        -> finding
                }
            }
        }
    }

    /**
     * Runs Roslyn static analysis on locally-checked-out changed C# files.
     * Returns analyzer findings (mapped to ReviewComments) for those files, or empty if
     * dotnet/analyzer isn't available or no files are local. Safe to call in the background.
     *
     * @param localFiles map of (display server path) -> (absolute local file path) for changed .cs files
     */
    fun runAnalyzers(localFiles: Map<String, String>): List<ReviewComment> {
        val csFiles = localFiles.filter { it.value.endsWith(".cs", ignoreCase = true) }
        if (csFiles.isEmpty()) return emptyList()

        val runner = RoslynAnalyzerRunner()
        if (!runner.isAvailable()) {
            thisLogger().info("dotnet not available — skipping static analysis.")
            return emptyList()
        }

        // Find the project/solution to analyze: nearest .csproj walking up from the first file,
        // else nearest .sln.
        val firstAbs = csFiles.values.first()
        val project = findProjectOrSolution(File(firstAbs)) ?: return emptyList()

        val absSet = csFiles.values.map { File(it).absolutePath }.toSet()
        val diags = runner.analyze(project, absSet)

        // Map each diagnostic back to the matching display path so it lines up with AI findings.
        return diags.mapNotNull { d ->
            val display = csFiles.entries.firstOrNull { (_, abs) ->
                File(abs).absolutePath.endsWith(d.filePath.substringAfterLast('/')) ||
                d.filePath.replace('\\','/').endsWith(File(abs).name)
            }?.key ?: d.filePath
            RoslynAnalyzerRunner.toReviewComment(d, display)
        }
    }

    private fun findProjectOrSolution(start: File): File? {
        var dir: File? = start.parentFile
        var firstSln: File? = null
        while (dir != null) {
            dir.listFiles()?.let { files ->
                files.firstOrNull { it.extension == "csproj" }?.let { return it }
                if (firstSln == null) firstSln = files.firstOrNull { it.extension == "sln" }
            }
            dir = dir.parentFile
        }
        return firstSln
    }

    /**
     * Checks whether a PR's changed files actually satisfy a work item's requirements
     * (description / repro steps / acceptance criteria) — a requirements-coverage check, not a
     * code-quality review.
     */
    fun validateAgainstWorkItem(prId: Int, workItem: WorkItem): WorkItemValidation {
        val provider = AiProviderFactory.create(settings)
        val pr = prService.getPr(prId)
        val diffs = prService.getPrDiffs(prId).filter { it.changeType != ChangeType.DELETE }

        if (diffs.isEmpty()) {
            throw IllegalStateException("No changed files found for PR #$prId.")
        }

        val files = diffs.map { d -> PrFile(d.path, d.toUnifiedDiff(), null) }
        val prompt = PromptBuilder.buildValidateWorkItem(
            workItem      = workItem,
            prTitle       = pr?.title ?: "PR #$prId",
            prDescription = pr?.description ?: "",
            files         = files
        )
        val raw = runBlocking { provider.complete(prompt, maxTokens = 4096) }
        return WorkItemValidationParser.parse(raw)
    }

    /** Lists model IDs the configured provider/account can use. Empty on failure. */
    fun listModels(): List<String> = runCatching {
        runBlocking { AiProviderFactory.create(settings).listModels() }
    }.getOrDefault(emptyList())

    /** Sends a tiny request to verify the AI key/model works. Throws with the real reason on failure. */
    fun testAiConnection(): String {
        val provider = AiProviderFactory.create(settings)
        return runBlocking {
            provider.ping()
            "✓ ${settings.state.aiProvider} key works (model: ${settings.state.aiModel.ifBlank { "default" }})"
        }
    }

    /** The most common language among the changed files (drives which prompt to use). */
    private fun dominantLanguage(paths: List<String>): String =
        paths.map { detectLanguage(it) }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key ?: "text"

    /**
     * Splits files into batches that fit a safe per-request character budget, so big PRs
     * don't blow the model's context. Each batch is reviewed in one call.
     */
    private fun chunkBySize(files: List<PrFile>, maxCharsPerBatch: Int = 45_000): List<List<PrFile>> {
        val batches = mutableListOf<MutableList<PrFile>>()
        var current = mutableListOf<PrFile>()
        var size = 0
        for (f in files) {
            val cost = f.unifiedDiff.length + (f.fullFileContent?.length ?: 0)
            if (current.isNotEmpty() && size + cost > maxCharsPerBatch) {
                batches.add(current); current = mutableListOf(); size = 0
            }
            current.add(f); size += cost
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
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
