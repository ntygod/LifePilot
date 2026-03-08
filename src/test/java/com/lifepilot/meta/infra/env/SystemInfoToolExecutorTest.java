package com.lifepilot.meta.infra.env;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SystemInfoToolExecutor 单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
class SystemInfoToolExecutorTest {

    private SystemInfoToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SystemInfoToolExecutor();
    }

    @Test
    void execute_返回系统信息() {
        ToolInput input = new ToolInput("builtin.env.system-info", Map.of(), JsonSchema.empty(), null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsKeys(
                "osName", "osVersion", "osArch",
                "javaVersion", "javaVendor", "jvmName",
                "totalMemoryMB", "freeMemoryMB", "maxMemoryMB", "usedMemoryMB",
                "availableProcessors",
                "userName", "userHome"
        );
    }

    @Test
    void execute_OS信息与System属性一致() {
        ToolInput input = new ToolInput("builtin.env.system-info", Map.of(), JsonSchema.empty(), null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("osName")).isEqualTo(System.getProperty("os.name"));
        assertThat(result.data().get("javaVersion")).isEqualTo(System.getProperty("java.version"));
    }

    @Test
    void execute_内存值为非负数() {
        ToolInput input = new ToolInput("builtin.env.system-info", Map.of(), JsonSchema.empty(), null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat((long) result.data().get("totalMemoryMB")).isGreaterThanOrEqualTo(0);
        assertThat((long) result.data().get("freeMemoryMB")).isGreaterThanOrEqualTo(0);
        assertThat((long) result.data().get("maxMemoryMB")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void execute_处理器数量大于零() {
        ToolInput input = new ToolInput("builtin.env.system-info", Map.of(), JsonSchema.empty(), null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat((int) result.data().get("availableProcessors")).isGreaterThan(0);
    }
}
