package com.lifepilot.meta.infra.interaction;

import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
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
 * 交互工具提供者。
 *
 * <p>集中管理统一的 {@code interact} 元能力工具，通过 action 参数路由到
 * choose / input / notify 三类具体交互。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public class InteractionToolProvider {

    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final InteractionBridge interactionBridge;
    private final NotificationService notificationService;
    private final NotificationProperties notificationProperties;

    public InteractionToolProvider(InteractionBridge interactionBridge,
                                   NotificationService notificationService,
                                   NotificationProperties notificationProperties) {
        this.interactionBridge = interactionBridge;
        this.notificationService = notificationService;
        this.notificationProperties = notificationProperties;
    }

    /**
     * 构建交互工具列表（1 个）。
     *
     * @return 交互工具列表
     */
    public List<BuiltinTool> buildInteractionTools() {
        var executor = new InteractActionDispatchExecutor(
                new ChooseToolExecutor(interactionBridge),
                new InputToolExecutor(interactionBridge),
                new NotifyToolExecutor(notificationService, notificationProperties)
        );
        return List.of(buildInteractTool(executor));
    }

    /** 构建统一交互工具。 */
    private BuiltinTool buildInteractTool(InteractActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("interact")
                .category(ToolCategory.INTERACTION)
                .name("用户交互")
                .description("与用户进行交互。通过 action 参数支持三类操作：" +
                        "choose=展示选项并阻塞等待用户选择，" +
                        "input=请求自由文本输入并阻塞等待用户响应，" +
                        "notify=非阻塞推送通知消息。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action", "message"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("choose", "input", "notify"),
                                        "description", "交互动作类型")),
                                Map.entry("message", Map.of(
                                        "type", "string",
                                        "description", "交互提示消息或通知内容")),
                                Map.entry("options", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "action=choose 时的可选项列表")),
                                Map.entry("sessionId", Map.of(
                                        "type", "string",
                                        "description", "当前会话 ID；阻塞交互时可显式传入，默认优先取上下文")),
                                Map.entry("channel", Map.of(
                                        "type", "string",
                                        "enum", List.of("WEB", "WECOM", "DINGTALK", "FEISHU"),
                                        "description", "action=notify 时可显式指定通知渠道；不传则默认使用当前会话渠道"))
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ))
                .tags(INFRA_TAGS)
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }
}
