package com.example.azdoreviewer.domain

data class ReviewComment(
    val file: String,
    val line: Int,
    val severity: Severity,
    val category: ReviewCategory,
    val comment: String,          // what is wrong and why (for the details pane)
    val suggestion: String,       // prose: how to fix it (for the details pane)
    val suggestedCode: String = "",   // the actual replacement code
    val endLine: Int = 0,         // last affected line (0 = same as `line`)
    val friendlyComment: String = ""  // a warm, ready-to-post PR comment
) {
    val lineRange: String
        get() = if (endLine > line) "$line–$endLine" else "$line"
}
