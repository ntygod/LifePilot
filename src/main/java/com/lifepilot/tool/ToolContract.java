package com.lifepilot.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolLayer;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;

import java.util.List;

/**
 * 工具契约 — ZhiWei 工具生态的核心抽象。
 *
 * <p>所有工具（无论来源）都必须实现此接口。sealed 修饰符确保
 * 工具类型在编译时完全已知，switch 表达式可以穷举匹配。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public sealed interface ToolContract permits BuiltinTool, SkillTool, McpTool {

    /** 工具唯一标识（全局唯一，格式：{namespace}.{name}）。 */
    String id();

    /** 工具显示名称。 */
    String name();

    /** 工具描述（供 LLM 理解工具用途）。 */
    String description();

    /** 输入参数的 JSON Schema。 */
    JsonSchema inputSchema();

    /** 输出类型的 JSON Schema。 */
    JsonSchema outputSchema();

    /** 风险等级。 */
    RiskLevel riskLevel();

    /** 是否幂等。 */
    boolean idempotent();

    /** 执行预算。 */
    ToolBudget budget();

    /** 工具所属层次。 */
    ToolLayer layer();

    /** 工具标签。 */
    List<String> tags();

    /** 是否可导出为 MCP 工具。 */
    default boolean exportable() {
        return false;
    }

    /**
     * 执行工具。
     *
     * <p>注意：此方法不应直接调用。应通过 ToolExecutionPipeline 执行。</p>
     *
     * @param input 类型安全的工具输入
     * @return 结构化的执行结果
     */
    ToolResult execute(ToolInput input);
}