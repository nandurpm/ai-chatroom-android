package com.example.aichatroom

import androidx.compose.ui.graphics.Color
import com.example.aichatroom.domain.*
import com.example.aichatroom.network.*
import com.example.aichatroom.ui.*
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Profile identity, config validation and cross-provider wire behavior use no live credentials. */
class AgentFeaturesTest {
    private val custom = AgentProfile("custom-id", "Ada", Color.Blue, "A", ProviderConfig(modelId = "test"))
    @Test fun promptsListAllPeersButNeverSelfAsPeer() {
        val names = listOf(custom, custom.copy(id = "b", displayName = "Bert"), custom.copy(id = "c", displayName = "Cleo"))
        val prompt = Prompts.forParticipant(custom, Mode.EXPERT, names)
        assertTrue(prompt.contains("Bert, Cleo")); assertFalse(prompt.contains("with a human and Ada"))
        assertTrue(Prompts.forParticipant(custom, Mode.FRIENDLY, listOf(custom)).contains("no other AI participants"))
    }
    @Test fun threeAgentTurnSharesEveryPreviousReply() = runTest {
        val messages = mutableListOf(Message(speaker = AgentProfile.USER, text = "Hi"))
        val store = object : MessageStore {
            override suspend fun history() = messages.toList()
            override suspend fun append(message: Message) { messages += message }
        }
        val agents = (1..3).map { n -> object : AIParticipant {
            override val speaker = custom.copy(id = "$n", displayName = "Agent $n")
            override suspend fun getResponse(conversationHistory: List<Message>, systemPrompt: String): String {
                assertEquals(n, conversationHistory.size)
                (1..3).forEach { assertTrue(systemPrompt.contains("Agent $it")) }
                return "reply $n"
            }
        } }
        TurnEngine(store) { false }.run(agents, Mode.EXPERT, {}, { "error" })
        assertEquals(4, messages.size); assertFalse(messages.any { it.error })
    }
    @Test fun genericClientSupportsCustomPathHeaderAndOwnIdentity() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
            val api = Retrofit.Builder().baseUrl(server.url("/custom/v1/")).addConverterFactory(GsonConverterFactory.create()).build().create(OpenAiApi::class.java)
            val a = custom.copy(providerConfig = custom.providerConfig.copy(authStyle = AuthStyle.CUSTOM, customHeader = "X-Test-Key", maxTokens = 77, thinking = false))
            val ai = OpenAiCompatibleParticipant(api, a, { "secret" })
            ai.getResponse(listOf(Message(speaker = a, text = "my answer")), "prompt")
            val request = server.takeRequest()
            assertEquals("/custom/v1/chat/completions", request.path)
            assertEquals("secret", request.getHeader("X-Test-Key")); assertNull(request.getHeader("Authorization"))
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals(77, json["max_tokens"].asInt)
            assertEquals("assistant", json["messages"].asJsonArray.last().asJsonObject["role"].asString)
        } finally { server.shutdown() }
    }
    @Test fun authModesNeverSendUnusedSecrets() {
        assertEquals(emptyMap<String,String>(), authHeaders(custom.providerConfig.copy(authStyle = AuthStyle.NONE), "ignored"))
        assertEquals(mapOf("x-goog-api-key" to "key"), authHeaders(custom.providerConfig.copy(authStyle = AuthStyle.GOOGLE_KEY), "key"))
    }
    @Test fun cleartextIsRestrictedToExplicitKeylessLocalEndpoints() {
        custom.providerConfig.copy(baseUrl = "http://10.0.2.2:11434/v1/", authStyle = AuthStyle.NONE, allowLocalHttp = true).validate()
        for (url in listOf("http://example.com/v1/", "https://user:pass@example.com/v1/", "https://example.com/?key=secret")) {
            assertThrows(IllegalArgumentException::class.java) { custom.providerConfig.copy(baseUrl = url, authStyle = AuthStyle.NONE, allowLocalHttp = true).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { custom.providerConfig.copy(baseUrl = "http://10.0.2.2:11434/", allowLocalHttp = true).validate() }
    }
    @Test fun allPresetsExceptIncompleteCustomAreValid() {
        ProviderPresets.all.dropLast(1).forEach { it.config.validate() }
        assertTrue(ProviderPresets.all.first { it.name.startsWith("Cerebras") }.terms.contains("No recurring free tier"))
    }
    @Test fun contextRetainsLatestHumanAndNeverChangesOriginalHistory() {
        val human = Message(speaker = AgentProfile.USER, text = "Latest request")
        val history = listOf(Message(speaker = custom, text = "x".repeat(2000)), human, Message(speaker = custom, text = "recent"))
        val selected = ContextBudget.fit(history, "system", 1000, 100)
        assertEquals(listOf(human, history.last()), selected); assertEquals(2000, history.first().text.length)
    }
    @Test fun oversizedLatestInputFailsExplicitlyAndUnicodeCountsBytes() {
        assertEquals(3L, ContextBudget.cost("ந"))
        assertThrows(IllegalArgumentException::class.java) {
            ContextBudget.fit(listOf(Message(speaker = AgentProfile.USER, text = "ந".repeat(500))), "system", 1000, 100)
        }
    }
    @Test fun contextReservesSystemAndOutputAndSkipsErrorBubbles() {
        assertThrows(IllegalArgumentException::class.java) { ContextBudget.fit(emptyList(), "x".repeat(1000), 1024, 100) }
        assertTrue(ContextBudget.fit(listOf(Message(speaker = custom, text = "error", error = true)), "system", 4096, 100).isEmpty())
    }
    @Test fun avatarTextHasContrastForLightAndDarkBackgrounds() {
        assertEquals(Color.Black, avatarTextColor(Color.White)); assertEquals(Color.White, avatarTextColor(Color.Black))
    }
}
