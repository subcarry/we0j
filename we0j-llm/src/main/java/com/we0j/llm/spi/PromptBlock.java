package com.we0j.llm.spi;

/**
 * 提示词块（FR-034/FR-041）：system 按块下发用于 Anthropic 缓存打点。
 * ★ 顺序即缓存前缀 —— 块内容与顺序在同一会话内必须字节一致（缓存稳定性铁律）。
 */
public record PromptBlock(String key, String text, boolean cacheBreakpoint) {

    public PromptBlock withCacheBreakpoint(boolean v) {
        return new PromptBlock(key, text, v);
    }
}
