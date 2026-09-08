package com.we0j.tool.builtin.file;

import java.util.Optional;

/**
 * 单级替换策略（DDD §5.7.1，ReplacerChain 链成员）。
 *
 * <p>约定：返回 {@link Optional#empty()} 表示本级不适用（无匹配 / 命中不唯一 / 与精确匹配退化等价），
 * 交给下一级；命中即停，不做二次校验。实现必须无副作用（不写文件、不询问权限）。
 */
@FunctionalInterface
public interface ReplaceStrategy {

    Optional<ReplacerChain.ReplaceOutcome> tryReplace(String content, String oldText,
                                                      String newText, boolean replaceAll);
}
