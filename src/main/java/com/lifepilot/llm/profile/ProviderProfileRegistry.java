package com.lifepilot.llm.profile;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 内置 ProviderProfile 注册表。
 *
 * <p>启动时一次性加载 BuiltinProviderProfiles 列表到只读 Map，按 id 查询。
 *
 * @author zsg
 * @since 2026-04-27
 */
@Component
public class ProviderProfileRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderProfileRegistry.class);

    private Map<String, ProviderProfile> profilesById = Map.of();

    @PostConstruct
    public void init() {
        profilesById = BuiltinProviderProfiles.all().stream()
                .collect(Collectors.toUnmodifiableMap(
                        ProviderProfile::id, p -> p));
        log.info("ProviderProfileRegistry 加载完成: {} 个 profile", profilesById.size());
    }

    /**
     * 按 id 获取 profile，未知 id 抛 IllegalStateException。
     *
     * @param id Profile id
     * @return ProviderProfile
     */
    public ProviderProfile get(String id) {
        var profile = profilesById.get(id);
        if (profile == null) {
            throw new IllegalStateException("未知的 ProviderProfile id: " + id);
        }
        return profile;
    }

    /**
     * 列出所有 profile。
     *
     * @return 不可变列表
     */
    public List<ProviderProfile> all() {
        return List.copyOf(profilesById.values());
    }
}
