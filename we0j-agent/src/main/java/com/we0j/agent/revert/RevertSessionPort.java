package com.we0j.agent.revert;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.common.domain.session.RevertRecord;
import java.util.List;

/**
 * RevertService 对会话存储的最小依赖缝（M4 设计决策，见交付报告）。
 *
 * <p>现有 {@code SessionService} 无 setRevert/getRevert/clearRevert——本端口的生产实现
 * （{@link SessionServiceRevertPort}）经 {@code updateRuntimeState(RuntimeState::withPendingRevert)}
 * 读写 {@link RevertRecord}（落 session.runtime_state JSON 列，FR-013 resume 完整）。
 * 单测以内存 Map 假实现即可，无需 DB/JPA。
 */
public interface RevertSessionPort {

    /** 内存权威历史（插入序）。 */
    List<MessageWithParts> history(String sessionId);

    /** 当前 pending revert；无则 null。 */
    RevertRecord getRevert(String sessionId);

    /** 标记对话边界（软删除，可撤销）。 */
    void setRevert(String sessionId, RevertRecord record);

    /** 清除对话边界。 */
    void clearRevert(String sessionId);
}
