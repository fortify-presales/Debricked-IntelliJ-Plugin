package com.debricked.intellijplugin.ui

import com.debricked.intellijplugin.api.DebrickedApiClient
import com.debricked.intellijplugin.core.DebrickedPluginManager
import com.debricked.intellijplugin.domain.DebrickedScanContext
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
import com.debricked.intellijplugin.settings.DebrickedCredentialStore
import com.debricked.intellijplugin.settings.DebrickedDefaultTab
import com.debricked.intellijplugin.settings.DebrickedSettingsConfigurable
import com.debricked.intellijplugin.settings.DebrickedSettingsManager
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
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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
import java.net.URLEncoder
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
) : DebrickedPluginManager.FindingsUpdateListener {

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
        "Summary widgets and quick actions will be added in Phase 2."
    )
    private val dependenciesPanel = PlaceholderTabPanel(
        "Dependencies",
        "Direct and transitive dependency views will be added in Phase 3."
    )
    private val licensesPanel = PlaceholderTabPanel(
        "Licenses",
        "License summary and policy information will be added in Phase 4."
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
        val contentFactory = ContentFactory.SERVICE.getInstance()
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
        if (selectedTab() != DebrickedTab.VULNERABILITIES || !startupContextReady) return
        val context = currentTabContext()
        vulnerabilitiesTabProvider.loadData(context, vulnerabilitiesDirty)
        vulnerabilitiesDirty = false
    }

    private fun requestVulnerabilityLoad(forceRefresh: Boolean) {
        vulnerabilitiesDirty = vulnerabilitiesDirty || forceRefresh
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

    fun repositoryActionText(): String =
        repositories.firstOrNull { it.id == settings.getRepositoryId() }?.name
            ?: settings.getRepositoryName().ifBlank { "Repository" }

    fun branchActionText(): String =
        branches.firstOrNull { it.id == settings.getSelectedBranchId() }?.name
            ?: settings.getSelectedBranchName().ifBlank { "Branch" }

    internal fun hasCredentials(): Boolean = pluginManager.hasCredentials()
    internal fun hasRepositories(): Boolean = repositories.isNotEmpty()
    internal fun hasBranches(): Boolean = branches.isNotEmpty()
    internal fun isLoadingBranches(): Boolean = loadingBranches
    internal fun availableRepositories(): List<RepositoryChoice> = repositories
    internal fun availableBranches(): List<BranchChoice> = branches

    internal fun selectRepository(repository: RepositoryChoice) {
        handleRepositorySelected(repository, forceSelectionRefresh = true)
    }

    internal fun selectBranch(branch: BranchChoice) {
        applyBranchSelection(branch, forceSelectionRefresh = true)
    }

    internal fun refreshRepositoriesFromToolbar() {
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

    internal fun openSettingsFromToolbar() {
        openSettings()
    }
}

private class ToolWindowContextHeader(
    private val controller: DebrickedTabbedToolWindowContent
) : JPanel(GridBagLayout()) {
    private val repositoryAction = RepositorySelectionAction(controller)
    private val branchAction = BranchSelectionAction(controller)
    private val contextToolbar = ActionManager.getInstance()
        .createActionToolbar("DebrickedContextHeader", DefaultActionGroup(repositoryAction, branchAction), true)
    private val refreshRepositoriesButton = JButton("Refresh repositories", AllIcons.Actions.Refresh).apply {
        toolTipText = "Reload repository list from Debricked"
        addActionListener { controller.refreshRepositoriesFromToolbar() }
    }
    private val settingsButton = JButton("Settings", AllIcons.General.Settings).apply {
        toolTipText = "Open Debricked settings"
        addActionListener { controller.openSettingsFromToolbar() }
    }

    init {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(6, 8)
        )

        contextToolbar.setTargetComponent(this)
        contextToolbar.setMinimumButtonSize(ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        contextToolbar.component.isOpaque = false
        add(contextToolbar.component, GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            insets = Insets(0, 0, 0, 8)
        })
        add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
            gridx = 1
            gridy = 0
            insets = Insets(0, 0, 0, 8)
        })
        add(refreshRepositoriesButton, GridBagConstraints().apply {
            gridx = 2
            gridy = 0
            insets = Insets(0, 0, 0, 8)
        })
        add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply {
            gridx = 3
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
        })
        add(settingsButton, GridBagConstraints().apply {
            gridx = 4
            gridy = 0
        })

        syncFromController()
    }

    fun syncFromController() {
        contextToolbar.updateActionsImmediately()

        settingsButton.isEnabled = true
    }
}

private class RepositorySelectionAction(
    private val controller: DebrickedTabbedToolWindowContent
) : ComboBoxAction() {
    init {
        setPopupTitle("Select Repository")
        setSmallVariant(true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = controller.repositoryActionText()
        e.presentation.description = "Select Debricked repository"
        e.presentation.isEnabled = controller.hasCredentials() && controller.hasRepositories()
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        val repositories = controller.availableRepositories()
        val selectedRepositoryId = DebrickedSettingsManager.getInstance().getRepositoryId()
        val shortlist = mutableListOf<RepositoryChoice>()

        repositories.firstOrNull { it.id == selectedRepositoryId }?.let { shortlist.add(it) }
        repositories.asSequence()
            .filter { it.id != selectedRepositoryId }
            .take(REPOSITORY_SHORTLIST_LIMIT - shortlist.size)
            .forEach { shortlist.add(it) }

        shortlist.forEach { repository ->
            group.add(object : AnAction(repository.name.ifBlank { repository.id }) {
                override fun actionPerformed(e: AnActionEvent) {
                    controller.selectRepository(repository)
                }
            })
        }

        if (repositories.size > shortlist.size) {
            group.add(object : AnAction("Search all repositories...") {
                override fun actionPerformed(e: AnActionEvent) {
                    showRepositorySearchPopup(button, repositories)
                }
            })
        }

        if (group.childActionsOrStubs.isEmpty()) {
            group.add(disabledAction("No repositories"))
        }
        return group
    }
    override fun getMinWidth(): Int = 180

    private fun showRepositorySearchPopup(button: JComponent, repositories: List<RepositoryChoice>) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(repositories.sortedBy { it.name.ifBlank { it.id }.lowercase() })
            .setTitle("Select Repository")
            .setMovable(false)
            .setResizable(true)
            .setRequestFocus(true)
            .setFilterAlwaysVisible(true)
            .setNamerForFiltering { it.name.ifBlank { it.id } }
            .setItemChosenCallback { selected -> controller.selectRepository(selected) }
            .createPopup()
            .showUnderneathOf(button)
    }
}

private class BranchSelectionAction(
    private val controller: DebrickedTabbedToolWindowContent
) : ComboBoxAction() {
    init {
        setPopupTitle("Select Branch")
        setSmallVariant(true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = if (controller.isLoadingBranches()) "Loading branches..." else controller.branchActionText()
        e.presentation.description = "Select Debricked branch"
        e.presentation.isEnabled = controller.hasCredentials() && (controller.hasBranches() || controller.isLoadingBranches())
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        controller.availableBranches().forEach { branch ->
            group.add(object : AnAction(branch.name.ifBlank { branch.id }) {
                override fun actionPerformed(e: AnActionEvent) {
                    controller.selectBranch(branch)
                }
            })
        }
        if (group.childActionsOrStubs.isEmpty()) {
            group.add(disabledAction("No branches"))
        }
        return group
    }
    override fun getMinWidth(): Int = 140
}

private fun disabledAction(text: String): AnAction = object : AnAction(text) {
    override fun actionPerformed(e: AnActionEvent) {}
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = false
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
    private val model = VulnerabilityTableModel()
    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        fillsViewportHeight = true
        rowHeight = 24
    }
    private val detailsPanel = VulnerabilityDetailsPanel { selectedStatus, reviewInfo ->
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

    init {
        border = JBUI.Borders.empty(8)
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
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tablePane, detailsScrollPane).apply {
            resizeWeight = 0.5
            setDividerLocation(0.5)
            border = JBUI.Borders.emptyTop(8)
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
            add(splitPane, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        showEmptyState("Loading vulnerabilities")
        applySortMode()
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
            FindingsState.NO_REMOTE_RESULTS -> "No findings available"
            FindingsState.STALE_DEPENDENCY_CHANGES -> "Local dependency changes detected"
            FindingsState.STALE_COMMIT -> "Latest branch results"
            FindingsState.CURRENT -> ""
        }
        statusLabel.isVisible = statusLabel.text.isNotBlank()
        if (findings.isEmpty()) {
            detailsPanel.setFinding(null, context)
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
            "Showing $pageStart-$pageEnd of $knownTotal"
        } else {
            "Showing $visibleCount entries"
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
        applySortMode()
        dispatchQuery(forceRefresh = false)
    }

    private fun setGroupMode(mode: GroupMode) {
        groupMode = mode
        applySortMode()
    }

    private fun updateDetailsForSelection(context: DebrickedScanContext? = null) {
        val resolvedContext = context ?: currentScanContext
        val viewRow = table.selectedRow
        if (viewRow < 0 || viewRow >= table.rowCount) {
            currentDetailsContext = null
            detailsRequestToken += 1
            detailsPanel.setFinding(null, resolvedContext)
            return
        }
        val finding = model.getFindingAt(viewRow)
        if (finding == null) {
            currentDetailsContext = null
            detailsRequestToken += 1
            detailsPanel.setFinding(null, resolvedContext)
            return
        }
        detailsPanel.setFinding(finding, resolvedContext)
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
        return VulnerabilityDetailsBundle(
            summarySources = summarySources,
            scoreSummaries = scoreSummaries,
            cvssDetails = null,
            dates = com.debricked.intellijplugin.domain.VulnerabilityDates(),
            affectedDependencies = affectedDependencies,
            files = emptyList(),
            dependencyTree = null,
            repositoryStatuses = repositoryStatuses,
            reviewStatusInfo = reviewStatusInfo,
            rootFixes = null,
            references = emptyList(),
            reachabilityDetails = null
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
            applyColumnVisibility()
            refreshView()
        }
    }
}

private class VulnerabilityTableModel : AbstractTableModel() {
    private val columns = arrayOf(
        "Name",
        "Introduced",
        "CVSS",
        "Dependencies",
        "Reachable Path",
        "Review Status",
        "Exploited (CISA)"
    )
    private var findings = emptyList<VulnerabilityFinding>()
    private var rows = emptyList<TableRow>()
    private var visibleFindings = 0

    private sealed class TableRow {
        data class GroupHeader(val label: String, val count: Int) : TableRow()
        data class Finding(val finding: VulnerabilityFinding) : TableRow()
    }

    fun setFindings(newFindings: List<VulnerabilityFinding>) {
        findings = newFindings
    }

    fun rebuildView(
        textFilter: String,
        visibleSeverities: Set<Severity>,
        sortColumn: Int,
        sortAscending: Boolean,
        groupColumn: Int?
    ) {
        val text = textFilter.lowercase()
        val filtered = findings.filter { finding ->
            if (!visibleSeverities.contains(finding.displaySeverity())) return@filter false
            if (text.isBlank()) return@filter true
            val name = (finding.cveId ?: finding.id).lowercase()
            val dep = buildDependencySearchText(finding).lowercase()
            name.contains(text) || dep.contains(text)
        }

        val sorted = filtered.sortedWith(compareByColumn(sortColumn, sortAscending))
        rows = if (groupColumn == null) {
            sorted.map { TableRow.Finding(it) }
        } else {
            buildGroupedRows(sorted, groupColumn)
        }
        visibleFindings = filtered.size
        fireTableDataChanged()
    }

    fun visibleFindingCount(): Int = visibleFindings
    fun isGroupHeader(row: Int): Boolean = rows.getOrNull(row) is TableRow.GroupHeader
    fun getFindingAt(row: Int): VulnerabilityFinding? = (rows.getOrNull(row) as? TableRow.Finding)?.finding

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        2 -> Double::class.javaObjectType
        else -> String::class.java
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        return when (val row = rows[rowIndex]) {
            is TableRow.GroupHeader -> when (columnIndex) {
                0 -> "${row.label} (${row.count})"
                else -> ""
            }
            is TableRow.Finding -> {
                val finding = row.finding
                when (columnIndex) {
                    0 -> finding.cveId ?: finding.id
                    1 -> finding.introducedDateText()
                    2 -> finding.cvss3Score ?: finding.cvss2Score ?: finding.exploitabilityScore
                    3 -> buildDependencyText(finding)
                    4 -> finding.reachablePath ?: "Unknown"
                    5 -> finding.reviewStatusDisplay()
                    6 -> finding.exploitedDisplay()
                    else -> ""
                }
            }
        }
    }

    private fun buildGroupedRows(sorted: List<VulnerabilityFinding>, groupColumn: Int): List<TableRow> {
        val grouped = linkedMapOf<String, MutableList<VulnerabilityFinding>>()
        sorted.forEach { finding ->
            val key = groupKeyFor(groupColumn, finding)
            grouped.getOrPut(key) { mutableListOf() }.add(finding)
        }
        val output = mutableListOf<TableRow>()
        grouped.forEach { (key, groupFindings) ->
            output.add(TableRow.GroupHeader(key, groupFindings.size))
            groupFindings.forEach { output.add(TableRow.Finding(it)) }
        }
        return output
    }

    private fun compareByColumn(column: Int, ascending: Boolean): Comparator<VulnerabilityFinding> {
        val factor = if (ascending) 1 else -1
        return Comparator { a, b ->
            val cmp = when (column) {
                0 -> (a.cveId ?: a.id).compareTo((b.cveId ?: b.id), ignoreCase = true)
                1 -> compareNullableLongs(a.introducedAt, b.introducedAt)
                2 -> compareCvss(a, b, ascending)
                3 -> buildPrimaryDependencyText(a).compareTo(buildPrimaryDependencyText(b), ignoreCase = true)
                4 -> (a.reachablePath ?: "Unknown").compareTo(b.reachablePath ?: "Unknown", ignoreCase = true)
                5 -> a.reviewStatusDisplay().compareTo(b.reviewStatusDisplay(), ignoreCase = true)
                6 -> a.exploitedDisplay().compareTo(b.exploitedDisplay(), ignoreCase = true)
                else -> 0
            }
            if (cmp != 0) {
                if (column == 2) cmp else cmp * factor
            } else {
                a.id.compareTo(b.id, ignoreCase = true)
            }
        }
    }

    private fun compareCvss(a: VulnerabilityFinding, b: VulnerabilityFinding, ascending: Boolean): Int {
        val scoreCmp = compareNullableDoubles(
            a.cvss3Score ?: a.cvss2Score ?: a.exploitabilityScore,
            b.cvss3Score ?: b.cvss2Score ?: b.exploitabilityScore
        )
        if (scoreCmp != 0) {
            return if (ascending) scoreCmp else -scoreCmp
        }
        return cvssSortRank(a).compareTo(cvssSortRank(b))
    }

    private fun cvssSortRank(finding: VulnerabilityFinding): Int = when {
        finding.cvss3Score != null -> 0
        finding.cvss2Score != null -> 1
        finding.exploitabilityScore != null -> 2
        else -> 3
    }

    private fun compareNullableDoubles(a: Double?, b: Double?): Int = when {
        a == null && b == null -> 0
        a == null -> -1
        b == null -> 1
        else -> a.compareTo(b)
    }

    private fun compareNullableLongs(a: Long?, b: Long?): Int = when {
        a == null && b == null -> 0
        a == null -> -1
        b == null -> 1
        else -> a.compareTo(b)
    }

    private fun groupKeyFor(column: Int, finding: VulnerabilityFinding): String = when (column) {
        3 -> buildPrimaryDependencyText(finding)
        4 -> "Reachable path: ${finding.reachablePath ?: "Unknown"}"
        5 -> "Review status: ${finding.reviewStatusDisplay()}"
        6 -> "Exploited (CISA): ${finding.exploitedDisplay()}"
        else -> "Other"
    }

    private fun buildDependencyText(finding: VulnerabilityFinding): String {
        val primary = buildPrimaryDependencyText(finding)
        val additionalCount = (finding.affectedDependencies.ifEmpty { finding.fallbackDependencies() }.size - 1).coerceAtLeast(0)
        return if (additionalCount > 0) {
            "$primary +$additionalCount"
        } else {
            primary
        }
    }

    private fun buildPrimaryDependencyText(finding: VulnerabilityFinding): String {
        val dependency = finding.affectedDependencies.firstOrNull() ?: finding.fallbackDependencies().firstOrNull()
        if (dependency == null || dependency.name.isBlank()) {
            return finding.ecosystem.name.lowercase()
        }
        val label = dependency.version?.takeIf { it.isNotBlank() }?.let { "${dependency.name}:$it" } ?: dependency.name
        return if (label.contains("(") && label.contains(")")) {
            label
        } else {
            "${label} (${finding.ecosystem.name.lowercase().replaceFirstChar { it.titlecase() }})"
        }
    }

    private fun buildDependencySearchText(finding: VulnerabilityFinding): String {
        val dependencies = finding.affectedDependencies.ifEmpty { finding.fallbackDependencies() }
        return dependencies.joinToString(" ") { dependency ->
            dependency.version?.takeIf { it.isNotBlank() }?.let { "${dependency.name} $it" } ?: dependency.name
        }
    }
}

private fun VulnerabilityFinding.displaySeverity(): Severity = when {
    severity != Severity.UNKNOWN -> severity
    cvss3Score != null -> when {
        cvss3Score >= 9.0 -> Severity.CRITICAL
        cvss3Score >= 7.0 -> Severity.HIGH
        cvss3Score >= 4.0 -> Severity.MEDIUM
        cvss3Score > 0.0 -> Severity.LOW
        else -> Severity.UNKNOWN
    }
    cvss2Score != null -> when {
        cvss2Score >= 7.0 -> Severity.HIGH
        cvss2Score >= 4.0 -> Severity.MEDIUM
        cvss2Score > 0.0 -> Severity.LOW
        else -> Severity.UNKNOWN
    }
    else -> Severity.UNKNOWN
}

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun VulnerabilityFinding.introducedDateText(): String {
    val ts = introducedAt ?: return "Unknown"
    return runCatching {
        DATE_FORMATTER.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate())
    }.getOrElse { "Unknown" }
}

private fun VulnerabilityFinding.reviewStatusDisplay(): String {
    val raw = reviewStatus ?: return "N/A"
    return raw.replace('_', ' ').replaceFirstChar { it.titlecase() }
}

private fun VulnerabilityFinding.exploitedDisplay(): String = when (exploited) {
    true -> "Yes"
    false -> "No"
    null -> "Unknown"
}

private fun VulnerabilityFinding.primaryIdentifier(): String = cveId ?: id

private fun VulnerabilityFinding.cvssDetailsDisplay(): String {
    val score = cvss3Score ?: cvss2Score ?: exploitabilityScore ?: return "Not available"
    val version = when {
        cvss3Score != null -> "CVSS3"
        cvss2Score != null -> "CVSS2"
        else -> "CVSS"
    }
    return "${DecimalFormat("0.0").format(score)} ($version)"
}

private fun VulnerabilityFinding.discoveredRelativeDisplay(): String {
    val ts = introducedAt ?: return "Unknown"
    return runCatching {
        val days = Duration.between(Instant.ofEpochMilli(ts), Instant.now()).toDays().coerceAtLeast(0)
        when {
            days == 0L -> "Today"
            days < 7 -> "$days day${if (days == 1L) "" else "s"} ago"
            days < 30 -> {
                val weeks = (days / 7).coerceAtLeast(1)
                "$weeks week${if (weeks == 1L) "" else "s"} ago"
            }
            days < 365 -> {
                val months = (days / 30).coerceAtLeast(1)
                "$months month${if (months == 1L) "" else "s"} ago"
            }
            else -> {
                val years = (days / 365).coerceAtLeast(1)
                "$years year${if (years == 1L) "" else "s"} ago"
            }
        }
    }.getOrElse { "Unknown" }
}

private fun VulnerabilityFinding.fallbackDependencies(): List<com.debricked.intellijplugin.domain.AffectedDependency> {
    if (packageName.isBlank()) return emptyList()
    return listOf(com.debricked.intellijplugin.domain.AffectedDependency(packageName, version.ifBlank { null }))
}

private class VulnerabilityDetailsPanel(
    private val onReviewStatusApply: (String, VulnerabilityReviewStatusInfo?) -> Unit
) : JPanel(BorderLayout()) {
    private val vulnerabilityCaptionLabel = JBLabel("Vulnerability").apply {
        foreground = JBColor(0xD81B60, 0xF06292)
    }
    private val titleLabel = JBLabel("Select a vulnerability").apply {
        font = font.deriveFont(Font.BOLD, 28f)
    }
    private val discoveredHeaderLabel = JBLabel("Discovered").apply {
        font = font.deriveFont(Font.BOLD, 16f)
    }
    private val identifierLabel = JBLabel("").apply { foreground = JBColor.GRAY }
    private val discoveredValueLabel = JBLabel("").apply { foreground = JBColor.GRAY }
    private val cvssLabel = JBLabel("")
    private val statusLabel = JBLabel("").apply { verticalAlignment = SwingConstants.TOP }
    private val dependenciesLabel = JBLabel("").apply { verticalAlignment = SwingConstants.TOP }
    private val dependencyInlineLabel = JBLabel("").apply { verticalAlignment = SwingConstants.TOP }
    private val fixedVersionLabel = JBLabel("")
    private val linkLabel = JBLabel("").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = JBColor.BLUE
        isVisible = false
    }
    private val scanLabel = JBLabel("").apply { foreground = JBColor.GRAY }
    private val detailsStatusLabel = JBLabel("").apply { foreground = JBColor.GRAY }
    private val scoresArea = createDetailsArea()
    private val scoreLabels = (0..5).map { JBLabel("") }
    private val advisoryCardsPanel = JPanel(GridLayout(1, 3, JBUI.scale(12), 0)).apply {
        isOpaque = false
        alignmentX = TOP_ALIGNMENT
    }
    private val cweCard = createAdvisoryCard("CWE")
    private val githubCard = createAdvisoryCard("GitHub")
    private val nvdCard = createAdvisoryCard("NVD")
    private val introducedArea = createDetailsArea()
    private val fixesArea = createDetailsArea()
    private val referencesArea = createDetailsArea()
    private val reachabilityArea = createDetailsArea()
    private val reviewMetaArea = createDetailsArea()
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
    private var findingUrl: String? = null
    private var currentReviewInfo: VulnerabilityReviewStatusInfo? = null

    private data class AdvisoryCard(
        val sourceName: String,
        val panel: JPanel,
        val subtitleLabel: JBLabel,
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
                layout = GridLayout(2, 3, JBUI.scale(8), JBUI.scale(4))
                isOpaque = false
                alignmentX = TOP_ALIGNMENT
                for (i in 0..2) {
                    add(scoreLabels[i].apply {
                        horizontalAlignment = SwingConstants.CENTER
                        font = font.deriveFont(Font.BOLD, 16f)
                    })
                }
                for (i in 3..5) {
                    add(scoreLabels[i].apply {
                        horizontalAlignment = SwingConstants.CENTER
                        font = font.deriveFont(Font.PLAIN, 10f)
                    })
                }
            }
            add(leftPanel)
            add(discoveredPanel)
            add(scoresPanel)
        }
        add(headlinePanel)
        add(Box.createVerticalStrut(8))
        add(emptyStateLabel)
        add(Box.createVerticalStrut(8))
        add(dependencyInlineLabel)
        add(Box.createVerticalStrut(10))
        advisoryCardsPanel.add(cweCard.panel)
        advisoryCardsPanel.add(githubCard.panel)
        advisoryCardsPanel.add(nvdCard.panel)
        summaryRowPanel.add(advisoryCardsPanel, BorderLayout.CENTER)
        add(summaryRowPanel)
        add(Box.createVerticalStrut(10))
        add(identifierLabel)
        add(Box.createVerticalStrut(8))
        add(cvssLabel)
        add(Box.createVerticalStrut(8))
        add(statusLabel)
        add(Box.createVerticalStrut(10))
        add(dependenciesLabel)
        add(Box.createVerticalStrut(12))
        add(fixedVersionLabel)
        add(Box.createVerticalStrut(8))
        add(linkLabel)
        add(Box.createVerticalStrut(8))
        add(scanLabel)
        add(Box.createVerticalStrut(8))
        add(detailsStatusLabel)
        addSection("Introduced through", introducedArea)
        addSection("Suggested fixes", fixesArea)
        addSection("References", referencesArea)
        addSection("Reachability details", reachabilityArea)
        addSection("Review details", reviewMetaArea)
        add(Box.createVerticalStrut(6))
        add(reviewActionPanel)

        linkLabel.addMouseListener(object : MouseAdapter() {
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
            dependencyInlineLabel,
            summaryRowPanel,
            identifierLabel,
            cvssLabel,
            statusLabel,
            dependenciesLabel,
            fixedVersionLabel,
            linkLabel,
            scanLabel,
            detailsStatusLabel,
            reviewActionPanel
        ).forEach { component ->
            component.alignmentX = LEFT_ALIGNMENT
        }
        renderEmptyState()
    }

    fun setFinding(finding: VulnerabilityFinding?, context: DebrickedScanContext?) {
        if (finding == null) {
            renderEmptyState()
            return
        }

        headlinePanel.isVisible = true
        emptyStateLabel.isVisible = false
        dependencyInlineLabel.isVisible = true
        summaryRowPanel.isVisible = true
        identifierLabel.isVisible = true
        discoveredValueLabel.isVisible = true
        cvssLabel.isVisible = true
        statusLabel.isVisible = true
        dependenciesLabel.isVisible = true
        fixedVersionLabel.isVisible = true
        scanLabel.isVisible = true
        reviewActionPanel.isVisible = true
        sectionPanels.forEach { it.isVisible = false }
        currentReviewInfo = null

        val id = finding.primaryIdentifier()
        val title = finding.title?.takeIf { it.isNotBlank() && !it.equals(id, ignoreCase = true) } ?: id
        titleLabel.text = title
        discoveredValueLabel.text = buildString {
            append(finding.discoveredRelativeDisplay())
            val discoveredDate = finding.introducedDateText()
            if (discoveredDate != "Unknown") {
                append(" • ")
                append(discoveredDate)
            }
        }
        identifierLabel.text = "Identifier: $id"
        val dependencies = finding.affectedDependencies.ifEmpty { finding.fallbackDependencies() }
        val firstDependency = dependencies.firstOrNull()?.name ?: "Unknown dependency"
        dependencyInlineLabel.text = htmlText("in dependency <b>${htmlEscape(firstDependency)}</b>")
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
        dependenciesLabel.text = htmlText(
            buildString {
                append("<b>Affected dependencies:</b><br/>")
                append(dependencies.joinToString("<br/>") { dependency ->
                    val version = dependency.version?.takeIf { it.isNotBlank() }?.let { ":$it" } ?: ""
                    htmlEscape("${dependency.name}$version")
                })
            }
        )
        renderSummarySources(emptyList())
        fixedVersionLabel.text = "Fixed version: ${finding.fixedVersion ?: "Not provided"}"
        reviewStatusCombo.selectedItem = finding.reviewStatus ?: "unexamined"
        findingUrl = buildFindingUrl(finding)
        linkLabel.text = "<html><a href=''>${htmlEscape("Open in Debricked UI")}</a></html>"
        linkLabel.isVisible = findingUrl != null
        scanLabel.text = contextSummary(context)
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
        val dependencies = bundle.affectedDependencies.ifEmpty { finding.affectedDependencies.ifEmpty { finding.fallbackDependencies() } }
        dependenciesLabel.text = htmlText(
            buildString {
                append("<b>Affected dependencies:</b><br/>")
                append(dependencies.joinToString("<br/>") { dependency ->
                    val version = dependency.version?.takeIf { it.isNotBlank() }?.let { ":$it" } ?: ""
                    htmlEscape("${dependency.name}$version")
                })
            }
        )
        fixedVersionLabel.text = buildFixHeadline(finding, bundle.rootFixes)
        scoresArea.text = buildCompactScoreSummaryText(bundle.scoreSummaries)
        renderSummarySources(bundle.summarySources)
        introducedArea.text = buildIntroducedThroughText(bundle.files, bundle.dependencyTree)
        fixesArea.text = buildRootFixText(bundle.rootFixes)
        referencesArea.text = buildReferencesText(bundle.references)
        reachabilityArea.text = buildReachabilityText(finding, bundle)
        reviewMetaArea.text = buildReviewMetaText(context, finding, bundle)
        currentReviewInfo = bundle.reviewStatusInfo
        reviewStatusCombo.selectedItem = resolveDisplayedReviewStatus(context, finding, bundle)
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
        dependencyInlineLabel.text = ""
        dependencyInlineLabel.isVisible = false
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
        introducedArea.text = ""
        fixesArea.text = ""
        referencesArea.text = ""
        reachabilityArea.text = ""
        reviewMetaArea.text = ""
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

    private fun addSection(title: String, area: JTextArea) {
        val sectionPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            isVisible = false
            add(Box.createVerticalStrut(12))
            add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD)
                alignmentX = LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(4))
            add(area)
        }
        area.alignmentX = LEFT_ALIGNMENT
        sectionPanels += sectionPanel
        sectionAreaToPanel[area] = sectionPanel
        add(sectionPanel)
    }

    private fun updateSectionVisibility() {
        sectionAreaToPanel.forEach { (area, panel) ->
            panel.isVisible = area.text.isNotBlank()
        }
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

    private fun createAdvisoryCard(sourceName: String): AdvisoryCard {
        val subtitleLabel = JBLabel().apply {
            font = font.deriveFont(Font.BOLD)
            alignmentX = LEFT_ALIGNMENT
        }
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
        
        scoreLabels[0].text = cvss["CVSS4"]?.scoreText ?: "N/A"
        scoreLabels[1].text = cvss["CVSS3"]?.scoreText ?: "N/A"
        scoreLabels[2].text = cvss["CVSS2"]?.scoreText ?: "N/A"
        scoreLabels[3].text = "CVSS4"
        scoreLabels[4].text = "CVSS3"
        scoreLabels[5].text = "CVSS2"
        
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
        cweCard.subtitleLabel.text = title
        cweCard.linkLabel.text = "View reference"
        cweCard.linkLabel.isEnabled = cweCard.linkTarget != null
        cweCard.linkLabel.isVisible = cweCard.linkTarget != null
        cweCard.moreDetailsButton.isEnabled = cweCard.fullText.length > ADVISORY_PREVIEW_LENGTH
        cweCard.moreDetailsButton.text = if (cweCard.moreDetailsButton.isEnabled) "More details" else ""
        updateAdvisoryCardBody(cweCard)
    }

    private fun bindAdvisoryCard(card: AdvisoryCard, source: VulnerabilitySummarySource?) {
        card.fullText = source?.description?.trim().orEmpty()
        card.expanded = false
        card.linkTarget = source?.link?.takeIf { it.isNotBlank() }
        val title = source?.title?.takeIf { it.isNotBlank() } ?: "${card.sourceName} advisory"
        val titleWithIcon = if (card.linkTarget != null) "$title 🔗" else title
        card.subtitleLabel.text = titleWithIcon
        card.linkLabel.text = "View on ${card.sourceName}"
        card.linkLabel.isEnabled = card.linkTarget != null
        card.linkLabel.isVisible = card.linkTarget != null
        card.moreDetailsButton.isEnabled = card.fullText.length > ADVISORY_PREVIEW_LENGTH
        card.moreDetailsButton.text = if (card.moreDetailsButton.isEnabled) "More details" else ""
        updateAdvisoryCardBody(card)
    }

    private fun toggleAdvisoryExpansion(card: AdvisoryCard) {
        if (!card.moreDetailsButton.isEnabled) return
        card.expanded = !card.expanded
        updateAdvisoryCardBody(card)
        card.panel.revalidate()
        card.panel.repaint()
        scrollToTop()
    }

    private fun updateAdvisoryCardBody(card: AdvisoryCard) {
        val text = card.fullText.ifBlank { "No summary available." }
        card.bodyArea.text = if (card.expanded || text.length <= ADVISORY_PREVIEW_LENGTH) {
            text
        } else {
            "${text.take(ADVISORY_PREVIEW_LENGTH).trimEnd()}..."
        }
        card.moreDetailsButton.text = if (card.moreDetailsButton.isEnabled) {
            if (card.expanded) "Less details" else "More details"
        } else {
            "Details unavailable"
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

    private fun buildRootFixText(rootFixes: VulnerabilityRootFixes?): String {
        if (rootFixes == null) return ""
        val fixes = rootFixes.fixes.entries.joinToString("\n") { "${it.key} -> ${it.value}" }
        val commands = rootFixes.commands.joinToString("\n")
        return listOf(
            if (rootFixes.rootFixesCount > 0) "Root fixes found: ${rootFixes.rootFixesCount}" else "",
            fixes,
            commands
        ).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun buildReferencesText(references: List<VulnerabilityReferenceLink>): String =
        references.joinToString("\n") { "${it.title} - ${it.link}" }

    private fun buildReachabilityText(
        finding: VulnerabilityFinding,
        bundle: VulnerabilityDetailsBundle
    ): String {
        val lines = mutableListOf<String>()
        lines += "List result: ${finding.reachablePath ?: "Unknown"}"
        finding.reachabilityMessage?.takeIf { it.isNotBlank() }?.let { lines += "Message: $it" }
        bundle.reachabilityDetails?.let { details ->
            if (!details.supported) {
                lines += "Detailed reachability analysis is not available for this account or repository."
            } else {
                details.reachAnalysis?.let { lines += "Analysis score: $it" }
                details.reachAnalysisLanguage?.let { lines += "Language: $it" }
                details.reachAnalysisMessage?.takeIf { it.isNotBlank() }?.let { lines += "Detail: $it" }
            }
        }
        return lines.joinToString("\n")
    }

    private fun buildReviewMetaText(
        context: VulnerabilityDetailsContext,
        finding: VulnerabilityFinding,
        bundle: VulnerabilityDetailsBundle
    ): String {
        val repoStatus = bundle.repositoryStatuses.firstOrNull { it.id == context.repositoryId }
        val lines = mutableListOf<String>()
        lines += "Current status: ${repoStatus?.type ?: finding.reviewStatusDisplay()}"
        repoStatus?.pausedUntil?.takeIf { it.isNotBlank() }?.let { lines += "Paused until: $it" }
        bundle.reviewStatusInfo?.let { info ->
            lines += "Comment required: ${if (info.enforceComment) "Yes" else "No"}"
            info.commentMinLength?.takeIf { it > 0 }?.let { lines += "Minimum comment length: $it" }
            info.oldComment?.takeIf { it.isNotBlank() }?.let { lines += "Previous comment: $it" }
            info.oldCommentAuthor?.takeIf { it.isNotBlank() }?.let { lines += "Comment author: $it" }
        }
        return lines.joinToString("\n")
    }

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

    private fun htmlText(text: String): String = "<html>${text.replace("\n", "<br/>")}</html>"

    private fun htmlEscape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private class CvssRenderer : DefaultTableCellRenderer() {
    private val df = DecimalFormat("0.0")

    override fun getTableCellRendererComponent(
        table: JTable?,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        if (table != null && (table.model as? VulnerabilityTableModel)?.isGroupHeader(row) == true) {
            text = ""
            icon = null
            foreground = if (isSelected) table.selectionForeground else JBColor.GRAY
            font = font.deriveFont(Font.BOLD)
            return c
        }

        val finding = (table?.model as? VulnerabilityTableModel)?.getFindingAt(row)
        val score = (value as? Number)?.toDouble()
        text = score?.let { df.format(it) } ?: "-"
        foreground = scoreColor(score)
        horizontalAlignment = SwingConstants.LEFT
        horizontalTextPosition = SwingConstants.LEFT
        iconTextGap = JBUI.scale(6)
        icon = finding
            ?.takeIf { it.cvss3Score != null }
            ?.displaySeverity()
            ?.takeIf { it != Severity.UNKNOWN }
            ?.let { SeverityShieldIcon(it) }
        font = font.deriveFont(Font.PLAIN)
        return c
    }

    private fun scoreColor(score: Double?): Color = when {
        score == null -> JBColor.GRAY
        score >= 9.0 -> JBColor(Color(170, 30, 30), Color(255, 120, 120))
        score >= 7.0 -> JBColor(Color(190, 100, 0), Color(255, 170, 90))
        score >= 4.0 -> JBColor(Color(160, 130, 0), Color(230, 210, 90))
        score > 0.0 -> JBColor(Color(0, 110, 190), Color(120, 180, 255))
        else -> JBColor.GRAY
    }
}

private class SeverityShieldIcon(private val severity: Severity) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(15)

    override fun getIconHeight(): Int = JBUI.scale(16)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.translate(x.toDouble(), y.toDouble())
            g2.color = backgroundColor(severity)
            g2.fill(buildShieldPath(iconWidth.toDouble(), iconHeight.toDouble()))

            g2.color = JBColor.WHITE
            val baseFont = c?.font ?: Font(Font.SANS_SERIF, Font.BOLD, 12)
            g2.font = baseFont.deriveFont(Font.BOLD, JBUI.scale(9).toFloat())
            val letter = severityLetter(severity)
            val metrics = g2.fontMetrics
            val textX = (iconWidth - metrics.stringWidth(letter)) / 2
            val textY = ((iconHeight - metrics.height) / 2) + metrics.ascent - JBUI.scale(1)
            g2.drawString(letter, textX, textY)
        } finally {
            g2.dispose()
        }
    }

    private fun backgroundColor(severity: Severity): Color = when (severity) {
        Severity.CRITICAL -> JBColor(Color(233, 53, 112), Color(255, 120, 170))
        Severity.HIGH -> JBColor(Color(234, 97, 35), Color(255, 155, 102))
        Severity.MEDIUM -> JBColor(Color(226, 171, 24), Color(247, 209, 102))
        Severity.LOW -> JBColor(Color(58, 120, 221), Color(116, 175, 255))
        Severity.UNKNOWN -> JBColor.GRAY
    }

    private fun severityLetter(severity: Severity): String = when (severity) {
        Severity.CRITICAL -> "C"
        Severity.HIGH -> "H"
        Severity.MEDIUM -> "M"
        Severity.LOW -> "L"
        Severity.UNKNOWN -> "?"
    }

    private fun buildShieldPath(width: Double, height: Double): Path2D.Double = Path2D.Double().apply {
        moveTo(width * 0.20, height * 0.10)
        quadTo(width * 0.50, 0.0, width * 0.80, height * 0.10)
        quadTo(width * 0.93, height * 0.15, width * 0.93, height * 0.31)
        lineTo(width * 0.93, height * 0.57)
        quadTo(width * 0.93, height * 0.77, width * 0.50, height)
        quadTo(width * 0.07, height * 0.77, width * 0.07, height * 0.57)
        lineTo(width * 0.07, height * 0.31)
        quadTo(width * 0.07, height * 0.15, width * 0.20, height * 0.10)
        closePath()
    }
}

private class NameRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable?,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        horizontalAlignment = SwingConstants.LEFT
        if (table != null && (table.model as? VulnerabilityTableModel)?.isGroupHeader(row) == true) {
            font = font.deriveFont(Font.BOLD)
            foreground = if (isSelected) table.selectionForeground else JBColor.GRAY
        } else {
            font = font.deriveFont(Font.PLAIN)
        }
        return c
    }
}

private class LeftAlignRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable?,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        horizontalAlignment = SwingConstants.LEFT
        if (table != null && (table.model as? VulnerabilityTableModel)?.isGroupHeader(row) == true) {
            text = ""
            foreground = if (isSelected) table.selectionForeground else JBColor.GRAY
            font = font.deriveFont(Font.BOLD)
        } else {
            font = font.deriveFont(Font.PLAIN)
        }
        return c
    }
}

private class PlaceholderTabPanel(
    title: String,
    description: String
) : JPanel(GridBagLayout()) {
    init {
        border = JBUI.Borders.empty(16)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(20, 24)
            )
            maximumSize = Dimension(420, Int.MAX_VALUE)
            add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, 14f)
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(8))
            add(JBLabel(description).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = JBColor.GRAY
            })
        })
    }
}
