package com.lifepilot.agent.task.proactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 信任升级服务 — 追踪反馈并在连续正反馈后建议升级自主度。
 *
 * <p>连续 N 次正反馈 → 标记建议升级 → 用户确认后生效。
 * 永远不会自动升级，必须用户主动同意。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class TrustUpgradeService {

    private static final Logger log = LoggerFactory.getLogger(TrustUpgradeService.class);

    /** 连续正反馈触发升级建议的阈值。 */
    private static final int UPGRADE_THRESHOLD = 5;

    /** 降级后冷却期。 */
    private static final Duration DOWNGRADE_COOLDOWN = Duration.ofDays(7);

    /** 各行为的默认自主度。 */
    private static final Map<String, AutonomyLevel> DEFAULT_LEVELS = Map.of(
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

    public TrustUpgradeService(AutonomyRepository autonomyRepository) {
        this.autonomyRepository = autonomyRepository;
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

        boolean shouldSuggest = !config.upgradeSuggested()
                && newPositive >= UPGRADE_THRESHOLD
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

    /** 记录负反馈（忽略/不相关）。 */
    public void recordNegativeFeedback(String userId, String behaviorName) {
        var config = getOrCreateConfig(userId, behaviorName);
        int newNegative = config.consecutiveNegative() + 1;

        // 连续 3 次负反馈 → 降一级
        AutonomyLevel level = config.autonomyLevel();
        Instant cooldown = config.cooldownUntil();
        if (newNegative >= 3 && level != AutonomyLevel.A) {
            level = level == AutonomyLevel.C ? AutonomyLevel.B : AutonomyLevel.A;
            cooldown = Instant.now().plus(DOWNGRADE_COOLDOWN);
            log.info("信任降级: userId={}, behavior={}, newLevel={}", userId, behaviorName, level);
        }

        autonomyRepository.upsert(new AutonomyConfig(
                userId, behaviorName, level,
                0, newNegative, false, cooldown, Instant.now()));
    }

    /** 用户确认升级。 */
    public boolean confirmUpgrade(String userId, String behaviorName) {
        var config = getOrCreateConfig(userId, behaviorName);
        if (!config.upgradeSuggested()) return false;
        if (config.autonomyLevel() == AutonomyLevel.C) return false;

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
