package com.lifepilot.interaction.model;

import org.springframework.lang.Nullable;

/**
 * 统一交互来源模型。
 *
 * <p>sourceId 是面向运行时的稳定标识；对于渠道来源，额外记录平台与实例 ID。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public record InteractionSource(
        SourceKind sourceKind,
        String sourceId,
        @Nullable String channelPlatform,
        @Nullable String channelInstanceId
) {

    public InteractionSource {
        sourceKind = sourceKind != null ? sourceKind : SourceKind.SYSTEM;
        sourceId = sourceId != null && !sourceId.isBlank() ? sourceId : "unknown";
        channelPlatform = normalize(channelPlatform);
        channelInstanceId = normalize(channelInstanceId);
    }

    public static InteractionSource channel(String platform, String instanceId) {
        return channel(instanceId, platform, instanceId);
    }

    public static InteractionSource channel(String sourceId, String platform, String instanceId) {
        return new InteractionSource(SourceKind.CHANNEL, sourceId, platform, instanceId);
    }

    public static InteractionSource workflow(String sourceId) {
        return new InteractionSource(SourceKind.WORKFLOW, sourceId, null, null);
    }

    public static InteractionSource cron(String sourceId) {
        return new InteractionSource(SourceKind.CRON, sourceId, null, null);
    }

    public static InteractionSource heartbeat(String sourceId) {
        return new InteractionSource(SourceKind.HEARTBEAT, sourceId, null, null);
    }

    public static InteractionSource system(String sourceId) {
        return new InteractionSource(SourceKind.SYSTEM, sourceId, null, null);
    }

    /**
     * 从旧版 channel 字符串推断来源。
     *
     * <p>第一阶段保留旧调用入口时，统一通过该方法归并到新来源模型。</p>
     */
    public static InteractionSource legacy(@Nullable String rawChannel, @Nullable String sessionId) {
        String normalized = normalize(rawChannel);
        if (normalized == null) {
            return system("unknown");
        }
        return switch (normalized.toLowerCase()) {
            case "web", "web-test" -> channel(normalized, "web", "web.default");
            case "feishu" -> channel("feishu", "feishu", "feishu.default");
            case "wecom" -> channel("wecom", "wecom", "wecom.default");
            case "dingtalk" -> channel("dingtalk", "dingtalk", "dingtalk.default");
            default -> {
                if (normalized.startsWith("cron")) {
                    yield cron(resolveScopedSourceId(normalized, sessionId, "cron"));
                }
                if (normalized.startsWith("heartbeat")) {
                    yield heartbeat(resolveScopedSourceId(normalized, sessionId, "heartbeat"));
                }
                if (normalized.startsWith("workflow")) {
                    yield workflow(resolveScopedSourceId(normalized, sessionId, "workflow"));
                }
                yield system(normalized);
            }
        };
    }

    public boolean isChannel() {
        return sourceKind == SourceKind.CHANNEL;
    }

    public boolean isAutonomous() {
        return sourceKind == SourceKind.CRON
                || sourceKind == SourceKind.HEARTBEAT
                || sourceKind == SourceKind.WORKFLOW;
    }

    public String displayValue() {
        return sourceId;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String resolveScopedSourceId(String rawChannel,
                                                @Nullable String sessionId,
                                                String prefix) {
        if (sessionId != null && !sessionId.isBlank() && sessionId.startsWith(prefix + ":")) {
            return sessionId;
        }
        return rawChannel;
    }
}
