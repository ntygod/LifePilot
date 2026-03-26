package com.lifepilot.permission.service;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 权限作用域正规化工具。
 *
 * @author zsg
 * @since 2026-03-26
 */
final class PermissionScopeNormalizer {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:[\\\\/].*");

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
        String sanitized = value.replace("\\", "/").trim();
        if (WINDOWS_ABSOLUTE_PATH.matcher(sanitized).matches()) {
            return normalizeSegmentPath(sanitized.substring(0, 3), sanitized.substring(3));
        }
        try {
            Path path = Path.of(value);
            Path normalizedPath = path.isAbsolute() ? path.normalize() : path.toAbsolutePath().normalize();
            return normalizedPath.toString().replace("\\", "/").trim();
        } catch (Exception ignored) {
            return sanitized;
        }
    }

    static boolean pathStartsWith(String requestPath, String grantPath) {
        String normalizedRequest = normalizePath(requestPath);
        String normalizedGrant = normalizePath(grantPath);
        boolean ignoreCase = WINDOWS
                || WINDOWS_ABSOLUTE_PATH.matcher(normalizedRequest).matches()
                || WINDOWS_ABSOLUTE_PATH.matcher(normalizedGrant).matches();
        String comparableRequest = ignoreCase ? normalizedRequest.toLowerCase(Locale.ROOT) : normalizedRequest;
        String comparableGrant = ignoreCase ? normalizedGrant.toLowerCase(Locale.ROOT) : normalizedGrant;
        String requestWithoutTrailingSlash = stripTrailingSlash(comparableRequest);
        String grantWithoutTrailingSlash = stripTrailingSlash(comparableGrant);
        if (requestWithoutTrailingSlash.equals(grantWithoutTrailingSlash)) {
            return true;
        }
        if (comparableGrant.endsWith("/")) {
            return comparableRequest.startsWith(comparableGrant);
        }
        return comparableRequest.startsWith(grantWithoutTrailingSlash + "/");
    }

    private static String normalizeSegmentPath(String root, String rawSegments) {
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : rawSegments.split("/+")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
                continue;
            }
            segments.addLast(segment);
        }
        return segments.isEmpty() ? root : root + String.join("/", segments);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if ("/".equals(value) || WINDOWS_ABSOLUTE_PATH.matcher(value).matches() && value.length() == 3) {
            return value;
        }
        int end = value.length();
        while (end > 1 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
