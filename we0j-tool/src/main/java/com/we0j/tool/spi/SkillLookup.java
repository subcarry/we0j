package com.we0j.tool.spi;

import com.we0j.common.domain.skill.SkillCard;
import java.util.List;
import java.util.Optional;

/**
 * SKILL 工具的运行时查询缝（DDD §5.11，FR-080）：we0j-tool 不依赖 we0j-agent，
 * 由 bootstrap 的 GateProvider 挂接 SkillService（find）与 SessionService
 * （recordInvoked → RuntimeState.invokedSkills，供压缩后恢复，FR-013）。
 *
 * <p>默认 {@link #NOOP}：未接线场景（单测/最小装配）安全降级——find 恒空、记录丢弃。
 */
public interface SkillLookup {

    /** 按名查找 skill 卡片。 */
    Optional<SkillCard> find(String name);

    /** 已调用记录（渐进式披露第二阶段的生命周期账本）。 */
    void recordInvoked(String sessionId, String name);

    /** 可用 skill 名清单（错误提示用；默认空 = 不泄露列表）。 */
    default List<String> names() {
        return List.of();
    }

    /** 无操作实现。 */
    SkillLookup NOOP = new SkillLookup() {
        @Override
        public Optional<SkillCard> find(String name) {
            return Optional.empty();
        }

        @Override
        public void recordInvoked(String sessionId, String name) {
            // 未接线：丢弃
        }
    };
}
