package com.lifepilot.meta.infra;

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
                "tavily",
                "",
                5,
                10,
                30,
                "basic",
                "general",
                true
        ));
        provider = new InfraToolProvider(properties, webSearchConfigProvider, null, null, null, null, null, null, null, null, null, null, null, null);
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
        var expectedToolIds = List.of(
                // 信息获取（3）
                "web.search",
                "web.fetch",
                "http.request",
                // 推理辅助（1）
                "reason.calculate",
                // Shell 执行（1）
                "shell.exec",
                // 浏览器自动化（14）
                "browser.navigate",
                "browser.click",
                "browser.input",
                "browser.screenshot",
                "browser.scroll",
                "browser.wait",
                "browser.hover",
                "browser.select",
                "browser.keyboard",
                "browser.evaluate",
                "browser.accessibility",
                "browser.tab",
                "browser.storage",
                "browser.close",
                // 代码执行（1）
                "code.execute",
                // 文件系统（11）
                "file.read",
                "file.write",
                "file.list",
                "file.search",
                "file.delete",
                "file.copy",
                "file.move",
                "file.info",
                "file.patch",
                "file.grep",
                "file.find"
        );
        assertThat(tools).hasSize(expectedToolIds.size());
        assertThat(tools).extracting(BuiltinTool::id)
                .containsExactlyInAnyOrderElementsOf(expectedToolIds);
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

        // 信息获取 / 推理 → LOW
        assertThat(toolMap.get("web.search")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);
        assertThat(toolMap.get("web.fetch")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);
        assertThat(toolMap.get("reason.calculate")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);

        // Shell / 代码执行 → HIGH
        assertThat(toolMap.get("shell.exec")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.HIGH);
        assertThat(toolMap.get("code.execute")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.HIGH);

        // 浏览器导航/点击/输入 → MEDIUM，截图 → LOW
        assertThat(toolMap.get("browser.navigate")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.MEDIUM);
        assertThat(toolMap.get("browser.click")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.MEDIUM);
        assertThat(toolMap.get("browser.input")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.MEDIUM);
        assertThat(toolMap.get("browser.screenshot")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);
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
        assertThat(toolMap.get("file.search").schedulingMode()).isEqualTo(ToolSchedulingMode.RESOURCE_SERIALIZED);
        assertThat(toolMap.get("file.patch").schedulingMode()).isEqualTo(ToolSchedulingMode.RESOURCE_SERIALIZED);
        assertThat(toolMap.get("file.find").schedulingMode()).isEqualTo(ToolSchedulingMode.RESOURCE_SERIALIZED);
    }
}
