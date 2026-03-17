package com.lifepilot.memory.working;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.memory.trace.MemoryEvent;
import com.lifepilot.memory.trace.MemoryEventRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
    private final TokenBudgetAllocator tokenBudgetAllocator;
    private final SlotEvictionPolicy slotEvictionPolicy;
    private final MemoryEventRecorder memoryEventRecorder;
    private final WorkingMemoryWal wal;

    /** 会话槽位列表。 */
    private final ConcurrentHashMap<String, List<WorkingMemorySlot>> sessions = new ConcurrentHashMap<>();

    /** 会话 Token 使用量。 */
    private final ConcurrentHashMap<String, Integer> tokenUsage = new ConcurrentHashMap<>();

    /** 会话最后活动时间。 */
    private final ConcurrentHashMap<String, Instant> lastActivity = new ConcurrentHashMap<>();

    /**
     * 完整构造函数（含 WAL 持久化支持）。
     */
    public WorkingMemory(MemoryProperties properties,
                         EpisodicMemory episodicMemory,
                         TokenBudgetAllocator tokenBudgetAllocator,
                         SlotEvictionPolicy slotEvictionPolicy,
                         MemoryEventRecorder memoryEventRecorder,
                         WorkingMemoryWal wal) {
        this.properties = properties;
        this.episodicMemory = episodicMemory;
        this.tokenBudgetAllocator = tokenBudgetAllocator;
        this.slotEvictionPolicy = slotEvictionPolicy;
        this.memoryEventRecorder = memoryEventRecorder;
        this.wal = wal;
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

        // 同步写入 WAL（SQLite WAL 模式下单行 insert 微秒级，失败不阻塞主流程）
        if (wal != null) {
            wal.append(sessionId, slot);
        }

        // 记录工作记忆追加事件（观测性失败不影响主流程）
        if (memoryEventRecorder != null) {
            try {
                MemoryEvent event = MemoryEvent.create(
                        "FORMATION",
                        "L1",
                        sessionId,
                        null,
                        null,
                        "L1_APPEND",
                        "Append slot to working memory: " + slot.getClass().getSimpleName(),
                        Map.of(
                                "tokenCount", slot.tokenCount(),
                                "importance", slot.importance()
                        )
                );
                memoryEventRecorder.record(event);
            } catch (Exception ignored) {
                // ignore
            }
        }

        // 超预算时执行淘汰（通过策略接口 + Token 预算分配器）
        int totalBudget = properties.getWorkingMemoryTokenBudget();
        // 使用 TokenBudgetAllocator 动态计算工作记忆可用预算
        BudgetAllocation allocation = this.tokenBudgetAllocator.allocate(
                totalBudget,
                slots.size(),
                0.0f,
                true
        );
        int budget = allocation.currentSessionBudget();
        int currentUsage = tokenUsage.getOrDefault(sessionId, 0);
        while (currentUsage > budget) {
            var victim = slotEvictionPolicy.selectEvictionCandidate(sessionId, slots, currentUsage, budget);
            if (victim == null) {
                log.warn("无法找到可淘汰的槽位: sessionId={}", sessionId);
                break;
            }
            slots.remove(victim);
            currentUsage -= victim.tokenCount();
            tokenUsage.put(sessionId, currentUsage);
            log.debug("淘汰槽位: sessionId={}, 类型={}, tokenCount={}", sessionId,
                    victim.getClass().getSimpleName(), victim.tokenCount());
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
     * 将会话的 {@link ConversationSlot} 集合转换为 {@link ConversationRecord} 并持久化到 L2 情景记忆，然后清除会话。
     *
     * <p>
     * 这是新推荐的统一入口：需要在调用方明确传入本次会话的 {@code goal}，
     * 以便在 L2 中区分不同类型的意图与任务。
     * </p>
     *
     * <p>
     * 该方法既可用于「会话真正结束」的显式触发场景（例如前端点击结束会话），
     * 也可被空闲/过期清理任务复用。
     * </p>
     *
     * @param sessionId 会话 ID
     * @param goal      会话目标/意图摘要（允许为 {@code null} 或空串，此时使用降级占位）
     * @return 持久化后的 {@link ConversationRecord}，如无可持久化消息则返回 {@code null}
     */
    public ConversationRecord flush(String sessionId, String goal) {
        var slots = sessions.get(sessionId);
        if (slots == null || slots.isEmpty()) {
            return null;
        }

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

        ConversationRecord record = null;
        if (!messages.isEmpty()) {
            String normalizedGoal = (goal == null || goal.isBlank())
                    ? "会话记录"
                    : goal;
            record = new ConversationRecord(
                    conversationId, sessionId, normalizedGoal, null,
                    messages, now, now);
            episodicMemory.save(record);
            log.info("Flush 会话到 L2: sessionId={}, 消息数={}", sessionId, messages.size());

            if (memoryEventRecorder != null) {
                try {
                    MemoryEvent event = MemoryEvent.create(
                            "FORMATION",
                            "L2",
                            sessionId,
                            conversationId,
                            null,
                            "L1_FLUSH",
                            "Flush working memory conversation to L2",
                            java.util.Map.of(
                                    "messageCount", messages.size(),
                                    "goal", normalizedGoal
                            )
                    );
                    memoryEventRecorder.record(event);
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }

        // 清除会话
        sessions.remove(sessionId);
        tokenUsage.remove(sessionId);
        lastActivity.remove(sessionId);

        // flush 成功后清除该会话的 WAL 记录
        if (wal != null) {
            wal.clearSession(sessionId);
        }

        return record;
    }



    /**
     * 将所有活跃会话的 L1 数据 flush 到 L2，用于系统关闭等场景防止数据丢失。
     *
     * <p>逐个会话 flush，单个会话失败不影响其他会话。</p>
     *
     * @param goal flush 目标描述
     */
    public void flushAll(String goal) {
        var sessionIds = List.copyOf(sessions.keySet());
        if (sessionIds.isEmpty()) {
            return;
        }
        log.info("批量 flush 所有活跃会话到 L2: 会话数={}, goal={}", sessionIds.size(), goal);
        for (var sessionId : sessionIds) {
            try {
                flush(sessionId, goal);
            } catch (Exception e) {
                log.warn("批量 flush 会话失败: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 清理空闲会话：超过给定空闲阈值后，自动 flush 到 L2 并从 L1 中移除。
     *
     * <p>由定时任务调用，避免工作记忆无限增长。</p>
     *
     * @param idleThreshold 空闲阈值
     */
    public void cleanupIdleSessions(Duration idleThreshold) {
        if (idleThreshold == null || idleThreshold.isNegative() || idleThreshold.isZero()) {
            return;
        }

        var now = Instant.now();
        var idleSessions = new ArrayList<String>();

        for (var entry : lastActivity.entrySet()) {
            var lastActive = entry.getValue();
            if (lastActive == null) {
                continue;
            }
            var idleDuration = Duration.between(lastActive, now);
            if (idleDuration.compareTo(idleThreshold) > 0) {
                idleSessions.add(entry.getKey());
            }
        }

        if (idleSessions.isEmpty()) {
            return;
        }

        for (var sessionId : idleSessions) {
            try {
                flush(sessionId, "空闲会话清理");
                log.info("清理空闲会话并 flush 到 L2: sessionId={}, idleMinutes>{}",
                        sessionId, idleThreshold.toMinutes());
            } catch (Exception e) {
                log.warn("清理空闲会话失败: sessionId={}, error={}",
                        sessionId, e.getMessage());
            }
        }
    }

}
