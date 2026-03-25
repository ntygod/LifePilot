package com.lifepilot.permission.service;

import com.lifepilot.permission.model.ExecutionGrantScope;
import org.springframework.lang.Nullable;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 授权作用域匹配器。
 *
 * @author zsg
 * @since 2026-03-25
 */
final class PermissionScopeMatcher {

    private static final List<String> PATH_KEYS = List.of("path", "workspacePath", "repoPath");
    private static final List<String> EXACT_IGNORE_CASE_KEYS = List.of("host", "origin", "collection", "taskId", "integrationId");

    private PermissionScopeMatcher() {
    }

    static boolean matches(ExecutionGrantScope grantScope, ExecutionGrantScope requestScope) {
        if (grantScope == null || grantScope.isEmpty()) {
            return true;
        }
        Map<String, Object> grantValues = grantScope.values();
        for (Map.Entry<String, Object> entry : grantValues.entrySet()) {
            Object requestValue = requestScope != null ? requestScope.get(entry.getKey()) : null;
            if (!matchesValue(entry.getKey(), entry.getValue(), requestValue)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesValue(String key, Object grantValue, @Nullable Object requestValue) {
        if (grantValue instanceof List<?> grantList) {
            return grantList.stream().anyMatch(candidate -> matchesValue(key, candidate, requestValue));
        }
        if (grantValue == null) {
            return true;
        }
        if (requestValue == null) {
            return false;
        }
        String grantText = String.valueOf(grantValue).trim();
        String requestText = String.valueOf(requestValue).trim();
        if (grantText.isEmpty()) {
            return true;
        }
        if (PATH_KEYS.contains(key)) {
            return pathStartsWith(requestText, grantText);
        }
        if ("origin".equals(key)) {
            return normalizeOrigin(requestText).equalsIgnoreCase(normalizeOrigin(grantText));
        }
        if (EXACT_IGNORE_CASE_KEYS.contains(key)) {
            return requestText.equalsIgnoreCase(grantText);
        }
        return requestText.equals(grantText);
    }

    private static boolean pathStartsWith(String requestPath, String grantPath) {
        try {
            String normalizedRequest = Path.of(requestPath).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            String normalizedGrant = Path.of(grantPath).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            return normalizedRequest.startsWith(normalizedGrant);
        } catch (Exception ignored) {
            return requestPath.equalsIgnoreCase(grantPath)
                    || requestPath.toLowerCase(Locale.ROOT).startsWith(grantPath.toLowerCase(Locale.ROOT));
        }
    }

    private static String normalizeOrigin(String value) {
        try {
            URI uri = URI.create(value);
            int port = uri.getPort();
            return port > 0
                    ? "%s://%s:%d".formatted(uri.getScheme(), uri.getHost(), port)
                    : "%s://%s".formatted(uri.getScheme(), uri.getHost());
        } catch (Exception ignored) {
            return value.trim();
        }
    }
}
