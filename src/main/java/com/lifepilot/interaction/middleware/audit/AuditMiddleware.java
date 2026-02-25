package com.lifepilot.interaction.middleware.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.middleware.GatewayMiddleware;
import com.lifepilot.interaction.middleware.MiddlewareChain;
import com.lifepilot.interaction.middleware.MiddlewareContext;
import com.lifepilot.interaction.middleware.router.RouteDecision;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计日志中间件，包裹整个管道记录请求/响应审计事件。
 *
 * <p>order=50，最先执行。在 {@code chain.next()} 前后记录时间，
 * 构建 {@link AuditEvent} 后通过 {@link CompletableFuture#runAsync} 异步持久化，
 * 持久化失败不影响响应。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class AuditMiddleware implements GatewayMiddleware {

    private static final Logger log = LoggerFactory.getLogger(AuditMiddleware.class);
    private static final int ORDER = 50;

    private final AuditEventRepository repository;
    private final DataRedactor redactor;
    private final GatewayProperties properties;

    public AuditMiddleware(AuditEventRepository repository, DataRedactor redactor,
                           GatewayProperties properties) {
        this.repository = repository;
        this.redactor = redactor;
        this.properties = properties;
    }

    @Override
    public GatewayResponse process(GatewayMessage message, MiddlewareChain chain) {
        Instant start = Instant.now();

        // 执行后续管道
        GatewayResponse response = chain.next(message);

        // 构建审计事件
        Duration latency = Duration.between(start, Instant.now());
        var event = buildAuditEvent(message, response, latency, chain.context());

        // 异步持久化，失败不影响响应
        CompletableFuture.runAsync(() -> {
            try {
                repository.save(event);
            } catch (Exception e) {
                log.error("审计事件持久化失败: auditId={}", event.auditId(), e);
            }
        });

        return response;
    }

    private AuditEvent buildAuditEvent(GatewayMessage message, GatewayResponse response,
                                        Duration latency, MiddlewareContext context) {
        int maxReqLen = properties.audit().requestSummaryMaxLength();
        int maxRespLen = properties.audit().responseSummaryMaxLength();

        String requestSummary = truncate(redactor.redact(message.contentAsText()), maxReqLen);
        String responseSummary = truncate(redactor.redact(response.content().toPlainText()), maxRespLen);
        String contentHash = sha256(message.contentAsText());
        String routeType = resolveRouteType(context);
        TokenUsage tokenUsage = response.tokenUsage();

        return AuditEvent.builder()
                .auditId(UUID.randomUUID().toString())
                .messageId(message.messageId())
                .sessionId(message.sessionId())
                .channelType(message.channelType().value())
                .userId(message.userId())
                .requestContentHash(contentHash)
                .requestSummary(requestSummary)
                .responseStatusCode(response.statusCode())
                .responseSummary(responseSummary)
                .routeType(routeType)
                .latencyMs(latency.toMillis())
                .tokenUsage(tokenUsage)
                .middlewareResultsJson(serializeMiddlewareResults(context))
                .createdAt(Instant.now())
                .build();
    }

    /**
     * 从上下文中解析路由类型。
     */
    private static String resolveRouteType(MiddlewareContext context) {
        return context.get(MiddlewareContext.KEY_ROUTE_DECISION, RouteDecision.class)
                .map(decision -> switch (decision) {
                    case RouteDecision.FastRoute _ -> "fast_path";
                    case RouteDecision.AgentRoute _ -> "agent";
                    case RouteDecision.ErrorRoute _ -> "error";
                })
                .orElse(null);
    }

    /**
     * 序列化中间件结果为 JSON 字符串（简化实现）。
     */
    private static String serializeMiddlewareResults(MiddlewareContext context) {
        Map<String, Object> snapshot = context.snapshot();
        if (snapshot.isEmpty()) {
            return null;
        }
        // 简化实现：记录上下文中的键列表
        var keys = snapshot.keySet().stream().sorted().toList();
        return "{\"keys\":" + keys + "}";
    }

    /**
     * SHA-256 哈希。
     */
    private static String sha256(String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在所有 JVM 中都可用
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 截断字符串到指定最大长度。
     */
    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public String name() {
        return "audit";
    }

    @Override
    public boolean enabled() {
        return properties.audit().enabled();
    }
}
