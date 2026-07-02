package com.example.azdoreviewer.infrastructure.ai.openai

import com.example.azdoreviewer.domain.ReviewComment
import com.example.azdoreviewer.infrastructure.ai.AiReviewProvider
import com.example.azdoreviewer.infrastructure.ai.FileReviewRequest
import com.example.azdoreviewer.infrastructure.ai.PrReviewRequest
import com.example.azdoreviewer.infrastructure.ai.PromptBuilder
import com.example.azdoreviewer.infrastructure.ai.ReviewResponseParser
import com.example.azdoreviewer.infrastructure.azdo.AiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenAiProvider(
    private val apiKey: String,
    private val model: String = "gpt-4o"
) : AiReviewProvider {

    override val providerName = "openai"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun reviewFile(request: FileReviewRequest): List<ReviewComment> =
        withContext(Dispatchers.IO) {
            val content = chat(PromptBuilder.build(request)) ?: return@withContext emptyList()
            ReviewResponseParser.parse(content, request.filePath)
        }

    override suspend fun reviewPullRequest(request: PrReviewRequest): List<ReviewComment> =
        withContext(Dispatchers.IO) {
            val content = chat(PromptBuilder.buildPr(request)) ?: return@withContext emptyList()
            ReviewResponseParser.parse(content, request.files.firstOrNull()?.path ?: "")
        }

    override suspend fun complete(prompt: String, maxTokens: Int): String =
        withContext(Dispatchers.IO) { chat(prompt) ?: "" }

    private fun chat(prompt: String): String? {
        val body = json.encodeToString(OpenAiRequest(
            model    = model,
            messages = listOf(OpenAiMessage(role = "user", content = prompt))
        ))
        val httpRequest = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .build()
        val raw = http.newCall(httpRequest).execute().use { r ->
            if (!r.isSuccessful) throw AiException("OpenAI error ${r.code}: ${r.message}")
            r.body?.string() ?: throw AiException("Empty response from OpenAI")
        }
        return json.decodeFromString<OpenAiResponse>(raw).choices.firstOrNull()?.message?.content
    }

    override suspend fun ping(): String = withContext(Dispatchers.IO) {
        val body = json.encodeToString(OpenAiRequest(
            model = model,
            messages = listOf(OpenAiMessage("user", "Reply with OK")),
            responseFormat = null
        ))
        val req = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .build()
        http.newCall(req).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) throw AiException("OpenAI error ${r.code}: ${s.take(200)}")
            "OK"
        }
    }

    @Serializable
    private data class OpenAiRequest(
        val model: String,
        val messages: List<OpenAiMessage>,
        @SerialName("response_format") val responseFormat: Map<String, String>? = mapOf("type" to "json_object")
    )

    @Serializable
    private data class OpenAiMessage(val role: String, val content: String)

    @Serializable
    private data class OpenAiResponse(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: OpenAiMessage)
}
