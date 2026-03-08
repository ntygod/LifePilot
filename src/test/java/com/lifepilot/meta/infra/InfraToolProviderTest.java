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
        provider = new InfraToolProvider(properties, restClientBuilder, null, null);
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
    void registerTools_注册5个工具_含环境感知和信息获取() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);

        provider.registerTools(registry);

        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(registry, times(5)).registerBuiltinTool(captor.capture());

        var tools = captor.getAllValues();
        assertThat(tools).extracting(BuiltinTool::id)
                .containsExactlyInAnyOrder(
                        "builtin.env.datetime",
                        "builtin.env.user-profile",
                        "builtin.env.system-info",
                        "builtin.web.search",
                        "builtin.web.fetch"
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
    void registerTools_所有环境工具风险等级为LOW() {
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);

        provider.registerTools(registry);

        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(registry, atLeastOnce()).registerBuiltinTool(captor.capture());

        for (BuiltinTool tool : captor.getAllValues()) {
            assertThat(tool.riskLevel())
                    .as("工具 %s 风险等级应为 LOW", tool.id())
                    .isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.LOW);
        }
    }
}
