package com.lifepilot.interaction.channel;

import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;

/**
 * 通道适配器接口，定义通道消息标准化和响应发送的契约。
 *
 * <p>每个交互通道（CLI / Web / 企微 / 钉钉 / 飞书）需实现此接口，
 * 将通道特定的原始消息转换为统一的 {@link GatewayMessage}，
 * 并将 {@link GatewayResponse} 转换为通道特定格式发送给用户。
 *
 * @author zsg
 * @since 2026-02-25
 */
public interface ChannelAdapter {

    /**
     * 返回此适配器对应的通道类型。
     *
     * @return 通道类型枚举值
     */
    ChannelType channelType();

    /**
     * 将通道特定的原始消息转换为统一的 {@link GatewayMessage}。
     *
     * <p>各通道原始消息类型不同，使用 {@code Object} 保持通用性，
     * 具体实现负责类型转换和字段映射。
     *
     * @param rawMessage 通道特定的原始消息对象
     * @return 标准化后的网关消息
     */
    GatewayMessage normalize(Object rawMessage);

    /**
     * 将网关响应转换为通道特定格式并发送给用户。
     *
     * @param userId   目标用户标识
     * @param response 网关响应
     */
    void sendResponse(String userId, GatewayResponse response);

    /**
     * 启动通道适配器。
     *
     * <p>执行通道特定的初始化逻辑，如建立 Webhook 连接、启动监听等。
     */
    void start();

    /**
     * 停止通道适配器。
     *
     * <p>执行通道特定的清理逻辑，如断开连接、释放资源等。
     */
    void stop();
}
