package com.lifepilot.agent.initiative.thinker;

import com.lifepilot.agent.initiative.Thinker;
import com.lifepilot.agent.initiative.model.*;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 默认思考器 — 基于规则从信号和记忆中生成想法。
 *
 * <p>两种工作模式：
 * <ul>
 *   <li>事件响应：收到信号后快速判断是否值得形成想法（纯规则，无 LLM）</li>
 *   <li>空闲思考：回顾 L3 记忆中的 GOAL 实体，检查是否有停滞的目标</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class DefaultThinker implements Thinker {

    private static final Logger log = LoggerFactory.getLogger(DefaultThinker.class);
    private static final int MAX_IDLE_THOUGHTS = 3;
    private static final int GOAL_STALE_DAYS = 14;

    @Nullable
    private final SemanticMemory semanticMemory;

    public DefaultThinker(@Nullable SemanticMemory semanticMemory) {
        this.semanticMemory = semanticMemory;
    }

    @Override
    public Optional<Thought> processSignal(Signal signal) {
        return switch (signal) {
            case Signal.ConversationEnded ended -> processConversationEnded(ended);
            case Signal.TimeElapsed elapsed -> processTimeElapsed(elapsed);
            case Signal.MemoryChanged changed -> processMemoryChanged(changed);
            case Signal.UserReturned returned -> Optional.empty(); // 暂不处理
            case Signal.IdleDetected idle -> Optional.empty(); // 由 idleThink() 处理
        };
    }

    @Override
    public List<Thought> idleThink() {
        if (semanticMemory == null) return List.of();

        var thoughts = new ArrayList<Thought>();

        // 1. 检查停滞的 GOAL 实体
        try {
            var goals = semanticMemory.findCurrentByType(EntityType.GOAL);
            for (var goal : goals) {
                if (goal.lifecycleState() != LifecycleState.ACTIVE) continue;
                if (isStaleGoal(goal)) {
                    var thought = buildFollowUpThought(goal);
                    thoughts.add(thought);
                    if (thoughts.size() >= MAX_IDLE_THOUGHTS) break;
                }
            }
        } catch (Exception e) {
            log.warn("空闲思考: 查询 GOAL 失败: {}", e.getMessage());
        }

        return thoughts;
    }

    private Optional<Thought> processConversationEnded(Signal.ConversationEnded signal) {
        // 对话结束后暂不自动生成想法（避免过度打扰）
        // 后续可以分析对话摘要，检测是否有未完成的承诺
        return Optional.empty();
    }

    private Optional<Thought> processTimeElapsed(Signal.TimeElapsed signal) {
        if (signal.upcomingDeadlines().isEmpty()) return Optional.empty();

        // 取第一个即将到期的事项生成提醒想法
        String deadline = signal.upcomingDeadlines().getFirst();
        var evidence = new Evidence(
                "time_elapsed",
                "deadline:" + deadline,
                null,
                deadline,
                null,
                signal.timestamp(),
                0.8f
        );

        var thought = new Thought(
                UUID.randomUUID().toString(),
                "reminder:deadline:" + deadline.hashCode(),
                ThoughtKind.REMINDER,
                deadline,
                List.of(evidence),
                0.7f,
                0.7f,
                Instant.now(),
                null,
                ThoughtState.READY,
                null
        );
        return Optional.of(thought);
    }

    private Optional<Thought> processMemoryChanged(Signal.MemoryChanged signal) {
        // 记忆变化暂不触发想法（避免噪音）
        return Optional.empty();
    }

    private boolean isStaleGoal(TemporalEntity goal) {
        if (goal.lastAccessedAt() == null && goal.updatedAt() == null) return false;
        Instant lastActivity = goal.lastAccessedAt() != null ? goal.lastAccessedAt() : goal.updatedAt();
        return Duration.between(lastActivity, Instant.now()).toDays() >= GOAL_STALE_DAYS;
    }

    private Thought buildFollowUpThought(TemporalEntity goal) {
        var evidence = new Evidence(
                "memory_entity",
                goal.id(),
                null,
                goal.name() + (goal.description() != null ? ": " + goal.description() : ""),
                goal.name(),
                goal.updatedAt() != null ? goal.updatedAt() : goal.createdAt(),
                0.8f
        );

        long staleDays = Duration.between(
                goal.lastAccessedAt() != null ? goal.lastAccessedAt() : goal.updatedAt(),
                Instant.now()
        ).toDays();

        // 成熟度随停滞天数增长
        float maturity = Math.min(0.9f, 0.5f + (staleDays - GOAL_STALE_DAYS) * 0.02f);

        return new Thought(
                UUID.randomUUID().toString(),
                "follow_up:goal:" + goal.id(),
                ThoughtKind.FOLLOW_UP,
                "目标「" + goal.name() + "」已 " + staleDays + " 天没有进展",
                List.of(evidence),
                0.7f,
                maturity,
                Instant.now(),
                null,
                maturity >= 0.6f ? ThoughtState.READY : ThoughtState.BREWING,
                null
        );
    }
}
