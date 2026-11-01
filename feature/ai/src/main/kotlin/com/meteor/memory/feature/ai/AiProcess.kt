package com.meteor.memory.feature.ai

/**
 * One step of a turn, in the order it happened.
 *
 * A turn is a sequence of thinking segments and tool calls: the model may think,
 * call a tool, read the result, think again, call another tool. Keeping the
 * steps ordered is what lets the transcript show "思考与工具调用" as one
 * collapsible block instead of two unrelated piles.
 */
sealed interface AiProcessStep {

    /** One thinking segment, as streamed by the provider. */
    data class Reasoning(val text: String) : AiProcessStep

    /**
     * One tool call.
     *
     * [arguments] is the raw JSON the model produced, shortened for display;
     * [result] is the tool output once it came back, also shortened.
     */
    data class Tool(
        val name: String,
        val arguments: String,
        val result: String? = null,
        val done: Boolean = false,
    ) : AiProcessStep
}
