package com.example.azdoreviewer.infrastructure.ai.claude

import com.example.azdoreviewer.domain.ReviewComment
import com.example.azdoreviewer.infrastructure.ai.AiReviewProvider
import com.example.azdoreviewer.infrastructure.ai.FileReviewRequest
import com.example.azdoreviewer.infrastructure.ai.PromptBuilder
import com.example.azdoreviewer.infrastructure.ai.ReviewResponseParser
import com.example.azdoreviewer.infrastructure.azdo.AiException
import com.example.azdoreviewer.settings.AzdoSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Two auth modes:
 *  - "apikey": standard x-api-key header (official, billed per token)
 *  - "oauth":  Bearer subscription token from ClaudeOAuth (Pro/Max, unofficial)
 */
class ClaudeProvider(
    private val apiKey: String?,       // null when using oauth
    private val model: String = DEFAULT_MODEL,
    private val useOAuth: Boolean = false
) : AiReviewProvider {

    override val providerName = "claude"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val http = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun reviewFile(request: FileReviewRequest): List<ReviewComment> {
        // Higher token budget so long reviews aren't truncated; low temperature for consistency.
        val text = callApi(PromptBuilder.build(request), maxTokens = 8192, temperature = 0.0)
        return ReviewResponseParser.parse(text, request.filePath)
    }

    override suspend fun ping(): String = callApi("Reply with exactly the word: OK", 16, 0.0)

    private suspend fun callApi(prompt: String, maxTokens: Int, temperature: Double): String = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ClaudeRequest(
            model = model, maxTokens = maxTokens, temperature = temperature,
            // Subscription OAuth requires the Claude Code system identity to be accepted
            system = if (useOAuth) "You are Claude Code, Anthropic's official CLI for Claude." else null,
            messages = listOf(Message("user", prompt))
        ))

        val builder = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("anthropic-version", "2023-06-01")

        if (useOAuth) {
            val token = AzdoSettings.getInstance().getClaudeOAuthAccess()
                ?: throw AiException("Not signed in to Claude. Use 'Sign in with Claude' in Settings.")
            builder.header("Authorization", "Bearer $token")
            builder.header("anthropic-beta", "oauth-2025-04-20")
        } else {
            val key = apiKey ?: throw AiException("No Claude API key set.")
            builder.header("x-api-key", key)
        }

        val raw = http.newCall(builder.build()).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                // On 401 with OAuth, try a one-time refresh then retry
                if (r.code == 401 && useOAuth && tryRefresh()) {
                    return@use null
                }
                val detail = when (r.code) {
                    401 -> if (useOAuth) "Claude login expired. Sign in again." else "Invalid API key (401)."
                    403 -> "Forbidden (403). Account lacks access for this request."
                    404 -> "Model '$model' not found (404). Clear the Model field for default."
                    429 -> "Rate limited (429). Wait and retry."
                    else -> "HTTP ${r.code}: ${s.take(300)}"
                }
                throw AiException(detail)
            }
            s.ifBlank { throw AiException("Empty response from Claude") }
        }

        // Retry path after refresh
        val finalRaw = raw ?: run {
            val token = AzdoSettings.getInstance().getClaudeOAuthAccess()
            val retry = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("anthropic-version", "2023-06-01")
                .header("Authorization", "Bearer $token")
                .header("anthropic-beta", "oauth-2025-04-20")
                .build()
            http.newCall(retry).execute().use { r ->
                val s = r.body?.string() ?: ""
                if (!r.isSuccessful) throw AiException("Claude error after refresh (${r.code}): ${s.take(200)}")
                s
            }
        }

        json.decodeFromString<ClaudeResponse>(finalRaw).content.firstOrNull()?.text
            ?: throw AiException("Claude response had no text.\nRaw: ${finalRaw.take(200)}")
    }

    private fun tryRefresh(): Boolean {
        val settings = AzdoSettings.getInstance()
        val refresh = settings.getClaudeOAuthRefresh() ?: return false
        return runCatching {
            val t = ClaudeOAuth.refresh(refresh)
            settings.saveClaudeOAuth(t.accessToken, t.refreshToken.ifBlank { refresh })
            true
        }.getOrDefault(false)
    }

    @Serializable
    private data class ClaudeRequest(
        val model: String,
        @kotlinx.serialization.SerialName("max_tokens") val maxTokens: Int,
        val temperature: Double = 0.0,
        val system: String? = null,
        val messages: List<Message>
    )

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class ClaudeResponse(val content: List<ContentBlock> = emptyList())

    @Serializable
    private data class ContentBlock(val type: String = "", val text: String = "")

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-4-6"
    }
}
