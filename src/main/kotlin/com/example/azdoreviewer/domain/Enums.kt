package com.example.azdoreviewer.domain

enum class PrStatus { ACTIVE, COMPLETED, ABANDONED, NOTSET }

enum class ChangeType { ADD, EDIT, DELETE, RENAME }

enum class LineType { ADDED, REMOVED, CONTEXT }

enum class Severity(val label: String) {
    CRITICAL("Critical"),
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
    INFO("Info")
}

enum class ReviewCategory(val label: String) {
    BUG("Bug"),
    SECURITY("Security"),
    PERFORMANCE("Performance"),
    CLEAN_CODE("Clean Code"),
    SOLID("SOLID"),
    DDD("DDD"),
    MAINTAINABILITY("Maintainability"),
    MISSING_TESTS("Missing Tests"),
    CONCURRENCY("Concurrency")
}
