package com.lifepilot.meta.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.browser.InteractiveElementIndexer;
import com.lifepilot.meta.infra.web.WebSearchConfig;
import com.lifepilot.meta.infra.web.WebSearchConfigProvider;
import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.runtime.RuntimeStatus;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * InfraToolProvider 单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
class InfraToolProviderTest {

    private MetaProperties properties;
    private InfraToolProvider provider;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        WebSearchConfigProvider webSearchConfigProvider = mock(WebSearchConfigProvider.class);
        when(webSearchConfigProvider.getConfig()).thenReturn(new WebSearchConfig(
                "https://api.tavily.com/search",
                "tavily",
                "",
                5,
                10,
                30,
                "basic",
                "general",
                true
        ));
        var workspaceResolver = new WorkspaceResolver(null, "");
        var indexer = new InteractiveElementIndexer(new ObjectMapper());
        // PersistentKernelManager 现在强依赖 PythonRuntimeManager，桩出 Ready 状态供 kernel 注册路径使用
        PythonRuntimeManager runtimeManager = mock(PythonRuntimeManager.class);
        when(runtimeManager.checkStatus()).thenReturn(new RuntimeStatus.Ready("3.12.13", 0L));
        when(runtimeManager.getPythonExecutable()).thenReturn(Paths.get("python3"));
        // 构造签名：23 参数 — workspaceResolver 位于第 16 位，之后依次是 attachmentRepository /
        // chatSessionRepository / skillPathWhitelist / ssrfGuard / interactiveElementIndexer /
        // pythonRuntimeManager / commandGuard。
        // 浏览器能力补全（PR #99）在 develop 19 参数基础上追加 ssrfGuard 与 interactiveElementIndexer；
        // Code Execution Runtime（Task 13）追加 pythonRuntimeManager 与 commandGuard。
        provider = new InfraToolProvider(
                properties,
                webSearchConfigProvider,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                workspaceResolver,
                null, null, null,
                com.lifepilot.meta.infra.web.SsrfGuard.disabled(),
                indexer,
                runtimeManager, null,
                null);  // sessionTranscriptRepository
    }

    @Test
    void provide_返回正确的SkillDefinition() {
        // provide() 方法已在 skill-architecture-simplify 中移除
        // InfraToolProvider 不再实现 BuiltinSkillProvider 接口
    }

    @Test
    void registerTools_注册全部已实现类别工具() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);

        provider.registerTools(registry);

        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(registry, atLeastOnce()).registerBuiltinTool(captor.capture());

        var tools = captor.getAllValues();
        // 基础工具（始终注册，不依赖外部环境）
        var alwaysExpected = List.of(
                // 信息获取（2）
                "web.search",
                "web.fetch",
                // Shell（1，shell.exec 始终注册；shell.process 仅当 processManager/sessionManager 可用时注册）
                "shell.exec",
                // 浏览器自动化（1）
                "browser",
                // 代码执行（2，kernel.enabled 默认开 → code.kernel 始终注册）
                "code.execute",
                "code.kernel",
                // 文件系统（5）
                "file.read",
                "file.write",
                "file.list",
                "file.edit",
                "file.manage",
                // Git 工具（2）
                "git.query",
                "git.mutate"
        );
        var toolIds = tools.stream().map(BuiltinTool::id).toList();
        assertThat(toolIds).containsAll(alwaysExpected);
        // shell.process 工具只在 processManager 或 sessionManager 可用时注册，不强制断言
        int expectedMin = alwaysExpected.size();
        int expectedMax = alwaysExpected.size() + 1; // shell.process
        assertThat(tools.size()).isBetween(expectedMin, expectedMax);
    }

    @Test
    void registerTools_所有工具均带非空tags() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);

        provider.registerTools(registry);

        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(registry, atLeastOnce()).registerBuiltinTool(captor.capture());

        for (BuiltinTool tool : captor.getAllValues()) {
            assertThat(tool.tags()).as("tool %s", tool.id()).isNotEmpty();
        }
    }

    @Test
    void registerTools_关键工具风险等级正确() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);

        provider.registerTools(registry);

        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(registry, atLeastOnce()).registerBuiltinTool(captor.capture());

        var toolMap = new java.util.HashMap<String, com.lifepilot.observability.guardrail.RiskLevel>();
        for (BuiltinTool tool : captor.getAllValues()) {
            toolMap.put(tool.id(), tool.riskLevel());
        }

        // 信息获取 → LOW
        assertThat(toolMap.get("web.search")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);
        assertThat(toolMap.get("web.fetch")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);

        // Shell / 代码执行 → HIGH
        assertThat(toolMap.get("shell.exec")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.HIGH);
        assertThat(toolMap.get("code.execute")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.HIGH);

        // 浏览器 → HIGH（包含 evaluate 等高风险 action）
        assertThat(toolMap.get("browser")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.HIGH);
    }

    @Test
    void registerTools_文件工具应启用资源串行调度() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);

        provider.registerTools(registry);

        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(registry, atLeastOnce()).registerBuiltinTool(captor.capture());

        var toolMap = new java.util.HashMap<String, BuiltinTool>();
        for (BuiltinTool tool : captor.getAllValues()) {
            toolMap.put(tool.id(), tool);
        }

        assertThat(toolMap.get("file.read").schedulingMode()).isEqualTo(ToolSchedulingMode.RESOURCE_SERIALIZED);
        assertThat(toolMap.get("file.write").schedulingMode()).isEqualTo(ToolSchedulingMode.RESOURCE_SERIALIZED);
        assertThat(toolMap.get("file.edit").schedulingMode()).isEqualTo(ToolSchedulingMode.RESOURCE_SERIALIZED);
        assertThat(toolMap.get("file.manage").schedulingMode()).isEqualTo(ToolSchedulingMode.RESOURCE_SERIALIZED);
    }

    @Test
    void Sandbox禁用时不创建kernel与code工具() {
        // 模拟 lifepilot.sandbox.enabled=false：SandboxAutoConfiguration 不注册任何 bean，
        // pythonRuntimeManager 注入为 null —— 之前会让 PersistentKernelManager 构造器 NPE 崩溃，
        // 现在应当优雅降级，仅跳过 code.execute / code.kernel 注册，其他工具仍正常构建。
        WebSearchConfigProvider webSearchConfigProvider = mock(WebSearchConfigProvider.class);
        when(webSearchConfigProvider.getConfig()).thenReturn(new WebSearchConfig(
                "https://api.tavily.com/search",
                "tavily",
                "",
                5,
                10,
                30,
                "basic",
                "general",
                true
        ));
        var workspaceResolver = new WorkspaceResolver(null, "");
        var indexer = new InteractiveElementIndexer(new ObjectMapper());
        var sandboxDisabledProvider = new InfraToolProvider(
                properties,
                webSearchConfigProvider,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                workspaceResolver,
                null, null, null,
                com.lifepilot.meta.infra.web.SsrfGuard.disabled(),
                indexer,
                null, // pythonRuntimeManager == null（sandbox 禁用）
                null,
                null);  // sessionTranscriptRepository

        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);

        // 关键断言：构造与 registerTools 都不抛 NPE，应用可正常启动
        sandboxDisabledProvider.registerTools(registry);

        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(registry, atLeastOnce()).registerBuiltinTool(captor.capture());

        var toolIds = captor.getAllValues().stream().map(BuiltinTool::id).toList();
        // 代码执行 / 内核管理工具应被跳过
        assertThat(toolIds).doesNotContain("code.execute", "code.kernel");
        // 不依赖 sandbox 的工具仍正常注册
        assertThat(toolIds).contains("web.search", "web.fetch", "shell.exec",
                "browser", "file.read", "file.write", "file.list", "file.edit", "file.manage");
    }
}
