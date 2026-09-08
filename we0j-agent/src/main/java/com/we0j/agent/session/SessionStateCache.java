package com.we0j.agent.session;

import com.we0j.common.domain.message.AssistantMessage;
import com.we0j.common.domain.message.Message;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.ReasoningPart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.session.RuntimeState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * 会话内存权威副本（DDD §5.1 / §5.2.2 核心不变式）：Loop 读历史、流式 delta 累积、
 * RuntimeState 读写都以本缓存为准；DB 只是节流落盘的投影。
 *
 * <p>结构（per session）：messages 插入序列表、parts 按 messageId 有序列表、
 * partIndex（partId → Part，O(1) delta 更新）、runtimeState。
 *
 * <p>并发：{@link ReentrantReadWriteLock} 保护跨结构的一致性变更（禁 synchronized，§11.1）；
 * 底层容器为并发安全类型，读侧即使不加锁也不会撕裂。高频 delta 路径只做内存替换，
 * 不触 DB（落盘由 PartWriteThrottler 节流）。
 */
@Component
public final class SessionStateCache {

    private static final class Entry {
        final List<Message> messages = new CopyOnWriteArrayList<>();
        /** messageId → parts（插入序）。 */
        final ConcurrentMap<String, List<Part>> partsByMessage = new ConcurrentHashMap<>();
        /** partId → part（O(1) 定位）。 */
        final ConcurrentMap<String, Part> partIndex = new ConcurrentHashMap<>();
        volatile RuntimeState runtimeState = RuntimeState.empty();
        final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    }

    private final ConcurrentMap<String, Entry> byId = new ConcurrentHashMap<>();

    /** 新会话建空条目。 */
    public void init(String sessionId) {
        byId.computeIfAbsent(sessionId, k -> new Entry());
    }

    /** 装载条目：不存在则创建，存在则整体替换（resume 装载语义）。 */
    public void load(String sessionId, List<Message> messages,
                     Map<String, List<Part>> partsByMessage, RuntimeState runtimeState) {
        Entry e = byId.computeIfAbsent(sessionId, k -> new Entry());
        e.rw.writeLock().lock();
        try {
            e.messages.clear();
            e.partsByMessage.clear();
            e.partIndex.clear();
            if (messages != null) e.messages.addAll(messages);
            if (partsByMessage != null) {
                partsByMessage.forEach((mid, parts) -> {
                    List<Part> list = new CopyOnWriteArrayList<>(parts);
                    e.partsByMessage.put(mid, list);
                    for (Part p : parts) e.partIndex.put(p.id(), p);
                });
            }
            e.runtimeState = runtimeState == null ? RuntimeState.empty() : runtimeState;
        } finally {
            e.rw.writeLock().unlock();
        }
    }

    public boolean has(String sessionId) {
        return byId.containsKey(sessionId);
    }

    /** 按插入序组装的完整历史（读锁）。 */
    public List<MessageWithParts> history(String sessionId) {
        Entry e = require(sessionId);
        e.rw.readLock().lock();
        try {
            List<MessageWithParts> out = new ArrayList<>(e.messages.size());
            for (Message m : e.messages) {
                List<Part> parts = e.partsByMessage.getOrDefault(m.id(), List.of());
                out.add(new MessageWithParts(m, new ArrayList<>(parts)));
            }
            return out;
        } finally {
            e.rw.readLock().unlock();
        }
    }

    public Optional<Message> message(String sessionId, String messageId) {
        Entry e = require(sessionId);
        e.rw.readLock().lock();
        try {
            return e.messages.stream().filter(m -> m.id().equals(messageId)).findFirst();
        } finally {
            e.rw.readLock().unlock();
        }
    }

    public List<Part> partsOfMessage(String sessionId, String messageId) {
        Entry e = require(sessionId);
        e.rw.readLock().lock();
        try {
            return new ArrayList<>(e.partsByMessage.getOrDefault(messageId, List.of()));
        } finally {
            e.rw.readLock().unlock();
        }
    }

    public void appendMessage(String sessionId, Message message) {
        Entry e = require(sessionId);
        e.rw.writeLock().lock();
        try {
            e.messages.add(message);
            e.partsByMessage.computeIfAbsent(message.id(), k -> new CopyOnWriteArrayList<>());
        } finally {
            e.rw.writeLock().unlock();
        }
    }

    /** 按 id 原位替换消息（tokens/finish/error/timeCompleted 演进）。id 不存在时抛错。 */
    public void updateMessage(String sessionId, Message message) {
        Entry e = require(sessionId);
        e.rw.writeLock().lock();
        try {
            for (int i = 0; i < e.messages.size(); i++) {
                if (e.messages.get(i).id().equals(message.id())) {
                    e.messages.set(i, message);
                    return;
                }
            }
            throw new IllegalArgumentException("message not in cache: " + message.id());
        } finally {
            e.rw.writeLock().unlock();
        }
    }

    /** 删除消息及其全部 Part（本轮失败回退 discardAssistantMessage 用）。 */
    public void removeMessage(String sessionId, String messageId) {
        Entry e = require(sessionId);
        e.rw.writeLock().lock();
        try {
            e.messages.removeIf(m -> m.id().equals(messageId));
            List<Part> parts = e.partsByMessage.remove(messageId);
            if (parts != null) parts.forEach(p -> e.partIndex.remove(p.id()));
        } finally {
            e.rw.writeLock().unlock();
        }
    }

    /** 追加 Part；同 id 已存在则按 updatePart 语义替换（upsert）。 */
    public void appendPart(String sessionId, Part part) {
        Entry e = require(sessionId);
        e.rw.writeLock().lock();
        try {
            doUpsertPart(e, part);
        } finally {
            e.rw.writeLock().unlock();
        }
    }

    /** upsert 语义的 Part 替换。返回替换前的旧值（可能为 null）。 */
    public Part updatePart(String sessionId, Part part) {
        Entry e = require(sessionId);
        e.rw.writeLock().lock();
        try {
            Part old = e.partIndex.get(part.id());
            doUpsertPart(e, part);
            return old;
        } finally {
            e.rw.writeLock().unlock();
        }
    }

    /**
     * 高频流式增量：仅改内存权威副本（DB 由 PartWriteThrottler 节流、Bus delta 另行发布）。
     * field 目前支持 "text"（TextPart / ReasoningPart）；未知字段忽略并返回原值。
     */
    public Optional<Part> appendDelta(String sessionId, String partId, String field, String delta) {
        Entry e = require(sessionId);
        e.rw.writeLock().lock();
        try {
            Part p = e.partIndex.get(partId);
            if (p == null || delta == null || delta.isEmpty()) return Optional.ofNullable(p);
            Part next;
            if ("text".equals(field)) {
                if (p instanceof TextPart tp) {
                    next = new TextPart(tp.id(), tp.messageId(), tp.sessionId(),
                            tp.text() + delta, tp.synthetic(), tp.ignored(), tp.displayOnly(),
                            tp.time(), tp.metadata());
                } else if (p instanceof ReasoningPart rp) {
                    next = new ReasoningPart(rp.id(), rp.messageId(), rp.sessionId(),
                            rp.text() + delta, rp.metadata(), rp.time());
                } else {
                    return Optional.of(p);
                }
            } else {
                return Optional.of(p);
            }
            doUpsertPart(e, next);
            return Optional.of(next);
        } finally {
            e.rw.writeLock().unlock();
        }
    }

    /** O(1) Part 查询。 */
    public Optional<Part> part(String partId) {
        for (Entry e : byId.values()) {
            Part p = e.partIndex.get(partId);
            if (p != null) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** 删除 Part（中断清理 / compact 裁剪）。返回被删的 Part。 */
    public Optional<Part> removePart(String sessionId, String partId) {
        Entry e = require(sessionId);
        e.rw.writeLock().lock();
        try {
            Part p = e.partIndex.remove(partId);
            if (p == null) return Optional.empty();
            List<Part> list = e.partsByMessage.get(p.messageId());
            if (list != null) list.removeIf(x -> x.id().equals(partId));
            return Optional.of(p);
        } finally {
            e.rw.writeLock().unlock();
        }
    }

    public RuntimeState runtimeState(String sessionId) {
        return require(sessionId).runtimeState;
    }

    /** 原子演进 RuntimeState（写锁内 read-modify-write）。 */
    public RuntimeState updateRuntimeState(String sessionId, UnaryOperator<RuntimeState> fn) {
        Entry e = require(sessionId);
        e.rw.writeLock().lock();
        try {
            RuntimeState next = fn.apply(e.runtimeState);
            e.runtimeState = next == null ? RuntimeState.empty() : next;
            return e.runtimeState;
        } finally {
            e.rw.writeLock().unlock();
        }
    }

    /** 最后一条 assistant 消息（中断清理定位用）。 */
    public Optional<AssistantMessage> lastAssistant(String sessionId) {
        Entry e = require(sessionId);
        e.rw.readLock().lock();
        try {
            AssistantMessage last = null;
            for (Message m : e.messages) {
                if (m instanceof AssistantMessage a) last = a;
            }
            return Optional.ofNullable(last);
        } finally {
            e.rw.readLock().unlock();
        }
    }

    /** 会话条目快照的 partId → messageId 视图（清理辅助）；仅测试/诊断使用。 */
    public Map<String, String> partMessageIndex(String sessionId) {
        Entry e = require(sessionId);
        Map<String, String> out = new HashMap<>();
        e.partIndex.forEach((pid, p) -> out.put(pid, p.messageId()));
        return out;
    }

    private void doUpsertPart(Entry e, Part part) {
        e.partIndex.put(part.id(), part);
        List<Part> list = e.partsByMessage.computeIfAbsent(part.messageId(),
                k -> new CopyOnWriteArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(part.id())) {
                list.set(i, part);
                return;
            }
        }
        list.add(part);
    }

    private Entry require(String sessionId) {
        Entry e = byId.get(sessionId);
        if (e == null) throw new IllegalArgumentException("session not initialized in cache: " + sessionId);
        return e;
    }
}
