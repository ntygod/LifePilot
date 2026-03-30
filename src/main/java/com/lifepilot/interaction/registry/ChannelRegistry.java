package com.lifepilot.interaction.registry;

import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道插件注册中心。
 *
 * <p>负责维护已加载的渠道插件描述，不承担实例生命周期管理。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChannelRegistry.class);

    private final ConcurrentHashMap<String, ChannelPluginDescriptor> plugins = new ConcurrentHashMap<>();

    public boolean register(ChannelPluginDescriptor descriptor) {
        if (descriptor == null || descriptor.pluginId() == null || descriptor.pluginId().isBlank()) {
            log.warn("渠道插件注册失败: pluginId 为空");
            return false;
        }
        plugins.put(descriptor.pluginId(), descriptor);
        log.info("渠道插件注册成功: pluginId={}, platform={}, connectorMode={}",
                descriptor.pluginId(), descriptor.platform(), descriptor.connectorMode());
        return true;
    }

    public boolean unregister(String pluginId) {
        ChannelPluginDescriptor removed = plugins.remove(pluginId);
        if (removed != null) {
            log.info("渠道插件已注销: pluginId={}", pluginId);
            return true;
        }
        return false;
    }

    public Optional<ChannelPluginDescriptor> find(String pluginId) {
        return Optional.ofNullable(plugins.get(pluginId));
    }

    public Optional<ChannelPluginDescriptor> findByPlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return Optional.empty();
        }
        return plugins.values().stream()
                .filter(descriptor -> platform.equalsIgnoreCase(descriptor.platform()))
                .findFirst();
    }

    public List<ChannelPluginDescriptor> listAll() {
        return List.copyOf(plugins.values());
    }
}
