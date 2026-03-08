package com.lifepilot.meta.convenience;

import com.lifepilot.mcp.config.McpServerConfig;
import com.lifepilot.mcp.registry.McpServerRegistry;
import com.lifepilot.mcp.transport.TransportType;
import com.lifepilot.meta.config.MetaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * McpInstallerRegistrar 单元测试 — 验证 mcp-installer 注册和 npx 降级。
 *
 * @author zsg
 * @since 2026-03-08
 */
class McpInstallerRegistrarTest {

    private McpServerRegistry mcpServerRegistry;
    private MetaProperties properties;

    @BeforeEach
    void setUp() {
        mcpServerRegistry = mock(McpServerRegistry.class);
        properties = new MetaProperties();
    }

    @Test
    void registerMcpInstaller_npx可用时注册成功() {
        // 创建 spy，覆盖 isNpxAvailable 返回 true
        var registrar = spy(new McpInstallerRegistrar(mcpServerRegistry, properties));
        doReturn(true).when(registrar).isNpxAvailable();

        registrar.registerMcpInstaller();

        // 验证 connectServer 被调用
        var captor = ArgumentCaptor.forClass(McpServerConfig.class);
        verify(mcpServerRegistry).connectServer(captor.capture());

        var config = captor.getValue();
        assertThat(config.name()).isEqualTo("mcp-installer");
        assertThat(config.transport()).isEqualTo(TransportType.STDIO);
        assertThat(config.command()).isEqualTo("npx");
        assertThat(config.args()).containsExactly("@anaisbetts/mcp-installer");
        assertThat(config.autoConnect()).isTrue();
        assertThat(config.reconnect()).isFalse();
    }

    @Test
    void registerMcpInstaller_npx不可用时跳过注册() {
        var registrar = spy(new McpInstallerRegistrar(mcpServerRegistry, properties));
        doReturn(false).when(registrar).isNpxAvailable();

        registrar.registerMcpInstaller();

        // 不应调用 connectServer
        verify(mcpServerRegistry, never()).connectServer(any());
    }

    @Test
    void registerMcpInstaller_npx不可用时不抛异常() {
        var registrar = spy(new McpInstallerRegistrar(mcpServerRegistry, properties));
        doReturn(false).when(registrar).isNpxAvailable();

        assertThatNoException().isThrownBy(registrar::registerMcpInstaller);
    }

    @Test
    void afterPropertiesSet_触发注册流程() {
        var registrar = spy(new McpInstallerRegistrar(mcpServerRegistry, properties));
        doReturn(true).when(registrar).isNpxAvailable();

        registrar.afterPropertiesSet();

        verify(mcpServerRegistry).connectServer(any());
    }

    @Test
    void registerMcpInstaller_使用自定义配置() {
        // 自定义 command 和 args
        properties.getMcpInstaller().setCommand("/usr/local/bin/npx");
        properties.getMcpInstaller().setArgs(List.of("@anaisbetts/mcp-installer", "--verbose"));

        var registrar = spy(new McpInstallerRegistrar(mcpServerRegistry, properties));
        doReturn(true).when(registrar).isNpxAvailable();

        registrar.registerMcpInstaller();

        var captor = ArgumentCaptor.forClass(McpServerConfig.class);
        verify(mcpServerRegistry).connectServer(captor.capture());

        var config = captor.getValue();
        assertThat(config.command()).isEqualTo("/usr/local/bin/npx");
        assertThat(config.args()).containsExactly("@anaisbetts/mcp-installer", "--verbose");
    }

    @Test
    void registerMcpInstaller_McpServerConfig构建正确() {
        var registrar = spy(new McpInstallerRegistrar(mcpServerRegistry, properties));
        doReturn(true).when(registrar).isNpxAvailable();

        registrar.registerMcpInstaller();

        var captor = ArgumentCaptor.forClass(McpServerConfig.class);
        verify(mcpServerRegistry).connectServer(captor.capture());

        var config = captor.getValue();
        // 验证所有关键字段
        assertThat(config.name()).isEqualTo(McpInstallerRegistrar.SERVER_NAME);
        assertThat(config.transport()).isEqualTo(TransportType.STDIO);
        assertThat(config.autoConnect()).isTrue();
        assertThat(config.reconnect()).isFalse();
        // 默认值由 McpServerConfig compact constructor 填充
        assertThat(config.timeout()).isEqualTo(McpServerConfig.DEFAULT_TIMEOUT);
    }
}
