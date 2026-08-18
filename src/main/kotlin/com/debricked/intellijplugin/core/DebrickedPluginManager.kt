package com.debricked.intellijplugin.core

import com.debricked.intellijplugin.api.DebrickedApiClient
import com.debricked.intellijplugin.domain.*
import com.debricked.intellijplugin.settings.DebrickedCredentialStore
import com.debricked.intellijplugin.settings.DebrickedSettingsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class DebrickedPluginManager(private val project: Project) {

    private val LOG = logger<DebrickedPluginManager>()
    private val apiClient = ApplicationManager.getApplication().getService(DebrickedApiClient::class.java)
    private val gitResolver = GitContextResolver(project)
    private val vulnerabilityCache = VulnerabilityCache()
    private val refreshLock = Any()
    @Volatile private var activeRefreshRequest: RefreshRequest? = null

    @Volatile private var currentFindings = listOf<VulnerabilityFinding>()
    @Volatile private var currentPageResult = VulnerabilityPageResult(
        findings = emptyList(),
        page = 1,
        rowsPerPage = 25,
        totalCount = 0,
        hasNext = false
    )
    @Volatile private var currentScanContext: DebrickedScanContext? = null
    @Volatile private var findingsState = FindingsState.NO_REMOTE_RESULTS
    private val listeners = mutableListOf<FindingsUpdateListener>()

    private data class RefreshRequest(
        val repositoryId: String,
        val branchId: String?,
        val branchName: String?,
        val defaultBranch: String?,
        val query: VulnerabilityQuery
    )

    init {
        // Subscribe to settings-applied events so the tool window refreshes when the user
        // changes repository in Settings and clicks OK/Apply.
        ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(DebrickedSettingsNotifier.TOPIC, object : DebrickedSettingsNotifier {
                override fun onSettingsApplied() {
                    LOG.info("Settings applied — refreshing findings")
                    clearFindingsCache()
                    refreshFindings()
                }
            })
    }

    fun refreshFindings(forceRefresh: Boolean = false, query: VulnerabilityQuery = VulnerabilityQuery()) {
        val settings = DebrickedSettingsManager.getInstance()
        val repositoryId = settings.getRepositoryId()
        val branchId = settings.getSelectedBranchId().ifBlank { null }
        val branchName = settings.getSelectedBranchName().ifBlank { null }
        val defaultBranch = settings.getDefaultBranch()?.ifBlank { null }
        val request = RefreshRequest(repositoryId, branchId, branchName, defaultBranch, query)

        if (repositoryId.isEmpty()) {
            currentFindings = emptyList()
            currentPageResult = VulnerabilityPageResult(
                findings = emptyList(),
                page = query.page.coerceAtLeast(1),
                rowsPerPage = query.rowsPerPage.coerceAtLeast(1),
                totalCount = 0,
                hasNext = false
            )
            findingsState = FindingsState.NO_REMOTE_RESULTS
            notifyListeners(currentPageResult, FindingsState.NO_REMOTE_RESULTS)
            return
        }

        synchronized(refreshLock) {
            if (!forceRefresh && findingsState == FindingsState.LOADING && request == activeRefreshRequest) {
                return
            }
            activeRefreshRequest = request
        }

        val sameBranch = when {
            branchName.isNullOrBlank() -> currentScanContext?.branchName.isNullOrBlank()
            else -> currentScanContext?.branchName?.equals(branchName, ignoreCase = true) == true
        }
        val preserveVisibleFindings = currentScanContext?.repositoryId == repositoryId && sameBranch
        // Keep current findings visible while loading to avoid full UI flicker.
        findingsState = FindingsState.LOADING
        notifyListeners(
            if (preserveVisibleFindings) currentPageResult else currentPageResult.copy(findings = emptyList()),
            FindingsState.LOADING
        )

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val gitContext = gitResolver.resolveGitContext()
                val pageResult = resolveFindingsWithFallback(
                    repositoryId,
                    branchId,
                    branchName,
                    defaultBranch,
                    query,
                    forceRefresh
                )
                currentFindings = pageResult.findings
                currentPageResult = pageResult
                findingsState = determineFindingsState(pageResult.findings, gitContext)
                val shouldPublish = synchronized(refreshLock) {
                    if (activeRefreshRequest == request) {
                        activeRefreshRequest = null
                        true
                    } else {
                        false
                    }
                }
                if (!shouldPublish) {
                    return@executeOnPooledThread
                }
                notifyListeners(pageResult, findingsState)
            } catch (e: Exception) {
                LOG.error("Failed to refresh findings: ${e.message}", e)
                currentFindings = emptyList()
                currentPageResult = VulnerabilityPageResult(
                    findings = emptyList(),
                    page = query.page.coerceAtLeast(1),
                    rowsPerPage = query.rowsPerPage.coerceAtLeast(1),
                    totalCount = 0,
                    hasNext = false
                )
                findingsState = FindingsState.NO_REMOTE_RESULTS
                val shouldPublish = synchronized(refreshLock) {
                    if (activeRefreshRequest == request) {
                        activeRefreshRequest = null
                        true
                    } else {
                        false
                    }
                }
                if (!shouldPublish) {
                    return@executeOnPooledThread
                }
                notifyListeners(currentPageResult, FindingsState.NO_REMOTE_RESULTS)
            }
        }
    }

    private fun resolveFindingsWithFallback(
        repositoryId: String,
        branchId: String?,
        branchName: String?,
        defaultBranch: String?,
        query: VulnerabilityQuery,
        forceRefresh: Boolean
    ): VulnerabilityPageResult {
        val queryKey = buildQueryKey(query)
        var resolvedPage = vulnerabilityCache.getOrLoadForQuery(repositoryId, branchId, queryKey, forceRefresh) {
            apiClient.getVulnerabilitiesPage(repositoryId, branchName = branchId, query = query)
        }
        var resolvedBranchName = branchName ?: branchId
        var fallbackReason: FallbackReason? = null

        val shouldTryDefaultBranch = resolvedPage.findings.isEmpty() &&
            !defaultBranch.isNullOrBlank() &&
            !matchesBranch(branchId, branchName, defaultBranch)

        if (shouldTryDefaultBranch) {
            val fallbackPage = vulnerabilityCache.getOrLoadForQuery(repositoryId, defaultBranch, queryKey, forceRefresh) {
                apiClient.getVulnerabilitiesPage(repositoryId, branchName = defaultBranch, query = query)
            }
            if (fallbackPage.findings.isNotEmpty()) {
                resolvedPage = fallbackPage
                resolvedBranchName = defaultBranch
                fallbackReason = FallbackReason.DEFAULT_BRANCH_USED
            } else if (!branchId.isNullOrBlank() || !branchName.isNullOrBlank()) {
                fallbackReason = FallbackReason.BRANCH_NOT_SCANNED
            }
        }

        if (resolvedPage.findings.isEmpty() && fallbackReason == null) {
            fallbackReason = FallbackReason.NO_REMOTE_DATA
        }

        currentScanContext = DebrickedScanContext(
            repositoryId = repositoryId,
            repositoryName = DebrickedSettingsManager.getInstance().getRepositoryName(),
            branchName = resolvedBranchName,
            commitSha = null,
            scanId = null,
            displayedCommitSha = resolvedBranchName,
            isExactCommitMatch = false,
            fallbackReason = fallbackReason
        )
        return resolvedPage
    }

    private fun matchesBranch(branchId: String?, branchName: String?, candidate: String): Boolean {
        return branchId.equals(candidate, ignoreCase = true) || branchName.equals(candidate, ignoreCase = true)
    }

    private fun determineFindingsState(
        findings: List<VulnerabilityFinding>,
        gitContext: GitContext
    ): FindingsState = when {
        findings.isEmpty() -> FindingsState.NO_REMOTE_RESULTS
        gitContext.isDirty -> FindingsState.STALE_DEPENDENCY_CHANGES
        else -> FindingsState.CURRENT
    }

    fun getCurrentFindings(): List<VulnerabilityFinding> = currentFindings
    fun getCurrentPageResult(): VulnerabilityPageResult = currentPageResult
    fun getCurrentScanContext(): DebrickedScanContext? = currentScanContext
    fun getFindingsState(): FindingsState = findingsState
    fun getGitContext(): GitContext = gitResolver.resolveGitContext()

    fun hasCredentials(): Boolean =
        DebrickedCredentialStore.getAccessToken() != null || DebrickedCredentialStore.getPassword() != null

    fun isConfigured(): Boolean =
        DebrickedSettingsManager.getInstance().isConfigured() && hasCredentials()

    fun addListener(listener: FindingsUpdateListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: FindingsUpdateListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun invalidateFindingsCache(repositoryId: String, branchId: String? = null) {
        vulnerabilityCache.invalidate(repositoryId, branchId)
    }

    fun clearFindingsCache() {
        vulnerabilityCache.clear()
    }

    private fun notifyListeners(pageResult: VulnerabilityPageResult, state: FindingsState) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onFindingsUpdated(pageResult, state) }
    }

    private fun buildQueryKey(query: VulnerabilityQuery): String {
        return listOf(
            query.search.trim(),
            query.page.coerceAtLeast(1).toString(),
            query.rowsPerPage.coerceAtLeast(1).toString(),
            query.sortColumn.lowercase(),
            query.order.lowercase()
        ).joinToString("|")
    }

    fun shutdown() {}

    interface FindingsUpdateListener {
        fun onFindingsUpdated(pageResult: VulnerabilityPageResult, state: FindingsState)
    }
}
