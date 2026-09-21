package com.example.aichatroom.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

// One bounded turn: each enabled participant speaks once. No recursive AI loops.
class TurnEngine(private val store: MessageStore,
    private val replyTimeoutMillis: Long = 90_000,
    private val reverseOrder: () -> Boolean = { Random.nextInt(4) == 0 }) {

    private suspend fun replyFor(ai: AIParticipant, history: List<Message>, mode: Mode,
        participants: List<AIParticipant>, describeError: (Exception) -> String): Message {
        return try {
            val reply = withTimeoutOrNull(replyTimeoutMillis) {
                ai.getResponse(history, Prompts.forParticipant(ai.speaker, mode, participants.map { it.speaker }))
            }
            if (reply == null) {
                Message(speaker = ai.speaker,
                    text = "${ai.speaker.label} took too long (${replyTimeoutMillis / 1000} seconds). Try again or disable this agent.",
                    error = true)
            } else {
                require(reply.isNotBlank()) { "Empty reply" }
                Message(speaker = ai.speaker, text = reply)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Message(speaker = ai.speaker, text = describeError(e), error = true)
        }
    }

    suspend fun run(participants: List<AIParticipant>, mode: Mode,
        thinking: (AgentProfile?) -> Unit, describeError: (Exception) -> String,
        parallel: Boolean = false, status: (AgentProfile, Boolean) -> Unit = { _, _ -> }) {
        val ordered = if (reverseOrder()) participants.reversed() else participants
        try {
            if (!parallel || ordered.size <= 1) {
                for (ai in ordered) {
                    currentCoroutineContext().ensureActive()
                    thinking(ai.speaker)
                    // Discussion mode intentionally re-reads after each reply so later AIs can react to earlier AIs.
                    val history = store.history().filterNot { it.error }
                    store.append(replyFor(ai, history, mode, participants, describeError))
                }
                return
            }

            // Fast mode starts every provider immediately. They all receive the same clean snapshot,
            // so a slow provider never blocks a fast provider from answering.
            val history = store.history().filterNot { it.error }
            val appendMutex = Mutex()
            supervisorScope {
                ordered.map { ai ->
                    launch {
                        status(ai.speaker, true)
                        try {
                            val message = replyFor(ai, history, mode, participants, describeError)
                            currentCoroutineContext().ensureActive()
                            appendMutex.withLock { store.append(message) }
                        } finally {
                            status(ai.speaker, false)
                        }
                    }
                }.joinAll()
            }
        } finally {
            thinking(null)
        }
    }
}
