package com.example.azdoreviewer.infrastructure.ai.claude

import com.example.azdoreviewer.infrastructure.azdo.AiException
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.Desktop
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Subscription-based OAuth (Pro/Max/Team/Enterprise) using the Claude Code public client.
 *
 * IMPORTANT: This reuses Claude Code's OAuth client_id and the subscription `user:inference`
 * scope. It is UNDOCUMENTED and may violate Anthropic's terms. Use at your own risk.
 *
 * Flow (PKCE, manual code paste — Anthropic's callback lives on their own domain so we
 * cannot run a local server to intercept it):
 *   1. startAuthorization() -> opens browser, returns the verifier to keep
 *   2. user approves, Anthropic shows a code on the callback page
 *   3. user pastes "code#state" back into the plugin
 *   4. exchangeCode() -> access + refresh tokens
 */
object ClaudeOAuth {

    private const val CLIENT_ID    = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"  // Claude Code public client
    private const val AUTHORIZE    = "https://claude.com/cai/oauth/authorize"
    private const val TOKEN_URL    = "https://platform.claude.com/v1/oauth/token"
    private const val REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"
    private const val SCOPES       = "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class PendingAuth(val verifier: String, val state: String)

    /** Builds the PKCE challenge, opens the browser, returns the verifier/state to keep for the exchange. */
    fun startAuthorization(): PendingAuth {
        val verifier  = randomUrlSafe(64)
        val challenge = s256(verifier)
        val state     = randomUrlSafe(32)

        val url = buildString {
            append(AUTHORIZE)
            append("?code=true")
            append("&client_id=").append(CLIENT_ID)
            append("&response_type=code")
            append("&redirect_uri=").append(enc(REDIRECT_URI))
            append("&scope=").append(enc(SCOPES))
            append("&code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
            append("&state=").append(state)
        }

        openBrowser(url)
        return PendingAuth(verifier, state)
    }

    /**
     * Exchanges the pasted code for tokens.
     * The user pastes the value Anthropic shows, which is typically "CODE#STATE".
     * Returns OAuthTokens; throws AiException on failure.
     */
    fun exchangeCode(pasted: String, pending: PendingAuth): OAuthTokens {
        val raw = pasted.trim()
        val code  = raw.substringBefore('#')
        val state = if (raw.contains('#')) raw.substringAfter('#') else pending.state

        val body = json.encodeToString(TokenRequest(
            grantType    = "authorization_code",
            code         = code,
            state        = state,
            clientId     = CLIENT_ID,
            redirectUri  = REDIRECT_URI,
            codeVerifier = pending.verifier
        ))

        return postToken(body)
    }

    /** Refreshes an expired access token. */
    fun refresh(refreshToken: String): OAuthTokens {
        val body = json.encodeToString(RefreshRequest(
            grantType    = "refresh_token",
            refreshToken = refreshToken,
            clientId     = CLIENT_ID
        ))
        return postToken(body)
    }

    private fun postToken(body: String): OAuthTokens {
        val req = Request.Builder()
            .url(TOKEN_URL)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        val resp = http.newCall(req).execute()
        val text = resp.use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                thisLogger().warn("OAuth token exchange failed ${r.code}: $s")
                throw AiException("Claude OAuth failed (${r.code}): ${s.take(300)}")
            }
            s
        }
        val t = json.decodeFromString<TokenResponse>(text)
        if (t.accessToken.isBlank()) throw AiException("OAuth response had no access_token.")
        return OAuthTokens(t.accessToken, t.refreshToken, t.expiresIn)
    }

    // ── PKCE helpers ─────────────────────────────────────────────────────────
    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private fun s256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun openBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                Desktop.getDesktop().browse(URI(url))
            else Runtime.getRuntime().exec(arrayOf("xdg-open", url))
        } catch (e: Exception) {
            thisLogger().warn("Failed to open browser: ${e.message}")
        }
    }

    @Serializable
    private data class TokenRequest(
        @SerialName("grant_type") val grantType: String,
        val code: String,
        val state: String,
        @SerialName("client_id") val clientId: String,
        @SerialName("redirect_uri") val redirectUri: String,
        @SerialName("code_verifier") val codeVerifier: String
    )

    @Serializable
    private data class RefreshRequest(
        @SerialName("grant_type") val grantType: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("client_id") val clientId: String
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token")  val accessToken: String = "",
        @SerialName("refresh_token") val refreshToken: String = "",
        @SerialName("expires_in")    val expiresIn: Long = 0
    )
}

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)
