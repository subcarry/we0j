package com.we0j.server.dto;

import java.util.List;
import java.util.Set;

/** 模型卡（GET /models；apiKey 脱敏为 "sk-…abcd" 形态，DDD §8.2）。 */
public record ModelDto(String providerId, String model, String qualifiedId, String family,
                       String apiBase, String apiKeyMasked, Set<String> features,
                       int contextWindow, Integer maxOutput, String reasoningEffort, String verbosity) {
}
