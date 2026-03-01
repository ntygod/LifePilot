package com.lifepilot.interaction.config;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.lifepilot.agent.AgentLoop;
import com.lifepilot.interaction.middleware.audit.AuditEventRepository;
import com.lifepilot.interaction.middleware.audit.AuditMiddleware;
import com.lifepilot.observability.redactor.DataRedactor;
import com.lifepilot.interaction.middleware.auth.AuthMiddleware;
import com.lifepilot.interaction.middleware.auth.AuthStrategy;
import com.lifepilot.interaction.middleware.execution.ExecutionMiddleware;
import com.lifepilot.interaction.middleware.ratelimit.RateLimitMiddleware;
import com.lifepilot.interaction.middleware.router.RouterMiddleware;
import com.lifepilot.interaction.middleware.security.PromptInjectionDetector;
import com.lifepilot.interaction.middleware.security.SecurityMiddleware;
import com.lifepilot.interaction.middleware.security.SensitiveDataDetector;
import com.lifepilot.interaction.middleware.security.TrustScoreCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Gateway 中间件自动配置，注册所有 6 个中间件 Bean 和支撑 Bean。
 *
 * <p>在 {@link GatewayAutoConfiguration} 之后加载，确保 Pipeline 和 Gateway 已注册。
 * 通过 {@code lifepilot.gateway.enabled} 条件控制整体启用。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@AutoConfiguration(after = GatewayAutoConfiguration.class)
@ConditionalOnProperty(name = "lifepilot.gateway.enabled", matchIfMissing = true)
public class GatewayMiddlewareAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayMiddlewareAutoConfiguration.class);

    // ── 认证相关 ──────────────────────────────────────────────────

    @Bean
    public AuthMiddleware authMiddleware(List<AuthStrategy> strategies, GatewayProperties properties) {
        var strategyMap = strategies.stream()
                .collect(Collectors.toMap(AuthStrategy::supportedChannel, Function.identity()));
        log.info("注册 AuthMiddleware，策略数量: {}", strategyMap.size());
        return new AuthMiddleware(strategyMap, properties);
    }

    // ── 限流相关 ──────────────────────────────────────────────────

    @Bean
    public RateLimitMiddleware rateLimitMiddleware(GatewayProperties properties) {
        log.info("注册 RateLimitMiddleware");
        return new RateLimitMiddleware(properties);
    }

    // ── 安全相关 ──────────────────────────────────────────────────

    @Bean
    public PromptInjectionDetector promptInjectionDetector() {
        return new PromptInjectionDetector();
    }

    @Bean
    public SensitiveDataDetector sensitiveDataDetector() {
        return new SensitiveDataDetector();
    }

    @Bean
    public TrustScoreCalculator trustScoreCalculator(JdbcTemplate jdbcTemplate) {
        return new TrustScoreCalculator(jdbcTemplate);
    }

    @Bean
    public SecurityMiddleware securityMiddleware(PromptInjectionDetector injectionDetector,
                                                 SensitiveDataDetector sensitiveDataDetector,
                                                 TrustScoreCalculator trustScoreCalculator,
                                                 GatewayProperties properties) {
        log.info("注册 SecurityMiddleware");
        return new SecurityMiddleware(injectionDetector, sensitiveDataDetector,
                trustScoreCalculator, properties);
    }

    // ── 路由相关 ──────────────────────────────────────────────────

    @Bean
    public RouterMiddleware routerMiddleware(GatewayProperties properties) {
        log.info("注册 RouterMiddleware");
        return new RouterMiddleware(properties);
    }

    // ── 执行相关 ──────────────────────────────────────────────────

    @Bean
    @ConditionalOnBean(AgentLoop.class)
    public ExecutionMiddleware executionMiddleware(AgentLoop agentLoop, GatewayProperties properties) {
        log.info("注册 ExecutionMiddleware");
        return new ExecutionMiddleware(agentLoop, properties);
    }

    // ── 审计相关 ──────────────────────────────────────────────────

    @Bean
    public AuditEventRepository auditEventRepository(JdbcTemplate jdbcTemplate) {
        return new AuditEventRepository(jdbcTemplate);
    }

    @Bean
    public AuditMiddleware auditMiddleware(AuditEventRepository repository,
                                           DataRedactor redactor,
                                           GatewayProperties properties) {
        log.info("注册 AuditMiddleware");
        return new AuditMiddleware(repository, redactor, properties);
    }

}
