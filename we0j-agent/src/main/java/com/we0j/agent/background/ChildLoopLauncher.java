package com.we0j.agent.background;

import com.we0j.agent.loop.LoopOutcome;
import com.we0j.infra.concurrency.AbortSignal;

/**
 * 子 Loop 启动缝（DDD §5.12.1 facade.runChildSession 的解耦替代）：
 * BackgroundTaskManager 不直接依赖 SessionFacade / AgentLoopFactory（避免装配环），
 * RuntimeBootstrap 装配为「以子专用 AgentLoopFactory 新建 SessionFacade → prompt →
 * 阻塞等待 completion，abort 级联 facade.cancel」。
 *
 * <p>约定：子 Session 已由调用方 create（用户消息由实现方 append，source=SUBAGENT）；
 * 实现应阻塞直到 Loop 终态并返回 {@link LoopOutcome}；抛异常 = 任务 FAILED。
 */
@FunctionalInterface
public interface ChildLoopLauncher {

    /**
     * @param childSessionId 子会话 id
     * @param prompt         子 Agent 任务输入（实现方负责 appendUserMessage）
     * @param modelRef       {@code provider/model}；null = 运行时默认卡
     * @param maxTurns       maxSteps 上限；null = 运行时默认
     * @param abort          任务级中断（父 abort 已级联进来；实现方 onCancel → facade.cancel）
     */
    LoopOutcome launch(String childSessionId, String prompt, String modelRef,
                       Integer maxTurns, AbortSignal abort) throws Exception;
}
