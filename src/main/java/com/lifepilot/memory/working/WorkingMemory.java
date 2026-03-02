package com.lifepilot.memory.working;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
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

    /** 会话槽位列表。 */
    private final ConcurrentHashMap<String, List<WorkingMemorySlot>> sessions = new ConcurrentHashMap<>();

    /** 会话 Token 使用量。 */
    private final ConcurrentHashMap<String, Integer> tokenUsage = new ConcurrentHashMap<>();

    /** 会话最后活动时间。 */
    private final ConcurrentHashMap<String, Instant> lastActivity = new ConcurrentHashMap<>();

    /**
     * 使用显式策略的构造函数。
     */
    public WorkingMemory(MemoryProperties properties,
                         EpisodicMemory episodicMemory,
                         TokenBudgetAllocator tokenBudgetAllocator,
                         SlotEvictionPolicy slotEvictionPolicy) {
        this.properties = properties;
        this.episodicMemory = episodicMemory;
        this.tokenBudgetAllocator = tokenBudgetAllocator;
        this.slotEvictionPolicy = slotEvictionPolicy;
    }

    /**
     * 兼容旧调用方的构造函数。
     *
     * <p>在未显式提供策略实例时，使用默认策略。</p>
     */
    public WorkingMemory(MemoryProperties properties, EpisodicMemory episodicMemory) {
        this(properties, episodicMemory, new TokenBudgetAllocator(properties), new DefaultSlotEvictionPolicy());
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

        // 超预算时执行淘汰（通过策略接口 + Token 预算分配器）
        int totalBudget = properties.getWorkingMemoryTokenBudget();
        // 使用 TokenBudgetAllocator 动态计算工作记忆可用预算
        BudgetAllocation allocation = this.tokenBudgetAllocator.allocate(
                totalBudget,
                slots.size(),
                0.0f
        );
        int budget = allocation.workingMemoryBudget();
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
     * <p>该方法用于会话「真正结束」或被 idle 清理的场景，而不是每轮对话结束。</p>
     *
     * @param sessionId 会话 ID
     */
    public void flush(String sessionId) {
        var slots = sessions.get(sessionId);
        if (slots == null || slots.isEmpty()) {
            return;
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
                flush(sessionId);
                log.info("清理空闲会话并 flush 到 L2: sessionId={}, idleMinutes>{}",
                        sessionId, idleThreshold.toMinutes());
            } catch (Exception e) {
                log.warn("清理空闲会话失败: sessionId={}, error={}",
                        sessionId, e.getMessage());
            }
        }
    }

}
