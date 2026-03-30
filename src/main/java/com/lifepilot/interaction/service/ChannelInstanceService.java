package com.lifepilot.interaction.service;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.repository.ChannelInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 渠道实例服务。
 *
 * <p>统一封装渠道实例的校验、读写和状态流转，底层已切换为 SQLite 持久化仓储。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public class ChannelInstanceService {

    private static final Logger log = LoggerFactory.getLogger(ChannelInstanceService.class);

    private final ChannelRegistry channelRegistry;
    private final ChannelInstanceRepository channelInstanceRepository;

    public ChannelInstanceService(ChannelRegistry channelRegistry,
                                  ChannelInstanceRepository channelInstanceRepository) {
        this.channelRegistry = channelRegistry;
        this.channelInstanceRepository = channelInstanceRepository;
    }

    public ChannelInstance save(ChannelInstance instance) {
        validatePluginBinding(instance);
        channelInstanceRepository.save(instance);
        log.info("渠道实例已保存: instanceId={}, pluginId={}, enabled={}, status={}",
                instance.instanceId(), instance.pluginId(), instance.enabled(), instance.status());
        return instance;
    }

    public ChannelInstance ensurePresent(ChannelInstance instance) {
        validatePluginBinding(instance);
        return find(instance.instanceId()).orElseGet(() -> save(instance));
    }

    public boolean delete(String instanceId) {
        boolean removed = channelInstanceRepository.deleteById(instanceId);
        if (removed) {
            log.info("渠道实例已删除: instanceId={}", instanceId);
            return true;
        }
        return false;
    }

    public Optional<ChannelInstance> find(String instanceId) {
        return channelInstanceRepository.findById(instanceId);
    }

    public List<ChannelInstance> listAll() {
        return channelInstanceRepository.findAll();
    }

    public List<ChannelInstance> listByPlugin(String pluginId) {
        return channelInstanceRepository.findByPluginId(pluginId);
    }

    public ChannelInstance updateStatus(String instanceId,
                                        ChannelInstanceStatus status,
                                        @Nullable String lastError,
                                        @Nullable Instant lastHeartbeatAt) {
        ChannelInstance updated = find(instanceId)
                .map(instance -> instance.withStatus(status, lastError, lastHeartbeatAt))
                .orElseThrow(() -> new IllegalArgumentException("渠道实例不存在: " + instanceId));
        channelInstanceRepository.save(updated);
        return updated;
    }

    public ChannelInstance updateEnabled(String instanceId, boolean enabled) {
        ChannelInstance updated = find(instanceId)
                .map(instance -> instance.withEnabled(enabled))
                .orElseThrow(() -> new IllegalArgumentException("渠道实例不存在: " + instanceId));
        channelInstanceRepository.save(updated);
        return updated;
    }

    public ChannelInstance update(ChannelInstance instance) {
        validatePluginBinding(instance);
        if (find(instance.instanceId()).isEmpty()) {
            throw new IllegalArgumentException("渠道实例不存在: " + instance.instanceId());
        }
        channelInstanceRepository.save(instance);
        log.info("渠道实例已更新: instanceId={}, enabled={}, status={}",
                instance.instanceId(), instance.enabled(), instance.status());
        return instance;
    }

    private void validatePluginBinding(ChannelInstance instance) {
        var descriptor = channelRegistry.find(instance.pluginId())
                .orElseThrow(() -> new IllegalArgumentException("未注册的渠道插件: " + instance.pluginId()));
        if (!descriptor.platform().equalsIgnoreCase(instance.platform())) {
            throw new IllegalArgumentException(
                    "渠道实例平台与插件平台不一致: instanceId=%s, pluginId=%s"
                            .formatted(instance.instanceId(), instance.pluginId()));
        }
    }

}
