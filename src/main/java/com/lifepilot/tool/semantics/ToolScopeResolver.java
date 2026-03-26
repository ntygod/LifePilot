package com.lifepilot.tool.semantics;

import com.lifepilot.tool.model.ToolInput;

/**
 * 工具资源解析器。
 *
 * <p>工具显式声明如何从输入中提取权限作用域与调度资源，框架不再根据 toolId 或字段名猜测。</p>
 *
 * @author zsg
 * @since 2026-03-26
 */
@FunctionalInterface
public interface ToolScopeResolver {

    ToolScopeResolution resolve(ToolInput input);
}
