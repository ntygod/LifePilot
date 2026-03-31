package com.lifepilot.meta.infra.code.kernel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CodeKernelToolProvider} 单元测试。
 *
 * <p>验证工具构建的正确性：工具数量、ID 和 Schema。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
class CodeKernelToolProviderTest {

    private final PersistentKernelManager manager;
    private final CodeKernelToolProvider provider;

    CodeKernelToolProviderTest() {
        var config = new com.lifepilot.meta.config.MetaProperties.Infra.Kernel();
        config.setMaxConcurrentKernels(3);
        config.setTtlMinutes(30);
        config.setCleanupIntervalSeconds(3600);
        this.manager = new PersistentKernelManager(config, null);
        this.provider = new CodeKernelToolProvider(manager);
    }

    @Test
    void buildKernelTools_应返回2个工具() {
        var tools = provider.buildKernelTools();
        assertThat(tools).hasSize(2);
    }

    @Test
    void 每个工具的id和schema应正确() {
        var tools = provider.buildKernelTools();
        var expectedIds = List.of("code.kernel.reset", "code.kernel.inspect");

        var actualIds = tools.stream().map(t -> t.id()).toList();
        assertThat(actualIds).containsExactlyInAnyOrderElementsOf(expectedIds);

        // 验证每个工具都有 kernelId 参数
        for (var tool : tools) {
            var schema = tool.inputSchema();
            assertThat(schema).isNotNull();
            var schemaMap = schema.toMap();
            assertThat(schemaMap).isNotNull();

            @SuppressWarnings("unchecked")
            var required = (java.util.List<String>) schemaMap.get("required");
            assertThat(required).contains("kernelId");
        }
    }

    @Test
    void reset工具应为非幂等() {
        var tools = provider.buildKernelTools();
        var resetTool = tools.stream()
                .filter(t -> "code.kernel.reset".equals(t.id()))
                .findFirst()
                .orElseThrow();

        assertThat(resetTool.idempotent()).isFalse();
    }

    @Test
    void inspect工具应为幂等() {
        var tools = provider.buildKernelTools();
        var inspectTool = tools.stream()
                .filter(t -> "code.kernel.inspect".equals(t.id()))
                .findFirst()
                .orElseThrow();

        // inspect 默认 idempotent=true（BuiltinTool.Builder 默认值）
        assertThat(inspectTool.idempotent()).isTrue();
    }

    @org.junit.jupiter.api.AfterAll
    static void cleanup() {
        // 静态清理不需要，各 manager 会在 GC 时清理
    }
}
