package com.lifepilot.interaction.model;

/**
 * 交互链路 trace header 键。
 *
 * <p>用于在网关消息中透传来源与投递模式，逐步替代平台特化元数据上的耦合判断。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public final class InteractionTraceHeaders {

    public static final String DELIVERY_MODE = "deliveryMode";
    public static final String SOURCE_KIND = "sourceKind";
    public static final String SOURCE_ID = "sourceId";
    public static final String CHANNEL_PLATFORM = "channelPlatform";
    public static final String CHANNEL_INSTANCE_ID = "channelInstanceId";
    public static final String AUTH_USER_ID = "authUserId";
    public static final String AUTH_TRUST_LEVEL = "authTrustLevel";

    private InteractionTraceHeaders() {
    }
}
