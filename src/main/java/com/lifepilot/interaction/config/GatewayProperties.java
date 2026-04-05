package com.lifepilot.interaction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Gateway 核心框架配置属性。
 *
 * <p>绑定 {@code lifepilot.gateway} 配置前缀。使用 record + {@link DefaultValue} 实现不可变配置绑定。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@ConfigurationProperties(prefix = "lifepilot.gateway")
public record GatewayProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue MiddlewareProperties middleware,
        @DefaultValue RateLimitProperties rateLimit,
        @DefaultValue SecurityProperties security,
        @DefaultValue AuthProperties auth,
        @DefaultValue RouterProperties router,
        @DefaultValue ExecutionProperties execution,
        @DefaultValue AuditProperties audit,
        @DefaultValue ChannelsProperties channels,
        @DefaultValue ReconnectProperties reconnect,
        @DefaultValue SessionProperties session,
        @DefaultValue WebhookProperties webhook
) {

    // ── 中间件启用与排序 ──────────────────────────────────────────

    /** 中间件启用与排序配置。 */
    public record MiddlewareProperties(
            @DefaultValue AuthMiddlewareProperties auth,
            @DefaultValue RateLimitMiddlewareProperties rateLimit,
            @DefaultValue SecurityMiddlewareProperties security,
            @DefaultValue RouterMiddlewareProperties router,
            @DefaultValue ExecutionMiddlewareProperties execution,
            @DefaultValue AuditMiddlewareProperties audit
    ) {
        /** 认证中间件配置。 */
        public record AuthMiddlewareProperties(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("100") int order
        ) {}

        /** 限流中间件配置。 */
        public record RateLimitMiddlewareProperties(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("200") int order
        ) {}

        /** 安全中间件配置。 */
        public record SecurityMiddlewareProperties(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("300") int order
        ) {}

        /** 路由中间件配置。 */
        public record RouterMiddlewareProperties(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("400") int order
        ) {}

        /** 执行中间件配置。 */
        public record ExecutionMiddlewareProperties(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("500") int order
        ) {}

        /** 审计中间件配置。 */
        public record AuditMiddlewareProperties(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("600") int order
        ) {}
    }

    // ── 限流配置 ──────────────────────────────────────────────────

    /** 限流配置。 */
    public record RateLimitProperties(
            @DefaultValue("30") int maxRequestsPerMinute
    ) {}

    // ── 安全配置 ──────────────────────────────────────────────────

    /** 安全配置。 */
    public record SecurityProperties(
            @DefaultValue PromptInjectionProperties promptInjection,
            @DefaultValue SensitiveDataProperties sensitiveData,
            @DefaultValue TrustScoreProperties trustScore
    ) {
        /** 提示词注入检测配置。 */
        public record PromptInjectionProperties(
                @DefaultValue("true") boolean enabled
        ) {}

        /** 敏感数据检测配置。 */
        public record SensitiveDataProperties(
                @DefaultValue("true") boolean enabled
        ) {}

        /** 信任分数配置。 */
        public record TrustScoreProperties(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("30") int cacheTtlMinutes
        ) {}
    }

    // ── 认证配置 ──────────────────────────────────────────────────

    /** 认证配置。 */
    public record AuthProperties(
            @DefaultValue WebAuthProperties web
    ) {
        /** Web 认证配置。 */
        public record WebAuthProperties(
                @DefaultValue JwtProperties jwt,
                @DefaultValue SessionAuthProperties session
        ) {
            /** JWT 配置。 */
            public record JwtProperties(
                    @DefaultValue("false") boolean enabled,
                    @DefaultValue("24") int expirationHours
            ) {}

            /** Session 认证配置。 */
            public record SessionAuthProperties(
                    @DefaultValue("true") boolean enabled,
                    @DefaultValue("30") int timeoutMinutes
            ) {}
        }
    }

    // ── 路由配置 ──────────────────────────────────────────────────

    /** 路由配置。 */
    public record RouterProperties(
            @DefaultValue({"todo", "schedule", "habit", "llm", "mcp", "skill"})
            List<String> fastPathCommands
    ) {}

    // ── 执行配置 ──────────────────────────────────────────────────

    /** 执行配置。 */
    public record ExecutionProperties(
            @DefaultValue("120") int timeoutSeconds,
            @DefaultValue("true") boolean streamingEnabled
    ) {}

    // ── 审计配置 ──────────────────────────────────────────────────

    /** 审计配置。 */
    public record AuditProperties(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("200") int requestSummaryMaxLength,
            @DefaultValue("200") int responseSummaryMaxLength,
            @DefaultValue("90") int retentionDays
    ) {}

    // ── 通道配置 ──────────────────────────────────────────────────

    /** 通道启用配置。 */
    public record ChannelsProperties(
            @DefaultValue CliChannelProperties cli,
            @DefaultValue WebChannelProperties web,
            @DefaultValue WecomChannelProperties wecom,
            @DefaultValue DingtalkChannelProperties dingtalk,
            @DefaultValue FeishuChannelProperties feishu,
            @DefaultValue QqChannelProperties qq
    ) {
        /** CLI 通道配置。 */
        public record CliChannelProperties(
                @DefaultValue("true") boolean enabled
        ) {}

        /** Web 通道配置。 */
        public record WebChannelProperties(
                @DefaultValue("false") boolean enabled
        ) {}

        /**
         * 企业微信通道配置。
         *
         * @param enabled        是否启用
         * @param corpId         企业 ID
         * @param agentId        应用 ID
         * @param secret         应用密钥
         * @param token          回调 Token
         * @param encodingAesKey 回调消息加密密钥
         */
        public record WecomChannelProperties(
                @DefaultValue("false") boolean enabled,
                @Nullable String corpId,
                @Nullable String agentId,
                @Nullable String secret,
                @Nullable String token,
                @Nullable String encodingAesKey
        ) {}

        /**
         * 钉钉通道配置。
         *
         * @param enabled   是否启用
         * @param appKey    应用 AppKey
         * @param appSecret 应用 AppSecret
         * @param robotCode 机器人编码
         */
        public record DingtalkChannelProperties(
                @DefaultValue("false") boolean enabled,
                @Nullable String appKey,
                @Nullable String appSecret,
                @Nullable String robotCode
        ) {}

        /**
         * 飞书通道配置。
         *
         * @param enabled           是否启用
         * @param appId             应用 ID
         * @param appSecret         应用密钥
         * @param verificationToken 验证 Token
         * @param encryptKey        事件加密密钥
         * @param eventCacheMaxSize 事件去重缓存容量上限
         */
        public record FeishuChannelProperties(
                @DefaultValue("false") boolean enabled,
                @Nullable String appId,
                @Nullable String appSecret,
                @Nullable String verificationToken,
                @Nullable String encryptKey,
                @DefaultValue("10000") int eventCacheMaxSize
        ) {}

        /**
         * QQ 机器人通道配置。
         *
         * @param enabled   是否启用
         * @param appId     QQ 机器人 AppID
         * @param appSecret QQ 机器人 AppSecret
         */
        public record QqChannelProperties(
                @DefaultValue("false") boolean enabled,
                @Nullable String appId,
                @Nullable String appSecret
        ) {}
    }

    // ── 重连配置 ──────────────────────────────────────────────────

    /** 重连配置。 */
    public record ReconnectProperties(
            @DefaultValue("10") int maxAttempts,
            @DefaultValue("1000") long initialDelayMs,
            @DefaultValue("60000") long maxDelayMs,
            @DefaultValue("2.0") double multiplier
    ) {}

    // ── 会话配置 ──────────────────────────────────────────────────

    /** 会话配置。 */
    public record SessionProperties(
            @DefaultValue("30") int idleTimeoutMinutes,
            @DefaultValue("24") int expireTimeoutHours,
            @DefaultValue("15") int cleanupIntervalMinutes
    ) {}

    // ── Webhook 配置 ──────────────────────────────────────────────

    /**
     * Webhook 通用配置。
     *
     * @param timestampToleranceSeconds 签名验证时间戳容忍窗口（秒）
     * @param maxRetryCount             失败消息最大重试次数
     * @param retryIntervalSeconds      失败消息重试间隔（秒）
     */
    public record WebhookProperties(
            @DefaultValue("300") int timestampToleranceSeconds,
            @DefaultValue("3") int maxRetryCount,
            @DefaultValue("60") int retryIntervalSeconds
    ) {}
}
