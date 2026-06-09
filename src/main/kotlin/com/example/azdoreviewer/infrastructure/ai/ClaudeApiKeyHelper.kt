package com.example.azdoreviewer.infrastructure.ai

import java.awt.Desktop
import java.net.URI

/**
 * Anthropic does not provide a public OAuth flow for third-party apps.
 * This helper opens the Anthropic console so the user can copy their API key,
 * then shows a dialog to paste it — the closest UX to "browser auth" available.
 */
object ClaudeApiKeyHelper {

    private const val CONSOLE_URL = "https://console.anthropic.com/account/keys"

    fun openConsolInBrowser() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(CONSOLE_URL))
            } else {
                Runtime.getRuntime().exec(arrayOf("xdg-open", CONSOLE_URL))
            }
        } catch (_: Exception) {
            // Silently ignore — user can open URL manually
        }
    }
}
