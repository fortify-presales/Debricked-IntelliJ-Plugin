package com.debricked.intellijplugin.domain

data class RepositoryConfiguration(
    val organizationId: String,
    val repositoryId: String,
    val repositoryName: String,
    val defaultBranch: String?
)

enum class Ecosystem {
    MAVEN,
    GRADLE,
    NPM
}

data class DependencyKey(
    val ecosystem: Ecosystem,
    val groupId: String?,
    val artifactId: String,
    val version: String
) {
    override fun toString(): String = when (ecosystem) {
        Ecosystem.MAVEN, Ecosystem.GRADLE ->
            "${groupId}:${artifactId}:${version}"
        Ecosystem.NPM ->
            "npm:${artifactId}:${version}"
    }
}

enum class Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN
}

enum class FallbackReason {
    COMMIT_NOT_SCANNED,
    BRANCH_NOT_SCANNED,
    DEFAULT_BRANCH_USED,
    NO_REMOTE_DATA
}

data class DebrickedScanContext(
    val repositoryId: String,
    val repositoryName: String,
    val branchName: String?,
    val commitSha: String?,
    val scanId: String?,
    val displayedCommitSha: String?,
    val isExactCommitMatch: Boolean,
    val fallbackReason: FallbackReason?
)

data class VulnerabilityFinding(
    val id: String,
    val ecosystem: Ecosystem,
    val packageName: String,
    val groupId: String?,
    val version: String,
    val severity: Severity,
    val fixedVersion: String?,
    val description: String?,
    val scanContext: DebrickedScanContext,
    val affectedFiles: List<String> = emptyList(),
    val cveId: String? = null,
    val exploitabilityScore: Double? = null
)

enum class FindingConfidence {
    VERIFIED_CURRENT_COMMIT,
    VERIFIED_BRANCH_LATEST,
    FALLBACK_DEFAULT_BRANCH,
    STALE_LOCAL_DEPENDENCY_CHANGES,
    UNKNOWN
}

data class FixSuggestion(
    val affectedDependency: DependencyKey,
    val suggestedVersion: String?,
    val confidence: FixConfidence = FixConfidence.UNKNOWN,
    val explanation: String = "",
    val dependencyPath: List<DependencyKey> = emptyList()
)

enum class FixConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN
}

enum class FindingsState {
    LOADING,
    CURRENT,
    STALE_DEPENDENCY_CHANGES,
    STALE_COMMIT,
    NO_REMOTE_RESULTS
}
