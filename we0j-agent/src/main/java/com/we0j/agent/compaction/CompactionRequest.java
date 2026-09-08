package com.we0j.agent.compaction;

import com.we0j.llm.spi.ModelCard;
import java.util.List;

/**
 * 压缩请求（DDD §5.5.4）：触发时机 + 用户附加指令（/compact [指令]）+
 * 压缩前已发现的延迟工具激活态（写入 CompactionSummaryMetadata.preCompactDiscoveredTools）。
 * card 为 null 时由服务按 "fast" 档解析（降本）。
 */
public record CompactionRequest(CompactionTrigger trigger, String userInstruction,
                                List<String> discoveredDeferredTools, ModelCard card) {

    public CompactionRequest {
        discoveredDeferredTools = discoveredDeferredTools == null
                ? List.of() : List.copyOf(discoveredDeferredTools);
    }

    public static CompactionRequest of(CompactionTrigger trigger) {
        return new CompactionRequest(trigger, null, List.of(), null);
    }

    public static CompactionRequest manual(String userInstruction) {
        return new CompactionRequest(CompactionTrigger.MANUAL, userInstruction, List.of(), null);
    }
}
