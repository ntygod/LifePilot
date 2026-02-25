package com.lifepilot.interaction.gateway;

import java.util.List;
import java.util.Optional;

import com.lifepilot.interaction.channel.ChannelAdapter;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;

/**
 * 消息网关接口，定义网关的核心操作。
 *
 * <p>Gateway 是所有交互通道的统一消息入口，负责接收消息、
 * 推入中间件管道处理、管理通道适配器的注册与生命周期。
 * 网关实现可替换而不影响上层调用方。
 *
 * @author zsg
 * @since 2026-02-25
 */
public interface MessageGateway {

    /**
     * 处理一条网关消息，将其推入中间件管道执行。
     *
     * @param message 统一网关消息
     * @return 网关响应
     */
    GatewayResponse process(GatewayMessage message);

    /**
     * 注册通道适配器。
     *
     * <p>同一通道类型不允许重复注册，否则抛出 {@link IllegalStateException}。
     * 若网关已在运行中，新注册的通道将立即启动。
     *
     * @param adapter 通道适配器
     * @throws IllegalStateException 如果该通道类型已注册
     */
    void registerChannel(ChannelAdapter adapter);

    /**
     * 按通道类型注销通道适配器。
     *
     * @param channelType 要注销的通道类型
     */
    void unregisterChannel(ChannelType channelType);

    /**
     * 按通道类型获取已注册的通道适配器。
     *
     * @param channelType 通道类型
     * @return 对应的通道适配器，不存在时返回 {@link Optional#empty()}
     */
    Optional<ChannelAdapter> getChannel(ChannelType channelType);

    /**
     * 返回所有已注册通道适配器的不可变列表。
     *
     * @return 通道适配器不可变列表
     */
    List<ChannelAdapter> getAllChannels();

    /**
     * 启动网关，启动所有已注册的通道适配器。
     */
    void start();

    /**
     * 停止网关，停止所有已注册的通道适配器。
     */
    void stop();

    /**
     * 检查网关是否正在运行。
     *
     * @return 如果网关正在运行则返回 true
     */
    boolean isRunning();
}
