package com.lifepilot.memory.governance.security;

import com.lifepilot.memory.config.MemoryProperties;
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
    private final MemoryProperties properties;

    public MemoryInjectionDetector(PromptInjectionPatternScanner scanner,
                                    SpaceTrustDistribution distribution,
                                    MemoryProperties properties) {
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
        try {
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
            if (distribution.isOutlier(spaceId, trustScore, properties.getSecurity().getOutlierThreshold())) {
                float distance = distribution.mahalanobisDistance(spaceId, trustScore);
                String details = "mahalanobis=" + String.format("%.2f", distance)
                        + " > threshold=" + properties.getSecurity().getOutlierThreshold();
                log.info("注入检测: SUSPICIOUS space={}, trustScore={}, {}",
                        spaceId, trustScore, details);
                if (properties.getSecurity().isBlockOnSuspicious()) {
                    return InjectionDetectionResult.blocked(
                            InjectionReason.TRUST_SCORE_OUTLIER,
                            Math.min(1.0f, distance / 10f),
                            details);
                }
                return InjectionDetectionResult.suspicious(
                        InjectionReason.TRUST_SCORE_OUTLIER,
                        Math.min(1.0f, distance / 10f),
                        details);
            }

            // 3. 通过后纳入样本集
            distribution.observe(spaceId, trustScore);
            return InjectionDetectionResult.pass();
        } catch (Exception e) {
            log.debug("注入检测异常容错, 返回 PASS: {}", e.getMessage());
            return InjectionDetectionResult.pass();
        }
    }
}
