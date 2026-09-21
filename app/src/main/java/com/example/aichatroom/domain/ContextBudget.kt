package com.example.aichatroom.domain

import com.google.gson.Gson

/** Only this controlled error text may be shown verbatim to a user. */
class ContextBudgetException(message: String) : IllegalArgumentException(message)

/** Conservative UTF-8 byte estimate: deliberately overcounts most BPE tokens, including non-Latin text.
 * Reserves output, system text and wire overhead. Never mutates or deletes the stored transcript.
 */
object ContextBudget {
    fun cost(text: String) = text.toByteArray(Charsets.UTF_8).size.toLong()
    private fun cost(message: Message): Long {
        val json = Gson()
        val labeled = json.toJson(mapOf("speaker" to message.speaker.displayName, "text" to message.text))
        // Count nested JSON escaping as well as labels; code with many quotes must not evade the budget.
        return cost(json.toJson(mapOf("role" to "assistant", "content" to labeled))) + 64
    }
    fun fit(history: List<Message>, system: String, context: Int, output: Int): List<Message> {
        val available = context.toLong() - output - cost(system) - 512
        if (available <= 0) throw ContextBudgetException("System prompt and output reservation exceed the context budget. Reduce output tokens or increase the context budget.")
        val clean = history.filterNot { it.error }
        if (clean.isEmpty()) return emptyList()
        // Always retain the current human request, even after several verbose peers have replied.
        val human = clean.indexOfLast { it.speaker.id == AgentProfile.USER.id }.takeIf { it >= 0 } ?: clean.lastIndex
        val selected = sortedSetOf(human)
        var used = cost(clean[human])
        if (used > available) throw ContextBudgetException("Your latest message is too large for this agent's context budget. Shorten it or raise the budget in Settings.")
        for (i in clean.indices.reversed()) {
            if (i == human) continue
            val next = cost(clean[i])
            if (used + next > available) break
            selected += i; used += next
        }
        return selected.map { clean[it] }
    }
}
