package com.we0j.tool.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.common.domain.permission.PermissionName;
import com.we0j.common.domain.permission.PermissionRequest;
import com.we0j.common.domain.permission.PermissionToolRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FR-086：同 permission + 相同 patterns 连续 ≥5 次触发。 */
class DoomLoopDetectorTest {

    private static PermissionRequest req(String sessionId, PermissionName name, List<String> patterns) {
        return new PermissionRequest("id-" + System.nanoTime(), sessionId, name, patterns,
                Map.of(), "?", List.of(), new PermissionToolRef("m", "c"));
    }

    @Test
    void triggersOnFifthIdenticalRequest() {
        DoomLoopDetector d = new DoomLoopDetector();
        PermissionRequest r = req("s1", PermissionName.BASH, List.of("git push *"));
        for (int i = 1; i <= 4; i++) {
            assertThat(d.isRepeating(r)).as("call %d", i).isFalse();
        }
        assertThat(d.isRepeating(r)).as("call 5").isTrue();
        assertThat(d.repeatCount(r)).isEqualTo(5);
        assertThat(d.isRepeating(r)).as("call 6 keeps firing").isTrue();
    }

    @Test
    void distinctInputResetsAllCounters() {
        DoomLoopDetector d = new DoomLoopDetector();
        PermissionRequest a = req("s1", PermissionName.BASH, List.of("git push *"));
        d.isRepeating(a);
        d.isRepeating(a);
        d.recordDistinct("s1", PermissionName.WRITE, List.of("other.txt"));
        assertThat(d.repeatCount(a)).isZero();
        assertThat(d.isRepeating(a)).isFalse();
    }

    @Test
    void differentPatternsAreDifferentKeys() {
        DoomLoopDetector d = new DoomLoopDetector();
        PermissionRequest a = req("s1", PermissionName.BASH, List.of("git push *"));
        PermissionRequest b = req("s1", PermissionName.BASH, List.of("npm install"));
        for (int i = 0; i < 4; i++) {
            assertThat(d.isRepeating(a)).isFalse();
            assertThat(d.isRepeating(b)).isFalse();
        }
        assertThat(d.isRepeating(a)).isTrue();
        assertThat(d.isRepeating(b)).isTrue();
    }

    @Test
    void sessionsAreIsolatedAndResettable() {
        DoomLoopDetector d = new DoomLoopDetector();
        PermissionRequest r1 = req("s1", PermissionName.BASH, List.of("git push *"));
        PermissionRequest r2 = req("s2", PermissionName.BASH, List.of("git push *"));
        for (int i = 0; i < 3; i++) d.isRepeating(r1);
        assertThat(d.isRepeating(r2)).isFalse();          // s2 独立计数
        d.reset("s1");
        assertThat(d.repeatCount(r1)).isZero();
        assertThat(d.isRepeating(r1)).isFalse();
    }
}
