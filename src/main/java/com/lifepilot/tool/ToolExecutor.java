package com.lifepilot.tool;

import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;

/**
 * 工具执行器函数式接口。
 *
 * <p>BuiltinTool 的实际执行逻辑通过此接口注入。
 * 实现约束：不应抛出受检异常，不应自行处理超时和重试，应保证线程安全。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * 执行工具逻辑。
     *
     * @param input 已通过 Schema 校验的工具输入
     * @return 结构化执行结果
     */
    ToolResult execute(ToolInput input);
}
