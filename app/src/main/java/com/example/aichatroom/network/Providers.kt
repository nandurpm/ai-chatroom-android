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

/** OpenAI-compatible JSON DTOs; Gson omits null optional overrides. */
data class OpenAiMessage(val role: String, val content: String, val name: String? = null)
data class OpenAiRequest(val model: String, val messages: List<OpenAiMessage>,
    @SerializedName("max_tokens") val maxTokens: Int = 8192, val stream: Boolean = false,
    @SerializedName("chat_template_kwargs") val chatTemplateKwargs: Map<String, Boolean>? = null)
data class OpenAiResponse(val choices: List<OpenAiChoice>?)
data class OpenAiChoice(val message: OpenAiAnswer?)
data class OpenAiAnswer(val content: String?, val refusal: String?)
interface OpenAiApi {
    @POST("chat/completions")
    suspend fun complete(@HeaderMap headers: Map<String, String>, @Body request: OpenAiRequest): OpenAiResponse
}
/** Gemini uses role/parts contents rather than chat-completions messages. */
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
    suspend fun generate(@Path("model") model: String, @HeaderMap headers: Map<String, String>,
        @Body request: GeminiRequest): GeminiResponse
}
class UserFacingException(message: String) : Exception(message)

/** Encodes names as JSON data and maps only this agent's own stable ID to the assistant role. */
object Transcript {
    fun openAi(history: List<Message>, self: AgentProfile = AgentProfile.NVIDIA) = history.filterNot { it.error }.map {
        // This agent sees its own previous replies as assistant; peers remain named data.
        OpenAiMessage(if (it.speaker.id == self.id) "assistant" else "user",
            Gson().toJson(mapOf("speaker" to it.speaker.label, "text" to it.text)))
    }
    fun gemini(history: List<Message>, self: AgentProfile = AgentProfile.GEMINI): List<Content> {
        val result = mutableListOf<Content>()
        history.filterNot { it.error }.forEach {
            val role = if (it.speaker.id == self.id) "model" else "user"
            // JSON encoding keeps speaker names separate from message text.
            val text = Gson().toJson(mapOf("speaker" to it.speaker.label, "text" to it.text))
            val previous = result.lastOrNull()
            if (previous?.role == role) result[result.lastIndex] = previous.copy(parts = previous.parts + Part(text))
            else result += Content(role, listOf(Part(text)))
        }
        return result
    }
}
/** Sanitized diagnostics never expose raw HTTP bodies or credentials. */
object ApiErrors {
    fun describe(e: Exception): String = when (e) {
        is ContextBudgetException -> e.message ?: "Check the context budget in Settings."
        is IllegalArgumentException -> "Invalid provider configuration. Check Settings."
        is UserFacingException -> e.message ?: "Unable to get a reply."
        is HttpException -> when (e.code()) {
            401, 403 -> "API key rejected or access denied. Open Settings to check your key and model access."
            402 -> "Provider requires billing or available credits. For Cerebras, check the Cloud Console credit balance/payment setup, then try again."
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
/** One implementation serves all OpenAI-compatible endpoints, including NVIDIA. */
class OpenAiCompatibleParticipant(private val api: OpenAiApi, override val speaker: AgentProfile,
    private val key: () -> String, private val quickReplies: Boolean = true,
    private val maxTokens: Int = 2048) : AIParticipant {
    private val config get() = speaker.providerConfig
    override suspend fun getResponse(conversationHistory: List<Message>, systemPrompt: String): String {
        val secret = withContext(Dispatchers.IO) { key() }
        val headers = authHeaders(config, secret)
        val budget = config.maxTokens ?: maxTokens
        val prompt = ReplyTuning.prompt(systemPrompt, quickReplies)
        val history = ContextBudget.fit(conversationHistory, prompt, config.contextTokens, budget)
        val messages = listOf(OpenAiMessage("system", prompt)) + Transcript.openAi(history, speaker)
        suspend fun request(model: String): String {
            val thinking = config.thinking?.let { mapOf("enable_thinking" to it) }
                ?: ReplyTuning.nvidia(model, quickReplies)
            val result = withRetry { api.complete(headers, OpenAiRequest(model, messages,
                maxTokens = budget, chatTemplateKwargs = thinking)) }
            val answer = result.choices?.firstOrNull()?.message
            return answer?.content?.takeIf { it.isNotBlank() } ?: answer?.refusal?.takeIf { it.isNotBlank() }
                ?: throw UserFacingException("${speaker.label} returned no answer text. Check the model in Settings.")
        }
        return try { request(config.modelId) } catch (e: HttpException) {
            if (e.code() !in listOf(404, 410) || config.fallbackModel.isBlank() || config.fallbackModel == config.modelId) throw e
            // Only model-not-found errors switch once; retries and cancellation keep their existing behavior.
            try { "[${speaker.label} fallback: ${config.fallbackModel}]\n\n" + request(config.fallbackModel) }
            catch (fallback: HttpException) {
                if (fallback.code() in listOf(404, 410)) throw UserFacingException("Both ${speaker.label} models are unavailable. Change the model IDs in Settings.")
                throw fallback
            }
        }
    }
}

/** Headers are attached per request, so no provider or GitHub token can leak to another client. */
fun authHeaders(config: ProviderConfig, secret: String): Map<String, String> {
    if (config.authStyle == AuthStyle.NONE) return emptyMap()
    if (secret.isBlank()) throw UserFacingException("Add your API key in Settings.")
    return when (config.authStyle) {
        AuthStyle.BEARER -> mapOf("Authorization" to "Bearer $secret")
        AuthStyle.GOOGLE_KEY -> mapOf("x-goog-api-key" to secret)
        AuthStyle.CUSTOM -> mapOf(config.customHeader to secret)
        AuthStyle.NONE -> emptyMap()
    }
}
/** Gemini adapter shares the same profile, budget and auth configuration as compatible providers. */
class GeminiParticipant(private val api: GeminiApi, private val key: () -> String,
    private val model: String, private val quickReplies: Boolean = true,
    private val maxTokens: Int = 2048,
    override val speaker: AgentProfile = AgentProfile.GEMINI) : AIParticipant {
    override suspend fun getResponse(conversationHistory: List<Message>, systemPrompt: String): String {
        val secret = withContext(Dispatchers.IO) { key() }
        if (secret.isBlank()) throw UserFacingException("Add your Gemini API key in Settings to invite Gemini.")
        val config = speaker.providerConfig
        val budget = config.maxTokens ?: maxTokens
        val prompt = ReplyTuning.prompt(systemPrompt, quickReplies)
        val history = ContextBudget.fit(conversationHistory, prompt, config.contextTokens, budget)
        val result = withRetry { api.generate(model, authHeaders(config, secret), GeminiRequest(Transcript.gemini(history, speaker),
            Content(parts = listOf(Part(prompt))),
            GenerationConfig(budget, config.thinkingLevel?.let { ThinkingConfig(it) } ?: ReplyTuning.gemini(model, quickReplies)))) }
        return result.candidates?.firstOrNull()?.content?.parts?.filterNot { it.thought == true }
            ?.mapNotNull { it.text }?.joinToString("\n")?.takeIf { it.isNotBlank() }
            ?: throw UserFacingException("Gemini returned no text or blocked this response. Try rephrasing your message.")
    }
}
/** Clients never follow redirects, especially when a custom endpoint receives a secret. */
class ProviderFactory {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private fun retrofit(url: String) = Retrofit.Builder().baseUrl(url.trimEnd('/') + "/").client(client)
        .addConverterFactory(GsonConverterFactory.create()).build()
    fun create(profiles: List<AgentProfile>, preferences: Preferences, key: (AgentProfile) -> String): List<AIParticipant> =
        profiles.filter { it.enabled && !it.archived && it.id != AgentProfile.USER.id }.map { a ->
            a.providerConfig.validate()
            val api = retrofit(a.providerConfig.baseUrl)
            val tokens = ReplyTuning.tokens(preferences.quickReplies, preferences.mode)
            when (a.providerConfig.shape) {
                RequestShape.OPENAI -> OpenAiCompatibleParticipant(api.create(OpenAiApi::class.java), a, { key(a) }, preferences.quickReplies, tokens)
                RequestShape.GEMINI -> GeminiParticipant(api.create(GeminiApi::class.java), { key(a) }, a.providerConfig.modelId, preferences.quickReplies, tokens, a)
            }
        }
}
