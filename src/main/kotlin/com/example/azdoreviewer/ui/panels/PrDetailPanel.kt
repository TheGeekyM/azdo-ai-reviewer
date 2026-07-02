package com.example.azdoreviewer.ui.panels

import com.example.azdoreviewer.domain.PullRequest
import com.example.azdoreviewer.ui.GitCheckoutHelper
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class PrDetailPanel(private val project: Project, private val pr: PullRequest) {

    private val tabs = JBTabbedPane()

    init {
        tabs.addTab("Details",      buildDetailsTab())
        tabs.addTab("Files Changed", FilesChangedPanel(project, pr).getComponent())
        tabs.addTab("AI Review",    ReviewResultPanel(project, pr).getComponent())
        tabs.addTab("Work Item",    WorkItemPanel(project, pr).getComponent())
    }

    fun getComponent(): JComponent = tabs

    private fun buildDetailsTab(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 8, 4, 8)
            anchor = GridBagConstraints.WEST
        }

        fun iconButton(icon: javax.swing.Icon, tip: String, action: () -> Unit) =
            JButton(icon).apply {
                toolTipText = tip
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                margin = Insets(0, 0, 0, 0)
                addActionListener { action() }
            }

        fun addRow(label: String, value: String, row: Int, copyable: Boolean = false, checkoutable: Boolean = false) {
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
            panel.add(JLabel("<html><b>$label</b></html>"), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            if (copyable || checkoutable) {
                val cell = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
                cell.add(JLabel(value))
                if (copyable) cell.add(iconButton(AllIcons.Actions.Copy, "Copy '$value'") {
                    CopyPasteManager.getInstance().setContents(StringSelection(value))
                })
                if (checkoutable) cell.add(iconButton(AllIcons.Vcs.Branch, "Checkout '$value'") {
                    GitCheckoutHelper.checkout(project, value)
                })
                panel.add(cell, gbc)
            } else {
                panel.add(JLabel(value), gbc)
            }
        }

        addRow("PR ID:",      "#${pr.id}",              0)
        addRow("Title:",      pr.title,                 1)
        addRow("Author:",     pr.authorDisplayName,     2)
        addRow("Status:",     pr.status.name,           3)
        addRow("Source:",     pr.sourceBranch,          4, copyable = true, checkoutable = true)
        addRow("Target:",     pr.targetBranch,          5, copyable = true)
        addRow("Created:",    pr.createdAt,             6)

        // Description
        gbc.gridx = 0; gbc.gridy = 7; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        panel.add(JLabel("<html><b>Description:</b></html>"), gbc)

        val descArea = JBTextArea(pr.description.ifBlank { "(no description)" }).apply {
            isEditable  = false
            lineWrap    = true
            wrapStyleWord = true
            rows        = 6
        }
        gbc.gridx = 0; gbc.gridy = 8; gbc.gridwidth = 2
        gbc.weightx = 1.0; gbc.weighty = 0.5; gbc.fill = GridBagConstraints.BOTH
        panel.add(JBScrollPane(descArea), gbc)

        // Filler
        gbc.gridx = 0; gbc.gridy = 9; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.VERTICAL
        panel.add(JPanel(), gbc)

        val wrapper = JPanel(BorderLayout())
        wrapper.add(panel, BorderLayout.CENTER)
        return wrapper
    }
}
