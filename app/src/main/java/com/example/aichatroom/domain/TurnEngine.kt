package com.example.aichatroom.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

// One bounded turn: each enabled participant speaks once. No recursive AI loops.
class TurnEngine(private val store: MessageStore,
    private val replyTimeoutMillis: Long = 90_000,
    private val reverseOrder: () -> Boolean = { Random.nextInt(4) == 0 }) {
    suspend fun run(participants: List<AIParticipant>, mode: Mode,
        thinking: (AgentProfile?) -> Unit, describeError: (Exception) -> String) {
        val ordered = if (reverseOrder()) participants.reversed() else participants
        try {
            for (ai in ordered) {
                currentCoroutineContext().ensureActive()
                thinking(ai.speaker)
                val message = try {
                    // Re-read AFTER each committed reply, excluding diagnostic bubbles.
                    val history = store.history().filterNot { it.error }
                    // This deadline includes retries and fallback; a slow provider cannot hold the room indefinitely.
                    val reply = withTimeoutOrNull(replyTimeoutMillis) {
                        ai.getResponse(history, Prompts.forParticipant(ai.speaker, mode, participants.map { it.speaker }))
                    }
                    if (reply == null) {
                        store.append(Message(speaker = ai.speaker, text = "${ai.speaker.label} took too long (90 seconds). Try again or disable this agent.", error = true))
                        continue
                    }
                    require(reply.isNotBlank()) { "Empty reply" }
                    Message(speaker = ai.speaker, text = reply)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (e: Exception) {
                    Message(speaker = ai.speaker, text = describeError(e), error = true)
                }
                currentCoroutineContext().ensureActive()
                store.append(message)
            }
        } finally { thinking(null) }
    }
}
