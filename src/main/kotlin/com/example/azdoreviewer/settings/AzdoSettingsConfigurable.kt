package com.example.azdoreviewer.settings

import com.example.azdoreviewer.application.PrService
import com.example.azdoreviewer.application.ReviewService
import com.example.azdoreviewer.infrastructure.ai.ClaudeApiKeyHelper
import com.example.azdoreviewer.infrastructure.ai.claude.ClaudeOAuth
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import java.awt.Desktop
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.net.URI
import javax.swing.*

class AzdoSettingsConfigurable : Configurable {

    private val settings = AzdoSettings.getInstance()

    // Azure DevOps
    private val serverUrlField  = JBTextField().apply { toolTipText = "e.g. https://your-server.example.com/DefaultCollection/MyProject/_git/MyRepo" }
    private val tokenField      = JBPasswordField()
    private val generateBtn     = JButton("Generate")
    private val testBtn         = JButton("Test Connection")
    private val statusLabel     = JBLabel("")

    // AI
    private val aiProviderCombo = ComboBox(arrayOf("claude", "openai", "gemini", "ollama"))
    private val aiModelField    = JBTextField()
    private val aiKeyField      = JBPasswordField()
    private val getKeyBtn       = JButton("Get API Key")
    private val testAiBtn       = JButton("Test AI Key")
    private val signInBtn       = JButton("Sign in with Claude (subscription)")
    private val signOutBtn      = JButton("Sign out")
    private val aiStatusLabel   = JBLabel("")
    private val maxFilesSpinner = JSpinner(SpinnerNumberModel(20, 1, 100, 1))

    override fun getDisplayName() = "Azure DevOps AI Reviewer"

    override fun createComponent(): JComponent {
        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            insets = Insets(5, 8, 5, 8)
            fill   = GridBagConstraints.HORIZONTAL
        }

        fun bold(text: String) = JBLabel(text).apply { font = font.deriveFont(Font.BOLD) }
        fun lbl(text: String)  = JBLabel("$text:")

        var row = 0

        // ── Azure DevOps ──────────────────────────────────────────────────────
        c.gridx = 0; c.gridy = row; c.gridwidth = 3; c.weightx = 1.0
        panel.add(bold("Azure DevOps"), c)
        row++

        // Server URL — full width
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0.0
        panel.add(lbl("Repo URL"), c)
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1.0
        panel.add(serverUrlField, c)
        row++

        // Token + Generate button on same row
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0.0
        panel.add(lbl("Token"), c)
        c.gridx = 1; c.weightx = 1.0; c.gridwidth = 1
        panel.add(tokenField, c)
        c.gridx = 2; c.weightx = 0.0
        generateBtn.addActionListener { openTokenPage() }
        panel.add(generateBtn, c)
        row++

        // Test + status on same row
        c.gridx = 1; c.gridy = row; c.weightx = 0.0; c.gridwidth = 1
        testBtn.addActionListener { onTest() }
        panel.add(testBtn, c)
        c.gridx = 2; c.weightx = 1.0
        panel.add(statusLabel, c)
        row++

        // Separator
        c.gridx = 0; c.gridy = row; c.gridwidth = 3; c.weightx = 1.0
        panel.add(JSeparator(), c)
        row++

        // ── AI Provider ───────────────────────────────────────────────────────
        c.gridx = 0; c.gridy = row; c.gridwidth = 3
        panel.add(bold("AI Provider"), c)
        row++

        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0.0
        panel.add(lbl("Provider"), c)
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1.0
        panel.add(aiProviderCombo, c)
        row++

        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0.0
        panel.add(lbl("Model"), c)
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1.0
        panel.add(aiModelField, c)
        row++

        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0.0
        panel.add(lbl("API Key"), c)
        c.gridx = 1; c.weightx = 1.0; c.gridwidth = 1
        panel.add(aiKeyField, c)
        c.gridx = 2; c.weightx = 0.0
        getKeyBtn.addActionListener { onGetApiKey() }
        panel.add(getKeyBtn, c)
        row++

        // Test AI key + status
        c.gridx = 1; c.gridy = row; c.weightx = 0.0; c.gridwidth = 1
        testAiBtn.addActionListener { onTestAi() }
        panel.add(testAiBtn, c)
        c.gridx = 2; c.weightx = 1.0
        panel.add(aiStatusLabel, c)
        row++

        // Subscription sign-in (Claude only)
        c.gridx = 1; c.gridy = row; c.weightx = 0.0; c.gridwidth = 1
        signInBtn.addActionListener { onSignIn() }
        panel.add(signInBtn, c)
        c.gridx = 2; c.weightx = 0.0
        signOutBtn.addActionListener { onSignOut() }
        panel.add(signOutBtn, c)
        row++

        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0.0
        panel.add(lbl("Max files"), c)
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1.0
        panel.add(maxFilesSpinner, c)
        row++

        // Filler
        c.gridx = 0; c.gridy = row; c.gridwidth = 3; c.weighty = 1.0
        c.fill = GridBagConstraints.VERTICAL
        panel.add(JPanel(), c)

        reset()
        return panel
    }

    private fun openTokenPage() {
        // Parse org from whatever is in the field right now
        val raw  = serverUrlField.text.trim().trimEnd('/')
        val base = if (raw.contains("/_git/")) raw.substringBefore("/_git/").substringBeforeLast('/') else raw
        val url  = if (base.isNotBlank()) "$base/_usersSettings/tokens" else "https://dev.azure.com"
        openUrl(url)
    }

    private fun onSignIn() {
        // 1) Save provider so the right key slot is used
        settings.state.aiProvider = aiProviderCombo.selectedItem as String

        // 2) Start PKCE flow (opens browser)
        val pending = ClaudeOAuth.startAuthorization()

        // 3) Ask the user to paste the code from the callback page
        val pasted = Messages.showInputDialog(
            "A browser opened for Claude sign-in.\n\n" +
            "1. Approve access with your Pro/Max/Team account\n" +
            "2. Copy the authorization code shown on the page\n" +
            "3. Paste it here (format may be CODE#STATE):",
            "Sign in with Claude", null
        )
        if (pasted.isNullOrBlank()) { aiStatusLabel.text = "Sign-in cancelled"; return }

        aiStatusLabel.text = "Exchanging code…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val tokens = ClaudeOAuth.exchangeCode(pasted, pending)
                settings.saveClaudeOAuth(tokens.accessToken, tokens.refreshToken)
            }
            ApplicationManager.getApplication().invokeLater {
                result.fold(
                    onSuccess = { aiStatusLabel.text = "✓ Signed in to Claude (subscription)" },
                    onFailure = { e ->
                        aiStatusLabel.text = "✗ Sign-in failed"
                        Messages.showErrorDialog(e.message ?: "Unknown error", "Claude Sign-in Failed")
                    }
                )
            }
        }
    }

    private fun onSignOut() {
        settings.clearClaudeOAuth()
        aiStatusLabel.text = "Signed out — using API key"
    }

    private fun onTestAi() {
        apply() // save key + provider + model first
        aiStatusLabel.text = "Testing…"
        testAiBtn.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { service<ReviewService>().testAiConnection() }
            ApplicationManager.getApplication().invokeLater {
                testAiBtn.isEnabled = true
                result.fold(
                    onSuccess = { msg -> aiStatusLabel.text = msg },
                    onFailure = { e ->
                        aiStatusLabel.text = "✗ Failed"
                        Messages.showErrorDialog(e.message ?: "Unknown error", "AI Key Test Failed")
                    }
                )
            }
        }
    }

    private fun onGetApiKey() {
        ClaudeApiKeyHelper.openConsolInBrowser()
        Messages.showInfoMessage(
            "1. Click 'Create Key'\n2. Copy the key\n3. Paste it into the API Key field\n4. Click Apply",
            "Get Claude API Key"
        )
    }

    private fun onTest() {
        apply()
        statusLabel.text  = "Testing…"
        testBtn.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { service<PrService>().testConnection() }
            ApplicationManager.getApplication().invokeLater {
                testBtn.isEnabled = true
                result.fold(
                    onSuccess = { ok ->
                        statusLabel.text = if (ok) "✓ Connected" else "✗ Failed"
                    },
                    onFailure = { e ->
                        statusLabel.text = "✗ Failed"
                        Messages.showErrorDialog(e.message ?: "Unknown error", "Connection Failed")
                    }
                )
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
            else Runtime.getRuntime().exec(arrayOf("xdg-open", url))
        } catch (_: Exception) {}
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return serverUrlField.text != s.repoUrl ||
               aiProviderCombo.selectedItem != s.aiProvider ||
               aiModelField.text != s.aiModel ||
               (maxFilesSpinner.value as Int) != s.maxFilesPerReview ||
               String(tokenField.password).isNotEmpty() ||
               String(aiKeyField.password).isNotEmpty()
    }

    override fun apply() {
        val s = settings.state
        settings.parseAndSaveRepoUrl(serverUrlField.text)
        s.aiProvider        = aiProviderCombo.selectedItem as String
        s.aiModel           = aiModelField.text.trim()
        s.maxFilesPerReview = maxFilesSpinner.value as Int

        String(tokenField.password).takeIf { it.isNotEmpty() && it != "••••••••••••••••" }?.let {
            settings.setPat(it)
        }
        String(aiKeyField.password).takeIf { it.isNotEmpty() && it != "••••••••••••••••" }?.let {
            settings.setAiApiKey(it)
        }
        reset() // refresh placeholders
    }

    override fun reset() {
        val s = settings.state
        serverUrlField.text  = s.repoUrl.ifBlank { s.organizationUrl }
        aiProviderCombo.selectedItem = s.aiProvider
        aiModelField.text    = s.aiModel
        maxFilesSpinner.value = s.maxFilesPerReview
        tokenField.text = if (settings.getPat() != null) "••••••••••••••••" else ""
        aiKeyField.text = if (settings.getAiApiKey() != null) "••••••••••••••••" else ""

        // Reflect Claude auth mode
        if (s.claudeAuthMode == "oauth" && settings.getClaudeOAuthAccess() != null) {
            aiStatusLabel.text = "✓ Signed in to Claude (subscription)"
        }
        // Sign-in only applies to Claude
        val isClaude = s.aiProvider == "claude"
        signInBtn.isEnabled  = isClaude
        signOutBtn.isEnabled = isClaude && s.claudeAuthMode == "oauth"
    }
}
