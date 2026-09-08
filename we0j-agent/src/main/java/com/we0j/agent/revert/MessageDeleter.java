package com.we0j.agent.revert;

import java.util.List;

/**
 * 物理删除消息的函数式缝（M4 设计决策）：RevertService 不直接依赖 SessionService 的删除路径
 * （现 SessionService 也无 deleteMessages——级联删 part + cache.evict + DB 删行的组合由
 * bootstrap 装配的实现提供），避免 revert → session 的写路径耦合。
 *
 * <p>调用时机：{@link RevertService#cleanup(String)}——下次 prompt 入口（对齐原项目，
 * 软删除边界在 cleanup 前始终可 unrevert）。
 */
@FunctionalInterface
public interface MessageDeleter {

    /** 按 messageId 列表删除消息及其全部 Part（实现方负责 cache + DB + Bus）。 */
    void delete(String sessionId, List<String> messageIds);
}
