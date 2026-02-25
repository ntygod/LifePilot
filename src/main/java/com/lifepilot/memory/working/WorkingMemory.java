package com.lifepilot.memory.working;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1 工作记忆服务 — 管理会话级别的短期记忆槽位。
 *
 * <p>使用 ConcurrentHashMap 管理多会话槽位，支持 Token 预算淘汰和会话 flush 到 L2 情景记忆。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class WorkingMemory {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemory.class);

    private final MemoryProperties properties;
    private final EpisodicMemory episodicMemory;

    /** 会话槽位列表。 */
    private final ConcurrentHashMap<String, List<WorkingMemorySlot>> sessions = new ConcurrentHashMap<>();

    /** 会话 Token 使用量。 */
    private final ConcurrentHashMap<String, Integer> tokenUsage = new ConcurrentHashMap<>();

    /** 会话最后活动时间。 */
    private final ConcurrentHashMap<String, Instant> lastActivity = new ConcurrentHashMap<>();

    public WorkingMemory(MemoryProperties properties, EpisodicMemory episodicMemory) {
        this.properties = properties;
        this.episodicMemory = episodicMemory;
    }

    /**
     * 追加槽位到会话，超预算时执行重要度加权淘汰。
     *
     * @param sessionId 会话 ID
     * @param slot 要追加的槽位
     */
    public void append(String sessionId, WorkingMemorySlot slot) {
        sessions.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        var slots = sessions.get(sessionId);

        slots.add(slot);
        tokenUsage.compute(sessionId, (k, v) -> (v == null ? 0 : v) + slot.tokenCount());
        lastActivity.put(sessionId, Instant.now());

        // 超预算时执行淘汰
        int budget = properties.getWorkingMemoryTokenBudget();
        while (tokenUsage.getOrDefault(sessionId, 0) > budget) {
            var victim = findEvictionCandidate(slots);
            if (victim == null) {
                log.warn("无法找到可淘汰的槽位: sessionId={}", sessionId);
                break;
            }
            slots.remove(victim);
            tokenUsage.compute(sessionId, (k, v) -> (v == null ? 0 : v) - victim.tokenCount());
            log.debug("淘汰槽位: sessionId={}, 类型={}, tokenCount={}", sessionId, victim.getClass().getSimpleName(), victim.tokenCount());
        }
    }

    /**
     * 获取会话的不可变槽位列表副本。
     *
     * @param sessionId 会话 ID
     * @return 槽位列表，会话不存在时返回空列表
     */
    public List<WorkingMemorySlot> getContext(String sessionId) {
        var slots = sessions.get(sessionId);
        if (slots == null) {
            return List.of();
        }
        synchronized (slots) {
            return List.copyOf(slots);
        }
    }

    /**
     * 获取会话的 Token 总使用量。
     *
     * @param sessionId 会话 ID
     * @return Token 总量，会话不存在时返回 0
     */
    public int getTokenCount(String sessionId) {
        return tokenUsage.getOrDefault(sessionId, 0);
    }

    /**
     * 将会话的 ConversationSlot 转换为 ConversationRecord 并持久化到 L2 情景记忆，然后清除会话。
     *
     * @param sessionId 会话 ID
     */
    public void flush(String sessionId) {
        var slots = sessions.get(sessionId);
        if (slots == null || slots.isEmpty()) {
            return;
        }

        // 提取 ConversationSlot 转换为 MessageRecord
        var now = Instant.now();
        var conversationId = UUID.randomUUID().toString();
        var messages = new ArrayList<MessageRecord>();

        synchronized (slots) {
            for (var slot : slots) {
                if (slot instanceof ConversationSlot cs) {
                    messages.add(new MessageRecord(
                            UUID.randomUUID().toString(),
                            conversationId,
                            cs.role(),
                            cs.content(),
                            null,
                            CompressionLevel.ORIGINAL,
                            cs.isPinned(),
                            cs.toolCallJson(),
                            cs.tokenCount(),
                            cs.createdAt()));
                }
            }
        }

        if (!messages.isEmpty()) {
            var record = new ConversationRecord(
                    conversationId, sessionId, "会话记录", null,
                    messages, now, now);
            episodicMemory.save(record);
            log.info("Flush 会话到 L2: sessionId={}, 消息数={}", sessionId, messages.size());
        }

        // 清除会话
        sessions.remove(sessionId);
        tokenUsage.remove(sessionId);
        lastActivity.remove(sessionId);
    }

    /**
     * 清除指定会话的所有槽位。
     *
     * @param sessionId 会话 ID
     */
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        tokenUsage.remove(sessionId);
        lastActivity.remove(sessionId);
    }

    /**
     * 返回所有活跃会话 ID 的不可变集合。
     *
     * @return 活跃会话 ID 集合
     */
    public Set<String> activeSessions() {
        return Set.copyOf(sessions.keySet());
    }

    /**
     * 查找淘汰候选槽位 — 按类型优先级和重要度排序。
     *
     * <p>淘汰优先级：ReasoningSlot → ToolResultSlot → ConversationSlot（跳过 pinned）。
     * 同类型内按 importance 升序排序，优先淘汰重要度最低的。</p>
     */
    private WorkingMemorySlot findEvictionCandidate(List<WorkingMemorySlot> slots) {
        synchronized (slots) {
            return slots.stream()
                    .filter(s -> !(s instanceof ConversationSlot cs && cs.isPinned()))
                    .min(Comparator.comparingInt(this::typePriority)
                            .thenComparing(WorkingMemorySlot::importance))
                    .orElse(null);
        }
    }

    /** 类型淘汰优先级：ReasoningSlot(0) → ToolResultSlot(1) → ConversationSlot(2)。 */
    private int typePriority(WorkingMemorySlot slot) {
        return switch (slot) {
            case ReasoningSlot _ -> 0;
            case ToolResultSlot _ -> 1;
            case ConversationSlot _ -> 2;
        };
    }
}
