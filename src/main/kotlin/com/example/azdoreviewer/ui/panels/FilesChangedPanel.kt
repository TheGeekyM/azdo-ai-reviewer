package com.example.azdoreviewer.ui.panels

import com.example.azdoreviewer.application.PrService
import com.example.azdoreviewer.domain.ChangeType
import com.example.azdoreviewer.domain.FileDiff
import com.example.azdoreviewer.domain.PullRequest
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*

class FilesChangedPanel(private val project: Project, private val pr: PullRequest) {

    private val prService   = service<PrService>()
    private val fileModel   = DefaultListModel<FileDiff>()
    private val fileList    = JBList(fileModel)
    private val statusLabel = JBLabel("Loading files…")
    private val panel       = JPanel(BorderLayout())

    init {
        fileList.cellRenderer = FileCellRenderer()
        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        // Double-click (or Enter) opens Rider's native diff viewer
        fileList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) fileList.selectedValue?.let { openNativeDiff(it) }
            }
        })
        fileList.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER)
                    fileList.selectedValue?.let { openNativeDiff(it) }
            }
        })

        val hint = JBLabel("Double-click a file to open it in Rider's diff viewer").apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            foreground = java.awt.Color.GRAY
        }

        val top = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(hint, BorderLayout.SOUTH)
        }

        panel.add(top, BorderLayout.NORTH)
        panel.add(JBScrollPane(fileList), BorderLayout.CENTER)

        loadFiles()
    }

    fun getComponent(): JComponent = panel

    private fun loadFiles() {
        statusLabel.text = "Loading changed files…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { prService.getPrDiffs(pr.id) }
            ApplicationManager.getApplication().invokeLater {
                result.fold(
                    onSuccess = { diffs ->
                        fileModel.clear()
                        diffs.forEach { fileModel.addElement(it) }
                        statusLabel.text = "${diffs.size} file(s) changed"
                    },
                    onFailure = { e -> statusLabel.text = "Error: ${e.message}" }
                )
            }
        }
    }

    /** Opens the file in Rider's built-in side-by-side diff viewer. */
    private fun openNativeDiff(diff: FileDiff) {
        val fileName = diff.path.substringAfterLast('/')
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        val factory  = DiffContentFactory.getInstance()

        val left  = factory.create(project, diff.oldContent, fileType)   // before
        val right = factory.create(project, diff.newContent, fileType)   // after

        val request = SimpleDiffRequest(
            "PR #${pr.id}: $fileName",
            left, right,
            "Base (${diff.changeType.name.lowercase()})",
            "PR changes"
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private class FileCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is FileDiff) {
                val (icon, color) = when (value.changeType) {
                    ChangeType.ADD    -> "A" to java.awt.Color(0x4CAF50)
                    ChangeType.DELETE -> "D" to java.awt.Color(0xF44336)
                    ChangeType.RENAME -> "R" to java.awt.Color(0x2196F3)
                    else              -> "M" to java.awt.Color(0xFF9800)
                }
                val dir  = value.path.substringBeforeLast('/', "")
                val name = value.path.substringAfterLast('/')
                text = "<html><b style='color:#${Integer.toHexString(color.rgb).substring(2)}'>$icon</b> " +
                       "$name <span style='color:gray'>$dir</span></html>"
                toolTipText = value.path
                border = BorderFactory.createEmptyBorder(3, 6, 3, 6)
            }
            return this
        }
    }
}
