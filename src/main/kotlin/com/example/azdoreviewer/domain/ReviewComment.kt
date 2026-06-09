package com.example.azdoreviewer.domain

data class ReviewComment(
    val file: String,
    val line: Int,
    val severity: Severity,
    val category: ReviewCategory,
    val title: String = "",       // short headline (≤10 words)
    val comment: String,          // what is wrong + the concrete failure scenario
    val suggestion: String = "",  // optional extra prose: how to fix it
    val suggestedCode: String = "",   // the actual replacement code
    val originalCode: String = "",    // the exact existing snippet this replaces (for safe matching)
    val endLine: Int = 0,         // last affected line (0 = same as `line`)
    val friendlyComment: String = "",  // a warm, ready-to-post PR comment
    val source: Source = Source.AI     // where the finding came from
) {
    enum class Source { AI, ANALYZER }

    val lineRange: String
        get() = if (endLine > line) "$line–$endLine" else "$line"
}
