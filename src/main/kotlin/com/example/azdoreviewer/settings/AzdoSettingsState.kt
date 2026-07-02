package com.example.azdoreviewer.settings

data class AzdoSettingsState(
    var repoUrl: String = "",       // full repo URL as entered by user
    var organizationUrl: String = "",
    var project: String = "",
    var repository: String = "",
    var aiProvider: String = "claude",
    var aiModel: String = "",
    var maxFilesPerReview: Int = 20,
    var reviewOnlyAddedLines: Boolean = false,
    var claudeAuthMode: String = "apikey",  // "apikey" or "oauth" (subscription)
    var verifyFindings: Boolean = true      // extra adversarial AI pass to catch false positives/bad severity
)
