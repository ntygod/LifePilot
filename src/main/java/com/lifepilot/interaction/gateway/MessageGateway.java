package com.lifepilot.interaction.gateway;

import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;

/**
 * 消息网关接口，定义网关的核心操作。
 *
 * <p>Gateway 是所有交互通道的统一消息入口，负责接收消息、
 * 推入中间件管道处理并管理网关生命周期。
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
     * 启动网关。
     */
    void start();

    /**
     * 停止网关。
     */
    void stop();

    /**
     * 检查网关是否正在运行。
     *
     * @return 如果网关正在运行则返回 true
     */
    boolean isRunning();
}
