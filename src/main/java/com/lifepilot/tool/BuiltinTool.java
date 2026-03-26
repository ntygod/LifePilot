package com.lifepilot.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolLayer;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;

import java.util.List;

/**
 * Java 原生工具（Layer 3）。
 *
 * <p>通过 Spring Bean 扫描自动注册。编译时类型安全，
 * 性能最优（进程内调用，无序列化开销）。</p>
 *
 * @param id 工具唯一标识
 * @param name 工具显示名称
 * @param description 工具描述
 * @param inputSchema 输入参数 JSON Schema
 * @param outputSchema 输出类型 JSON Schema
 * @param riskLevel 风险等级
 * @param idempotent 是否幂等
 * @param executionSemantics 执行语义
 * @param budget 执行预算
 * @param tags 工具标签
 * @param exportable 是否可导出为 MCP 工具
 * @param category 工具所属元能力分组
 * @param executor 实际执行逻辑
 * @author zsg
 * @since 2026-02-24
 */
public record BuiltinTool(
        String id,
        String name,
        String description,
        JsonSchema inputSchema,
        JsonSchema outputSchema,
        RiskLevel riskLevel,
        boolean idempotent,
        ToolExecutionSemantics executionSemantics,
        ToolBudget budget,
        List<String> tags,
        boolean exportable,
        ToolCategory category,
        ToolExecutor executor
) implements ToolContract {

    @Override
    public ToolLayer layer() {
        return ToolLayer.JAVA_NATIVE;
    }

    @Override
    public ToolResult execute(ToolInput input) {
        return executor.execute(input);
    }

    /** Builder 模式构建 BuiltinTool。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * BuiltinTool 构建器。
     *
     * @author zsg
     * @since 2026-02-24
     */
    public static class Builder {
        private String id;
        private String name;
        private String description;
        private JsonSchema inputSchema = JsonSchema.empty();
        private JsonSchema outputSchema = JsonSchema.empty();
        private RiskLevel riskLevel = RiskLevel.LOW;
        private boolean idempotent = true;
        private ToolExecutionSemantics executionSemantics;
        private ToolBudget budget = ToolBudget.DEFAULT;
        private List<String> tags = List.of();
        private boolean exportable = false;
        private ToolCategory category = ToolCategory.ACTION;
        private ToolExecutor executor;

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder inputSchema(JsonSchema inputSchema) { this.inputSchema = inputSchema; return this; }
        public Builder outputSchema(JsonSchema outputSchema) { this.outputSchema = outputSchema; return this; }
        public Builder riskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; return this; }
        public Builder idempotent(boolean idempotent) { this.idempotent = idempotent; return this; }
        public Builder executionSemantics(ToolExecutionSemantics executionSemantics) {
            this.executionSemantics = executionSemantics;
            return this;
        }
        public Builder budget(ToolBudget budget) { this.budget = budget; return this; }
        public Builder tags(List<String> tags) { this.tags = List.copyOf(tags); return this; }
        public Builder exportable(boolean exportable) { this.exportable = exportable; return this; }
        public Builder category(ToolCategory category) { this.category = category; return this; }
        public Builder executor(ToolExecutor executor) { this.executor = executor; return this; }

        public BuiltinTool build() {
            if (executionSemantics == null) {
                throw new IllegalStateException("BuiltinTool 必须显式声明 executionSemantics");
            }
            return new BuiltinTool(id, name, description, inputSchema, outputSchema,
                    riskLevel, idempotent, executionSemantics, budget, List.copyOf(tags),
                    exportable, category, executor);
        }
    }
}
