package com.lifepilot.interaction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.repository.ChannelInstanceEventRepository;
import com.lifepilot.interaction.repository.ChannelInstanceRepository;
import com.lifepilot.interaction.repository.ChannelPluginRepository;
import com.lifepilot.interaction.runtime.ConnectorManager;
import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import com.lifepilot.interaction.runtime.ChannelRuntimeIngressService;
import com.lifepilot.interaction.runtime.ConnectorRuntimeManager;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.interaction.service.ChannelIngressService;
import com.lifepilot.interaction.service.ChannelInstanceEventService;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.marketplace.install.InstalledExtensionRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 渠道控制面配置。
 *
 * <p>先落地插件注册、实例管理和运行状态管理三类核心 Bean，
 * 为后续统一 ingress / delivery 重构提供稳定骨架。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
@Configuration(proxyBeanMethods = false)
public class ChannelControlPlaneConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChannelControlPlaneConfiguration.class);

    @Bean
    public ChannelRegistry channelRegistry() {
        return new ChannelRegistry();
    }

    @Bean
    public ChannelPluginRepository channelPluginRepository(JdbcTemplate jdbcTemplate,
                                                           ObjectMapper objectMapper) {
        return new ChannelPluginRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    public ChannelInstanceRepository channelInstanceRepository(JdbcTemplate jdbcTemplate,
                                                               ObjectMapper objectMapper) {
        return new ChannelInstanceRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    public ChannelInstanceEventRepository channelInstanceEventRepository(JdbcTemplate jdbcTemplate,
                                                                        ObjectMapper objectMapper) {
        return new ChannelInstanceEventRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    public ChannelInstanceService channelInstanceService(ChannelRegistry channelRegistry,
                                                         ChannelInstanceRepository channelInstanceRepository) {
        return new ChannelInstanceService(channelRegistry, channelInstanceRepository);
    }

    @Bean
    public ChannelInstanceEventService channelInstanceEventService(ChannelInstanceEventRepository channelInstanceEventRepository) {
        return new ChannelInstanceEventService(channelInstanceEventRepository);
    }

    @Bean
    public ConnectorManagerProperties connectorManagerProperties(Environment environment) {
        return Binder.get(environment)
                .bind("lifepilot.gateway.channels.connector-manager", ConnectorManagerProperties.class)
                .orElseGet(ConnectorManagerProperties::new);
    }

    @Bean
    public ConnectorManager connectorManager(ConnectorManagerProperties properties,
                                             Environment environment,
                                             ObjectProvider<InstalledExtensionRepository> installedExtensionRepositoryProvider) {
        return new ConnectorManager(
                properties,
                environment,
                RestClient.create(),
                installedExtensionRepositoryProvider.getIfAvailable()
        );
    }

    @Bean
    public ConnectorRuntimeManager connectorRuntimeManager(ChannelInstanceService channelInstanceService,
                                                           ChannelRegistry channelRegistry,
                                                           ChannelInstanceEventService channelInstanceEventService,
                                                           ConnectorManager connectorManager) {
        return new ConnectorRuntimeManager(
                channelInstanceService,
                channelRegistry,
                channelInstanceEventService,
                RestClient.create(),
                connectorManager
        );
    }

    @Bean
    public ChannelIngressService channelIngressService(ObjectProvider<MessageGateway> gatewayProvider) {
        return new ChannelIngressService(gatewayProvider);
    }

    @Bean
    public ChannelDeliveryDispatcher channelDeliveryDispatcher(ChannelRegistry channelRegistry,
                                                              ChannelInstanceEventService channelInstanceEventService,
                                                              ConnectorManager connectorManager,
                                                              @Nullable SseSessionManager sseSessionManager) {
        return new ChannelDeliveryDispatcher(
                channelRegistry,
                channelInstanceEventService,
                RestClient.create(),
                connectorManager,
                sseSessionManager
        );
    }

    @Bean
    public ChannelRuntimeIngressService channelRuntimeIngressService(ChannelInstanceService channelInstanceService,
                                                                    ChannelIngressService channelIngressService,
                                                                    ConnectorRuntimeManager connectorRuntimeManager,
                                                                    ChannelInstanceEventService channelInstanceEventService,
                                                                    ChannelDeliveryDispatcher channelDeliveryDispatcher) {
        return new ChannelRuntimeIngressService(
                channelInstanceService,
                channelIngressService,
                connectorRuntimeManager,
                channelInstanceEventService,
                channelDeliveryDispatcher
        );
    }

    @Bean
    public ApplicationRunner builtinChannelBootstrap(ChannelRegistry channelRegistry,
                                                     ChannelPluginRepository channelPluginRepository,
                                                     ChannelInstanceService channelInstanceService,
                                                     ConnectorRuntimeManager connectorRuntimeManager) {
        return args -> {
            channelPluginRepository.findAll().forEach(channelRegistry::register);
            BuiltinChannelCatalog.builtinPlugins().forEach(descriptor -> {
                channelRegistry.register(descriptor);
                channelPluginRepository.save(descriptor);
            });
            channelInstanceService.ensurePresent(BuiltinChannelCatalog.webDefaultInstance());
            channelInstanceService.listAll().forEach(instance -> restoreInstance(
                    instance,
                    channelInstanceService,
                    connectorRuntimeManager
            ));
        };
    }

    private void restoreInstance(com.lifepilot.interaction.model.ChannelInstance instance,
                                 ChannelInstanceService channelInstanceService,
                                 ConnectorRuntimeManager connectorRuntimeManager) {
        if (!instance.enabled()) {
            if (instance.status() == ChannelInstanceStatus.RUNNING
                    || instance.status() == ChannelInstanceStatus.STARTING) {
                channelInstanceService.updateStatus(
                        instance.instanceId(),
                        ChannelInstanceStatus.STOPPED,
                        null,
                        instance.lastHeartbeatAt()
                );
            }
            return;
        }
        try {
            connectorRuntimeManager.start(instance.instanceId());
        } catch (Exception e) {
            log.warn("渠道实例启动恢复失败: instanceId={}, error={}",
                    instance.instanceId(), e.getMessage());
        }
    }
}
