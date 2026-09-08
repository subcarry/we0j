package com.we0j.agent.context;

import com.we0j.common.domain.part.TextPart;

/**
 * 持久 reminder 的落库缝（DDD §5.4.2 ReminderInjector 对 SessionService 的依赖抽象）。
 *
 * <p>函数式窄接口让 ReminderInjector 测试不触 DB；生产实现
 * {@link SessionServiceReminderStore} 经 SessionService.appendPart/updatePart 落库。
 */
public interface ReminderStore {

    /** 新建合成 TextPart 并持久化，返回带最终 id 的 Part（供本轮请求即时消费）。 */
    TextPart appendSynthetic(String sessionId, String userMessageId, String source, String text);

    /** 同 source 已有合成 Part → 删除旧 Part、落新 Part（★ 替换而非追加，防 reminder 堆积）。 */
    TextPart replaceSynthetic(String sessionId, String userMessageId, String source, String text);

    /** 空实现：persistent 注入只走内存不落库（测试 / 无会话存储场景）。 */
    ReminderStore NOOP = new ReminderStore() {
        @Override
        public TextPart appendSynthetic(String sessionId, String userMessageId, String source, String text) {
            return ReminderInjector.newSyntheticPart(userMessageId, sessionId, source, text);
        }

        @Override
        public TextPart replaceSynthetic(String sessionId, String userMessageId, String source, String text) {
            return ReminderInjector.newSyntheticPart(userMessageId, sessionId, source, text);
        }
    };
}
