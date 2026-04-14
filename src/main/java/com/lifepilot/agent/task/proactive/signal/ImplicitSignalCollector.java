package com.lifepilot.agent.task.proactive.signal;

import com.lifepilot.agent.task.proactive.DeliveryResult;
import com.lifepilot.agent.task.proactive.ProactiveAction;
import com.lifepilot.agent.task.proactive.ProactiveMemoryBridge;
import com.lifepilot.agent.task.proactive.TrustUpgradeService;
import com.lifepilot.agent.task.proactive.GoalView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 隐式信号采集器 — 从用户行为中推断反馈，比按钮更丰富。
 *
 * <p>核心信号：
 * <ul>
 *   <li>投递后参与：推送后 N 分钟内用户发起相关对话 → 强正信号</li>
 *   <li>投递后忽略：推送后 N 小时无反应 → 弱负信号</li>
 *   <li>未命中检测：用户主动提出的需求在目标库中有但未推送 → 漏掉的机会</li>
 * </ul>
 * </p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ImplicitSignalCollector {

    private static final Logger log = LoggerFactory.getLogger(ImplicitSignalCollector.class);

    /** 投递后视为"参与"的时间窗口。 */
    private static final Duration ENGAGEMENT_WINDOW = Duration.ofMinutes(10);

    /** 投递后视为"忽略"的时间阈值。 */
    private static final Duration IGNORE_THRESHOLD = Duration.ofHours(4);

    /** recentDeliveries 硬上限，防止内存膨胀。 */
    private static final int MAX_RECENT_DELIVERIES = 500;

    @Nullable private final ProactiveMemoryBridge memoryBridge;
    @Nullable private final TrustUpgradeService trustUpgradeService;

    /** 最近投递记录：notificationId → (behaviorName, topicKey, deliveredAt) */
    private final Map<String, DeliveryRecord> recentDeliveries = new ConcurrentHashMap<>();

    public ImplicitSignalCollector(@Nullable ProactiveMemoryBridge memoryBridge,
                                   @Nullable TrustUpgradeService trustUpgradeService) {
        this.memoryBridge = memoryBridge;
        this.trustUpgradeService = trustUpgradeService;
    }

    /** 引擎投递后调用 — 记录投递事件以便后续检测参与度。 */
    public void onDelivered(String userId, ProactiveAction action, DeliveryResult result) {
        if (result.notificationId() == null) return;
        recentDeliveries.put(result.notificationId(), new DeliveryRecord(
                userId, action.candidate().behaviorName(),
                action.candidate().topicKey(), result.deliveredAt()));

        // 清理过期记录（超过 24h）+ 硬上限裁剪
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        recentDeliveries.values().removeIf(d -> d.deliveredAt.isBefore(cutoff));
        while (recentDeliveries.size() > MAX_RECENT_DELIVERIES) {
            recentDeliveries.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().deliveredAt))
                    .ifPresent(e -> recentDeliveries.remove(e.getKey()));
        }
    }

    /** 对话完成后调用 — 检测投递后参与信号。 */
    public void onConversationCompleted(String userId, String conversationSummary) {
        Instant now = Instant.now();
        var engagedDeliveries = new ArrayList<String>();

        for (var entry : recentDeliveries.entrySet()) {
            var delivery = entry.getValue();
            if (!delivery.userId.equals(userId)) continue;

            Duration elapsed = Duration.between(delivery.deliveredAt, now);
            if (elapsed.compareTo(ENGAGEMENT_WINDOW) <= 0) {
                if (isRelated(conversationSummary, delivery.topicKey)) {
                    recordSignal(userId, delivery.behaviorName, delivery.topicKey, 0.8f,
                            "投递后 " + elapsed.toMinutes() + " 分钟内发起相关对话");
                    engagedDeliveries.add(entry.getKey());
                }
            }
        }

        engagedDeliveries.forEach(recentDeliveries::remove);
    }

    /** 心跳时调用 — 检测投递后忽略信号。 */
    public void checkIgnoredDeliveries(String userId) {
        Instant now = Instant.now();
        var ignoredKeys = new ArrayList<String>();

        for (var entry : recentDeliveries.entrySet()) {
            var delivery = entry.getValue();
            if (!delivery.userId.equals(userId)) continue;

            Duration elapsed = Duration.between(delivery.deliveredAt, now);
            if (elapsed.compareTo(IGNORE_THRESHOLD) > 0) {
                recordSignal(userId, delivery.behaviorName, delivery.topicKey, -0.3f,
                        "投递后 " + elapsed.toHours() + " 小时无反应");
                ignoredKeys.add(entry.getKey());
            }
        }

        ignoredKeys.forEach(recentDeliveries::remove);
    }

    /** 对话完成后调用 — 未命中检测。 */
    public void checkMissedOpportunities(String userId, String conversationContent) {
        if (memoryBridge == null) return;

        var goals = memoryBridge.getActiveGoals();
        for (var goal : goals) {
            if (isRelated(conversationContent, goal.goal())) {
                recordSignal(userId, null, goal.goal(), -0.5f,
                        "用户主动提到「" + goal.goal() + "」，引擎未主动推送");
            }
        }
    }

    /** 简单的相关性检测 — 检查话题关键词是否出现在文本中。 */
    private boolean isRelated(String text, String topicKey) {
        if (text == null || topicKey == null) return false;
        String core = topicKey.replaceAll("^[a-z]+-", "");
        if (core.length() < 2) return false;
        return text.contains(core) || core.contains(text.substring(0, Math.min(4, text.length())));
    }

    /** 记录信号 — 回流到信任和偏好系统（通过 L4）。 */
    private void recordSignal(String userId, @Nullable String behaviorName,
                               @Nullable String topicKey, float value, String evidence) {
        boolean positive = value > 0;

        // 信任回流
        if (trustUpgradeService != null && behaviorName != null) {
            if (positive) {
                trustUpgradeService.recordPositiveFeedback(userId, behaviorName);
            } else if (value < -0.3f) {
                trustUpgradeService.recordNegativeFeedback(userId, behaviorName);
            }
        }

        // 偏好回流 — 写入 L4
        if (memoryBridge != null && behaviorName != null) {
            float signal = positive ? 0.8f : 0.2f;
            memoryBridge.observePreference("proactive-domain", behaviorName, signal);
        }

        log.debug("隐式信号: behavior={}, topic={}, value={}, evidence={}",
                behaviorName, topicKey, value, evidence);
    }

    private record DeliveryRecord(String userId, String behaviorName, String topicKey, Instant deliveredAt) {}
}
