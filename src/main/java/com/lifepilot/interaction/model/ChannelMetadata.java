package com.lifepilot.interaction.model;

import org.springframework.lang.Nullable;

/**
 * 通道元数据 sealed interface，穷举所有通道的元数据类型。
 *
 * <p>每个 permit 返回对应的 {@link ChannelType} 枚举值，
 * 认证和审计逻辑可以通过 switch 表达式类型安全地处理通道特定信息。
 *
 * @author zsg
 * @since 2026-02-25
 */
public sealed interface ChannelMetadata
        permits ChannelMetadata.WebMetadata,
                ChannelMetadata.WecomMetadata,
                ChannelMetadata.DingtalkMetadata,
                ChannelMetadata.FeishuMetadata {

    /**
     * 获取通道类型。
     *
     * @return 对应的 {@link ChannelType} 枚举值
     */
    ChannelType channelType();

    /**
     * Web 通道元数据。
     *
     * @param userAgent    用户代理字符串
     * @param remoteAddr   远程地址
     * @param sessionToken 会话令牌（可空）
     * @param acceptsSse   是否接受 SSE 流式响应
     * @author zsg
     * @since 2026-02-25
     */
    record WebMetadata(String userAgent, String remoteAddr,
                       @Nullable String sessionToken, boolean acceptsSse)
            implements ChannelMetadata {

        @Override
        public ChannelType channelType() {
            return ChannelType.WEB;
        }
    }

    /**
     * 企业微信通道元数据。
     *
     * @param corpId       企业 ID
     * @param agentId      应用 ID
     * @param msgSignature 消息签名
     * @param timestamp    时间戳
     * @param nonce        随机数
     * @param encryptedMsg 加密消息（可空）
     * @author zsg
     * @since 2026-02-25
     */
    record WecomMetadata(String corpId, String agentId, String msgSignature,
                         String timestamp, String nonce, @Nullable String encryptedMsg)
            implements ChannelMetadata {

        @Override
        public ChannelType channelType() {
            return ChannelType.WECOM;
        }
    }

    /**
     * 钉钉通道元数据。
     *
     * @param chatbotUserId    机器人用户 ID
     * @param conversationId   会话 ID
     * @param conversationType 会话类型
     * @param senderNick       发送者昵称
     * @param sign             签名
     * @param timestamp        时间戳
     * @param isAtAll          是否 @所有人
     * @author zsg
     * @since 2026-02-25
     */
    record DingtalkMetadata(String chatbotUserId, String conversationId,
                            String conversationType, String senderNick,
                            String sign, long timestamp, boolean isAtAll)
            implements ChannelMetadata {

        @Override
        public ChannelType channelType() {
            return ChannelType.DINGTALK;
        }
    }

    /**
     * 飞书通道元数据。
     *
     * @param appId      应用 ID
     * @param tenantKey  租户标识
     * @param messageId  消息 ID
     * @param chatId     群聊 ID（可空，私聊时为空）
     * @param chatType   聊天类型
     * @param eventId    事件 ID
     * @param eventType  事件类型
     * @author zsg
     * @since 2026-02-25
     */
    record FeishuMetadata(String appId, String tenantKey, String messageId,
                          @Nullable String chatId, String chatType,
                          String eventId, String eventType)
            implements ChannelMetadata {

        @Override
        public ChannelType channelType() {
            return ChannelType.FEISHU;
        }
    }
}
