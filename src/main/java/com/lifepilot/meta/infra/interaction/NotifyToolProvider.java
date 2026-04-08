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
 * 通知工具提供者。
 *
 * <p>管理独立的 {@code notify} 工具，通过 {@link NotificationService} 向用户推送非阻塞通知。
 * 与 {@code channel} 工具的区别：notify 面向"告知用户"（系统自动路由渠道），
 * channel 面向"发到指定渠道实例"（显式控制目标）。</p>
 *
 * @author zsg
 * @since 2026-04-07
 */
public class NotifyToolProvider {

    private static final List<String> INFRA_TAGS = List.of("infrastructure");

    private final NotificationService notificationService;
    private final NotificationProperties notificationProperties;

    public NotifyToolProvider(NotificationService notificationService,
                              NotificationProperties notificationProperties) {
        this.notificationService = notificationService;
        this.notificationProperties = notificationProperties;
    }

    /**
     * 构建通知工具列表（1 个）。
     *
     * @return 通知工具列表
     */
    public List<BuiltinTool> buildNotifyTools() {
        var executor = new NotifyToolExecutor(notificationService, notificationProperties);
        return List.of(buildNotifyTool(executor));
    }

    /** 构建通知工具。 */
    private BuiltinTool buildNotifyTool(NotifyToolExecutor executor) {
        return BuiltinTool.builder()
                .id("notify")
                .category(ToolCategory.INTERACTION)
                .name("推送通知")
                .description("向用户推送通知消息（非阻塞）。" +
                        "适用于定时任务结果、后台任务完成提醒、告警等场景。" +
                        "正常对话中直接在回复里说即可，不需要此工具。" +
                        "向特定渠道/群组发消息请用 channel。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("message"),
                        "properties", Map.of(
                                "message", Map.of("type", "string",
                                        "description", "通知内容"),
                                "channel", Map.of("type", "string",
                                        "description", "指定通知渠道；不传则使用当前会话渠道")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.none()
                ))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }
}
