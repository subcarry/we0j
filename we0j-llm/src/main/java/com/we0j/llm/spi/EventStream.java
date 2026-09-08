package com.we0j.llm.spi;

import com.we0j.common.domain.event.StreamEvent;
import com.we0j.common.domain.event.TokenUsage;

/**
 * 拉流抽象（DDD §5.3.1）：Iterable + AutoCloseable，虚拟线程上阻塞读。
 * Loop 代码写成线性 for 循环，与 Python `async for event in llm.full_stream()` 一一对应；
 * close() 幂等并取消底层 HTTP call；aggregatedUsage() 在 close 后可取。
 */
public interface EventStream extends Iterable<StreamEvent>, AutoCloseable {

    @Override
    void close();

    TokenUsage aggregatedUsage();
}
