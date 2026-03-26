package com.lifepilot.permission.service;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.config.ToolConfigProperties;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
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

    private final PermissionScopeResolver permissionScopeResolver = new PermissionScopeResolver();
    private final DynamicToolRegistry toolRegistry = new DynamicToolRegistry(_ -> {});
    private final AutonomousTaskApprovalAdvisor autonomousTaskApprovalAdvisor =
            new AutonomousTaskApprovalAdvisor(toolRegistry);

    PermissionRequestFactoryTest() {
        toolRegistry.registerBuiltinTool(BuiltinTool.builder()
                .id("builtin.file.delete")
                .name("删除文件")
                .description("删除文件或目录，支持递归删除非空目录")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.HIGH)
                .budget(ToolBudget.DEFAULT)
                .executor(_ -> ToolResult.success(Map.of()))
                .build());
        toolRegistry.registerBuiltinTool(BuiltinTool.builder()
                .id("builtin.code.execute")
                .name("执行代码")
                .description("在沙箱环境中执行代码，支持 Python/JavaScript/Shell")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.HIGH)
                .budget(ToolBudget.DEFAULT)
                .executor(_ -> ToolResult.success(Map.of()))
                .build());
    }

    @Test
    void 显式上下文优先覆盖推导出的任务与工作区() {
        var factory = new PermissionRequestFactory(
                new ObservabilityProperties(),
                new ToolConfigProperties(),
                autonomousTaskApprovalAdvisor,
                permissionScopeResolver
        );
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

    @Test
    void 创建定时任务时_任务级主体可从作用域回退解析() {
        var factory = new PermissionRequestFactory(
                new ObservabilityProperties(),
                new ToolConfigProperties(),
                autonomousTaskApprovalAdvisor,
                permissionScopeResolver
        );
        var tool = BuiltinTool.builder()
                .id("builtin.cron.create")
                .name("创建定时任务")
                .description("创建任务")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.HIGH)
                .budget(ToolBudget.DEFAULT)
                .executor(_ -> ToolResult.success(Map.of()))
                .build();
        var input = new ToolInput(
                tool.id(),
                Map.of("name", "daily-report"),
                JsonSchema.empty(),
                null,
                Map.of(ToolContextKeys.CHANNEL_TYPE, "web")
        );

        var request = factory.create(tool, input, "trace-2");

        assertThat(request.taskId()).isNull();
        assertThat(request.subjectId(com.lifepilot.permission.model.PermissionSubjectType.TASK))
                .isEqualTo("daily-report");
    }

    @Test
    void 运行时文件写入应归一到父目录工作区() {
        var factory = new PermissionRequestFactory(
                new ObservabilityProperties(),
                new ToolConfigProperties(),
                autonomousTaskApprovalAdvisor,
                permissionScopeResolver
        );
        var tool = BuiltinTool.builder()
                .id("builtin.file.write")
                .name("写入文件")
                .description("写入文件")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.HIGH)
                .budget(ToolBudget.DEFAULT)
                .executor(_ -> ToolResult.success(Map.of()))
                .build();
        var input = new ToolInput(
                tool.id(),
                Map.of("path", "D:/WorkSpace/Project/work/hello_output.txt", "content", "hello"),
                JsonSchema.empty(),
                null,
                Map.of(ToolContextKeys.CHANNEL_TYPE, "cron", ToolContextKeys.SESSION_ID, "cron:task-1")
        );

        var request = factory.create(tool, input, "trace-3");

        assertThat(request.taskId()).isEqualTo("task-1");
        assertThat(request.resourceScope().get("workspacePath")).isEqualTo("D:/WorkSpace/Project/work");
    }

    @Test
    void 创建高风险定时任务时_应标记需要任务级预授权() {
        var factory = new PermissionRequestFactory(
                new ObservabilityProperties(),
                new ToolConfigProperties(),
                autonomousTaskApprovalAdvisor,
                permissionScopeResolver
        );
        var tool = BuiltinTool.builder()
                .id("builtin.cron.create")
                .name("创建定时任务")
                .description("创建任务")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .budget(ToolBudget.DEFAULT)
                .executor(_ -> ToolResult.success(Map.of()))
                .build();
        var input = new ToolInput(
                tool.id(),
                Map.of(
                        "taskId", "task-risk-1",
                        "instruction", "执行 Python Hello World 代码，并将结果写入 D:/WorkSpace/Project/work/hello_output.txt"
                ),
                JsonSchema.empty(),
                null,
                Map.of(ToolContextKeys.CHANNEL_TYPE, "web")
        );

        var request = factory.create(tool, input, "trace-4");

        assertThat(request.requiresAutonomousPreAuthorization()).isTrue();
        assertThat(request.resourceScope().get("taskId")).isEqualTo("task-risk-1");
    }
}
