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

    @Volatile private var currentFindings = listOf<VulnerabilityFinding>()
    @Volatile private var currentScanContext: DebrickedScanContext? = null
    @Volatile private var findingsState = FindingsState.NO_REMOTE_RESULTS
    private val listeners = mutableListOf<FindingsUpdateListener>()

    init {
        // Subscribe to settings-applied events so the tool window refreshes when the user
        // changes repository in Settings and clicks OK/Apply.
        ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(DebrickedSettingsNotifier.TOPIC, object : DebrickedSettingsNotifier {
                override fun onSettingsApplied() {
                    LOG.info("Settings applied — refreshing findings")
                    refreshFindings()
                }
            })
    }

    fun refreshFindings() {
        val settings = DebrickedSettingsManager.getInstance()
        val repositoryId = settings.getRepositoryId()

        if (repositoryId.isEmpty()) {
            currentFindings = emptyList()
            findingsState = FindingsState.NO_REMOTE_RESULTS
            notifyListeners(emptyList(), FindingsState.NO_REMOTE_RESULTS)
            return
        }

        // Immediately show LOADING so the tool window clears stale data right away
        currentFindings = emptyList()
        findingsState = FindingsState.LOADING
        notifyListeners(emptyList(), FindingsState.LOADING)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val gitContext = gitResolver.resolveGitContext()
                val findings = resolveFindingsWithFallback(
                    repositoryId,
                    gitContext.commitSha,
                    gitContext.branchName
                )
                currentFindings = findings
                findingsState = determineFindingsState(findings, gitContext)
                notifyListeners(findings, findingsState)
            } catch (e: Exception) {
                LOG.error("Failed to refresh findings: ${e.message}", e)
                currentFindings = emptyList()
                findingsState = FindingsState.NO_REMOTE_RESULTS
                notifyListeners(emptyList(), FindingsState.NO_REMOTE_RESULTS)
            }
        }
    }

    private fun resolveFindingsWithFallback(
        repositoryId: String,
        commitSha: String?,
        branchName: String?
    ): List<VulnerabilityFinding> {
        val findings = apiClient.getVulnerabilities(repositoryId)
        currentScanContext = DebrickedScanContext(
            repositoryId = repositoryId,
            repositoryName = DebrickedSettingsManager.getInstance().getRepositoryName(),
            branchName = branchName,
            commitSha = commitSha,
            scanId = null,
            displayedCommitSha = if (findings.isNotEmpty()) commitSha ?: branchName else null,
            isExactCommitMatch = false,
            fallbackReason = if (findings.isEmpty()) FallbackReason.NO_REMOTE_DATA else null
        )
        return findings
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
    fun getCurrentScanContext(): DebrickedScanContext? = currentScanContext
    fun getFindingsState(): FindingsState = findingsState
    fun getGitContext(): GitContext = gitResolver.resolveGitContext()

    fun isConfigured(): Boolean {
        val settings = DebrickedSettingsManager.getInstance()
        return settings.isConfigured() &&
            (DebrickedCredentialStore.getAccessToken() != null || DebrickedCredentialStore.getPassword() != null)
    }

    fun addListener(listener: FindingsUpdateListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: FindingsUpdateListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyListeners(findings: List<VulnerabilityFinding>, state: FindingsState) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.onFindingsUpdated(findings, state) }
    }

    fun shutdown() {}

    interface FindingsUpdateListener {
        fun onFindingsUpdated(findings: List<VulnerabilityFinding>, state: FindingsState)
    }
}
