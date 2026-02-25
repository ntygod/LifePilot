package com.lifepilot.interaction.config;

import java.nio.file.Path;
import java.util.UUID;

import com.lifepilot.interaction.middleware.audit.AuditEventRepository;
import com.lifepilot.interaction.middleware.audit.AuditMiddleware;
import com.lifepilot.interaction.middleware.audit.DataRedactor;
import com.lifepilot.interaction.middleware.auth.AuthMiddleware;
import com.lifepilot.interaction.middleware.auth.CliAuthStrategy;
import com.lifepilot.interaction.middleware.ratelimit.RateLimitMiddleware;
import com.lifepilot.interaction.middleware.router.RouterMiddleware;
import com.lifepilot.interaction.middleware.security.PromptInjectionDetector;
import com.lifepilot.interaction.middleware.security.SecurityMiddleware;
import com.lifepilot.interaction.middleware.security.SensitiveDataDetector;
import com.lifepilot.interaction.middleware.security.TrustScoreCalculator;
import com.lifepilot.interaction.middleware.GatewayMiddleware;
import com.lifepilot.interaction.middleware.MiddlewarePipeline;
import com.lifepilot.interaction.gateway.MessageGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GatewayMiddlewareAutoConfiguration 集成测试。
 *
 * <p>验证所有中间件 Bean 注册成功、依赖注入链完整。
 *
 * @author zsg
 * @since 2026-02-25
 */
@SpringBootTest
@ActiveProfiles("test")
class GatewayMiddlewareAutoConfiguration_集成测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "gw-autoconfig-test-" + DB_ID + ".db").toString().replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "gw-autoconfig-vec-test-" + DB_ID + ".db").toString().replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void 认证相关Bean_注册成功() {
        assertThat(context.getBean(CliAuthStrategy.class)).isNotNull();
        assertThat(context.getBean(AuthMiddleware.class)).isNotNull();
    }

    @Test
    void 限流相关Bean_注册成功() {
        assertThat(context.getBean(RateLimitMiddleware.class)).isNotNull();
    }

    @Test
    void 安全相关Bean_注册成功() {
        assertThat(context.getBean(PromptInjectionDetector.class)).isNotNull();
        assertThat(context.getBean(SensitiveDataDetector.class)).isNotNull();
        assertThat(context.getBean(TrustScoreCalculator.class)).isNotNull();
        assertThat(context.getBean(SecurityMiddleware.class)).isNotNull();
    }

    @Test
    void 路由相关Bean_注册成功() {
        assertThat(context.getBean(RouterMiddleware.class)).isNotNull();
    }

    @Test
    void 审计相关Bean_注册成功() {
        assertThat(context.getBean(DataRedactor.class)).isNotNull();
        assertThat(context.getBean(AuditEventRepository.class)).isNotNull();
        assertThat(context.getBean(AuditMiddleware.class)).isNotNull();
    }

    @Test
    void 框架Bean_注册成功() {
        assertThat(context.getBean(MiddlewarePipeline.class)).isNotNull();
        assertThat(context.getBean(MessageGateway.class)).isNotNull();
    }

    @Test
    void 中间件Pipeline_收集到所有启用的中间件() {
        // Pipeline 自动收集所有 GatewayMiddleware Bean
        var middlewares = context.getBeansOfType(GatewayMiddleware.class);
        // Auth, RateLimit, Security, Router, Audit（ExecutionMiddleware 需要 AgentLoop，测试环境未注册）
        assertThat(middlewares.size()).isGreaterThanOrEqualTo(5);
    }
}
