package com.we0j.llm.spi;

/** Reasoning/Thinking 控制（FR-040）：Anthropic budgetTokens / OpenAI reasoningEffort。 */
public record ReasoningConfig(boolean enabled, Integer budgetTokens, String effort) {}
