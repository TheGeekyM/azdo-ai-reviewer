package com.example.azdoreviewer.infrastructure.analyzer

import com.example.azdoreviewer.domain.ReviewCategory
import com.example.azdoreviewer.domain.ReviewComment
import com.example.azdoreviewer.domain.Severity
import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs Roslyn-based static analysis (Roslynator CLI, which bundles the .NET CA analyzers)
 * on a C# project and returns diagnostics as ReviewComments. Requires `dotnet` on PATH.
 *
 * Zero user setup: the CLI is auto-installed as a dotnet global tool on first use.
 * Accurate compiler-level facts (EF translatability, null safety, CA rules, etc.) — no AI guessing.
 */
class RoslynAnalyzerRunner {

    private val log = thisLogger()

    data class AnalyzerDiagnostic(
        val filePath: String,   // absolute path
        val line: Int,
        val ruleId: String,
        val severity: String,   // error | warning | info
        val message: String
    )

    /** True if dotnet is available; analysis is skipped (silently) otherwise. */
    fun isAvailable(): Boolean = runCatching {
        runProcess(listOf(dotnet(), "--version"), File("."), 15).exitCode == 0
    }.getOrDefault(false)

    /** Ensures the Roslynator CLI dotnet tool is installed. Idempotent. */
    fun ensureInstalled(): Boolean {
        if (toolExists()) return true
        log.info("Installing roslynator.dotnet.cli global tool…")
        val r = runProcess(
            listOf(dotnet(), "tool", "install", "-g", "roslynator.dotnet.cli"),
            File(System.getProperty("user.home")), 180
        )
        // "already installed" also counts as success
        return r.exitCode == 0 || r.output.contains("already installed", ignoreCase = true) || toolExists()
    }

    /**
     * Analyzes the project that contains [changedAbsPaths]. Returns diagnostics for those files only.
     * [projectOrSolution] is the .csproj/.sln to analyze (found by walking up from a changed file).
     */
    fun analyze(projectOrSolution: File, changedAbsPaths: Set<String>, timeoutSec: Long = 240): List<AnalyzerDiagnostic> {
        if (!ensureInstalled()) {
            log.warn("Roslynator CLI not available; skipping static analysis.")
            return emptyList()
        }
        val out = File.createTempFile("azdo-roslyn", ".sarif")
        return try {
            val cmd = mutableListOf(
                roslynator(), "analyze", projectOrSolution.absolutePath,
                "--output", out.absolutePath,
                "--severity-level", "info",
                "--verbosity", "quiet"
            )
            // Load the bundled Sonar / SecurityCodeScan / Roslynator analyzers (verified to work).
            val analyzerDlls = extractBundledAnalyzers()
            if (analyzerDlls.isNotEmpty()) {
                cmd.add("--analyzer-assemblies")
                cmd.addAll(analyzerDlls.map { it.absolutePath })
            }
            val r = runProcess(cmd, projectOrSolution.parentFile ?: File("."), timeoutSec)
            if (r.exitCode != 0 && !out.exists()) {
                log.warn("roslynator analyze failed (${r.exitCode}): ${r.output.take(500)}")
                return emptyList()
            }
            val all = RoslynatorXmlParser.parse(out.readText())
            // Keep only diagnostics on the PR's changed files.
            val changedNorm = changedAbsPaths.map { it.replace('\\', '/') }.toSet()
            all.filter { d -> changedNorm.any { it.endsWith(relSuffix(d.filePath)) || d.filePath.replace('\\','/').endsWith(relSuffix(it)) } }
        } catch (e: Exception) {
            log.warn("Static analysis error: ${e.message}")
            emptyList()
        } finally {
            out.delete()
        }
    }

    private fun relSuffix(path: String): String {
        val p = path.replace('\\', '/')
        // last two path segments are enough to match across worktrees
        val parts = p.split('/')
        return if (parts.size >= 2) parts.takeLast(2).joinToString("/") else p
    }

    // ── process helpers ───────────────────────────────────────────────────────
    private data class ProcResult(val exitCode: Int, val output: String)

    private fun runProcess(cmd: List<String>, dir: File, timeoutSec: Long): ProcResult {
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        // Make dotnet tools resolvable
        val home = System.getProperty("user.home")
        pb.environment()["PATH"] = (pb.environment()["PATH"] ?: "") + ":$home/.dotnet/tools"
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) { proc.destroyForcibly(); return ProcResult(-1, output + "\n[timed out]") }
        return ProcResult(proc.exitValue(), output)
    }

    private fun dotnet(): String =
        listOf("/usr/lib/dotnet/dotnet", "/usr/bin/dotnet", "dotnet")
            .firstOrNull { it == "dotnet" || File(it).exists() } ?: "dotnet"

    private fun roslynator(): String {
        val home = System.getProperty("user.home")
        val candidate = File("$home/.dotnet/tools/roslynator")
        return if (candidate.exists()) candidate.absolutePath else "roslynator"
    }

    private fun toolExists(): Boolean =
        File("${System.getProperty("user.home")}/.dotnet/tools/roslynator").exists()

    /**
     * Extracts the bundled analyzer DLLs (Sonar, SecurityCodeScan, Roslynator) from plugin
     * resources to a cached temp dir and returns the entry-point analyzer assemblies to load.
     * All DLLs (incl. dependencies) are extracted to the same dir so they resolve.
     */
    private fun extractBundledAnalyzers(): List<File> {
        return runCatching {
            val dir = File(System.getProperty("java.io.tmpdir"), "azdo-analyzers").apply { mkdirs() }
            val index = readResource("/analyzers/index.txt")?.lines()?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: return emptyList()
            val load = readResource("/analyzers/load.txt")?.lines()?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()

            for (name in index) {
                val target = File(dir, name)
                if (target.exists() && target.length() > 0) continue
                javaClass.getResourceAsStream("/analyzers/$name")?.use { ins ->
                    target.outputStream().use { ins.copyTo(it) }
                }
            }
            load.map { File(dir, it) }.filter { it.exists() }
        }.getOrElse {
            log.warn("Could not extract bundled analyzers: ${it.message}")
            emptyList()
        }
    }

    private fun readResource(path: String): String? =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

    companion object {
        /** Maps a CA/analyzer rule id to our category + a sensible severity floor. */
        fun toReviewComment(d: AnalyzerDiagnostic, fileForDisplay: String): ReviewComment {
            val category = categoryForRule(d.ruleId)
            // Security findings (SCS, Sonar security S2076/S2078/S3649/S5131...) are never trivial.
            val sev = when {
                category == ReviewCategory.SECURITY -> Severity.HIGH
                d.severity.equals("error", true)    -> Severity.HIGH
                d.severity.equals("warning", true)  -> Severity.MEDIUM
                else                                -> Severity.LOW
            }
            return ReviewComment(
                file        = fileForDisplay,
                line        = d.line,
                endLine     = d.line,
                severity    = sev,
                category    = category,
                title       = "${d.ruleId}: ${d.message.take(60)}",
                comment     = "${d.message}  (static analysis rule ${d.ruleId})",
                suggestion  = "",
                friendlyComment = friendly(d)
            )
        }

        private fun friendly(d: AnalyzerDiagnostic): String =
            "Static analysis flagged this (${d.ruleId}): ${d.message} — worth a quick look."

        // Sonar security rules (S-prefixed) that should be treated as Security.
        private val SONAR_SECURITY = setOf(
            "S2076", "S2078", "S3649", "S2083", "S5131", "S5146", // injection / XSS / path traversal
            "S2068", "S2070", "S4790", "S5547", "S2245",          // weak crypto / hardcoded creds
            "S4830", "S4423", "S5542", "S2092", "S3330"           // TLS / cookie / cert
        )

        private fun categoryForRule(id: String): ReviewCategory = when {
            id in SONAR_SECURITY || id.startsWith("SCS") ||
                id.startsWith("CA21") || id.startsWith("CA30") || id.startsWith("CA53") -> ReviewCategory.SECURITY
            id.startsWith("CA18") || id.startsWith("CA20") -> ReviewCategory.PERFORMANCE
            id.startsWith("CA22") || id == "S2551" || id == "S2095" -> ReviewCategory.CONCURRENCY
            id.startsWith("CA1063") || id.startsWith("CA2000") || id == "S2589" || id == "S2259" -> ReviewCategory.BUG
            id.startsWith("EF") -> ReviewCategory.PERFORMANCE
            // Roslynator RCS rules are mostly clean-code / readability refactorings.
            id.startsWith("RCS") -> ReviewCategory.CLEAN_CODE
            else -> ReviewCategory.CLEAN_CODE
        }
    }
}
