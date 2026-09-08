package com.we0j.agent.loop;

import com.we0j.common.domain.part.MessageError;
import com.we0j.common.domain.part.Tokens;

/**
 * 一次 Loop 运行的最终结果（DDD §5.2.1）。
 *
 * @param reason 退出原因
 * @param steps  外层循环实际执行的步数
 * @param tokens 各步 assistant 消息 tokens 的累计和
 * @param error  导致 ABORTED / FATAL_ERROR 的消息级错误（正常完成为 null）
 */
public record LoopOutcome(LoopExitReason reason, int steps, Tokens tokens, MessageError error) {

    public static LoopOutcome of(LoopExitReason reason, int steps, Tokens tokens) {
        return new LoopOutcome(reason, steps, tokens, null);
    }
}
