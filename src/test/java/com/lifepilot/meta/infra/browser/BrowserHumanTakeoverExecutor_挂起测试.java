package com.lifepilot.meta.infra.browser;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BrowserHumanTakeoverExecutor 行为测试 — 验证工具输出携带 _suspend 信号并包含必要字段。
 *
 * @author zsg
 * @since 2026-04-24
 */
class BrowserHumanTakeoverExecutor_挂起测试 {

    private final BrowserHumanTakeoverExecutor executor = new BrowserHumanTakeoverExecutor(new MetaProperties());

    @Test
    void 传入_reason_返回携带_suspend_信号的成功结果() {
        var params = new LinkedHashMap<String, Object>();
        params.put("reason", "需要扫码登录");
        params.put("sessionId", "task-login");
        var input = new ToolInput("browser", params, JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsEntry("_suspend", true);
        assertThat(result.data()).containsEntry("sessionId", "task-login");
        assertThat(result.<String>getData("message")).contains("需要扫码登录");

        @SuppressWarnings("unchecked")
        Map<String, Object> suspendReason = (Map<String, Object>) result.data().get("_suspendReason");
        assertThat(suspendReason).isNotNull();
        assertThat(suspendReason).containsEntry("type", "BrowserTakeover");
        assertThat(suspendReason).containsEntry("sessionId", "task-login");
        assertThat(suspendReason).containsEntry("reason", "需要扫码登录");
        assertThat(suspendReason.get("requestedAt")).asString().isNotBlank();
        // MetaProperties 默认 takeover.timeoutSeconds=300，应原样透出
        assertThat(suspendReason).containsEntry("timeoutSeconds", 300);
    }

    @Test
    void 未传_sessionId_时默认_default() {
        var params = new LinkedHashMap<String, Object>();
        params.put("reason", "人机验证");
        var input = new ToolInput("browser", params, JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> suspendReason = (Map<String, Object>) result.data().get("_suspendReason");
        assertThat(suspendReason).containsEntry("sessionId", "default");
        assertThat(result.data()).containsEntry("sessionId", "default");
    }

    @Test
    void 缺少_reason_返回_error() {
        var input = new ToolInput("browser", Map.of(), JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("reason");
    }

    @Test
    void reason_为空白字符串_返回_error() {
        var params = new LinkedHashMap<String, Object>();
        params.put("reason", "   ");
        var input = new ToolInput("browser", params, JsonSchema.empty(), null, null);

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("不能为空");
    }
}
