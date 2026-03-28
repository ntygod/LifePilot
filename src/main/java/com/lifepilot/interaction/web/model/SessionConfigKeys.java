package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 会话级聊天配置的共享键和辅助方法。
 *
 * @author zsg
 * @since 2026-03-10
 */
public final class SessionConfigKeys {

    public static final String PREFERRED_PROVIDER = "preferredProvider";
    public static final String LEGACY_MODEL_ID = "modelId";
    public static final String TEMPERATURE = "temperature";
    public static final String MAX_STEPS = "maxSteps";
    public static final String MAX_DURATION_SECONDS = "maxDurationSeconds";

    private SessionConfigKeys() {
    }

    @Nullable
    public static String normalizeString(@Nullable String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    @Nullable
    public static String getString(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof String stringValue) {
            return normalizeString(stringValue);
        }
        return null;
    }

    @Nullable
    public static Integer getInteger(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof Number numberValue) {
            return numberValue.intValue();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    public static Double getDouble(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value instanceof Number numberValue) {
            return numberValue.doubleValue();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    public static String resolvePreferredProviderId(Map<String, Object> config) {
        String preferredProviderId = getString(config, PREFERRED_PROVIDER);
        if (preferredProviderId != null) {
            return preferredProviderId;
        }

        return getString(config, LEGACY_MODEL_ID);
    }
}
