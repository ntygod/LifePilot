package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.intent.IntentMemoryService;
import com.lifepilot.agent.task.proactive.preference.PreferenceLearner;
import com.lifepilot.agent.task.proactive.schedule.ScheduleExtractor;
import com.lifepilot.agent.task.reminder.ReminderFocusState;
import com.lifepilot.agent.task.reminder.ReminderFocusStateHolder;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 主动智能引擎 — 三级需求检测 + 行为插件编排。
 *
 * <p>心跳流程：
 * <ol>
 *   <li>Gate 1（SILENT）：变化量检查 — 无变化直接休眠</li>
 *   <li>Gate 2（FAST）：每个插件快速检测候选 — 无高分候选则跳过</li>
 *   <li>Gate 3（FULL）：高分候选进入精细推理 → 决策门控 → 投递</li>
 * </ol>
 * </p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class ProactiveEngine {

    private static final Logger log = LoggerFactory.getLogger(ProactiveEngine.class);
    private static final String PROACTIVE_TYPE = "proactive_action";

    /** Gate 2 默认阈值。 */
    private static final float DEFAULT_GATE2_THRESHOLD = 0.4f;

    private final List<ProactiveBehavior> behaviors;
    private final DecisionGate decisionGate;
    private final DeliveryEngine deliveryEngine;
    @Nullable
    private final NotificationProperties notificationProperties;
    @Nullable
    private final NotificationRepository notificationRepository;
    @Nullable
    private final AgentConfigProperties config;
    @Nullable
    private final ReminderFocusStateHolder focusStateHolder;
    @Nullable
    private final IntentMemoryService intentMemoryService;
    @Nullable
    private final PreferenceLearner preferenceLearner;

    /** 上次心跳时间，用于 Gate 1 变化量检查。 */
    @Nullable
    private volatile Instant lastHeartbeatAt;

    /** 测试用简化构造器。 */
    public ProactiveEngine(List<ProactiveBehavior> behaviors,
                           DecisionGate decisionGate,
                           DeliveryEngine deliveryEngine) {
        this(behaviors, decisionGate, deliveryEngine, null, null, null, null, null, null);
    }

    public ProactiveEngine(List<ProactiveBehavior> behaviors,
                           DecisionGate decisionGate,
                           DeliveryEngine deliveryEngine,
                           @Nullable NotificationProperties notificationProperties,
                           @Nullable NotificationRepository notificationRepository,
                           @Nullable AgentConfigProperties config,
                           @Nullable ReminderFocusStateHolder focusStateHolder,
                           @Nullable IntentMemoryService intentMemoryService,
                           @Nullable PreferenceLearner preferenceLearner) {
        this.behaviors = List.copyOf(behaviors);
        this.decisionGate = decisionGate;
        this.deliveryEngine = deliveryEngine;
        this.notificationProperties = notificationProperties;
        this.notificationRepository = notificationRepository;
        this.config = config;
        this.focusStateHolder = focusStateHolder;
        this.intentMemoryService = intentMemoryService;
        this.preferenceLearner = preferenceLearner;
    }

    /**
     * 生产入口：自行构建 ContextPacket 并执行心跳。
     */
    public DetectionLevel heartbeat() {
        var ctx = buildContextPacket();
        var level = heartbeat(ctx);
        lastHeartbeatAt = ctx.now();
        return level;
    }

    /**
     * 执行一次心跳（可注入上下文，主要供测试使用）。
     *
     * @param ctx 心跳上下文
     * @return 本次心跳的检测级别
     */
    public DetectionLevel heartbeat(ContextPacket ctx) {
        // ── Gate 1: 变化量检查 ──
        if (!ctx.hasChangeSinceLastHeartbeat()) {
            log.debug("主动引擎: SILENT — 自上次心跳无变化");
            return DetectionLevel.SILENT;
        }

        // ── 全局维护：意图过期清理 + 新意图提取（Y1: 从插件上移到引擎层统一执行） ──
        runIntentMaintenance(ctx);

        // ── Gate 2: 各插件快速检测候选 ──
        var allCandidates = new ArrayList<ProactiveCandidate>();
        for (var behavior : behaviors) {
            try {
                var candidates = behavior.detect(ctx);
                allCandidates.addAll(candidates);
            } catch (Exception e) {
                log.warn("主动引擎: 插件 detect 异常, behavior={}, error={}",
                        behavior.name(), e.getMessage());
            }
        }

        float maxScore = allCandidates.stream()
                .map(ProactiveCandidate::score)
                .max(Float::compare)
                .orElse(0f);

        if (allCandidates.isEmpty() || maxScore < gate2Threshold()) {
            log.debug("主动引擎: FAST — candidates={}, maxScore={}",
                    allCandidates.size(), maxScore);
            return DetectionLevel.FAST;
        }

        // ── Gate 3: 高分候选进入精细推理 ──
        var topCandidates = allCandidates.stream()
                .filter(c -> c.score() >= gate2Threshold())
                .sorted(Comparator.comparingDouble(ProactiveCandidate::score).reversed())
                .toList();

        // 按插件分组，调用 reason()
        var byBehavior = topCandidates.stream()
                .collect(Collectors.groupingBy(ProactiveCandidate::behaviorName,
                        LinkedHashMap::new, Collectors.toList()));

        var allActions = new ArrayList<ProactiveAction>();
        for (var entry : byBehavior.entrySet()) {
            var behavior = findBehavior(entry.getKey());
            if (behavior == null) continue;
            try {
                var actions = behavior.reason(entry.getValue(), ctx);
                allActions.addAll(actions);
            } catch (Exception e) {
                log.warn("主动引擎: 插件 reason 异常, behavior={}, error={}",
                        entry.getKey(), e.getMessage());
            }
        }

        if (allActions.isEmpty()) {
            log.debug("主动引擎: FULL — 推理后无有效动作");
            return DetectionLevel.FULL;
        }

        // ── 决策门控 ──
        var gated = decisionGate.evaluate(allActions, ctx);

        // ── 投递 ──
        for (var ga : gated) {
            try {
                var result = deliveryEngine.deliver(ga.action(), ga.level(), ctx.userId());
                // 偏好学习：记录投递结果用于五维偏好模型
                if (preferenceLearner != null) {
                    try {
                        preferenceLearner.learnFromDelivery(ga.action(), result, ctx.userId(), true);
                    } catch (Exception ex) {
                        log.debug("主动引擎: 偏好学习跳过: {}", ex.getMessage());
                    }
                }
                var behavior = findBehavior(ga.action().candidate().behaviorName());
                if (behavior != null) {
                    behavior.onDelivered(ga.action(), result);
                }
            } catch (Exception e) {
                log.warn("主动引擎: 投递异常, topic={}, error={}",
                        ga.action().candidate().topicKey(), e.getMessage());
            }
        }

        log.info("主动引擎: FULL — candidates={}, actions={}, delivered={}",
                topCandidates.size(), allActions.size(), gated.size());
        return DetectionLevel.FULL;
    }

    /** 全局意图维护 — 过期清理 + 对话提取。 */
    private void runIntentMaintenance(ContextPacket ctx) {
        if (intentMemoryService == null) return;
        try {
            intentMemoryService.expireStaleIntents();
            intentMemoryService.extractFromRecentConversations(ctx.userId());
        } catch (Exception e) {
            log.debug("主动引擎: 意图维护跳过: {}", e.getMessage());
        }
    }

    private float gate2Threshold() {
        return config != null ? config.getTask().getProactiveEngineGate2Threshold() : DEFAULT_GATE2_THRESHOLD;
    }

    private ProactiveBehavior findBehavior(String name) {
        return behaviors.stream()
                .filter(b -> b.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private ContextPacket buildContextPacket() {
        String userId = notificationProperties != null
                ? notificationProperties.getDefaultUserId() : "default";
        Instant now = Instant.now();
        ZoneId zoneId = ZoneId.systemDefault();
        Instant startOfDay = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant();
        int sentToday = notificationRepository != null
                ? (int) notificationRepository.countSentByUserIdAndTypeSince(userId, PROACTIVE_TYPE, startOfDay)
                : 0;
        int dailyMax = config != null ? config.getTask().getProactiveReminderDailyMaxReminders() : 3;
        int heartbeatMin = config != null ? config.getTask().getHeartbeatIntervalSeconds() / 60 : 30;
        ReminderFocusState focusState = focusStateHolder != null ? focusStateHolder.get() : null;
        LocalTime qStart = parseTime(config != null
                ? config.getTask().getProactiveReminderQuietHoursStart() : null);
        LocalTime qEnd = parseTime(config != null
                ? config.getTask().getProactiveReminderQuietHoursEnd() : null);

        return new ContextPacket(userId, now, zoneId, qStart, qEnd,
                sentToday, dailyMax, focusState, lastHeartbeatAt, heartbeatMin);
    }

    @Nullable
    private static LocalTime parseTime(@Nullable String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return null;
        try {
            return LocalTime.parse(timeStr);
        } catch (Exception e) {
            return null;
        }
    }
}
