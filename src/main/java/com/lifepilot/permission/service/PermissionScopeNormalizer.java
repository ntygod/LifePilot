package com.lifepilot.permission.service;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 权限作用域正规化工具。
 *
 * @author zsg
 * @since 2026-03-26
 */
final class PermissionScopeNormalizer {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private PermissionScopeNormalizer() {
    }

    static String normalizeOrigin(String value) {
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

    static String normalizePath(String value) {
        try {
            return Path.of(value).toAbsolutePath().normalize().toString().replace("\\", "/").trim();
        } catch (Exception ignored) {
            return value.replace("\\", "/").trim();
        }
    }

    static boolean pathStartsWith(String requestPath, String grantPath) {
        String normalizedRequest = normalizePath(requestPath);
        String normalizedGrant = normalizePath(grantPath);
        String comparableRequest = WINDOWS ? normalizedRequest.toLowerCase(Locale.ROOT) : normalizedRequest;
        String comparableGrant = WINDOWS ? normalizedGrant.toLowerCase(Locale.ROOT) : normalizedGrant;
        try {
            return Path.of(comparableRequest).startsWith(Path.of(comparableGrant));
        } catch (Exception ignored) {
            return comparableRequest.equals(comparableGrant)
                    || comparableRequest.startsWith(comparableGrant + "/");
        }
    }
}
