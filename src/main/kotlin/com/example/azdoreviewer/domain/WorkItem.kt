package com.example.azdoreviewer.domain

data class WorkItem(
    val id: Int,
    val type: String,      // "User Story", "Bug", "Task", ...
    val title: String,
    val state: String = "",
    val description: String = "",
    val reproSteps: String = "",           // bugs only
    val acceptanceCriteria: String = ""    // user stories only
)

/** Result of asking the AI whether a PR's diffs actually satisfy a work item's requirements. */
data class WorkItemValidation(
    val verdict: String,   // "Meets requirements" | "Partially meets requirements" | "Does not meet requirements" | "Unclear"
    val findings: List<RequirementFinding>,
    val summary: String
)

data class RequirementFinding(
    val requirement: String,   // paraphrase of one AC bullet / repro step / description ask
    val status: String,        // "Met" | "Not Met" | "Partial" | "Unclear"
    val explanation: String,
    val file: String = "",             // best-guess file this requirement's evidence/gap is in, blank if none
    val line: Int = 0,                 // best-guess line, 0 if unknown
    val friendlyComment: String = ""   // warm, ready-to-post PR comment (Not Met/Partial only)
) {
    val isMet: Boolean get() = status.equals("Met", ignoreCase = true)
}
