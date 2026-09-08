package com.we0j.agent.revert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.we0j.agent.session.MessageWithParts;
import com.we0j.agent.snapshot.SnapshotService;
import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.Message;
import com.we0j.common.domain.message.TimeCreated;
import com.we0j.common.domain.message.TimeCreatedCompleted;
import com.we0j.common.domain.message.UserMessage;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.StepFinishPart;
import com.we0j.common.domain.part.StepStartPart;
import com.we0j.common.domain.part.Tokens;
import com.we0j.common.domain.session.RevertMode;
import com.we0j.common.domain.session.RevertRecord;
import com.we0j.common.exception.SnapshotException;
import com.we0j.infra.bus.Bus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M4 回滚服务测试：假 RevertSessionPort（内存）+ SnapshotService 桩 + 假 MessageDeleter。
 * 不触 DB / 真实 git / 真实 AgentLoop（接线在主线程）。
 */
class RevertServiceTest {

    // ── fakes ────────────────────────────────────────────────────────────────

    static final class FakePort implements RevertSessionPort {
        final List<MessageWithParts> history = new ArrayList<>();
        RevertRecord revert;

        @Override
        public List<MessageWithParts> history(String sessionId) {
            return history;
        }

        @Override
        public RevertRecord getRevert(String sessionId) {
            return revert;
        }

        @Override
        public void setRevert(String sessionId, RevertRecord record) {
            this.revert = record;
        }

        @Override
        public void clearRevert(String sessionId) {
            this.revert = null;
        }
    }

    /** SnapshotService 桩：可编程 hash + 调用记录。 */
    static final class StubSnapshot implements SnapshotService {
        List<String> trackResults = new ArrayList<>();
        int trackCalls;
        Map<String, List<String>> patchResults = new HashMap<>();
        final List<String> patchCalls = new ArrayList<>();
        List<String> revertedFiles;
        String revertedTo;
        String restoredHash;
        boolean available = true;

        @Override
        public Optional<String> track(String sessionId) {
            trackCalls++;
            String h = trackResults.isEmpty() ? "hash-" + trackCalls : trackResults.remove(0);
            return h == null ? Optional.empty() : Optional.of(h);
        }

        @Override
        public List<String> patch(String sessionId, String treeHash) {
            patchCalls.add(treeHash);
            return patchResults.getOrDefault(treeHash, List.of());
        }

        @Override
        public FullDiff diffFull(String sessionId, String hash1, String hash2) {
            return FullDiff.empty();
        }

        @Override
        public void restore(String sessionId, String treeHash) {
            restoredHash = treeHash;
        }

        @Override
        public void revert(String sessionId, List<String> files, String treeHash) {
            revertedFiles = files;
            revertedTo = treeHash;
        }

        @Override
        public boolean available() {
            return available;
        }
    }

    static final class RecordingDeleter implements MessageDeleter {
        final List<List<String>> deleted = new ArrayList<>();

        @Override
        public void delete(String sessionId, List<String> messageIds) {
            deleted.add(messageIds);
        }
    }

    // ── fixture builders ─────────────────────────────────────────────────────

    private static MessageWithParts user(String id) {
        UserMessage m = new UserMessage(id, "s", new TimeCreated(Instant.now()),
                null, null, null, null, null, null, null, null, null);
        return new MessageWithParts(m, List.of());
    }

    private static MessageWithParts assistant(String id, String startSnap, String finishSnap) {
        AssistantMessage m = new AssistantMessage(id, "s",
                new TimeCreatedCompleted(Instant.now(), Instant.now()),
                null, null, Tokens.empty(), null, null, null, null, null);
        List<Part> parts = new ArrayList<>();
        if (startSnap != null) parts.add(new StepStartPart(id + "-ss", id, "s", startSnap));
        if (finishSnap != null) {
            parts.add(new StepFinishPart(id + "-sf", id, "s", finishSnap, BigDecimal.ZERO, Tokens.empty()));
        }
        return new MessageWithParts(m, parts);
    }

    private static MessageWithParts hiddenUser(String id) {
        Message m = new UserMessage(id, "s", new TimeCreated(Instant.now()),
                null, null, null, null, null, null, null, null, Map.of("hidden", true));
        return new MessageWithParts(m, List.of());
    }

    FakePort port;
    StubSnapshot snap;
    RecordingDeleter deleter;
    RevertService svc;

    @BeforeEach
    void setUp() {
        port = new FakePort();
        snap = new StubSnapshot();
        deleter = new RecordingDeleter();
        svc = new RevertService(port, snap, new Bus(), deleter);
        // u1 → a1(s1..s2) → u2 → a2(s3..s4)
        port.history.add(user("u1"));
        port.history.add(assistant("a1", "s1", "s2"));
        port.history.add(user("u2"));
        port.history.add(assistant("a2", "s3", "s4"));
    }

    // ── anchors ──────────────────────────────────────────────────────────────

    @Test
    void listAnchorsSkipsHiddenAndCarriesFirstStepStartSnapshot() {
        port.history.add(2, hiddenUser("h1"));
        List<RewindAnchor> anchors = svc.listAnchors("s");
        assertThat(anchors).extracting(RewindAnchor::messageId).containsExactly("u1", "u2");
        assertThat(anchors.get(0).snapshot()).isEqualTo("s1");
        assertThat(anchors.get(1).snapshot()).isEqualTo("s3");
    }

    // ── CONVERSATION ─────────────────────────────────────────────────────────

    @Test
    void conversationModeMarksBoundaryWithoutCodeOps() {
        RevertService.RevertResult r = svc.revert("s", "u1", RevertMode.CONVERSATION);

        assertThat(r.record().mode()).isEqualTo(RevertMode.CONVERSATION);
        assertThat(r.record().targetMessageId()).isEqualTo("u1");
        assertThat(r.record().targetSnapshot()).isEqualTo("s1");
        assertThat(r.revertedFileCount()).isZero();
        assertThat(snap.trackCalls).isZero();                          // 不动代码
        assertThat(snap.revertedTo).isNull();
        assertThat(port.revert).isNotNull();                           // 边界已标记（软删除）
        assertThat(port.revert.mode()).isEqualTo(RevertMode.CONVERSATION);
    }

    // ── BOTH ─────────────────────────────────────────────────────────────────

    @Test
    void bothModeTracksUndoThenPatchesThenReverts() {
        snap.trackResults.add("undo-h");
        snap.patchResults.put("s1", List.of("a.txt", "b.txt"));

        RevertService.RevertResult r = svc.revert("s", "u1", RevertMode.BOTH);

        assertThat(snap.trackCalls).isEqualTo(1);                      // 先 track 存 undo
        assertThat(snap.patchCalls).containsExactly("s1");             // patch 以目标快照为基线
        assertThat(snap.revertedTo).isEqualTo("s1");
        assertThat(snap.revertedFiles).containsExactly("a.txt", "b.txt");
        assertThat(r.revertedFileCount()).isEqualTo(2);

        RevertRecord rec = port.revert;
        assertThat(rec).isNotNull();
        assertThat(rec.mode()).isEqualTo(RevertMode.BOTH);
        assertThat(rec.snapshot()).isEqualTo("undo-h");                // unrevert 依据
        assertThat(rec.targetSnapshot()).isEqualTo("s1");
        assertThat(rec.revertedFiles()).containsExactly("a.txt", "b.txt");
    }

    @Test
    void bothModeFailsLoudlyWhenAnchorHasNoSnapshot() {
        port.history.removeIf(m -> m.message().id().equals("a1"));      // u1 之后无 StepStartPart 锚点
        assertThatThrownBy(() -> svc.revert("s", "u1", RevertMode.BOTH))
                .isInstanceOf(SnapshotException.class)
                .hasMessageContaining("No snapshot recorded");
        assertThat(snap.trackCalls).isZero();
    }

    // ── unrevert / cleanup ───────────────────────────────────────────────────

    @Test
    void unrevertRestoresCodeAndClearsBoundary() {
        svc.revert("s", "u1", RevertMode.BOTH);
        assertThat(port.revert).isNotNull();

        svc.unrevert("s");

        assertThat(snap.restoredHash).isNotNull();                     // restore(undoSnapshot)
        assertThat(port.revert).isNull();                              // 边界已清除
    }

    @Test
    void unrevertWithoutPendingThrows() {
        assertThatThrownBy(() -> svc.unrevert("s")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cleanupPhysicallyDeletesMessagesAfterTargetThenClears() {
        svc.revert("s", "u1", RevertMode.CONVERSATION);

        svc.cleanup("s");

        assertThat(deleter.deleted).hasSize(1);
        assertThat(deleter.deleted.get(0)).containsExactly("a1", "u2", "a2");
        assertThat(port.revert).isNull();
    }

    @Test
    void cleanupWithoutPendingIsNoop() {
        svc.cleanup("s");
        assertThat(deleter.deleted).isEmpty();
    }
}
