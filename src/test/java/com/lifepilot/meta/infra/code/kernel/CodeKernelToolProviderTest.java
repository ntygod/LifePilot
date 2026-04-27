package com.lifepilot.meta.infra.code.kernel;

import com.lifepilot.sandbox.runtime.PythonRuntimeManager;
import com.lifepilot.sandbox.runtime.RuntimeStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CodeKernelToolProvider} 单元测试。
 *
 * <p>验证 {@code code.kernel} 单工具多 action 的元数据正确性。
 * {@link PythonRuntimeManager} 通过 mock 桩出 Ready 状态，避免依赖真实捆绑运行时。</p>
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
        var runtimeManager = mock(PythonRuntimeManager.class);
        // 仅元数据测试不会触发 kernel 创建，使用 lenient stub 避免 UnnecessaryStubbingException
        lenient().when(runtimeManager.checkStatus()).thenReturn(new RuntimeStatus.Ready("3.12.13", 0L));
        lenient().when(runtimeManager.getPythonExecutable()).thenReturn(Paths.get("python3"));
        this.manager = new PersistentKernelManager(config, null, runtimeManager);
        this.provider = new CodeKernelToolProvider(manager);
    }

    @Test
    void buildKernelTools_仅返回1个code_kernel工具() {
        var tools = provider.buildKernelTools();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).id()).isEqualTo("code.kernel");
    }

    @Test
    void schema应包含action枚举与kernelId() {
        var tool = provider.buildKernelTools().get(0);
        var schemaMap = tool.inputSchema().toMap();

        @SuppressWarnings("unchecked")
        var required = (List<String>) schemaMap.get("required");
        assertThat(required).containsExactly("action");

        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) schemaMap.get("properties");
        assertThat(properties).containsKeys("action", "kernelId");

        @SuppressWarnings("unchecked")
        var actionProp = (Map<String, Object>) properties.get("action");
        @SuppressWarnings("unchecked")
        var enumValues = (List<String>) actionProp.get("enum");
        assertThat(enumValues).containsExactlyInAnyOrder("list", "reset", "inspect");
    }

    @Test
    void actionMetadata应覆盖三个action() {
        var tool = provider.buildKernelTools().get(0);
        assertThat(tool.actionMetadata()).containsKeys("list", "reset", "inspect");
    }
}
