package com.debricked.intellijplugin.ui

import com.debricked.intellijplugin.core.DebrickedPluginManager
import javax.swing.JComponent

data class TabContext(
    val repositoryId: String,
    val branchId: String?,
    val branchName: String?
)

interface TabProvider {
    val tabTitle: String
    fun getPanel(): JComponent
    fun loadData(context: TabContext, forceRefresh: Boolean = false)
    fun invalidate(context: TabContext)
}

class PassiveTabProvider(
    override val tabTitle: String,
    private val panel: JComponent
) : TabProvider {
    override fun getPanel(): JComponent = panel
    override fun loadData(context: TabContext, forceRefresh: Boolean) {}
    override fun invalidate(context: TabContext) {}
}

class VulnerabilitiesTabProvider(
    private val panel: JComponent,
    private val pluginManager: DebrickedPluginManager
) : TabProvider {
    override val tabTitle: String = "Vulnerabilities"

    override fun getPanel(): JComponent = panel

    override fun loadData(context: TabContext, forceRefresh: Boolean) {
        if (context.repositoryId.isBlank()) return
        pluginManager.refreshFindings(forceRefresh = forceRefresh)
    }

    override fun invalidate(context: TabContext) {
        if (context.repositoryId.isBlank()) return
        pluginManager.invalidateFindingsCache(context.repositoryId, context.branchId)
    }
}
