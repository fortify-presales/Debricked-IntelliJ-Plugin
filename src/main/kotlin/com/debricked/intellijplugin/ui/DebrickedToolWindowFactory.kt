package com.debricked.intellijplugin.ui

import com.debricked.intellijplugin.core.DebrickedPluginManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class DebrickedToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        DebrickedTabbedToolWindowContent(project, toolWindow)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
