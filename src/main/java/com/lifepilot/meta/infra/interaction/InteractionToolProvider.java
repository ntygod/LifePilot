package com.lifepilot.meta.infra.interaction;

import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;

import java.util.List;
import java.util.Map;

/**
 * 交互工具提供者 — 构建所有交互控制工具的 {@link BuiltinTool} 列表。
 *
 * <p>从 {@link com.lifepilot.meta.infra.InfraToolProvider} 中拆分出来，
 * 集中管理 3 个交互工具（choose / input / notify）的注册逻辑。</p>
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
     * 构建所有交互工具的 BuiltinTool 列表（3 个）。
     *
     * @return 交互工具列表
     */
    public List<BuiltinTool> buildInteractionTools() {
        return List.of(
                buildChooseTool(new ChooseToolExecutor(interactionBridge)),
                buildInputTool(new InputToolExecutor(interactionBridge)),
                buildNotifyTool(new NotifyToolExecutor(notificationService, notificationProperties))
        );
    }

    /** 构建选择工具 — 阻塞等待用户从选项列表中选择，LOW 风险。 */
    private BuiltinTool buildChooseTool(ChooseToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.interact.choose")
                .category(ToolCategory.INTERACTION)
                .name("请求用户选择")
                .description("向用户展示选项列表并请求选择，阻塞等待用户响应")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("message", "options"),
                        "properties", Map.of(
                                "message", Map.of("type", "string",
                                        "description", "选择提示消息"),
                                "options", Map.of("type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "可选项列表"),
                                "sessionId", Map.of("type", "string",
                                        "description", "当前会话 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.SEQUENTIAL))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建输入工具 — 阻塞等待用户自由文本输入，LOW 风险。 */
    private BuiltinTool buildInputTool(InputToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.interact.input")
                .category(ToolCategory.INTERACTION)
                .name("请求用户输入")
                .description("向用户展示输入提示并请求自由文本输入，阻塞等待用户响应")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("message"),
                        "properties", Map.of(
                                "message", Map.of("type", "string",
                                        "description", "输入提示消息"),
                                "sessionId", Map.of("type", "string",
                                        "description", "当前会话 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.SEQUENTIAL))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }

    /** 构建通知工具 — 非阻塞推送通知消息，LOW 风险。默认定向到当前会话渠道。 */
    private BuiltinTool buildNotifyTool(NotifyToolExecutor executor) {
        return BuiltinTool.builder()
                .id("builtin.interact.notify")
                .category(ToolCategory.INTERACTION)
                .name("推送通知")
                .description("向用户推送通知消息，非阻塞（不等待用户响应）。默认定向到当前会话渠道，也可显式指定 channel")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("message"),
                        "properties", Map.of(
                                "message", Map.of("type", "string",
                                        "description", "通知消息内容"),
                                "channel", Map.of("type", "string",
                                        "enum", List.of("WEB", "WECOM", "DINGTALK", "FEISHU"),
                                        "description", "可选，显式指定通知渠道；不传则默认使用当前会话渠道")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.PARALLEL_SAFE))
                .tags(INFRA_TAGS)
                .executor(executor::execute)
                .build();
    }
}
