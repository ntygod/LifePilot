package com.lifepilot.interaction.model;

/**
 * 通道类型枚举，标识消息来源通道。
 *
 * <p>每个枚举值持有字符串标识（{@link #value}）和是否需要 Webhook 的标识（{@link #requiresWebhook}）。
 * 提供 {@link #fromValue(String)} 静态方法从字符串解析为枚举值。
 *
 * @author zsg
 * @since 2026-02-25
 */
public enum ChannelType {

    WEB("web", false),
    WECOM("wecom", true),
    DINGTALK("dingtalk", true),
    FEISHU("feishu", true),
    QQ("qq", false);

    private final String value;
    private final boolean requiresWebhook;

    ChannelType(String value, boolean requiresWebhook) {
        this.value = value;
        this.requiresWebhook = requiresWebhook;
    }

    /**
     * 获取通道类型的字符串标识。
     */
    public String value() {
        return value;
    }

    /**
     * 是否需要 Webhook 接入。
     */
    public boolean requiresWebhook() {
        return requiresWebhook;
    }

    /**
     * 从字符串值解析为对应的 ChannelType 枚举值。
     *
     * @param value 通道类型字符串标识
     * @return 对应的 ChannelType 枚举值
     * @throws IllegalArgumentException 未知的通道类型字符串
     */
    public static ChannelType fromValue(String value) {
        for (ChannelType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的通道类型: " + value);
    }
}
