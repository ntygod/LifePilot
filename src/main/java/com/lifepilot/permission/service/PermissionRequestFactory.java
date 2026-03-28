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
        ToolExecutionSemantics semantics = tool.executionSemantics();
        PermissionActionType actionType = semantics.actionType();
        ToolScopeResolution scopeResolution = semantics.scopeResolver().resolve(input);
        ExecutionGrantScope resourceScope = scopeResolution.scope();
        String channel = input.getContextValue(ToolContextKeys.CHANNEL_TYPE, String.class).orElse("unknown");
        String sessionId = input.getContextValue(ToolContextKeys.SESSION_ID, String.class).orElse(null);
        String userId = input.getContextValue(ToolContextKeys.USER_ID, String.class).orElse(null);
        String turnId = input.getContextValue(ToolContextKeys.TURN_ID, String.class).orElse(null);
        String traceId = input.getContextValue(ToolContextKeys.CALLER_TRACE_ID, String.class)
                .orElse(fallbackTraceId);
        String taskId = input.getContextValue(ToolContextKeys.TASK_ID, String.class)
                .orElseGet(() -> resolveTaskId(channel, sessionId));
        String workspaceId = input.getContextValue(ToolContextKeys.WORKSPACE_ID, String.class)
                .orElse(scopeResolution.workspaceId());
        boolean autonomousTaskGrantRequired = actionType == PermissionActionType.CREATE_SCHEDULE
                && autonomousTaskApprovalAdvisor.requiresApproval(firstNonBlankParam(input, "instruction"));

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
                traceId,
                autonomousTaskGrantRequired
        );
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
        if (!"shell.exec".equals(toolId) && !"code.execute".equals(toolId)) {
            return originalLevel;
        }
        String execPath = firstNonBlankParam(input, "workingDirectory", "cwd");
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
