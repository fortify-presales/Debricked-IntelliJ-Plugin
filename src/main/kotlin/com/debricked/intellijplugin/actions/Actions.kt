package com.debricked.intellijplugin.actions

import com.debricked.intellijplugin.core.DebrickedPluginManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager

class RefreshFindingsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val pluginManager = project.getService(DebrickedPluginManager::class.java)
        ApplicationManager.getApplication().executeOnPooledThread {
            pluginManager.refreshFindings(forceRefresh = true)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

class RunLocalScanAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // TODO: Implement local CLI scan (Phase 3)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
