package com.example.azdoreviewer.ui

import com.example.azdoreviewer.ui.panels.PrListPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class PrToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PrListPanel(project, toolWindow)
        val content = toolWindow.contentManager.factory
            .createContent(panel.getComponent(), "Pull Requests", false)
        // The main list tab is pinned; PR detail tabs (created in PrListPanel) are closeable.
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
