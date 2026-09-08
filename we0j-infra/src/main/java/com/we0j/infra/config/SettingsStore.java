package com.we0j.infra.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.we0j.common.exception.ConfigValidationException;
import com.we0j.infra.path.DirectoryLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * 分层配置存储（FR-14 / DDD §4.4）。
 *
 * <p>user（{@code ~/.we0j/settings.json}）→ project（{@code <root>/.we0j/settings.json}）
 * 深合并后反序列化并校验。缓存键 = 各层文件 (path, mtime, size) 指纹组合，任一变化即 miss
 * ——实现 mtime 热加载。用 {@link java.util.concurrent.locks.ReentrantLock} 双检，
 * 禁 {@code synchronized}（虚拟线程 pinning，§11.1）。
 */
@Component
public final class SettingsStore {

    private final ObjectMapper mapper;
    private final ConfigValidator validator;

    /** 缓存键 = 各层文件的 (path, lastModified, size) 组合。任一变化即 miss。 */
    private record CacheKey(List<String> parts) {}

    private volatile CacheKey lastKey;
    private volatile Settings cached;
    private final java.util.concurrent.locks.ReentrantLock lock =
            new java.util.concurrent.locks.ReentrantLock();

    public SettingsStore() {
        this(ConfigMappers.mapper(), new ConfigValidator());
    }

    public SettingsStore(ObjectMapper mapper, ConfigValidator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    /** 读取当前生效配置：命中缓存直接返回；否则加锁双检后重载 user←project。 */
    public Settings current(Path projectRoot) {
        CacheKey key = keyOf(projectRoot);
        Settings local = cached;
        if (local != null && key.equals(lastKey)) return local;
        lock.lock();
        try {
            if (cached != null && key.equals(lastKey)) return cached;
            JsonNode userNode = readNode(DirectoryLayout.userSettings());
            JsonNode projectNode = readNode(projectRoot.resolve(".we0j").resolve("settings.json"));
            JsonNode merged = LayeredConfigMerger.deepMerge(userNode, projectNode);
            Settings s = treeToSettings(merged);
            validator.validate(s, merged);           // 抛 ConfigValidationException
            cached = s;
            lastKey = key;
            return s;
        } finally {
            lock.unlock();
        }
    }

    /** 清空缓存（配置写入后或 /reload 时调用）。 */
    public void refresh() {
        cached = null;
        lastKey = null;
    }

    /** 强制重载并返回。 */
    public Settings refresh(Path projectRoot) {
        refresh();
        return current(projectRoot);
    }

    private Settings treeToSettings(JsonNode merged) {
        try {
            return mapper.treeToValue(merged, Settings.class);
        } catch (IOException e) {
            throw new ConfigValidationException("failed to parse settings: " + e.getMessage(), e);
        }
    }

    private CacheKey keyOf(Path projectRoot) {
        return new CacheKey(Stream.of(
                DirectoryLayout.userSettings(),
                projectRoot.resolve(".we0j").resolve("settings.json"))
                .map(SettingsStore::fingerprint).toList());
    }

    private static String fingerprint(Path p) {
        try {
            return p + "::" + Files.getLastModifiedTime(p).toMillis() + "::" + Files.size(p);
        } catch (IOException e) {
            return p + "::missing";
        }
    }

    /** 文件不存在/不可读时返回空对象节点（该层贡献为空）。 */
    static JsonNode readNode(Path p) {
        try {
            if (!Files.isRegularFile(p)) return ConfigMappers.mapper().createObjectNode();
            return ConfigMappers.mapper().readTree(Files.readString(p, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConfigValidationException("failed to load settings from " + p + ": " + e.getMessage(), e);
        }
    }
}
