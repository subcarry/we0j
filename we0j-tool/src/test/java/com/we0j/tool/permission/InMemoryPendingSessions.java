package com.we0j.tool.permission;

import com.we0j.common.domain.permission.PermissionRequest;
import com.we0j.common.domain.permission.ReplyDecision;
import com.we0j.infra.concurrency.AbortSignal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 测试替身：单会话内存版 PendingSessions（SessionRegistry 的 SessionEntry 语义等价物）。 */
public final class InMemoryPendingSessions implements PendingSessions {

    public final class TestSlot implements Slot {
        private final ConcurrentMap<String, CompletableFuture<ReplyDecision>> permissions =
                new ConcurrentHashMap<>();
        private final ConcurrentMap<String, CompletableFuture<List<List<String>>>> questions =
                new ConcurrentHashMap<>();
        private final ConcurrentMap<String, PermissionRequest> requests = new ConcurrentHashMap<>();
        private final AbortSignal abort = AbortSignal.create();

        @Override
        public ConcurrentMap<String, CompletableFuture<ReplyDecision>> pendingPermissions() {
            return permissions;
        }

        @Override
        public ConcurrentMap<String, CompletableFuture<List<List<String>>>> pendingQuestions() {
            return questions;
        }

        @Override
        public ConcurrentMap<String, PermissionRequest> pendingPermissionRequests() {
            return requests;
        }

        @Override
        public AbortSignal abortSignal() {
            return abort;
        }
    }

    private final Slot slot = new TestSlot();
    private final String sessionId;

    public InMemoryPendingSessions(String sessionId) {
        this.sessionId = sessionId;
    }

    public Slot slot() { return slot; }

    /** 轮询等待第 n 个 pending 出现（禁依赖第三方 awaitility；2s 超时后抛错快速失败）。 */
    public void awaitPendingCount(int n) {
        long deadline = System.currentTimeMillis() + 2000;
        while (slot.pendingPermissions().size() < n) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + n + " pending permission(s)");
            }
            sleep();
        }
    }

    public void awaitPendingQuestionCount(int n) {
        long deadline = System.currentTimeMillis() + 2000;
        while (slot.pendingQuestions().size() < n) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + n + " pending question(s)");
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Optional<Slot> findSlot(String id) {
        return sessionId.equals(id) ? Optional.of(slot) : Optional.empty();
    }
}
