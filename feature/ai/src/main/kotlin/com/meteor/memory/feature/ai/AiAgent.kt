package com.meteor.memory.feature.ai

/** One completed agent run: final text plus how much work it took. */
data class AgentResult(
    val content: String,
    val steps: Int,
    val toolCalls: Int,
    val usage: AiUsage = AiUsage(),
    val serverToolCallsJson: String? = null,
    val citations: List<AiCitation> = emptyList(),
    val title: String? = null,
    val finishReason: String? = null,
)

/**
 * Bounded tool loop. Read tools run directly; tools marked as requiring approval
 * fail closed without an explicit decision from the app listener. Every request
 * and result is charged against a budget (ADR-027).
 */
class AiAgent(
    private val provider: AiProvider,
    private val tools: Toolbox,
    private val maxSteps: Int = 6,
    private val maxToolCallsPerStep: Int = 4,
    private val maxToolResultChars: Int = 2500,
    private val maxTotalToolChars: Int = 24000,
    private val listener: AiChatListener? = null,
    private val providerOptions: AiProviderOptions = AiProviderOptions(),
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
            val completion = if (listener == null) {
                provider.completeWithOptions(
                    messages = messages,
                    tools = specs,
                    maxTokens = 1500,
                    options = providerOptions,
                )
            } else {
                val active = listener
                provider.completeStreamWithOptions(
                    messages = messages,
                    tools = specs,
                    maxTokens = 1500,
                    options = providerOptions,
                    callback = object : AiStreamCallback {
                        override fun onContent(delta: String) = active.onContent(delta)

                        override fun onReasoning(delta: String) = active.onReasoning(delta)

                        override fun onServerToolEvent(event: AiServerToolEvent) {
                            active.onServerToolEvent(event)
                        }
                    },
                )
            }
            completion.usage?.let { usage = usage.merge(it) }
            if (completion.toolCalls.isEmpty()) {
                val text = completion.content?.trim().orEmpty()
                if (text.isEmpty()) {
                    if (completion.finishReason == "content_filter") {
                        throw AiProviderException(0, "content-filter")
                    }
                    throw AiProviderException(0, "empty-response")
                }
                completion.citations.forEach { listener?.onCitation(it) }
                completion.title?.let { listener?.onTitle(it) }
                return AgentResult(
                    content = text,
                    steps = steps,
                    toolCalls = toolCalls,
                    usage = usage,
                    serverToolCallsJson = completion.serverToolCallsJson,
                    citations = completion.citations,
                    title = completion.title,
                    finishReason = completion.finishReason,
                )
            }

            val calls = completion.toolCalls.take(maxToolCallsPerStep)
            toolCalls += calls.size
            messages += AiMessage(
                role = AiRole.ASSISTANT,
                content = completion.content,
                toolCalls = calls,
                reasoningContent = completion.reasoningContent,
            )

            val approvals = calls
                .filter { tools.requiresApproval(it.name) }
                .map { AiToolApproval(it.name, it.arguments) }
            val approved = approvals.isEmpty() || listener?.onToolApprovals(approvals) == true

            var budgetReached = false
            for (call in calls) {
                listener?.onToolCall(call.name, call.arguments)
                val result = if (
                    tools.requiresApproval(call.name) &&
                    !approved
                ) {
                    """{"error":"user-denied"}"""
                } else {
                    tools.execute(call.name, call.arguments, allowedPersonId).take(maxToolResultChars)
                }
                listener?.onToolResult(call.name, result)
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
