package com.example.azdoreviewer.infrastructure.cache

import com.example.azdoreviewer.domain.FileDiff
import com.example.azdoreviewer.domain.PullRequest
import com.example.azdoreviewer.domain.ReviewComment
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.intellij.openapi.components.Service
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class PrCacheService {

    private val prListCache: Cache<String, List<PullRequest>> = Caffeine.newBuilder()
        .expireAfterWrite(2, TimeUnit.MINUTES)
        .maximumSize(10)
        .build()

    private val diffCache: Cache<Int, List<FileDiff>> = Caffeine.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .maximumSize(50)
        .build()

    private val reviewCache: Cache<Int, List<ReviewComment>> = Caffeine.newBuilder()
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .maximumSize(50)
        .build()

    fun getPrList(key: String, loader: () -> List<PullRequest>): List<PullRequest> =
        prListCache.get(key) { loader() }!!

    fun getDiffs(prId: Int, loader: () -> List<FileDiff>): List<FileDiff> =
        diffCache.get(prId) { loader() }!!

    fun getReview(prId: Int, loader: () -> List<ReviewComment>): List<ReviewComment> =
        reviewCache.get(prId) { loader() }!!

    fun invalidatePr(prId: Int) {
        diffCache.invalidate(prId)
        reviewCache.invalidate(prId)
    }

    fun invalidateAll() {
        prListCache.invalidateAll()
        diffCache.invalidateAll()
        reviewCache.invalidateAll()
    }
}
