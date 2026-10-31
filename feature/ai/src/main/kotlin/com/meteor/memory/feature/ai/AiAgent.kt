package com.meteor.memory.feature.ai

/** One completed agent run: final text plus how much work it took. */
data class AgentResult(
    val content: String,
    val steps: Int,
    val toolCalls: Int,
    val usage: AiUsage = AiUsage(),
)

/**
 * Bounded tool loop. The model may ask for **read-only** archive tools for at
 * most [maxSteps] rounds; every request and result is charged against a budget
 * so a looping model cannot burn a user's quota without limit (ADR-027).
 */
class AiAgent(
    private val provider: AiProvider,
    private val tools: Toolbox,
    private val maxSteps: Int = 6,
    private val maxToolCallsPerStep: Int = 4,
    private val maxToolResultChars: Int = 2500,
    private val maxTotalToolChars: Int = 24000,
) {

    suspend fun run(conversation: List<AiMessage>, allowedPersonId: String?): AgentResult {
        require(conversation.firstOrNull()?.role == AiRole.SYSTEM) { "conversation must start with a system message" }

        val messages = conversation.toMutableList()
        val specs = tools.specs()
        var steps = 0
        var toolCalls = 0
        var toolChars = 0
        var usage = AiUsage()

        while (steps < maxSteps) {
            steps++
            val completion = provider.complete(messages = messages, tools = specs)
            completion.usage?.let { usage = usage.merge(it) }
            if (completion.toolCalls.isEmpty()) {
                val text = completion.content?.trim().orEmpty()
                if (text.isEmpty()) throw AiProviderException(0, "empty-response")
                return AgentResult(text, steps, toolCalls, usage)
            }

            val calls = completion.toolCalls.take(maxToolCallsPerStep)
            toolCalls += calls.size
            messages += AiMessage(
                role = AiRole.ASSISTANT,
                content = completion.content,
                toolCalls = calls,
                reasoningContent = completion.reasoningContent,
            )

            var budgetReached = false
            for (call in calls) {
                val result = tools.execute(call.name, call.arguments, allowedPersonId)
                    .take(maxToolResultChars)
                toolChars += result.length
                messages += AiMessage.tool(call.id, result)
                if (toolChars >= maxTotalToolChars) {
                    budgetReached = true
                    break
                }
            }
            if (budgetReached) messages += AiMessage.user(TOOL_BUDGET_NOTE)
        }

        throw AiProviderException(0, "step-limit")
    }

    private companion object {
        const val TOOL_BUDGET_NOTE =
            "Tool budget reached. Answer the user now using only the evidence you already retrieved."
    }
}
