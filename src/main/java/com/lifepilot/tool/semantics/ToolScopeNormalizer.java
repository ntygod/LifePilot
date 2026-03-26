package com.lifepilot.tool.semantics;

import org.springframework.lang.Nullable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 工具资源正规化工具。
 *
 * <p>统一处理路径、Origin 与工作区路径的标准化，避免权限与调度各自实现一套规则。</p>
 *
 * @author zsg
 * @since 2026-03-26
 */
public final class ToolScopeNormalizer {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    private ToolScopeNormalizer() {
    }

    @Nullable
    public static String normalizeOrigin(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return value.trim();
            }
            int port = uri.getPort();
            return port > 0
                    ? "%s://%s:%d".formatted(uri.getScheme(), uri.getHost(), port)
                    : "%s://%s".formatted(uri.getScheme(), uri.getHost());
        } catch (Exception ignored) {
            return value.trim();
        }
    }

    @Nullable
    public static String extractHost(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            return uri.getHost();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    public static String normalizePath(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
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

    public static boolean pathStartsWith(String requestPath, String grantPath) {
        String normalizedRequest = normalizePath(requestPath);
        String normalizedGrant = normalizePath(grantPath);
        if (normalizedRequest == null || normalizedGrant == null) {
            return false;
        }
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

    @Nullable
    public static String resolveWorkspacePath(@Nullable String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(normalizedPath);
            if (Files.exists(path)) {
                if (Files.isRegularFile(path) && path.getParent() != null) {
                    return path.getParent().toString().replace("\\", "/");
                }
                return path.toString().replace("\\", "/");
            }
            String fileName = path.getFileName() != null ? path.getFileName().toString() : "";
            boolean looksLikeFile = fileName.contains(".") && !fileName.startsWith(".");
            if (looksLikeFile && path.getParent() != null) {
                return path.getParent().toString().replace("\\", "/");
            }
            return path.toString().replace("\\", "/");
        } catch (Exception ignored) {
            int slash = normalizedPath.lastIndexOf('/');
            return slash > 0 ? normalizedPath.substring(0, slash) : normalizedPath;
        }
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
