package com.lifepilot.agent.initiative.thinker;

import com.lifepilot.agent.initiative.Thinker;
import com.lifepilot.agent.initiative.model.*;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.*;

/**
 * 默认思考器 — 基于规则从信号和记忆中生成想法。
 *
 * <p>两种工作模式：
 * <ul>
 *   <li>事件响应：收到信号后快速判断是否值得形成想法（纯规则，无 LLM）</li>
 *   <li>空闲思考：消费记忆注意力信号，发现值得表达的提醒、追问和洞察</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class DefaultThinker implements Thinker {

    private static final Logger log = LoggerFactory.getLogger(DefaultThinker.class);
    private static final int MAX_IDLE_THOUGHTS = 3;

    /** 记忆注意力服务（memory-proactive-foundation）—— 空闲思考的"该关注什么"来源。 */
    private final MemoryAttentionService memoryAttentionService;
    private final float readyThreshold;

    public DefaultThinker(MemoryAttentionService memoryAttentionService, float readyThreshold) {
        if (readyThreshold < 0.0f || readyThreshold > 1.0f) {
            throw new IllegalArgumentException("想法就绪阈值必须在 0 到 1 之间");
        }
        this.memoryAttentionService = memoryAttentionService;
        this.readyThreshold = readyThreshold;
    }

    @Override
    public Optional<Thought> processSignal(Signal signal) {
        return switch (signal) {
            case Signal.ConversationEnded ended -> processConversationEnded(ended);
            case Signal.TimeElapsed elapsed -> processTimeElapsed(elapsed);
            case Signal.MemoryChanged changed -> processMemoryChanged(changed);
            case Signal.UserReturned returned -> processUserReturned(returned);
            case Signal.IdleDetected idle -> Optional.empty(); // 由 idleThink() 处理
        };
    }

    @Override
    public List<Thought> idleThink() {
        var thoughts = new ArrayList<Thought>();
        try {
            var items = memoryAttentionService.computeAttention(
                    MemoryReadFilter.userProfile(), MAX_IDLE_THOUGHTS * 2);
            for (var item : items) {
                var thought = toThought(item);
                if (thought == null) continue;
                thoughts.add(thought);
                if (thoughts.size() >= MAX_IDLE_THOUGHTS) break;
            }
        } catch (Exception e) {
            log.warn("空闲思考: 记忆注意力计算失败: {}", e.getMessage());
        }
        return thoughts;
    }

    /** 将一条注意力项转为想法；EVOLVING 可表达性低，跳过（返回 null）。 */
    @Nullable
    private Thought toThought(MemoryAttentionService.AttentionItem item) {
        ThoughtKind kind;
        String prefix;
        switch (item.kind()) {
            case DUE_SOON, EXPIRING -> { kind = ThoughtKind.REMINDER; prefix = "reminder"; }
            case NEGLECTED -> { kind = ThoughtKind.FOLLOW_UP; prefix = "follow_up"; }
            case CONNECTION -> { kind = ThoughtKind.INSIGHT; prefix = "insight"; }
            default -> { return null; }  // EVOLVING 等：不主动成想法
        }
        float confidence = clamp01(item.score());
        float maturity = clamp01(0.5f + item.score() * 0.45f);
        Instant now = Instant.now();
        var evidence = new Evidence(
                "memory_entity", item.entityId(), null,
                item.reason(), item.name(), now, confidence);
        // 截止/到期类想法以 dueAt 作为成熟度截止锚点，驱动 deadline pull 自然升温
        Instant matureAt = item.dueAt();
        return new Thought(
                UUID.randomUUID().toString(),
                prefix + ":" + item.kind().name().toLowerCase() + ":" + item.entityId(),
                kind,
                item.reason(),
                List.of(evidence),
                confidence,
                maturity,
                now,
                matureAt,
                maturity >= readyThreshold ? ThoughtState.READY : ThoughtState.BREWING,
                null,
                now);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private Optional<Thought> processConversationEnded(Signal.ConversationEnded signal) {
        // 对话结束事件只作为表达窗口；避免从摘要规则派生高噪声想法。
        return Optional.empty();
    }

    private Optional<Thought> processTimeElapsed(Signal.TimeElapsed signal) {
        if (signal.upcomingDeadlines().isEmpty()) return Optional.empty();

        // 取第一个即将到期的事项生成提醒想法
        String deadline = signal.upcomingDeadlines().getFirst();
        Instant now = Instant.now();
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
                now,
                null,
                ThoughtState.READY,
                null,
                now
        );
        return Optional.of(thought);
    }

    private Optional<Thought> processMemoryChanged(Signal.MemoryChanged signal) {
        // 记忆变化由 MemoryAttentionService 聚合后在 idleThink 中消费，避免单条写入直接打扰用户。
        return Optional.empty();
    }

    private Optional<Thought> processUserReturned(Signal.UserReturned signal) {
        // 用户回归只改变表达时机，不直接生成新想法。
        return Optional.empty();
    }
}
