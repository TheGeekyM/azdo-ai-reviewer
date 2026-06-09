package com.example.azdoreviewer.domain

data class FileDiff(
    val path: String,
    val originalPath: String?,
    val changeType: ChangeType,
    val hunks: List<DiffHunk>,
    val oldContent: String = "",   // content at base commit ("" if added/new)
    val newContent: String = ""    // content at source commit ("" if deleted)
) {
    /**
     * Produces a real unified diff between oldContent and newContent.
     * Falls back to the hunk-based rendering when content isn't available.
     */
    fun toUnifiedDiff(): String {
        if (oldContent.isBlank() && newContent.isNotBlank()) {
            // Added file: every line is new
            return buildString {
                appendLine("--- /dev/null")
                appendLine("+++ b/$path")
                newContent.lines().forEachIndexed { i, l -> appendLine("+$l") }
            }
        }
        if (newContent.isBlank() && oldContent.isNotBlank()) {
            return buildString {
                appendLine("--- a/$path")
                appendLine("+++ /dev/null")
                oldContent.lines().forEach { appendLine("-$it") }
            }
        }
        return UnifiedDiff.compute(originalPath ?: path, path, oldContent, newContent)
    }
}

/**
 * Minimal LCS-based unified diff generator producing COMPACT hunks:
 * only changed lines plus [CONTEXT_LINES] of surrounding context, with @@ headers.
 * Large unchanged regions are collapsed — the whole file is NOT included.
 */
object UnifiedDiff {

    private const val CONTEXT_LINES = 3

    private data class Op(val type: Char, val text: String, val newLine: Int)

    fun compute(oldPath: String, newPath: String, oldText: String, newText: String): String {
        val a = oldText.lines()
        val b = newText.lines()
        val ops = diffOps(a, b)

        // No changes at all
        if (ops.none { it.type != ' ' }) return "(no textual changes)"

        val hunks = buildHunks(ops)
        return buildString {
            appendLine("--- a/$oldPath")
            appendLine("+++ b/$newPath")
            hunks.forEach { hunk ->
                val firstNew = hunk.firstOrNull { it.newLine > 0 }?.newLine ?: 1
                appendLine("@@ around line $firstNew @@")
                hunk.forEach { op ->
                    when (op.type) {
                        '+'  -> appendLine("+${op.text}    [line ${op.newLine}]")
                        '-'  -> appendLine("-${op.text}")
                        else -> appendLine(" ${op.text}")
                    }
                }
            }
        }
    }

    /** Build LCS ops with correct new-file line numbers. */
    private fun diffOps(a: List<String>, b: List<String>): List<Op> {
        val lcs = lcsTable(a, b)
        val raw = mutableListOf<Pair<Char, String>>()
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) { raw.add(' ' to a[i]); i++; j++ }
            else if (lcs[i + 1][j] >= lcs[i][j + 1]) { raw.add('-' to a[i]); i++ }
            else { raw.add('+' to b[j]); j++ }
        }
        while (i < a.size) { raw.add('-' to a[i]); i++ }
        while (j < b.size) { raw.add('+' to b[j]); j++ }

        var newLine = 0
        return raw.map { (t, text) ->
            if (t == '+' || t == ' ') newLine++
            Op(t, text, if (t == '+' || t == ' ') newLine else 0)
        }
    }

    /** Group ops into hunks: keep each change + CONTEXT_LINES around it, drop the rest. */
    private fun buildHunks(ops: List<Op>): List<List<Op>> {
        val keep = BooleanArray(ops.size)
        ops.forEachIndexed { idx, op ->
            if (op.type != ' ') {
                val from = (idx - CONTEXT_LINES).coerceAtLeast(0)
                val to   = (idx + CONTEXT_LINES).coerceAtMost(ops.size - 1)
                for (k in from..to) keep[k] = true
            }
        }
        val hunks = mutableListOf<MutableList<Op>>()
        var current: MutableList<Op>? = null
        ops.forEachIndexed { idx, op ->
            if (keep[idx]) {
                if (current == null) { current = mutableListOf(); hunks.add(current!!) }
                current!!.add(op)
            } else current = null
        }
        return hunks
    }

    private fun lcsTable(a: List<String>, b: List<String>): Array<IntArray> {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (x in a.indices.reversed())
            for (y in b.indices.reversed())
                dp[x][y] = if (a[x] == b[y]) dp[x + 1][y + 1] + 1
                           else maxOf(dp[x + 1][y], dp[x][y + 1])
        return dp
    }
}

data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>
)

data class DiffLine(
    val type: LineType,
    val lineNumber: Int,
    val content: String
)
