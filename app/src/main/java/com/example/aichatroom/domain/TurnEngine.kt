package com.example.aichatroom.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.random.Random

// One bounded turn: each enabled participant speaks once. No recursive AI loops.
class TurnEngine(private val store: MessageStore,
    private val reverseOrder: () -> Boolean = { Random.nextInt(4) == 0 }) {
    suspend fun run(participants: List<AIParticipant>, mode: Mode,
        thinking: (Speaker?) -> Unit, describeError: (Exception) -> String) {
        val ordered = if (reverseOrder()) participants.reversed() else participants
        try {
            for (ai in ordered) {
                currentCoroutineContext().ensureActive()
                thinking(ai.speaker)
                val message = try {
                    // Re-read AFTER each committed reply, excluding diagnostic bubbles.
                    val history = store.history().filterNot { it.error }
                    val reply = ai.getResponse(history, Prompts.forParticipant(ai.speaker, mode))
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
