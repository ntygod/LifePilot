package com.lifepilot.interaction.service;

import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 统一渠道入站服务。
 *
 * <p>负责把已经标准化的入站消息送入消息网关，后续可在这里接入实例级鉴权、
 * connector 来源校验和统一审计。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ChannelIngressService {

    private final ObjectProvider<MessageGateway> messageGatewayProvider;

    public ChannelIngressService(ObjectProvider<MessageGateway> messageGatewayProvider) {
        this.messageGatewayProvider = messageGatewayProvider;
    }

    public GatewayResponse submitSync(GatewayMessage message) {
        MessageGateway messageGateway = messageGatewayProvider.getIfAvailable();
        if (messageGateway == null) {
            throw new IllegalStateException("MessageGateway 当前不可用，无法处理渠道入站消息");
        }
        return messageGateway.process(message);
    }
}
