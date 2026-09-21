package com.example.aichatroom

import com.example.aichatroom.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TurnEngineTest {
    private class MemoryStore : MessageStore {
        val messages = mutableListOf(Message(speaker = AgentProfile.USER, text = "Explain coroutines"))
        override suspend fun history() = messages.toList()
        override suspend fun append(message: Message) { messages += message }
    }
    private class Fake(override val speaker: AgentProfile, val block: suspend (List<Message>) -> String) : AIParticipant {
        val seen = mutableListOf<List<Message>>()
        override suspend fun getResponse(conversationHistory: List<Message>, systemPrompt: String): String {
            seen += conversationHistory.toList()
            assertTrue(systemPrompt.contains(speaker.label))
            return block(conversationHistory)
        }
    }
    @Test fun secondParticipantSeesFirstReply() = runTest {
        val store = MemoryStore()
        val open = Fake(AgentProfile.NVIDIA) { "NVIDIA reply" }
        val gemini = Fake(AgentProfile.GEMINI) { "Gemini reply" }
        TurnEngine(store) { false }.run(listOf(open, gemini), Mode.EXPERT, {}, { "error" })
        assertEquals(listOf(AgentProfile.USER, AgentProfile.NVIDIA, AgentProfile.GEMINI), store.messages.map { it.speaker })
        assertEquals("NVIDIA reply", gemini.seen.single().last().text)
        assertEquals(1, open.seen.single().size)
    }
    @Test fun reversedOrderSharesGeminiReply() = runTest {
        val store = MemoryStore()
        val open = Fake(AgentProfile.NVIDIA) { "NVIDIA reply" }
        val gemini = Fake(AgentProfile.GEMINI) { "Gemini reply" }
        TurnEngine(store) { true }.run(listOf(open, gemini), Mode.FRIENDLY, {}, { "error" })
        assertEquals(AgentProfile.GEMINI, store.messages[1].speaker)
        assertEquals("Gemini reply", open.seen.single().last().text)
    }
    @Test fun failureDoesNotStopOtherAIOrPolluteHistory() = runTest {
        val store = MemoryStore()
        val open = Fake(AgentProfile.NVIDIA) { throw IllegalStateException("test failure") }
        val gemini = Fake(AgentProfile.GEMINI) { "Still works" }
        TurnEngine(store) { false }.run(listOf(open, gemini), Mode.EXPERT, {}, { "friendly error" })
        assertTrue(store.messages[1].error)
        assertEquals(1, gemini.seen.single().size)
        assertEquals("Still works", store.messages.last().text)
    }
    @Test fun soloModeCallsOnlyEnabledParticipant() = runTest {
        val store = MemoryStore()
        val gemini = Fake(AgentProfile.GEMINI) { "Solo reply" }
        TurnEngine(store) { true }.run(listOf(gemini), Mode.EXPERT, {}, { "error" })
        assertEquals(2, store.messages.size)
        assertEquals(AgentProfile.GEMINI, store.messages.last().speaker)
    }
    @Test fun cancellationAddsNoErrorAndNeverStartsSecondAI() = runTest {
        val store = MemoryStore()
        val open = Fake(AgentProfile.NVIDIA) { delay(10000); "late reply" }
        val gemini = Fake(AgentProfile.GEMINI) { "should not run" }
        var thinking: AgentProfile? = null
        val job = launch { TurnEngine(store) { false }.run(listOf(open, gemini), Mode.EXPERT,
            { thinking = it }, { "error" }) }
        runCurrent()
        assertEquals(AgentProfile.NVIDIA, thinking)
        job.cancelAndJoin()
        assertEquals(1, store.messages.size)
        assertTrue(gemini.seen.isEmpty())
        assertNull(thinking)
    }
    @Test fun slowProviderTimesOutAndNextAIStillReplies() = runTest {
        val store = MemoryStore()
        val slow = Fake(AgentProfile.NVIDIA) { delay(100_000); "late" }
        val fast = Fake(AgentProfile.GEMINI) { "ready" }
        var thinking: AgentProfile? = null
        TurnEngine(store) { false }.run(listOf(slow, fast), Mode.FRIENDLY,
            { thinking = it }, { "error" })
        assertEquals(90_000L, currentTime)
        assertTrue(store.messages[1].error)
        assertTrue(store.messages[1].text.contains("took too long"))
        assertEquals("ready", store.messages.last().text)
        assertEquals(1, fast.seen.single().size)
        assertNull(thinking)
    }
}

