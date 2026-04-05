package com.lifepilot.interaction.config;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.ChannelOperationDescriptor;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.model.ConnectorMode;
import com.lifepilot.observability.guardrail.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 内建渠道目录。
 *
 * <p>当前仅注册 Web UI 本地渠道，作为后续统一控制面的第一号官方插件。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public final class BuiltinChannelCatalog {

    private BuiltinChannelCatalog() {
    }

    public static List<ChannelPluginDescriptor> builtinPlugins() {
        return List.of(webuiPlugin());
    }

    public static ChannelPluginDescriptor webuiPlugin() {
        return new ChannelPluginDescriptor(
                "webui",
                "Web UI",
                "1.0.0",
                "zhiwei",
                "web",
                ConnectorMode.LOCAL,
                Map.of("transport", "http+sse"),
                List.of("receive", "send", "sse-stream", "a2ui-signal"),
                Map.of(
                        "type", "object",
                        "properties", Map.of()
                ),
                List.of(),
                Map.of(
                        "title", "Web UI 内建渠道",
                        "steps", List.of("该渠道为系统内建本地渠道，无需额外配置。")
                ),
                null,
                null
        );
    }

    public static ChannelInstance webDefaultInstance() {
        Instant now = Instant.now();
        return new ChannelInstance(
                "web.default",
                "webui",
                "web",
                "默认 Web UI",
                true,
                ChannelInstanceStatus.CREATED,
                Map.of("transport", "http+sse"),
                null,
                null,
                null,
                null,
                now,
                now
        );
    }

    /**
     * 飞书渠道操作描述列表。
     *
     * <p>供飞书 connector 的 {@code channel-plugin.json} 声明操作列表，
     * 也可在测试或默认注册场景中直接使用。</p>
     *
     * @return 飞书 12 个操作描述
     */
    public static List<ChannelOperationDescriptor> feishuOperationDescriptors() {
        return List.of(
                new ChannelOperationDescriptor(
                        "send_message", "发送消息",
                        "发送文本/富文本消息到指定用户或群",
                        Map.ofEntries(
                                Map.entry("targetId", Map.of("type", "string", "description", "目标用户/群 ID")),
                                Map.entry("content", Map.of("type", "string", "description", "消息内容（文本或 JSON）")),
                                Map.entry("msgType", Map.of("type", "string", "enum", List.of("text", "post", "interactive"), "description", "消息类型", "default", "text")),
                                Map.entry("receiveIdType", Map.of("type", "string", "enum", List.of("chat_id", "open_id", "user_id"), "description", "目标 ID 类型，默认 chat_id", "default", "chat_id"))
                        ),
                        List.of("targetId", "content"),
                        RiskLevel.MEDIUM, true, null,
                        null
                ),
                new ChannelOperationDescriptor(
                        "send_card", "发送卡片",
                        "发送交互卡片到指定用户或群",
                        Map.ofEntries(
                                Map.entry("targetId", Map.of("type", "string", "description", "目标用户/群 ID")),
                                Map.entry("cardJson", Map.of("type", "string", "description", "飞书交互卡片 JSON")),
                                Map.entry("msgType", Map.of("type", "string", "description", "消息类型", "default", "interactive")),
                                Map.entry("receiveIdType", Map.of("type", "string", "enum", List.of("chat_id", "open_id", "user_id"), "description", "目标 ID 类型，默认 chat_id", "default", "chat_id"))
                        ),
                        List.of("targetId", "cardJson"),
                        RiskLevel.MEDIUM, true, null,
                        null
                ),
                new ChannelOperationDescriptor(
                        "reply_message", "回复消息",
                        "回复特定消息（thread reply）",
                        Map.ofEntries(
                                Map.entry("messageId", Map.of("type", "string", "description", "消息 ID（回复目标）")),
                                Map.entry("content", Map.of("type", "string", "description", "回复内容")),
                                Map.entry("msgType", Map.of("type", "string", "enum", List.of("text", "post", "interactive"), "description", "消息类型")),
                                Map.entry("replyInThread", Map.of("type", "boolean", "description", "是否以话题回复形式"))
                        ),
                        List.of("messageId", "content"),
                        RiskLevel.MEDIUM, true, null,
                        Map.of("messageId", "parentMessageId",
                                "msgType", "msgType",
                                "replyInThread", "replyInThread")
                ),
                new ChannelOperationDescriptor(
                        "update_message", "更新消息",
                        "更新已发送消息的内容",
                        Map.ofEntries(
                                Map.entry("messageId", Map.of("type", "string", "description", "消息 ID")),
                                Map.entry("content", Map.of("type", "string", "description", "更新后的内容")),
                                Map.entry("msgType", Map.of("type", "string", "enum", List.of("text", "post", "interactive"), "description", "消息类型"))
                        ),
                        List.of("messageId", "content"),
                        RiskLevel.MEDIUM, false, "message_update",
                        null
                ),
                new ChannelOperationDescriptor(
                        "recall_message", "撤回消息",
                        "撤回已发送消息",
                        Map.of("messageId", Map.of("type", "string", "description", "消息 ID")),
                        List.of("messageId"),
                        RiskLevel.MEDIUM, false, "message_recall",
                        null
                ),
                new ChannelOperationDescriptor(
                        "upload_file", "上传文件",
                        "上传文件到飞书",
                        Map.ofEntries(
                                Map.entry("fileName", Map.of("type", "string", "description", "文件名")),
                                Map.entry("fileData", Map.of("type", "string", "description", "Base64 编码的文件数据")),
                                Map.entry("fileType", Map.of("type", "string", "enum", List.of("image", "file"), "description", "文件类型"))
                        ),
                        List.of("fileName", "fileData"),
                        RiskLevel.MEDIUM, false, "file_upload",
                        null
                ),
                new ChannelOperationDescriptor(
                        "download_file", "下载文件",
                        "从飞书下载文件",
                        Map.of("fileToken", Map.of("type", "string", "description", "飞书文件 token")),
                        List.of("fileToken"),
                        RiskLevel.LOW, false, "file_download",
                        null
                ),
                new ChannelOperationDescriptor(
                        "create_group", "创建群聊",
                        "创建飞书群聊",
                        Map.ofEntries(
                                Map.entry("groupName", Map.of("type", "string", "description", "群名称")),
                                Map.entry("groupDescription", Map.of("type", "string", "description", "群描述"))
                        ),
                        List.of("groupName"),
                        RiskLevel.HIGH, false, "group_create",
                        null
                ),
                new ChannelOperationDescriptor(
                        "manage_members", "管理群成员",
                        "添加/移除群成员",
                        Map.ofEntries(
                                Map.entry("chatId", Map.of("type", "string", "description", "群 ID")),
                                Map.entry("memberIds", Map.of("type", "string", "description", "成员 ID 列表，逗号分隔")),
                                Map.entry("memberAction", Map.of("type", "string", "enum", List.of("add", "remove"), "description", "成员操作类型"))
                        ),
                        List.of("chatId", "memberIds", "memberAction"),
                        RiskLevel.HIGH, false, "group_manage_members",
                        null
                ),
                new ChannelOperationDescriptor(
                        "create_task", "创建任务",
                        "创建飞书任务",
                        Map.ofEntries(
                                Map.entry("taskSummary", Map.of("type", "string", "description", "任务标题")),
                                Map.entry("taskDueTimestamp", Map.of("type", "string", "description", "任务截止时间戳"))
                        ),
                        List.of("taskSummary"),
                        RiskLevel.MEDIUM, false, "task_create",
                        null
                ),
                new ChannelOperationDescriptor(
                        "create_document", "创建文档",
                        "创建飞书文档",
                        Map.ofEntries(
                                Map.entry("documentTitle", Map.of("type", "string", "description", "文档标题")),
                                Map.entry("documentFolderToken", Map.of("type", "string", "description", "目标文件夹 token"))
                        ),
                        List.of("documentTitle"),
                        RiskLevel.MEDIUM, false, "document_create",
                        null
                ),
                new ChannelOperationDescriptor(
                        "create_calendar_event", "创建日程",
                        "创建飞书日程",
                        Map.ofEntries(
                                Map.entry("eventSummary", Map.of("type", "string", "description", "日程标题")),
                                Map.entry("eventStartTime", Map.of("type", "string", "description", "日程开始时间，ISO 8601 格式")),
                                Map.entry("eventEndTime", Map.of("type", "string", "description", "日程结束时间，ISO 8601 格式")),
                                Map.entry("calendarId", Map.of("type", "string", "description", "日历 ID"))
                        ),
                        List.of("eventSummary", "eventStartTime", "eventEndTime"),
                        RiskLevel.MEDIUM, false, "calendar_event_create",
                        null
                )
        );
    }
}
