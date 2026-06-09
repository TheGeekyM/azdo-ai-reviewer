package com.example.azdoreviewer.infrastructure.ai

import com.example.azdoreviewer.infrastructure.ai.claude.ClaudeProvider
import com.example.azdoreviewer.infrastructure.ai.local.OllamaProvider
import com.example.azdoreviewer.infrastructure.ai.openai.OpenAiProvider
import com.example.azdoreviewer.infrastructure.azdo.AiException
import com.example.azdoreviewer.settings.AzdoSettings

object AiProviderFactory {

    fun create(settings: AzdoSettings): AiReviewProvider {
        val provider = settings.state.aiProvider
        val model    = settings.state.aiModel

        return when (provider) {
            "claude" -> {
                if (settings.state.claudeAuthMode == "oauth") {
                    // Subscription login — no API key, Bearer token from PasswordSafe
                    if (settings.getClaudeOAuthAccess() == null)
                        throw AiException("Not signed in to Claude. Use 'Sign in with Claude' in Settings.")
                    ClaudeProvider(apiKey = null, model = model.ifBlank { ClaudeProvider.DEFAULT_MODEL }, useOAuth = true)
                } else {
                    val key = settings.getAiApiKey()
                        ?: throw AiException("No Claude API key set. Either paste a key or use 'Sign in with Claude'.")
                    ClaudeProvider(apiKey = key, model = model.ifBlank { ClaudeProvider.DEFAULT_MODEL })
                }
            }
            "openai" -> {
                val key = settings.getAiApiKey() ?: throw AiException("No OpenAI API key set.")
                OpenAiProvider(key, model.ifBlank { "gpt-4o" })
            }
            "ollama" -> OllamaProvider(model.ifBlank { "llama3" })
            else     -> throw AiException("Unknown AI provider: $provider")
        }
    }
}
