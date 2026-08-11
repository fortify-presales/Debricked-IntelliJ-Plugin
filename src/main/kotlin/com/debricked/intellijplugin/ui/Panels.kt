package com.debricked.intellijplugin.ui

import com.debricked.intellijplugin.domain.*
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

/** "Not connected" placeholder shown when no repository/credentials are configured. */
class NotConfiguredPanel(
    private val project: Project,
    private val onRefresh: () -> Unit
) : JPanel(BorderLayout()) {
    init {
        border = JBUI.Borders.empty(20)
        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(Box.createVerticalGlue())
            add(JBLabel("Not Connected to Debricked").apply {
                font = font.deriveFont(Font.BOLD, 14f)
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(10))
            add(JBLabel("Configure your Debricked repository to see vulnerability findings.").apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = JBColor.GRAY
            })
            add(Box.createVerticalStrut(16))
            add(JButton("Open Debricked Settings").apply {
                alignmentX = Component.CENTER_ALIGNMENT
                addActionListener {
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(
                        project,
                        com.debricked.intellijplugin.settings.DebrickedSettingsConfigurable::class.java
                    )
                    onRefresh()
                }
            })
            add(Box.createVerticalGlue())
        }
        add(center, BorderLayout.CENTER)
    }
}

/**
 * Main findings panel — shows status header + either a loading spinner,
 * empty state, or a severity-grouped tree of vulnerabilities.
 */
class DebrickedFindingsPanel(
    private val findings: List<VulnerabilityFinding>,
    private val state: FindingsState,
    private val context: DebrickedScanContext?
) : JPanel(BorderLayout()) {

    init {
        add(buildHeader(), BorderLayout.NORTH)
        add(buildContent(), BorderLayout.CENTER)
    }

    private fun buildHeader(): JPanel {
        val repoText = context?.let { "${it.repositoryName} [${it.repositoryId}]" } ?: "Debricked"
        val statusText = when (state) {
            FindingsState.LOADING                  -> "Loading..."
            FindingsState.CURRENT                  -> "${findings.size} findings · ${now()}"
            FindingsState.NO_REMOTE_RESULTS        -> "No findings · ${now()}"
            FindingsState.STALE_DEPENDENCY_CHANGES -> "${findings.size} findings · local changes detected"
            FindingsState.STALE_COMMIT             -> "${findings.size} findings · latest branch results"
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(5, 8)
            )
            add(JBLabel(repoText).apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.WEST)
            add(JBLabel(statusText).apply { foreground = JBColor.GRAY }, BorderLayout.EAST)
        }
    }

    private fun buildContent(): JComponent = when (state) {
        FindingsState.LOADING -> buildLoadingPanel()
        FindingsState.NO_REMOTE_RESULTS -> buildEmptyPanel()
        else -> buildFindingsTree()
    }

    private fun buildLoadingPanel(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(Box.createVerticalGlue())
        add(JBLabel("Fetching vulnerability data from Debricked…").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = JBColor.GRAY
        })
        add(Box.createVerticalStrut(12))
        add(JProgressBar().apply {
            isIndeterminate = true
            maximumSize = Dimension(220, 4)
            alignmentX = Component.CENTER_ALIGNMENT
        })
        add(Box.createVerticalGlue())
    }

    private fun buildEmptyPanel(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(Box.createVerticalGlue())
        add(JBLabel("No vulnerabilities found").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            alignmentX = Component.CENTER_ALIGNMENT
        })
        add(Box.createVerticalStrut(8))
        add(JBLabel("No open-source vulnerabilities detected for this repository.").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            foreground = JBColor.GRAY
        })
        add(Box.createVerticalGlue())
    }

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
            // Expand all severity group rows
            repeat(2) { for (i in 0 until rowCount) expandRow(i) }
        }
        return JBScrollPane(tree)
    }

    private fun now(): String = SimpleDateFormat("HH:mm").format(Date())
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
