package com.lifepilot.memory.governance.security;

import com.lifepilot.memory.governance.config.MemoryGovernanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * 记忆注入检测主入口。
 *
 * <p>两层检测：
 * <ol>
 *   <li>{@link PromptInjectionPatternScanner} 扫描已知注入模式；命中 → BLOCKED</li>
 *   <li>{@link SpaceTrustDistribution} 计算 trustScore 在该 space 的偏离度；outlier → SUSPICIOUS</li>
 * </ol>
 * </p>
 *
 * <p>默认开关关闭（{@code memory.security.injection-detection-enabled=false}），
 * 开启后才做检测。关闭时直接 PASS，不影响主链路。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class MemoryInjectionDetector {

    private static final Logger log = LoggerFactory.getLogger(MemoryInjectionDetector.class);

    private final PromptInjectionPatternScanner scanner;
    private final SpaceTrustDistribution distribution;
    private final MemoryGovernanceProperties properties;

    public MemoryInjectionDetector(PromptInjectionPatternScanner scanner,
                                    SpaceTrustDistribution distribution,
                                    MemoryGovernanceProperties properties) {
        this.scanner = Objects.requireNonNull(scanner);
        this.distribution = Objects.requireNonNull(distribution);
        this.properties = Objects.requireNonNull(properties);
    }

    /**
     * 检测文本 + trustScore 是否可能是注入。
     */
    public InjectionDetectionResult detect(@Nullable String spaceId,
                                            @Nullable String text,
                                            float trustScore) {
        if (!properties.getSecurity().isInjectionDetectionEnabled()) {
            return InjectionDetectionResult.pass();
        }
        requireTrustScore(trustScore);
        float outlierThreshold = positiveFinite(
                properties.getSecurity().getOutlierThreshold(),
                "注入检测异常阈值");

        // 1. Prompt injection 模式扫描
        var match = scanner.scan(text);
        if (match.isPresent()) {
            log.info("注入检测: BLOCKED space={}, pattern={}, excerpt={}",
                    spaceId, match.get().pattern(), match.get().excerpt());
            return InjectionDetectionResult.blocked(
                    InjectionReason.PROMPT_INJECTION_PATTERN,
                    1.0f,
                    "命中模式: " + match.get().pattern());
        }

        // 2. TrustScore 分布异常
        if (distribution.isOutlier(spaceId, trustScore, outlierThreshold)) {
            float distance = distribution.mahalanobisDistance(spaceId, trustScore);
            String details = "mahalanobis=" + String.format("%.2f", distance)
                    + " > threshold=" + outlierThreshold;
            log.info("注入检测: SUSPICIOUS space={}, trustScore={}, {}",
                    spaceId, trustScore, details);
            float confidence = outlierConfidence(distance);
            if (properties.getSecurity().isBlockOnSuspicious()) {
                return InjectionDetectionResult.blocked(
                        InjectionReason.TRUST_SCORE_OUTLIER,
                        confidence,
                        details);
            }
            return InjectionDetectionResult.suspicious(
                    InjectionReason.TRUST_SCORE_OUTLIER,
                    confidence,
                    details);
        }

        // 3. 通过后纳入样本集
        distribution.observe(spaceId, trustScore);
        return InjectionDetectionResult.pass();
    }

    private static float outlierConfidence(float distance) {
        if (!(distance >= 0.0f)) {
            return 0.0f;
        }
        return Math.min(1.0f, distance / 10.0f);
    }

    private static void requireTrustScore(float trustScore) {
        if (!(trustScore >= 0.0f && trustScore <= 1.0f)) {
            throw new IllegalArgumentException("trustScore 必须在 [0,1] 范围内: " + trustScore);
        }
    }

    private static float positiveFinite(float value, String name) {
        if (!(value > 0.0f) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(name + "必须是正有限数: " + value);
        }
        return value;
    }
}
