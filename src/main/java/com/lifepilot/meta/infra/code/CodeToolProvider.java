package com.lifepilot.meta.infra.code;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.code.kernel.PersistentKernelManager;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.sandbox.guard.CommandGuard;
import com.lifepilot.sandbox.repository.SandboxRepository;
import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.session.SandboxSessionManager;
import com.lifepilot.sandbox.validator.CodeValidator;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.dispatch.ActionMetadata;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码工具提供者 — 单工具多 action：代码执行 + 内核管理。
 *
 * <p>原 {@code code} + {@code code} 合并为 {@code code} 工具。
 * 支持一次性沙箱和持久内核两种模式。</p>
 *
 * @author zsg
 * @since 2026-04-07
 */
public class CodeToolProvider {

    private static final Logger log = LoggerFactory.getLogger(CodeToolProvider.class);

    private final MetaProperties properties;
    @Nullable
    private final SandboxSessionManager sandboxSessionManager;
    @Nullable
    private final CodeValidator codeValidator;
    @Nullable
    private final SandboxRepository sandboxRepository;
    @Nullable
    private final PersistentKernelManager kernelManager;
    @Nullable
    private final PythonRuntimeManager runtimeManager;
    @Nullable
    private final CommandGuard commandGuard;

    public CodeToolProvider(MetaProperties properties,
                            @Nullable SandboxSessionManager sandboxSessionManager,
                            @Nullable CodeValidator codeValidator,
                            @Nullable SandboxRepository sandboxRepository,
                            @Nullable PersistentKernelManager kernelManager,
                            @Nullable PythonRuntimeManager runtimeManager,
                            @Nullable CommandGuard commandGuard) {
        this.properties = properties;
        this.sandboxSessionManager = sandboxSessionManager;
        this.codeValidator = codeValidator;
        this.sandboxRepository = sandboxRepository;
        this.kernelManager = kernelManager;
        this.runtimeManager = runtimeManager;
        this.commandGuard = commandGuard;
    }

    /** 构建代码工具列表（仅 1 个：{@code code}）。 */
    public List<BuiltinTool> buildCodeTools() {
        var executor = new CodeExecuteToolExecutor(
                properties, sandboxSessionManager, codeValidator, sandboxRepository,
                kernelManager, runtimeManager, commandGuard);
        return List.of(buildCodeTool(executor));
    }

    /** 构建合并后的 code 工具：exec + kernel_list / kernel_inspect / kernel_reset。 */
    private BuiltinTool buildCodeTool(CodeExecuteToolExecutor executor) {
        // action 元数据：exec 走原有 HIGH 风险，kernel 操作走 LOW
        var actionMetadata = new LinkedHashMap<String, ActionMetadata>();
        actionMetadata.put("exec", new ActionMetadata(RiskLevel.HIGH, ToolExecutionSemantics.of(
                PermissionActionType.EXECUTE_SHELL, ToolSchedulingMode.SEQUENTIAL, ToolScopeResolvers.none())));
        if (kernelManager != null) {
            var kernelListSemantics = ToolExecutionSemantics.of(
                    PermissionActionType.GENERIC_TOOL_OPERATION, ToolSchedulingMode.PARALLEL_SAFE,
                    ToolScopeResolvers.none());
            var kernelMutateSemantics = ToolExecutionSemantics.of(
                    PermissionActionType.GENERIC_TOOL_OPERATION, ToolSchedulingMode.SEQUENTIAL,
                    ToolScopeResolvers.exactValues("kernelIds", "kernelId"));
            actionMetadata.put("kernel_list", new ActionMetadata(RiskLevel.LOW, kernelListSemantics));
            actionMetadata.put("kernel_inspect", new ActionMetadata(RiskLevel.LOW, kernelMutateSemantics));
            actionMetadata.put("kernel_reset", new ActionMetadata(RiskLevel.LOW, kernelMutateSemantics));
        }

        var actionEnum = kernelManager != null
                ? List.of("exec", "kernel_list", "kernel_inspect", "kernel_reset")
                : List.of("exec");

        return BuiltinTool.builder()
                .id("code")
                .category(ToolCategory.ACTION)
                .name("代码执行")
                .description("""
                        执行 Python / JavaScript / Shell 代码，管理持久内核会话。action 默认 exec。
                        exec — 执行代码。Python 预装 pandas/numpy/scipy/scikit-learn/matplotlib/seaborn/\
                        openpyxl/python-docx/python-pptx/pypdf/pillow/requests/httpx/beautifulsoup4。\
                        kernelId 传入则变量和导入跨调用保持，不传则每次一次性沙箱。\
                        安全护栏：永久阻断 rm -rf 系统目录/mkfs/dd 写块设备/shutdown/fork bomb 等；\
                        默认拒绝 rm -rf 子目录/chmod -R 777/git reset --hard/curl|sh/SQL DROP/sudo 等。\
                        删除文件走 file_manage(action=delete)，错误"代码执行环境未启用"不可重试。"""
                        + (kernelManager != null ? """
                        kernel_list — 列出当前活跃内核（无需参数）。
                        kernel_inspect — 查看指定内核变量与状态（kernelId 必填）。
                        kernel_reset — 清空指定内核变量与导入（kernelId 必填）。""" : ""))
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", buildInputProperties(actionEnum),
                        "dependentRequired", Map.of(
                                "exec", List.of("code"),
                                "kernel_inspect", List.of("kernelId"),
                                "kernel_reset", List.of("kernelId")
                        ))))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ))
                .tags(List.of(
                        "代码", "执行", "脚本", "沙箱",
                        "code", "execute", "python", "javascript",
                        "pandas", "numpy", "matplotlib", "数据分析",
                        "内核", "kernel", "变量", "调试"
                ))
                .actionMetadata(actionMetadata)
                .executor(input -> dispatch(input, executor))
                .build();
    }

    private Map<String, Object> buildInputProperties(List<String> actionEnum) {
        var props = new LinkedHashMap<String, Object>();
        props.put("action", Map.of(
                "type", "string",
                "enum", actionEnum,
                "description", "操作类型。exec=执行代码（默认）kernel_list=列出活跃内核 kernel_inspect=查看内核变量 kernel_reset=重置内核。"));
        props.put("code", Map.of("type", "string",
                "description", "action=exec 时必填：要执行的代码"));
        props.put("language", Map.of("type", "string",
                "enum", List.of("python", "javascript", "shell"),
                "description", "action=exec 时可选，编程语言，python 默认"));
        props.put("timeoutSeconds", Map.of("type", "integer",
                "description", "action=exec 时可选，执行超时秒数，默认 30"));
        props.put("kernelId", Map.of("type", "string",
                "description", "action=exec 时可选（持久内核 ID）；kernel_inspect/kernel_reset 时必填。"));
        return props;
    }

    private ToolResult dispatch(ToolInput input, CodeExecuteToolExecutor executor) {
        String action = input.getOptionalParam("action", String.class).orElse("exec");
        return switch (action) {
            case "exec" -> executor.execute(input);
            case "kernel_list" -> executeKernelList();
            case "kernel_inspect" -> executeKernelInspect(input);
            case "kernel_reset" -> executeKernelReset(input);
            default -> ToolResult.error("不支持的 action: " + action);
        };
    }

    private ToolResult executeKernelList() {
        if (kernelManager == null) return ToolResult.error("内核管理未启用");
        try {
            var kernelList = kernelManager.listKernels();
            var kernelMaps = kernelList.stream()
                    .map(info -> Map.<String, Object>of(
                            "kernelId", info.kernelId(),
                            "state", info.state(),
                            "idleSeconds", info.idleSeconds()
                    ))
                    .toList();
            return ToolResult.success(Map.of("kernels", kernelMaps, "total", kernelMaps.size()));
        } catch (Exception e) {
            log.error("列出内核失败: error={}", e.getMessage(), e);
            return ToolResult.error("列出内核失败: " + e.getMessage());
        }
    }

    private ToolResult executeKernelInspect(ToolInput input) {
        if (kernelManager == null) return ToolResult.error("内核管理未启用");
        try {
            String kernelId = input.getParam("kernelId", String.class);
            Map<String, String> variables = kernelManager.inspectKernel(kernelId);
            return ToolResult.success(Map.of("kernelId", kernelId, "variables", variables));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("检查内核失败: error={}", e.getMessage(), e);
            return ToolResult.error("检查内核失败: " + e.getMessage());
        }
    }

    private ToolResult executeKernelReset(ToolInput input) {
        if (kernelManager == null) return ToolResult.error("内核管理未启用");
        try {
            String kernelId = input.getParam("kernelId", String.class);
            kernelManager.resetKernel(kernelId);
            return ToolResult.success(Map.of("kernelId", kernelId, "message", "内核已重置，所有变量和导入已清空"));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("重置内核失败: error={}", e.getMessage(), e);
            return ToolResult.error("重置内核失败: " + e.getMessage());
        }
    }
}
