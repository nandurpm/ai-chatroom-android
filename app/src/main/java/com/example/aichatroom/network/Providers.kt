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

data class NvidiaMessage(val role: String, val content: String, val name: String? = null)
data class NvidiaRequest(val model: String, val messages: List<NvidiaMessage>,
    @SerializedName("max_tokens") val maxTokens: Int = 8192, val stream: Boolean = false,
    @SerializedName("chat_template_kwargs") val chatTemplateKwargs: Map<String, Boolean>? = null)
data class NvidiaResponse(val choices: List<NvidiaChoice>?)
data class NvidiaChoice(val message: NvidiaAnswer?)
data class NvidiaAnswer(val content: String?, val refusal: String?)
interface NvidiaApi {
    @POST("v1/chat/completions")
    suspend fun complete(@Header("Authorization") authorization: String, @Body request: NvidiaRequest): NvidiaResponse
}
data class Part(val text: String? = null, val thought: Boolean? = null)
data class Content(val role: String? = null, val parts: List<Part>)
data class ThinkingConfig(val thinkingLevel: String)
data class GenerationConfig(val maxOutputTokens: Int = 8192, val thinkingConfig: ThinkingConfig? = null)

// Only known model IDs get provider-specific controls; custom model IDs keep their defaults.
object ReplyTuning {
    fun nvidia(model: String, quick: Boolean): Map<String, Boolean>? =
        if (quick && model in setOf("nvidia/nemotron-3-super-120b-a12b", "qwen/qwen3.5-397b-a17b"))
            mapOf("enable_thinking" to false) else null
    fun gemini(model: String, quick: Boolean): ThinkingConfig? =
        if (quick && model == "gemini-3.6-flash") ThinkingConfig("MINIMAL") else null
    fun tokens(quick: Boolean, mode: Mode): Int =
        if (!quick) 8192 else if (mode == Mode.EXPERT) 4096 else 2048
    fun prompt(prompt: String, quick: Boolean): String = prompt + if (quick)
        "\nBe direct. For casual chat use a few sentences. For technical questions give the essential steps and details without repetition. Expand when the user asks." else ""
}
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
    fun nvidia(history: List<Message>) = history.filterNot { it.error }.map {
        // NVIDIA sees its own previous replies as assistant; peers remain named data.
        NvidiaMessage(if (it.speaker == Speaker.NVIDIA) "assistant" else "user",
            Gson().toJson(mapOf("speaker" to it.speaker.label, "text" to it.text)))
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
            404, 410 -> "Model not available. Update the model ID in Settings."
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
class NvidiaParticipant(private val api: NvidiaApi, private val key: () -> String,
    private val model: String,
    private val fallbackModel: String = "qwen/qwen3.5-397b-a17b",
    private val quickReplies: Boolean = true, private val maxTokens: Int = 2048) : AIParticipant {
    override val speaker = Speaker.NVIDIA
    override suspend fun getResponse(conversationHistory: List<Message>, systemPrompt: String): String {
        val secret = withContext(Dispatchers.IO) { key() }
        if (secret.isBlank()) throw UserFacingException("Add your NVIDIA API key in Settings. OpenAI credits are not needed.")
        val messages = listOf(NvidiaMessage("system", ReplyTuning.prompt(systemPrompt, quickReplies))) + Transcript.nvidia(conversationHistory)
        suspend fun request(modelId: String): String {
            val response = withRetry { api.complete("Bearer $secret", NvidiaRequest(modelId, messages, maxTokens = maxTokens,
                chatTemplateKwargs = ReplyTuning.nvidia(modelId, quickReplies))) }
            val answer = response.choices?.firstOrNull()?.message
            // Reasoning-only/empty content is not shown as a completed answer.
            return answer?.content?.takeIf { it.isNotBlank() } ?: answer?.refusal?.takeIf { it.isNotBlank() }
                ?: throw UserFacingException("NVIDIA returned no answer text. Try again or choose another model in Settings.")
        }
        return try { request(model) } catch (e: HttpException) {
            if (e.code() !in listOf(404, 410) || fallbackModel == model || fallbackModel.isBlank()) throw e
            // One model switch only; auth/quota/server errors do not switch models.
            try { "[NVIDIA fallback: $fallbackModel]\n\n" + request(fallbackModel) }
            catch (fallbackError: HttpException) {
                if (fallbackError.code() in listOf(404, 410)) throw UserFacingException(
                    "Both NVIDIA models are unavailable. Select an active primary/fallback model in Settings; the requested Qwen endpoint is deprecated.")
                throw fallbackError
            }
        }
    }
}
class GeminiParticipant(private val api: GeminiApi, private val key: () -> String,
    private val model: String, private val quickReplies: Boolean = true,
    private val maxTokens: Int = 2048) : AIParticipant {
    override val speaker = Speaker.GEMINI
    override suspend fun getResponse(conversationHistory: List<Message>, systemPrompt: String): String {
        val secret = withContext(Dispatchers.IO) { key() }
        if (secret.isBlank()) throw UserFacingException("Add your Gemini API key in Settings to invite Gemini.")
        val result = withRetry { api.generate(model, secret, GeminiRequest(Transcript.gemini(conversationHistory),
            Content(parts = listOf(Part(ReplyTuning.prompt(systemPrompt, quickReplies)))),
            GenerationConfig(maxTokens, ReplyTuning.gemini(model, quickReplies)))) }
        return result.candidates?.firstOrNull()?.content?.parts?.filterNot { it.thought == true }
            ?.mapNotNull { it.text }?.joinToString("\n")?.takeIf { it.isNotBlank() }
            ?: throw UserFacingException("Gemini returned no text or blocked this response. Try rephrasing your message.")
    }
}
class ProviderFactory {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).callTimeout(200, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private fun retrofit(url: String) = Retrofit.Builder().baseUrl(url).client(client)
        .addConverterFactory(GsonConverterFactory.create()).build()
    private val nvidia = retrofit("https://integrate.api.nvidia.com/").create(NvidiaApi::class.java)
    private val gemini = retrofit("https://generativelanguage.googleapis.com/").create(GeminiApi::class.java)
    fun create(preferences: Preferences, key: (Speaker) -> String): List<AIParticipant> = buildList {
        if (preferences.nvidiaEnabled) add(NvidiaParticipant(nvidia, { key(Speaker.NVIDIA) }, preferences.nvidiaModel, preferences.nvidiaFallbackModel,
            preferences.quickReplies, ReplyTuning.tokens(preferences.quickReplies, preferences.mode)))
        if (preferences.geminiEnabled) add(GeminiParticipant(gemini, { key(Speaker.GEMINI) }, preferences.geminiModel,
            preferences.quickReplies, ReplyTuning.tokens(preferences.quickReplies, preferences.mode)))
    }
}
