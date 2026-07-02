package com.example.azdoreviewer.ui.panels

import com.example.azdoreviewer.application.CommentService
import com.example.azdoreviewer.application.PrService
import com.example.azdoreviewer.application.ReviewService
import com.example.azdoreviewer.domain.PullRequest
import com.example.azdoreviewer.domain.RequirementFinding
import com.example.azdoreviewer.domain.ReviewCategory
import com.example.azdoreviewer.domain.ReviewComment
import com.example.azdoreviewer.domain.Severity
import com.example.azdoreviewer.domain.WorkItem
import com.example.azdoreviewer.domain.WorkItemValidation
import com.example.azdoreviewer.ui.EditorNavigator
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.*

/** Lets the user check a PR's changes against a work item's description/repro steps/acceptance criteria. */
class WorkItemPanel(private val project: Project, private val pr: PullRequest) {

    private val prService      = service<PrService>()
    private val reviewService  = service<ReviewService>()
    private val commentService = service<CommentService>()

    private var currentWorkItem: WorkItem? = null
    // (finding, fromNotMetSection)
    private var selected: Pair<RequirementFinding, Boolean>? = null

    private val idField     = JBTextField().apply { columns = 10 }
    private val validateBtn = JButton("Validate", AllIcons.Actions.Execute)
    private val statusLabel = JBLabel("Enter a work item # and click Validate.")
    private val progress    = JProgressBar().apply { isIndeterminate = true; isVisible = false }

    private val metListModel    = DefaultListModel<RequirementFinding>()
    private val notMetListModel = DefaultListModel<RequirementFinding>()
    private val metList    = JBList(metListModel).apply { cellRenderer = FindingCellRenderer() }
    private val notMetList = JBList(notMetListModel).apply { cellRenderer = FindingCellRenderer() }
    private val metHeader    = sectionHeader("✓ Met", Color(0x388E3C))
    private val notMetHeader = sectionHeader("✗ Not Met", Color(0xD32F2F))

    private val workItemInfo = JBTextArea().apply {
        isEditable = false; isFocusable = false
        lineWrap = true; wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        background = UIUtil.getPanelBackground()
    }
    private val verdictLabel  = JBLabel().apply { font = font.deriveFont(Font.BOLD, 14f) }
    private val summaryArea   = JBTextArea().apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        background = UIUtil.getPanelBackground()
        font = font.deriveFont(13f)
    }

    // Plain styled text pane — NO HTML/emoji rendering (freezes the EDT on Linux, see CLAUDE.md).
    private val detailPane = JTextPane().apply {
        isEditable = false
        background = UIUtil.getPanelBackground()
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        text = "Run a validation, then select a requirement above to see details."
    }

    private lateinit var openCodeBtn: JButton
    private lateinit var postCommentBtn: JButton

    private val resultsContent = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val resultsPlaceholder = JBLabel("Run a validation to see the report here.", SwingConstants.CENTER)

    // ── Loading animation ─────────────────────────────────────────────────────
    private val loadingMessages = listOf(
        "Fetching work item details",
        "Reading acceptance criteria",
        "Comparing against the PR's changes",
        "Checking requirement coverage",
        "Finalizing the validation report"
    )
    private var loadingTimer: Timer? = null
    private var loadingTick = 0

    private val panel = JPanel(BorderLayout())

    init {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JBLabel("Work item #:"))
            add(idField)
            add(validateBtn)
        }
        validateBtn.addActionListener { validate() }
        idField.addActionListener { validate() }

        val top = JPanel(BorderLayout()).apply {
            add(bar, BorderLayout.NORTH)
            add(progress, BorderLayout.CENTER)
        }

        openCodeBtn = JButton("Open Code", AllIcons.Actions.EditSource).apply {
            isEnabled = false
            addActionListener { selected?.first?.let { openCode(it) } }
        }
        postCommentBtn = JButton("Post Comment", AllIcons.Actions.Commit).apply {
            isEnabled = false
            addActionListener { selected?.first?.let { postComment(it) } }
        }
        val actionBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 3)).apply {
            add(openCodeBtn); add(postCommentBtn)
        }

        metList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        notMetList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        wireList(metList, metListModel, fromNotMet = false)
        wireList(notMetList, notMetListModel, fromNotMet = true)

        showPlaceholder()

        val detailArea = JPanel(BorderLayout()).apply {
            add(actionBar, BorderLayout.NORTH)
            add(JBScrollPane(detailPane), BorderLayout.CENTER)
        }

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(resultsContent), detailArea).apply {
            resizeWeight = 0.62
            dividerLocation = 320
            (bottomComponent as JComponent).minimumSize = Dimension(0, 140)
        }

        panel.add(top, BorderLayout.NORTH)
        panel.add(split, BorderLayout.CENTER)
        panel.add(statusLabel.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.SOUTH)

        prefillLinkedWorkItem()
    }

    fun getComponent(): JComponent = panel

    private fun sectionHeader(text: String, color: Color) = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD, 13f)
        foreground = color
        border = JBUI.Borders.empty(8, 4, 4, 4)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun wireList(list: JBList<RequirementFinding>, model: DefaultListModel<RequirementFinding>, fromNotMet: Boolean) {
        list.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val v = list.selectedValue ?: return@addListSelectionListener
            (if (fromNotMet) metList else notMetList).clearSelection()
            selectFinding(v, fromNotMet)
        }
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val idx = list.locationToIndex(e.point)
                if (idx < 0) return
                list.selectedIndex = idx
                if (e.clickCount == 2) model.getElementAt(idx).let { openCode(it) }
            }
        })
    }

    private fun selectFinding(f: RequirementFinding, fromNotMet: Boolean) {
        selected = f to fromNotMet
        openCodeBtn.isEnabled = f.file.isNotBlank()
        postCommentBtn.isEnabled = fromNotMet
        showDetail(f)
    }

    private fun prefillLinkedWorkItem() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val linkedId = runCatching { prService.getLinkedWorkItemId(pr.id) }.getOrNull()
            if (linkedId != null) {
                ApplicationManager.getApplication().invokeLater {
                    if (idField.text.isBlank()) {
                        idField.text = linkedId.toString()
                        statusLabel.text = "Linked work item #$linkedId found — click Validate."
                    }
                }
            }
        }
    }

    private fun validate() {
        val id = idField.text.trim().removePrefix("#").toIntOrNull()
        if (id == null) {
            statusLabel.text = "Enter a valid work item number."
            return
        }

        progress.isVisible = true
        validateBtn.isEnabled = false
        currentWorkItem = null
        selected = null
        openCodeBtn.isEnabled = false
        postCommentBtn.isEnabled = false
        detailPane.text = ""
        startLoadingAnimation()

        ApplicationManager.getApplication().executeOnPooledThread {
            val workItemResult = runCatching { prService.getWorkItem(id) }
            val workItem = workItemResult.getOrNull()
            if (workItem == null) {
                ApplicationManager.getApplication().invokeLater {
                    stopLoadingAnimation()
                    progress.isVisible = false
                    validateBtn.isEnabled = true
                    showPlaceholder()
                    statusLabel.text = "✗ Couldn't load work item #$id: ${workItemResult.exceptionOrNull()?.message}"
                }
                return@executeOnPooledThread
            }

            val result = runCatching { reviewService.validateAgainstWorkItem(pr.id, workItem) }

            ApplicationManager.getApplication().invokeLater {
                stopLoadingAnimation()
                progress.isVisible = false
                validateBtn.isEnabled = true
                result.fold(
                    onSuccess = { validation ->
                        currentWorkItem = workItem
                        renderReport(workItem, validation)
                        statusLabel.text = "✓ Validation complete — ${validation.verdict}"
                    },
                    onFailure = { e ->
                        showPlaceholder()
                        statusLabel.text = "✗ Validation failed: ${e.message}"
                    }
                )
            }
        }
    }

    private fun startLoadingAnimation() {
        loadingTick = 0
        renderLoadingFrame()
        loadingTimer = Timer(500) { renderLoadingFrame() }.apply { start() }
    }

    private fun stopLoadingAnimation() {
        loadingTimer?.stop()
        loadingTimer = null
    }

    private fun renderLoadingFrame() {
        val msgIndex = (loadingTick / 4) % loadingMessages.size
        val dots = ".".repeat(loadingTick % 4)
        val message = loadingMessages[msgIndex]
        loadingTick++
        statusLabel.text = "$message$dots"

        resultsContent.removeAll()
        resultsContent.add(JBLabel("$message$dots", SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.PLAIN, 14f)
            alignmentX = Component.CENTER_ALIGNMENT
            border = JBUI.Borders.empty(40, 10)
        })
        resultsContent.revalidate(); resultsContent.repaint()
    }

    private fun showPlaceholder() {
        resultsContent.removeAll()
        resultsContent.add(resultsPlaceholder.apply { alignmentX = Component.CENTER_ALIGNMENT; border = JBUI.Borders.empty(40, 10) })
        resultsContent.revalidate(); resultsContent.repaint()
        detailPane.text = "Run a validation, then select a requirement above to see details."
    }

    private fun renderReport(workItem: WorkItem, validation: WorkItemValidation) {
        workItemInfo.text = buildString {
            append("#${workItem.id}  ${workItem.type}")
            if (workItem.state.isNotBlank()) append(" · ${workItem.state}")
            append("  —  ${workItem.title}\n")
            if (workItem.description.isNotBlank()) append("\n${workItem.description}\n")
            if (workItem.reproSteps.isNotBlank()) append("\nRepro steps:\n${workItem.reproSteps}\n")
            if (workItem.acceptanceCriteria.isNotBlank()) append("\nAcceptance criteria:\n${workItem.acceptanceCriteria}\n")
        }.trim()

        verdictLabel.text = verdictIcon(validation.verdict) + " " + validation.verdict
        verdictLabel.foreground = verdictColor(validation.verdict)
        summaryArea.text = validation.summary

        val met = validation.findings.filter { it.isMet }
        val notMet = validation.findings.filterNot { it.isMet }
        metListModel.clear(); met.forEach { metListModel.addElement(it) }
        notMetListModel.clear(); notMet.forEach { notMetListModel.addElement(it) }
        metHeader.text = "✓ Met (${met.size})"
        notMetHeader.text = "✗ Not Met (${notMet.size})"

        resultsContent.removeAll()
        resultsContent.add(capsuleHeight(JBScrollPane(workItemInfo), 110))
        resultsContent.add(Box.createVerticalStrut(6))
        resultsContent.add(verdictLabel.apply { alignmentX = Component.LEFT_ALIGNMENT; border = JBUI.Borders.empty(6, 4) })
        resultsContent.add(summaryArea.apply { alignmentX = Component.LEFT_ALIGNMENT })
        resultsContent.add(metHeader)
        resultsContent.add(capsuleHeight(JBScrollPane(metList), 140))
        resultsContent.add(notMetHeader)
        resultsContent.add(capsuleHeight(JBScrollPane(notMetList), 180))
        resultsContent.revalidate(); resultsContent.repaint()

        detailPane.text = "Select a requirement above to see the full explanation."
    }

    /** Caps a scrollable component's height while letting it stretch to the container's width (BoxLayout idiom). */
    private fun capsuleHeight(c: JComponent, height: Int): JComponent = c.apply {
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, height)
        preferredSize = Dimension(10, height)
    }

    private fun showDetail(f: RequirementFinding) {
        val doc = javax.swing.text.DefaultStyledDocument()
        fun style(color: Color, bold: Boolean, size: Int = 13): javax.swing.text.SimpleAttributeSet {
            val a = javax.swing.text.SimpleAttributeSet()
            javax.swing.text.StyleConstants.setForeground(a, color)
            javax.swing.text.StyleConstants.setBold(a, bold)
            javax.swing.text.StyleConstants.setFontSize(a, size)
            javax.swing.text.StyleConstants.setFontFamily(a, Font.SANS_SERIF)
            return a
        }
        fun add(text: String, attrs: javax.swing.text.SimpleAttributeSet) = doc.insertString(doc.length, text, attrs)

        add("[${f.status}]\n", style(statusColor(f.status), true, 14))
        add("${f.requirement}\n\n", style(UIUtil.getLabelForeground(), true, 13))
        add(f.explanation.trim() + "\n", style(UIUtil.getLabelForeground(), false, 13))
        if (f.file.isNotBlank()) {
            add("\n${f.file.substringAfterLast('/')}${if (f.line > 0) " : line ${f.line}" else ""}\n", style(Color(0x888888), true, 12))
        }
        if (!f.isMet && f.friendlyComment.isNotBlank()) {
            add("\nSUGGESTED COMMENT\n", style(Color(0x6897BB), true, 12))
            add(f.friendlyComment.trim() + "\n", style(UIUtil.getLabelForeground(), false, 13))
        }

        detailPane.document = doc
        detailPane.caretPosition = 0
    }

    private fun openCode(f: RequirementFinding) {
        if (f.file.isBlank()) { statusLabel.text = "No file/line identified for this requirement."; return }
        EditorNavigator.open(project, f.file, f.line.coerceAtLeast(1)) { msg ->
            ApplicationManager.getApplication().invokeLater { statusLabel.text = msg }
        }
    }

    private fun postComment(f: RequirementFinding) {
        val workItem = currentWorkItem
        val comment = ReviewComment(
            file            = f.file,
            line            = f.line.coerceAtLeast(1),
            endLine         = f.line.coerceAtLeast(1),
            severity        = Severity.MEDIUM,
            category        = ReviewCategory.MISSING_TESTS,
            comment         = f.explanation,
            friendlyComment = f.friendlyComment.ifBlank { f.explanation }
        )

        val target = "PR #${pr.id}" + (workItem?.let { " → work item #${it.id}" } ?: "") +
            (if (f.file.isNotBlank()) "  →  ${f.file.substringAfterLast('/')} : ${f.line}" else "")

        val dialog = PostPreviewDialog(project, target, commentService.previewComment(comment))
        if (!dialog.showAndGet()) return
        val edited = dialog.getText()
        if (edited.isBlank()) return

        progress.isVisible = true
        statusLabel.text = "Posting comment…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val r = runCatching {
                if (f.file.isNotBlank()) commentService.postLineComment(pr.id, comment, edited)
                else commentService.postGeneralComment(pr.id, edited)
            }
            ApplicationManager.getApplication().invokeLater {
                progress.isVisible = false
                statusLabel.text = r.fold(
                    { "✓ Comment posted" },
                    { "✗ Failed: ${it.message}" }
                )
            }
        }
    }

    private fun verdictColor(verdict: String) = when {
        verdict.contains("Does not", ignoreCase = true)  -> Color(0xD32F2F)
        verdict.contains("Partially", ignoreCase = true) -> Color(0xF57C00)
        verdict.contains("Meets", ignoreCase = true)     -> Color(0x388E3C)
        else                                              -> Color(0x1976D2)
    }

    private fun verdictIcon(verdict: String) = when {
        verdict.contains("Does not", ignoreCase = true)  -> "✗"
        verdict.contains("Partially", ignoreCase = true) -> "⚠"
        verdict.contains("Meets", ignoreCase = true)     -> "✓"
        else                                              -> "?"
    }

    private fun statusColor(status: String) = when (status.lowercase()) {
        "met"     -> Color(0x388E3C)
        "not met" -> Color(0xD32F2F)
        "partial" -> Color(0xF57C00)
        else      -> Color(0x1976D2)
    }

    private fun statusLetter(status: String) = when (status.lowercase()) {
        "met"     -> "✓"
        "not met" -> "✗"
        "partial" -> "~"
        else      -> "?"
    }

    /** One requirement per row: status badge + paraphrase + file:line (if known). */
    private inner class FindingCellRenderer : ListCellRenderer<RequirementFinding> {
        override fun getListCellRendererComponent(
            list: JList<out RequirementFinding>, f: RequirementFinding, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val bg = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            val fg = if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()

            val card = JPanel(BorderLayout(10, 0)).apply {
                background = bg; isOpaque = true
                border = JBUI.Borders.empty(6, 10)
            }
            card.add(JLabel(BadgeIcon(statusColor(f.status), statusLetter(f.status), 22)), BorderLayout.WEST)

            val center = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
            center.add(JLabel(f.requirement.ifBlank { f.explanation }.take(100)).apply {
                foreground = fg
                font = font.deriveFont(12f)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            if (f.file.isNotBlank()) {
                center.add(JLabel("${f.file.substringAfterLast('/')}${if (f.line > 0) " : ${f.line}" else ""}").apply {
                    foreground = if (isSelected) fg else Color.GRAY
                    font = font.deriveFont(11f)
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
            card.add(center, BorderLayout.CENTER)

            return JPanel(BorderLayout()).apply {
                isOpaque = true; background = bg
                add(card, BorderLayout.CENTER)
                add(JSeparator().apply { foreground = Color(0x3C3F41) }, BorderLayout.SOUTH)
            }
        }
    }

    /** A filled circle with a centered glyph — used for status badges. */
    private class BadgeIcon(private val color: Color, private val glyph: String, private val size: Int) : Icon {
        override fun getIconWidth() = size
        override fun getIconHeight() = size
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x, y, size, size)
            g2.color = Color.WHITE
            g2.font = g2.font.deriveFont(Font.BOLD, (size * 0.5f))
            val fm = g2.fontMetrics
            val tx = x + (size - fm.stringWidth(glyph)) / 2
            val ty = y + (size - fm.height) / 2 + fm.ascent
            g2.drawString(glyph, tx, ty)
            g2.dispose()
        }
    }
}
