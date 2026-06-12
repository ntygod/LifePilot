package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.reminder.ReminderFeedbackRepository;
import com.lifepilot.memory.governance.lifecycle.WeightSource;
import com.lifepilot.memory.store.entity.SemanticMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 信任升级服务 — 追踪反馈并在连续正反馈后建议升级自主度。
 *
 * <p>连续 N 次正反馈 → 标记建议升级 → 用户确认后生效。
 * 永远不会自动升级，必须用户主动同意。</p>
 *
 * <p>Task 25（解 S14）：提供 3-arg {@link #recordNegativeFeedback(String, String, String)}
 * 重载，除调整自主度冷却外额外做"提醒 → L3 proactive_insight"反向溯源：查 {@link ReminderFeedbackRepository#findInsightEntityIdsByNotification}
 * 得到本次提醒依赖的 insight 实体 id 列表，对每条调
 * {@link SemanticMemory#updateImportanceScore(String, float, WeightSource)} 做固定
 * {@value #NEGATIVE_FEEDBACK_DELTA} 惩罚。连续累计后由 {@code NegativeFeedbackListener}
 * 依据配置阈值转 SUPERSEDED（-0.5 × 3 = -1.5 &lt; -1.0 直接触发；或累计 count=3 触发）。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class TrustUpgradeService {

    private static final Logger log = LoggerFactory.getLogger(TrustUpgradeService.class);

    private static final int DEFAULT_UPGRADE_THRESHOLD = 5;
    private static final Duration DEFAULT_DOWNGRADE_COOLDOWN = Duration.ofDays(7);

    /**
     * 每次"无用"反馈对关联 insight 实体施加的 importance 惩罚步长。
     *
     * <p>取 -0.5 的考量：
     * <ul>
     *   <li>3 次累计 -1.5 &lt; NegativeFeedbackListener 默认 -1.0 阈值，直接触发 SUPERSEDED</li>
     *   <li>单次惩罚过猛（如 -1.0）会让偶然误点立即弃用 insight；-0.5 提供至少 2 次容错空间</li>
     * </ul>
     */
    static final float NEGATIVE_FEEDBACK_DELTA = -0.5f;

    /** 各行为的默认自主度。 */
    public static final Map<String, AutonomyLevel> DEFAULT_LEVELS = Map.of(
            "reminder", AutonomyLevel.A,
            "follow-up", AutonomyLevel.A,
            "insight", AutonomyLevel.A,
            "clipboard", AutonomyLevel.A,
            "info-supplement", AutonomyLevel.A,
            "context-prep", AutonomyLevel.B,
            "report", AutonomyLevel.B,
            "task-execution", AutonomyLevel.B
    );

    private final AutonomyRepository autonomyRepository;
    @Nullable
    private final AgentConfigProperties config;
    /**
     * 反馈仓库 —— 注入后 {@link #recordNegativeFeedback(String, String, String)}
     * 才能反查 notification → insight 关联；单测可为 null 跳过溯源路径。
     */
    @Nullable
    private final ReminderFeedbackRepository reminderFeedbackRepository;
    /**
     * 语义记忆 —— 注入后 {@link #recordNegativeFeedback(String, String, String)}
     * 才能对 insight 实体做 importanceScore 惩罚；单测可为 null 跳过溯源路径。
     */
    @Nullable
    private final SemanticMemory semanticMemory;

    /** 老构造 —— 不支持溯源的最小可用签名，保留给尚未迁移的调用点。 */
    public TrustUpgradeService(AutonomyRepository autonomyRepository,
                               @Nullable AgentConfigProperties config) {
        this(autonomyRepository, config, null, null);
    }

    /**
     * 完整构造 —— 注入反馈仓库与语义记忆后，
     * {@link #recordNegativeFeedback(String, String, String)} 会溯源到 insight 实体做惩罚。
     *
     * @param autonomyRepository         自主度仓库
     * @param config                     配置（可空）
     * @param reminderFeedbackRepository 反馈仓库（可空，为空时溯源路径静默跳过）
     * @param semanticMemory             语义记忆（可空，为空时溯源路径静默跳过）
     */
    public TrustUpgradeService(AutonomyRepository autonomyRepository,
                               @Nullable AgentConfigProperties config,
                               @Nullable ReminderFeedbackRepository reminderFeedbackRepository,
                               @Nullable SemanticMemory semanticMemory) {
        this.autonomyRepository = autonomyRepository;
        this.config = config;
        this.reminderFeedbackRepository = reminderFeedbackRepository;
        this.semanticMemory = semanticMemory;
    }

    private int upgradeThreshold() {
        return config != null ? config.getTask().getProactiveEngineTrustUpgradeThreshold() : DEFAULT_UPGRADE_THRESHOLD;
    }

    private Duration downgradeCooldown() {
        return config != null ? Duration.ofDays(config.getTask().getProactiveEngineTrustDowngradeCooldownDays()) : DEFAULT_DOWNGRADE_COOLDOWN;
    }

    /** 获取行为的当前自主度（不存在则用默认值）。 */
    public AutonomyLevel getLevel(String userId, String behaviorName) {
        var config = autonomyRepository.findByUserAndBehavior(userId, behaviorName);
        if (config != null) return config.autonomyLevel();
        return DEFAULT_LEVELS.getOrDefault(behaviorName, AutonomyLevel.A);
    }

    /** 记录正反馈（有用/采纳）。 */
    public void recordPositiveFeedback(String userId, String behaviorName) {
        var config = getOrCreateConfig(userId, behaviorName);
        int newPositive = config.consecutivePositive() + 1;

        // 冷却期内不触发升级建议
        boolean inCooldown = config.cooldownUntil() != null && Instant.now().isBefore(config.cooldownUntil());
        boolean shouldSuggest = !inCooldown
                && !config.upgradeSuggested()
                && newPositive >= upgradeThreshold()
                && config.autonomyLevel() != AutonomyLevel.C;

        autonomyRepository.upsert(new AutonomyConfig(
                userId, behaviorName, config.autonomyLevel(),
                newPositive, 0, shouldSuggest || config.upgradeSuggested(),
                config.cooldownUntil(), Instant.now()));

        if (shouldSuggest) {
            log.info("信任升级建议: userId={}, behavior={}, currentLevel={}, consecutivePositive={}",
                    userId, behaviorName, config.autonomyLevel(), newPositive);
        }
    }

    /**
     * 记录负反馈（忽略/不相关）—— 不携带通知上下文的老调用入口，
     * 保持向后兼容（如 ImplicitSignalCollector 的隐式负向信号不属于任何具体提醒）。
     */
    public void recordNegativeFeedback(String userId, String behaviorName) {
        recordNegativeFeedback(userId, behaviorName, null);
    }

    /**
     * 记录负反馈（忽略/不相关），并在有 notificationId 时溯源到 L3 {@code proactive_insight_*}
     * 实体做 importanceScore 惩罚。
     *
     * <p>溯源流程：
     * <ol>
     *   <li>按传入行为调整 autonomy 冷却（沿用老语义）</li>
     *   <li>若 {@code notificationId} 非空且仓库 / 语义记忆都注入了，查
     *       {@link ReminderFeedbackRepository#findInsightEntityIdsByNotification} 得到关联 insight 列表</li>
     *   <li>对每条关联实体调 {@link SemanticMemory#updateImportanceScore} 施加
     *       {@value #NEGATIVE_FEEDBACK_DELTA} 惩罚 —— 事件自动驱动 NegativeFeedbackListener 累计判定</li>
     * </ol>
     *
     * <p>任一依赖缺失 / 无关联 insight / 单条惩罚失败 —— 均静默跳过，不让反馈主流程挂掉。</p>
     *
     * @param userId         用户 ID
     * @param behaviorName   行为名
     * @param notificationId 通知 ID（可空 —— 隐式信号等没有明确通知归属的场景传 null）
     */
    public void recordNegativeFeedback(String userId, String behaviorName, @Nullable String notificationId) {
        var config = getOrCreateConfig(userId, behaviorName);
        int newNegative = config.consecutiveNegative() + 1;

        // 连续 3 次负反馈 → 降一级
        AutonomyLevel level = config.autonomyLevel();
        Instant cooldown = config.cooldownUntil();
        if (newNegative >= 3 && level != AutonomyLevel.A) {
            level = level == AutonomyLevel.C ? AutonomyLevel.B : AutonomyLevel.A;
            cooldown = Instant.now().plus(downgradeCooldown());
            log.info("信任降级: userId={}, behavior={}, newLevel={}", userId, behaviorName, level);
        }

        autonomyRepository.upsert(new AutonomyConfig(
                userId, behaviorName, level,
                0, newNegative, false, cooldown, Instant.now()));

        // Task 25（解 S14）：溯源到 L3 insight 并调 importanceScore 惩罚 —— 任一缺失就跳过
        applyInsightFeedbackPenalty(notificationId);
    }

    /**
     * 对关联 insight 施加 importanceScore 惩罚 —— 每个 id 单独兜底异常，避免一条失败中断整批。
     *
     * <p>{@link SemanticMemory#updateImportanceScore(String, float, WeightSource)} 接受
     * 的是"目标分数"而非"增量"，服务端会依据新旧差自动发
     * {@link com.lifepilot.memory.lifecycle.events.EntityWeightChanged} 携带 delta。
     * 本方法读当前分 → 应用 {@value #NEGATIVE_FEEDBACK_DELTA} → 不夹紧下界（允许 &lt; 0
     * 以触发 {@link com.lifepilot.memory.lifecycle.listeners.NegativeFeedbackListener}
     * 的 cumulativeScore &lt; -1.0 阈值分支）；上界夹紧到 1.0 保持与 SemanticMemory 语义一致。</p>
     *
     * @param notificationId 通知 ID（null / 空时直接跳过）
     */
    private void applyInsightFeedbackPenalty(@Nullable String notificationId) {
        if (notificationId == null || notificationId.isBlank()) return;
        if (reminderFeedbackRepository == null || semanticMemory == null) return;
        try {
            var insightIds = reminderFeedbackRepository.findInsightEntityIdsByNotification(notificationId);
            for (var insightId : insightIds) {
                try {
                    var existing = semanticMemory.findById(insightId);
                    if (existing.isEmpty()) {
                        log.debug("信任服务: 负反馈溯源实体不存在 insight={}, 跳过", insightId);
                        continue;
                    }
                    // 下界不夹紧 —— cumulativeScore 可为负，供 NegativeFeedbackListener 阈值判定
                    float newScore = Math.min(1.0f, existing.get().importanceScore() + NEGATIVE_FEEDBACK_DELTA);
                    semanticMemory.updateImportanceScore(insightId, newScore, WeightSource.USER_FEEDBACK);
                    log.debug("信任服务: 负反馈溯源惩罚 notification={}, insight={}, newScore={}, delta={}",
                            notificationId, insightId, newScore, NEGATIVE_FEEDBACK_DELTA);
                } catch (Exception e) {
                    log.warn("信任服务: 负反馈惩罚失败 insight={}, error={}", insightId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("信任服务: 负反馈溯源查询失败 notification={}, error={}", notificationId, e.getMessage());
        }
    }

    /** 用户确认升级。 */
    public boolean confirmUpgrade(String userId, String behaviorName) {
        var config = getOrCreateConfig(userId, behaviorName);
        if (!config.upgradeSuggested()) return false;
        if (config.autonomyLevel() == AutonomyLevel.C) return false;
        // 冷却期内不允许升级
        if (config.cooldownUntil() != null && Instant.now().isBefore(config.cooldownUntil())) {
            log.debug("信任升级拒绝: 冷却期未过, behavior={}", behaviorName);
            return false;
        }

        AutonomyLevel newLevel = config.autonomyLevel() == AutonomyLevel.A
                ? AutonomyLevel.B : AutonomyLevel.C;

        autonomyRepository.upsert(new AutonomyConfig(
                userId, behaviorName, newLevel,
                0, 0, false, null, Instant.now()));

        log.info("信任升级确认: userId={}, behavior={}, {} → {}",
                userId, behaviorName, config.autonomyLevel(), newLevel);
        return true;
    }

    private AutonomyConfig getOrCreateConfig(String userId, String behaviorName) {
        var config = autonomyRepository.findByUserAndBehavior(userId, behaviorName);
        if (config != null) return config;
        var defaultConfig = AutonomyConfig.defaultFor(userId, behaviorName,
                DEFAULT_LEVELS.getOrDefault(behaviorName, AutonomyLevel.A));
        autonomyRepository.upsert(defaultConfig);
        return defaultConfig;
    }
}
