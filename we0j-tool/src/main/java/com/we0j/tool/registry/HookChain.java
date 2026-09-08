package com.we0j.tool.registry;

import com.we0j.tool.spi.ToolInput;
import com.we0j.tool.spi.ToolResult;
import java.util.Map;

/**
 * 工具执行前后钩子链（DDD §5.2.5 步骤 2/7）——P2 前为占位空实现：
 * before 原样把 input 包装返回，after 原样返回结果。
 * 后续接插件/审计/hook 配置（FR-09x）时以实现替换 {@link #NOOP}。
 */
public interface HookChain {

    /** tool.execute.before：可改写 input 或抛异常直接拒绝。 */
    record BeforeToolExecute(String sessionId, String callId, String toolName, Map<String, Object> input) {}

    /** tool.execute.after：可改写结果。 */
    record AfterToolExecute(String sessionId, String callId, String toolName,
                            Map<String, Object> input, ToolResult result) {}

    default ToolInput beforeToolExecute(BeforeToolExecute before) {
        return new ToolInput(before.input());
    }

    default ToolResult afterToolExecute(AfterToolExecute after) {
        return after.result();
    }

    /** 空实现占位（M2 装配默认）。 */
    HookChain NOOP = new HookChain() {};
}
