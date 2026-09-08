package com.we0j.agent.compaction;

/** 压缩执行结果（DDD §5.5.4）：action 指示 Loop 继续/中断。 */
public record CompactionOutcome(CompactionAction action, String summary, int beforeTokens, int afterTokens) {

    public static CompactionOutcome none() {
        return new CompactionOutcome(CompactionAction.NONE, null, 0, 0);
    }

    public static CompactionOutcome failed() {
        return new CompactionOutcome(CompactionAction.BREAK, null, 0, 0);
    }
}
