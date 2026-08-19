package com.debricked.intellijplugin.ui

import com.debricked.intellijplugin.api.DebrickedApiClient
import com.debricked.intellijplugin.core.DebrickedPluginManager
import com.debricked.intellijplugin.domain.*
import com.debricked.intellijplugin.settings.DebrickedCredentialStore
import com.debricked.intellijplugin.settings.DebrickedSettingsManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

// Severity badge colors — adapt to light/dark theme via JBColor
private object SeverityStyle {
    val CRITICAL = JBColor(Color(180, 20, 20), Color(255, 100, 100))
    val HIGH     = JBColor(Color(200, 90,  0), Color(255, 160, 80))
    val MEDIUM   = JBColor(Color(160, 130, 0), Color(220, 200, 80))
    val LOW      = JBColor(Color(0,   100, 200), Color(100, 170, 255))
    val UNKNOWN  = JBColor(Color(100, 100, 100), Color(160, 160, 160))

    fun colorFor(s: Severity) = when (s) {
        Severity.CRITICAL -> CRITICAL
        Severity.HIGH     -> HIGH
        Severity.MEDIUM   -> MEDIUM
        Severity.LOW      -> LOW
        Severity.UNKNOWN  -> UNKNOWN
    }
}

// Tree node payload types
data class SeverityGroupNode(val severity: Severity, val count: Int)
data class FindingNode(val finding: VulnerabilityFinding)

/** Repository identifier used in the toolbar combo. */
data class RepositoryChoice(
    val id: String,
    val name: String,
    val organizationId: String,
    val defaultBranch: String? = null
) {
    override fun toString(): String = name.ifBlank { id }
}

/**
 * Repository selector bar — single line across the full width.
 * Left: "Repository:" label + dropdown + ↺ load-list button + status text.
 * Right: ↺ refresh-findings + ⚙ settings buttons.
 */
class RepositoryBar(
    private val project: Project,
    private val pluginManager: DebrickedPluginManager
) : JPanel(BorderLayout()) {

    private val apiClient = ApplicationManager.getApplication().getService(DebrickedApiClient::class.java)
    private val settings = DebrickedSettingsManager.getInstance()

    private val repoCombo = JComboBox<RepositoryChoice>().apply {
        preferredSize = Dimension(260, preferredSize.height)
        renderer = RepositoryComboRenderer()
        maximumRowCount = 20
    }
    private val loadReposButton   = mkIconButton(AllIcons.Actions.Refresh,  "Refresh repository list from Debricked")
    private val refreshFindingsButton = mkIconButton(AllIcons.Actions.Refresh,  "Refresh vulnerability findings")
    private val settingsButton    = mkIconButton(AllIcons.General.Settings,  "Open Debricked authentication settings")
    private val statusLabel = JBLabel("").apply { foreground = JBColor.GRAY }

    // Guard flag — prevents the ActionListener firing during programmatic model updates
    private var suppressEvents = false

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(6, 8)
        )

        repoCombo.addActionListener {
            if (suppressEvents) return@addActionListener
            val selected = repoCombo.selectedItem as? RepositoryChoice ?: return@addActionListener
            if (selected.id == settings.getRepositoryId()) return@addActionListener
            settings.setRepositoryId(selected.id)
            settings.setRepositoryName(selected.name)
            pluginManager.refreshFindings()
        }

        loadReposButton.addActionListener { loadRepositories() }
        refreshFindingsButton.addActionListener { pluginManager.refreshFindings() }
        settingsButton.addActionListener {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, com.debricked.intellijplugin.settings.DebrickedSettingsConfigurable::class.java)
        }

        statusLabel.border = JBUI.Borders.emptyLeft(8)

        add(JPanel(GridBagLayout()).apply {
            isOpaque = false

            add(JBLabel("Repository:"), GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, 8)
            })

            add(repoCombo, GridBagConstraints().apply {
                gridx = 1
                gridy = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
            })

            add(statusLabel, GridBagConstraints().apply {
                gridx = 2
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 8, 0, 6)
            })

            add(loadReposButton, GridBagConstraints().apply {
                gridx = 3
                gridy = 0
                insets = Insets(0, 0, 0, 2)
            })

            add(refreshFindingsButton, GridBagConstraints().apply {
                gridx = 4
                gridy = 0
                insets = Insets(0, 0, 0, 2)
            })

            add(settingsButton, GridBagConstraints().apply {
                gridx = 5
                gridy = 0
            })
        }, BorderLayout.CENTER)

        preloadCurrentRepo()
    }

    private fun preloadCurrentRepo() {
        val id = settings.getRepositoryId()
        val name = settings.getRepositoryName()
        suppressEvents = true
        if (id.isNotBlank()) {
            val model = DefaultComboBoxModel<RepositoryChoice>()
            model.addElement(RepositoryChoice(id, name.ifBlank { id }, settings.getOrganizationId()))
            repoCombo.model = model
            repoCombo.selectedIndex = 0
            repoCombo.isEnabled = true
        } else {
            repoCombo.model = DefaultComboBoxModel()
            repoCombo.isEnabled = false
            setStatus("Click ↺ to load repositories")
        }
        suppressEvents = false
    }

    private fun loadRepositories() {
        val accessToken = DebrickedCredentialStore.getAccessToken() ?: ""
        val password = DebrickedCredentialStore.getPassword() ?: ""
        val apiUrl = settings.getApiUrl()
        val authMethod = settings.getAuthMethod()
        val username = settings.getUsername()

        loadReposButton.isEnabled = false
        repoCombo.isEnabled = false
        setStatus("Loading…")

        Thread({
            try {
                val repos = apiClient.connectAndGetRepositories(apiUrl, authMethod, accessToken, username, password)
                val choices = repos
                    .filter { it.id.isNotBlank() }
                    .map { RepositoryChoice(it.id, it.name.ifBlank { it.id }, it.organizationId) }
                    .sortedBy { it.name.lowercase() }

                ApplicationManager.getApplication().invokeLater({
                    suppressEvents = true
                    val model = DefaultComboBoxModel<RepositoryChoice>()
                    choices.forEach { model.addElement(it) }
                    repoCombo.model = model
                    repoCombo.isEnabled = choices.isNotEmpty()
                    suppressEvents = false
                    selectCurrentRepo()
                    loadReposButton.isEnabled = true
                    setStatus("${choices.size} repos")
                }, ModalityState.any())
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater({
                    loadReposButton.isEnabled = true
                    repoCombo.isEnabled = repoCombo.model.size > 0
                    setStatus("Load failed: ${e.message?.take(60)}")
                }, ModalityState.any())
            }
        }, "debricked-load-repos").apply { isDaemon = true }.start()
    }

    private fun selectCurrentRepo() {
        val currentId = settings.getRepositoryId()
        if (currentId.isBlank()) return
        val model = repoCombo.model as? DefaultComboBoxModel<RepositoryChoice> ?: return
        suppressEvents = true
        for (i in 0 until model.size) {
            if (model.getElementAt(i).id == currentId) { repoCombo.selectedIndex = i; break }
        }
        suppressEvents = false
    }

    private fun setStatus(text: String) { statusLabel.text = text }

    private fun mkIconButton(icon: javax.swing.Icon, tooltip: String) = JButton(icon).apply {
        toolTipText = tooltip
        preferredSize = Dimension(24, 24)
        minimumSize = preferredSize
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        margin = Insets(0, 0, 0, 0)
    }
}

private class RepositoryComboRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        if (value == null) { text = "Select a repository…"; foreground = JBColor.GRAY }
        return comp
    }
}

/**
 * Main findings panel — shows status header + either a loading spinner,
 * empty state, or a severity-grouped tree of vulnerabilities.
 */
class DebrickedFindingsPanel(
    private val findings: List<VulnerabilityFinding>,
    private val state: FindingsState,
    @Suppress("unused") private val context: DebrickedScanContext?
) : JPanel(BorderLayout()) {

    init {
        border = JBUI.Borders.empty(8)
        add(buildStatusBar(), BorderLayout.NORTH)
        add(buildContent(), BorderLayout.CENTER)
    }

    private fun buildStatusBar(): JPanel {
        // Read directly from settings so the status always reflects the selected repo,
        // even during a LOADING state when context hasn't been updated yet.
        val settings = DebrickedSettingsManager.getInstance()
        val repoId = settings.getRepositoryId()
        val repoName = settings.getRepositoryName()
        val repoText = when {
            repoId.isBlank() -> "Debricked"
            repoName.isBlank() -> "Repository $repoId"
            else -> "$repoName [$repoId]"
        }
        val statusText = when (state) {
            FindingsState.LOADING                  -> "Loading…"
            FindingsState.CURRENT                  -> "${findings.size} findings · ${now()}"
            FindingsState.NO_REMOTE_RESULTS        -> "No findings · ${now()}"
            FindingsState.TIMEOUT                  -> "${findings.size} findings · request timed out"
            FindingsState.STALE_DEPENDENCY_CHANGES -> "${findings.size} findings · local changes detected"
            FindingsState.STALE_COMMIT             -> "${findings.size} findings · latest branch results"
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(6, 10)
            )
            add(JBLabel(repoText).apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.WEST)
            add(JBLabel(statusText).apply { foreground = JBColor.GRAY }, BorderLayout.EAST)
        }
    }

    private fun buildContent(): JComponent = when (state) {
        FindingsState.LOADING -> buildLoadingPanel()
        FindingsState.NO_REMOTE_RESULTS -> buildEmptyPanel()
        FindingsState.TIMEOUT -> buildFindingsTree()
        else -> buildFindingsTree()
    }

    private fun buildLoadingPanel(): JPanel = buildCenteredStatePanel(
        title = "Loading vulnerabilities",
        description = "Fetching vulnerability data from Debricked…",
        footer = JProgressBar().apply {
            isIndeterminate = true
            alignmentX = Component.CENTER_ALIGNMENT
            maximumSize = Dimension(220, preferredSize.height)
        }
    )

    private fun buildEmptyPanel(): JPanel = buildCenteredStatePanel(
        title = "No vulnerabilities found",
        description = "No open-source vulnerabilities detected for this repository."
    )

    private fun buildFindingsTree(): JBScrollPane {
        val root = DefaultMutableTreeNode("root")
        val grouped = findings.groupBy { it.severity }

        for (severity in listOf(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW, Severity.UNKNOWN)) {
            val items = grouped[severity]?.sortedByDescending { it.exploitabilityScore ?: 0.0 }
            if (items.isNullOrEmpty()) continue
            val groupNode = DefaultMutableTreeNode(SeverityGroupNode(severity, items.size))
            items.forEach { groupNode.add(DefaultMutableTreeNode(FindingNode(it))) }
            root.add(groupNode)
        }

        val tree = JTree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = DebrickedTreeCellRenderer()
            border = JBUI.Borders.empty(8)
            // Expand all severity group rows
            repeat(2) { for (i in 0 until rowCount) expandRow(i) }
        }
        return JBScrollPane(tree).apply {
            border = JBUI.Borders.empty()
            viewportBorder = null
        }
    }

    private fun now(): String = SimpleDateFormat("HH:mm").format(Date())

    private fun buildCenteredStatePanel(
        title: String,
        description: String,
        footer: JComponent? = null
    ): JPanel = JPanel(GridBagLayout()).apply {
        border = JBUI.Borders.empty(12)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border()),
                JBUI.Borders.empty(20, 24)
            )
            maximumSize = Dimension(360, Int.MAX_VALUE)
            add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, 14f)
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(8))
            add(JBLabel(description).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = JBColor.GRAY
            })
            if (footer != null) {
                add(Box.createVerticalStrut(14))
                add(footer)
            }
        })
    }
}

/** ColoredTreeCellRenderer for severity group and finding nodes. */
private class DebrickedTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: javax.swing.JTree, value: Any?, selected: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean
    ) {
        val node = (value as? DefaultMutableTreeNode)?.userObject ?: return
        when (node) {
            is SeverityGroupNode -> {
                val color = SeverityStyle.colorFor(node.severity)
                val label = node.severity.name.lowercase().replaceFirstChar { it.titlecase() }
                append(label, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
                append("  (${node.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is FindingNode -> {
                val f = node.finding
                if (f.cveId != null) {
                    append(f.cveId, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                append("${f.packageName}:${f.version}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                if (f.fixedVersion != null) {
                    append(
                        " → ${f.fixedVersion}",
                        SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN,
                            JBColor(Color(0, 130, 0), Color(100, 200, 100)))
                    )
                }
                if (f.exploitabilityScore != null) {
                    append(
                        "  CVSS ${"%.1f".format(f.exploitabilityScore)}",
                        SimpleTextAttributes.GRAYED_ATTRIBUTES
                    )
                }
                append("  [${f.ecosystem.name.lowercase()}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
}
