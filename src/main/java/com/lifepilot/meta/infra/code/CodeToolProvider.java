package com.lifepilot.meta.infra.code;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.code.kernel.PersistentKernelManager;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.sandbox.repository.SandboxRepository;
import com.lifepilot.sandbox.session.SandboxSessionManager;
import com.lifepilot.sandbox.validator.CodeValidator;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 代码执行工具提供者。
 *
 * <p>管理 {@code code.execute} 工具，支持一次性沙箱和持久内核两种模式。</p>
 *
 * @author zsg
 * @since 2026-04-07
 */
public class CodeToolProvider {

    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final MetaProperties properties;
    @Nullable
    private final SandboxSessionManager sandboxSessionManager;
    @Nullable
    private final CodeValidator codeValidator;
    @Nullable
    private final SandboxRepository sandboxRepository;
    @Nullable
    private final PersistentKernelManager kernelManager;

    public CodeToolProvider(MetaProperties properties,
                            @Nullable SandboxSessionManager sandboxSessionManager,
                            @Nullable CodeValidator codeValidator,
                            @Nullable SandboxRepository sandboxRepository,
                            @Nullable PersistentKernelManager kernelManager) {
        this.properties = properties;
        this.sandboxSessionManager = sandboxSessionManager;
        this.codeValidator = codeValidator;
        this.sandboxRepository = sandboxRepository;
        this.kernelManager = kernelManager;
    }

    /**
     * 构建代码执行工具列表。
     *
     * @return 代码执行工具列表
     */
    public List<BuiltinTool> buildCodeTools() {
        var executor = new CodeExecuteToolExecutor(
                properties, sandboxSessionManager, codeValidator, sandboxRepository, kernelManager);
        return List.of(buildCodeExecuteTool(executor));
    }

    /** 构建代码执行工具。 */
    private BuiltinTool buildCodeExecuteTool(CodeExecuteToolExecutor executor) {
        return BuiltinTool.builder()
                .id("code.execute")
                .category(ToolCategory.ACTION)
                .name("执行代码")
                .description("沙箱代码执行")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("code"),
                        "properties", Map.of(
                                "code", Map.of("type", "string",
                                        "description", "要执行的代码"),
                                "language", Map.of("type", "string",
                                        "description", "编程语言（python/javascript/shell），默认使用配置值"),
                                "timeoutSeconds", Map.of("type", "integer",
                                        "description", "执行超时秒数，默认 30"),
                                "kernelId", Map.of("type", "string",
                                        "description", "持久内核 ID（如 \"data-analysis\"）。" +
                                                "传入后变量和导入跨调用保持，同一 kernelId 共享状态。" +
                                                "不传则一次性沙箱。" +
                                                "特殊 code 值：'kernel:reset' 清空状态，'kernel:inspect' 查看变量。")
                        )
                )))
                .riskLevel(RiskLevel.HIGH)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.EXECUTE_SHELL,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }
}
