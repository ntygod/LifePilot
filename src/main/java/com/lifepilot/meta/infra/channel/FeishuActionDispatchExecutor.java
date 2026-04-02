package com.lifepilot.meta.infra.channel;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.DeliveryMode;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import com.lifepilot.interaction.runtime.ChannelOperationDispatcher;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeDeliveryRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeOperationRequest;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 飞书渠道工具 action 路由执行器。
 *
 * <p>统一承接飞书渠道的消息发送、群管理、文件操作、任务/文档/日程创建等动作，
 * 通过 {@code action} 参数路由到对应的处理逻辑。</p>
 *
 * <p>消息发送类操作（send_message、send_card、reply_message）通过
 * {@link ChannelDeliveryDispatcher} 投递，其余操作通过
 * {@link ChannelOperationDispatcher} 执行。</p>
 *
 * @author zsg
 * @since 2026-04-02
 */
public class FeishuActionDispatchExecutor extends ActionDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(FeishuActionDispatchExecutor.class);

    private final ChannelOperationDispatcher operationDispatcher;
    private final ChannelDeliveryDispatcher deliveryDispatcher;
    private final ChannelInstanceService channelInstanceService;

    public FeishuActionDispatchExecutor(ChannelOperationDispatcher operationDispatcher,
                                        ChannelDeliveryDispatcher deliveryDispatcher,
                                        ChannelInstanceService channelInstanceService) {
        this.operationDispatcher = operationDispatcher;
        this.deliveryDispatcher = deliveryDispatcher;
        this.channelInstanceService = channelInstanceService;

        // ── Phase 7: 飞书基础操作 ──

        register("send_message", RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ),
                this::handleSendMessage);

        register("send_card", RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ),
                this::handleSendCard);

        register("reply_message", RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ),
                this::handleReplyMessage);

        register("update_message", RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ),
                this::handleUpdateMessage);

        register("recall_message", RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ),
                this::handleRecallMessage);

        register("upload_file", RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ),
                this::handleUploadFile);

        register("download_file", RiskLevel.LOW,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        ToolScopeResolvers.none()
                ),
                this::handleDownloadFile);

        register("create_group", RiskLevel.HIGH,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ),
                this::handleCreateGroup);

        register("manage_members", RiskLevel.HIGH,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ),
                this::handleManageMembers);

        // ── Phase 8: 高级飞书 API 操作 ──

        register("create_task", RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ),
                this::handleCreateTask);

        register("create_document", RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ),
                this::handleCreateDocument);

        register("create_calendar_event", RiskLevel.MEDIUM,
                ToolExecutionSemantics.of(
                        PermissionActionType.GENERIC_TOOL_OPERATION,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.none()
                ),
                this::handleCreateCalendarEvent);
    }

    // ─────────────────────────────────────────────
    //  Phase 7: 飞书基础操作处理器
    // ─────────────────────────────────────────────

    /** 发送文本/富文本消息到指定用户或群。 */
    private ToolResult handleSendMessage(ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String targetId = input.getParam("targetId", String.class);
            String content = input.getParam("content", String.class);
            String msgType = input.getOptionalParam("msgType", String.class).orElse("text");
            String receiveIdType = input.getOptionalParam("receiveIdType", String.class).orElse("chat_id");

            var responseContent = buildTextContent(content);
            var deliveryContent = deliveryDispatcher.buildContent(responseContent);

            var targetAttributes = new LinkedHashMap<String, Object>();
            targetAttributes.put("receiveIdType", receiveIdType);
            targetAttributes.put("msgType", msgType);

            var target = new ChannelRuntimeDeliveryRequest.Target(
                    targetId, null, Map.copyOf(targetAttributes));
            String responseId = generateResponseId();

            var deliveryRequest = new ChannelRuntimeDeliveryRequest(
                    instance.instanceId(), responseId, DeliveryMode.ASYNC_PUSH,
                    target, deliveryContent, List.of(), Map.of());

            deliveryDispatcher.deliver(instance, deliveryRequest);
            log.info("飞书消息已发送: instanceId={}, targetId={}, msgType={}",
                    instance.instanceId(), targetId, msgType);
            return ToolResult.success(Map.of("responseId", responseId, "status", "sent"));
        } catch (Exception e) {
            log.warn("飞书消息发送失败: {}", e.getMessage());
            return ToolResult.error("飞书消息发送失败: " + e.getMessage());
        }
    }

    /** 发送交互卡片。 */
    private ToolResult handleSendCard(ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String targetId = input.getParam("targetId", String.class);
            String cardJson = input.getParam("cardJson", String.class);
            String receiveIdType = input.getOptionalParam("receiveIdType", String.class).orElse("chat_id");

            var deliveryContent = new ChannelRuntimeDeliveryRequest.Content(
                    "interactive", cardJson, Map.of("cardJson", cardJson));

            var targetAttributes = new LinkedHashMap<String, Object>();
            targetAttributes.put("receiveIdType", receiveIdType);
            targetAttributes.put("msgType", "interactive");

            var target = new ChannelRuntimeDeliveryRequest.Target(
                    targetId, null, Map.copyOf(targetAttributes));
            String responseId = generateResponseId();

            var deliveryRequest = new ChannelRuntimeDeliveryRequest(
                    instance.instanceId(), responseId, DeliveryMode.ASYNC_PUSH,
                    target, deliveryContent, List.of(), Map.of());

            deliveryDispatcher.deliver(instance, deliveryRequest);
            log.info("飞书卡片消息已发送: instanceId={}, targetId={}", instance.instanceId(), targetId);
            return ToolResult.success(Map.of("responseId", responseId, "status", "sent"));
        } catch (Exception e) {
            log.warn("飞书卡片消息发送失败: {}", e.getMessage());
            return ToolResult.error("飞书卡片消息发送失败: " + e.getMessage());
        }
    }

    /** 回复特定消息（thread reply）。 */
    private ToolResult handleReplyMessage(ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String messageId = input.getParam("messageId", String.class);
            String content = input.getParam("content", String.class);
            String msgType = input.getOptionalParam("msgType", String.class).orElse("text");
            boolean replyInThread = input.getOptionalParam("replyInThread", Boolean.class).orElse(false);

            var responseContent = buildTextContent(content);
            var deliveryContent = deliveryDispatcher.buildContent(responseContent);

            var target = new ChannelRuntimeDeliveryRequest.Target(null, null, Map.of());
            String responseId = generateResponseId();

            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("parentMessageId", messageId);
            metadata.put("msgType", msgType);
            metadata.put("replyInThread", replyInThread);

            var deliveryRequest = new ChannelRuntimeDeliveryRequest(
                    instance.instanceId(), responseId, DeliveryMode.ASYNC_PUSH,
                    target, deliveryContent, List.of(), Map.copyOf(metadata));

            deliveryDispatcher.deliver(instance, deliveryRequest);
            log.info("飞书消息回复已发送: instanceId={}, parentMessageId={}", instance.instanceId(), messageId);
            return ToolResult.success(Map.of("responseId", responseId, "status", "replied"));
        } catch (Exception e) {
            log.warn("飞书消息回复失败: {}", e.getMessage());
            return ToolResult.error("飞书消息回复失败: " + e.getMessage());
        }
    }

    /** 更新已发送消息的内容。 */
    private ToolResult handleUpdateMessage(ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String messageId = input.getParam("messageId", String.class);
            String content = input.getParam("content", String.class);
            String msgType = input.getOptionalParam("msgType", String.class).orElse("text");

            var params = Map.<String, Object>of(
                    "messageId", messageId,
                    "content", content,
                    "msgType", msgType
            );
            var request = new ChannelRuntimeOperationRequest(
                    instance.instanceId(), generateOperationId(),
                    "message_update", params, null);

            var response = operationDispatcher.execute(instance, request);
            log.info("飞书消息已更新: instanceId={}, messageId={}", instance.instanceId(), messageId);
            return ToolResult.success(Map.of(
                    "operationId", response.operationId(),
                    "success", response.success(),
                    "result", response.result()
            ));
        } catch (Exception e) {
            log.warn("飞书消息更新失败: {}", e.getMessage());
            return ToolResult.error("飞书消息更新失败: " + e.getMessage());
        }
    }

    /** 撤回已发送消息。 */
    private ToolResult handleRecallMessage(ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String messageId = input.getParam("messageId", String.class);

            var params = Map.<String, Object>of("messageId", messageId);
            var request = new ChannelRuntimeOperationRequest(
                    instance.instanceId(), generateOperationId(),
                    "message_recall", params, null);

            var response = operationDispatcher.execute(instance, request);
            log.info("飞书消息已撤回: instanceId={}, messageId={}", instance.instanceId(), messageId);
            return ToolResult.success(Map.of(
                    "operationId", response.operationId(),
                    "success", response.success(),
                    "result", response.result()
            ));
        } catch (Exception e) {
            log.warn("飞书消息撤回失败: {}", e.getMessage());
            return ToolResult.error("飞书消息撤回失败: " + e.getMessage());
        }
    }

    /** 上传文件到飞书。 */
    private ToolResult handleUploadFile(ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String fileName = input.getParam("fileName", String.class);
            String fileData = input.getParam("fileData", String.class);
            String fileType = input.getOptionalParam("fileType", String.class).orElse("file");

            var params = new LinkedHashMap<String, Object>();
            params.put("fileName", fileName);
            params.put("fileData", fileData);
            params.put("fileType", fileType);

            var request = new ChannelRuntimeOperationRequest(
                    instance.instanceId(), generateOperationId(),
                    "file_upload", Map.copyOf(params), null);

            var response = operationDispatcher.execute(instance, request);
            log.info("飞书文件已上传: instanceId={}, fileName={}", instance.instanceId(), fileName);
            return ToolResult.success(Map.of(
                    "operationId", response.operationId(),
                    "success", response.success(),
                    "result", response.result()
            ));
        } catch (Exception e) {
            log.warn("飞书文件上传失败: {}", e.getMessage());
            return ToolResult.error("飞书文件上传失败: " + e.getMessage());
        }
    }

    /** 从飞书下载文件。 */
    private ToolResult handleDownloadFile(ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String fileToken = input.getParam("fileToken", String.class);

            var params = Map.<String, Object>of("fileToken", fileToken);
            var request = new ChannelRuntimeOperationRequest(
                    instance.instanceId(), generateOperationId(),
                    "file_download", params, null);

            var response = operationDispatcher.execute(instance, request);
            log.info("飞书文件已下载: instanceId={}, fileToken={}", instance.instanceId(), fileToken);
            return ToolResult.success(Map.of(
                    "operationId", response.operationId(),
                    "success", response.success(),
                    "result", response.result()
            ));
        } catch (Exception e) {
            log.warn("飞书文件下载失败: {}", e.getMessage());
            return ToolResult.error("飞书文件下载失败: " + e.getMessage());
        }
    }

    /** 创建飞书群聊。 */
    private ToolResult handleCreateGroup(ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String groupName = input.getParam("groupName", String.class);
            String groupDescription = input.getOptionalParam("groupDescription", String.class).orElse("");

            var params = new LinkedHashMap<String, Object>();
            params.put("groupName", groupName);
            params.put("groupDescription", groupDescription);

            var request = new ChannelRuntimeOperationRequest(
                    instance.instanceId(), generateOperationId(),
                    "group_create", Map.copyOf(params), null);

            var response = operationDispatcher.execute(instance, request);
            log.info("飞书群聊已创建: instanceId={}, groupName={}", instance.instanceId(), groupName);
            return ToolResult.success(Map.of(
                    "operationId", response.operationId(),
                    "success", response.success(),
                    "result", response.result()
            ));
        } catch (Exception e) {
            log.warn("飞书群聊创建失败: {}", e.getMessage());
            return ToolResult.error("飞书群聊创建失败: " + e.getMessage());
        }
    }

    /** 添加/移除群成员。 */
    private ToolResult handleManageMembers(ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String chatId = input.getParam("chatId", String.class);
            String memberIds = input.getParam("memberIds", String.class);
            String memberAction = input.getParam("memberAction", String.class);

            var params = new LinkedHashMap<String, Object>();
            params.put("chatId", chatId);
            params.put("memberIds", memberIds);
            params.put("memberAction", memberAction);

            var request = new ChannelRuntimeOperationRequest(
                    instance.instanceId(), generateOperationId(),
                    "group_manage_members", Map.copyOf(params), null);

            var response = operationDispatcher.execute(instance, request);
            log.info("飞书群成员已管理: instanceId={}, chatId={}, action={}",
                    instance.instanceId(), chatId, memberAction);
            return ToolResult.success(Map.of(
                    "operationId", response.operationId(),
                    "success", response.success(),
                    "result", response.result()
            ));
        } catch (Exception e) {
            log.warn("飞书群成员管理失败: {}", e.getMessage());
            return ToolResult.error("飞书群成员管理失败: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  Phase 8: 高级飞书 API 操作处理器
    // ─────────────────────────────────────────────

    /** 创建飞书任务。 */
    private ToolResult handleCreateTask(ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String taskSummary = input.getParam("taskSummary", String.class);

            var params = new LinkedHashMap<String, Object>();
            params.put("taskSummary", taskSummary);
            input.getOptionalParam("taskDueTimestamp", String.class)
                    .ifPresent(v -> params.put("taskDueTimestamp", v));

            var request = new ChannelRuntimeOperationRequest(
                    instance.instanceId(), generateOperationId(),
                    "task_create", Map.copyOf(params), null);

            var response = operationDispatcher.execute(instance, request);
            log.info("飞书任务已创建: instanceId={}, taskSummary={}", instance.instanceId(), taskSummary);
            return ToolResult.success(Map.of(
                    "operationId", response.operationId(),
                    "success", response.success(),
                    "result", response.result()
            ));
        } catch (Exception e) {
            log.warn("飞书任务创建失败: {}", e.getMessage());
            return ToolResult.error("飞书任务创建失败: " + e.getMessage());
        }
    }

    /** 创建飞书文档。 */
    private ToolResult handleCreateDocument(ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String documentTitle = input.getParam("documentTitle", String.class);

            var params = new LinkedHashMap<String, Object>();
            params.put("documentTitle", documentTitle);
            input.getOptionalParam("documentFolderToken", String.class)
                    .ifPresent(v -> params.put("documentFolderToken", v));

            var request = new ChannelRuntimeOperationRequest(
                    instance.instanceId(), generateOperationId(),
                    "document_create", Map.copyOf(params), null);

            var response = operationDispatcher.execute(instance, request);
            log.info("飞书文档已创建: instanceId={}, documentTitle={}", instance.instanceId(), documentTitle);
            return ToolResult.success(Map.of(
                    "operationId", response.operationId(),
                    "success", response.success(),
                    "result", response.result()
            ));
        } catch (Exception e) {
            log.warn("飞书文档创建失败: {}", e.getMessage());
            return ToolResult.error("飞书文档创建失败: " + e.getMessage());
        }
    }

    /** 创建飞书日程。 */
    private ToolResult handleCreateCalendarEvent(ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String eventSummary = input.getParam("eventSummary", String.class);
            String eventStartTime = input.getParam("eventStartTime", String.class);
            String eventEndTime = input.getParam("eventEndTime", String.class);

            var params = new LinkedHashMap<String, Object>();
            params.put("eventSummary", eventSummary);
            params.put("eventStartTime", eventStartTime);
            params.put("eventEndTime", eventEndTime);
            input.getOptionalParam("calendarId", String.class)
                    .ifPresent(v -> params.put("calendarId", v));

            var request = new ChannelRuntimeOperationRequest(
                    instance.instanceId(), generateOperationId(),
                    "calendar_event_create", Map.copyOf(params), null);

            var response = operationDispatcher.execute(instance, request);
            log.info("飞书日程已创建: instanceId={}, eventSummary={}", instance.instanceId(), eventSummary);
            return ToolResult.success(Map.of(
                    "operationId", response.operationId(),
                    "success", response.success(),
                    "result", response.result()
            ));
        } catch (Exception e) {
            log.warn("飞书日程创建失败: {}", e.getMessage());
            return ToolResult.error("飞书日程创建失败: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  内部辅助方法
    // ─────────────────────────────────────────────

    /**
     * 根据 instanceId 参数解析渠道实例。
     *
     * @param input 工具输入
     * @return 渠道实例
     * @throws IllegalArgumentException 实例不存在
     */
    private ChannelInstance resolveInstance(ToolInput input) {
        String instanceId = input.getParam("instanceId", String.class);
        return channelInstanceService.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("飞书渠道实例不存在: " + instanceId));
    }

    /** 构建文本响应内容。 */
    private ResponseContent buildTextContent(String text) {
        return new ResponseContent.TextContent(text);
    }

    /** 生成唯一响应 ID。 */
    private String generateResponseId() {
        return "feishu-resp-" + UUID.randomUUID();
    }

    /** 生成唯一操作 ID。 */
    private String generateOperationId() {
        return "feishu-op-" + UUID.randomUUID();
    }
}
