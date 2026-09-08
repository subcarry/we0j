package com.we0j.agent.context;

/**
 * 上下文贡献者 SPI（DDD §5.4.2 / FR-044）：替代原项目 400 行 inject_system_reminders() 巨型函数。
 * 每个实现按 {@link #order()} 排序执行，产出 0/1 条 reminder 文本。
 *
 * <p>Spring 风格装配：bootstrap 收集 List&lt;ContextContributor&gt; 手工注入 ReminderInjector
 * （M3 无容器自动扫描，接线点见 ContextAssembler 类注释）。
 */
public interface ContextContributor {

    /** 唯一标识，对应 TextPart.metadata.source，用于注入去重。 */
    String source();

    /** 排序值，越小越先注入（顺序即上下文前缀，保持稳定）。 */
    int order();

    /** 是否持久化：true = 写合成 TextPart 落库（经 ReminderStore）；false = 仅本轮内存附加。 */
    boolean persistent();

    /** 是否适用于当前上下文（lane / mode / 配置开关）。 */
    default boolean appliesTo(ContributeContext ctx) {
        return true;
    }

    /** 产出内容；返回 null 或空白表示本轮不注入。 */
    String render(ContributeContext ctx);
}
