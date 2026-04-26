package com.lifepilot.interaction.web.a2ui;

import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

import java.util.List;
import java.util.Map;

/**
 * A2UI 组件输出工具提供者。
 *
 * <p>注册 {@code ui.render} 内置工具，供 LLM 通过 tool call 提交结构化组件树。
 * 该工具通过 a2ui skill 的 suggested-tools 按需激活，未加载 skill 时不暴露。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class UiEmitToolProvider {

    private static final List<String> UI_TAGS = List.of("界面", "渲染", "组件", "前端", "交互", "卡片",
            "ui", "render", "component", "frontend", "widget");

    private final SseSessionManager sseManager;
    private final int maxComponentsPerTree;
    private final UiEmitTreeCapture treeCapture;

    /**
     * 构造函数。
     *
     * @param sseManager           SSE 会话管理器
     * @param maxComponentsPerTree 单个组件树允许的最大组件数
     * @param treeCapture          组件树捕获桥接器
     */
    public UiEmitToolProvider(SseSessionManager sseManager, int maxComponentsPerTree, UiEmitTreeCapture treeCapture) {
        this.sseManager = sseManager;
        this.maxComponentsPerTree = maxComponentsPerTree;
        this.treeCapture = treeCapture;
    }

    /**
     * 构建 ui.render 工具。
     *
     * @return ui.render BuiltinTool 实例
     */
    public BuiltinTool buildTool() {
        var executor = new UiEmitToolExecutor(sseManager, maxComponentsPerTree, treeCapture);
        return BuiltinTool.builder()
                .id("ui.render")
                .name("渲染交互组件")
                .description("渲染界面组件：向前端渲染交互式 UI 组件（按钮、表单、卡片、信号灯）。纯展示用 Markdown。")
                .category(ToolCategory.INTERACTION)
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("components"),
                        "properties", Map.of(
                                "components", Map.of(
                                        "type", "array",
                                        "description", "组件树节点列表",
                                        "items", Map.of(
                                                "type", "object",
                                                "required", List.of("id", "type", "properties"),
                                                "properties", Map.of(
                                                        "id", Map.of("type", "string",
                                                                "description", "组件唯一标识"),
                                                        "type", Map.of("type", "string",
                                                                "description", "组件类型（如 button、form、text）"),
                                                        "properties", Map.of("type", "object",
                                                                "description", "组件属性键值对"),
                                                        "children", Map.of("type", "array",
                                                                "description", "子组件 id 列表（可选）",
                                                                "items", Map.of("type", "string")),
                                                        "signal", Map.of("type", "object",
                                                                "description", "用户操作触发的信号定义（可选）",
                                                                "properties", Map.of(
                                                                        "name", Map.of("type", "string",
                                                                                "description", "信号名称"),
                                                                        "payload", Map.of("type", "object",
                                                                                "description", "信号附带数据")
                                                                ))
                                                )
                                        )
                                )
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ))
                .tags(UI_TAGS)
                .executor(executor::execute)
                .build();
    }
}
