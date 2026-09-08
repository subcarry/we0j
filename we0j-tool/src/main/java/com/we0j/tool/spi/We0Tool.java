package com.we0j.tool.spi;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 内置工具声明（DDD §5.6.1）。元注解含 @Component：标注即成为 bean，ToolRegistry 收集。
 * 描述文本放 classpath:/tool-descriptions/&lt;name&gt;.md（description 为空时加载），便于复用原项目提示词。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface We0Tool {

    /** 模型可见名（ToolNames 常量）。 */
    String name();

    /** 为空则从 /tool-descriptions/<name>.md 加载。 */
    String description() default "";

    /** 权限名（FR-081）。 */
    com.we0j.common.domain.permission.PermissionName permission();

    /** 默认延迟加载（FR-065）。 */
    boolean deferLoading() default true;

    /** 可见渠道（FR-063）。 */
    com.we0j.common.domain.message.ChannelSource[] sources() default {
            com.we0j.common.domain.message.ChannelSource.CLI,
            com.we0j.common.domain.message.ChannelSource.WEB,
            com.we0j.common.domain.message.ChannelSource.SUBAGENT };

    /** 内容受众；空 = 仅 ASSISTANT。 */
    Audience[] audience() default {};
}
