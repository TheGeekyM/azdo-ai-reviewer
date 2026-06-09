package com.example.azdoreviewer.ui.panels

import com.example.azdoreviewer.application.PrService
import com.example.azdoreviewer.domain.PrStatus
import com.example.azdoreviewer.domain.PullRequest
import com.example.azdoreviewer.settings.AzdoSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

class PrListPanel(private val project: Project, private val toolWindow: ToolWindow) {

    private val prService = service<PrService>()
    private val settings  = AzdoSettings.getInstance()

    private var allPrs: List<PullRequest> = emptyList()

    private val listModel = DefaultListModel<PullRequest>()
    private val prList    = JBList(listModel)
    private val statusLabel = JBLabel("Loading pull requests…")
    private val panel     = JPanel(BorderLayout())

    // Filters (devops-style facets)
    private val creatorFilter   = ComboBox<String>()
    private val reviewerFilter  = ComboBox<String>()
    private val mergeFilter     = ComboBox(arrayOf("All", "Can merge", "Conflicts / blocked"))
    private val ANY = "Anyone"

    init {
        prList.cellRenderer = PrCardRenderer()
        prList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        prList.fixedCellHeight = -1

        prList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) prList.selectedValue?.let { openPrDetail(it) }
            }
        })
        prList.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER)
                    prList.selectedValue?.let { openPrDetail(it) }
            }
        })

        val north = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(buildToolbar())
            add(buildFilterBar())
        }

        panel.add(north, BorderLayout.NORTH)
        panel.add(JBScrollPane(prList), BorderLayout.CENTER)
        panel.add(statusLabel.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.SOUTH)

        loadPrs(false)
    }

    fun getComponent(): JComponent = panel

    private fun buildToolbar(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        toolbar.add(JButton("Refresh", AllIcons.Actions.Refresh).apply {
            addActionListener { loadPrs(true) }
        })
        toolbar.add(JButton("Settings", AllIcons.General.Settings).apply {
            addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, "Azure DevOps AI Reviewer") }
        })
        return toolbar
    }

    private fun buildFilterBar(): JComponent {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        bar.add(JBLabel("Creator:"))
        bar.add(creatorFilter)
        bar.add(JBLabel("Reviewer:"))
        bar.add(reviewerFilter)
        bar.add(JBLabel("Merge:"))
        bar.add(mergeFilter)

        val apply = { applyFilters() }
        creatorFilter.addActionListener { apply() }
        reviewerFilter.addActionListener { apply() }
        mergeFilter.addActionListener { apply() }
        return bar
    }

    private fun loadPrs(forceRefresh: Boolean) {
        if (!settings.isConfigured()) {
            statusLabel.text = "Not configured — click Settings."
            return
        }
        statusLabel.text = "Loading…"
        prList.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { prService.getAssignedPrs(forceRefresh) }
            ApplicationManager.getApplication().invokeLater {
                prList.isEnabled = true
                result.fold(
                    onSuccess = { prs ->
                        allPrs = prs
                        rebuildFilterOptions(prs)
                        applyFilters()
                        prefetchAvatars(prs)
                    },
                    onFailure = { e -> statusLabel.text = "Error: ${e.message}" }
                )
            }
        }
    }

    private fun rebuildFilterOptions(prs: List<PullRequest>) {
        val creators  = (listOf(ANY) + prs.map { it.authorDisplayName }.distinct().sorted())
        val reviewers = (listOf(ANY) + prs.flatMap { it.reviewers.map { r -> r.displayName } }.distinct().sorted())

        // Preserve selection where possible
        val prevCreator  = creatorFilter.selectedItem as? String ?: ANY
        val prevReviewer = reviewerFilter.selectedItem as? String ?: ANY

        creatorFilter.model  = DefaultComboBoxModel(creators.toTypedArray())
        reviewerFilter.model = DefaultComboBoxModel(reviewers.toTypedArray())
        creatorFilter.selectedItem  = if (creators.contains(prevCreator)) prevCreator else ANY
        reviewerFilter.selectedItem = if (reviewers.contains(prevReviewer)) prevReviewer else ANY
    }

    private fun applyFilters() {
        val creator  = creatorFilter.selectedItem as? String ?: ANY
        val reviewer = reviewerFilter.selectedItem as? String ?: ANY
        val merge    = mergeFilter.selectedItem as? String ?: "All"

        val filtered = allPrs.filter { pr ->
            (creator == ANY || pr.authorDisplayName == creator) &&
            (reviewer == ANY || pr.reviewers.any { it.displayName == reviewer }) &&
            when (merge) {
                "Can merge"           -> pr.canMerge
                "Conflicts / blocked" -> !pr.canMerge
                else                  -> true
            }
        }

        listModel.clear()
        filtered.forEach { listModel.addElement(it) }
        statusLabel.text = "${filtered.size} of ${allPrs.size} pull request(s)"
    }

    private fun prefetchAvatars(prs: List<PullRequest>) {
        ApplicationManager.getApplication().executeOnPooledThread {
            prs.map { it.authorImageUrl }.filter { it.isNotBlank() }.distinct()
                .forEach { runCatching { prService.getAvatar(it) } }
            ApplicationManager.getApplication().invokeLater { prList.repaint() }
        }
    }

    private fun openPrDetail(pr: PullRequest) {
        val cm = toolWindow.contentManager
        val tabTitle = "PR #${pr.id}"
        cm.findContent(tabTitle)?.let { cm.setSelectedContent(it); return }

        val detailPanel = PrDetailPanel(project, pr)
        val content = cm.factory.createContent(detailPanel.getComponent(), tabTitle, true).apply {
            isCloseable = true
            description = pr.title
        }
        cm.addContent(content)
        cm.setSelectedContent(content)
    }

    /** Card renderer: small left avatar (with ✗ overlay if not mergeable), title, badge, branches. */
    private inner class PrCardRenderer : ListCellRenderer<PullRequest> {
        override fun getListCellRendererComponent(
            list: JList<out PullRequest>, pr: PullRequest, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val bg = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            val fg = if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()

            val card = JPanel(BorderLayout(8, 2)).apply {
                background = bg; isOpaque = true
                border = JBUI.Borders.empty(6, 8)
            }

            // Small avatar on the LEFT (24px) with merge-blocked ✗ overlay
            card.add(JLabel(avatarIcon(pr)).apply {
                verticalAlignment = SwingConstants.TOP
                border = JBUI.Borders.emptyTop(2)
            }, BorderLayout.WEST)

            val center = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }

            val titleRow = JPanel(BorderLayout(6, 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
            titleRow.add(JLabel("#${pr.id}  ${pr.title}").apply {
                font = font.deriveFont(Font.BOLD); foreground = fg
            }, BorderLayout.CENTER)
            titleRow.add(statusBadge(pr), BorderLayout.EAST)

            center.add(titleRow)
            center.add(Box.createVerticalStrut(2))
            center.add(JLabel("${pr.authorDisplayName}  ·  ${pr.repositoryName}").apply {
                foreground = if (isSelected) fg else Color.GRAY
                font = font.deriveFont(11f); alignmentX = Component.LEFT_ALIGNMENT
            })
            center.add(JLabel("${pr.sourceBranch}  →  ${pr.targetBranch}").apply {
                foreground = if (isSelected) fg else Color(0x808080)
                font = font.deriveFont(11f); alignmentX = Component.LEFT_ALIGNMENT
            })
            card.add(center, BorderLayout.CENTER)

            return JPanel(BorderLayout()).apply {
                isOpaque = true; background = bg
                add(card, BorderLayout.CENTER)
                add(JSeparator().apply { foreground = Color(0x3C3F41) }, BorderLayout.SOUTH)
            }
        }

        private fun avatarIcon(pr: PullRequest): Icon {
            val bytes = runCatching { prService.getAvatar(pr.authorImageUrl) }.getOrNull()
            val img = bytes?.let { runCatching { ImageIcon(it).image }.getOrNull() }
            return CircleAvatarIcon(img, 24, initials(pr.authorDisplayName), blocked = !pr.canMerge)
        }

        private fun initials(name: String) =
            name.split(" ").filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.first().uppercase() }.ifBlank { "?" }

        private fun statusBadge(pr: PullRequest): JComponent {
            val (text, color) = when {
                !pr.canMerge && pr.mergeStatus.equals("conflicts", true) -> "CONFLICTS" to Color(0xD32F2F)
                !pr.canMerge && pr.mergeStatus.isNotBlank()              -> pr.mergeStatus.uppercase() to Color(0xF57C00)
                pr.status == PrStatus.ACTIVE                             -> "ACTIVE"    to Color(0x4CAF50)
                pr.status == PrStatus.COMPLETED                         -> "COMPLETED" to Color(0x2196F3)
                else                                                    -> "—"         to Color(0x9E9E9E)
            }
            return JLabel(text).apply {
                font = font.deriveFont(Font.BOLD, 10f)
                foreground = Color.WHITE; isOpaque = true; background = color
                border = JBUI.Borders.empty(2, 6)
            }
        }
    }

    /** Circular avatar with optional red ✗ badge overlay (for non-mergeable PRs). */
    private class CircleAvatarIcon(
        private val image: Image?,
        private val size: Int,
        private val initials: String,
        private val blocked: Boolean
    ) : Icon {
        override fun getIconWidth() = size
        override fun getIconHeight() = size
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val clip = java.awt.geom.Ellipse2D.Float(x.toFloat(), y.toFloat(), size.toFloat(), size.toFloat())
            g2.clip = clip
            if (image != null) {
                g2.drawImage(image, x, y, size, size, null)
            } else {
                g2.color = Color(0x5C6BC0); g2.fillOval(x, y, size, size)
                g2.color = Color.WHITE
                g2.font = g2.font.deriveFont(Font.BOLD, 10f)
                val fm = g2.fontMetrics
                g2.drawString(initials, x + (size - fm.stringWidth(initials)) / 2, y + (size - fm.height) / 2 + fm.ascent)
            }
            g2.clip = null

            // Red ✗ badge bottom-right if the PR can't be merged
            if (blocked) {
                val b = 11
                val bx = x + size - b; val by = y + size - b
                g2.color = Color(0xD32F2F); g2.fillOval(bx, by, b, b)
                g2.color = Color.WHITE
                g2.stroke = BasicStroke(1.6f)
                g2.drawLine(bx + 3, by + 3, bx + b - 3, by + b - 3)
                g2.drawLine(bx + b - 3, by + 3, bx + 3, by + b - 3)
            }
            g2.dispose()
        }
    }
}
