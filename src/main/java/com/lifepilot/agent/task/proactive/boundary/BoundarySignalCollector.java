package com.lifepilot.agent.task.proactive.boundary;

import com.lifepilot.agent.suspend.event.A2aTaskCompletedEvent;
import com.lifepilot.agent.suspend.event.WorkflowCompletedEvent;
import com.lifepilot.agent.task.proactive.ConversationCompletedEvent;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 边界信号采集器 — 订阅对话 / 工作流 / A2A 委派完成事件，记录"任务边界"时间点，
 * 供 {@code ProactiveEngine.buildContextPacket} 查询"当前是否处于边界窗口"。
 *
 * <p>产品背景：JetBrains 2026 田野研究（arXiv:2601.10253）显示 post-commit 时段用户
 * engagement 52%，mid-task 时段 dismissed 62%，同一内容时机错位带来 21pp 体验差距。
 * CHI 2025 Goldilocks Time Window（arXiv:2504.09332）进一步证明时机错位对体验的破坏
 * 强于内容错位。因此将 boundary 作为 {@code DecisionGate} 的关键维度。</p>
 *
 * <p>实现要点：
 * <ul>
 *   <li>每用户独立维护最近边界事件队列（{@link ConcurrentLinkedDeque}，容量硬上限 32 条）</li>
 *   <li>陈旧事件（超过 4 倍窗口）在写入时顺手淘汰，避免内存无限增长</li>
 *   <li>Workflow / A2A 事件不携带 userId，回退到 {@link NotificationProperties#getDefaultUserId()}；
 *       本地个人助手场景下 99% 只有一个用户，该回退是合理的</li>
 *   <li>JVM 重启后事件清空，第一次心跳 boundaryState 为 OUT_OF_BOUNDARY（安全默认值）</li>
 * </ul>
 * </p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class BoundarySignalCollector {

    private static final Logger log = LoggerFactory.getLogger(BoundarySignalCollector.class);

    /** 单用户最近边界事件硬上限，防止恶意/异常场景内存膨胀。 */
    private static final int MAX_EVENTS_PER_USER = 32;

    /** 边界类型 — 便于后续按类型做差异化阈值（本 spec 暂统一视为边界）。 */
    public enum EventType { CONVERSATION, WORKFLOW, A2A }

    /** 边界事件记录。 */
    public record BoundaryEvent(String userId, EventType type, Instant occurredAt) {}

    private final int boundaryWindowMinutes;

    @Nullable
    private final NotificationProperties notificationProperties;

    /** userId → 最近边界事件队列（头部最新）。 */
    private final Map<String, Deque<BoundaryEvent>> byUser = new ConcurrentHashMap<>();

    public BoundarySignalCollector(int boundaryWindowMinutes,
                                   @Nullable NotificationProperties notificationProperties) {
        this.boundaryWindowMinutes = Math.max(1, boundaryWindowMinutes);
        this.notificationProperties = notificationProperties;
    }

    /** 对话完成 → 记录边界事件。 */
    @EventListener
    public void onConversationCompleted(ConversationCompletedEvent event) {
        record(event.getUserId(), EventType.CONVERSATION, Instant.now());
    }

    /** 工作流完成 → 记录边界事件（事件本身无 userId，用 defaultUserId 回退）。 */
    @EventListener
    public void onWorkflowCompleted(WorkflowCompletedEvent event) {
        String userId = resolveDefaultUserId();
        if (userId == null) {
            log.debug("边界信号: WorkflowCompletedEvent 无 defaultUserId, 跳过");
            return;
        }
        record(userId, EventType.WORKFLOW, Instant.now());
    }

    /** A2A 委派完成 → 记录边界事件。 */
    @EventListener
    public void onA2aTaskCompleted(A2aTaskCompletedEvent event) {
        String userId = resolveDefaultUserId();
        if (userId == null) {
            log.debug("边界信号: A2aTaskCompletedEvent 无 defaultUserId, 跳过");
            return;
        }
        record(userId, EventType.A2A, Instant.now());
    }

    /**
     * 判断指定用户在给定时刻是否处于边界窗口内。
     *
     * @param userId 用户 ID（空值返回 false）
     * @param now    当前时间
     * @return true 当且仅当存在满足 {@code now - eventAt ≤ boundaryWindowMinutes} 的事件
     */
    public boolean isWithinBoundary(@Nullable String userId, Instant now) {
        if (userId == null || userId.isBlank()) return false;
        Deque<BoundaryEvent> events = byUser.get(userId);
        if (events == null || events.isEmpty()) return false;
        Duration window = Duration.ofMinutes(boundaryWindowMinutes);
        for (BoundaryEvent ev : events) {
            Duration gap = Duration.between(ev.occurredAt, now);
            // gap 可能为负（事件时钟晚于 now，理论不会，防御）
            if (!gap.isNegative() && gap.compareTo(window) <= 0) return true;
        }
        return false;
    }

    /** 仅供测试/诊断使用：获取某用户当前边界事件队列快照。 */
    public java.util.List<BoundaryEvent> snapshot(String userId) {
        Deque<BoundaryEvent> events = byUser.get(userId);
        if (events == null) return java.util.List.of();
        return java.util.List.copyOf(events);
    }

    /** 记录边界事件 + 清理策略。 */
    private void record(@Nullable String userId, EventType type, Instant at) {
        if (userId == null || userId.isBlank()) return;
        Deque<BoundaryEvent> deque = byUser.computeIfAbsent(userId, _ -> new ConcurrentLinkedDeque<>());
        deque.addFirst(new BoundaryEvent(userId, type, at));

        // 容量硬上限裁剪
        while (deque.size() > MAX_EVENTS_PER_USER) {
            deque.pollLast();
        }

        // 陈旧淘汰：> 4x 窗口
        Instant cutoff = at.minus(Duration.ofMinutes(boundaryWindowMinutes * 4L));
        deque.removeIf(ev -> ev.occurredAt.isBefore(cutoff));

        log.debug("边界信号记录: userId={}, type={}, at={}, queueSize={}",
                userId, type, at, deque.size());
    }

    @Nullable
    private String resolveDefaultUserId() {
        if (notificationProperties == null) return null;
        String uid = notificationProperties.getDefaultUserId();
        return (uid == null || uid.isBlank()) ? null : uid;
    }
}
