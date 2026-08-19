package com.debricked.intellijplugin.ui

import com.debricked.intellijplugin.api.DebrickedApiClient
import com.debricked.intellijplugin.core.DataCache
import com.debricked.intellijplugin.core.DependencyCache
import com.debricked.intellijplugin.core.DebrickedPluginManager
import com.debricked.intellijplugin.domain.DebrickedScanContext
import com.debricked.intellijplugin.domain.DependenciesState
import com.debricked.intellijplugin.domain.DependencyItem
import com.debricked.intellijplugin.domain.DependencyPageResult
import com.debricked.intellijplugin.domain.DependencyQuery
import com.debricked.intellijplugin.domain.FindingsState
import com.debricked.intellijplugin.domain.Severity
import com.debricked.intellijplugin.domain.VulnerabilityDetailsBundle
import com.debricked.intellijplugin.domain.VulnerabilityDetailsContext
import com.debricked.intellijplugin.domain.VulnerabilityDependencyTree
import com.debricked.intellijplugin.domain.VulnerabilityDependencyTreeNode
import com.debricked.intellijplugin.domain.VulnerabilityFileRef
import com.debricked.intellijplugin.domain.VulnerabilityFinding
import com.debricked.intellijplugin.domain.VulnerabilityPageResult
import com.debricked.intellijplugin.domain.VulnerabilityQuery
import com.debricked.intellijplugin.domain.VulnerabilityReferenceLink
import com.debricked.intellijplugin.domain.VulnerabilityReviewStatusInfo
import com.debricked.intellijplugin.domain.VulnerabilityRootFixes
import com.debricked.intellijplugin.domain.VulnerabilityScoreSummary
import com.debricked.intellijplugin.domain.VulnerabilitySummarySource
import com.debricked.intellijplugin.domain.VulnerabilityTimeline
import com.debricked.intellijplugin.settings.DebrickedCredentialStore
import com.debricked.intellijplugin.settings.DebrickedDefaultTab
import com.debricked.intellijplugin.settings.DebrickedSettingsConfigurable
import com.debricked.intellijplugin.settings.DebrickedSettingsManager
import com.debricked.intellijplugin.ui.common.PlaceholderTabPanel
import com.debricked.intellijplugin.ui.common.ToolWindowContextHeader
import com.debricked.intellijplugin.ui.common.ToolWindowContextHeaderController
import com.debricked.intellijplugin.ui.dependency.DependencyTableModel
import com.debricked.intellijplugin.ui.dependency.DependencyColumns
import com.debricked.intellijplugin.ui.vulnerability.cvssDetailsDisplay
import com.debricked.intellijplugin.ui.vulnerability.CvssRenderer
import com.debricked.intellijplugin.ui.vulnerability.discoveredRelativeDisplay
import com.debricked.intellijplugin.ui.vulnerability.displaySeverity
import com.debricked.intellijplugin.ui.vulnerability.exploitedDisplay
import com.debricked.intellijplugin.ui.vulnerability.fallbackDependencies
import com.debricked.intellijplugin.ui.vulnerability.introducedDateText
import com.debricked.intellijplugin.ui.vulnerability.LeftAlignRenderer
import com.debricked.intellijplugin.ui.vulnerability.NameRenderer
import com.debricked.intellijplugin.ui.vulnerability.primaryIdentifier
import com.debricked.intellijplugin.ui.vulnerability.reviewStatusDisplay
import com.debricked.intellijplugin.ui.vulnerability.VulnerabilityTableModel
import com.intellij.ide.BrowserUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Point
import java.awt.RenderingHints
import java.text.DecimalFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.awt.geom.Path2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.net.URI
import java.net.URLEncoder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

private const val REPOSITORY_SHORTLIST_LIMIT = 25

data class BranchChoice(val id: String, val name: String) {
    override fun toString(): String = name.ifBlank { id }
}

private enum class DebrickedTab {
    DASHBOARD,
    VULNERABILITIES,
    DEPENDENCIES,
    LICENSES
}

class DebrickedTabbedToolWindowContent(
    private val project: Project,
    private val toolWindow: ToolWindow
) : DebrickedPluginManager.FindingsUpdateListener, ToolWindowContextHeaderController {

    private val pluginManager = project.getService(DebrickedPluginManager::class.java)
    private val apiClient = ApplicationManager.getApplication().getService(DebrickedApiClient::class.java)
    private val settings = DebrickedSettingsManager.getInstance()

    private val vulnerabilitiesPanel = VulnerabilitiesTabPanel(
        project = project,
        onRefreshFindings = { query -> pluginManager.refreshFindings(forceRefresh = true, query = query) },
        onQueryChanged = { query -> pluginManager.refreshFindings(forceRefresh = false, query = query) }
    )
    private val dashboardPanel = PlaceholderTabPanel(
        "Dashboard",
        "Summary widgets and quick actions will be added soon."
    )
    private val dependenciesPanel = PlaceholderTabPanel(
        "Dependencies",
        "Dependency inventory and details will be added soon."
    )
    private val licensesPanel = PlaceholderTabPanel(
        "Licenses",
        "License summary and policy information will be added soon."
    )

    private val vulnerabilitiesTabProvider = VulnerabilitiesTabProvider(vulnerabilitiesPanel, pluginManager)
    private val tabProviders = mapOf(
        DebrickedTab.DASHBOARD to PassiveTabProvider("Dashboard", dashboardPanel),
        DebrickedTab.VULNERABILITIES to vulnerabilitiesTabProvider,
        DebrickedTab.DEPENDENCIES to PassiveTabProvider("Dependencies", dependenciesPanel),
        DebrickedTab.LICENSES to PassiveTabProvider("Licenses", licensesPanel)
    )
    private val contents = linkedMapOf<DebrickedTab, Content>()
    private val headerPanels = mutableListOf<ToolWindowContextHeader>()

    private var repositories = emptyList<RepositoryChoice>()
    private var branches = emptyList<BranchChoice>()
    private var loadingBranches = false
    private var startupContextReady = false
    private var vulnerabilitiesDirty = true
    private var vulnerabilitiesForceRefreshPending = false

    init {
        DebrickedCredentialStore.loadFromStorage()
        installContents()
        installTitleActions()
        pluginManager.addListener(this)

        if (pluginManager.hasCredentials()) {
            loadRepositories(forceSelectionRefresh = false)
        } else {
            startupContextReady = true
            refreshTitleActions()
        }
    }

    override fun onFindingsUpdated(pageResult: VulnerabilityPageResult, state: FindingsState) {
        ApplicationManager.getApplication().invokeLater({
            vulnerabilitiesPanel.updateFindings(pageResult, state, pluginManager.getCurrentScanContext())
        }, ModalityState.any())
    }

    private fun installContents() {
        val contentFactory = ContentFactory.getInstance()
        toolWindow.contentManager.removeAllContents(true)

        addTabContent(contentFactory, DebrickedTab.DASHBOARD, "Dashboard", dashboardPanel)
        addTabContent(contentFactory, DebrickedTab.VULNERABILITIES, "Vulnerabilities", vulnerabilitiesPanel)
        addTabContent(contentFactory, DebrickedTab.DEPENDENCIES, "Dependencies", dependenciesPanel)
        addTabContent(contentFactory, DebrickedTab.LICENSES, "Licenses", licensesPanel)

        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                onToolWindowTabSelected()
            }
        })

        contents[defaultTab()]?.let { toolWindow.contentManager.setSelectedContent(it) }
        onToolWindowTabSelected()
    }

    private fun addTabContent(
        contentFactory: ContentFactory,
        tab: DebrickedTab,
        title: String,
        component: JComponent
    ) {
        val wrappedComponent = JPanel(BorderLayout()).apply {
            val header = ToolWindowContextHeader(this@DebrickedTabbedToolWindowContent)
            headerPanels += header
            add(header, BorderLayout.NORTH)
            add(component, BorderLayout.CENTER)
        }
        val content = contentFactory.createContent(wrappedComponent, title, false).apply {
            isCloseable = false
        }
        contents[tab] = content
        toolWindow.contentManager.addContent(content)
    }

    private fun installTitleActions() {
        toolWindow.setTitleActions(emptyList())
        toolWindow.setAdditionalGearActions(DefaultActionGroup())
    }

    private fun refreshTitleActions() {
        refreshHeaderPanels()
    }

    private fun refreshHeaderPanels() {
        headerPanels.forEach { it.syncFromController() }
    }

    private fun loadRepositories(forceSelectionRefresh: Boolean) {
        startupContextReady = false
        refreshTitleActions()

        val apiUrl = settings.getApiUrl()
        val authMethod = settings.getAuthMethod()
        val accessToken = DebrickedCredentialStore.getAccessToken() ?: ""
        val username = settings.getUsername()
        val password = DebrickedCredentialStore.getPassword() ?: ""

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val loadedRepositories = apiClient.connectAndGetRepositories(
                    apiUrl = apiUrl,
                    authMethod = authMethod,
                    accessToken = accessToken,
                    username = username,
                    password = password
                ).map { RepositoryChoice(it.id, it.name.ifBlank { it.id }, it.organizationId, it.defaultBranch) }

                ApplicationManager.getApplication().invokeLater({
                    repositories = loadedRepositories
                    refreshTitleActions()

                    val selected = chooseInitialRepository(loadedRepositories)
                    if (selected != null) {
                        handleRepositorySelected(selected, forceSelectionRefresh)
                    } else {
                        startupContextReady = true
                        onToolWindowTabSelected()
                    }
                }, ModalityState.any())
            } catch (_: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    repositories = emptyList()
                    branches = emptyList()
                    startupContextReady = true
                    refreshTitleActions()
                }, ModalityState.any())
            }
        }
    }

    private fun chooseInitialRepository(availableRepositories: List<RepositoryChoice>): RepositoryChoice? {
        val savedRepositoryId = settings.getRepositoryId()
        if (savedRepositoryId.isNotBlank()) {
            availableRepositories.firstOrNull { it.id == savedRepositoryId }?.let { return it }
        }
        return availableRepositories.firstOrNull()
    }

    private fun handleRepositorySelected(repository: RepositoryChoice, forceSelectionRefresh: Boolean = false) {
        val previousRepositoryId = settings.getRepositoryId()
        val repositoryChanged = previousRepositoryId != repository.id

        if (!repositoryChanged && startupContextReady && !forceSelectionRefresh && branches.isNotEmpty()) {
            return
        }

        if (repositoryChanged && previousRepositoryId.isNotBlank()) {
            pluginManager.invalidateFindingsCache(previousRepositoryId)
        }

        settings.setRepositoryId(repository.id)
        settings.setRepositoryName(repository.name)
        settings.setDefaultBranch(repository.defaultBranch)
        settings.pushRecentRepositoryId(repository.id)

        if (repositoryChanged) {
            settings.setSelectedBranchId("")
            settings.setSelectedBranchName("")
            branches = emptyList()
            vulnerabilitiesDirty = true
            startupContextReady = false
            vulnerabilitiesTabProvider.invalidate(currentTabContext())
            refreshTitleActions()
        }

        loadBranches(repository, forceSelectionRefresh)
    }

    private fun loadBranches(repository: RepositoryChoice, forceSelectionRefresh: Boolean) {
        if (loadingBranches) return
        loadingBranches = true
        refreshTitleActions()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val loadedBranches = apiClient.getBranches(repository.id).map { BranchChoice(it.id, it.name) }
                val repoDefault = repository.defaultBranch ?: settings.getDefaultBranch()

                ApplicationManager.getApplication().invokeLater({
                    branches = loadedBranches
                    refreshTitleActions()

                    val selectedBranch = chooseInitialBranch(loadedBranches, repoDefault)
                    if (selectedBranch != null) {
                        applyBranchSelection(selectedBranch, forceSelectionRefresh)
                    } else {
                        settings.setSelectedBranchId("")
                        settings.setSelectedBranchName("")
                        startupContextReady = true
                        requestVulnerabilityLoad(forceSelectionRefresh)
                        refreshTitleActions()
                    }
                }, ModalityState.any())
            } catch (_: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    branches = emptyList()
                    startupContextReady = true
                    refreshTitleActions()
                }, ModalityState.any())
            } finally {
                loadingBranches = false
            }
        }
    }

    private fun chooseInitialBranch(branches: List<BranchChoice>, defaultBranch: String?): BranchChoice? {
        val savedBranchId = settings.getSelectedBranchId()
        if (savedBranchId.isNotBlank()) {
            branches.firstOrNull { it.id == savedBranchId }?.let { return it }
        }

        if (!defaultBranch.isNullOrBlank()) {
            branches.firstOrNull {
                it.name.equals(defaultBranch, ignoreCase = true) || it.id.equals(defaultBranch, ignoreCase = true)
            }?.let { return it }
        }

        return branches.firstOrNull()
    }

    private fun applyBranchSelection(branch: BranchChoice, forceSelectionRefresh: Boolean) {
        val previousBranchId = settings.getSelectedBranchId()
        val branchChanged = previousBranchId != branch.id

        if (!branchChanged && startupContextReady && !forceSelectionRefresh) {
            return
        }

        settings.setSelectedBranchId(branch.id)
        settings.setSelectedBranchName(branch.name)
        startupContextReady = true

        if (branchChanged) {
            val repositoryId = settings.getRepositoryId()
            pluginManager.invalidateFindingsCache(repositoryId, previousBranchId.ifBlank { null })
            pluginManager.invalidateFindingsCache(repositoryId, branch.id)
            vulnerabilitiesDirty = true
        }

        refreshTitleActions()
        requestVulnerabilityLoad(forceRefresh = forceSelectionRefresh || branchChanged)
    }

    private fun currentTabContext(): TabContext {
        val repositoryId = settings.getRepositoryId()
        val branchId = settings.getSelectedBranchId().ifBlank { null }
        val branchName = settings.getSelectedBranchName().ifBlank { null }
        return TabContext(repositoryId, branchId, branchName)
    }

    private fun onToolWindowTabSelected() {
        val selected = selectedTab()
        if (!startupContextReady) return
        val context = currentTabContext()
        when (selected) {
            DebrickedTab.VULNERABILITIES -> {
                if (vulnerabilitiesDirty) {
                    vulnerabilitiesTabProvider.loadData(context, vulnerabilitiesForceRefreshPending)
                    vulnerabilitiesDirty = false
                    vulnerabilitiesForceRefreshPending = false
                }
            }
            else -> {}
        }
    }

    private fun requestVulnerabilityLoad(forceRefresh: Boolean) {
        vulnerabilitiesDirty = true
        vulnerabilitiesForceRefreshPending = vulnerabilitiesForceRefreshPending || forceRefresh
        if (selectedTab() == DebrickedTab.VULNERABILITIES && startupContextReady) {
            onToolWindowTabSelected()
        }
    }

    private fun selectedTab(): DebrickedTab? {
        val selectedContent = toolWindow.contentManager.selectedContent ?: return null
        return contents.entries.firstOrNull { it.value == selectedContent }?.key
    }

    private fun defaultTab(): DebrickedTab = when (settings.getDefaultTab()) {
        DebrickedDefaultTab.DASHBOARD -> DebrickedTab.DASHBOARD
        DebrickedDefaultTab.VULNERABILITIES -> DebrickedTab.VULNERABILITIES
        DebrickedDefaultTab.DEPENDENCIES -> DebrickedTab.DEPENDENCIES
        DebrickedDefaultTab.LICENSES -> DebrickedTab.LICENSES
    }

    override fun repositoryActionText(): String =
        repositories.firstOrNull { it.id == settings.getRepositoryId() }?.name
            ?: settings.getRepositoryName().ifBlank { "Repository" }

    override fun branchActionText(): String =
        branches.firstOrNull { it.id == settings.getSelectedBranchId() }?.name
            ?: settings.getSelectedBranchName().ifBlank { "Branch" }

    override fun hasCredentials(): Boolean = pluginManager.hasCredentials()
    override fun hasRepositories(): Boolean = repositories.isNotEmpty()
    override fun hasBranches(): Boolean = branches.isNotEmpty()
    override fun isLoadingBranches(): Boolean = loadingBranches
    override fun availableRepositories(): List<RepositoryChoice> = repositories
    override fun availableBranches(): List<BranchChoice> = branches

    override fun selectRepository(repository: RepositoryChoice) {
        handleRepositorySelected(repository, forceSelectionRefresh = true)
    }

    override fun selectBranch(branch: BranchChoice) {
        applyBranchSelection(branch, forceSelectionRefresh = true)
    }

    override fun refreshRepositoriesFromToolbar() {
        loadRepositories(forceSelectionRefresh = true)
    }

    internal fun refreshFindingsFromToolbar() {
        pluginManager.refreshFindings(forceRefresh = true)
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, DebrickedSettingsConfigurable::class.java)
        DebrickedCredentialStore.loadFromStorage()
        pluginManager.clearFindingsCache()
        vulnerabilitiesDirty = true
        repositories = emptyList()
        branches = emptyList()
        startupContextReady = false
        refreshTitleActions()

        if (pluginManager.hasCredentials()) {
            loadRepositories(forceSelectionRefresh = false)
        } else {
            startupContextReady = true
            refreshTitleActions()
        }
    }

    override fun openSettingsFromToolbar() {
        openSettings()
    }
}

private class VulnerabilitiesTabPanel(
    private val project: Project,
    private val onRefreshFindings: (VulnerabilityQuery) -> Unit,
    private val onQueryChanged: (VulnerabilityQuery) -> Unit
) : JPanel(BorderLayout()) {
    private enum class DisplayColumn(
        val title: String,
        val modelIndex: Int,
        val toggleable: Boolean,
        val defaultVisible: Boolean
    ) {
        NAME("Name", 0, false, true),
        INTRODUCED("Introduced", 1, true, true),
        CVSS("CVSS", 2, true, true),
        DEPENDENCIES("Dependencies", 3, true, true),
        REACHABLE_PATH("Reachable Path", 4, true, false),
        REVIEW_STATUS("Review Status", 5, true, true),
        EXPLOITED("Exploited (CISA)", 6, true, false)
    }

    private enum class SortMode(val label: String, val columnIndex: Int, val ascending: Boolean) {
        CVSS("CVSS", 2, false),
        NAME("Name", 0, true),
        INTRODUCED("Introduced", 1, false),
        DEPENDENCIES("Dependencies", 3, true),
        REACHABLE_PATH("Reachable path", 4, true),
        REVIEW_STATUS("Review status", 5, true),
        EXPLOITED("Exploited (CISA)", 6, true)
    }

    private enum class GroupMode(val label: String, val columnIndex: Int?) {
        NONE("None", null),
        DEPENDENCIES("Dependencies", 3),
        REACHABLE_PATH("Reachable path", 4),
        REVIEW_STATUS("Review status", 5),
        EXPLOITED("Exploited (CISA)", 6)
    }

    private fun SortMode.apiSortColumn(): String = when (this) {
        SortMode.CVSS -> "cvss"
        SortMode.NAME -> "name"
        SortMode.INTRODUCED -> "discovered"
        SortMode.DEPENDENCIES -> "dependency"
        SortMode.REACHABLE_PATH -> "reachabilityAnalysis"
        SortMode.REVIEW_STATUS -> "vulnerabilityStatus"
        SortMode.EXPLOITED -> "exploitationStatus"
    }

    private val apiClient = ApplicationManager.getApplication().getService(DebrickedApiClient::class.java)
    private val settings = DebrickedSettingsManager.getInstance()
    private val model = VulnerabilityTableModel()
    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        fillsViewportHeight = true
        rowHeight = 24
    }
    private val detailsPanel = VulnerabilityDetailsPanel(project) { selectedStatus, reviewInfo ->
        applyReviewStatusChange(selectedStatus, reviewInfo)
    }
    private val statusLabel = JBLabel("Loading...").apply { foreground = JBColor.GRAY }
    private val countLabel = JBLabel("").apply { foreground = JBColor.GRAY }
    private val pageLabel = JBLabel("Page 1").apply { foreground = JBColor.GRAY }
    private val previousPageButton = JButton("Prev")
    private val nextPageButton = JButton("Next")
    private val pageSizeCombo = ComboBox(arrayOf(15, 25, 50, 100)).apply {
        selectedItem = 25
        toolTipText = "Rows per page"
    }
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search by name or dependency"
        preferredSize = JBUI.size(360, preferredSize.height)
        minimumSize = JBUI.size(260, minimumSize.height)
    }
    private var currentPage = 1
    private var rowsPerPage = 25
    private var hasNextPage = false
    private var totalCount: Int? = null
    private val searchDebounceTimer = javax.swing.Timer(250) {
        currentPage = 1
        dispatchQuery(forceRefresh = false)
    }.apply {
        isRepeats = false
    }
    private val refreshAction = object : AnAction("Refresh", "Refresh vulnerability findings", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) {
            dispatchQuery(forceRefresh = true)
        }

        override fun displayTextInToolbar(): Boolean = false

        override fun update(e: AnActionEvent) {
            e.presentation.text = "Refresh findings"
            e.presentation.icon = AllIcons.Actions.Refresh
            e.presentation.description = "Refresh vulnerability findings"
            e.presentation.isEnabled = true
        }
    }
    private val optionsAction = FindingsOptionsActionGroup()
    private val sidebarToolbar = ActionManager.getInstance()
        .createActionToolbar("DebrickedVulnerabilitySidebar", DefaultActionGroup(refreshAction, optionsAction), true)
    private val sidebarPanel = JPanel(BorderLayout())
    private var sortMode = SortMode.CVSS
    private var groupMode = GroupMode.NONE
    private val visibleColumns = linkedSetOf<DisplayColumn>().apply {
        addAll(DisplayColumn.values().filter { it.defaultVisible })
    }
    private val visibleSeverities = linkedSetOf(
        Severity.CRITICAL,
        Severity.HIGH,
        Severity.MEDIUM,
        Severity.LOW,
        Severity.UNKNOWN
    )
    private val detailsCache = mutableMapOf<String, VulnerabilityDetailsBundle>()
    private var currentScanContext: DebrickedScanContext? = null
    private var currentDetailsContext: VulnerabilityDetailsContext? = null
    private var detailsRequestToken = 0

    private val allowedRowsPerPage = setOf(15, 25, 50, 100)
    private var persistedDividerLocation = -1
    private var splitPane: JSplitPane? = null

    init {
        border = JBUI.Borders.empty(8)
        restoreViewState()
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateDetailsForSelection()
            }
        }

        applyColumnVisibility()

        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSearchChanged()
            override fun removeUpdate(e: DocumentEvent) = onSearchChanged()
            override fun changedUpdate(e: DocumentEvent) = onSearchChanged()
        })

        previousPageButton.addActionListener {
            if (currentPage <= 1) return@addActionListener
            currentPage -= 1
            dispatchQuery(forceRefresh = false)
        }
        nextPageButton.addActionListener {
            if (!hasNextPage) return@addActionListener
            currentPage += 1
            dispatchQuery(forceRefresh = false)
        }
        pageSizeCombo.addActionListener {
            val selected = pageSizeCombo.selectedItem as? Int ?: return@addActionListener
            if (selected == rowsPerPage) return@addActionListener
            rowsPerPage = selected
            persistViewState()
            currentPage = 1
            dispatchQuery(forceRefresh = false)
        }

        val searchBar = JPanel(GridBagLayout()).apply {
            isOpaque = false
            add(searchField, GridBagConstraints().apply {
                gridx = 0
                weightx = 0.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0, 0, 0, 8)
            })
            add(statusLabel, GridBagConstraints().apply {
                gridx = 1
                weightx = 0.0
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, 8)
            })
            add(previousPageButton, GridBagConstraints().apply {
                gridx = 2
                weightx = 0.0
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, 6)
            })
            add(nextPageButton, GridBagConstraints().apply {
                gridx = 3
                weightx = 0.0
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, 6)
            })
            add(pageLabel, GridBagConstraints().apply {
                gridx = 4
                weightx = 0.0
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, 6)
            })
            add(pageSizeCombo, GridBagConstraints().apply {
                gridx = 5
                weightx = 0.0
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, 8)
            })
            add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
                gridx = 6
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
            })
            add(countLabel, GridBagConstraints().apply {
                gridx = 7
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, 0)
            })
        }

        val tablePane = JBScrollPane(table)
        val detailsScrollPane = JBScrollPane(detailsPanel).apply {
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = JBUI.Borders.empty()
        }
        splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tablePane, detailsScrollPane).apply {
            resizeWeight = 0.5
            border = JBUI.Borders.emptyTop(8)
        }
        splitPane?.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            if ((splitPane?.width ?: 0) > 0) {
                persistViewState()
            }
        }

        sidebarToolbar.setTargetComponent(this)
        sidebarToolbar.setOrientation(SwingConstants.VERTICAL)
        sidebarToolbar.setMiniMode(true)
        sidebarToolbar.setMinimumButtonSize(ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        sidebarToolbar.component.isOpaque = false
        sidebarToolbar.component.border = JBUI.Borders.empty()
        sidebarPanel.isOpaque = false
        sidebarPanel.border = JBUI.Borders.empty(4, 0, 0, 2)
        sidebarPanel.add(sidebarToolbar.component, BorderLayout.NORTH)

        add(searchBar, BorderLayout.NORTH)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(sidebarPanel, BorderLayout.WEST)
            splitPane?.let { add(it, BorderLayout.CENTER) }
        }, BorderLayout.CENTER)
        SwingUtilities.invokeLater {
            splitPane?.let {
                if (persistedDividerLocation > 0) {
                    it.dividerLocation = persistedDividerLocation
                } else {
                    it.setDividerLocation(0.5)
                }
            }
        }
        showEmptyState("Loading vulnerabilities")
        applySortMode()
    }

    private fun restoreViewState() {
        val savedColumns = settings.getVulnerabilitiesVisibleColumns()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { token -> DisplayColumn.values().firstOrNull { it.name == token } }
        if (savedColumns.isNotEmpty()) {
            visibleColumns.clear()
            visibleColumns.addAll(savedColumns)
        }

        sortMode = SortMode.values().firstOrNull { it.name == settings.getVulnerabilitiesSortMode() } ?: SortMode.CVSS
        groupMode = GroupMode.values().firstOrNull { it.name == settings.getVulnerabilitiesGroupMode() } ?: GroupMode.NONE

        rowsPerPage = settings.getVulnerabilitiesRowsPerPage().takeIf { it in allowedRowsPerPage } ?: 25
        pageSizeCombo.selectedItem = rowsPerPage
        searchField.text = settings.getVulnerabilitiesSearchText()
        persistedDividerLocation = settings.getVulnerabilitiesDividerLocation()
    }

    private fun persistViewState() {
        settings.setVulnerabilitiesVisibleColumns(visibleColumns.joinToString(",") { it.name })
        settings.setVulnerabilitiesSortMode(sortMode.name)
        settings.setVulnerabilitiesGroupMode(groupMode.name)
        settings.setVulnerabilitiesRowsPerPage(rowsPerPage)
        settings.setVulnerabilitiesSearchText(searchField.text.trim())
        splitPane?.let {
            if (it.dividerLocation > 0) {
                settings.setVulnerabilitiesDividerLocation(it.dividerLocation)
            }
        }
    }

    override fun removeNotify() {
        persistViewState()
        super.removeNotify()
    }

    fun updateFindings(
        pageResult: VulnerabilityPageResult,
        state: FindingsState,
        context: DebrickedScanContext?
    ) {
        currentScanContext = context
        val findings = pageResult.findings
        currentPage = pageResult.page.coerceAtLeast(1)
        rowsPerPage = pageResult.rowsPerPage.coerceAtLeast(1)
        hasNextPage = pageResult.hasNext
        totalCount = pageResult.totalCount
        model.setFindings(findings)
        refreshView()
        statusLabel.text = when (state) {
            FindingsState.LOADING -> "Loading..."
            FindingsState.TIMEOUT -> "Connection timed out. Showing last available results"
            FindingsState.NO_REMOTE_RESULTS -> "No findings available"
            FindingsState.STALE_DEPENDENCY_CHANGES -> "Local dependency changes detected"
            FindingsState.STALE_COMMIT -> "Latest branch results"
            FindingsState.CURRENT -> ""
        }
        statusLabel.isVisible = statusLabel.text.isNotBlank()
        if (findings.isEmpty()) {
            detailsPanel.setFinding(null)
            showEmptyState(statusLabel.text)
        } else {
            showTableState()
            updateDetailsForSelection(context)
        }
    }

    private fun showTableState() {
        table.isEnabled = true
        searchField.isEnabled = true
        pageSizeCombo.isEnabled = true
        previousPageButton.isEnabled = currentPage > 1
        nextPageButton.isEnabled = hasNextPage
        pageLabel.text = "Page $currentPage"
        pageSizeCombo.selectedItem = rowsPerPage
    }

    private fun showEmptyState(message: String) {
        table.emptyText.text = message
    }

    private fun applyFilter() {
        refreshView()
    }

    private fun onSearchChanged() {
        searchDebounceTimer.restart()
        persistViewState()
        applyFilter()
    }

    private fun applySortMode() {
        refreshView()
    }

    private fun refreshView() {
        model.rebuildView(
            textFilter = "",
            visibleSeverities = visibleSeverities,
            sortColumn = sortMode.columnIndex,
            sortAscending = sortMode.ascending,
            groupColumn = groupMode.columnIndex
        )
        val visibleCount = model.visibleFindingCount()
        val knownTotal = totalCount
        val pageStart = if (visibleCount == 0) 0 else ((currentPage - 1) * rowsPerPage) + 1
        val pageEnd = (pageStart + visibleCount - 1).coerceAtLeast(0)
        countLabel.text = if (knownTotal != null && knownTotal >= 0) {
            "$pageStart–$pageEnd of $knownTotal"
        } else if (visibleCount > 0) {
            "$visibleCount entries"
        } else {
            ""
        }
        previousPageButton.isEnabled = currentPage > 1
        nextPageButton.isEnabled = hasNextPage
        pageLabel.text = "Page $currentPage"
        updateDetailsForSelection()
        sidebarToolbar.updateActionsImmediately()
    }

    private fun dispatchQuery(forceRefresh: Boolean) {
        val query = VulnerabilityQuery(
            search = searchField.text.trim(),
            page = currentPage,
            rowsPerPage = rowsPerPage,
            sortColumn = sortMode.apiSortColumn(),
            order = if (sortMode.ascending) "asc" else "desc"
        )
        if (forceRefresh) {
            onRefreshFindings(query)
        } else {
            onQueryChanged(query)
        }
    }

    private fun applyColumnVisibility() {
        table.createDefaultColumnsFromModel()
        DisplayColumn.values().forEach { column ->
            if (!visibleColumns.contains(column)) {
                findViewColumnByModelIndex(column.modelIndex)?.let { table.columnModel.removeColumn(it) }
            }
        }
        applyColumnRenderers()
    }

    private fun applyColumnRenderers() {
        DisplayColumn.values().forEach { column ->
            val renderer = when (column) {
                DisplayColumn.NAME -> NameRenderer()
                DisplayColumn.CVSS -> CvssRenderer()
                else -> LeftAlignRenderer()
            }
            findViewColumnByModelIndex(column.modelIndex)?.cellRenderer = renderer
        }
    }

    private fun findViewColumnByModelIndex(modelIndex: Int): javax.swing.table.TableColumn? {
        val columnModel = table.columnModel
        for (i in 0 until columnModel.columnCount) {
            val column = columnModel.getColumn(i)
            if (column.modelIndex == modelIndex) {
                return column
            }
        }
        return null
    }

    private fun setSortMode(mode: SortMode) {
        sortMode = mode
        currentPage = 1
        persistViewState()
        applySortMode()
        dispatchQuery(forceRefresh = false)
    }

    private fun setGroupMode(mode: GroupMode) {
        groupMode = mode
        persistViewState()
        applySortMode()
    }

    private fun updateDetailsForSelection(context: DebrickedScanContext? = null) {
        val resolvedContext = context ?: currentScanContext
        val viewRow = table.selectedRow
        if (viewRow < 0 || viewRow >= table.rowCount) {
            currentDetailsContext = null
            detailsRequestToken += 1
            detailsPanel.setFinding(null)
            return
        }
        val finding = model.getFindingAt(viewRow)
        if (finding == null) {
            currentDetailsContext = null
            detailsRequestToken += 1
            detailsPanel.setFinding(null)
            return
        }
        detailsPanel.setFinding(finding)
        val detailsContext = buildDetailsContext(finding, resolvedContext)
        currentDetailsContext = detailsContext
        if (detailsContext != null) {
            loadDetailsBundle(detailsContext, finding)
        }
    }

    private fun updateDetailsForSelection() {
        updateDetailsForSelection(null)
    }

    private fun buildDetailsContext(
        finding: VulnerabilityFinding,
        context: DebrickedScanContext?
    ): VulnerabilityDetailsContext? {
        val vulnerabilityId = finding.vulnerabilityId?.takeIf { it.isNotBlank() } ?: return null
        val repositoryId = context?.repositoryId
            ?.takeIf { it.isNotBlank() }
            ?: finding.scanContext.repositoryId.takeIf { it.isNotBlank() }
            ?: return null
        return VulnerabilityDetailsContext(
            vulnerabilityId = vulnerabilityId,
            repositoryId = repositoryId,
            branchName = context?.branchName ?: finding.scanContext.branchName,
            commitId = finding.debrickedCommitId?.takeIf { it.isNotBlank() },
            title = finding.title,
            cveId = finding.cveId
        )
    }

    private fun loadDetailsBundle(context: VulnerabilityDetailsContext, finding: VulnerabilityFinding) {
        val cacheKey = detailsCacheKey(context)
        val cached = detailsCache[cacheKey]
        if (cached != null) {
            detailsPanel.applyDetails(finding, context, cached)
            loadDependencyTreeLazy(context, finding, cached)
            return
        }
        detailsPanel.showDetailsLoading()
        val requestToken = ++detailsRequestToken
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val bundle = fetchDetailsBundle(context)
                ApplicationManager.getApplication().invokeLater({
                    if (requestToken != detailsRequestToken || currentDetailsContext != context) return@invokeLater
                    detailsCache[cacheKey] = bundle
                    detailsPanel.applyDetails(finding, context, bundle)
                    loadDependencyTreeLazy(context, finding, bundle)
                }, ModalityState.any())
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    if (requestToken != detailsRequestToken || currentDetailsContext != context) return@invokeLater
                    detailsPanel.showDetailsError(e.message ?: "Failed to load vulnerability details.")
                }, ModalityState.any())
            }
        }
    }

    private fun fetchDetailsBundle(context: VulnerabilityDetailsContext): VulnerabilityDetailsBundle {
        val summarySources = safeDetailsCall(emptyList<VulnerabilitySummarySource>()) {
            apiClient.getVulnerabilityRefSummary(context.vulnerabilityId)
        }
        val scoreSummaries = safeDetailsCall(emptyList<VulnerabilityScoreSummary>()) {
            apiClient.getVulnerabilityCveSummary(context.vulnerabilityId)
        }
        val reviewStatusInfo = safeDetailsCall<VulnerabilityReviewStatusInfo?>(null) {
            apiClient.getVulnerabilityReviewStatus(context.vulnerabilityId, context.repositoryId)
        }
        val repositoryStatuses = reviewStatusInfo?.repositoryStatuses ?: emptyList()
        val affectedDependencies = safeDetailsCall(emptyList()) {
            apiClient.getVulnerabilityAffectedDependencies(context.vulnerabilityId, context.repositoryId, context.commitId)
        }
        val files = safeDetailsCall(emptyList<VulnerabilityFileRef>()) {
            apiClient.getVulnerabilityFiles(context.vulnerabilityId, context.repositoryId, context.commitId)
        }
        val rootFixes = safeDetailsCall<VulnerabilityRootFixes?>(null) {
            apiClient.getVulnerabilityRootFixes(context.vulnerabilityId, context.repositoryId, context.commitId)
        }
        val vulnerableTimelines = safeDetailsCall(emptyList<VulnerabilityTimeline>()) {
            apiClient.getVulnerabilityVulnerableTimeline(context.vulnerabilityId, context.repositoryId)
        }
        val references = safeDetailsCall(emptyList<VulnerabilityReferenceLink>()) {
            apiClient.getVulnerabilityReferences(context.vulnerabilityId)
        }
        val reachabilityDetails = safeDetailsCall<com.debricked.intellijplugin.domain.VulnerabilityReachabilityDetails?>(null) {
            context.commitId?.takeIf { it.isNotBlank() }
                ?.let { apiClient.getVulnerabilityReachabilityData(context.vulnerabilityId, it) }
        }
        return VulnerabilityDetailsBundle(
            summarySources = summarySources,
            scoreSummaries = scoreSummaries,
            cvssDetails = null,
            dates = com.debricked.intellijplugin.domain.VulnerabilityDates(),
            affectedDependencies = affectedDependencies,
            files = files,
            dependencyTree = null,
            repositoryStatuses = repositoryStatuses,
            reviewStatusInfo = reviewStatusInfo,
            rootFixes = rootFixes,
            vulnerableTimelines = vulnerableTimelines,
            references = references,
            reachabilityDetails = reachabilityDetails
        )
    }

    private fun loadDependencyTreeLazy(
        context: VulnerabilityDetailsContext,
        finding: VulnerabilityFinding,
        bundle: VulnerabilityDetailsBundle
    ) {
        if (bundle.dependencyTree != null) return
        val file = bundle.files.firstOrNull() ?: return
        val requestToken = detailsRequestToken
        ApplicationManager.getApplication().executeOnPooledThread {
            val tree = safeDetailsCall<VulnerabilityDependencyTree?>(null) {
                apiClient.getVulnerabilityDependencyTree(
                    context.vulnerabilityId,
                    file.id,
                    context.repositoryId,
                    context.commitId
                )
            } ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (requestToken != detailsRequestToken || currentDetailsContext != context) return@invokeLater
                val key = detailsCacheKey(context)
                val existing = detailsCache[key] ?: bundle
                val updated = existing.copy(dependencyTree = tree)
                detailsCache[key] = updated
                detailsPanel.applyDetails(finding, context, updated)
            }, ModalityState.any())
        }
    }

    private fun <T> safeDetailsCall(defaultValue: T, supplier: () -> T): T {
        return try {
            supplier()
        } catch (_: Exception) {
            defaultValue
        }
    }

    private fun applyReviewStatusChange(selectedStatus: String, reviewInfo: VulnerabilityReviewStatusInfo?) {
        val context = currentDetailsContext ?: return
        val comment = promptForReviewComment(selectedStatus, reviewInfo) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                apiClient.setVulnerabilityReviewStatus(context.vulnerabilityId, context.repositoryId, selectedStatus, comment)
                detailsCache.remove(detailsCacheKey(context))
                ApplicationManager.getApplication().invokeLater({
                    dispatchQuery(forceRefresh = true)
                }, ModalityState.any())
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    Messages.showErrorDialog(
                        project,
                        e.message ?: "Failed to update review status.",
                        "Debricked Review Status"
                    )
                }, ModalityState.any())
            }
        }
    }

    private fun promptForReviewComment(
        selectedStatus: String,
        reviewInfo: VulnerabilityReviewStatusInfo?
    ): String? {
        val enforceComment = reviewInfo?.enforceComment == true
        val minLength = reviewInfo?.commentMinLength ?: 0
        if (!enforceComment && minLength <= 0) {
            return reviewInfo?.oldComment
        }
        while (true) {
            val input = Messages.showInputDialog(
                project,
                "Enter a comment for review status '$selectedStatus'.",
                "Debricked Review Status",
                null,
                reviewInfo?.oldComment.orEmpty(),
                null
            ) ?: return null
            if (input.trim().length >= minLength) {
                return input.trim()
            }
            Messages.showErrorDialog(
                project,
                "Comment must be at least $minLength characters long.",
                "Debricked Review Status"
            )
        }
    }

    private fun detailsCacheKey(context: VulnerabilityDetailsContext): String =
        listOf(context.vulnerabilityId, context.repositoryId, context.commitId.orEmpty()).joinToString("|")

    private inner class FindingsOptionsActionGroup : ActionGroup() {
        private val columnActions = DisplayColumn.values()
            .filter { it.toggleable }
            .map { column -> ColumnVisibilityAction(column) }
        private val sortActions = SortMode.values().map { mode -> SortModeToggleAction(mode) }
        private val groupActions = GroupMode.values().map { mode -> GroupModeToggleAction(mode) }

        override fun isPopup(): Boolean = true
        override fun displayTextInToolbar(): Boolean = false

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val actions = mutableListOf<AnAction>()
            actions.add(Separator.create("Columns"))
            actions.addAll(columnActions)
            actions.add(Separator.create("Sort By"))
            actions.addAll(sortActions)
            actions.add(Separator.create("Group By"))
            actions.addAll(groupActions)
            return actions.toTypedArray()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.text = "View options"
            e.presentation.icon = AllIcons.Actions.Show
            e.presentation.description = "Choose columns, sort and group vulnerability findings"
            e.presentation.isEnabled = model.rowCount > 0
        }
    }

    private inner class SortModeToggleAction(private val mode: SortMode) : ToggleAction(mode.label) {
        override fun isSelected(e: AnActionEvent): Boolean = sortMode == mode

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) {
                setSortMode(mode)
            }
        }
    }

    private inner class GroupModeToggleAction(private val mode: GroupMode) : ToggleAction(mode.label) {
        override fun isSelected(e: AnActionEvent): Boolean = groupMode == mode

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) {
                setGroupMode(mode)
            }
        }
    }

    private inner class ColumnVisibilityAction(
        private val column: DisplayColumn
    ) : ToggleAction(column.title) {
        override fun isSelected(e: AnActionEvent): Boolean = visibleColumns.contains(column)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) {
                visibleColumns.add(column)
            } else {
                visibleColumns.remove(column)
            }
            persistViewState()
            applyColumnVisibility()
            refreshView()
        }
    }
}

private class VulnerabilityDetailsPanel(
    private val project: Project,
    private val onReviewStatusApply: (String, VulnerabilityReviewStatusInfo?) -> Unit
) : JPanel(BorderLayout()) {
    private val vulnerabilityCaptionLabel = JBLabel("Vulnerability").apply {
        foreground = JBColor(0xD81B60, 0xF06292)
    }
    private val titleLabel = JBLabel("Select a vulnerability").apply {
        font = font.deriveFont(Font.BOLD, 16f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = JBColor.BLUE
    }
    private val discoveredHeaderLabel = JBLabel("Discovered").apply {
        font = font.deriveFont(Font.BOLD, 12f)
    }
    private val identifierLabel = JBLabel("").apply { foreground = JBColor.GRAY }
    private val discoveredValueLabel = JBLabel("").apply { foreground = JBColor.GRAY }
    private val cvssLabel = JBLabel("")
    private val statusLabel = JBLabel("").apply { verticalAlignment = SwingConstants.TOP }
    private val dependenciesLabel = JBLabel("").apply { verticalAlignment = SwingConstants.TOP }
    private val dependencyInlinePrefixLabel = JBLabel("in dependency").apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(Font.PLAIN)
    }
    private val dependencyInlineChipButton = JButton("").apply {
        isOpaque = true
        isContentAreaFilled = true
        isFocusPainted = false
        isBorderPainted = true
        background = JBColor(0xFFFFFF, 0x2F3440)
        foreground = JBColor(0x1F3F80, 0xC6DBFF)
        font = font.deriveFont(Font.BOLD, 12f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        margin = Insets(0, 0, 0, 0)
        horizontalAlignment = SwingConstants.LEFT
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border()),
            JBUI.Borders.empty(3, 8)
        )
        // Dependency tab navigation is planned for a later phase.
        toolTipText = "Dependency navigation will be available in a later phase"
    }
    private val dependencyInlinePanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(0)
        add(dependencyInlinePrefixLabel)
        add(dependencyInlineChipButton)
    }
    private val fixedVersionLabel = JBLabel("")
    private val linkLabel = JBLabel("").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = JBColor.BLUE
        isVisible = false
    }
    private val scanLabel = JBLabel("").apply { foreground = JBColor.GRAY }
    private val detailsStatusLabel = JBLabel("").apply { foreground = JBColor.GRAY }
    private val scoresArea = createDetailsArea()
    private val scoreLabels = (0..8).map { JBLabel("") }
    private val scoreBoxPanel = JPanel()
    private val advisoryCardsPanel = JPanel(GridLayout(1, 3, JBUI.scale(12), 0)).apply {
        isOpaque = false
        alignmentX = TOP_ALIGNMENT
    }
    private val cweCard = createAdvisoryCard("CWE")
    private val githubCard = createAdvisoryCard("GitHub")
    private val nvdCard = createAdvisoryCard("NVD")
    private val actionsSectionBody = createSectionBodyPanel()
    private val cisaKevSectionBody = createSectionBodyPanel()
    private val introducedSectionBody = createSectionBodyPanel()
    private val fixesSectionBody = createSectionBodyPanel()
    private val referencesSectionBody = createSectionBodyPanel()
    private val reviewStatusCombo = ComboBox(arrayOf("unexamined", "vulnerable", "unaffected", "remediated")).apply {
        maximumSize = Dimension(JBUI.scale(180), preferredSize.height)
    }
    private val applyReviewStatusButton = JButton("Apply").apply {
        toolTipText = "Update review status in Debricked"
    }
    private val reviewActionPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        add(JBLabel("Review action: "))
        add(reviewStatusCombo)
        add(Box.createHorizontalStrut(8))
        add(applyReviewStatusButton)
    }
    private val emptyStateLabel = JBLabel("Please select a Vulnerability...").apply {
        foreground = JBColor.GRAY
    }
    private val headlinePanel = JPanel()
    private val summaryRowPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
    }
    private val sectionPanels = mutableListOf<JPanel>()
    private val sectionAreaToPanel = mutableMapOf<JTextArea, JPanel>()
    private val sectionBodyToPanel = mutableMapOf<JPanel, JPanel>()
    private val localProjectRepositoryIdentity by lazy { resolveLocalProjectRepositoryIdentity(project.basePath) }
    private var renderedReferences = emptyList<VulnerabilityReferenceLink>()
    private var renderedReferenceColumns = 0
    private var findingUrl: String? = null
    private var currentReviewInfo: VulnerabilityReviewStatusInfo? = null

    private data class FileLocationInfo(
        val repository: String,
        val branch: String? = null
    )

    private data class FileNavigationTarget(
        val url: String,
        val label: String,
        val generatedSource: Boolean = false,
        val localPath: Path? = null,
        val lineNumber: Int? = null
    )

    private data class AdvisoryCard(
        val sourceName: String,
        val panel: JPanel,
        val subtitleLabel: JTextArea,
        val bodyArea: JTextArea,
        val linkLabel: JBLabel,
        val moreDetailsButton: JButton,
        var fullText: String = "",
        var expanded: Boolean = false,
        var linkTarget: String? = null
    )

    companion object {
        private const val ADVISORY_PREVIEW_LENGTH = 260
    }

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border()),
            JBUI.Borders.empty(12)
        )
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        headlinePanel.apply {
            layout = GridLayout(1, 3, JBUI.scale(12), 0)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(12)
            )
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            val leftPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(vulnerabilityCaptionLabel)
                add(Box.createVerticalStrut(4))
                add(titleLabel)
            }
            val discoveredPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(discoveredHeaderLabel)
                add(Box.createVerticalStrut(4))
                add(discoveredValueLabel)
            }
            val scoresPanel = JPanel().apply {
                layout = GridLayout(3, 3, JBUI.scale(4), JBUI.scale(0))
                isOpaque = false
                alignmentX = TOP_ALIGNMENT
                preferredSize = Dimension(preferredSize.width, JBUI.scale(48))
                // Row 1: Score values
                add(scoreBoxPanel.apply {
                    layout = BorderLayout()
                    isOpaque = false
                    add(scoreLabels[0].apply {
                        horizontalAlignment = SwingConstants.CENTER
                        font = font.deriveFont(Font.BOLD, 16f)
                    }, BorderLayout.CENTER)
                })
                add(scoreLabels[1].apply {
                    horizontalAlignment = SwingConstants.CENTER
                    font = font.deriveFont(Font.BOLD, 16f)
                })
                add(scoreLabels[2].apply {
                    horizontalAlignment = SwingConstants.CENTER
                    font = font.deriveFont(Font.BOLD, 16f)
                    foreground = JBColor.GRAY
                })
                // Row 2: CVSS version labels
                add(scoreLabels[3].apply {
                    horizontalAlignment = SwingConstants.CENTER
                    font = font.deriveFont(Font.PLAIN, 9f)
                })
                add(scoreLabels[4].apply {
                    horizontalAlignment = SwingConstants.CENTER
                    font = font.deriveFont(Font.PLAIN, 9f)
                })
                add(scoreLabels[5].apply {
                    horizontalAlignment = SwingConstants.CENTER
                    font = font.deriveFont(Font.PLAIN, 9f)
                })
                // Row 3: Severity labels
                add(scoreLabels[6].apply {
                    horizontalAlignment = SwingConstants.CENTER
                    font = font.deriveFont(Font.PLAIN, 9f)
                    foreground = JBColor.GRAY
                })
                add(scoreLabels[7].apply {
                    horizontalAlignment = SwingConstants.CENTER
                    font = font.deriveFont(Font.PLAIN, 9f)
                    foreground = JBColor.GRAY
                })
                add(scoreLabels[8].apply {
                    horizontalAlignment = SwingConstants.CENTER
                    font = font.deriveFont(Font.PLAIN, 9f)
                    foreground = JBColor.GRAY
                })
            }
            add(leftPanel)
            add(discoveredPanel)
            add(scoresPanel)
        }
        add(headlinePanel)
        add(Box.createVerticalStrut(8))
        add(emptyStateLabel)
        add(Box.createVerticalStrut(8))
        add(dependencyInlinePanel)
        add(Box.createVerticalStrut(10))
        advisoryCardsPanel.add(cweCard.panel)
        advisoryCardsPanel.add(githubCard.panel)
        advisoryCardsPanel.add(nvdCard.panel)
        summaryRowPanel.add(advisoryCardsPanel, BorderLayout.CENTER)
        add(summaryRowPanel)
        //add(Box.createVerticalStrut(10))
        //add(identifierLabel)
        //add(Box.createVerticalStrut(8))
        //add(cvssLabel)
        //add(Box.createVerticalStrut(8))
        //add(statusLabel)
        add(Box.createVerticalStrut(8))
        add(detailsStatusLabel)
        addSection("Actions", actionsSectionBody)
        addSection("CISA KEV", cisaKevSectionBody)
        addSection("Introduced through", introducedSectionBody)
        addSection("Suggested fixes", fixesSectionBody)
        addSection("References", referencesSectionBody)

        linkLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                findingUrl?.let { BrowserUtil.browse(it) }
            }
        })
        titleLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                findingUrl?.let { BrowserUtil.browse(it) }
            }
        })
        applyReviewStatusButton.addActionListener {
            val selectedStatus = reviewStatusCombo.selectedItem as? String ?: return@addActionListener
            onReviewStatusApply(selectedStatus, currentReviewInfo)
        }
        cweCard.moreDetailsButton.addActionListener { toggleAdvisoryExpansion(cweCard) }
        githubCard.moreDetailsButton.addActionListener { toggleAdvisoryExpansion(githubCard) }
        nvdCard.moreDetailsButton.addActionListener { toggleAdvisoryExpansion(nvdCard) }
        cweCard.linkLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                cweCard.linkTarget?.let { BrowserUtil.browse(it) }
            }
        })
        githubCard.linkLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                githubCard.linkTarget?.let { BrowserUtil.browse(it) }
            }
        })
        nvdCard.linkLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                nvdCard.linkTarget?.let { BrowserUtil.browse(it) }
            }
        })
        listOf(
            headlinePanel,
            emptyStateLabel,
            dependencyInlinePanel,
            summaryRowPanel,
            identifierLabel,
            cvssLabel,
            statusLabel,
            dependenciesLabel,
            fixedVersionLabel,
            linkLabel,
            scanLabel,
            detailsStatusLabel
        ).forEach { component ->
            component.alignmentX = LEFT_ALIGNMENT
        }
        referencesSectionBody.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                rerenderReferencesIfNeeded()
            }
        })
        renderEmptyState()
    }

    fun setFinding(finding: VulnerabilityFinding?) {
        if (finding == null) {
            renderEmptyState()
            return
        }

        headlinePanel.isVisible = true
        emptyStateLabel.isVisible = false
        dependencyInlinePanel.isVisible = true
        summaryRowPanel.isVisible = true
        identifierLabel.isVisible = true
        discoveredValueLabel.isVisible = true
        cvssLabel.isVisible = true
        statusLabel.isVisible = true
        dependenciesLabel.isVisible = false
        fixedVersionLabel.isVisible = false
        scanLabel.isVisible = false
        reviewActionPanel.isVisible = true
        sectionPanels.forEach { it.isVisible = false }
        currentReviewInfo = null

        val id = finding.primaryIdentifier()
        val title = finding.title?.takeIf { it.isNotBlank() && !it.equals(id, ignoreCase = true) } ?: id
        titleLabel.text = title
        discoveredValueLabel.text = finding.discoveredRelativeDisplay()
        identifierLabel.text = "Identifier: $id"
        val dependencies = finding.affectedDependencies.ifEmpty { finding.fallbackDependencies() }
        val firstDependency = dependencies.firstOrNull()?.name ?: "Unknown dependency"
        dependencyInlineChipButton.text = buildDependencyInlineChipText(firstDependency)
        dependencyInlineChipButton.toolTipText = "Dependency navigation will be available in a later phase"
        cvssLabel.text = "Severity: ${finding.displaySeverity()} • CVSS: ${finding.cvssDetailsDisplay()}"
        statusLabel.text = htmlText(
            buildString {
                append("<b>Review status:</b> ${htmlEscape(finding.reviewStatusDisplay())}<br/>")
                append("<b>Reachability:</b> ${htmlEscape(finding.reachablePath ?: "Unknown")}")
                finding.reachabilityMessage
                    ?.takeIf { it.isNotBlank() }
                    ?.let { append("<br/><b>Reachability message:</b> ${htmlEscape(it)}") }
                append("<br/><b>CISA exploited:</b> ${htmlEscape(finding.exploitedDisplay())}")
                finding.pausedUntil?.takeIf { it.isNotBlank() }?.let {
                    append("<br/><b>Paused until:</b> ${htmlEscape(it)}")
                }
            }
        )
        renderSummarySources(emptyList())
        reviewStatusCombo.selectedItem = finding.reviewStatus ?: "unexamined"
        findingUrl = buildFindingUrl(finding)
        linkLabel.isVisible = false
        scanLabel.text = ""
        detailsStatusLabel.text = "Loading details..."
        detailsStatusLabel.isVisible = true
        setBusy(true)
        clearDetailsSections()
        scrollToTop()
        revalidate()
        repaint()
    }

    fun showDetailsLoading() {
        detailsStatusLabel.text = "Loading details..."
        detailsStatusLabel.isVisible = true
    }

    fun applyDetails(
        finding: VulnerabilityFinding,
        context: VulnerabilityDetailsContext,
        bundle: VulnerabilityDetailsBundle
    ) {
        detailsStatusLabel.text = ""
        detailsStatusLabel.isVisible = false
        setBusy(false)
        scoresArea.text = buildCompactScoreSummaryText(bundle.scoreSummaries)
        renderSummarySources(bundle.summarySources)
        currentReviewInfo = bundle.reviewStatusInfo
        reviewStatusCombo.selectedItem = resolveDisplayedReviewStatus(context, finding, bundle)
        renderActionsSection(context, finding, bundle)
        renderCisaKevSection(finding, bundle)
        renderIntroducedThroughSection(bundle)
        renderSuggestedFixesSection(bundle)
        renderReferencesSection(bundle.references)
        updateSectionVisibility()
        scrollToTop()
        revalidate()
        repaint()
    }

    fun showDetailsError(message: String) {
        setBusy(false)
        detailsStatusLabel.text = message
        detailsStatusLabel.isVisible = true
    }

    private fun renderEmptyState() {
        headlinePanel.isVisible = false
        emptyStateLabel.isVisible = true
        dependencyInlineChipButton.text = ""
        dependencyInlinePanel.isVisible = false
        summaryRowPanel.isVisible = false
        identifierLabel.text = ""
        identifierLabel.isVisible = false
        discoveredValueLabel.text = ""
        discoveredValueLabel.isVisible = false
        cvssLabel.text = ""
        cvssLabel.isVisible = false
        statusLabel.text = ""
        statusLabel.isVisible = false
        dependenciesLabel.text = ""
        dependenciesLabel.isVisible = false
        fixedVersionLabel.text = ""
        fixedVersionLabel.isVisible = false
        linkLabel.text = ""
        linkLabel.isVisible = false
        scanLabel.text = ""
        scanLabel.isVisible = false
        detailsStatusLabel.text = ""
        detailsStatusLabel.isVisible = false
        setBusy(false)
        reviewActionPanel.isVisible = false
        sectionPanels.forEach { it.isVisible = false }
        currentReviewInfo = null
        clearDetailsSections()
        findingUrl = null
        revalidate()
        repaint()
    }

    private fun clearDetailsSections() {
        scoresArea.text = ""
        renderSummarySources(emptyList())
        actionsSectionBody.removeAll()
        cisaKevSectionBody.removeAll()
        introducedSectionBody.removeAll()
        fixesSectionBody.removeAll()
        referencesSectionBody.removeAll()
        renderedReferences = emptyList()
        renderedReferenceColumns = 0
    }

    private fun setBusy(isBusy: Boolean) {
        val cursorType = if (isBusy) Cursor.WAIT_CURSOR else Cursor.DEFAULT_CURSOR
        cursor = Cursor.getPredefinedCursor(cursorType)
        linkLabel.isEnabled = !isBusy
        cweCard.linkLabel.isEnabled = !isBusy && cweCard.linkTarget != null
        cweCard.moreDetailsButton.isEnabled = !isBusy && cweCard.fullText.length > ADVISORY_PREVIEW_LENGTH
        githubCard.linkLabel.isEnabled = !isBusy && githubCard.linkTarget != null
        nvdCard.linkLabel.isEnabled = !isBusy && nvdCard.linkTarget != null
        githubCard.moreDetailsButton.isEnabled = !isBusy && githubCard.fullText.length > ADVISORY_PREVIEW_LENGTH
        nvdCard.moreDetailsButton.isEnabled = !isBusy && nvdCard.fullText.length > ADVISORY_PREVIEW_LENGTH
        reviewStatusCombo.isEnabled = !isBusy
        applyReviewStatusButton.isEnabled = !isBusy
    }

    private fun addSection(title: String, content: JComponent) {
        val sectionPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            isVisible = false
            add(Box.createVerticalStrut(8))
            add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD)
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(4))
            add(content)
        }
        content.alignmentX = LEFT_ALIGNMENT
        sectionPanels += sectionPanel
        when (content) {
            is JTextArea -> sectionAreaToPanel[content] = sectionPanel
            is JPanel -> sectionBodyToPanel[content] = sectionPanel
        }
        add(sectionPanel)
    }

    private fun updateSectionVisibility() {
        sectionAreaToPanel.forEach { (area, panel) ->
            panel.isVisible = area.text.isNotBlank()
        }
        sectionBodyToPanel.forEach { (body, panel) ->
            panel.isVisible = body.componentCount > 0
        }
    }

    private fun createSectionBodyPanel(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
    }

    private fun createDetailsArea(): JTextArea = JTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        foreground = JBColor.foreground()
        alignmentX = LEFT_ALIGNMENT
    }

    private fun createAdvisoryTitleArea(): JTextArea = createDetailsArea().apply {
        font = font.deriveFont(Font.BOLD)
        cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
        isFocusable = false
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    private fun createCardPanel(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border()),
            JBUI.Borders.empty(10)
        )
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    private fun createPill(text: String, background: JBColor, foreground: JBColor = JBColor.WHITE): JBLabel = JBLabel(text).apply {
        this.background = background
        this.foreground = foreground
        isOpaque = true
        border = JBUI.Borders.empty(3, 10)
    }

    private fun createWrappedInfoArea(
        text: String,
        bold: Boolean = false,
        foreground: Color = JBColor.foreground(),
        monospace: Boolean = false
    ): JTextArea = createDetailsArea().apply {
        this.text = text
        this.foreground = foreground
        font = when {
            monospace -> Font(Font.MONOSPACED, if (bold) Font.BOLD else Font.PLAIN, font.size)
            bold -> font.deriveFont(Font.BOLD)
            else -> font.deriveFont(Font.PLAIN)
        }
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    private fun createLinkArea(
        text: String,
        url: String,
        bold: Boolean = false,
        monospace: Boolean = false
    ): JTextArea = createWrappedInfoArea(
        text = text,
        bold = bold,
        foreground = JBColor.BLUE,
        monospace = monospace
    ).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                BrowserUtil.browse(url)
            }
        })
    }

    private fun renderIntroducedThroughSection(bundle: VulnerabilityDetailsBundle) {
        introducedSectionBody.removeAll()

        val rootFixCount = bundle.rootFixes?.rootFixesCount ?: bundle.rootFixes?.fixes?.size ?: 0
        if (rootFixCount > 0) {
            val summaryRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                add(createPill(rootFixCount.toString(), JBColor(0x22B35A, 0x2F8F4E)))
                add(JBLabel(if (rootFixCount == 1) "Direct dependency to update" else "Direct dependencies to update").apply {
                    foreground = JBColor.GRAY
                })
            }
            introducedSectionBody.add(summaryRow)
            introducedSectionBody.add(Box.createVerticalStrut(8))
        }

        if (bundle.files.isNotEmpty()) {
            val metaRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                add(JBLabel("${bundle.files.size} file${if (bundle.files.size == 1) "" else "s"}").apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(Font.BOLD)
                })
            }
            introducedSectionBody.add(metaRow)
            introducedSectionBody.add(Box.createVerticalStrut(6))
            bundle.files.forEachIndexed { index, file ->
                introducedSectionBody.add(createIntroducedFileCard(file))
                if (index < bundle.files.lastIndex) {
                    introducedSectionBody.add(Box.createVerticalStrut(8))
                }
            }
        }

        val paths = bundle.dependencyTree?.roots?.flatMap { flattenDependencyPaths(it) }.orEmpty()
        if (paths.isNotEmpty()) {
            if (introducedSectionBody.componentCount > 0) {
                introducedSectionBody.add(Box.createVerticalStrut(10))
            }
            introducedSectionBody.add(JBLabel("Dependency path").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.BOLD, 11f)
                alignmentX = LEFT_ALIGNMENT
            })
            introducedSectionBody.add(Box.createVerticalStrut(4))
            paths.take(6).forEachIndexed { index, path ->
                introducedSectionBody.add(createCardPanel().apply {
                    add(createWrappedInfoArea(path.joinToString(" -> "), monospace = true))
                })
                if (index < minOf(paths.lastIndex, 5)) {
                    introducedSectionBody.add(Box.createVerticalStrut(6))
                }
            }
        }
    }

    private fun renderActionsSection(
        context: VulnerabilityDetailsContext,
        finding: VulnerabilityFinding,
        bundle: VulnerabilityDetailsBundle
    ) {
        actionsSectionBody.removeAll()

        val cards = JPanel(GridLayout(1, 2, JBUI.scale(12), 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        cards.add(createReviewActionCard(context, finding, bundle))
        cards.add(createFixActionCard(bundle))
        actionsSectionBody.add(cards)
    }

    private fun createReviewActionCard(
        context: VulnerabilityDetailsContext,
        finding: VulnerabilityFinding,
        bundle: VulnerabilityDetailsBundle
    ): JPanel {
        val repositoryName = bundle.repositoryStatuses.firstOrNull { it.id == context.repositoryId }?.name ?: context.repositoryId
        val displayedStatus = resolveDisplayedReviewStatus(context, finding, bundle)
        return createCardPanel().apply {
            background = JBColor(0xF4F6FF, 0x2E3240)
            isOpaque = true
            add(JBLabel("Review status for").apply {
                font = font.deriveFont(Font.BOLD, 12f)
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(8))
            add(createPill(repositoryName, JBColor(0xFFFFFF, 0x3B4152), JBColor(0x2F4A7D, 0xD6E4FF)).apply {
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(10))
            add(JBLabel("Current: ${displayedStatus.replaceFirstChar { it.titlecase() }}").apply {
                foreground = when (displayedStatus.lowercase()) {
                    "vulnerable" -> JBColor(0xE91E63, 0xFF7BA5)
                    "unaffected", "remediated" -> JBColor(0x2E7D32, 0x8ED694)
                    else -> JBColor.foreground()
                }
                font = font.deriveFont(Font.BOLD, 12f)
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(10))
            add(reviewActionPanel.apply {
                alignmentX = LEFT_ALIGNMENT
            })
        }
    }

    private fun createFixActionCard(bundle: VulnerabilityDetailsBundle): JPanel {
        val fixCount = bundle.rootFixes?.rootFixesCount ?: bundle.rootFixes?.fixes?.size ?: 0
        return createCardPanel().apply {
            background = JBColor(0xEFFAF0, 0x243527)
            isOpaque = true
            add(JBLabel("Fix vulnerabilities").apply {
                font = font.deriveFont(Font.BOLD, 12f)
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(10))
            val summaryRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                add(createPill(fixCount.toString(), JBColor(0x1FB655, 0x2F8F4E)).apply {
                    foreground = JBColor.WHITE
                })
                add(JBLabel(if (fixCount == 1) "Direct dependency to update" else "Direct dependencies to update").apply {
                    foreground = JBColor.GRAY
                })
            }
            add(summaryRow)
            add(Box.createVerticalStrut(12))
            add(JButton("Full fix details ›").apply {
                alignmentX = LEFT_ALIGNMENT
                isOpaque = false
                isContentAreaFilled = false
                isBorderPainted = false
                border = JBUI.Borders.empty()
                foreground = JBColor.BLUE
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { scrollToSection(fixesSectionBody) }
            })
        }
    }

    private fun renderCisaKevSection(
        finding: VulnerabilityFinding,
        bundle: VulnerabilityDetailsBundle
    ) {
        cisaKevSectionBody.removeAll()

        val showReachabilityCard = hasReachabilityAnalysisResult(bundle)
        val cards = JPanel(GridLayout(1, if (showReachabilityCard) 2 else 1, JBUI.scale(12), 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        cards.add(createCardPanel().apply {
            background = JBColor(0xFFFFFF, 0x2E2E2E)
            isOpaque = true
            add(JBLabel(if (finding.exploited == true) "Known exploit reported" else "No exploit reported").apply {
                font = font.deriveFont(Font.BOLD, 12f)
                alignmentX = LEFT_ALIGNMENT
                foreground = if (finding.exploited == true) {
                    JBColor(0xD32F2F, 0xFF8A80)
                } else {
                    JBColor.foreground()
                }
            })
            add(Box.createVerticalStrut(8))
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                add(JBLabel().apply {
                    icon = if (finding.exploited == true) AllIcons.General.Warning else AllIcons.Actions.Checked
                })
                add(JBLabel(
                    when (finding.exploited) {
                        true -> "CISA flagged this vulnerability as exploited"
                        false -> "CISA KEV currently shows no exploit reported"
                        null -> "Exploit status is currently unknown"
                    }
                ).apply {
                    foreground = JBColor.GRAY
                })
            })
        })
        if (showReachabilityCard) {
            cards.add(createReachabilityAnalysisCard(bundle.reachabilityDetails!!, finding))
        }
        cisaKevSectionBody.add(cards)
    }

    private fun hasReachabilityAnalysisResult(bundle: VulnerabilityDetailsBundle): Boolean {
        val details = bundle.reachabilityDetails ?: return false
        if (!details.supported) return false
        return !details.reachAnalysis.isNullOrBlank() ||
            !details.reachAnalysisMessage.isNullOrBlank() ||
            !details.reachAnalysisLanguage.isNullOrBlank()
    }

    private fun createReachabilityAnalysisCard(
        details: com.debricked.intellijplugin.domain.VulnerabilityReachabilityDetails,
        finding: VulnerabilityFinding
    ): JPanel = createCardPanel().apply {
        val status = finding.reachablePath?.takeIf { it.isNotBlank() } ?: "Unknown"
        val isReachable = status.contains("reachable", ignoreCase = true) && !status.contains("not", ignoreCase = true)

        background = JBColor(0xFFFFFF, 0x2E2E2E)
        isOpaque = true
        add(JBLabel("Reachability analysis").apply {
            font = font.deriveFont(Font.BOLD, 12f)
            alignmentX = LEFT_ALIGNMENT
        })
        add(Box.createVerticalStrut(8))
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JBLabel().apply {
                icon = if (isReachable) AllIcons.General.Warning else AllIcons.Actions.Checked
            })
            add(JBLabel("List status: $status").apply {
                foreground = if (isReachable) JBColor(0xD32F2F, 0xFF8A80) else JBColor.GRAY
            })
        })
        details.reachAnalysis?.takeIf { it.isNotBlank() }?.let {
            add(Box.createVerticalStrut(6))
            add(createWrappedInfoArea("Analysis score: $it", bold = true))
        }
        details.reachAnalysisLanguage?.takeIf { it.isNotBlank() }?.let {
            add(Box.createVerticalStrut(4))
            add(createWrappedInfoArea("Language: $it", foreground = JBColor.GRAY))
        }
        details.reachAnalysisMessage?.takeIf { it.isNotBlank() }?.let {
            add(Box.createVerticalStrut(6))
            add(createWrappedInfoArea(it))
        }
    }

    private fun createLinkButton(text: String, url: String): JButton = JButton(text).apply {
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        border = JBUI.Borders.empty()
        foreground = JBColor.BLUE
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = url
        addActionListener { BrowserUtil.browse(url) }
    }

    private fun createNavigationButton(text: String, target: FileNavigationTarget): JButton = JButton(text).apply {
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        border = JBUI.Borders.empty()
        foreground = JBColor.BLUE
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = target.localPath?.toString() ?: target.url
        addActionListener { openNavigationTarget(target) }
    }

    private fun createNavigationArea(
        text: String,
        target: FileNavigationTarget,
        bold: Boolean = false,
        monospace: Boolean = false
    ): JTextArea = createWrappedInfoArea(
        text = text,
        bold = bold,
        foreground = JBColor.BLUE,
        monospace = monospace
    ).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = target.localPath?.toString() ?: target.url
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                openNavigationTarget(target)
            }
        })
    }

    private fun openNavigationTarget(target: FileNavigationTarget) {
        val openedLocally = target.localPath?.let { openLocalFile(it, target.lineNumber) } == true
        if (!openedLocally) {
            BrowserUtil.browse(target.url)
        }
    }

    private fun openLocalFile(path: Path, lineNumber: Int?): Boolean {
        val ioFile = path.toFile()
        if (!ioFile.exists()) return false
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile) ?: return false
        val descriptor = OpenFileDescriptor(project, virtualFile, lineNumber ?: 0, 0)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        return true
    }

    private fun createIntroducedFileCard(file: VulnerabilityFileRef): JPanel = createCardPanel().apply {
        val navigationTarget = resolveFileNavigationTarget(file)
        val locationInfo = navigationTarget?.url?.let { parseFileLocationInfo(it) }
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(JBLabel().apply {
                        icon = AllIcons.FileTypes.Any_type
                        border = JBUI.Borders.empty(2, 0, 0, 0)
                    }, BorderLayout.WEST)
                    add(
                        navigationTarget
                            ?.let { createNavigationArea(file.name, it, bold = true, monospace = true) }
                            ?: createWrappedInfoArea(file.name, bold = true, monospace = true),
                        BorderLayout.CENTER
                    )
                }, BorderLayout.CENTER)
                navigationTarget?.let {
                    val actionLabel = when {
                        it.localPath != null && it.generatedSource -> "Open ${it.label} in Editor"
                        it.localPath != null -> "Open in Editor"
                        it.generatedSource -> "Open ${it.label} ↗"
                        else -> "View in File ↗"
                    }
                    add(JPanel(BorderLayout()).apply {
                        isOpaque = false
                        add(createNavigationButton(actionLabel, it).apply {
                            horizontalAlignment = SwingConstants.RIGHT
                        }, BorderLayout.NORTH)
                    }, BorderLayout.EAST)
                }
            })
            navigationTarget?.let {
                add(Box.createVerticalStrut(4))
                add(createWrappedInfoArea(it.localPath?.toString() ?: it.url, foreground = JBColor.GRAY))
                if (it.generatedSource) {
                    add(Box.createVerticalStrut(4))
                    add(createWrappedInfoArea("Generated by Debricked from ${it.label}", foreground = JBColor.GRAY))
                }
            }
            locationInfo?.let { info ->
                add(Box.createVerticalStrut(6))
                add(createFileMetaRow(info))
            }
        })
    }

    private fun resolveFileNavigationTarget(file: VulnerabilityFileRef): FileNavigationTarget? {
        val originalUrl = file.url?.takeIf { it.isNotBlank() } ?: return null
        val remappedManifest = resolveGeneratedLockManifest(file.name, originalUrl)
        if (remappedManifest == null) {
            return FileNavigationTarget(
                url = originalUrl,
                label = file.name,
                localPath = resolveLocalFilePath(originalUrl),
                lineNumber = extractLineNumber(originalUrl)
            )
        }
        val remappedUrl = remappedManifest.first
        return FileNavigationTarget(
            url = remappedUrl,
            label = remappedManifest.second,
            generatedSource = true,
            localPath = resolveLocalFilePath(remappedUrl),
            lineNumber = extractLineNumber(remappedUrl)
        )
    }

    private fun resolveGeneratedLockManifest(fileName: String, originalUrl: String): Pair<String, String>? {
        val candidateNames = generatedLockManifestCandidates(fileName) ?: return null
        val resolvedManifest = chooseManifestCandidate(originalUrl, candidateNames) ?: return null
        return replaceUrlFileName(originalUrl, resolvedManifest) to resolvedManifest
    }

    private fun generatedLockManifestCandidates(fileName: String): List<String>? = when (fileName.lowercase()) {
        "gradle.debricked.lock" -> listOf("build.gradle", "build.gradle.kts")
        "maven.debricked.lock" -> listOf("pom.xml")
        "pip.debricked.lock" -> listOf("requirements.txt", "pyproject.toml", "Pipfile")
        else -> null
    }

    private fun chooseManifestCandidate(originalUrl: String, candidates: List<String>): String? {
        val locationInfo = parseFileLocationInfo(originalUrl)
        val relativeDirectory = extractRepositoryRelativeDirectory(originalUrl)
        val basePath = project.basePath
        if (basePath != null && shouldUseLocalManifestHints(locationInfo, basePath)) {
            candidates.firstOrNull { candidate ->
                val localPath = relativeDirectory
                    ?.let { Path.of(basePath, it, candidate) }
                    ?: Path.of(basePath, candidate)
                Files.exists(localPath)
            }?.let { return it }
        }
        return candidates.firstOrNull()
    }

    private fun shouldUseLocalManifestHints(locationInfo: FileLocationInfo?, basePath: String): Boolean {
        val remoteRepositoryIdentity = normalizeRepositoryIdentity(locationInfo?.repository) ?: return false
        val localRepositoryIdentity = localProjectRepositoryIdentity
            ?: File(basePath).name.takeIf { it.isNotBlank() }
        return remoteRepositoryIdentity.equals(localRepositoryIdentity, ignoreCase = true)
    }

    private fun resolveLocalProjectRepositoryIdentity(basePath: String?): String? {
        if (basePath.isNullOrBlank()) return null
        val remoteUrl = runCatching {
            val process = ProcessBuilder("git", "-C", basePath, "config", "--get", "remote.origin.url")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly()
                return@runCatching null
            }
            process.inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrNull().orEmpty()
        return normalizeRepositoryIdentity(remoteUrl)
    }

    private fun normalizeRepositoryIdentity(repository: String?): String? {
        if (repository.isNullOrBlank()) return null
        val trimmed = repository.trim().removeSuffix("/")
        val path = when {
            trimmed.startsWith("git@") -> trimmed.substringAfter(':', "")
            else -> runCatching { URI(trimmed).path.trim('/') }.getOrNull().orEmpty()
        }.removeSuffix(".git")
        if (path.isBlank()) return null
        val segments = path.split('/').filter { it.isNotBlank() }
        return when {
            segments.size >= 2 -> segments.takeLast(2).joinToString("/")
            segments.isNotEmpty() -> segments.last()
            else -> null
        }
    }

    private fun resolveLocalFilePath(url: String): Path? {
        val basePath = project.basePath ?: return null
        val locationInfo = parseFileLocationInfo(url)
        if (!shouldUseLocalManifestHints(locationInfo, basePath)) return null
        val relativeDirectory = extractRepositoryRelativeDirectory(url)
        val fileName = runCatching { URI(url).path.substringAfterLast('/') }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val path = relativeDirectory
            ?.let { Path.of(basePath, it, fileName) }
            ?: Path.of(basePath, fileName)
        return path.takeIf { Files.exists(it) }
    }

    private fun extractLineNumber(url: String): Int? {
        val fragment = runCatching { URI(url).fragment }.getOrNull() ?: return null
        val match = Regex("L(\\d+)").find(fragment) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()?.minus(1)?.coerceAtLeast(0)
    }

    private fun extractRepositoryRelativeDirectory(url: String): String? {
        val pathSegments = runCatching { URI(url).path.trim('/').split('/').filter { it.isNotBlank() } }.getOrNull() ?: return null
        val fileSegments = when {
            pathSegments.size >= 5 && pathSegments[2] == "blob" -> pathSegments.drop(4)
            pathSegments.size >= 6 && pathSegments[2] == "-" && pathSegments[3] == "blob" -> pathSegments.drop(5)
            else -> return null
        }
        return fileSegments.dropLast(1).takeIf { it.isNotEmpty() }?.joinToString(Path.of(".").fileSystem.separator)
    }

    private fun replaceUrlFileName(url: String, newFileName: String): String {
        val lastSlashIndex = url.lastIndexOf('/')
        if (lastSlashIndex < 0) return url
        return url.substring(0, lastSlashIndex + 1) + newFileName
    }

    private fun createFileMetaRow(info: FileLocationInfo): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(JBLabel().apply {
            icon = AllIcons.Nodes.Folder
        })
        add(JBLabel(info.repository).apply {
            foreground = JBColor.GRAY
        })
        info.branch?.takeIf { it.isNotBlank() }?.let { branch ->
            add(JBLabel("•").apply { foreground = JBColor.GRAY })
            add(createPill(branch, JBColor(0xEEF2F7, 0x313C4D), JBColor(0x44566C, 0xD9E4F5)))
        }
    }

    private fun parseFileLocationInfo(url: String): FileLocationInfo? {
        return runCatching {
            val pathSegments = URI(url).path.trim('/').split('/').filter { it.isNotBlank() }
            when {
                pathSegments.size >= 5 && pathSegments[2] == "blob" -> FileLocationInfo(
                    repository = "${pathSegments[0]}/${pathSegments[1]}",
                    branch = pathSegments[3]
                )
                pathSegments.size >= 6 && pathSegments[2] == "-" && pathSegments[3] == "blob" -> FileLocationInfo(
                    repository = pathSegments.take(2).joinToString("/"),
                    branch = pathSegments[4]
                )
                else -> null
            }
        }.getOrNull()
    }

    private fun renderSuggestedFixesSection(bundle: VulnerabilityDetailsBundle) {
        fixesSectionBody.removeAll()

        val timelines = bundle.vulnerableTimelines
        val fixes = bundle.rootFixes?.fixes.orEmpty()

        fixes.entries.forEachIndexed { index, (dependencyKey, targetVersion) ->
            val timeline = timelines.firstOrNull { timelineMatchesFix(it, dependencyKey) }
            fixesSectionBody.add(createFixCard(dependencyKey, targetVersion, timeline))
            if (index < fixes.size - 1 || bundle.rootFixes?.commands?.isNotEmpty() == true) {
                fixesSectionBody.add(Box.createVerticalStrut(8))
            }
        }

        bundle.rootFixes?.commands
            ?.takeIf { it.isNotEmpty() }
            ?.let { commands ->
                fixesSectionBody.add(createCardPanel().apply {
                    add(JBLabel("Suggested commands").apply {
                        font = font.deriveFont(Font.BOLD, 12f)
                        alignmentX = LEFT_ALIGNMENT
                    })
                    add(Box.createVerticalStrut(6))
                    commands.forEachIndexed { index, command ->
                        add(createWrappedInfoArea(command, monospace = true))
                        if (index < commands.lastIndex) {
                            add(Box.createVerticalStrut(4))
                        }
                    }
                })
            }

        if (fixes.isEmpty()) {
            timelines.forEachIndexed { index, timeline ->
                fixesSectionBody.add(createTimelineOnlyCard(timeline))
                if (index < timelines.lastIndex) {
                    fixesSectionBody.add(Box.createVerticalStrut(8))
                }
            }
        }
    }

    private fun renderReferencesSection(references: List<VulnerabilityReferenceLink>) {
        referencesSectionBody.removeAll()
        renderedReferences = references
        if (references.isEmpty()) return

        val columns = calculateReferenceColumnCount()
        renderedReferenceColumns = columns
        val gridPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        references.forEachIndexed { index, reference ->
            gridPanel.add(createReferenceCard(reference, columns), GridBagConstraints().apply {
                gridx = index % columns
                gridy = index / columns
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(0, 0, JBUI.scale(12), JBUI.scale(12))
            })
        }
        gridPanel.add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
            gridx = 0
            gridy = (references.size + columns - 1) / columns
            gridwidth = columns
            weightx = 1.0
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        })
        referencesSectionBody.add(gridPanel)
    }

    private fun rerenderReferencesIfNeeded() {
        if (renderedReferences.isEmpty()) return
        val columns = calculateReferenceColumnCount()
        if (columns != renderedReferenceColumns) {
            renderReferencesSection(renderedReferences)
            referencesSectionBody.revalidate()
            referencesSectionBody.repaint()
        }
    }

    private fun calculateReferenceColumnCount(): Int {
        val availableWidth = referencesSectionBody.width
            .takeIf { it > 0 }
            ?: (referencesSectionBody.parent?.width ?: width)
        return when {
            availableWidth >= JBUI.scale(1080) -> 3
            availableWidth >= JBUI.scale(720) -> 2
            else -> 1
        }
    }

    private fun createReferenceCard(reference: VulnerabilityReferenceLink, columns: Int): JPanel = createCardPanel().apply {
        alignmentY = TOP_ALIGNMENT
        val linkButton = createLinkButton("↗", reference.link).apply {
            font = font.deriveFont(Font.BOLD, 16f)
            toolTipText = reference.link
        }
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(createExternalReferenceTitle(reference, columns), BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(linkButton, BorderLayout.NORTH)
            }, BorderLayout.EAST)
        })

        val badges = buildReferenceBadges(reference)
        if (badges.isNotEmpty()) {
            add(Box.createVerticalStrut(10))
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                badges.forEach { add(it) }
            })
        }
    }

    private fun createExternalReferenceTitle(reference: VulnerabilityReferenceLink, columns: Int): JComponent {
        val rawTitle = reference.title.ifBlank { reference.link }
        val previewTitle = previewReferenceTitle(rawTitle, columns)
        val wrapWidthPx = when (columns) {
            1 -> JBUI.scale(560)
            2 -> JBUI.scale(300)
            else -> JBUI.scale(210)
        }
        val titleLabel = JBLabel("<html><div style='width:${wrapWidthPx}px;'>${htmlEscape(previewTitle)}</div></html>").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = LEFT_ALIGNMENT
            verticalAlignment = SwingConstants.TOP
            toolTipText = "<html>${htmlEscape(rawTitle)}<br/><span style='color:gray'>${htmlEscape(reference.link)}</span></html>"
        }
        titleLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                BrowserUtil.browse(reference.link)
            }
        })
        return titleLabel
    }

    private fun previewReferenceTitle(title: String, columns: Int): String {
        val normalized = title.replace(Regex("\\s+"), " ").trim()
        val maxLength = when (columns) {
            1 -> 180
            2 -> 100
            else -> 68
        }
        return if (normalized.length <= maxLength) {
            normalized
        } else {
            "${normalized.take(maxLength).trimEnd(' ', '.', ',', ';', ':')}…"
        }
    }

    private fun buildReferenceBadges(reference: VulnerabilityReferenceLink): List<JComponent> {
        val badges = mutableListOf<JComponent>()
        reference.tags.forEach { tag ->
            if (tag.isBlank()) return@forEach
            badges += createPill(
                tag,
                JBColor(0x7B2CBF, 0x5E3B8C),
                JBColor.WHITE
            )
        }
        reference.domain
            ?.takeIf { it.isNotBlank() }
            ?.let {
                badges += createPill(
                    it,
                    JBColor(0xF2F4F7, 0x32363F),
                    JBColor(0x667085, 0xD0D5DD)
                )
            }
        return badges
    }

    private fun timelineMatchesFix(timeline: VulnerabilityTimeline, dependencyKey: String): Boolean {
        val (dependencyName, _) = parseRootFixKey(dependencyKey)
        val normalizedFix = dependencyName.lowercase()
        return timeline.dependencies.any { dependency ->
            val candidates = listOfNotNull(dependency.name, dependency.shortName)
                .map { it.substringBefore(" (").lowercase() }
            candidates.any { it == normalizedFix || normalizedFix.endsWith(it) || it.endsWith(normalizedFix.substringAfterLast(':')) }
        }
    }

    private fun createFixCard(
        dependencyKey: String,
        targetVersion: String,
        timeline: VulnerabilityTimeline?
    ): JPanel {
        val (dependencyName, currentVersion) = parseRootFixKey(dependencyKey)
        val dependencyLink = timeline?.dependencies?.firstOrNull()?.link
        val ecosystem = timeline?.dependencies?.firstNotNullOfOrNull { dependency ->
            extractDependencyEcosystem(dependency.shortName ?: dependency.name)
        }
        return createCardPanel().apply {
            add(createDependencyHeader(dependencyName, ecosystem, dependencyLink))
            add(Box.createVerticalStrut(6))
            add(createVersionComparisonPanel(currentVersion, targetVersion))

            timeline?.let {
                add(Box.createVerticalStrut(8))
                add(createTimelineIntervalsPanel(it))
            }
        }
    }

    private fun createTimelineOnlyCard(timeline: VulnerabilityTimeline): JPanel = createCardPanel().apply {
        val dependency = timeline.dependencies.firstOrNull()
        val dependencyTitle = dependency?.shortName?.let { stripDependencyEcosystem(it) }
            ?: dependency?.name?.let { stripDependencyEcosystem(it) }
            ?: "Dependency"
        val dependencyLink = dependency?.link
        val ecosystem = dependency?.let {
            extractDependencyEcosystem(it.shortName ?: it.name)
        }
        add(createDependencyHeader(dependencyTitle, ecosystem, dependencyLink))
        dependency?.name
            ?.takeIf { it.isNotBlank() && stripDependencyEcosystem(it) != dependencyTitle }
            ?.let {
                add(Box.createVerticalStrut(4))
                add(createWrappedInfoArea(it, foreground = JBColor.GRAY))
            }
        add(Box.createVerticalStrut(8))
        add(createTimelineIntervalsPanel(timeline))
    }

    private fun createDependencyHeader(dependencyName: String, ecosystem: String?, dependencyLink: String?): JPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(
            dependencyLink?.takeIf { it.isNotBlank() }
                ?.let { createLinkArea(dependencyName, it, bold = true) }
                ?: createWrappedInfoArea(dependencyName, bold = true),
            BorderLayout.CENTER
        )
        ecosystem?.takeIf { it.isNotBlank() }?.let {
            add(createPill(it, JBColor(0xE8EEF9, 0x2F3B52), JBColor(0x274472, 0xD7E6FF)), BorderLayout.EAST)
        }
    }

    private fun createVersionComparisonPanel(currentVersion: String?, targetVersion: String): JPanel = JPanel().apply {
        layout = GridLayout(1, if (currentVersion.isNullOrBlank()) 1 else 2, JBUI.scale(8), 0)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        currentVersion?.takeIf { it.isNotBlank() }?.let {
            add(createVersionStagePanel("Current", it, JBColor(0xFDECEC, 0x4E2B2B), JBColor(0xA62C2C, 0xFFD9D9)))
        }
        add(createVersionStagePanel("Recommended", targetVersion, JBColor(0xE8F6EA, 0x1F4A2D), JBColor(0x227A3E, 0xDBFFE5)))
    }

    private fun createVersionStagePanel(label: String, version: String, background: JBColor, foreground: JBColor): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border()),
            JBUI.Borders.empty(8)
        )
        add(JBLabel(label).apply {
            this.foreground = JBColor.GRAY
            font = font.deriveFont(Font.BOLD, 11f)
            alignmentX = LEFT_ALIGNMENT
        })
        add(Box.createVerticalStrut(6))
        add(createPill(version, background, foreground).apply {
            alignmentX = LEFT_ALIGNMENT
        })
    }

    private fun extractDependencyEcosystem(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        val start = trimmed.lastIndexOf('(')
        val end = trimmed.lastIndexOf(')')
        if (start < 0 || end <= start) return null
        return trimmed.substring(start + 1, end).trim().takeIf { it.isNotBlank() }
    }

    private fun stripDependencyEcosystem(text: String): String {
        val ecosystem = extractDependencyEcosystem(text) ?: return text
        return text.removeSuffix("($ecosystem)").trim().removeSuffix("(").trim()
    }

    private fun createTimelineIntervalsPanel(timeline: VulnerabilityTimeline): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(JBLabel("Version timeline").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.BOLD, 11f)
            alignmentX = LEFT_ALIGNMENT
        })
        add(Box.createVerticalStrut(6))
        timeline.intervals.forEachIndexed { index, interval ->
            add(createTimelineIntervalRow(interval))
            if (index < timeline.intervals.lastIndex) {
                add(Box.createVerticalStrut(4))
            }
        }
    }

    private fun createTimelineIntervalRow(interval: com.debricked.intellijplugin.domain.VulnerabilityTimelineInterval): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        val background = if (interval.vulnerable) JBColor(0xF8D7DA, 0x5C2B31) else JBColor(0xDFF3E4, 0x234A31)
        val pillForeground = if (interval.vulnerable) JBColor(0xB71C1C, 0xFFD7D7) else JBColor(0x1B5E20, 0xD8F5DC)
        add(createPill(formatTimelineRange(interval.startVersion, interval.endVersion), background, pillForeground))
        add(JBLabel(if (interval.vulnerable) "Vulnerable" else "Safe").apply {
            foreground = JBColor.GRAY
        })
    }

    private fun createAdvisoryCard(sourceName: String): AdvisoryCard {
        val subtitleLabel = createAdvisoryTitleArea()
        val bodyArea = createDetailsArea().apply {
            font = font.deriveFont(Font.PLAIN, 11f)
        }
        val linkLabel = JBLabel("").apply {
            foreground = JBColor.BLUE
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = LEFT_ALIGNMENT
            isVisible = false
        }
        val moreDetailsButton = JButton("More details").apply {
            alignmentX = LEFT_ALIGNMENT
            isEnabled = false
        }
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(8)
            )
            alignmentY = TOP_ALIGNMENT
            add(subtitleLabel)
            add(Box.createVerticalStrut(6))
            add(bodyArea)
            add(Box.createVerticalStrut(6))
            add(linkLabel)
            add(Box.createVerticalStrut(6))
            add(moreDetailsButton)
        }
        return AdvisoryCard(sourceName, panel, subtitleLabel, bodyArea, linkLabel, moreDetailsButton)
    }

    private fun buildCompactScoreSummaryText(scoreSummaries: List<VulnerabilityScoreSummary>): String {
        val cvss = mutableMapOf("CVSS4" to (null as VulnerabilityScoreSummary?), "CVSS3" to null, "CVSS2" to null)
        scoreSummaries.forEach { score ->
            if (cvss.containsKey(score.category)) {
                cvss[score.category] = score
            }
        }
        
        // Row 1: Score values
        scoreLabels[0].text = cvss["CVSS4"]?.scoreText ?: "N/A"
        scoreLabels[1].text = cvss["CVSS3"]?.scoreText ?: "N/A"
        scoreLabels[2].text = cvss["CVSS2"]?.scoreText ?: "N/A"
        
        // Row 2: Version labels
        scoreLabels[3].text = "CVSS4"
        scoreLabels[4].text = "CVSS3"
        scoreLabels[5].text = "CVSS2"
        
        // Row 3: Severity labels
        scoreLabels[6].text = cvss["CVSS4"]?.label ?: ""
        scoreLabels[7].text = cvss["CVSS3"]?.label ?: ""
        scoreLabels[8].text = cvss["CVSS2"]?.label ?: ""
        
        // Style CVSS4 box if score exists
        val cvss4Score = cvss["CVSS4"]?.scoreText
        if (cvss4Score != null && cvss4Score != "N/A") {
            scoreBoxPanel.apply {
                background = JBColor(0x1e3a5f, 0x2d5a8a)
                border = null
                isOpaque = true
            }
            scoreLabels[0].foreground = JBColor(Color.WHITE, Color.WHITE)

        } else {
            scoreBoxPanel.isOpaque = false
            scoreLabels[0].foreground = JBColor.foreground()
        }
        
        return ""
    }

    private fun renderSummarySources(summarySources: List<VulnerabilitySummarySource>) {
        val cwe = summarySources.firstOrNull { it.category.equals("CWE", ignoreCase = true) }
        bindCweCard(cwe)
        bindAdvisoryCard(githubCard, summarySources.firstOrNull { it.category.equals("GitHub", ignoreCase = true) })
        bindAdvisoryCard(nvdCard, summarySources.firstOrNull { it.category.equals("NVD", ignoreCase = true) })
    }

    private fun bindCweCard(source: VulnerabilitySummarySource?) {
        val description = source?.description?.trim().orEmpty()
        cweCard.fullText = description.ifBlank { "No CWE summary available." }
        cweCard.expanded = false
        cweCard.linkTarget = source?.link?.takeIf { it.isNotBlank() }
        val title = source?.title?.takeIf { it.isNotBlank() } ?: "CWE"
        val titleWithIcon = if (cweCard.linkTarget != null) "$title 🔗" else title
        cweCard.subtitleLabel.text = titleWithIcon
        cweCard.subtitleLabel.foreground = if (cweCard.linkTarget != null) JBColor.BLUE else JBColor.foreground()
        cweCard.subtitleLabel.cursor = Cursor.getPredefinedCursor(
            if (cweCard.linkTarget != null) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR
        )
        cweCard.subtitleLabel.mouseListeners.forEach { cweCard.subtitleLabel.removeMouseListener(it) }
        cweCard.subtitleLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                cweCard.linkTarget?.let { BrowserUtil.browse(it) }
            }
        })
        cweCard.linkLabel.isVisible = false
        updateAdvisoryCardBody(cweCard)
    }

    private fun bindAdvisoryCard(card: AdvisoryCard, source: VulnerabilitySummarySource?) {
        card.fullText = source?.description?.trim().orEmpty()
        card.expanded = false
        card.linkTarget = source?.link?.takeIf { it.isNotBlank() }
        val title = source?.title?.takeIf { it.isNotBlank() } ?: "${card.sourceName} advisory"
        val titleWithIcon = if (card.linkTarget != null) "$title 🔗" else title
        card.subtitleLabel.text = titleWithIcon
        card.subtitleLabel.foreground = if (card.linkTarget != null) JBColor.BLUE else JBColor.foreground()
        card.subtitleLabel.cursor = Cursor.getPredefinedCursor(
            if (card.linkTarget != null) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR
        )
        card.subtitleLabel.mouseListeners.forEach { card.subtitleLabel.removeMouseListener(it) }
        card.subtitleLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                card.linkTarget?.let { BrowserUtil.browse(it) }
            }
        })
        card.linkLabel.isVisible = false
        updateAdvisoryCardBody(card)
    }

    private fun toggleAdvisoryExpansion(card: AdvisoryCard) {
        if (!card.moreDetailsButton.isEnabled) return
        card.expanded = !card.expanded
        updateAdvisoryCardBody(card)
        card.panel.revalidate()
        card.panel.repaint()
    }

    private fun updateAdvisoryCardBody(card: AdvisoryCard) {
        val text = card.fullText.ifBlank { "No summary available." }
        val hasExpandableDetails = text.length > ADVISORY_PREVIEW_LENGTH
        card.bodyArea.text = if (card.expanded || text.length <= ADVISORY_PREVIEW_LENGTH) {
            text
        } else {
            "${text.take(ADVISORY_PREVIEW_LENGTH).trimEnd()}..."
        }
        card.moreDetailsButton.isEnabled = hasExpandableDetails
        card.moreDetailsButton.isVisible = hasExpandableDetails
        card.moreDetailsButton.text = if (hasExpandableDetails) {
            if (card.expanded) "Less details" else "More details"
        } else {
            ""
        }
        card.panel.revalidate()
        card.panel.repaint()
    }

    private fun scrollToTop() {
        SwingUtilities.invokeLater {
            (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this) as? JScrollPane)
                ?.viewport
                ?.viewPosition = Point(0, 0)
        }
    }

    private fun buildIntroducedThroughText(
        files: List<VulnerabilityFileRef>,
        dependencyTree: VulnerabilityDependencyTree?
    ): String {
        val fileText = files.joinToString("\n") { file ->
            listOf(file.name, file.url).filter { !it.isNullOrBlank() }.joinToString(" - ")
        }
        val treeText = dependencyTree?.roots
            ?.flatMap { flattenDependencyPaths(it) }
            ?.joinToString("\n") { path -> path.joinToString(" -> ") }
            .orEmpty()
        return listOf(fileText, treeText).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun flattenDependencyPaths(
        node: VulnerabilityDependencyTreeNode,
        prefix: List<String> = emptyList()
    ): List<List<String>> {
        val label = node.version?.takeIf { it.isNotBlank() }?.let { "${node.name}:$it" } ?: node.name
        val path = prefix + label
        if (node.children.isEmpty()) return listOf(path)
        return node.children.flatMap { flattenDependencyPaths(it, path) }
    }

    private fun buildRootFixText(
        rootFixes: VulnerabilityRootFixes?,
        vulnerableTimelines: List<VulnerabilityTimeline>
    ): String {
        val sections = mutableListOf<String>()

        rootFixes?.let { fixes ->
            val fixLines = fixes.fixes.entries.joinToString("\n") { (dependencyKey, targetVersion) ->
                val (dependencyName, currentVersion) = parseRootFixKey(dependencyKey)
                val currentVersionText = currentVersion?.takeIf { it.isNotBlank() }?.let { " from $it" }.orEmpty()
                "• Update $dependencyName$currentVersionText to $targetVersion"
            }
            val commandLines = fixes.commands.joinToString("\n") { "• $it" }
            sections += listOf(
                if (fixes.rootFixesCount > 0) "Direct dependencies to update: ${fixes.rootFixesCount}" else "",
                fixLines,
                if (commandLines.isNotBlank()) "Suggested commands:\n$commandLines" else "",
                if (!fixes.isReady && fixes.fixes.isEmpty() && fixes.commands.isEmpty()) "Fix suggestions are not ready yet." else ""
            ).filter { it.isNotBlank() }.joinToString("\n")
        }

        buildVulnerableTimelineText(vulnerableTimelines)
            .takeIf { it.isNotBlank() }
            ?.let { sections += it }

        return sections.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun parseRootFixKey(key: String): Pair<String, String?> {
        val parts = key.split("#", limit = 2)
        return parts.first() to parts.getOrNull(1)
    }

    private fun buildVulnerableTimelineText(vulnerableTimelines: List<VulnerabilityTimeline>): String {
        if (vulnerableTimelines.isEmpty()) return ""
        val entries = vulnerableTimelines.mapNotNull { timeline ->
            val dependency = timeline.dependencies.firstOrNull() ?: return@mapNotNull null
            val dependencyLabel = dependency.shortName?.takeIf { it.isNotBlank() } ?: dependency.name
            val vulnerableRanges = timeline.intervals
                .filter { it.vulnerable }
                .joinToString(", ") { interval -> formatTimelineRange(interval.startVersion, interval.endVersion) }
                .ifBlank { "Not reported" }
            buildString {
                append("• $dependencyLabel")
                if (dependency.shortName?.takeIf { it.isNotBlank() } != null && dependency.shortName != dependency.name) {
                    append("\n  Package: ${dependency.name}")
                }
                append("\n  Vulnerable versions: $vulnerableRanges")
            }
        }
        if (entries.isEmpty()) return ""
        return "Vulnerable dependency timeline:\n${entries.joinToString("\n")}"
    }

    private fun formatTimelineRange(startVersion: String?, endVersion: String?): String {
        val start = startVersion
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { it.equals("zero", ignoreCase = true) }
        val end = endVersion
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { it.equals("inf", ignoreCase = true) }
        return when {
            start != null && end != null -> "$start - $end"
            start == null && end != null -> "up to $end"
            start != null && end == null -> "$start and later"
            else -> "All versions"
        }
    }

    private fun scrollToSection(component: JComponent) {
        SwingUtilities.invokeLater {
            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this) as? JScrollPane ?: return@invokeLater
            val point = SwingUtilities.convertPoint(component.parent, component.location, scrollPane.viewport.view)
            scrollPane.viewport.viewPosition = Point(0, point.y.coerceAtLeast(0))
        }
    }

    private fun buildReferencesText(references: List<VulnerabilityReferenceLink>): String =
        references.joinToString("\n") { "${it.title} - ${it.link}" }

    private fun resolveDisplayedReviewStatus(
        context: VulnerabilityDetailsContext,
        finding: VulnerabilityFinding,
        bundle: VulnerabilityDetailsBundle
    ): String {
        return bundle.repositoryStatuses.firstOrNull { it.id == context.repositoryId }?.type
            ?: finding.reviewStatus
            ?: "unexamined"
    }

    private fun buildFixHeadline(finding: VulnerabilityFinding, rootFixes: VulnerabilityRootFixes?): String {
        if (rootFixes != null && rootFixes.fixes.isNotEmpty()) {
            return "Fixed version: ${rootFixes.fixes.values.distinct().joinToString(", ")}"
        }
        return "Fixed version: ${finding.fixedVersion ?: "Not provided"}"
    }

    private fun contextSummary(context: DebrickedScanContext?): String {
        if (context == null) return "Scan context unavailable"
        val branch = context.branchName ?: "all branches"
        return "Repository ${context.repositoryId} • Branch $branch"
    }

    private fun buildFindingUrl(finding: VulnerabilityFinding): String? {
        val vulnerabilityId = finding.vulnerabilityId?.trim()
        val commitId = finding.debrickedCommitId?.trim()
        if (vulnerabilityId.isNullOrEmpty() || commitId.isNullOrEmpty()) return null
        val apiUrl = DebrickedSettingsManager.getInstance().getApiUrl().trim()
        if (apiUrl.isEmpty()) return null
        val appBase = apiUrl.removeSuffix("/").removeSuffix("/api")
        return "$appBase/app/en/vulnerability/${URLEncoder.encode(vulnerabilityId, "UTF-8")}?commitId=${URLEncoder.encode(commitId, "UTF-8")}"
    }

    private fun buildDependencyInlineChipText(dependencyName: String): String {
        val trimmedDependencyName = dependencyName.trim()
        return if (trimmedDependencyName.length <= 54) {
            trimmedDependencyName
        } else {
            "${trimmedDependencyName.take(51).trimEnd()}..."
        }
    }

    private fun htmlText(text: String): String = "<html>${text.replace("\n", "<br/>")}</html>"

    private fun htmlEscape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

// ════════════════════════��═════════════════════════════════════════════════════
// Dependencies Tab
// ══════════════════════════════════════════════════════════════════════════════

class DependenciesTabProvider(
    private val panel: DependenciesTabPanel
) : TabProvider {
    override val tabTitle: String = "Dependencies"
    override fun getPanel(): JComponent = panel

    override fun loadData(context: TabContext, forceRefresh: Boolean) {
        if (context.repositoryId.isBlank()) return
        panel.requestLoad(context.repositoryId, context.branchId, forceRefresh)
    }

    override fun invalidate(context: TabContext) {
        panel.invalidateCache()
    }
}

class DependenciesTabPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val apiClient = ApplicationManager.getApplication().getService(DebrickedApiClient::class.java)
    private val cache = DependencyCache()

    private val model = DependencyTableModel()
    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        fillsViewportHeight = true
        rowHeight = 24
        autoCreateRowSorter = true
    }
    private val detailsPanel = DependencyDetailsPanel()
    private val statusLabel = JBLabel("").apply { foreground = JBColor.GRAY }
    private val countLabel = JBLabel("").apply { foreground = JBColor.GRAY }
    private val pageLabel = JBLabel("Page 1").apply { foreground = JBColor.GRAY }
    private val previousPageButton = JButton("Prev")
    private val nextPageButton = JButton("Next")
    private val pageSizeCombo = ComboBox(arrayOf(15, 25, 50, 100)).apply {
        selectedItem = 25
        toolTipText = "Rows per page"
    }
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search by package name"
        preferredSize = JBUI.size(360, preferredSize.height)
        minimumSize = JBUI.size(260, minimumSize.height)
    }
    private val searchDebounceTimer = javax.swing.Timer(250) {
        currentPage = 1
        triggerLoad(forceRefresh = false)
    }.apply { isRepeats = false }

    private val refreshAction = object : AnAction("Refresh", "Refresh dependency list", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) { triggerLoad(forceRefresh = true) }
        override fun displayTextInToolbar(): Boolean = false
        override fun update(e: AnActionEvent) {
            e.presentation.text = "Refresh dependencies"
            e.presentation.icon = AllIcons.Actions.Refresh
            e.presentation.description = "Refresh dependency list"
            e.presentation.isEnabled = true
        }
    }
    private val sidebarToolbar = ActionManager.getInstance()
        .createActionToolbar("DebrickedDependenciesSidebar", DefaultActionGroup(refreshAction), true)

    private var currentPage = 1
    private var rowsPerPage = 25
    private var hasNextPage = false
    private var totalCount: Int? = null
    private var currentRepositoryId: String = ""
    private var currentBranchId: String? = null
    private var loadRequestToken = 0

    init {
        border = JBUI.Borders.empty(8)

        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val viewRow = table.selectedRow
                if (viewRow >= 0) {
                    val modelRow = table.convertRowIndexToModel(viewRow)
                    detailsPanel.setItem(model.getItemAt(modelRow))
                } else {
                    detailsPanel.setItem(null)
                }
            }
        }

        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSearchChanged()
            override fun removeUpdate(e: DocumentEvent) = onSearchChanged()
            override fun changedUpdate(e: DocumentEvent) = onSearchChanged()
        })

        previousPageButton.addActionListener {
            if (currentPage <= 1) return@addActionListener
            currentPage -= 1
            triggerLoad(forceRefresh = false)
        }
        nextPageButton.addActionListener {
            if (!hasNextPage) return@addActionListener
            currentPage += 1
            triggerLoad(forceRefresh = false)
        }
        pageSizeCombo.addActionListener {
            val selected = pageSizeCombo.selectedItem as? Int ?: return@addActionListener
            if (selected == rowsPerPage) return@addActionListener
            rowsPerPage = selected
            currentPage = 1
            triggerLoad(forceRefresh = false)
        }

        val searchBar = JPanel(GridBagLayout()).apply {
            isOpaque = false
            add(searchField, GridBagConstraints().apply {
                gridx = 0; weightx = 0.0; fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0, 0, 0, 8)
            })
            add(statusLabel, GridBagConstraints().apply {
                gridx = 1; weightx = 0.0; anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, 8)
            })
            add(previousPageButton, GridBagConstraints().apply {
                gridx = 2; weightx = 0.0; anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, 6)
            })
            add(nextPageButton, GridBagConstraints().apply {
                gridx = 3; weightx = 0.0; anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, 6)
            })
            add(pageLabel, GridBagConstraints().apply {
                gridx = 4; weightx = 0.0; anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, 6)
            })
            add(pageSizeCombo, GridBagConstraints().apply {
                gridx = 5; weightx = 0.0; anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, 8)
            })
            add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
                gridx = 6; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            })
            add(countLabel, GridBagConstraints().apply {
                gridx = 7; anchor = GridBagConstraints.WEST
            })
        }

        applyColumnRenderers()

        sidebarToolbar.setTargetComponent(this)
        sidebarToolbar.setOrientation(SwingConstants.VERTICAL)
        sidebarToolbar.setMiniMode(true)
        sidebarToolbar.setMinimumButtonSize(ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        sidebarToolbar.component.isOpaque = false
        sidebarToolbar.component.border = JBUI.Borders.empty()

        val sidebarPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 0, 2)
            add(sidebarToolbar.component, BorderLayout.NORTH)
        }

        val tablePane = JBScrollPane(table)
        val detailsScrollPane = JBScrollPane(detailsPanel).apply {
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = JBUI.Borders.empty()
        }
        val mainSplitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tablePane, detailsScrollPane).apply {
            resizeWeight = 0.5
            border = JBUI.Borders.emptyTop(8)
        }

        add(searchBar, BorderLayout.NORTH)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(sidebarPanel, BorderLayout.WEST)
            add(mainSplitPane, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        SwingUtilities.invokeLater { mainSplitPane.setDividerLocation(0.5) }
        table.emptyText.text = "Select a repository and branch to load dependencies"
        updatePaginationControls()
    }

    private fun applyColumnRenderers() {
        table.createDefaultColumnsFromModel()
        // Vulnerabilities count: right-align
        val vulnCol = table.columnModel.getColumn(DependencyColumns.VULNERABILITIES)
        vulnCol.cellRenderer = object : DefaultTableCellRenderer() {
            init { horizontalAlignment = SwingConstants.RIGHT }
            override fun getTableCellRendererComponent(t: JTable, v: Any?, sel: Boolean, foc: Boolean, row: Int, col: Int): java.awt.Component {
                super.getTableCellRendererComponent(t, v, sel, foc, row, col)
                val count = v as? Int ?: 0
                text = if (count > 0) count.toString() else "-"
                foreground = when {
                    count > 0 -> JBColor(0xD32F2F, 0xFF7070)
                    else -> JBColor.GRAY
                }
                return this
            }
        }
    }

    fun requestLoad(repositoryId: String, branchId: String?, forceRefresh: Boolean) {
        if (repositoryId.isBlank()) return
        val contextChanged = repositoryId != currentRepositoryId || branchId != currentBranchId
        if (contextChanged) {
            currentRepositoryId = repositoryId
            currentBranchId = branchId
            currentPage = 1
        }
        triggerLoad(forceRefresh = forceRefresh || contextChanged)
    }

    fun invalidateCache() {
        if (currentRepositoryId.isNotBlank()) {
            cache.invalidate(currentRepositoryId, currentBranchId)
        }
    }

    private fun onSearchChanged() {
        searchDebounceTimer.restart()
    }

    private fun triggerLoad(forceRefresh: Boolean) {
        if (currentRepositoryId.isBlank()) return
        val repositoryId = currentRepositoryId
        val branchId = currentBranchId
        val query = DependencyQuery(
            search = searchField.text.trim(),
            page = currentPage,
            rowsPerPage = rowsPerPage,
            sortColumn = "name",
            order = "asc"
        )
        val queryKey = buildQueryKey(query)

        statusLabel.text = "Loading..."
        statusLabel.isVisible = true
        table.emptyText.text = "Loading dependencies..."
        previousPageButton.isEnabled = false
        nextPageButton.isEnabled = false
        val requestToken = ++loadRequestToken

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val pageResult = cache.getOrLoad(repositoryId, branchId, queryKey, forceRefresh) {
                    apiClient.getDependenciesPage(repositoryId, branchId, query)
                }
                ApplicationManager.getApplication().invokeLater({
                    if (requestToken != loadRequestToken) return@invokeLater
                    applyPageResult(pageResult)
                }, ModalityState.any())
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    if (requestToken != loadRequestToken) return@invokeLater
                    model.setItems(emptyList())
                    table.emptyText.text = "Failed to load dependencies: ${e.message ?: "Unknown error"}"
                    statusLabel.text = ""
                    statusLabel.isVisible = false
                    updatePaginationControls()
                    detailsPanel.setItem(null)
                }, ModalityState.any())
            }
        }
    }

    private fun applyPageResult(pageResult: DependencyPageResult) {
        currentPage = pageResult.page.coerceAtLeast(1)
        rowsPerPage = pageResult.rowsPerPage.coerceAtLeast(1)
        hasNextPage = pageResult.hasNext
        totalCount = pageResult.totalCount
        model.setItems(pageResult.dependencies)
        if (pageResult.dependencies.isEmpty()) {
            table.emptyText.text = if (searchField.text.isBlank()) "No dependencies found" else "No dependencies match your search"
        }
        statusLabel.text = ""
        statusLabel.isVisible = false
        updatePaginationControls()
        sidebarToolbar.updateActionsImmediately()
    }

    private fun updatePaginationControls() {
        val visibleCount = model.visibleCount()
        val knownTotal = totalCount
        val pageStart = if (visibleCount == 0) 0 else ((currentPage - 1) * rowsPerPage) + 1
        val pageEnd = (pageStart + visibleCount - 1).coerceAtLeast(0)
        countLabel.text = if (knownTotal != null && knownTotal >= 0) {
            "$pageStart–$pageEnd of $knownTotal"
        } else if (visibleCount > 0) {
            "$visibleCount entries"
        } else {
            ""
        }
        previousPageButton.isEnabled = currentPage > 1
        nextPageButton.isEnabled = hasNextPage
        pageLabel.text = "Page $currentPage"
        pageSizeCombo.selectedItem = rowsPerPage
    }

    private fun buildQueryKey(query: DependencyQuery): String =
        listOf(query.search.trim(), query.page, query.rowsPerPage, query.sortColumn, query.order).joinToString("|")
}

private class DependencyDetailsPanel : JPanel() {
    private val emptyLabel = JBLabel("Select a dependency to view details").apply {
        foreground = JBColor.GRAY
        alignmentX = LEFT_ALIGNMENT
    }
    private val nameLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, 15f)
        alignmentX = LEFT_ALIGNMENT
    }
    private val versionLabel = JBLabel("").apply { foreground = JBColor.GRAY; alignmentX = LEFT_ALIGNMENT }
    private val ecosystemLabel = JBLabel("").apply { foreground = JBColor.GRAY; alignmentX = LEFT_ALIGNMENT }
    private val scopeLabel = JBLabel("").apply { alignmentX = LEFT_ALIGNMENT }
    private val licensesLabel = JBLabel("").apply { alignmentX = LEFT_ALIGNMENT }
    private val vulnLabel = JBLabel("").apply { alignmentX = LEFT_ALIGNMENT }
    private val latestVersionLabel = JBLabel("").apply { foreground = JBColor.GRAY; alignmentX = LEFT_ALIGNMENT }
    private val linkLabel = JBLabel("Open ↗").apply {
        foreground = JBColor.BLUE
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        alignmentX = LEFT_ALIGNMENT
        isVisible = false
    }
    private var currentLink: String? = null
    private val detailRows = mutableListOf<JComponent>()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border()),
            JBUI.Borders.empty(12)
        )
        add(emptyLabel)
        listOf(nameLabel, versionLabel, ecosystemLabel, scopeLabel, licensesLabel, vulnLabel, latestVersionLabel, linkLabel)
            .forEach { label ->
                detailRows += label
                add(Box.createVerticalStrut(4))
                add(label)
            }
        linkLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                currentLink?.let { com.intellij.ide.BrowserUtil.browse(it) }
            }
        })
        renderEmpty()
    }

    fun setItem(item: DependencyItem?) {
        if (item == null) { renderEmpty(); return }
        emptyLabel.isVisible = false
        detailRows.forEach { it.isVisible = true }
        nameLabel.text = item.name
        versionLabel.text = "Version: ${item.version.ifBlank { "unknown" }}"
        ecosystemLabel.text = "Ecosystem: ${item.ecosystem ?: "Unknown"}"
        scopeLabel.text = "Scope: ${if (item.isIndirect) "Transitive" else "Direct"}"
        licensesLabel.text = "Licenses: ${item.licenses.joinToString(", ").ifBlank { "None detected" }}"
        val vulnCount = item.vulnerabilityCount
        vulnLabel.text = when (vulnCount) {
            0 -> "Vulnerabilities: None"
            1 -> "Vulnerabilities: 1"
            else -> "Vulnerabilities: $vulnCount"
        }
        vulnLabel.foreground = if (vulnCount > 0) JBColor(0xD32F2F, 0xFF7070) else JBColor.GRAY
        latestVersionLabel.text = item.latestVersion?.takeIf { it.isNotBlank() }?.let { "Latest: $it" } ?: ""
        latestVersionLabel.isVisible = latestVersionLabel.text.isNotBlank()
        currentLink = item.link
        linkLabel.isVisible = !item.link.isNullOrBlank()
        revalidate(); repaint()
    }

    private fun renderEmpty() {
        emptyLabel.isVisible = true
        detailRows.forEach { it.isVisible = false }
        currentLink = null
        revalidate(); repaint()
    }
}



