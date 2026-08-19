package com.debricked.intellijplugin.ui.common

import com.debricked.intellijplugin.settings.DebrickedSettingsManager
import com.debricked.intellijplugin.ui.BranchChoice
import com.debricked.intellijplugin.ui.RepositoryChoice
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

private const val REPOSITORY_SHORTLIST_LIMIT = 25

internal interface ToolWindowContextHeaderController {
    fun repositoryActionText(): String
    fun branchActionText(): String
    fun hasCredentials(): Boolean
    fun hasRepositories(): Boolean
    fun hasBranches(): Boolean
    fun isLoadingBranches(): Boolean
    fun availableRepositories(): List<RepositoryChoice>
    fun availableBranches(): List<BranchChoice>
    fun selectRepository(repository: RepositoryChoice)
    fun selectBranch(branch: BranchChoice)
    fun refreshRepositoriesFromToolbar()
    fun openSettingsFromToolbar()
}

internal class ToolWindowContextHeader(
    private val controller: ToolWindowContextHeaderController
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
    private val controller: ToolWindowContextHeaderController
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
        val repositories = orderedRepositories(controller.availableRepositories())
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
        val ordered = orderedRepositories(repositories)
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(ordered)
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

    private fun orderedRepositories(repositories: List<RepositoryChoice>): List<RepositoryChoice> {
        if (repositories.isEmpty()) return emptyList()
        val byId = repositories.associateBy { it.id }
        val recent = DebrickedSettingsManager.getInstance().getRecentRepositoryIds()
            .mapNotNull { byId[it] }
        val recentIds = recent.asSequence().map { it.id }.toSet()
        val alphabeticalRemainder = repositories.asSequence()
            .filter { it.id !in recentIds }
            .sortedBy { it.name.ifBlank { it.id }.lowercase() }
            .toList()
        return recent + alphabeticalRemainder
    }
}

private class BranchSelectionAction(
    private val controller: ToolWindowContextHeaderController
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

