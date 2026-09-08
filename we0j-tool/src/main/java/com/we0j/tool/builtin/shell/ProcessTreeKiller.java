package com.we0j.tool.builtin.shell;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JDK 原生进程树杀死（替代 psutil，FR-024）。
 * 顺序：先 destroy 全部后代 → 父 destroy（SIGTERM）→ 2s 宽限 → 全部 destroyForcibly（SIGKILL）。
 * 先子后父，避免父进程在退出前重生子进程。
 */
public final class ProcessTreeKiller {

    /** 杀死进程及其全部后代进程。可从 AbortSignal 清理回调线程调用，幂等。 */
    public static void kill(Process p) {
        try {
            List<ProcessHandle> descendants = p.descendants().toList();
            for (ProcessHandle h : descendants) h.destroy();               // SIGTERM
            p.destroy();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {                          // 2s 宽限
                for (ProcessHandle h : descendants) h.destroyForcibly();    // SIGKILL
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        } catch (Exception ignored) {
            p.destroyForcibly();
        }
    }

    private ProcessTreeKiller() {}
}
