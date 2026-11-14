package com.meteor.memory.ui.ai

import com.meteor.memory.feature.ai.AiUsageTotals
import com.meteor.memory.feature.settings.AppSettings

/** Maps the persisted lifetime counters to the usage UI model. */
internal fun AppSettings.aiUsageTotals(): AiUsageTotals = AiUsageTotals(
    tokens = aiTotalPromptTokens + aiTotalCompletionTokens,
    requests = aiTotalRequests,
    elapsedMs = aiTotalElapsedMs,
    cacheHitTokens = aiTotalCacheHitTokens,
    cacheTokens = aiTotalCacheHitTokens + aiTotalCacheMissTokens,
)
