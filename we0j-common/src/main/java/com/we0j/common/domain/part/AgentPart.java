package com.we0j.common.domain.part;

/** 子 Agent 引用片段（Agent 工具调用来源，FR-079）。 */
public record AgentPart(String id, String messageId, String sessionId, AgentPartSource source) implements Part {

    /** 来源信息：agentId / 子会话 id / 人格名。 */
    public record AgentPartSource(String agentId, String sessionId, String subagentType) {}
}
