package com.we0j.agent.compaction;

import com.we0j.llm.spi.ContentBlock;
import com.we0j.llm.spi.ProviderMessage;
import java.util.List;

/**
 * 摘要提示词（DDD §5.5.4，FR-052 步骤 4）：强制五章节结构 + 关键事实逐字保留铁律。
 *
 * <p>/compact [指令] 的 userInstruction 注入到末尾指令消息（"Focus on ..."）。
 * 对话本体以真实 ProviderMessage 序列下发（而非 §5.5.4 文本模板中的 {@code <conversation>}
 * 内联序列化）——保留消息结构才能让 truncateHead 重试按 API round 整组丢弃（FR-052 步骤 5）。
 */
public final class CompactionPromptBuilder {

    /** system 全文（对齐原项目压缩摘要提示词铁律）。 */
    public static final String SYSTEM = """
            You are a conversation summarizer for a coding agent. Your output replaces the earlier \
            part of the conversation history, so anything you omit is permanently lost.

            Produce a summary in EXACTLY these markdown sections:

            ## 1. Task Objective
            What the user asked for. Quote the original request verbatim if short.

            ## 2. Completed Work
            Concrete actions already taken: files read/created/modified (full paths), commands run \
            (exact command lines) and their outcomes, decisions made and their rationale.

            ## 3. Current State
            Exact current state of the codebase and the task: what works, what is broken, what is \
            half-finished. Include file paths and symbol names.

            ## 4. Key Facts & Constraints
            Verbatim error messages, API contracts, schema definitions, version numbers, environment \
            constraints, user preferences and prohibitions stated during the conversation.

            ## 5. Pending Work
            What remains to be done, in priority order.

            Rules:
            - NEVER summarize away a file path, identifier, error message, or command line — keep them verbatim.
            - NEVER invent work that did not happen.
            - Be dense and factual. No preamble, no closing remarks.
            - Output markdown only.\
            """;

    public String system() {
        return SYSTEM;
    }

    /**
     * 末尾指令消息：可选用户指令（/compact 聚焦要求）+ "Summarize now."。
     * 该消息不属于对话 round 本体，由调用方在 truncateHead 重试时保持在尾部。
     */
    public ProviderMessage instructionMessage(String userInstruction) {
        String text = (userInstruction == null || userInstruction.isBlank())
                ? "Summarize the conversation above now."
                : "User instruction for this summary — Focus on: " + userInstruction.trim()
                        + "\nSummarize the conversation above now.";
        return ProviderMessage.user(List.of(new ContentBlock.Text(text)));
    }
}
