package com.example.azdoreviewer.infrastructure.ai.local

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

class OllamaProvider(
    private val model: String = "llama3",
    private val baseUrl: String = "http://localhost:11434"
) : AiReviewProvider {

    override val providerName = "ollama"

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .readTimeout(300, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun reviewFile(request: FileReviewRequest): List<ReviewComment> =
        withContext(Dispatchers.IO) {
            ReviewResponseParser.parse(generate(PromptBuilder.build(request)), request.filePath)
        }

    override suspend fun reviewPullRequest(request: PrReviewRequest): List<ReviewComment> =
        withContext(Dispatchers.IO) {
            ReviewResponseParser.parse(generate(PromptBuilder.buildPr(request)), request.files.firstOrNull()?.path ?: "")
        }

    override suspend fun complete(prompt: String, maxTokens: Int): String =
        withContext(Dispatchers.IO) { generate(prompt) }

    private fun generate(prompt: String): String {
        val body = json.encodeToString(OllamaRequest(model = model, prompt = prompt, stream = false))
        val httpRequest = Request.Builder()
            .url("$baseUrl/api/generate")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val raw = http.newCall(httpRequest).execute().use { r ->
            if (!r.isSuccessful) throw AiException("Ollama error ${r.code}: ${r.message}")
            r.body?.string() ?: throw AiException("Empty response from Ollama")
        }
        return json.decodeFromString<OllamaResponse>(raw).response
    }

    override suspend fun ping(): String = withContext(Dispatchers.IO) {
        val body = json.encodeToString(OllamaRequest(model = model, prompt = "Reply with OK", stream = false))
        val req = Request.Builder()
            .url("$baseUrl/api/generate")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val s = r.body?.string() ?: ""
            if (!r.isSuccessful) throw AiException("Ollama error ${r.code}: ${s.take(200)}")
            "OK"
        }
    }

    @Serializable
    private data class OllamaRequest(val model: String, val prompt: String, val stream: Boolean)

    @Serializable
    private data class OllamaResponse(val response: String = "")
}
