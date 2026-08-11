package com.lifepilot.modelservice.service;

import com.lifepilot.llm.config.ProviderCapability;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型服务运行时健康快照。
 *
 * <p>只记录启动预热等已有探测结果；诊断读取本快照时不会触发新的模型请求。</p>
 *
 * @author zsg
 * @since 2026-07-05
 */
public class ModelServiceRuntimeHealth {

    private final Map<String, ProviderHealth> providers = new ConcurrentHashMap<>();

    public void record(String providerId,
                       Set<ProviderCapability> capabilities,
                       HealthState state,
                       @Nullable String detail) {
        providers.put(providerId, new ProviderHealth(
                providerId,
                capabilities.stream().map(Enum::name).sorted().toList(),
                state,
                detail,
                Instant.now()
        ));
    }

    public List<ProviderHealth> snapshot() {
        return providers.values().stream()
                .sorted(java.util.Comparator.comparing(ProviderHealth::providerId))
                .toList();
    }

    public enum HealthState {
        HEALTHY,
        UNHEALTHY,
        SKIPPED
    }

    public record ProviderHealth(
            String providerId,
            List<String> capabilities,
            HealthState state,
            @Nullable String detail,
            Instant checkedAt
    ) {
        public boolean hasCapability(ProviderCapability capability) {
            return capabilities.contains(capability.name());
        }
    }
}
