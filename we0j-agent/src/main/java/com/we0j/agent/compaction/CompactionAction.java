package com.we0j.agent.compaction;

/** 压缩完成后 Loop 的动作指令（DDD §5.5.4）。 */
public enum CompactionAction {
    /** 无事可做（无需摘要）。 */
    NONE,
    /** 压缩成功，Loop 继续下一步。 */
    CONTINUE,
    /** 压缩失败，Loop 应中断本轮（FR-053 防死循环）。 */
    BREAK
}
