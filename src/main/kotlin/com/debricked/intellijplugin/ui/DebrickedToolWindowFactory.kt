package com.debricked.intellijplugin.ui

import com.debricked.intellijplugin.core.DebrickedPluginManager
import com.debricked.intellijplugin.domain.*
import com.debricked.intellijplugin.settings.DebrickedCredentialStore
import com.debricked.intellijplugin.settings.DebrickedSettingsConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class DebrickedToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DebrickedToolWindowContent(project, toolWindow)
        val content = ContentFactory.SERVICE.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

class DebrickedToolWindowContent(
    private val project: Project,
    private val toolWindow: ToolWindow
) : JPanel(BorderLayout()), DebrickedPluginManager.FindingsUpdateListener {

    private val pluginManager = project.getService(DebrickedPluginManager::class.java)
    private val contentWrapper = JPanel(BorderLayout())

    init {
        // Warm credential cache from PasswordSafe while on the EDT
        DebrickedCredentialStore.loadFromStorage()
        add(buildToolbar(), BorderLayout.NORTH)
        add(contentWrapper, BorderLayout.CENTER)
        pluginManager.addListener(this)
        refreshUI(pluginManager.getCurrentFindings(), pluginManager.getFindingsState())
        if (pluginManager.isConfigured()) {
            pluginManager.refreshFindings()
        }
    }

    private fun buildToolbar(): JComponent {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Refresh Findings", "Refresh Debricked vulnerability findings", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    pluginManager.refreshFindings()
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = pluginManager.getFindingsState() != FindingsState.LOADING
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            })
            addSeparator()
            add(object : AnAction("Debricked Settings", "Open Debricked Settings", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, DebrickedSettingsConfigurable::class.java)
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            })
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("Debricked.Toolbar", actionGroup, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    override fun onFindingsUpdated(findings: List<VulnerabilityFinding>, state: FindingsState) {
        // ModalityState.any() is required so this fires even while Settings dialog is open
        ApplicationManager.getApplication().invokeLater({
            refreshUI(findings, state)
        }, ModalityState.any())
    }

    private fun refreshUI(findings: List<VulnerabilityFinding>, state: FindingsState) {
        contentWrapper.removeAll()
        if (!pluginManager.isConfigured()) {
            contentWrapper.add(NotConfiguredPanel(project) { pluginManager.refreshFindings() }, BorderLayout.CENTER)
        } else {
            contentWrapper.add(
                DebrickedFindingsPanel(findings, state, pluginManager.getCurrentScanContext()),
                BorderLayout.CENTER
            )
        }
        contentWrapper.revalidate()
        contentWrapper.repaint()
    }
}
