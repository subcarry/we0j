package com.we0j.llm.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 统一模型请求（DDD §5.3.1）：Loop → ModelClient → Provider 的唯一入参形态。 */
public record ChatRequest(
        ModelCard model,
        List<PromptBlock> system,
        List<ProviderMessage> messages,
        List<ToolDefinition> tools,
        CacheStrategy cacheStrategy,
        ReasoningConfig reasoning,
        Integer maxOutputTokens,
        Double temperature,
        Map<String, Object> extra) {

    public ChatRequest {
        system = system == null ? List.of() : List.copyOf(system);
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    public static Builder builder() { return new Builder(); }

    /** 替换 extra（ParamDropper 剔除不支持参数时重建副本用）。 */
    public ChatRequest withExtra(Map<String, Object> v) {
        return new ChatRequest(model, system, messages, tools, cacheStrategy, reasoning,
                maxOutputTokens, temperature, v);
    }

    /** 替换 model 卡（ParamDropper 剔除请求头后重建副本用）。 */
    public ChatRequest withModel(ModelCard v) {
        return new ChatRequest(v, system, messages, tools, cacheStrategy, reasoning,
                maxOutputTokens, temperature, extra);
    }

    public static final class Builder {
        private ModelCard model;
        private List<PromptBlock> system = List.of();
        private List<ProviderMessage> messages = List.of();
        private List<ToolDefinition> tools = List.of();
        private CacheStrategy cacheStrategy = CacheStrategy.DEFAULT;
        private ReasoningConfig reasoning;
        private Integer maxOutputTokens;
        private Double temperature;
        private Map<String, Object> extra = Map.of();

        public Builder model(ModelCard v) { this.model = v; return this; }
        public Builder system(List<PromptBlock> v) { this.system = v == null ? List.of() : v; return this; }
        public Builder messages(List<ProviderMessage> v) { this.messages = v == null ? List.of() : v; return this; }
        public Builder tools(List<ToolDefinition> v) { this.tools = v == null ? List.of() : v; return this; }
        public Builder cacheStrategy(CacheStrategy v) { this.cacheStrategy = v == null ? CacheStrategy.DEFAULT : v; return this; }
        public Builder reasoning(ReasoningConfig v) { this.reasoning = v; return this; }
        public Builder maxOutputTokens(Integer v) { this.maxOutputTokens = v; return this; }
        public Builder temperature(Double v) { this.temperature = v; return this; }
        public Builder extra(Map<String, Object> v) { this.extra = v == null ? Map.of() : v; return this; }

        public ChatRequest build() { return new ChatRequest(model, system, messages, tools, cacheStrategy, reasoning, maxOutputTokens, temperature, extra); }
    }
}
