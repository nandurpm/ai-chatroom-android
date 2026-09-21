package com.example.aichatroom

import com.example.aichatroom.domain.*
import com.example.aichatroom.network.*
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class NvidiaFallbackTest {
    private val primary = "nvidia/nemotron-3-super-120b-a12b"
    private val fallback = "qwen/qwen3.5-397b-a17b"
    private val history = listOf(Message(speaker = AgentProfile.USER, text = "Hello"))
    private fun ai(server: MockWebServer, backup: String = fallback) = NvidiaParticipant(
        Retrofit.Builder().baseUrl(server.url("/v1/")).addConverterFactory(GsonConverterFactory.create())
            .build().create(OpenAiApi::class.java), { "fake-nvidia-key" }, primary, backup)
    private suspend fun verifyFallback(status: Int) {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(status))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Backup answer"}}]}"""))
            val reply = ai(server).getResponse(history, "Expert prompt")
            assertTrue(reply.contains(fallback))
            assertTrue(reply.contains("Backup answer"))
            assertEquals(2, server.requestCount)
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("Bearer fake-nvidia-key", second.getHeader("Authorization"))
            val one = JsonParser.parseString(first.body.readUtf8()).asJsonObject
            val two = JsonParser.parseString(second.body.readUtf8()).asJsonObject
            assertEquals(primary, one["model"].asString)
            assertEquals(fallback, two["model"].asString)
            assertEquals(one["messages"], two["messages"])
        } finally { server.shutdown() }
    }
    @Test fun missingModel404UsesFallback() = runTest { verifyFallback(404) }
    @Test fun retiredModel410UsesFallback() = runTest { verifyFallback(410) }
    @Test fun bothModelsUnavailableStopsAfterOneSwitch() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(410))
            try { ai(server).getResponse(history, "prompt"); fail("Expected unavailable models") }
            catch (e: UserFacingException) { assertTrue(e.message!!.contains("Both NVIDIA models")) }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun sameFallbackDoesNotRepeatUnavailableModel() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            try { ai(server, primary).getResponse(history, "prompt"); fail("Expected 404") }
            catch (e: HttpException) { assertEquals(404, e.code()) }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun quotaErrorsRetryPrimaryWithoutSwitchingModels() = runTest {
        val server = MockWebServer()
        try {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(429)) }
            try { ai(server).getResponse(history, "prompt"); fail("Expected 429") }
            catch (e: HttpException) { assertEquals(429, e.code()) }
            assertEquals(3, server.requestCount)
            repeat(3) {
                assertEquals(primary, JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject["model"].asString)
            }
        } finally { server.shutdown() }
    }
    @Test fun legacyChatGptMessagesKeepTheirIdentity() {
        val old = Message(speaker = AgentProfile.CHATGPT, text = "Historical OpenAI answer")
        val request = Transcript.openAi(listOf(old)).single()
        assertEquals("ChatGPT", AgentProfile.valueOf("CHATGPT").label)
        assertEquals("user", request.role)
        assertEquals("ChatGPT", JsonParser.parseString(request.content).asJsonObject["speaker"].asString)
        assertEquals("gemini-3.6-flash", Preferences().geminiModel)
    }
}
