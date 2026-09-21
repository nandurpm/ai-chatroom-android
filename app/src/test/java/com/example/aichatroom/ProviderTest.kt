package com.example.aichatroom

import com.example.aichatroom.domain.*
import com.example.aichatroom.network.*
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ProviderTest {
    private fun retrofit(server: MockWebServer) = Retrofit.Builder().baseUrl(server.url("/v1/"))
        .addConverterFactory(GsonConverterFactory.create()).build()
    private val history = listOf(Message(speaker = AgentProfile.USER, text = "Hi"),
        Message(speaker = AgentProfile.NVIDIA, text = "First response"))
    @Test fun geminiReceivesNamedPeerAndSystemPrompt() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"private thought","thought":true},{"text":"Hello"}]}}]}"""))
            val ai = GeminiParticipant(Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(GsonConverterFactory.create()).build().create(GeminiApi::class.java), { "fake-test-key" }, "gemini-3.6-flash")
            assertEquals("Hello", ai.getResponse(history, "Expert prompt"))
            val request = server.takeRequest()
            assertEquals("/v1beta/models/gemini-3.6-flash:generateContent", request.path)
            assertEquals("fake-test-key", request.getHeader("x-goog-api-key"))
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("MINIMAL", json["generationConfig"].asJsonObject["thinkingConfig"].asJsonObject["thinkingLevel"].asString)
            assertTrue(json["systemInstruction"].toString().contains("Expert prompt"))
            assertTrue(json["contents"].toString().contains("NVIDIA"))
            assertTrue(json["contents"].toString().contains("First response"))
        } finally { server.shutdown() }
    }
    @Test fun nvidiaUsesChatEndpointAndNamedPeer() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Hello"}}]}"""))
            val ai = NvidiaParticipant(retrofit(server).create(OpenAiApi::class.java), { "fake-test-key" }, "nvidia/nemotron-3-super-120b-a12b")
            val peer = listOf(Message(speaker = AgentProfile.USER, text = "Hi"), Message(speaker = AgentProfile.GEMINI, text = "Peer answer"))
            assertEquals("Hello", ai.getResponse(peer, "System prompt"))
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer fake-test-key", request.getHeader("Authorization"))
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            val last = json["messages"].asJsonArray.last().asJsonObject
            assertEquals("Gemini", JsonParser.parseString(last["content"].asString).asJsonObject["speaker"].asString)
            assertEquals(2048, json["max_tokens"].asInt)
            assertFalse(json["chat_template_kwargs"].asJsonObject["enable_thinking"].asBoolean)
            assertFalse(json.has("max_completion_tokens"))
            assertFalse(json.has("store"))
            assertEquals("user", last["role"].asString)
        } finally { server.shutdown() }
    }
    @Test fun missingKeyNeverCallsNetwork() = runTest {
        val server = MockWebServer()
        try {
            val ai = NvidiaParticipant(retrofit(server).create(OpenAiApi::class.java), { "" }, "nvidia/nemotron-3-super-120b-a12b")
            try { ai.getResponse(history, "prompt"); fail("Expected missing key") }
            catch (e: UserFacingException) { assertTrue(e.message!!.contains("Settings")) }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun rateLimitRetriesThenSucceeds() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "1"))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Recovered"}}]}"""))
            val ai = NvidiaParticipant(retrofit(server).create(OpenAiApi::class.java), { "fake" }, "nvidia/nemotron-3-super-120b-a12b")
            assertEquals("Recovered", ai.getResponse(history, "prompt"))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun invalidKeyIsNotRetried() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            val ai = NvidiaParticipant(retrofit(server).create(OpenAiApi::class.java), { "fake" }, "nvidia/nemotron-3-super-120b-a12b")
            try { ai.getResponse(history, "prompt"); fail("Expected HTTP error") }
            catch (e: HttpException) { assertEquals(401, e.code()) }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun retryIsBoundedAndCancellationPassesThrough() = runTest {
        var calls = 0
        try { withRetry<String> { calls++; throw java.io.IOException("offline") }; fail("Expected failure") }
        catch (_: java.io.IOException) { assertEquals(3, calls) }
        calls = 0
        try { withRetry<String> { calls++; throw CancellationException() }; fail("Expected cancellation") }
        catch (_: CancellationException) { assertEquals(1, calls) }
    }
}
