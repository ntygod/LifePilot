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
 * <p>注入捆绑 Python 运行时管理器与命令护栏，传递给 {@link CodeExecuteToolExecutor}：
 * runtimeManager 让 execute() 入口校验运行时就绪状态；commandGuard 在 process / kernel
 * 路径执行前阻断 HARDLINE / DANGEROUS 命令。</p>
 *
 * <p>工具能力边界（暴露给 LLM）：
 * <ul>
 *   <li>支持 Python / JavaScript / Shell；Python 预装数据科学栈</li>
 *   <li>HARDLINE 命令（rm -rf / 等）永久阻断；DANGEROUS 命令（git reset --hard 等）默认拒绝</li>
 *   <li>运行时未启用时返回 "代码执行环境未启用" 错误，由调用方决定是否提示用户</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-07
 */
public class CodeToolProvider {

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

    /**
     * 构建代码执行工具列表。
     *
     * @return 代码执行工具列表
     */
    public List<BuiltinTool> buildCodeTools() {
        var executor = new CodeExecuteToolExecutor(
                properties, sandboxSessionManager, codeValidator, sandboxRepository,
                kernelManager, runtimeManager, commandGuard);
        return List.of(buildCodeExecuteTool(executor));
    }

    /** 构建代码执行工具。 */
    private BuiltinTool buildCodeExecuteTool(CodeExecuteToolExecutor executor) {
        return BuiltinTool.builder()
                .id("code.execute")
                .category(ToolCategory.ACTION)
                .name("执行代码")
                .description("""
                        执行 Python / JavaScript / Shell 代码。
                        Python 运行时已预装 pandas / numpy / scipy / scikit-learn / matplotlib / seaborn / \
                        openpyxl / python-docx / python-pptx / pypdf / pillow / requests / httpx / beautifulsoup4 \
                        等数据科学常用库，import 即用，不需要 pip install。
                        JavaScript 依赖系统 Node.js；Shell 在 Linux/macOS 走 bash，Windows 走 cmd（不能执行 .sh 脚本）。
                        永久阻断（不可绕过）：rm -rf 系统目录 / mkfs / dd 写块设备 / shutdown / reboot / fork bomb 等不可恢复操作。
                        默认拒绝（可配置放行）：rm -rf 子目录 / chmod -R 777 / git reset --hard / curl|sh / heredoc 跑 shell / SQL DROP / sudo 等高风险操作。
                        错误"代码执行环境未启用"表示当前不可用，不要重试。
                        """)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("code"),
                        "properties", Map.of(
                                "code", Map.of("type", "string",
                                        "description", "要执行的代码"),
                                "language", Map.of("type", "string",
                                        "enum", List.of("python", "javascript", "shell"),
                                        "description", "编程语言。python 默认，预装数据科学栈；javascript 依赖系统 Node.js；shell 在 Linux/macOS 走 bash，Windows 走 cmd"),
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
                .tags(List.of(
                        "代码", "执行", "脚本", "沙箱",
                        "code", "execute", "python", "javascript",
                        "pandas", "numpy", "matplotlib", "数据分析",
                        "文档生成", "docx", "xlsx", "pdf", "csv",
                        "图像处理", "pillow", "ML", "scikit-learn",
                        "API 调试", "requests", "httpx"
                ))
                .executor(executor::execute)
                .build();
    }
}
