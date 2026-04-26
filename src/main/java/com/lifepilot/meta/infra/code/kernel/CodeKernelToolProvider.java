package com.lifepilot.meta.infra.code.kernel;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.dispatch.ActionMetadata;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码内核工具提供者 — 单工具多 action（list/reset/inspect）。
 *
 * <p>统一为 {@code code.kernel} 工具，{@code action} 参数路由：
 * <ul>
 *   <li>{@code list} — 列出所有活跃内核（无需 kernelId，幂等）</li>
 *   <li>{@code reset} — 清空指定内核的变量与导入</li>
 *   <li>{@code inspect} — 查看指定内核的变量、状态</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class CodeKernelToolProvider {

    private static final Logger log = LoggerFactory.getLogger(CodeKernelToolProvider.class);

    private final PersistentKernelManager kernelManager;

    public CodeKernelToolProvider(PersistentKernelManager kernelManager) {
        this.kernelManager = kernelManager;
    }

    /**
     * 构建代码内核管理工具列表（仅 1 个：{@code code.kernel}）。
     */
    public List<BuiltinTool> buildKernelTools() {
        return List.of(buildKernelTool());
    }

    private BuiltinTool buildKernelTool() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.of("list", "reset", "inspect"),
                "description", "操作类型：list=列出活跃内核（无需 kernelId）；reset=清空内核变量与导入；inspect=查看内核变量与状态。"));
        properties.put("kernelId", Map.of(
                "type", "string",
                "description", "持久内核 ID；reset / inspect 必填，list 忽略。"));

        var listSemantics = ToolExecutionSemantics.of(
                com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                ToolSchedulingMode.PARALLEL_SAFE,
                ToolScopeResolvers.none());
        var mutateSemantics = ToolExecutionSemantics.of(
                com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                ToolSchedulingMode.SEQUENTIAL,
                ToolScopeResolvers.exactValues("kernelIds", "kernelId"));
        var inspectSemantics = ToolExecutionSemantics.of(
                com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                ToolSchedulingMode.PARALLEL_SAFE,
                ToolScopeResolvers.exactValues("kernelIds", "kernelId"));

        var actionMetadata = new LinkedHashMap<String, ActionMetadata>();
        actionMetadata.put("list", new ActionMetadata(RiskLevel.LOW, listSemantics));
        actionMetadata.put("reset", new ActionMetadata(RiskLevel.LOW, mutateSemantics));
        actionMetadata.put("inspect", new ActionMetadata(RiskLevel.LOW, inspectSemantics));

        return BuiltinTool.builder()
                .id("code.kernel")
                .category(ToolCategory.ACTION)
                .name("代码内核管理")
                .description("管理代码内核会话：list 列出活跃内核、reset 重置内核变量与已导入模块、inspect 查看内核变量与执行状态。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", properties
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("kernelIds", "kernelId")
                ))
                .tags(List.of("内核", "代码", "管理", "列表", "重置", "变量", "状态", "调试", "kernel", "code"))
                .actionMetadata(actionMetadata)
                .executor(this::dispatch)
                .build();
    }

    private ToolResult dispatch(ToolInput input) {
        String action;
        try {
            action = input.getParam("action", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数 action：" + e.getMessage());
        }
        return switch (action) {
            case "list" -> executeList(input);
            case "reset" -> executeReset(input);
            case "inspect" -> executeInspect(input);
            default -> ToolResult.error("不支持的 action: " + action + "（允许：list / reset / inspect）");
        };
    }

    private ToolResult executeList(ToolInput input) {
        try {
            var kernelList = kernelManager.listKernels();
            var kernelMaps = kernelList.stream()
                    .map(info -> Map.<String, Object>of(
                            "kernelId", info.kernelId(),
                            "state", info.state(),
                            "idleSeconds", info.idleSeconds()
                    ))
                    .toList();
            return ToolResult.success(Map.of(
                    "kernels", kernelMaps,
                    "total", kernelMaps.size()
            ));
        } catch (Exception e) {
            log.error("列出内核失败: error={}", e.getMessage(), e);
            return ToolResult.error("列出内核失败: " + e.getMessage());
        }
    }

    private ToolResult executeReset(ToolInput input) {
        try {
            String kernelId = input.getParam("kernelId", String.class);
            kernelManager.resetKernel(kernelId);
            return ToolResult.success(Map.of(
                    "kernelId", kernelId,
                    "message", "内核已重置，所有变量和导入已清空"
            ));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("重置内核失败: error={}", e.getMessage(), e);
            return ToolResult.error("重置内核失败: " + e.getMessage());
        }
    }

    private ToolResult executeInspect(ToolInput input) {
        try {
            String kernelId = input.getParam("kernelId", String.class);
            Map<String, String> variables = kernelManager.inspectKernel(kernelId);
            return ToolResult.success(Map.of(
                    "kernelId", kernelId,
                    "variables", variables
            ));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("检查内核失败: error={}", e.getMessage(), e);
            return ToolResult.error("检查内核失败: " + e.getMessage());
        }
    }
}
