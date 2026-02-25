package com.lifepilot.interaction.channel.converter;

import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.ResponseContent;

/**
 * 消息格式转换器接口，将统一 {@link ResponseContent} 转换为通道特定的字符串格式。
 *
 * <p>每个通道实现自己的转换器，将 Agent 响应转换为平台可接受的消息格式。
 *
 * @author zsg
 * @since 2026-02-26
 */
public interface MessageConverter {

    /**
     * 返回此转换器对应的通道类型。
     *
     * @return 通道类型
     */
    ChannelType channelType();

    /**
     * 将统一响应内容转换为通道特定的字符串格式。
     *
     * @param content 响应内容
     * @return 通道特定格式的字符串
     */
    String convert(ResponseContent content);
}
