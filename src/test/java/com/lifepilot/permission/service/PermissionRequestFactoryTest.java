package com.lifepilot.permission.service;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.config.ToolConfigProperties;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PermissionRequestFactory 单元测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
class PermissionRequestFactoryTest {

    @Test
    void 显式上下文优先覆盖推导出的任务与工作区() {
        var factory = new PermissionRequestFactory(new ObservabilityProperties(), new ToolConfigProperties());
        var tool = BuiltinTool.builder()
                .id("builtin.shell.exec")
                .name("执行 Shell")
                .description("执行命令")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.HIGH)
                .budget(ToolBudget.DEFAULT)
                .executor(_ -> ToolResult.success(Map.of()))
                .build();
        var input = new ToolInput(
                tool.id(),
                Map.of("cwd", "D:/WorkSpace/Project/News"),
                JsonSchema.empty(),
                null,
                Map.of(
                        ToolContextKeys.CHANNEL_TYPE, "cron",
                        ToolContextKeys.SESSION_ID, "cron:ignored-task",
                        ToolContextKeys.TASK_ID, "task-explicit",
                        ToolContextKeys.WORKSPACE_ID, "workspace-explicit"
                )
        );

        var request = factory.create(tool, input, "trace-1");

        assertThat(request.taskId()).isEqualTo("task-explicit");
        assertThat(request.workspaceId()).isEqualTo("workspace-explicit");
    }
}
