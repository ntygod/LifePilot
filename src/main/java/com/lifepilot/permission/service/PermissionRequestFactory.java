package com.lifepilot.permission.service;

import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.interaction.model.SourceKind;
import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.config.ToolConfigProperties;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeNormalizer;
import com.lifepilot.tool.semantics.ToolScopeResolution;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
    private final AutonomousTaskApprovalAdvisor autonomousTaskApprovalAdvisor;

    public PermissionRequestFactory(ObservabilityProperties observabilityProperties,
                                    ToolConfigProperties toolConfigProperties,
                                    AutonomousTaskApprovalAdvisor autonomousTaskApprovalAdvisor) {
        this.observabilityProperties = observabilityProperties;
        this.toolConfigProperties = toolConfigProperties;
        this.autonomousTaskApprovalAdvisor = autonomousTaskApprovalAdvisor;
    }

    public PermissionRequest create(ToolContract tool, ToolInput input, String fallbackTraceId) {
        ToolExecutionSemantics semantics = resolveExecutionSemantics(tool, input);
        PermissionActionType actionType = semantics.actionType();
        ToolScopeResolution scopeResolution = semantics.scopeResolver().resolve(input);
        ExecutionGrantScope resourceScope = scopeResolution.scope();
        String sessionId = input.getContextValue(ToolContextKeys.SESSION_ID, String.class).orElse(null);
        String rawChannel = input.getContextValue(ToolContextKeys.CHANNEL_TYPE, String.class).orElse(null);
        InteractionSource legacySource = InteractionSource.legacy(rawChannel, sessionId);
        SourceKind sourceKind = input.getContextValue(ToolContextKeys.SOURCE_KIND, String.class)
                .map(this::resolveSourceKind)
                .orElse(legacySource.sourceKind());
        String sourceId = input.getContextValue(ToolContextKeys.SOURCE_ID, String.class)
                .orElse(legacySource.sourceId());
        String channelPlatform = input.getContextValue(ToolContextKeys.CHANNEL_PLATFORM, String.class)
                .orElse(legacySource.channelPlatform());
        String channelInstanceId = input.getContextValue(ToolContextKeys.CHANNEL_INSTANCE_ID, String.class)
                .orElse(legacySource.channelInstanceId());
        String channel = channelInstanceId != null && !channelInstanceId.isBlank()
                ? channelInstanceId
                : (channelPlatform != null && !channelPlatform.isBlank()
                ? channelPlatform
                : (rawChannel != null && !rawChannel.isBlank() ? rawChannel : sourceId));
        String userId = input.getContextValue(ToolContextKeys.USER_ID, String.class).orElse(null);
        String turnId = input.getContextValue(ToolContextKeys.TURN_ID, String.class).orElse(null);
        String traceId = input.getContextValue(ToolContextKeys.CALLER_TRACE_ID, String.class)
                .orElse(fallbackTraceId);
        String taskId = input.getContextValue(ToolContextKeys.TASK_ID, String.class)
                .orElseGet(() -> resolveTaskId(sourceKind, sourceId, sessionId));
        String workspaceId = input.getContextValue(ToolContextKeys.WORKSPACE_ID, String.class)
                .orElse(scopeResolution.workspaceId());
        boolean autonomousTaskGrantRequired = actionType == PermissionActionType.CREATE_SCHEDULE
                && autonomousTaskApprovalAdvisor.requiresApproval(firstNonBlankParam(input, "instruction"));

        return new PermissionRequest(
                tool.id(),
                actionType,
                resolveRiskLevel(tool, input),
                channel,
                sourceKind,
                sourceId,
                channelPlatform,
                channelInstanceId,
                resourceScope,
                sessionId,
                workspaceId,
                taskId,
                userId,
                turnId,
                traceId,
                autonomousTaskGrantRequired
        );
    }

    private RiskLevel resolveRiskLevel(ToolContract tool, ToolInput input) {
        RiskLevel baseRiskLevel = resolveConfiguredRiskLevel(tool);
        if (baseRiskLevel == null) {
            String defaultRisk = observabilityProperties.getGuardrail().getToolRisk().getDefaultRiskLevel();
            baseRiskLevel = resolveDeclaredRiskLevel(tool, input);
            if (baseRiskLevel == null) {
                baseRiskLevel = RiskLevel.valueOf(defaultRisk.toUpperCase(Locale.ROOT));
            }
        }
        return applyTrustedWorkspaceDowngrade(tool.id(), baseRiskLevel, input);
    }

    private ToolExecutionSemantics resolveExecutionSemantics(ToolContract tool, ToolInput input) {
        if (tool instanceof com.lifepilot.tool.BuiltinTool builtinTool) {
            return builtinTool.resolveExecutionSemantics(input);
        }
        return tool.executionSemantics();
    }

    private RiskLevel resolveDeclaredRiskLevel(ToolContract tool, ToolInput input) {
        if (tool instanceof com.lifepilot.tool.BuiltinTool builtinTool) {
            return builtinTool.resolveRiskLevel(input);
        }
        return tool.riskLevel();
    }

    private SourceKind resolveSourceKind(String raw) {
        try {
            return SourceKind.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SourceKind.SYSTEM;
        }
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
        if (!"shell.exec".equals(toolId) && !"shell".equals(toolId) && !"code.execute".equals(toolId)) {
            return originalLevel;
        }
        String execPath = firstNonBlankParam(input, "workingDirectory", "workDir", "cwd");
        if (execPath == null || toolConfigProperties.getTrustedWorkspace().getPaths().isEmpty()) {
            return originalLevel;
        }
        String normalizedExecPath = ToolScopeNormalizer.normalizePath(execPath);
        boolean trusted = toolConfigProperties.getTrustedWorkspace().getPaths().stream()
                .map(ToolScopeNormalizer::normalizePath)
                .filter(java.util.Objects::nonNull)
                .anyMatch(normalizedExecPath::startsWith);
        if (!trusted) {
            return originalLevel;
        }
        RiskLevel downgraded = RiskLevel.valueOf(
                toolConfigProperties.getTrustedWorkspace().getDowngradeLevel().toUpperCase(Locale.ROOT));
        return downgraded.ordinal() < originalLevel.ordinal() ? downgraded : originalLevel;
    }

    private String resolveTaskId(SourceKind sourceKind, String sourceId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (sourceKind == SourceKind.CRON
                || sourceKind == SourceKind.HEARTBEAT
                || sourceKind == SourceKind.WORKFLOW
                || sourceId.startsWith("cron")
                || sourceId.startsWith("heartbeat")
                || sourceId.startsWith("workflow")) {
            int separator = sessionId.indexOf(':');
            return separator >= 0 && separator + 1 < sessionId.length()
                    ? sessionId.substring(separator + 1)
                    : sessionId;
        }
        return null;
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

}
