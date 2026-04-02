package com.lifepilot.interaction.config;

import com.lifepilot.interaction.gateway.MessageGateway;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 网关就绪健康指标，确保 Tauri 健康检查在网关真正启动后才返回 UP。
 *
 * <p>注册为 Spring Boot Actuator HealthIndicator，当网关未运行时
 * 返回 OUT_OF_SERVICE，阻止 Tauri 端过早认为后端已就绪。</p>
 *
 * @author zsg
 * @since 2026-04-02
 */
public class GatewayHealthIndicator implements HealthIndicator {

    private final MessageGateway gateway;

    public GatewayHealthIndicator(MessageGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public Health health() {
        if (gateway.isRunning()) {
            return Health.up().withDetail("gateway", "running").build();
        }
        return Health.outOfService().withDetail("gateway", "not started").build();
    }
}
