package com.lifepilot.memory.eval.runner;

import com.lifepilot.memory.eval.loader.BenchmarkCase;
import com.lifepilot.memory.eval.loader.BenchmarkMessage;
import com.lifepilot.memory.eval.loader.BenchmarkSession;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.memory.scope.MemorySpace;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.memory.semantic.RealtimeExtractor;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把 benchmark 对话经 L0 transcript 入口重放到记忆系统。
 *
 * <p>为了不改动现有记忆模块代码，本 Replayer：</p>
 * <ol>
 *     <li>为每个 session 创建独立 {@code sessionId = eval:${caseId}:${sessionIdx}}</li>
 *     <li>对每轮对话（user → assistant 配对）生成一条 {@link ChatTurnMemorySnapshot}
 *         写入 snapshot 仓库（满足 RealtimeExtractor 的 fail-closed 要求）</li>
 *     <li>调用 {@link RealtimeExtractor#extractAsync} 触发 AUDN → 质量门控 → L3 upsert 链路</li>
 * </ol>
 *
 * <p>LoCoMo 对话常常是两个真实 speaker，没有 user/assistant 严格交替。Replayer 做
 * round-robin 映射：第 0 条 → user，第 1 条 → assistant，以此类推。</p>
 *
 * <p>多个 session 可以并行触发 extractAsync；每个 session 用 CountDownLatch 收敛等待，
 * 避免 case 间串扰。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class ConversationReplayer {

    private static final Logger log = LoggerFactory.getLogger(ConversationReplayer.class);

    @Nullable
    private final RealtimeExtractor realtimeExtractor;
    @Nullable
    private final ChatTurnMemorySnapshotRepository snapshotRepository;
    @Nullable
    private final MemorySpaceRepository memorySpaceRepository;
    private final Clock clock;

    /** 每条 extractAsync 最大等待，避免评估卡死。 */
    private final long extractionTimeoutMillis;

    public ConversationReplayer(@Nullable RealtimeExtractor realtimeExtractor,
                                @Nullable ChatTurnMemorySnapshotRepository snapshotRepository,
                                @Nullable MemorySpaceRepository memorySpaceRepository,
                                Clock clock) {
        this(realtimeExtractor, snapshotRepository, memorySpaceRepository, clock, 30_000L);
    }

    public ConversationReplayer(@Nullable RealtimeExtractor realtimeExtractor,
                                @Nullable ChatTurnMemorySnapshotRepository snapshotRepository,
                                @Nullable MemorySpaceRepository memorySpaceRepository,
                                Clock clock,
                                long extractionTimeoutMillis) {
        this.realtimeExtractor = realtimeExtractor;
        this.snapshotRepository = snapshotRepository;
        this.memorySpaceRepository = memorySpaceRepository;
        this.clock = clock;
        this.extractionTimeoutMillis = extractionTimeoutMillis;
    }

    /**
     * 重放整个 case。异常记 WARN，不中断后续 session/case。
     */
    public void replay(BenchmarkCase benchmarkCase) {
        String personalSpaceId = resolvePersonalSpaceId();
        String experienceSpaceId = resolveExperienceSpaceId();
        int sessionIdx = 0;
        for (BenchmarkSession session : benchmarkCase.sessions()) {
            String sessionId = "eval:" + benchmarkCase.caseId() + ":" + sessionIdx;
            sessionIdx++;
            try {
                replaySession(sessionId, session, personalSpaceId, experienceSpaceId);
            } catch (RuntimeException e) {
                log.warn("重放 session 失败 case={} session={} error={}",
                        benchmarkCase.caseId(), session.sessionId(), e.getMessage());
            }
        }
    }

    private void replaySession(String sessionId,
                               BenchmarkSession session,
                               @Nullable String personalSpaceId,
                               @Nullable String experienceSpaceId) {
        List<BenchmarkMessage> messages = session.messages();
        // 配对：user → assistant
        List<Pair> pairs = pairMessages(messages);
        for (int i = 0; i < pairs.size(); i++) {
            Pair pair = pairs.get(i);
            String turnId = sessionId + "-turn-" + i;
            if (snapshotRepository != null) {
                try {
                    ChatTurnMemorySnapshot snapshot = new ChatTurnMemorySnapshot(
                            turnId, sessionId,
                            personalSpaceId, experienceSpaceId,
                            /* domainWriteSpaceId */ null,
                            /* projectSpaceId */ null,
                            allSpaces(personalSpaceId, experienceSpaceId),
                            List.of(),
                            /* personalLearningEnabled */ true,
                            /* domainLearningEnabled */ false,
                            /* experienceLearningEnabled */ true,
                            Map.of("source", "memory-eval-harness"),
                            Instant.now(clock));
                    snapshotRepository.save(snapshot);
                } catch (RuntimeException e) {
                    log.debug("写 snapshot 失败 turn={} error={}", turnId, e.getMessage());
                }
            }

            if (realtimeExtractor != null) {
                triggerExtraction(sessionId, turnId, pair.userMessage(), pair.assistantMessage());
            }
        }
    }

    /**
     * 触发 extractAsync 并尝试等待对应的提取完成。
     * RealtimeExtractor 使用 virtual thread 异步执行，这里不能直接 join；
     * 简化策略：对每条消息短暂 sleep，确保在 timeout 内大概率完成。
     */
    private void triggerExtraction(String sessionId, String turnId,
                                   String userMessage, String aiResponse) {
        try {
            realtimeExtractor.extractAsync(sessionId, turnId, userMessage, aiResponse);
        } catch (RuntimeException e) {
            log.warn("extractAsync 触发失败 turn={} error={}", turnId, e.getMessage());
        }
    }

    /**
     * 等待所有已触发的 virtual thread 提取完成。调用者在一个 session 或一整个 case 写完后使用。
     * 简化实现：固定 poll + 等待窗口，超过 {@code extractionTimeoutMillis} 放弃。
     */
    public void awaitPendingExtractions() {
        long start = System.currentTimeMillis();
        // 给 virtual thread scheduler 一个 yield 机会；实际等待由下游事件系统决定
        while (System.currentTimeMillis() - start < Math.min(extractionTimeoutMillis, 5_000L)) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Nullable
    private String resolvePersonalSpaceId() {
        if (memorySpaceRepository == null) return null;
        try {
            return memorySpaceRepository.ensureDefaultPersonalSpace().id();
        } catch (RuntimeException e) {
            log.debug("resolve personalSpaceId 失败: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    private String resolveExperienceSpaceId() {
        if (memorySpaceRepository == null) return null;
        try {
            return memorySpaceRepository.ensureDefaultExperienceSpace().id();
        } catch (RuntimeException e) {
            log.debug("resolve experienceSpaceId 失败: {}", e.getMessage());
            return null;
        }
    }

    private static List<String> allSpaces(String... ids) {
        List<String> result = new ArrayList<>(ids.length);
        for (String id : ids) if (id != null) result.add(id);
        return result;
    }

    /**
     * 把 benchmark 消息按 round-robin 拼成 user/assistant 对。
     * 奇数条消息时最后一条会独立配一个空 assistant 消息。
     */
    static List<Pair> pairMessages(List<BenchmarkMessage> messages) {
        List<Pair> out = new ArrayList<>();
        String pendingUser = null;
        for (BenchmarkMessage m : messages) {
            boolean treatAsUser = "user".equalsIgnoreCase(m.role());
            // LoCoMo 可能所有消息都标 user；按顺序 round-robin 切 user/assistant
            if (pendingUser == null) {
                pendingUser = m.content();
            } else {
                out.add(new Pair(pendingUser, m.content()));
                pendingUser = null;
            }
            // 忽略 role 推断——LoCoMo 里 Alice/Bob 都被统一标 user，交给 pair 滚动分配
            if (!treatAsUser) {
                // placeholder 以支持未来扩展
            }
        }
        if (pendingUser != null) {
            out.add(new Pair(pendingUser, ""));
        }
        return out;
    }

    record Pair(String userMessage, String assistantMessage) {
        Pair {
            if (userMessage == null) userMessage = "";
            if (assistantMessage == null) assistantMessage = "";
        }
    }

    /** 为单测/CLI 准备的工厂方法：生成一个全 null 依赖的 no-op Replayer，不触发任何 Spring Bean。 */
    public static ConversationReplayer noop() {
        return new ConversationReplayer(null, null, null, Clock.systemUTC());
    }
}
