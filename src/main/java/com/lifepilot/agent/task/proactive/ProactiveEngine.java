package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.behavior.BehaviorActivationPolicy;
import com.lifepilot.agent.task.proactive.boundary.BoundarySignalCollector;
import com.lifepilot.agent.task.proactive.boundary.BoundaryState;
import com.lifepilot.agent.task.proactive.boundary.FocusMode;
import com.lifepilot.agent.task.proactive.boundary.FocusStateDetector;
import com.lifepilot.agent.task.proactive.signal.ImplicitSignalCollector;
import com.lifepilot.agent.task.reminder.ReminderFocusState;
import com.lifepilot.agent.task.reminder.ReminderFocusStateHolder;
import com.lifepilot.memory.governance.lifecycle.events.ProactiveTaskCancelled;
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

    /** 单次心跳进入 reason() 的最大候选数 — 控制 LLM 调用量。 */
    private static final int MAX_REASON_CANDIDATES = 3;

    private final List<ProactiveBehavior> behaviors;
    private final DecisionGate decisionGate;
    private final DeliveryEngine deliveryEngine;
    @Nullable private final NotificationProperties notificationProperties;
    @Nullable private final NotificationRepository notificationRepository;
    @Nullable private final AgentConfigProperties config;
    @Nullable private final ReminderFocusStateHolder focusStateHolder;
    @Nullable private final ProactiveMemoryBridge memoryBridge;
    @Nullable private final TrustUpgradeService trustUpgradeService;
    @Nullable private final ImplicitSignalCollector implicitSignalCollector;
    @Nullable private final BoundarySignalCollector boundarySignalCollector;
    @Nullable private final FocusStateDetector focusStateDetector;
    @Nullable private final BehaviorActivationPolicy behaviorActivationPolicy;
    private final BehaviorHealthTracker healthTracker = new BehaviorHealthTracker();

    /** 上次心跳时间，用于 Gate 1 变化量检查。 */
    @Nullable
    private volatile Instant lastHeartbeatAt;

    public ProactiveEngine(List<ProactiveBehavior> behaviors,
                           DecisionGate decisionGate,
                           DeliveryEngine deliveryEngine,
                           @Nullable NotificationProperties notificationProperties,
                           @Nullable NotificationRepository notificationRepository,
                           @Nullable AgentConfigProperties config,
                           @Nullable ReminderFocusStateHolder focusStateHolder,
                           @Nullable ProactiveMemoryBridge memoryBridge,
                           @Nullable TrustUpgradeService trustUpgradeService,
                           @Nullable ImplicitSignalCollector implicitSignalCollector,
                           @Nullable BoundarySignalCollector boundarySignalCollector,
                           @Nullable FocusStateDetector focusStateDetector,
                           @Nullable BehaviorActivationPolicy behaviorActivationPolicy) {
        this.behaviors = List.copyOf(behaviors);
        this.decisionGate = decisionGate;
        this.deliveryEngine = deliveryEngine;
        this.notificationProperties = notificationProperties;
        this.notificationRepository = notificationRepository;
        this.config = config;
        this.focusStateHolder = focusStateHolder;
        this.memoryBridge = memoryBridge;
        this.trustUpgradeService = trustUpgradeService;
        this.implicitSignalCollector = implicitSignalCollector;
        this.boundarySignalCollector = boundarySignalCollector;
        this.focusStateDetector = focusStateDetector;
        this.behaviorActivationPolicy = behaviorActivationPolicy;
    }

    /**
     * 标记主动任务完成 — 面向外部的统一入口，委派给
     * {@link ProactiveMemoryBridge#markGoalFulfilled(String)}（后者负责归档
     * L3 GOAL 实体 + 清理 goal tracking + 发 {@link ProactiveTaskCancelled}
     * 事件，供 {@code ProactiveTaskCancelListener}（Phase 1 挂载）级联将
     * 关联 insight 转 CANCELLED）。
     *
     * <p>之所以让 Bridge 发事件而非在本方法里发：{@link com.lifepilot.agent.task.proactive.behavior.TaskExecutionBehavior}
     * 等行为插件会在 {@code reason} 中直接调 {@code bridge.markGoalFulfilled}
     * 自主归档目标，如果事件只在 Engine 发，那些路径会漏事件；把事件发布
     * 下沉到 Bridge 保证所有调用路径都触发级联。若将来需要将调用强制收拢到
     * Engine，应直接收敛调用入口并同步更新所有行为插件。</p>
     *
     * <p>taskId 即为 L3 GOAL 实体 id。memoryBridge 为空时退化为 no-op。</p>
     *
     * @param taskId 主动任务 id（即 GOAL 实体 id）
     */
    public void markGoalFulfilled(String taskId) {
        if (memoryBridge == null) {
            log.debug("主动引擎: markGoalFulfilled 无记忆桥接, 跳过, taskId={}", taskId);
            return;
        }
        memoryBridge.markGoalFulfilled(taskId);
    }

    /** 生产入口：自行构建 ContextPacket 并执行心跳。 */
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

        // ── 隐式信号检测 ──
        runImplicitSignalCheck(ctx);

        // ── Gate 2: 各插件快速检测候选 ──
        var allCandidates = new ArrayList<ProactiveCandidate>();
        for (var behavior : behaviors) {
            if (!healthTracker.tryActivate(behavior.name())) {
                log.debug("主动引擎: 插件已降级，跳过 detect, behavior={}", behavior.name());
                continue;
            }
            // 分层激活策略：关闭时直通，开启时按 BehaviorLayer 过滤
            if (behaviorActivationPolicy != null
                    && !behaviorActivationPolicy.shouldActivate(behavior.layer(), ctx)) {
                log.debug("主动引擎: 分层激活跳过, behavior={} layer={}", behavior.name(), behavior.layer());
                continue;
            }
            try {
                var candidates = behavior.detect(ctx);
                allCandidates.addAll(candidates);
                healthTracker.recordSuccess(behavior.name());
            } catch (Exception e) {
                healthTracker.recordFailure(behavior.name(), e);
            }
        }

        // ── 偏好前置过滤：用 L4 偏好分数调整候选分 ──
        allCandidates = applyPreferenceScoring(allCandidates, ctx);

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
                .limit(MAX_REASON_CANDIDATES)
                .toList();

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
                healthTracker.recordSuccess(entry.getKey());
            } catch (Exception e) {
                healthTracker.recordFailure(entry.getKey(), e);
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
                // 偏好学习：投递即正信号，写入 L4
                learnPreferenceFromDelivery(ga.action(), result, ctx.userId());
                // 隐式信号：记录投递事件以便后续检测参与度
                if (implicitSignalCollector != null) {
                    try {
                        implicitSignalCollector.onDelivered(ctx.userId(), ga.action(), result);
                    } catch (Exception ex) {
                        log.debug("主动引擎: 隐式信号记录跳过: {}", ex.getMessage());
                    }
                }
                // 信任追踪：投递不记正反馈，由用户显式反馈（NotificationController.dispatchFeedbackToEngine）驱动
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

    /**
     * 偏好前置过滤 — 基于 L4 偏好规则调整候选分数。
     */
    private ArrayList<ProactiveCandidate> applyPreferenceScoring(ArrayList<ProactiveCandidate> candidates,
                                                                  ContextPacket ctx) {
        if (memoryBridge == null) return candidates;

        var domainPrefs = memoryBridge.getPreferences("proactive-domain");
        var timingPrefs = memoryBridge.getPreferences("proactive-timing");
        if (domainPrefs.isEmpty() && timingPrefs.isEmpty()) return candidates;

        // 构建偏好 Map
        var domainMap = new HashMap<String, Float>();
        for (var p : domainPrefs) {
            if (p.observationCount() >= 3) {
                domainMap.put(p.key(), ProactiveMemoryBridge.parseFloat(p.value(), 0.5f));
            }
        }
        String currentTimeSlot = TimeSlotResolver.resolve(ctx);
        float timingPref = timingPrefs.stream()
                .filter(p -> p.key().equals(currentTimeSlot) && p.observationCount() >= 3)
                .map(p -> ProactiveMemoryBridge.parseFloat(p.value(), 0.5f))
                .findFirst().orElse(0.5f);

        var adjusted = new ArrayList<ProactiveCandidate>();
        for (var c : candidates) {
            float domainPref = domainMap.getOrDefault(c.behaviorName(), 0.5f);
            float domainMultiplier = 0.5f + domainPref * 0.7f;
            float timingMultiplier = 0.5f + timingPref * 0.7f;
            float combinedMultiplier = (domainMultiplier + timingMultiplier) / 2;
            float newScore = Math.max(0f, Math.min(1f, c.score() * combinedMultiplier));

            adjusted.add(new ProactiveCandidate(
                    c.id(), c.behaviorName(), c.topicKey(), c.title(),
                    newScore, c.rationale(), c.detail()));
        }
        return adjusted;
    }

    /** 偏好学习 — 投递即正信号，三维度写入 L4。负反馈由 ImplicitSignalCollector 处理。 */
    private void learnPreferenceFromDelivery(ProactiveAction action, DeliveryResult result,
                                              String userId) {
        if (memoryBridge == null) return;
        try {
            float signal = 0.8f;
            String timeSlot = TimeSlotResolver.resolve(result.deliveredAt(), ZoneId.systemDefault());
            memoryBridge.observePreference("proactive-timing", timeSlot, signal);
            memoryBridge.observePreference("proactive-domain", action.candidate().behaviorName(), signal);
            memoryBridge.observePreference("proactive-style", result.level().name(), signal);
        } catch (Exception e) {
            log.debug("主动引擎: 偏好学习跳过: {}", e.getMessage());
        }
    }

    /** 隐式信号检测 — 检查已投递通知是否被忽略。 */
    private void runImplicitSignalCheck(ContextPacket ctx) {
        if (implicitSignalCollector == null) return;
        try {
            implicitSignalCollector.checkIgnoredDeliveries(ctx.userId());
        } catch (Exception e) {
            log.debug("主动引擎: 隐式信号检测跳过: {}", e.getMessage());
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

        // 从记忆模块读取画像和经验
        String portrait = memoryBridge != null ? memoryBridge.getUserPortrait() : null;
        String experience = memoryBridge != null ? memoryBridge.getRecentExperiences() : null;

        // 边界 / 专注状态
        BoundaryState boundaryState = BoundaryState.UNKNOWN;
        if (boundarySignalCollector != null) {
            boundaryState = boundarySignalCollector.isWithinBoundary(userId, now)
                    ? BoundaryState.IN_BOUNDARY
                    : BoundaryState.OUT_OF_BOUNDARY;
        }
        FocusMode focusMode = FocusMode.NORMAL;
        if (focusStateDetector != null) {
            focusMode = focusStateDetector.detect(userId, focusState);
        }

        return new ContextPacket(userId, now, zoneId, qStart, qEnd,
                sentToday, dailyMax, focusState, lastHeartbeatAt, heartbeatMin,
                portrait != null && !portrait.isBlank() ? portrait : null,
                experience != null && !experience.isBlank() ? experience : null,
                boundaryState, focusMode);
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
