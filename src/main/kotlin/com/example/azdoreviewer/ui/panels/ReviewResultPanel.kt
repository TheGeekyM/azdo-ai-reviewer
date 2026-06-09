package com.example.azdoreviewer.ui.panels

import com.example.azdoreviewer.application.CommentService
import com.example.azdoreviewer.application.ReviewService
import com.example.azdoreviewer.domain.PullRequest
import com.example.azdoreviewer.domain.ReviewComment
import com.example.azdoreviewer.domain.Severity
import com.example.azdoreviewer.settings.AzdoSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

class ReviewResultPanel(private val project: Project, private val pr: PullRequest) {

    private val reviewService  = service<ReviewService>()
    private val commentService = service<CommentService>()
    private val settings       = AzdoSettings.getInstance()

    private var comments: List<ReviewComment> = emptyList()

    private val listModel  = DefaultListModel<ReviewComment>()
    private val issueList   = JBList(listModel)
    // Plain styled text pane — NO HTML/emoji rendering (that was hanging the EDT on Linux).
    private val detailPane  = JTextPane().apply {
        isEditable = false
        background = UIUtil.getPanelBackground()
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        text = "Select an issue above to see details."
    }
    private val statusLabel = JBLabel("Click 'Run AI Review' to analyse this PR")
    private val progress    = JProgressBar().apply { isIndeterminate = true; isVisible = false }
    private val summaryBar  = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
    private val panel       = JPanel(BorderLayout())

    private lateinit var runBtn: JButton
    private lateinit var postAllBtn: JButton
    private lateinit var postSelBtn: JButton
    private lateinit var fixBtn: JButton

    // ── Loading animation ─────────────────────────────────────────────────────
    private val loadingMessages = listOf(
        "Analysing the code",
        "Reviewing for bugs and defects",
        "Checking security and performance",
        "Inspecting SOLID and DDD",
        "Generating review feedback"
    )
    private var loadingTimer: Timer? = null
    private var loadingTick = 0

    init {
        issueList.cellRenderer = IssueCellRenderer()
        issueList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // Single click → show full issue details + solution + code in the second box
        issueList.addListSelectionListener {
            if (!it.valueIsAdjusting) issueList.selectedValue?.let { c -> showDetail(c) }
        }
        // Mouse click: 1 = show details (belt-and-suspenders), 2 = open file
        issueList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val idx = issueList.locationToIndex(e.point)
                if (idx < 0) return
                issueList.selectedIndex = idx
                val c = listModel.getElementAt(idx)
                if (e.clickCount == 2) openInEditor(c) else showDetail(c)
            }
        })

        val top = JPanel(BorderLayout()).apply {
            add(buildToolbar(), BorderLayout.NORTH)
            add(progress, BorderLayout.CENTER)
            add(summaryBar, BorderLayout.SOUTH)
        }

        // Detail area = a small action bar (Fix / Open) on top of the detail text.
        fixBtn = JButton("Apply Fix", AllIcons.Actions.IntentionBulb).apply {
            isEnabled = false
            toolTipText = "Replace the affected lines in the file with the suggested code"
            addActionListener { issueList.selectedValue?.let { applyFix(it) } }
        }
        val openBtn2 = JButton("Open in Editor", AllIcons.Actions.EditSource).apply {
            addActionListener { issueList.selectedValue?.let { openInEditor(it) } }
        }
        val detailBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 3)).apply {
            add(fixBtn); add(openBtn2)
        }
        val detailArea = JPanel(BorderLayout()).apply {
            add(detailBar, BorderLayout.NORTH)
            add(JBScrollPane(detailPane), BorderLayout.CENTER)
        }

        val split = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JBScrollPane(issueList),
            detailArea
        ).apply {
            resizeWeight = 0.5
            dividerLocation = 260
            (bottomComponent as JComponent).minimumSize = java.awt.Dimension(0, 140)
        }

        panel.add(top, BorderLayout.NORTH)
        panel.add(split, BorderLayout.CENTER)
        panel.add(statusLabel.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.SOUTH)
    }

    fun getComponent(): JComponent = panel

    private fun buildToolbar(): JComponent {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))

        runBtn = JButton("Run AI Review", AllIcons.Actions.Execute).apply {
            addActionListener { runReview(false) }
        }
        val rerunBtn = JButton("Re-run", AllIcons.Actions.Refresh).apply {
            addActionListener { runReview(true) }
        }
        postAllBtn = JButton("Post All to PR", AllIcons.Vcs.Push).apply {
            isEnabled = false
            addActionListener { postAll() }
        }
        postSelBtn = JButton("Post Selected", AllIcons.Actions.Commit).apply {
            isEnabled = false
            addActionListener { postSelected() }
        }
        val openBtn = JButton("Open in Editor", AllIcons.Actions.EditSource).apply {
            addActionListener { issueList.selectedValue?.let { openInEditor(it) } }
        }

        bar.add(runBtn); bar.add(rerunBtn)
        bar.add(JSeparator(SwingConstants.VERTICAL))
        bar.add(openBtn)
        bar.add(JSeparator(SwingConstants.VERTICAL))
        bar.add(postAllBtn); bar.add(postSelBtn)
        return bar
    }

    private fun runReview(forceRefresh: Boolean) {
        val needsKey = settings.state.aiProvider != "ollama" &&
            !(settings.state.aiProvider == "claude" && settings.state.claudeAuthMode == "oauth")
        if (needsKey && settings.getAiApiKey() == null) {
            Messages.showWarningDialog(project,
                "No AI credentials configured.\nGo to Settings → Azure DevOps AI Reviewer and set your ${settings.state.aiProvider} key, or use 'Sign in with Claude'.",
                "AI Not Configured")
            return
        }

        progress.isVisible = true
        runBtn.isEnabled   = false
        listModel.clear(); summaryBar.removeAll()
        comments = emptyList()
        startLoadingAnimation()

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { reviewService.reviewPr(pr.id, forceRefresh) }
            ApplicationManager.getApplication().invokeLater {
                stopLoadingAnimation()
                progress.isVisible = false
                runBtn.isEnabled   = true
                result.fold(
                    onSuccess = { found ->
                        comments = found
                        found.forEach { listModel.addElement(it) }
                        buildSummary(found)
                        val enable = found.isNotEmpty()
                        postAllBtn.isEnabled = enable
                        postSelBtn.isEnabled = enable
                        statusLabel.text = if (found.isEmpty())
                            "✓ Review complete — no issues found"
                        else "✓ ${found.size} issue(s) found"
                    },
                    onFailure = { e ->
                        detailPane.text = ""
                        statusLabel.text = "✗ Review failed"
                        Messages.showErrorDialog(project, e.message ?: "Unknown error", "AI Review Failed")
                    }
                )
            }
        }
    }

    /** Animated "Analysing the code…" loop shown big & centered in the detail box. */
    private fun startLoadingAnimation() {
        loadingTick = 0
        renderLoadingFrame()
        loadingTimer = Timer(500) { renderLoadingFrame() }.apply { start() }
    }

    private fun stopLoadingAnimation() {
        loadingTimer?.stop()
        loadingTimer = null
        detailPane.text = ""
    }

    private fun renderLoadingFrame() {
        val msgIndex = (loadingTick / 4) % loadingMessages.size  // change message every ~2s
        val dots = ".".repeat((loadingTick % 4))                 // animated 0–3 dots
        val message = loadingMessages[msgIndex]
        loadingTick++

        statusLabel.text = "$message$dots"
        detailPane.document = javax.swing.text.DefaultStyledDocument()  // reset styles
        detailPane.text = "\n\n        $message$dots\n\n        Reviewing PR #${pr.id} — this can take 30–120 seconds."
    }

    private fun buildSummary(found: List<ReviewComment>) {
        summaryBar.removeAll()
        Severity.entries.forEach { sev ->
            val count = found.count { it.severity == sev }
            if (count > 0) summaryBar.add(severityChip(sev, count))
        }
        summaryBar.revalidate(); summaryBar.repaint()
    }

    private fun postAll() {
        if (comments.isEmpty()) return
        if (Messages.showYesNoDialog(project,
                "Post all ${comments.size} comments to PR #${pr.id}?",
                "Post to Azure DevOps", Messages.getQuestionIcon()) != Messages.YES) return

        progress.isVisible = true
        statusLabel.text = "Posting comments…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val r = runCatching { commentService.postAllComments(pr.id, comments) }
            ApplicationManager.getApplication().invokeLater {
                progress.isVisible = false
                statusLabel.text = r.fold(
                    { "✓ All comments posted to PR #${pr.id}" },
                    { "✗ Failed: ${it.message}" }
                )
            }
        }
    }

    private fun postSelected() {
        val c = issueList.selectedValue ?: run {
            Messages.showInfoMessage(project, "Select an issue first.", "Nothing Selected"); return
        }

        // Show an editable preview of exactly what will be posted
        val dialog = PostPreviewDialog(
            project,
            target = "PR #${pr.id}  →  ${c.file.substringAfterLast('/')} : ${c.lineRange}",
            initialText = commentService.previewComment(c)
        )
        if (!dialog.showAndGet()) return   // user cancelled
        val edited = dialog.getText()
        if (edited.isBlank()) return

        progress.isVisible = true
        statusLabel.text = "Posting comment…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val r = runCatching { commentService.postLineComment(pr.id, c, edited) }
            ApplicationManager.getApplication().invokeLater {
                progress.isVisible = false
                statusLabel.text = r.fold(
                    { "✓ Posted: ${c.file.substringAfterLast('/')}:${c.line}" },
                    { "✗ Failed: ${it.message}" }
                )
            }
        }
    }

    private fun showDetail(c: ReviewComment) {
        val doc = javax.swing.text.DefaultStyledDocument()
        fun style(name: String, color: Color, bold: Boolean, size: Int = 13): javax.swing.text.SimpleAttributeSet {
            val a = javax.swing.text.SimpleAttributeSet()
            javax.swing.text.StyleConstants.setForeground(a, color)
            javax.swing.text.StyleConstants.setBold(a, bold)
            javax.swing.text.StyleConstants.setFontSize(a, size)
            javax.swing.text.StyleConstants.setFontFamily(a, Font.SANS_SERIF)
            return a
        }
        fun add(text: String, attrs: javax.swing.text.SimpleAttributeSet) =
            doc.insertString(doc.length, text, attrs)

        val header   = style("h", severityColor(c.severity), true, 14)
        val label    = style("l", Color(0x888888), true, 12)
        val body     = style("b", UIUtil.getLabelForeground(), false, 13)
        val codeAttr = javax.swing.text.SimpleAttributeSet().apply {
            javax.swing.text.StyleConstants.setForeground(this, Color(0xA9B7C6))
            javax.swing.text.StyleConstants.setFontFamily(this, Font.MONOSPACED)
            javax.swing.text.StyleConstants.setFontSize(this, 12)
        }

        add("${c.severity.label}  ·  ${c.category.label}\n", header)
        add("${c.file.substringAfterLast('/')}  :  line${if (c.endLine > c.line) "s" else ""} ${c.lineRange}\n\n", label)

        add("WHAT'S WRONG\n", style("s1", Color(0xCC7832), true))
        add("${c.comment.trim()}\n\n", body)

        add("HOW TO FIX IT\n", style("s2", Color(0x6A8759), true))
        add("${c.suggestion.trim()}\n", body)

        if (c.suggestedCode.isNotBlank()) {
            add("\nSUGGESTED CODE (replaces line${if (c.endLine > c.line) "s" else ""} ${c.lineRange})\n", style("s3", Color(0x6897BB), true))
            add("${c.suggestedCode.trim()}\n", codeAttr)
        }

        add("\nTip: double-click the issue to open the file at the line.", style("t", Color(0x777777), false, 11))

        detailPane.document = doc
        detailPane.caretPosition = 0

        // Fix is available only when the AI gave concrete replacement code.
        fixBtn.isEnabled = c.suggestedCode.isNotBlank()
    }

    /**
     * Applies the AI's suggested code to the file: opens it, replaces the affected
     * line range with `suggestedCode`, inside an undoable write command.
     */
    private fun applyFix(c: ReviewComment) {
        if (c.suggestedCode.isBlank()) return

        val dumb = com.intellij.openapi.project.DumbService.getInstance(project)
        if (dumb.isDumb) { statusLabel.text = "⏳ IDE is indexing — try again in a moment."; return }

        statusLabel.text = "Locating ${c.file.substringAfterLast('/')}…"
        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(project, "Locating file", true) {
                @Volatile private var vFile: com.intellij.openapi.vfs.VirtualFile? = null
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    vFile = com.intellij.openapi.application.ReadAction.nonBlocking<com.intellij.openapi.vfs.VirtualFile?> {
                        findFileInProject(c.file)
                    }.inSmartMode(project).executeSynchronously()
                }
                override fun onSuccess() {
                    val f = vFile
                    if (f == null) { statusLabel.text = "File not found locally"; offerCheckout(c); return }
                    performFix(f, c)
                }
                override fun onThrowable(error: Throwable) { statusLabel.text = "⚠ ${error.message}" }
            }
        )
    }

    private fun performFix(file: com.intellij.openapi.vfs.VirtualFile, c: ReviewComment) {
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
        if (doc == null) { statusLabel.text = "⚠ Could not load document"; return }

        val startLine = (c.line - 1).coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0))
        val endLine   = (if (c.endLine > c.line) c.endLine - 1 else startLine)
            .coerceIn(startLine, (doc.lineCount - 1).coerceAtLeast(0))

        // Preview the exact replacement so the user confirms before editing the file.
        val original = doc.getText(com.intellij.openapi.util.TextRange(
            doc.getLineStartOffset(startLine), doc.getLineEndOffset(endLine)))
        val confirm = Messages.showYesNoDialog(
            project,
            "Replace line${if (endLine > startLine) "s ${startLine + 1}–${endLine + 1}" else " ${startLine + 1}"} " +
            "in ${file.name}?\n\n— Current —\n$original\n\n— New —\n${c.suggestedCode.trim()}",
            "Apply Suggested Fix", "Apply", "Cancel", Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        // Open the file so the change is visible, then replace inside a write command.
        com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, startLine, 0).navigate(true)

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, "Apply Suggested Fix", null, {
            val from = doc.getLineStartOffset(startLine)
            val to   = doc.getLineEndOffset(endLine)
            // Preserve the leading indentation of the first replaced line.
            val indent = original.takeWhile { it == ' ' || it == '\t' }
            val newText = c.suggestedCode.trim().lines().joinToString("\n") { line ->
                if (line.isBlank()) line else indent + line.trimStart()
            }
            doc.replaceString(from, to, newText)
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(doc)
        })
        statusLabel.text = "✓ Applied fix to ${file.name}:${c.line}  (Ctrl+Z to undo)"
    }

    /**
     * Tries to open the file at the given line, WITHOUT ever blocking the UI.
     * - If the IDE is indexing (dumb mode), we don't wait — we tell the user.
     * - The index lookup runs in a cancellable background read action.
     * - If the file isn't in the local project (e.g. branch not checked out), we fail fast.
     */
    private fun openInEditor(c: ReviewComment) {
        // Fast bail: don't even try while the project is indexing — that's what hangs.
        val dumb = com.intellij.openapi.project.DumbService.getInstance(project)
        if (dumb.isDumb) {
            statusLabel.text = "⏳ IDE is indexing — try again in a moment."
            return
        }

        statusLabel.text = "Locating ${c.file.substringAfterLast('/')}…"

        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(project, "Locating file", true) {
                @Volatile private var vFile: com.intellij.openapi.vfs.VirtualFile? = null

                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    vFile = com.intellij.openapi.application.ReadAction.nonBlocking<com.intellij.openapi.vfs.VirtualFile?> {
                        findFileInProject(c.file)
                    }
                        .inSmartMode(project)
                        .executeSynchronously()
                }

                override fun onSuccess() {
                    val f = vFile
                    if (f == null) {
                        statusLabel.text = "File not found locally"
                        offerCheckout(c)
                        return
                    }
                    val line = (c.line - 1).coerceAtLeast(0)
                    com.intellij.openapi.fileEditor.OpenFileDescriptor(project, f, line, 0).navigate(true)
                    statusLabel.text = "Opened ${c.file.substringAfterLast('/')}:${c.line}"
                }

                override fun onThrowable(error: Throwable) {
                    statusLabel.text = "⚠ Could not open file: ${error.message}"
                }
            }
        )
    }

    /** Locate a virtual file whose path ends with the PR-relative path. Runs inside a read action. */
    private fun findFileInProject(serverPath: String): com.intellij.openapi.vfs.VirtualFile? {
        val rel  = serverPath.trimStart('/')
        val name = rel.substringAfterLast('/')
        val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)

        val candidates = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(name, scope)
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.path.replace('\\', '/').endsWith(rel) }
            ?: candidates.firstOrNull()
    }

    /** When a PR file isn't local, offer to fetch + check out the PR's source branch. */
    private fun offerCheckout(c: ReviewComment) {
        val branch = pr.sourceBranch
        val choice = Messages.showDialog(
            project,
            "\"${c.file.substringAfterLast('/')}\" isn't in your current checkout.\n\n" +
            "It belongs to this pull request's branch:\n    $branch\n\n" +
            "Check out that branch so you can open and edit the file?",
            "File Not in Current Branch",
            arrayOf("Checkout \"$branch\"", "Cancel"),
            0,
            Messages.getQuestionIcon()
        )
        if (choice == 0) checkoutBranch(branch, c)
    }

    private fun checkoutBranch(branch: String, reopenAfter: ReviewComment) {
        val repoManager = git4idea.repo.GitRepositoryManager.getInstance(project)
        val repo = repoManager.repositories.firstOrNull()
        if (repo == null) {
            Messages.showErrorDialog(project, "No Git repository found in this project.", "Cannot Checkout")
            return
        }

        statusLabel.text = "Checking out $branch…"

        // Fetch first so the remote branch is available, then checkout.
        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(project, "Fetching $branch", true) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    git4idea.fetch.GitFetchSupport.fetchSupport(project).fetchAllRemotes(listOf(repo)).showNotificationIfFailed()
                }
                override fun onSuccess() {
                    // checkout() is async; reopen the file in its success callback
                    git4idea.branch.GitBrancher.getInstance(project).checkout(
                        branch, false, listOf(repo)
                    ) {
                        statusLabel.text = "Checked out $branch — opening file…"
                        // Give VFS a moment to refresh, then reopen
                        ApplicationManager.getApplication().invokeLater { openInEditor(reopenAfter) }
                    }
                }
                override fun onThrowable(error: Throwable) {
                    statusLabel.text = "✗ Fetch failed"
                    Messages.showErrorDialog(project, error.message ?: "Fetch failed", "Checkout Failed")
                }
            }
        )
    }

    private fun severityChip(sev: Severity, count: Int): JComponent =
        JLabel("${sev.label}: $count", CircleBadgeIcon(severityColor(sev), severityLetter(sev), 16), SwingConstants.LEFT).apply {
            iconTextGap = 5
            foreground = severityColor(sev)
            font = font.deriveFont(Font.BOLD, 11f)
            border = JBUI.Borders.empty(3, 6)
        }

    private fun severityColor(sev: Severity) = when (sev) {
        Severity.CRITICAL -> Color(0xD32F2F)
        Severity.HIGH     -> Color(0xF57C00)
        Severity.MEDIUM   -> Color(0xFBC02D)
        Severity.LOW      -> Color(0x388E3C)
        Severity.INFO     -> Color(0x1976D2)
    }

    private fun severityColorHex(sev: Severity) =
        "#" + Integer.toHexString(severityColor(sev).rgb).substring(2)

    private fun String.escapeHtml() =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun severityLetter(sev: Severity) = when (sev) {
        Severity.CRITICAL -> "C"
        Severity.HIGH     -> "H"
        Severity.MEDIUM   -> "M"
        Severity.LOW      -> "L"
        Severity.INFO     -> "I"
    }

    /** One issue per row: circular severity icon + category + file:line + short text. */
    private inner class IssueCellRenderer : ListCellRenderer<ReviewComment> {
        override fun getListCellRendererComponent(
            list: JList<out ReviewComment>, c: ReviewComment, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val bg = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            val fg = if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()

            val card = JPanel(BorderLayout(10, 0)).apply {
                background = bg; isOpaque = true
                border = JBUI.Borders.empty(8, 10)
            }

            // Circular severity icon on the left
            card.add(JLabel(CircleBadgeIcon(severityColor(c.severity), severityLetter(c.severity), 26)).apply {
                verticalAlignment = SwingConstants.TOP
                toolTipText = c.severity.label
            }, BorderLayout.WEST)

            val center = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
            center.add(JLabel("${c.severity.label}  ·  ${c.category.label}").apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = if (isSelected) fg else severityColor(c.severity)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            center.add(Box.createVerticalStrut(2))
            center.add(JLabel(c.comment.take(100) + if (c.comment.length > 100) "…" else "").apply {
                foreground = fg
                font = font.deriveFont(12f)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            center.add(JLabel("${c.file.substringAfterLast('/')} : ${c.lineRange}").apply {
                foreground = if (isSelected) fg else Color.GRAY
                font = font.deriveFont(11f)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            card.add(center, BorderLayout.CENTER)

            return JPanel(BorderLayout()).apply {
                isOpaque = true; background = bg
                add(card, BorderLayout.CENTER)
                add(JSeparator().apply { foreground = Color(0x3C3F41) }, BorderLayout.SOUTH)
            }
        }
    }

    /** A filled circle with a centered letter — used for severity icons. */
    private class CircleBadgeIcon(
        private val color: Color,
        private val letter: String,
        private val size: Int
    ) : Icon {
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
            val tx = x + (size - fm.stringWidth(letter)) / 2
            val ty = y + (size - fm.height) / 2 + fm.ascent
            g2.drawString(letter, tx, ty)
            g2.dispose()
        }
    }
}
