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
    val vulnerabilityId: String? = null,
    val debrickedCommitId: String? = null,
    val title: String? = null,
    val ecosystem: Ecosystem,
    val packageName: String,
    val groupId: String?,
    val version: String,
    val affectedDependencies: List<AffectedDependency> = emptyList(),
    val severity: Severity,
    val fixedVersion: String?,
    val description: String?,
    val scanContext: DebrickedScanContext,
    val affectedFiles: List<String> = emptyList(),
    val cveId: String? = null,
    val reviewStatus: String? = null,
    val pausedUntil: String? = null,
    val introducedAt: Long? = null,
    val reachablePath: String? = null,
    val reachabilityMessage: String? = null,
    val exploited: Boolean? = null,
    val exploitabilityScore: Double? = null,
    val cvss2Score: Double? = null,
    val cvss3Score: Double? = null
)

data class AffectedDependency(
    val name: String,
    val version: String? = null
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
    TIMEOUT,
    NO_REMOTE_RESULTS
}

data class VulnerabilityQuery(
    val search: String = "",
    val page: Int = 1,
    val rowsPerPage: Int = 25,
    val sortColumn: String = "cvss",
    val order: String = "desc"
)

data class VulnerabilityPageResult(
    val findings: List<VulnerabilityFinding>,
    val page: Int,
    val rowsPerPage: Int,
    val totalCount: Int? = null,
    val hasNext: Boolean = false
)

data class VulnerabilityDetailsContext(
    val vulnerabilityId: String,
    val repositoryId: String,
    val branchName: String? = null,
    val commitId: String? = null,
    val title: String? = null,
    val cveId: String? = null
)

data class VulnerabilitySummarySource(
    val key: String,
    val category: String,
    val title: String,
    val description: String,
    val explanation: String? = null,
    val link: String? = null,
    val missing: Boolean = false
)

data class VulnerabilityScoreSummary(
    val category: String,
    val label: String,
    val scoreText: String,
    val highlighted: Boolean
)

data class VulnerabilityDates(
    val discoveredAt: Long? = null,
    val publishedAt: Long? = null,
    val updatedAt: Long? = null
)

data class VulnerabilityReferenceLink(
    val title: String,
    val link: String,
    val domain: String? = null,
    val tags: List<String> = emptyList()
)

data class VulnerabilityFileRef(
    val id: String,
    val name: String,
    val url: String? = null
)

data class VulnerabilityDependencyTreeNode(
    val name: String,
    val version: String? = null,
    val url: String? = null,
    val vulnerable: Boolean = false,
    val children: List<VulnerabilityDependencyTreeNode> = emptyList()
)

data class VulnerabilityDependencyTree(
    val fileName: String? = null,
    val fileUrl: String? = null,
    val roots: List<VulnerabilityDependencyTreeNode> = emptyList()
)

data class VulnerabilityRepositoryBranch(
    val id: String,
    val name: String,
    val latestCommitId: String? = null,
    val isVulnerable: Boolean = false
)

data class VulnerabilityRepositoryStatus(
    val id: String,
    val name: String,
    val link: String? = null,
    val type: String,
    val pausedUntil: String? = null,
    val branches: List<VulnerabilityRepositoryBranch> = emptyList()
)

data class VulnerabilityReviewStatusInfo(
    val repositoryStatuses: List<VulnerabilityRepositoryStatus> = emptyList(),
    val enforceComment: Boolean = false,
    val commentMinLength: Int? = null,
    val oldComment: String? = null,
    val oldCommentAuthor: String? = null
)

data class VulnerabilityRootFixes(
    val rootFixesCount: Int = 0,
    val fixes: Map<String, String> = emptyMap(),
    val commands: List<String> = emptyList(),
    val isReady: Boolean = false
)

data class VulnerabilityTimelineDependency(
    val name: String,
    val shortName: String? = null,
    val link: String? = null
)

data class VulnerabilityTimelineInterval(
    val vulnerable: Boolean,
    val startVersion: String? = null,
    val endVersion: String? = null
)

data class VulnerabilityTimeline(
    val dependencies: List<VulnerabilityTimelineDependency> = emptyList(),
    val intervals: List<VulnerabilityTimelineInterval> = emptyList()
)

data class VulnerabilityReachabilityDetails(
    val supported: Boolean = true,
    val reachAnalysisLanguage: String? = null,
    val reachAnalysisMessage: String? = null,
    val reachAnalysis: String? = null
)

data class VulnerabilityCvssDetails(
    val explanation: String? = null
)

// ── Dependencies ──────────────────────────────────────────────────────────────

data class DependencyItem(
    val id: String,
    val name: String,
    val version: String,
    val ecosystem: String?,
    val licenses: List<String>,
    val vulnerabilityCount: Int,
    val isIndirect: Boolean,
    val latestVersion: String? = null,
    val link: String? = null
)

data class DependencyQuery(
    val search: String = "",
    val page: Int = 1,
    val rowsPerPage: Int = 25,
    val sortColumn: String = "name",
    val order: String = "asc"
)

data class DependencyPageResult(
    val dependencies: List<DependencyItem>,
    val page: Int,
    val rowsPerPage: Int,
    val totalCount: Int? = null,
    val hasNext: Boolean = false
)

enum class DependenciesState {
    LOADING,
    CURRENT,
    NO_RESULTS,
    ERROR
}

// ── VulnerabilityDetailsBundle (unchanged) ────────────────────────────────────

data class VulnerabilityDetailsBundle(
    val summarySources: List<VulnerabilitySummarySource> = emptyList(),
    val scoreSummaries: List<VulnerabilityScoreSummary> = emptyList(),
    val cvssDetails: VulnerabilityCvssDetails? = null,
    val dates: VulnerabilityDates = VulnerabilityDates(),
    val affectedDependencies: List<AffectedDependency> = emptyList(),
    val files: List<VulnerabilityFileRef> = emptyList(),
    val dependencyTree: VulnerabilityDependencyTree? = null,
    val repositoryStatuses: List<VulnerabilityRepositoryStatus> = emptyList(),
    val reviewStatusInfo: VulnerabilityReviewStatusInfo? = null,
    val rootFixes: VulnerabilityRootFixes? = null,
    val vulnerableTimelines: List<VulnerabilityTimeline> = emptyList(),
    val references: List<VulnerabilityReferenceLink> = emptyList(),
    val reachabilityDetails: VulnerabilityReachabilityDetails? = null
)
