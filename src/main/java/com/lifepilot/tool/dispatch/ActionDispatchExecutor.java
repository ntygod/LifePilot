package com.lifepilot.tool.dispatch;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.ToolExecutor;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * action 参数路由执行器基类。
 *
 * <p>子类只需注册 action 到处理器的映射，基类负责读取 action 参数、校验并分发。</p>
 *
 * @author zsg
 * @since 2026-03-31
 */
public abstract class ActionDispatchExecutor implements ToolExecutor {

    private final Map<String, ActionHandler> handlers = new LinkedHashMap<>();
    private final Map<String, ActionMetadata> metadata = new LinkedHashMap<>();

    /**
     * 注册 action 处理器。
     *
     * @param action action 名称
     * @param riskLevel action 风险等级
     * @param executionSemantics action 执行语义
     * @param handler 处理器
     */
    protected void register(String action,
                            RiskLevel riskLevel,
                            ToolExecutionSemantics executionSemantics,
                            ActionHandler handler) {
        handlers.put(action, handler);
        metadata.put(action, new ActionMetadata(riskLevel, executionSemantics));
    }

    /**
     * 获取已注册 action 的元数据。
     *
     * @return action → 元数据映射
     */
    public Map<String, ActionMetadata> actionMetadata() {
        return Map.copyOf(metadata);
    }

    /**
     * 获取已注册 action 名称集合。
     *
     * @return action 名称集合
     */
    public Set<String> actions() {
        return Set.copyOf(handlers.keySet());
    }

    /**
     * 从输入中解析 action。
     *
     * @param input 工具输入
     * @return action 名称
     */
    protected String resolveAction(ToolInput input) {
        return input.getParam("action", String.class);
    }

    /**
     * 查找指定 action 的元数据。
     *
     * @param action action 名称
     * @return action 元数据
     */
    public Optional<ActionMetadata> metadataOf(String action) {
        return Optional.ofNullable(metadata.get(action));
    }

    @Override
    public ToolResult execute(ToolInput input) {
        String action;
        try {
            action = resolveAction(input);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        var handler = handlers.get(action);
        if (handler == null) {
            return ToolResult.error("不支持的操作: " + action + "，可选: " + handlers.keySet());
        }
        return handler.handle(input);
    }
}
