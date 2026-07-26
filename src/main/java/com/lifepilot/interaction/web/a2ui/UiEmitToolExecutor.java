package com.lifepilot.interaction.web.a2ui;

import com.lifepilot.interaction.web.model.A2uiComponent;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.model.A2uiSignal;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.tool.ToolExecutor;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ui.render 工具执行器 — 接收 LLM 工具调用传入的 A2UI 组件树，校验后通过 SSE 推送到前端。
 *
 * <p>执行流程：</p>
 * <ol>
 *   <li>从上下文中提取 streamId / sessionId / turnId</li>
 *   <li>将参数中的 components 列表转换为 {@link A2uiComponent} record 列表</li>
 *   <li>构建 {@link A2uiComponentTree} 并通过 {@link A2uiComponentValidator} 校验</li>
 *   <li>校验通过后通过 {@link SseSessionManager} 推送 UI 事件</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-14
 */
public class UiEmitToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(UiEmitToolExecutor.class);

    private final SseSessionManager sseManager;
    private final int maxComponents;
    private final UiEmitTreeCapture treeCapture;

    /**
     * 构造函数。
     *
     * @param sseManager    SSE 会话管理器
     * @param maxComponents 单次允许推送的最大组件数
     * @param treeCapture   组件树捕获桥接器，用于将校验后的树传递给编排器持久化
     */
    public UiEmitToolExecutor(SseSessionManager sseManager, int maxComponents, UiEmitTreeCapture treeCapture) {
        this.sseManager = sseManager;
        this.maxComponents = maxComponents;
        this.treeCapture = treeCapture;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(ToolInput input) {
        // 1. 提取上下文字段
        var streamIdOpt = input.getContextValue(ToolContextKeys.STREAM_ID, String.class);
        if (streamIdOpt.isEmpty() || streamIdOpt.get().isBlank()) {
            log.warn("ui.render 缺少 streamId，无法推送 UI 事件");
            return ToolResult.error("缺少必需上下文字段: streamId");
        }
        String streamId = streamIdOpt.get();
        String sessionId = input.getContextValue(ToolContextKeys.SESSION_ID, String.class).orElse("");
        String turnId = input.getContextValue(ToolContextKeys.TURN_ID, String.class).orElse("");

        // 2. 提取 components 列表
        List<Map<String, Object>> rawComponents;
        try {
            rawComponents = (List<Map<String, Object>>) input.getParam("components", List.class);
        } catch (IllegalArgumentException e) {
            log.warn("ui.render 参数错误: {}", e.getMessage());
            return ToolResult.error("参数错误: " + e.getMessage());
        }

        if (rawComponents.isEmpty()) {
            return ToolResult.error("components 不能为空");
        }

        // 3. 将 Map 列表转换为 A2uiComponent record 列表
        List<A2uiComponent> components;
        try {
            components = convertComponents(rawComponents);
        } catch (Exception e) {
            log.warn("ui.render 组件转换失败: {}", e.getMessage());
            return ToolResult.error("组件格式错误: " + e.getMessage());
        }

        // 4. 构建组件树并校验
        var tree = new A2uiComponentTree(components);
        var validationResult = A2uiComponentValidator.validate(tree, maxComponents);

        if (!validationResult.valid()) {
            String errors = String.join("; ", validationResult.errors());
            log.warn("ui.render 组件树校验失败: streamId={}, errors={}", streamId, errors);
            return ToolResult.error("组件树校验失败: " + errors);
        }

        // 如果有截断则使用截断后的组件树
        A2uiComponentTree finalTree = validationResult.truncatedTree() != null
                ? validationResult.truncatedTree()
                : tree;

        // 5. 通过 SSE 推送 UI 事件
        Map<String, Object> uiData = Map.of(
                "sessionId", sessionId,
                "turnId", turnId,
                "components", finalTree.components()
        );
        sseManager.sendEvent(streamId, SseEventType.UI, uiData);

        // 捕获组件树，供编排器在 coreLoop 结束后读取并持久化
        treeCapture.capture(streamId, finalTree);

        int count = finalTree.components().size();
        log.info("ui.render 已推送 {} 个组件: streamId={}, sessionId={}, turnId={}",
                count, streamId, sessionId, turnId);

        return ToolResult.success(Map.of(
                "status", "emitted",
                "componentCount", count
        ));
    }

    /**
     * 将原始 Map 列表转换为 {@link A2uiComponent} record 列表。
     *
     * @param rawComponents 来自工具参数的原始组件列表
     * @return 转换后的 A2uiComponent 列表
     */
    @SuppressWarnings("unchecked")
    private List<A2uiComponent> convertComponents(List<Map<String, Object>> rawComponents) {
        var result = new ArrayList<A2uiComponent>(rawComponents.size());
        for (var raw : rawComponents) {
            if (raw == null) {
                throw new IllegalArgumentException("组件节点不能为 null");
            }
            String id = (String) raw.get("id");
            String type = (String) raw.get("type");

            // 解析 properties
            Object propertiesRaw = raw.get("properties");
            Map<String, Object> properties = propertiesRaw instanceof Map<?, ?> propertiesMap
                    ? (Map<String, Object>) propertiesMap
                    : Map.of();

            // 解析 children
            Object childrenRaw = raw.get("children");
            List<String> children = childrenRaw instanceof List<?> childrenList
                    ? (List<String>) childrenList
                    : List.of();

            // 解析 signal（可选）
            Object signalRaw = raw.get("signal");
            A2uiSignal signal = null;
            if (signalRaw instanceof Map<?, ?> signalMap) {
                String signalName = (String) signalMap.get("name");
                Object payloadRaw = signalMap.get("payload");
                Map<String, Object> payload = payloadRaw instanceof Map<?, ?> payloadMap
                        ? (Map<String, Object>) payloadMap
                        : Map.of();
                signal = new A2uiSignal(signalName, payload);
            }

            result.add(new A2uiComponent(id, type, properties, children, signal));
        }
        return result;
    }
}
