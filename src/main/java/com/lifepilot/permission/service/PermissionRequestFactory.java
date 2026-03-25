package com.lifepilot.permission.service;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.config.ToolConfigProperties;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 权限请求构造器。
 *
 * <p>负责从工具调用中提取风险、动作类型和资源作用域，生成权限系统使用的标准请求对象。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
@Service
public class PermissionRequestFactory {

    private final ObservabilityProperties observabilityProperties;
    private final ToolConfigProperties toolConfigProperties;

    public PermissionRequestFactory(ObservabilityProperties observabilityProperties,
                                    ToolConfigProperties toolConfigProperties) {
        this.observabilityProperties = observabilityProperties;
        this.toolConfigProperties = toolConfigProperties;
    }

    public PermissionRequest create(ToolContract tool, ToolInput input, String fallbackTraceId) {
        PermissionActionType actionType = resolveActionType(tool.id());
        ExecutionGrantScope resourceScope = resolveResourceScope(tool.id(), actionType, input);
        String channel = input.getContextValue(ToolContextKeys.CHANNEL_TYPE, String.class).orElse("unknown");
        String sessionId = input.getContextValue(ToolContextKeys.SESSION_ID, String.class).orElse(null);
        String userId = input.getContextValue(ToolContextKeys.USER_ID, String.class).orElse(null);
        String turnId = input.getContextValue(ToolContextKeys.TURN_ID, String.class).orElse(null);
        String traceId = input.getContextValue(ToolContextKeys.CALLER_TRACE_ID, String.class)
                .orElse(fallbackTraceId);
        String taskId = input.getContextValue(ToolContextKeys.TASK_ID, String.class)
                .orElseGet(() -> resolveTaskId(channel, sessionId));
        String workspaceId = input.getContextValue(ToolContextKeys.WORKSPACE_ID, String.class)
                .orElseGet(() -> resolveWorkspaceId(resourceScope));

        return new PermissionRequest(
                tool.id(),
                actionType,
                resolveRiskLevel(tool, input),
                channel,
                resourceScope,
                sessionId,
                workspaceId,
                taskId,
                userId,
                turnId,
                traceId
        );
    }

    private PermissionActionType resolveActionType(String toolId) {
        if (toolId.startsWith("builtin.file.")) {
            return switch (toolId) {
                case "builtin.file.write", "builtin.file.patch", "builtin.file.copy", "builtin.file.move"
                        -> PermissionActionType.WRITE_FILE;
                case "builtin.file.delete" -> PermissionActionType.DELETE_FILE;
                default -> PermissionActionType.READ_FILE;
            };
        }
        if ("builtin.shell.exec".equals(toolId) || "builtin.code.execute".equals(toolId)) {
            return PermissionActionType.EXECUTE_SHELL;
        }
        if (toolId.startsWith("builtin.browser.")) {
            return PermissionActionType.BROWSER_AUTOMATION;
        }
        if ("builtin.http.request".equals(toolId) || "builtin.web.fetch".equals(toolId)
                || "builtin.web.search".equals(toolId)) {
            return PermissionActionType.HTTP_REQUEST;
        }
        if (toolId.startsWith("builtin.memory.")
                && !toolId.startsWith("builtin.memory.search")
                && !toolId.startsWith("builtin.memory.recall")
                && !"builtin.memory.query-at-time".equals(toolId)) {
            return PermissionActionType.WRITE_MEMORY;
        }
        if (toolId.startsWith("builtin.datastore.")) {
            return PermissionActionType.MODIFY_DATASTORE;
        }
        if (toolId.startsWith("builtin.cron.")) {
            return PermissionActionType.CREATE_SCHEDULE;
        }
        return PermissionActionType.GENERIC_TOOL_OPERATION;
    }

    private ExecutionGrantScope resolveResourceScope(String toolId,
                                                     PermissionActionType actionType,
                                                     ToolInput input) {
        return switch (actionType) {
            case READ_FILE, WRITE_FILE, DELETE_FILE -> resolveFileScope(input);
            case EXECUTE_SHELL -> resolveShellScope(input);
            case BROWSER_AUTOMATION -> resolveBrowserScope(input);
            case HTTP_REQUEST -> resolveHttpScope(input);
            case MODIFY_DATASTORE -> resolveDatastoreScope(input);
            case CREATE_SCHEDULE -> resolveScheduleScope(toolId, input);
            case WRITE_MEMORY, GENERIC_TOOL_OPERATION -> ExecutionGrantScope.EMPTY;
        };
    }

    private ExecutionGrantScope resolveFileScope(ToolInput input) {
        String path = firstNonBlankParam(input,
                "path", "targetPath", "sourcePath", "fromPath", "toPath", "filePath");
        if (path == null) {
            return ExecutionGrantScope.EMPTY;
        }
        String normalizedPath = normalizePath(path);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("path", normalizedPath);
        String workspacePath = resolveWorkspacePath(normalizedPath);
        if (workspacePath != null) {
            values.put("workspacePath", workspacePath);
        }
        return ExecutionGrantScope.of(values);
    }

    private ExecutionGrantScope resolveShellScope(ToolInput input) {
        String workspacePath = firstNonBlankParam(input, "workingDirectory", "cwd");
        if (workspacePath == null) {
            return ExecutionGrantScope.EMPTY;
        }
        return ExecutionGrantScope.of(Map.of("workspacePath", normalizePath(workspacePath)));
    }

    private ExecutionGrantScope resolveBrowserScope(ToolInput input) {
        return resolveOriginScope(firstNonBlankParam(input, "url", "origin"));
    }

    private ExecutionGrantScope resolveHttpScope(ToolInput input) {
        return resolveOriginScope(firstNonBlankParam(input, "url", "origin", "endpoint"));
    }

    private ExecutionGrantScope resolveDatastoreScope(ToolInput input) {
        String collection = firstNonBlankParam(input, "collection", "collectionName", "name");
        if (collection == null) {
            return ExecutionGrantScope.EMPTY;
        }
        return ExecutionGrantScope.of(Map.of("collection", collection));
    }

    private ExecutionGrantScope resolveScheduleScope(String toolId, ToolInput input) {
        String taskId = firstNonBlankParam(input, "id", "taskId");
        if (taskId == null && "builtin.cron.create".equals(toolId)) {
            taskId = firstNonBlankParam(input, "name");
        }
        return taskId != null
                ? ExecutionGrantScope.of(Map.of("taskId", taskId))
                : ExecutionGrantScope.EMPTY;
    }

    private ExecutionGrantScope resolveOriginScope(String urlOrOrigin) {
        if (urlOrOrigin == null) {
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

    private RiskLevel resolveRiskLevel(ToolContract tool, ToolInput input) {
        RiskLevel baseRiskLevel = resolveConfiguredRiskLevel(tool);
        if (baseRiskLevel == null) {
            String defaultRisk = observabilityProperties.getGuardrail().getToolRisk().getDefaultRiskLevel();
            baseRiskLevel = tool.riskLevel() != null ? tool.riskLevel()
                    : RiskLevel.valueOf(defaultRisk.toUpperCase(Locale.ROOT));
        }
        return applyTrustedWorkspaceDowngrade(tool.id(), baseRiskLevel, input);
    }

    private RiskLevel resolveConfiguredRiskLevel(ToolContract tool) {
        String configured = observabilityProperties.getGuardrail()
                .getToolRisk().getToolRiskMapping().get(tool.id());
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return RiskLevel.valueOf(configured.toUpperCase(Locale.ROOT));
    }

    private RiskLevel applyTrustedWorkspaceDowngrade(String toolId, RiskLevel originalLevel, ToolInput input) {
        if (!"builtin.shell.exec".equals(toolId) && !"builtin.code.execute".equals(toolId)) {
            return originalLevel;
        }
        String execPath = firstNonBlankParam(input, "workingDirectory", "cwd");
        if (execPath == null || toolConfigProperties.getTrustedWorkspace().getPaths().isEmpty()) {
            return originalLevel;
        }
        String normalizedExecPath = normalizePath(execPath);
        boolean trusted = toolConfigProperties.getTrustedWorkspace().getPaths().stream()
                .map(this::normalizePath)
                .anyMatch(normalizedExecPath::startsWith);
        if (!trusted) {
            return originalLevel;
        }
        RiskLevel downgraded = RiskLevel.valueOf(
                toolConfigProperties.getTrustedWorkspace().getDowngradeLevel().toUpperCase(Locale.ROOT));
        return downgraded.ordinal() < originalLevel.ordinal() ? downgraded : originalLevel;
    }

    private String resolveTaskId(String channel, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (channel != null && (channel.startsWith("cron") || channel.startsWith("heartbeat") || channel.startsWith("workflow"))) {
            int separator = sessionId.indexOf(':');
            return separator >= 0 && separator + 1 < sessionId.length()
                    ? sessionId.substring(separator + 1)
                    : sessionId;
        }
        return null;
    }

    private String resolveWorkspaceId(ExecutionGrantScope scope) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        return Optional.ofNullable(scope.get("workspacePath"))
                .or(() -> Optional.ofNullable(scope.get("path")))
                .map(String::valueOf)
                .orElse(null);
    }

    private String firstNonBlankParam(ToolInput input, String... names) {
        for (String name : names) {
            String value = input.getOptionalParam(name, String.class).orElse(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizePath(String rawPath) {
        try {
            return Path.of(rawPath).toAbsolutePath().normalize().toString().replace("\\", "/");
        } catch (Exception ignored) {
            return rawPath.replace("\\", "/").trim();
        }
    }

    private String resolveWorkspacePath(String normalizedPath) {
        try {
            Path path = Path.of(normalizedPath);
            String fileName = path.getFileName() != null ? path.getFileName().toString() : "";
            if (fileName.contains(".") && path.getParent() != null) {
                return path.getParent().toString().replace("\\", "/");
            }
            return path.toString().replace("\\", "/");
        } catch (Exception ignored) {
            int slash = normalizedPath.lastIndexOf('/');
            return slash > 0 ? normalizedPath.substring(0, slash) : normalizedPath;
        }
    }

    private String normalizeOrigin(String rawValue) {
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

    private String extractHost(String rawValue) {
        try {
            URI uri = URI.create(rawValue);
            return uri.getHost();
        } catch (Exception ignored) {
            return null;
        }
    }
}
