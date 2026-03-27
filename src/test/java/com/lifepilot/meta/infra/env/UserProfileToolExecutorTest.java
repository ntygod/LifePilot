package com.lifepilot.meta.infra.env;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserProfileToolExecutor 单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
class UserProfileToolExecutorTest {

    private MetaProperties properties;
    private UserProfileToolExecutor executor;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        executor = new UserProfileToolExecutor(properties);
    }

    @Test
    void execute_返回用户偏好信息() {
        ToolInput input = new ToolInput("env.user-profile", Map.of(), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsKeys("timezone", "cacheTtlSeconds");
    }

    @Test
    void execute_时区为空时使用系统时区() {
        properties.getInfra().getUserProfile().setTimezone("");
        ToolInput input = new ToolInput("env.user-profile", Map.of(), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("timezone")).isEqualTo(ZoneId.systemDefault().getId());
    }

    @Test
    void execute_返回配置的时区() {
        properties.getInfra().getUserProfile().setTimezone("Europe/London");
        ToolInput input = new ToolInput("env.user-profile", Map.of(), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("timezone")).isEqualTo("Europe/London");
    }

    @Test
    void execute_返回正确的cacheTtl() {
        properties.getInfra().getUserProfile().setCacheTtlSeconds(600);
        ToolInput input = new ToolInput("env.user-profile", Map.of(), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("cacheTtlSeconds")).isEqualTo(600);
    }
}
