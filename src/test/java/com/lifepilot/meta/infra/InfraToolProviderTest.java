package com.lifepilot.meta.infra;

import com.lifepilot.config.workspace.WorkspaceResolver;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.web.WebSearchConfig;
import com.lifepilot.meta.infra.web.WebSearchConfigProvider;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        provider = new InfraToolProvider(properties, webSearchConfigProvider, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, workspaceResolver, null);
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
                // 代码执行（1）
                "code.execute",
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
    void registerTools_所有工具tags含infrastructure() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);

        provider.registerTools(registry);

        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(registry, atLeastOnce()).registerBuiltinTool(captor.capture());

        for (BuiltinTool tool : captor.getAllValues()) {
            assertThat(tool.tags()).contains("infrastructure");
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
}
