package com.example.azdoreviewer.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Editable preview of a PR comment before it is posted to Azure DevOps.
 * OK button = "Post comment", Cancel = abort.
 */
class PostPreviewDialog(
    project: Project,
    private val target: String,
    initialText: String
) : DialogWrapper(project) {

    private val textArea = JBTextArea(initialText).apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        rows = 16
    }

    init {
        title = "Review comment before posting"
        setOKButtonText("Post comment")
        setCancelButtonText("Cancel")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 6))
        panel.preferredSize = Dimension(620, 460)

        panel.add(JBLabel("Posting to:  $target").apply {
            border = JBUI.Borders.emptyBottom(4)
            font = font.deriveFont(Font.BOLD)
        }, BorderLayout.NORTH)

        panel.add(JBScrollPane(textArea), BorderLayout.CENTER)

        panel.add(JBLabel("You can edit the text above. Markdown is supported in Azure DevOps comments.").apply {
            border = JBUI.Borders.emptyTop(4)
            foreground = java.awt.Color.GRAY
        }, BorderLayout.SOUTH)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea

    fun getText(): String = textArea.text.trim()
}
