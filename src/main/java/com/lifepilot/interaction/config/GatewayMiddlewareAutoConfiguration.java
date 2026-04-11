package com.lifepilot.interaction.config;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.interaction.middleware.audit.AuditEventRepository;
import com.lifepilot.interaction.middleware.audit.AuditMiddleware;
import com.lifepilot.interaction.middleware.auth.AuthMiddleware;
import com.lifepilot.interaction.middleware.auth.AuthStrategy;
import com.lifepilot.interaction.middleware.execution.ExecutionMiddleware;
import com.lifepilot.interaction.middleware.ratelimit.RateLimitMiddleware;
import com.lifepilot.interaction.middleware.router.RouterMiddleware;
import com.lifepilot.interaction.middleware.security.PromptInjectionDetector;
import com.lifepilot.interaction.middleware.security.SecurityMiddleware;
import com.lifepilot.interaction.middleware.security.SensitiveDataDetector;
import com.lifepilot.interaction.middleware.security.TrustScoreCalculator;
import com.lifepilot.config.threadpool.VirtualThreadExecutorFactory;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.service.ChatTurnService;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.observability.redactor.DataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 网关中间件自动装配 — 注册认证、限流、安全、路由、执行、审计等中间件。
 *
 * @author zsg
 * @since 2026-02-25
 */
@AutoConfiguration(after = GatewayAutoConfiguration.class)
@ConditionalOnProperty(name = "lifepilot.gateway.enabled", matchIfMissing = true)
public class GatewayMiddlewareAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayMiddlewareAutoConfiguration.class);

    @Bean
    public AuthMiddleware authMiddleware(List<AuthStrategy> strategies, GatewayProperties properties) {
        var strategyMap = strategies.stream()
                .collect(Collectors.toMap(AuthStrategy::supportedChannel, Function.identity()));
        log.info("注册 AuthMiddleware: 策略数={}", strategyMap.size());
        return new AuthMiddleware(strategyMap, properties);
    }

    @Bean
    public RateLimitMiddleware rateLimitMiddleware(GatewayProperties properties) {
        log.info("注册 RateLimitMiddleware");
        return new RateLimitMiddleware(properties);
    }

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
        return new SecurityMiddleware(injectionDetector, sensitiveDataDetector, trustScoreCalculator, properties);
    }

    @Bean
    public RouterMiddleware routerMiddleware(GatewayProperties properties) {
        log.info("注册 RouterMiddleware");
        return new RouterMiddleware(properties);
    }

    @Bean
    public ExecutionMiddleware executionMiddleware(AgentOrchestrator agentOrchestrator,
                                                   AgentConfigProperties agentConfigProperties,
                                                   GatewayProperties properties,
                                                   ObjectProvider<SseSessionManager> sseSessionManagerProvider,
                                                   ChatSessionRepository chatSessionRepository,
                                                   ChatTurnService chatTurnService,
                                                   VirtualThreadExecutorFactory virtualThreadExecutorFactory) {
        log.info("注册 ExecutionMiddleware");
        return new ExecutionMiddleware(
                agentOrchestrator,
                agentConfigProperties,
                properties,
                chatSessionRepository,
                chatTurnService,
                sseSessionManagerProvider.getIfAvailable(),
                virtualThreadExecutorFactory.create("agent-exec")
        );
    }

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
