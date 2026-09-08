package com.we0j.infra.filestore;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JsonFileStore 行为测试（DDD §4.6，FR-077 AC）：
 * 写入→读取一致性、损坏文件降级、读改写并发无丢写。
 */
class JsonFileStoreTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JsonFileStore store = new JsonFileStore(new FileLocks());

    @Test
    void writeThenReadRoundTrips(@TempDir Path dir) {
        Path file = dir.resolve("todos.json");
        Map<String, Object> value = new HashMap<>();
        value.put("items", List.of("a", "b"));
        value.put("count", 2);

        store.writeAtomic(file, value);
        Map<String, Object> read = store.read(file, MAP_TYPE, new HashMap<>());

        assertThat(read).containsEntry("count", 2)
                .containsEntry("items", List.of("a", "b"));
    }

    @Test
    void missingFileReturnsFallback(@TempDir Path dir) {
        Map<String, Object> fallback = new HashMap<>();
        assertThat(store.read(dir.resolve("nope.json"), MAP_TYPE, fallback)).isSameAs(fallback);
    }

    @Test
    void corruptFileIsBackedUpAndFallbackReturned(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.json");
        Files.writeString(file, "{bad json");
        Map<String, Object> fallback = new HashMap<>();

        Map<String, Object> result = store.read(file, MAP_TYPE, fallback);

        assertThat(result).isSameAs(fallback);
        assertThat(file).doesNotExist();
        try (var stream = Files.list(dir)) {
            List<Path> backups = stream
                    .filter(p -> p.getFileName().toString().startsWith("state.json.corrupt-"))
                    .toList();
            assertThat(backups).hasSize(1);
            assertThat(backups.getFirst()).content().isEqualTo("{bad json");
        }
    }

    @Test
    void concurrentUpdateLosesNoWrites(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("counter.json");
        int threads = 10;
        int increments = 100;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < increments; i++) {
                        store.update(file, MAP_TYPE, new HashMap<>(), current -> {
                            Object raw = current.get("count");
                            long count = raw instanceof Number n ? n.longValue() : 0L;
                            Map<String, Object> next = new HashMap<>(current);
                            next.put("count", count + 1);
                            return next;
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        Map<String, Object> finalState = store.read(file, MAP_TYPE, Map.of("count", -1));
        assertThat(((Number) finalState.get("count")).longValue())
                .isEqualTo((long) threads * increments);
    }
}
