package com.we0j.common.domain.part;

import java.util.List;

/** 压缩摘要元数据（对齐原项目 CompactionSummaryMetadata 字段，FR-052）。 */
public record CompactionSummaryMetadata(
        CompactionPreservedTail preservedTail,
        CompactionPreservedSegment preservedSegment,
        List<String> preCompactDiscoveredTools,
        Integer messagesSummarized,
        Integer truePostCompactTokenCount,
        PostCompactTaskStatusMetadata postCompactTaskStatus) {

    public CompactionSummaryMetadata {
        preCompactDiscoveredTools = preCompactDiscoveredTools == null ? List.of() : List.copyOf(preCompactDiscoveredTools);
    }

    /** 保留尾部：压缩后仍完整可见的消息 id 集。 */
    public record CompactionPreservedTail(List<String> messageIds) {
        public CompactionPreservedTail {
            messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
        }
    }

    /** 保留分段（非尾部但被完整保留的段落）。 */
    public record CompactionPreservedSegment(List<String> messageIds) {
        public CompactionPreservedSegment {
            messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
        }
    }

    /** 压缩后任务状态恢复位（PostCompactionRestore 注入来源标记）。 */
    public record PostCompactTaskStatusMetadata(String source, List<String> taskIds) {
        public PostCompactTaskStatusMetadata {
            taskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
        }
    }
}
