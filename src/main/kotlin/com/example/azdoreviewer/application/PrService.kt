package com.example.azdoreviewer.application

import com.example.azdoreviewer.domain.FileDiff
import com.example.azdoreviewer.domain.PullRequest
import com.example.azdoreviewer.infrastructure.azdo.AzdoHttpClient
import com.example.azdoreviewer.infrastructure.cache.PrCacheService
import com.example.azdoreviewer.settings.AzdoSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
class PrService {

    private val settings get() = AzdoSettings.getInstance()
    private val cache    get() = service<PrCacheService>()

    // Single shared client so the PR->repo map survives between list and diff calls
    private val client = AzdoHttpClient(AzdoSettings.getInstance())

    fun getAssignedPrs(forceRefresh: Boolean = false): List<PullRequest> {
        if (forceRefresh) cache.invalidateAll()
        return cache.getPrList(cacheKey()) { client.fetchAssignedPrs() }
    }

    fun getPrDiffs(prId: Int, forceRefresh: Boolean = false): List<FileDiff> {
        if (forceRefresh) cache.invalidatePr(prId)
        // Ensure the client's PR->repo map is populated (it may be empty after an IDE restart
        // even if the PR list is cached) by refreshing the list first.
        if (!client.knowsPr(prId)) {
            client.fetchAssignedPrs()
        }
        return cache.getDiffs(prId) { client.fetchPrDiffs(prId) }
    }

    fun testConnection(): Boolean = client.testConnection()

    private val avatarCache = mutableMapOf<String, ByteArray?>()
    /** Cached avatar bytes for an author image URL. */
    fun getAvatar(imageUrl: String): ByteArray? {
        if (imageUrl.isBlank()) return null
        return avatarCache.getOrPut(imageUrl) { client.fetchAvatar(imageUrl) }
    }

    /** Posts a comment thread; ensures the PR->repo map is populated first. */
    fun createCommentThread(prId: Int, request: com.example.azdoreviewer.infrastructure.azdo.dto.CommentThreadRequest) {
        if (!client.knowsPr(prId)) client.fetchAssignedPrs()
        client.createCommentThread(prId, request)
    }

    private fun cacheKey(): String = settings.state.organizationUrl
}
