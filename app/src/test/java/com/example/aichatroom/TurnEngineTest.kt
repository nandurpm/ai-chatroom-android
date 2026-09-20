package com.example.aichatroom

import com.example.aichatroom.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TurnEngineTest {
    private class MemoryStore : MessageStore {
        val messages = mutableListOf(Message(speaker = Speaker.USER, text = "Explain coroutines"))
        override suspend fun history() = messages.toList()
        override suspend fun append(message: Message) { messages += message }
    }
    private class Fake(override val speaker: Speaker, val block: suspend (List<Message>) -> String) : AIParticipant {
        val seen = mutableListOf<List<Message>>()
        override suspend fun getResponse(conversationHistory: List<Message>, systemPrompt: String): String {
            seen += conversationHistory.toList()
            assertTrue(systemPrompt.contains(speaker.label))
            return block(conversationHistory)
        }
    }
    @Test fun secondParticipantSeesFirstReply() = runTest {
        val store = MemoryStore()
        val open = Fake(Speaker.CHATGPT) { "OpenAI reply" }
        val gemini = Fake(Speaker.GEMINI) { "Gemini reply" }
        TurnEngine(store) { false }.run(listOf(open, gemini), Mode.EXPERT, {}, { "error" })
        assertEquals(listOf(Speaker.USER, Speaker.CHATGPT, Speaker.GEMINI), store.messages.map { it.speaker })
        assertEquals("OpenAI reply", gemini.seen.single().last().text)
        assertEquals(1, open.seen.single().size)
    }
    @Test fun reversedOrderSharesGeminiReply() = runTest {
        val store = MemoryStore()
        val open = Fake(Speaker.CHATGPT) { "OpenAI reply" }
        val gemini = Fake(Speaker.GEMINI) { "Gemini reply" }
        TurnEngine(store) { true }.run(listOf(open, gemini), Mode.FRIENDLY, {}, { "error" })
        assertEquals(Speaker.GEMINI, store.messages[1].speaker)
        assertEquals("Gemini reply", open.seen.single().last().text)
    }
    @Test fun failureDoesNotStopOtherAIOrPolluteHistory() = runTest {
        val store = MemoryStore()
        val open = Fake(Speaker.CHATGPT) { throw IllegalStateException("test failure") }
        val gemini = Fake(Speaker.GEMINI) { "Still works" }
        TurnEngine(store) { false }.run(listOf(open, gemini), Mode.EXPERT, {}, { "friendly error" })
        assertTrue(store.messages[1].error)
        assertEquals(1, gemini.seen.single().size)
        assertEquals("Still works", store.messages.last().text)
    }
    @Test fun soloModeCallsOnlyEnabledParticipant() = runTest {
        val store = MemoryStore()
        val gemini = Fake(Speaker.GEMINI) { "Solo reply" }
        TurnEngine(store) { true }.run(listOf(gemini), Mode.EXPERT, {}, { "error" })
        assertEquals(2, store.messages.size)
        assertEquals(Speaker.GEMINI, store.messages.last().speaker)
    }
    @Test fun cancellationAddsNoErrorAndNeverStartsSecondAI() = runTest {
        val store = MemoryStore()
        val open = Fake(Speaker.CHATGPT) { delay(10000); "late reply" }
        val gemini = Fake(Speaker.GEMINI) { "should not run" }
        var thinking: Speaker? = null
        val job = launch { TurnEngine(store) { false }.run(listOf(open, gemini), Mode.EXPERT,
            { thinking = it }, { "error" }) }
        runCurrent()
        assertEquals(Speaker.CHATGPT, thinking)
        job.cancelAndJoin()
        assertEquals(1, store.messages.size)
        assertTrue(gemini.seen.isEmpty())
        assertNull(thinking)
    }
}
