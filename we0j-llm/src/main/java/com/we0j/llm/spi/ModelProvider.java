package com.we0j.llm.spi;

import com.we0j.infra.concurrency.AbortSignal;

/** Provider SPI（FR-031）：四类端点各一个实现；Loop 只依赖本接口。 */
public interface ModelProvider {

    /** provider 标识：anthropic | openai | openai-responses | gemini。 */
    String id();

    boolean supports(ModelCard card);

    /** 打开流式事件流。阻塞式拉取（虚拟线程）；abort 触发后抛 InferenceAbortedException。 */
    EventStream openStream(ChatRequest request, com.we0j.infra.concurrency.AbortSignal abort);
}
