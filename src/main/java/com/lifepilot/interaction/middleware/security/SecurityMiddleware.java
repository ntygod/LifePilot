package com.lifepilot.interaction.middleware.security;

import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.middleware.GatewayMiddleware;
import com.lifepilot.interaction.middleware.MiddlewareChain;
import com.lifepilot.interaction.middleware.MiddlewareContext;
import com.lifepilot.interaction.middleware.auth.TrustLevel;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全检查中间件，依次执行 Prompt 注入检测、敏感数据检测和信任分数计算。
 *
 * <p>处理流程：
 * <ol>
 *   <li>{@link PromptInjectionDetector} 检测注入攻击 — CRITICAL/HIGH 违规直接返回 403</li>
 *   <li>{@link SensitiveDataDetector} 检测敏感数据并脱敏</li>
 *   <li>信任等级低于 {@link TrustLevel#TRUSTED} 时，通过 {@link TrustScoreCalculator} 计算信任分数</li>
 *   <li>构建 {@link SecurityCheckResult} 放入 {@link MiddlewareContext}，调用 {@code chain.next()}</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SecurityMiddleware implements GatewayMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SecurityMiddleware.class);

    /** TRUSTED 用户的默认信任分数 */
    private static final double DEFAULT_TRUSTED_SCORE = 1.0;

    private final PromptInjectionDetector injectionDetector;
    private final SensitiveDataDetector sensitiveDataDetector;
    private final TrustScoreCalculator trustScoreCalculator;
    private final GatewayProperties properties;

    public SecurityMiddleware(PromptInjectionDetector injectionDetector,
                              SensitiveDataDetector sensitiveDataDetector,
                              TrustScoreCalculator trustScoreCalculator,
                              GatewayProperties properties) {
        this.injectionDetector = injectionDetector;
        this.sensitiveDataDetector = sensitiveDataDetector;
        this.trustScoreCalculator = trustScoreCalculator;
        this.properties = properties;
    }

    @Override
    public GatewayResponse process(GatewayMessage message, MiddlewareChain chain) {
        String content = message.contentAsText();
        var allViolations = new ArrayList<SecurityViolation>();

        // 1. Prompt 注入检测 — CRITICAL/HIGH 违规直接阻断
        var injectionViolations = injectionDetector.detect(content);
        if (hasBlockingViolation(injectionViolations)) {
            log.warn("安全检查阻断请求: messageId={}, violations={}", message.messageId(), injectionViolations.size());
            var blockedResult = SecurityCheckResult.builder()
                    .violations(injectionViolations)
                    .blocked(true)
                    .redactedContent(null)
                    .trustScore(0.0)
                    .build();
            chain.context().set(MiddlewareContext.KEY_SECURITY_CHECK_RESULT, blockedResult);
            return GatewayResponse.error(message.channelType(), "请求包含不安全内容，已被拦截", 403);
        }
        allViolations.addAll(injectionViolations);

        // 2. 敏感数据检测与脱敏
        // 注意：脱敏内容仅用于审计记录（SecurityCheckResult），
        // 不替换原始 message 内容 — Agent 需要完整语义上下文才能正确理解用户意图。
        // 敏感数据的保护通过审计日志脱敏 + 响应后处理实现，而非输入截断。
        var sensitiveResult = sensitiveDataDetector.detect(content);
        allViolations.addAll(sensitiveResult.violations());
        String redactedContent = sensitiveResult.redactedContent();

        // 3. 信任分数计算 — 仅 TrustLevel 低于 TRUSTED 时执行
        var trustLevel = chain.context()
                .get(MiddlewareContext.KEY_TRUST_LEVEL, TrustLevel.class)
                .orElse(TrustLevel.ANONYMOUS);

        double trustScore;
        if (trustLevel == TrustLevel.TRUSTED) {
            trustScore = DEFAULT_TRUSTED_SCORE;
        } else {
            trustScore = trustScoreCalculator.calculate(message.userId());
        }

        // 4. 构建安全检查结果并放入上下文
        var checkResult = SecurityCheckResult.builder()
                .violations(allViolations)
                .blocked(false)
                .redactedContent(redactedContent)
                .trustScore(trustScore)
                .build();
        chain.context().set(MiddlewareContext.KEY_SECURITY_CHECK_RESULT, checkResult);

        log.debug("安全检查通过: messageId={}, violations={}, trustScore={}",
                message.messageId(), allViolations.size(), trustScore);

        return chain.next(message);
    }

    /**
     * 判断违规列表中是否包含 CRITICAL 或 HIGH 级别的违规。
     */
    private boolean hasBlockingViolation(List<SecurityViolation> violations) {
        return violations.stream()
                .anyMatch(v -> "CRITICAL".equals(v.severity()) || "HIGH".equals(v.severity()));
    }

    @Override
    public int order() {
        return properties.middleware().security().order();
    }

    @Override
    public String name() {
        return "security";
    }

    @Override
    public boolean enabled() {
        return properties.middleware().security().enabled();
    }
}
