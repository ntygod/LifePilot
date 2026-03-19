package com.lifepilot.meta.infra;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

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
        var restClientBuilder = mock(RestClient.Builder.class);
        when(restClientBuilder.build()).thenReturn(mock(RestClient.class));
        provider = new InfraToolProvider(properties, restClientBuilder, null, null, null, null, null, null, null, null);
    }

    @Test
    void provide_返回正确的SkillDefinition() {
        var definition = provider.provide();

        assertThat(definition.id()).isEqualTo("builtin.infrastructure");
        assertThat(definition.name()).isEqualTo("基础工具集");
        assertThat(definition.description()).isNotBlank();
        assertThat(definition.version()).isEqualTo("1.0.0");
    }

    @Test
    void registerTools_注册31个工具_含全部已实现类别() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);

        provider.registerTools(registry);

        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(registry, times(31)).registerBuiltinTool(captor.capture());

        var tools = captor.getAllValues();
        assertThat(tools).extracting(BuiltinTool::id)
                .containsExactlyInAnyOrder(
                        // 环境感知（3）
                        "builtin.env.datetime",
                        "builtin.env.user-profile",
                        "builtin.env.system-info",
                        // 信息获取（2）
                        "builtin.web.search",
                        "builtin.web.fetch",
                        // 推理辅助（1）
                        "builtin.reason.calculate",
                        // Shell 执行（1）
                        "builtin.shell.exec",
                        // 浏览器自动化（13）
                        "builtin.browser.navigate",
                        "builtin.browser.click",
                        "builtin.browser.input",
                        "builtin.browser.screenshot",
                        "builtin.browser.scroll",
                        "builtin.browser.wait",
                        "builtin.browser.hover",
                        "builtin.browser.select",
                        "builtin.browser.keyboard",
                        "builtin.browser.evaluate",
                        "builtin.browser.accessibility",
                        "builtin.browser.tab",
                        "builtin.browser.storage",
                        // 代码执行（1）
                        "builtin.code.execute",
                        // 文件系统（10）
                        "builtin.file.read",
                        "builtin.file.write",
                        "builtin.file.list",
                        "builtin.file.search",
                        "builtin.file.append",
                        "builtin.file.delete",
                        "builtin.file.copy",
                        "builtin.file.move",
                        "builtin.file.info",
                        "builtin.file.patch"
                );
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

        // 环境感知 / 信息获取 / 推理 → LOW
        assertThat(toolMap.get("builtin.env.datetime")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);
        assertThat(toolMap.get("builtin.env.user-profile")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);
        assertThat(toolMap.get("builtin.env.system-info")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);
        assertThat(toolMap.get("builtin.web.search")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);
        assertThat(toolMap.get("builtin.web.fetch")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);
        assertThat(toolMap.get("builtin.reason.calculate")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);

        // Shell / 代码执行 → HIGH
        assertThat(toolMap.get("builtin.shell.exec")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.HIGH);
        assertThat(toolMap.get("builtin.code.execute")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.HIGH);

        // 浏览器导航/点击/输入 → MEDIUM，截图 → LOW
        assertThat(toolMap.get("builtin.browser.navigate")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.MEDIUM);
        assertThat(toolMap.get("builtin.browser.click")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.MEDIUM);
        assertThat(toolMap.get("builtin.browser.input")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.MEDIUM);
        assertThat(toolMap.get("builtin.browser.screenshot")).isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);
    }
}
