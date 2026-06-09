package com.example.azdoreviewer.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.util.Base64

@State(
    name = "AzdoReviewerSettings",
    storages = [Storage("azdo-reviewer.xml")]
)
@Service(Service.Level.APP)
class AzdoSettings : PersistentStateComponent<AzdoSettingsState> {

    private var _state = AzdoSettingsState()

    override fun getState(): AzdoSettingsState = _state
    override fun loadState(state: AzdoSettingsState) { _state = state }

    fun getPat(): String?   = PasswordSafe.instance.getPassword(creds("pat"))
    fun setPat(pat: String) = PasswordSafe.instance.setPassword(creds("pat"), pat)

    fun getAiApiKey(): String?       = PasswordSafe.instance.getPassword(creds("ai-${_state.aiProvider}"))
    fun setAiApiKey(key: String)     = PasswordSafe.instance.setPassword(creds("ai-${_state.aiProvider}"), key)

    // ── Claude subscription OAuth tokens ──────────────────────────────────────
    fun getClaudeOAuthAccess(): String?  = PasswordSafe.instance.getPassword(creds("claude-oauth-access"))
    fun getClaudeOAuthRefresh(): String? = PasswordSafe.instance.getPassword(creds("claude-oauth-refresh"))

    fun saveClaudeOAuth(access: String, refresh: String) {
        PasswordSafe.instance.setPassword(creds("claude-oauth-access"), access)
        PasswordSafe.instance.setPassword(creds("claude-oauth-refresh"), refresh)
        _state.claudeAuthMode = "oauth"
    }

    fun clearClaudeOAuth() {
        PasswordSafe.instance.setPassword(creds("claude-oauth-access"), null)
        PasswordSafe.instance.setPassword(creds("claude-oauth-refresh"), null)
        _state.claudeAuthMode = "apikey"
    }

    fun getAuthHeader(): String {
        val pat = getPat() ?: throw IllegalStateException(
            "No access token set. Click '🌐 Get Azure DevOps Token' in Settings."
        )
        val encoded = Base64.getEncoder().encodeToString(":$pat".toByteArray())
        return "Basic $encoded"
    }

    fun isConfigured(): Boolean =
        _state.organizationUrl.isNotBlank() &&
        _state.project.isNotBlank() &&
        _state.repository.isNotBlank() &&
        getPat() != null

    /**
     * Parses a full AzDO repo URL into (organizationUrl, project, repository).
     *
     * Handles all formats:
     *   https://your-server.example.com   (bare server — appends /DefaultCollection)
     *   https://your-server.example.com/DefaultCollection/MyProject/_git/MyRepo
     *   https://dev.azure.com/MyOrg/MyProject/_git/MyRepo
     */
    fun parseAndSaveRepoUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        _state.repoUrl = clean

        val gitIdx = clean.indexOf("/_git/")
        if (gitIdx < 0) {
            // No /_git/ — just a server URL. Ensure collection is appended for on-prem.
            val isCloud = clean.contains("dev.azure.com")
            _state.organizationUrl = if (!isCloud && !clean.contains("/DefaultCollection"))
                "$clean/DefaultCollection"
            else clean
            return
        }

        val repoName  = clean.substring(gitIdx + 6)
        val beforeGit = clean.substring(0, gitIdx)
        val lastSlash = beforeGit.lastIndexOf('/')

        _state.repository      = repoName
        _state.project         = if (lastSlash >= 0) beforeGit.substring(lastSlash + 1) else beforeGit
        _state.organizationUrl = if (lastSlash >= 0) beforeGit.substring(0, lastSlash) else beforeGit
    }

    private fun creds(key: String) = CredentialAttributes(
        generateServiceName("AzdoReviewer", key), key
    )

    companion object {
        fun getInstance(): AzdoSettings = service()
    }
}
