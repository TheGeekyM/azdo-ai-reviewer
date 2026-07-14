package com.example.azdoreviewer.infrastructure.azdo

import com.example.azdoreviewer.domain.*
import com.example.azdoreviewer.infrastructure.azdo.dto.*
import com.example.azdoreviewer.settings.AzdoSettings
import com.intellij.util.net.ssl.CertificateManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AzdoHttpClient(private val settings: AzdoSettings) : AzdoClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .sslSocketFactory(
            CertificateManager.getInstance().sslContext.socketFactory,
            CertificateManager.getInstance().trustManager
        )
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val authHeader = try {
                settings.getAuthHeader()
            } catch (e: IllegalStateException) {
                throw AzdoException.NotConfigured(e.message ?: "Not authenticated.")
            }
            val req = chain.request().newBuilder()
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .build()
            chain.proceed(req)
        }
        .build()

    // base = collection URL, e.g. https://your-server.example.com/DefaultCollection  or  https://dev.azure.com/MyOrg
    private val base get() = settings.state.organizationUrl.trimEnd('/')

    private val v = "api-version=5.0"

    // Cache of PR id -> (project, repositoryId) so diff/comment calls target the right repo
    private val prRepoMap = mutableMapOf<Int, Pair<String, String>>()

    fun knowsPr(prId: Int): Boolean = prRepoMap.containsKey(prId)

    /** Downloads an author avatar image (PNG/JPG bytes) with auth. Returns null on failure. */
    fun fetchAvatar(imageUrl: String): ByteArray? {
        if (imageUrl.isBlank()) return null
        return runCatching {
            http.newCall(Request.Builder().url(imageUrl).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()
    }

    override fun testConnection(): Boolean {
        val body = get("$base/_apis/connectionData")
        return body.contains("authenticatedUser") || body.contains("instanceId")
    }

    override fun getCurrentUserId(): String {
        val body = get("$base/_apis/connectionData")
        return json.decodeFromString<ConnectionDataResponse>(body).authenticatedUser?.id ?: ""
    }

    /**
     * Lists active PRs across the whole collection in a single call.
     * Verified endpoint: {base}/_apis/git/pullrequests?status=active
     */
    override fun fetchAssignedPrs(): List<PullRequest> {
        val body = get("$base/_apis/git/pullrequests?status=active&\$top=100&$v")
        val prs = json.decodeFromString<PrListResponse>(body).value.map { it.toDomain() }
        prRepoMap.clear()
        prs.forEach { pr ->
            prRepoMap[pr.id] = pr.projectName.ifBlank { pr.repositoryName } to pr.repositoryId
        }
        return prs
    }

    override fun fetchPrDiffs(prId: Int): List<FileDiff> {
        val (project, repoId) = prRepoMap[prId] ?: return emptyList()

        val iterBody = get(
            "$base/$project/_apis/git/repositories/$repoId/pullrequests/$prId/iterations?$v"
        )
        val iterations = json.decodeFromString<IterationListResponse>(iterBody).value
        val latestIteration = iterations.maxByOrNull { it.id } ?: return emptyList()

        val sourceCommit = latestIteration.sourceRefCommit?.commitId ?: ""
        val baseCommit   = latestIteration.commonRefCommit?.commitId
            ?: latestIteration.targetRefCommit?.commitId ?: ""

        val changesBody = get(
            "$base/$project/_apis/git/repositories/$repoId/pullrequests/$prId" +
            "/iterations/${latestIteration.id}/changes?$v"
        )
        val changes = json.decodeFromString<IterationChangesResponse>(changesBody)

        return changes.changeEntries
            .filter { it.item.path != null }   // deletes/folders can send "path":null
            .take(settings.state.maxFilesPerReview)
            .mapNotNull { entry ->
                runCatching { buildFileDiff(project, repoId, entry, sourceCommit, baseCommit) }.getOrNull()
            }
    }

    private fun buildFileDiff(
        project: String, repoId: String, entry: ChangeEntryDto,
        sourceCommit: String, baseCommit: String
    ): FileDiff {
        val itemPath = entry.item.path ?: ""
        val changeType = when (entry.changeType.lowercase()) {
            "add"    -> ChangeType.ADD
            "delete" -> ChangeType.DELETE
            "rename" -> ChangeType.RENAME
            else     -> ChangeType.EDIT
        }

        // New content at the PR source commit (empty for deletes)
        val newContent = if (changeType == ChangeType.DELETE) "" else
            fetchContentAtCommit(project, repoId, itemPath, sourceCommit) ?: ""

        // Old content at the merge base (empty for adds / when base file doesn't exist)
        val oldContent = if (changeType == ChangeType.ADD) "" else
            fetchContentAtCommit(project, repoId, itemPath, baseCommit) ?: ""

        val lines = newContent.lines().mapIndexed { i, line ->
            DiffLine(
                type       = if (changeType == ChangeType.ADD) LineType.ADDED else LineType.CONTEXT,
                lineNumber = i + 1,
                content    = line
            )
        }
        val hunk = DiffHunk(
            oldStart = 1, oldCount = if (changeType == ChangeType.ADD) 0 else lines.size,
            newStart = 1, newCount = lines.size,
            lines    = lines
        )
        return FileDiff(
            path         = itemPath,
            originalPath = if (changeType == ChangeType.RENAME) entry.item.originalObjectId else null,
            changeType   = changeType,
            hunks        = listOf(hunk),
            oldContent   = oldContent,
            newContent   = newContent
        )
    }

    private fun fetchContentAtCommit(project: String, repoId: String, path: String, commitSha: String): String? {
        if (commitSha.isBlank()) return null
        val encPath = java.net.URLEncoder.encode(path, "UTF-8")
        return runCatching {
            get(
                "$base/$project/_apis/git/repositories/$repoId/items" +
                "?path=$encPath&versionDescriptor.version=$commitSha&versionDescriptor.versionType=commit&\$format=text&$v"
            )
        }.getOrNull()
    }

    override fun fetchFileContent(repoId: String, path: String, commitSha: String): String? = null // unused

    override fun fetchWorkItem(workItemId: Int): WorkItem {
        val body = get("$base/_apis/wit/workitems/$workItemId?$v")
        return json.decodeFromString<WorkItemDto>(body).toDomain()
    }

    override fun fetchLinkedWorkItemIds(prId: Int): List<Int> {
        val (project, repoId) = prRepoMap[prId] ?: return emptyList()
        val body = get("$base/$project/_apis/git/repositories/$repoId/pullrequests/$prId/workitems?$v")
        return json.decodeFromString<PrWorkItemRefsResponse>(body).value.mapNotNull { it.id.toIntOrNull() }
    }

    override fun createCommentThread(prId: Int, request: CommentThreadRequest) {
        val (project, repoId) = prRepoMap[prId] ?: throw AzdoException.NotFound("PR $prId not in current list. Refresh first.")
        val body = json.encodeToString(request)
        post(
            "$base/$project/_apis/git/repositories/$repoId/pullrequests/$prId/threads?$v",
            body
        )
    }

    private fun get(url: String): String {
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        return response.use { r ->
            when {
                r.code == 401 -> throw AzdoException.Unauthorized(
                    "HTTP 401 Unauthorized\nURL: $url\n\n" +
                    "• Server URL must include the collection (auto-added: /DefaultCollection)\n" +
                    "• PAT scopes needed: Code (Read), Pull Request Threads (Read & Write)\n" +
                    "• PAT may be expired"
                )
                r.code == 404 -> throw AzdoException.NotFound("HTTP 404\nURL: $url")
                !r.isSuccessful -> throw AzdoException.ApiError(r.code, "HTTP ${r.code}\nURL: $url\n${r.message}")
                else -> {
                    val body = r.body?.string() ?: ""
                    if (body.trimStart().startsWith("<"))
                        throw AzdoException.Unauthorized(
                            "Got HTML instead of JSON from:\n$url\n\nServer URL is likely wrong."
                        )
                    body
                }
            }
        }
    }

    override fun votePr(prId: Int, vote: Int) {
        val (project, repoId) = prRepoMap[prId] ?: throw AzdoException.NotFound("PR $prId not in current list. Refresh first.")
        val userId = getCurrentUserId()
        put(
            "$base/$project/_apis/git/repositories/$repoId/pullrequests/$prId/reviewers/$userId?$v",
            """{"vote": $vote}"""
        )
    }

    override fun completePr(prId: Int) {
        val (project, repoId) = prRepoMap[prId] ?: throw AzdoException.NotFound("PR $prId not in current list. Refresh first.")
        val prUrl = "$base/$project/_apis/git/repositories/$repoId/pullrequests/$prId?$v"
        // Need lastMergeSourceCommit to complete.
        val pr = json.decodeFromString<PrCompletionDto>(get(prUrl))
        val commitId = pr.lastMergeSourceCommit?.commitId
            ?: throw AzdoException.ApiError(409, "PR has no merge commit yet (conflicts or not ready to merge).")

        patch(prUrl, """{"status":"completed","lastMergeSourceCommit":{"commitId":"$commitId"}}""")
    }

    private fun post(url: String, body: String): String {
        val reqBody = body.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(reqBody).build()
        val response = http.newCall(request).execute()
        return response.use { r ->
            if (!r.isSuccessful) throw AzdoException.ApiError(r.code, "HTTP ${r.code}\nURL: $url\n${r.message}")
            r.body?.string() ?: ""
        }
    }

    private fun put(url: String, body: String): String {
        val reqBody = body.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).put(reqBody).build()
        val response = http.newCall(request).execute()
        return response.use { r ->
            if (!r.isSuccessful) throw AzdoException.ApiError(r.code, "HTTP ${r.code}\nURL: $url\n${r.message}")
            r.body?.string() ?: ""
        }
    }

    private fun patch(url: String, body: String): String {
        val reqBody = body.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).patch(reqBody).build()
        val response = http.newCall(request).execute()
        return response.use { r ->
            if (!r.isSuccessful) throw AzdoException.ApiError(r.code, "HTTP ${r.code}\nURL: $url\n${r.message}")
            r.body?.string() ?: ""
        }
    }
}
