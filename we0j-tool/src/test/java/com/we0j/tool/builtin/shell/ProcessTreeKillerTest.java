package com.we0j.tool.builtin.shell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** ProcessTreeKiller：spawn 子进程 → kill 后父与全部后代退出（±500ms 轮询等待）。 */
class ProcessTreeKillerTest {

    private static boolean awaitUntil(long timeoutMs, java.util.function.BooleanSupplier cond)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return cond.getAsBoolean();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void killsWindowsTree() throws Exception {
        // start /b 拉起一个 detached ping，父 cmd 自己再跑一个 ping（30s）
        Process p = new ProcessBuilder("cmd", "/c",
                "start /b cmd /c ping -n 30 127.0.0.1 & ping -n 30 127.0.0.1")
                .redirectErrorStream(true)
                .start();
        drainAsync(p);
        assertThat(awaitUntil(5_000, () -> p.descendants().count() >= 1)).isTrue();
        List<ProcessHandle> children = p.descendants().toList();

        ProcessTreeKiller.kill(p);

        assertThat(p.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitUntil(5_000, () -> children.stream().noneMatch(ProcessHandle::isAlive)))
                .as("all child processes terminated after kill").isTrue();
        assertThat(p.descendants().toList()).isEmpty();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void killsUnixTree() throws Exception {
        Process p = new ProcessBuilder("bash", "-c", "sleep 300 & sleep 300 & wait")
                .redirectErrorStream(true)
                .start();
        drainAsync(p);
        assertThat(awaitUntil(5_000, () -> p.descendants().count() >= 2)).isTrue();
        List<ProcessHandle> children = p.descendants().toList();

        ProcessTreeKiller.kill(p);

        assertThat(p.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitUntil(5_000, () -> children.stream().noneMatch(ProcessHandle::isAlive)))
                .as("all child processes terminated after kill").isTrue();
    }

    private static void drainAsync(Process p) {
        Thread t = Thread.ofVirtual().unstarted(() -> {
            try (var in = p.getInputStream()) {
                in.readAllBytes();
            } catch (IOException ignored) {
            }
        });
        t.start();
    }
}
