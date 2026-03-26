package com.lifepilot.permission.service;

import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.model.ToolInput;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 权限作用域归一化器。
 *
 * <p>统一定义“任务预授权”和“运行时工具调用”两条链路的作用域语义，
 * 避免同一动作类型在不同阶段使用不一致的 scope 规则。</p>
 *
 * @author zsg
 * @since 2026-03-26
 */
@Component
public class PermissionScopeResolver {

    public ExecutionGrantScope resolveRuntimeScope(PermissionActionType actionType, ToolInput input) {
        return switch (actionType) {
            case READ_FILE, WRITE_FILE, DELETE_FILE -> fileRuntimeScope(firstNonBlankParam(input,
                    "path", "targetPath", "sourcePath", "fromPath", "toPath", "filePath"));
            case EXECUTE_SHELL -> shellRuntimeScope(firstNonBlankParam(input, "workingDirectory", "cwd"));
            case BROWSER_AUTOMATION -> originScope(firstNonBlankParam(input, "url", "origin"));
            case HTTP_REQUEST -> originScope(firstNonBlankParam(input, "url", "origin", "endpoint"));
            case MODIFY_DATASTORE -> datastoreScope(firstNonBlankParam(input, "collection", "collectionName", "name"));
            case CREATE_SCHEDULE, WRITE_MEMORY, GENERIC_TOOL_OPERATION -> ExecutionGrantScope.EMPTY;
        };
    }

    @Nullable
    public String resolveWorkspaceId(ExecutionGrantScope scope) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        return Optional.ofNullable(scope.get("workspacePath"))
                .or(() -> Optional.ofNullable(scope.get("path")))
                .map(String::valueOf)
                .orElse(null);
    }

    @Nullable
    public String normalizePath(@Nullable String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        return PermissionScopeNormalizer.normalizePath(rawPath);
    }

    @Nullable
    public String resolveWorkspacePath(@Nullable String normalizedPath) {
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

    private ExecutionGrantScope fileRuntimeScope(@Nullable String rawPath) {
        String normalizedPath = normalizePath(rawPath);
        if (normalizedPath == null) {
            return ExecutionGrantScope.EMPTY;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("path", normalizedPath);
        String workspacePath = resolveWorkspacePath(normalizedPath);
        if (workspacePath != null) {
            values.put("workspacePath", workspacePath);
        }
        return ExecutionGrantScope.of(values);
    }

    private ExecutionGrantScope shellRuntimeScope(@Nullable String workingDirectory) {
        String workspacePath = normalizePath(workingDirectory);
        if (workspacePath == null) {
            return ExecutionGrantScope.EMPTY;
        }
        return ExecutionGrantScope.of(Map.of("workspacePath", workspacePath));
    }

    private ExecutionGrantScope originScope(@Nullable String urlOrOrigin) {
        if (urlOrOrigin == null || urlOrOrigin.isBlank()) {
            return ExecutionGrantScope.EMPTY;
        }
        String origin = normalizeOrigin(urlOrOrigin);
        String host = extractHost(urlOrOrigin);
        Map<String, Object> values = new LinkedHashMap<>();
        if (origin != null) {
            values.put("origin", origin);
        }
        if (host != null) {
            values.put("host", host);
        }
        return values.isEmpty() ? ExecutionGrantScope.EMPTY : ExecutionGrantScope.of(values);
    }

    private ExecutionGrantScope datastoreScope(@Nullable String collection) {
        if (collection == null || collection.isBlank()) {
            return ExecutionGrantScope.EMPTY;
        }
        return ExecutionGrantScope.of(Map.of("collection", collection));
    }

    @Nullable
    private String firstNonBlankParam(ToolInput input, String... names) {
        for (String name : names) {
            String value = input.getOptionalParam(name, String.class).orElse(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Nullable
    private String normalizeOrigin(@Nullable String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(rawValue);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getPort() > 0
                    ? "%s://%s:%d".formatted(uri.getScheme(), uri.getHost(), uri.getPort())
                    : "%s://%s".formatted(uri.getScheme(), uri.getHost());
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private String extractHost(String rawValue) {
        try {
            URI uri = URI.create(rawValue);
            return uri.getHost();
        } catch (Exception ignored) {
            return null;
        }
    }
}
