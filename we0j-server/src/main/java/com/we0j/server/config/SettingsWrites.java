package com.we0j.server.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.we0j.infra.config.SettingsStore;
import com.we0j.infra.path.DirectoryLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 用户级 settings.json 的读-改-写回（{@link SettingsStore} 只有读/失效 API，
 * 写回属控制台低频操作，这里直接改 JSON 文件字段后 refresh() 触发 mtime 热加载）。
 *
 * <p>禁 synchronized：ReentrantLock 串行化整文件读改写（虚拟线程安全）。
 */
public final class SettingsWrites {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();
    private static final ReentrantLock FILE_LOCK = new ReentrantLock();

    private SettingsWrites() {
    }

    /** 写 {@code web.token}（首启随机 token 落盘，DDD §8.1）。 */
    public static void writeWebToken(SettingsStore store, String token) throws IOException {
        mutate(store, root -> childObject(root, "web").put("token", token));
    }

    /** 增改 {@code common.providers.<id>} 的 enabled / apiKey / apiBase（null 字段不动）。 */
    public static void updateProvider(SettingsStore store, String providerId,
                                      Boolean enabled, String apiKey, String apiBase)
            throws IOException {
        mutate(store, root -> {
            ObjectNode target = childObject(childObject(root, "common"), "providers");
            // 上面得到的是 providers 容器，再取/建 providerId 子对象：
            ObjectNode provider = childObject(target, providerId);
            if (enabled != null) {
                provider.put("enabled", enabled);
            }
            if (apiKey != null) {
                provider.put("apiKey", apiKey);
            }
            if (apiBase != null) {
                provider.put("apiBase", apiBase);
            }
        });
    }

    /** 写 {@code common.chat.default = {provider, model}}（登录页厂家切换；Loop 下一轮热生效）。 */
    public static void writeChatDefault(SettingsStore store, String providerId, String model)
            throws IOException {
        mutate(store, root -> {
            ObjectNode chat = childObject(childObject(root, "common"), "chat");
            ObjectNode def = childObject(chat, "default");
            def.put("provider", providerId);
            def.put("model", model);
        });
    }

    @FunctionalInterface
    private interface Editor {
        void apply(ObjectNode root);
    }

    private static void mutate(SettingsStore store, Editor editor) throws IOException {
        FILE_LOCK.lock();
        try {
            Path file = DirectoryLayout.userSettings();
            ObjectNode root = readRoot(file);
            editor.apply(root);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, PRETTY.writeValueAsString(root), StandardCharsets.UTF_8);
            store.refresh();                       // 指纹缓存失效 → 下次 current() 读到新值
        } finally {
            FILE_LOCK.unlock();
        }
    }

    private static ObjectNode readRoot(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return MAPPER.createObjectNode();
        }
        JsonNode parsed = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        return parsed != null && parsed.isObject() ? (ObjectNode) parsed : MAPPER.createObjectNode();
    }

    /** path(key)，缺失则创建空对象节点并挂回 parent。 */
    private static ObjectNode childObject(ObjectNode parent, String key) {
        JsonNode existing = parent.get(key);
        if (existing != null && existing.isObject()) {
            return (ObjectNode) existing;
        }
        ObjectNode created = parent.putObject(key);
        if (existing != null) {
            // 原值是数组/标量等非法形态：putObject 已覆盖，语义按"重建"处理。
            created.removeAll();
        }
        return created;
    }
}
