package com.lifepilot.meta.infra.env;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DateTimeToolExecutor 单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
class DateTimeToolExecutorTest {

    private MetaProperties properties;
    private DateTimeToolExecutor executor;

    @BeforeEach
    void setUp() {
        properties = new MetaProperties();
        executor = new DateTimeToolExecutor(properties);
    }

    @Test
    void execute_返回当前日期时间信息() {
        ToolInput input = new ToolInput("builtin.env.datetime", Map.of(), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsKeys("date", "time", "dayOfWeek", "timezone", "formatted");
        // 验证日期格式正确
        String date = (String) result.data().get("date");
        assertThat(date).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void execute_使用系统时区_当配置为空时() {
        properties.getInfra().getUserProfile().setTimezone("");
        ToolInput input = new ToolInput("builtin.env.datetime", Map.of(), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("timezone")).isEqualTo(ZoneId.systemDefault().getId());
    }

    @Test
    void execute_使用配置时区() {
        properties.getInfra().getUserProfile().setTimezone("Asia/Shanghai");
        ToolInput input = new ToolInput("builtin.env.datetime", Map.of(), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("timezone")).isEqualTo("Asia/Shanghai");
    }

    @Test
    void execute_输入参数timezone覆盖配置() {
        properties.getInfra().getUserProfile().setTimezone("Asia/Shanghai");
        ToolInput input = new ToolInput("builtin.env.datetime",
                Map.of("timezone", "America/New_York"), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("timezone")).isEqualTo("America/New_York");
    }

    @Test
    void execute_日期与当前日期一致() {
        ToolInput input = new ToolInput("builtin.env.datetime", Map.of(), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isTrue();
        String date = (String) result.data().get("date");
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        assertThat(date).isEqualTo(today);
    }

    @Test
    void execute_无效时区返回错误() {
        ToolInput input = new ToolInput("builtin.env.datetime",
                Map.of("timezone", "Invalid/Zone"), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("获取日期时间失败");
    }
}
