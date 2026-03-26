package com.lifepilot.permission.service;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自主任务高风险审批顾问测试。
 *
 * @author zsg
 * @since 2026-03-26
 */
class AutonomousTaskApprovalAdvisorTest {

    private final DynamicToolRegistry toolRegistry = new DynamicToolRegistry(_ -> {});
    private final AutonomousTaskApprovalAdvisor advisor = new AutonomousTaskApprovalAdvisor(toolRegistry);

    AutonomousTaskApprovalAdvisorTest() {
        register("builtin.file.delete", "删除文件",
                "删除文件或目录，支持递归删除非空目录", RiskLevel.HIGH);
        register("builtin.code.execute", "执行代码",
                "在沙箱环境中执行代码，支持 Python/JavaScript/Shell", RiskLevel.HIGH);
        register("builtin.file.read", "读取文件",
                "读取指定路径的文件内容", RiskLevel.LOW);
    }

    @Test
    void 删除目录任务应识别为需要审批() {
        assertThat(advisor.requiresApproval("检查目录 D:\\WorkSpace\\Project\\work，如果存在文件则删除所有文件"))
                .isTrue();
    }

    @Test
    void 执行代码并写结果任务应识别为需要审批() {
        assertThat(advisor.requiresApproval("执行 Python Hello World 代码，并将结果写入 hello_output.txt"))
                .isTrue();
    }

    @Test
    void 只读型定时任务不应触发高风险审批() {
        assertThat(advisor.requiresApproval("每天早上读取日志文件并汇总状态"))
                .isFalse();
    }

    private void register(String id, String name, String description, RiskLevel riskLevel) {
        toolRegistry.registerBuiltinTool(BuiltinTool.builder()
                .id(id)
                .name(name)
                .description(description)
                .inputSchema(JsonSchema.empty())
                .riskLevel(riskLevel)
                .budget(ToolBudget.DEFAULT)
                .executor(_ -> ToolResult.success(Map.of()))
                .build());
    }
}
