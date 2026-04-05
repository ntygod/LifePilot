package com.lifepilot.meta.infra.code.kernel;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 代码内核工具提供者 — 构建 3 个 code.kernel.* 工具。
 *
 * <p>工具列表：
 * <ul>
 *   <li>{@code code.kernel.list} — 列出所有活跃内核（LOW，幂等）</li>
 *   <li>{@code code.kernel.reset} — 重置内核状态（LOW）</li>
 *   <li>{@code code.kernel.inspect} — 检查内核变量（LOW，幂等）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class CodeKernelToolProvider {

    private static final Logger log = LoggerFactory.getLogger(CodeKernelToolProvider.class);
    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final PersistentKernelManager kernelManager;

    public CodeKernelToolProvider(PersistentKernelManager kernelManager) {
        this.kernelManager = kernelManager;
    }

    /**
     * 构建所有代码内核管理工具。
     *
     * @return 3 个内核工具列表
     */
    public List<BuiltinTool> buildKernelTools() {
        return List.of(
                buildListTool(),
                buildResetTool(),
                buildInspectTool()
        );
    }

    /** 构建列出内核工具。 */
    private BuiltinTool buildListTool() {
        return BuiltinTool.builder()
                .id("code.kernel.list")
                .category(ToolCategory.PERCEPTION)
                .name("列出代码内核")
                .description("列出所有活跃的持久代码内核，显示每个内核的 ID、状态和空闲时间")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of()
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.of(
                        com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.none()
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeList)
                .build();
    }

    /** 构建重置内核工具。 */
    private BuiltinTool buildResetTool() {
        return BuiltinTool.builder()
                .id("code.kernel.reset")
                .category(ToolCategory.ACTION)
                .name("重置代码内核")
                .description("重置指定的持久代码内核，清空所有变量和导入状态，内核进程保持运行。用于在新任务开始前清理内核环境")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("kernelId"),
                        "properties", Map.of(
                                "kernelId", Map.of("type", "string",
                                        "description", "持久内核的 kernelId")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.exactValues("kernelIds", "kernelId")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeReset)
                .build();
    }

    /** 构建检查内核工具。 */
    private BuiltinTool buildInspectTool() {
        return BuiltinTool.builder()
                .id("code.kernel.inspect")
                .category(ToolCategory.PERCEPTION)
                .name("检查代码内核")
                .description("检查指定持久代码内核的当前状态，包括已定义的变量名及其类型。用于了解内核中的上下文信息")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("kernelId"),
                        "properties", Map.of(
                                "kernelId", Map.of("type", "string",
                                        "description", "持久内核的 kernelId")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        com.lifepilot.permission.model.PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.exactValues("kernelIds", "kernelId")
                ))
                .tags(INFRA_TAGS)
                .executor(this::executeInspect)
                .build();
    }

    // ─────────────────────────────────────────────
    //  工具执行方法
    // ─────────────────────────────────────────────

    private ToolResult executeList(ToolInput input) {
        try {
            var kernelList = kernelManager.listKernels();
            // 将 KernelInfo record 转为 Map 给 ToolResult
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
