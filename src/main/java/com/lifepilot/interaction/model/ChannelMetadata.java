package com.lifepilot.interaction.model;

import com.lifepilot.interaction.web.model.ChatTurnAction;
import org.springframework.lang.Nullable;

/**
 * 通道元数据 sealed interface。
 *
 * <p>当前主服务只保留 Web 本地渠道需要的元数据。
 * 外部渠道的协议细节应停留在 connector，不再进入核心执行链路。</p>
 * 
 * @author zsg
 * @since 2026-02-25
 */
public sealed interface ChannelMetadata permits ChannelMetadata.WebMetadata {

    /**
     * 获取通道类型。
     *
     * @return 对应的 {@link ChannelType} 枚举值
     */
    ChannelType channelType();

    /**
     * Web 通道元数据。
     *
     * @param userAgent         用户代理字符串
     * @param remoteAddr        远程地址
     * @param sessionToken      会话令牌（可空）
     * @param acceptsSse        是否接受 SSE 流式响应
     * @param preferredProvider 会话级偏好 LLM Provider（可空，使用默认路由）
     * @author zsg
     * @since 2026-02-25
     */
    record WebMetadata(String userAgent, String remoteAddr,
                       @Nullable String sessionToken, boolean acceptsSse,
                       @Nullable String preferredProvider,
                       @Nullable String turnId,
                       @Nullable ChatTurnAction action)
            implements ChannelMetadata {

        public WebMetadata(String userAgent, String remoteAddr,
                           @Nullable String sessionToken, boolean acceptsSse,
                           @Nullable String preferredProvider) {
            this(userAgent, remoteAddr, sessionToken, acceptsSse, preferredProvider, null, ChatTurnAction.SEND);
        }

        @Override
        public ChannelType channelType() {
            return ChannelType.WEB;
        }
    }
}
