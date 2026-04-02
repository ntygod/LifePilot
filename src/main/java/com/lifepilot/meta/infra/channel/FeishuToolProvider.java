package com.lifepilot.meta.infra.channel;

import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import com.lifepilot.interaction.runtime.ChannelOperationDispatcher;
import com.lifepilot.interaction.service.ChannelInstanceService;
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
 * 飞书渠道工具提供者。
 *
 * <p>集中管理统一的 {@code channel.feishu} 元能力工具，通过 action 参数路由到
 * 消息发送、群管理、文件操作、任务/文档/日程创建等具体操作。</p>
 *
 * @author zsg
 * @since 2026-04-02
 */
public class FeishuToolProvider {

    private static final List<String> CHANNEL_TAGS = List.of("channel", "feishu");

    private final ChannelOperationDispatcher operationDispatcher;
    private final ChannelDeliveryDispatcher deliveryDispatcher;
    private final ChannelInstanceService channelInstanceService;

    public FeishuToolProvider(ChannelOperationDispatcher operationDispatcher,
                              ChannelDeliveryDispatcher deliveryDispatcher,
                              ChannelInstanceService channelInstanceService) {
        this.operationDispatcher = operationDispatcher;
        this.deliveryDispatcher = deliveryDispatcher;
        this.channelInstanceService = channelInstanceService;
    }

    /**
     * 构建飞书渠道工具列表（1 个）。
     *
     * @return 飞书工具列表
     */
    public List<BuiltinTool> buildFeishuTools() {
        var executor = new FeishuActionDispatchExecutor(
                operationDispatcher, deliveryDispatcher, channelInstanceService);
        return List.of(buildFeishuTool(executor));
    }

    /** 构建统一飞书渠道工具。 */
    private BuiltinTool buildFeishuTool(FeishuActionDispatchExecutor executor) {
        return BuiltinTool.builder()
                .id("channel.feishu")
                .category(ToolCategory.ACTION)
                .name("飞书渠道操作")
                .description("通过飞书渠道发送消息、管理群组、上传下载文件、创建任务/文档/日程等操作。" +
                        "通过 action 参数支持以下操作：" +
                        "send_message=发送文本/富文本消息到指定用户或群，" +
                        "send_card=发送交互卡片，" +
                        "reply_message=回复特定消息（thread reply），" +
                        "update_message=更新已发送消息的内容，" +
                        "recall_message=撤回已发送消息，" +
                        "upload_file=上传文件到飞书，" +
                        "download_file=从飞书下载文件，" +
                        "create_group=创建飞书群聊，" +
                        "manage_members=添加/移除群成员，" +
                        "create_task=创建飞书任务，" +
                        "create_document=创建飞书文档，" +
                        "create_calendar_event=创建飞书日程。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action", "instanceId"),
                        "properties", Map.ofEntries(
                                Map.entry("action", Map.of(
                                        "type", "string",
                                        "enum", List.of("send_message", "send_card", "reply_message",
                                                "update_message", "recall_message", "upload_file",
                                                "download_file", "create_group", "manage_members",
                                                "create_task", "create_document", "create_calendar_event"),
                                        "description", "操作类型")),
                                Map.entry("instanceId", Map.of(
                                        "type", "string",
                                        "description", "飞书渠道实例 ID，如 feishu.default")),
                                Map.entry("targetId", Map.of(
                                        "type", "string",
                                        "description", "目标用户/群 ID")),
                                Map.entry("receiveIdType", Map.of(
                                        "type", "string",
                                        "enum", List.of("chat_id", "open_id", "user_id"),
                                        "description", "目标 ID 类型，默认 chat_id")),
                                Map.entry("content", Map.of(
                                        "type", "string",
                                        "description", "消息内容（文本或 JSON）")),
                                Map.entry("msgType", Map.of(
                                        "type", "string",
                                        "enum", List.of("text", "post", "interactive"),
                                        "description", "消息类型")),
                                Map.entry("messageId", Map.of(
                                        "type", "string",
                                        "description", "消息 ID（更新/撤回/回复时使用）")),
                                Map.entry("cardJson", Map.of(
                                        "type", "string",
                                        "description", "飞书交互卡片 JSON（send_card 时使用）")),
                                Map.entry("fileName", Map.of(
                                        "type", "string",
                                        "description", "文件名")),
                                Map.entry("fileData", Map.of(
                                        "type", "string",
                                        "description", "Base64 编码的文件数据")),
                                Map.entry("fileType", Map.of(
                                        "type", "string",
                                        "enum", List.of("image", "file"),
                                        "description", "文件类型")),
                                Map.entry("fileToken", Map.of(
                                        "type", "string",
                                        "description", "飞书文件 token（下载时使用）")),
                                Map.entry("groupName", Map.of(
                                        "type", "string",
                                        "description", "群名称（创建群时使用）")),
                                Map.entry("groupDescription", Map.of(
                                        "type", "string",
                                        "description", "群描述")),
                                Map.entry("memberIds", Map.of(
                                        "type", "string",
                                        "description", "成员 ID 列表，逗号分隔")),
                                Map.entry("memberAction", Map.of(
                                        "type", "string",
                                        "enum", List.of("add", "remove"),
                                        "description", "成员操作类型")),
                                Map.entry("chatId", Map.of(
                                        "type", "string",
                                        "description", "群 ID（群管理时使用）")),
                                Map.entry("replyInThread", Map.of(
                                        "type", "boolean",
                                        "description", "是否以话题回复形式")),
                                Map.entry("taskSummary", Map.of(
                                        "type", "string",
                                        "description", "任务标题（create_task 时使用）")),
                                Map.entry("taskDueTimestamp", Map.of(
                                        "type", "string",
                                        "description", "任务截止时间戳（create_task 时使用）")),
                                Map.entry("documentTitle", Map.of(
                                        "type", "string",
                                        "description", "文档标题（create_document 时使用）")),
                                Map.entry("documentFolderToken", Map.of(
                                        "type", "string",
                                        "description", "目标文件夹 token（create_document 时使用）")),
                                Map.entry("eventSummary", Map.of(
                                        "type", "string",
                                        "description", "日程标题（create_calendar_event 时使用）")),
                                Map.entry("eventStartTime", Map.of(
                                        "type", "string",
                                        "description", "日程开始时间，ISO 8601 格式（create_calendar_event 时使用）")),
                                Map.entry("eventEndTime", Map.of(
                                        "type", "string",
                                        "description", "日程结束时间，ISO 8601 格式（create_calendar_event 时使用）")),
                                Map.entry("calendarId", Map.of(
                                        "type", "string",
                                        "description", "日历 ID（create_calendar_event 时使用）"))
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.SEQUENTIAL))
                .tags(CHANNEL_TAGS)
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }
}
