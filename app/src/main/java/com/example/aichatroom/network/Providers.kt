package com.example.aichatroom.network

import com.example.aichatroom.domain.*
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class OpenMessage(val role: String, val content: String, val name: String? = null)
data class OpenRequest(val model: String, val messages: List<OpenMessage>,
    @SerializedName("max_completion_tokens") val maxTokens: Int = 4096, val store: Boolean = false)
data class OpenResponse(val choices: List<OpenChoice>?)
data class OpenChoice(val message: OpenAnswer?)
data class OpenAnswer(val content: String?, val refusal: String?)
interface OpenApi {
    @POST("v1/chat/completions")
    suspend fun complete(@Header("Authorization") authorization: String, @Body request: OpenRequest): OpenResponse
}
data class Part(val text: String? = null, val thought: Boolean? = null)
data class Content(val role: String? = null, val parts: List<Part>)
data class GenerationConfig(val maxOutputTokens: Int = 8192)
data class GeminiRequest(val contents: List<Content>, val systemInstruction: Content,
    val generationConfig: GenerationConfig = GenerationConfig())
data class Candidate(val content: Content?, val finishReason: String?)
data class GeminiResponse(val candidates: List<Candidate>?)
interface GeminiApi {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generate(@Path("model") model: String, @Header("x-goog-api-key") key: String,
        @Body request: GeminiRequest): GeminiResponse
}
class UserFacingException(message: String) : Exception(message)

object Transcript {
    fun open(history: List<Message>) = history.filterNot { it.error }.map {
        // Gemini is a separate participant, not a prior OpenAI assistant response.
        OpenMessage(if (it.speaker == Speaker.CHATGPT) "assistant" else "user", it.text,
            when (it.speaker) { Speaker.USER -> "Human"; Speaker.CHATGPT -> "ChatGPT"; Speaker.GEMINI -> "Gemini" })
    }
    fun gemini(history: List<Message>): List<Content> {
        val result = mutableListOf<Content>()
        history.filterNot { it.error }.forEach {
            val role = if (it.speaker == Speaker.GEMINI) "model" else "user"
            // JSON encoding keeps speaker names separate from message text.
            val text = Gson().toJson(mapOf("speaker" to it.speaker.label, "text" to it.text))
            val previous = result.lastOrNull()
            if (previous?.role == role) result[result.lastIndex] = previous.copy(parts = previous.parts + Part(text))
            else result += Content(role, listOf(Part(text)))
        }
        return result
    }
}
object ApiErrors {
    fun describe(e: Exception): String = when (e) {
        is UserFacingException -> e.message ?: "Unable to get a reply."
        is HttpException -> when (e.code()) {
            401, 403 -> "API key rejected or access denied. Open Settings to check your key and model access."
            429 -> "Rate limit or quota reached. Wait, check API billing/quota, then try again."
            400, 413 -> "Request rejected. Check the model in Settings; if this chat is too long, start a new chat."
            404 -> "Model not available. Update the model ID in Settings."
            in 500..599 -> "AI service temporarily unavailable. Please try again shortly."
            else -> "API request failed (HTTP ${e.code()}). Please try again."
        }
        is IOException -> "Connection failed or timed out. Check your network and try again."
        else -> "Unable to complete this reply. Please try again."
    }
}
// At most 3 attempts. Cancellation is never retried. Never display/log raw HTTP bodies.
suspend fun <T> withRetry(block: suspend () -> T): T {
    repeat(3) { attempt ->
        try { return block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val retryable = e is IOException || (e is HttpException && (e.code() == 429 || e.code() in 500..599))
            if (!retryable || attempt == 2) throw e
            val header = (e as? HttpException)?.response()?.headers()?.get("Retry-After")
            val seconds = header?.toLongOrNull() ?: header?.let {
                runCatching { (ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()) / 1000 }.getOrNull()
            }
            // Long server cooldowns are surfaced rather than retried too early.
            if (seconds != null && seconds > 30) throw e
            delay(if (seconds != null) seconds.coerceAtLeast(1) * 1000 else (1000L shl attempt))
        }
    }
    error("Unreachable")
}
class OpenAiParticipant(private val api: OpenApi, private val key: () -> String,
    private val model: String) : AIParticipant {
    override val speaker = Speaker.CHATGPT
    override suspend fun getResponse(conversationHistory: List<Message>, systemPrompt: String): String {
        val secret = withContext(Dispatchers.IO) { key() }
        if (secret.isBlank()) throw UserFacingException("Add your OpenAI API key in Settings to invite ChatGPT.")
        val result = withRetry { api.complete("Bearer $secret", OpenRequest(model,
            listOf(OpenMessage("system", systemPrompt)) + Transcript.open(conversationHistory))) }
        val answer = result.choices?.firstOrNull()?.message
        return answer?.content?.takeIf { it.isNotBlank() } ?: answer?.refusal?.takeIf { it.isNotBlank() }
            ?: throw UserFacingException("ChatGPT returned no text. Try rephrasing your message.")
    }
}
class GeminiParticipant(private val api: GeminiApi, private val key: () -> String,
    private val model: String) : AIParticipant {
    override val speaker = Speaker.GEMINI
    override suspend fun getResponse(conversationHistory: List<Message>, systemPrompt: String): String {
        val secret = withContext(Dispatchers.IO) { key() }
        if (secret.isBlank()) throw UserFacingException("Add your Gemini API key in Settings to invite Gemini.")
        val result = withRetry { api.generate(model, secret, GeminiRequest(Transcript.gemini(conversationHistory),
            Content(parts = listOf(Part(systemPrompt))))) }
        return result.candidates?.firstOrNull()?.content?.parts?.filterNot { it.thought == true }
            ?.mapNotNull { it.text }?.joinToString("\n")?.takeIf { it.isNotBlank() }
            ?: throw UserFacingException("Gemini returned no text or blocked this response. Try rephrasing your message.")
    }
}
class ProviderFactory {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private fun retrofit(url: String) = Retrofit.Builder().baseUrl(url).client(client)
        .addConverterFactory(GsonConverterFactory.create()).build()
    private val open = retrofit("https://api.openai.com/").create(OpenApi::class.java)
    private val gemini = retrofit("https://generativelanguage.googleapis.com/").create(GeminiApi::class.java)
    fun create(preferences: Preferences, key: (Speaker) -> String): List<AIParticipant> = buildList {
        if (preferences.chatGptEnabled) add(OpenAiParticipant(open, { key(Speaker.CHATGPT) }, preferences.openAiModel))
        if (preferences.geminiEnabled) add(GeminiParticipant(gemini, { key(Speaker.GEMINI) }, preferences.geminiModel))
    }
}
