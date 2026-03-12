package com.lifepilot.interaction.web.service;

import com.lifepilot.interaction.web.repository.UserSettingsRepository;
import com.lifepilot.llm.LlmRoutingPreferenceResolver;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * 基于用户设置解析场景默认 Provider。
 *
 * @author zsg
 * @since 2026-03-10
 */
@Service
public class UserSettingsLlmRoutingPreferenceResolver implements LlmRoutingPreferenceResolver {

    private final UserSettingsRepository userSettingsRepository;

    public UserSettingsLlmRoutingPreferenceResolver(UserSettingsRepository userSettingsRepository) {
        this.userSettingsRepository = userSettingsRepository;
    }

    @Override
    @Nullable
    public String preferredProviderForScene(String scene) {
        var settings = userSettingsRepository.getSettings();
        if (settings.sceneProviders() != null) {
            String sceneProvider = settings.sceneProviders().get(scene);
            if (sceneProvider != null && !sceneProvider.isBlank()) {
                return sceneProvider;
            }
        }
        return settings.llmProvider() != null && !settings.llmProvider().isBlank()
                ? settings.llmProvider()
                : null;
    }
}
