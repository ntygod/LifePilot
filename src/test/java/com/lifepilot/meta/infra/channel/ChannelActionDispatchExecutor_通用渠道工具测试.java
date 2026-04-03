package com.lifepilot.meta.infra.channel;

import com.lifepilot.interaction.config.BuiltinChannelCatalog;
import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.ChannelOperationDescriptor;
import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import com.lifepilot.interaction.runtime.ChannelOperationDispatcher;
import com.lifepilot.interaction.runtime.ChannelRuntimeProtocol;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeDeliveryRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeOperationRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeOperationResponse;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChannelActionDispatchExecutor} 通用渠道工具测试。
 *
 * <p>使用飞书操作描述验证通用渠道执行器的行为，确保与原 FeishuActionDispatchExecutor 功能等价。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class ChannelActionDispatchExecutor_通用渠道工具测试 {

    @Mock
    private ChannelOperationDispatcher operationDispatcher;

    @Mock
    private ChannelDeliveryDispatcher deliveryDispatcher;

    @Mock
    private ChannelInstanceService channelInstanceService;

    private ChannelActionDispatchExecutor executor;

    private ChannelInstance instance;

    /** 宽松 Schema，不做校验限制。 */
    private static final JsonSchema PERMISSIVE_SCHEMA = JsonSchema.of(Map.of("type", "object"));

    @BeforeEach
    void 初始化() {
        List<ChannelOperationDescriptor> ops = BuiltinChannelCatalog.feishuOperationDescriptors();
        executor = new ChannelActionDispatchExecutor(
                operationDispatcher, deliveryDispatcher, channelInstanceService, "feishu", ops);

        instance = new ChannelInstance(
                "feishu.test",
                "feishu",
                "feishu",
                "飞书测试",
                true,
                ChannelInstanceStatus.RUNNING,
                Map.of(),
                Map.of(ChannelRuntimeProtocol.INSTANCE_TOKEN_SECRET_KEY, "token-test"),
                null, null, null,
                Instant.parse("2026-04-03T10:00:00Z"),
                Instant.parse("2026-04-03T10:00:00Z")
        );
    }

    // ─── action 注册验证 ─────────────────────────────

    @Test
    void 应注册全部12个action() {
        Set<String> actions = executor.actions();
        assertThat(actions).hasSize(12);
        assertThat(actions).containsExactlyInAnyOrder(
                "send_message", "send_card", "reply_message",
                "update_message", "recall_message",
                "upload_file", "download_file",
                "create_group", "manage_members",
                "create_task", "create_document", "create_calendar_event"
        );
    }

    @Test
    void 每个action都有对应的元数据() {
        for (String action : executor.actions()) {
            assertThat(executor.metadataOf(action)).isPresent();
        }
    }

    // ─── send_message 测试 ───────────────────────────

    @Test
    void send_message_正常发送消息() {
        // given
        setupInstanceMock("feishu.test");
        var deliveryContent = new ChannelRuntimeDeliveryRequest.Content(
                "text", "你好", Map.of("text", "你好"));
        when(deliveryDispatcher.buildContent(any())).thenReturn(deliveryContent);

        var params = new LinkedHashMap<String, Object>();
        params.put("action", "send_message");
        params.put("instanceId", "feishu.test");
        params.put("targetId", "user-123");
        params.put("content", "你好");
        ToolInput input = buildInput(params);

        // when
        ToolResult result = executor.execute(input);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsKey("responseId");
        assertThat(result.data()).containsEntry("status", "sent");

        // 验证 deliveryDispatcher.deliver 被调用
        ArgumentCaptor<ChannelRuntimeDeliveryRequest> captor =
                ArgumentCaptor.forClass(ChannelRuntimeDeliveryRequest.class);
        verify(deliveryDispatcher).deliver(eq(instance), captor.capture());
        ChannelRuntimeDeliveryRequest delivery = captor.getValue();
        assertThat(delivery.instanceId()).isEqualTo("feishu.test");
        assertThat(delivery.target().userId()).isEqualTo("user-123");
    }

    // ─── send_card 测试 ─────────────────────────────

    @Test
    void send_card_应构建interactive类型content() {
        // given
        setupInstanceMock("feishu.test");
        String cardJson = "{\"header\":{\"title\":\"审批\"}}";

        var params = new LinkedHashMap<String, Object>();
        params.put("action", "send_card");
        params.put("instanceId", "feishu.test");
        params.put("targetId", "group-456");
        params.put("cardJson", cardJson);
        ToolInput input = buildInput(params);

        // when
        ToolResult result = executor.execute(input);

        // then
        assertThat(result.isSuccess()).isTrue();

        ArgumentCaptor<ChannelRuntimeDeliveryRequest> captor =
                ArgumentCaptor.forClass(ChannelRuntimeDeliveryRequest.class);
        verify(deliveryDispatcher).deliver(eq(instance), captor.capture());
        ChannelRuntimeDeliveryRequest delivery = captor.getValue();
        assertThat(delivery.content().type()).isEqualTo("interactive");
        assertThat(delivery.content().payload()).containsEntry("cardJson", cardJson);
    }

    // ─── update_message 测试 ────────────────────────

    @Test
    void update_message_应通过OperationDispatcher发送message_update操作() {
        // given
        setupInstanceMock("feishu.test");
        var opResponse = new ChannelRuntimeOperationResponse(
                true, "op-update-1", Map.of("updated", true), null);
        when(operationDispatcher.execute(eq(instance), any(ChannelRuntimeOperationRequest.class)))
                .thenReturn(opResponse);

        var params = new LinkedHashMap<String, Object>();
        params.put("action", "update_message");
        params.put("instanceId", "feishu.test");
        params.put("messageId", "msg-789");
        params.put("content", "更新后的内容");
        ToolInput input = buildInput(params);

        // when
        ToolResult result = executor.execute(input);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsEntry("success", true);

        ArgumentCaptor<ChannelRuntimeOperationRequest> captor =
                ArgumentCaptor.forClass(ChannelRuntimeOperationRequest.class);
        verify(operationDispatcher).execute(eq(instance), captor.capture());
        ChannelRuntimeOperationRequest opReq = captor.getValue();
        assertThat(opReq.operationType()).isEqualTo("message_update");
        assertThat(opReq.parameters()).containsEntry("messageId", "msg-789");
        assertThat(opReq.parameters()).containsEntry("content", "更新后的内容");
    }

    // ─── recall_message 测试 ────────────────────────

    @Test
    void recall_message_应发送message_recall操作() {
        // given
        setupInstanceMock("feishu.test");
        var opResponse = new ChannelRuntimeOperationResponse(
                true, "op-recall-1", Map.of("recalled", true), null);
        when(operationDispatcher.execute(eq(instance), any(ChannelRuntimeOperationRequest.class)))
                .thenReturn(opResponse);

        var params = new LinkedHashMap<String, Object>();
        params.put("action", "recall_message");
        params.put("instanceId", "feishu.test");
        params.put("messageId", "msg-to-recall");
        ToolInput input = buildInput(params);

        // when
        ToolResult result = executor.execute(input);

        // then
        assertThat(result.isSuccess()).isTrue();

        ArgumentCaptor<ChannelRuntimeOperationRequest> captor =
                ArgumentCaptor.forClass(ChannelRuntimeOperationRequest.class);
        verify(operationDispatcher).execute(eq(instance), captor.capture());
        assertThat(captor.getValue().operationType()).isEqualTo("message_recall");
        assertThat(captor.getValue().parameters()).containsEntry("messageId", "msg-to-recall");
    }

    // ─── create_group 测试 ──────────────────────────

    @Test
    void create_group_应发送group_create操作() {
        // given
        setupInstanceMock("feishu.test");
        var opResponse = new ChannelRuntimeOperationResponse(
                true, "op-group-1", Map.of("chatId", "oc_new123"), null);
        when(operationDispatcher.execute(eq(instance), any(ChannelRuntimeOperationRequest.class)))
                .thenReturn(opResponse);

        var params = new LinkedHashMap<String, Object>();
        params.put("action", "create_group");
        params.put("instanceId", "feishu.test");
        params.put("groupName", "项目讨论群");
        params.put("groupDescription", "用于讨论项目进展");
        ToolInput input = buildInput(params);

        // when
        ToolResult result = executor.execute(input);

        // then
        assertThat(result.isSuccess()).isTrue();

        ArgumentCaptor<ChannelRuntimeOperationRequest> captor =
                ArgumentCaptor.forClass(ChannelRuntimeOperationRequest.class);
        verify(operationDispatcher).execute(eq(instance), captor.capture());
        assertThat(captor.getValue().operationType()).isEqualTo("group_create");
        assertThat(captor.getValue().parameters()).containsEntry("groupName", "项目讨论群");
    }

    // ─── 异常场景 ─────────────────────────────────

    @Test
    void 实例不存在_应返回ToolResult_error() {
        // given
        when(channelInstanceService.find("feishu.nonexist")).thenReturn(Optional.empty());

        var params = new LinkedHashMap<String, Object>();
        params.put("action", "send_message");
        params.put("instanceId", "feishu.nonexist");
        params.put("targetId", "user-123");
        params.put("content", "你好");
        ToolInput input = buildInput(params);

        // when
        ToolResult result = executor.execute(input);

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("渠道实例不存在");
    }

    @Test
    void 下游异常_send_message_应返回ToolResult_error() {
        // given
        setupInstanceMock("feishu.test");
        var deliveryContent = new ChannelRuntimeDeliveryRequest.Content(
                "text", "你好", Map.of("text", "你好"));
        when(deliveryDispatcher.buildContent(any())).thenReturn(deliveryContent);
        doThrow(new IllegalStateException("connector 不可达"))
                .when(deliveryDispatcher).deliver(any(), any());

        var params = new LinkedHashMap<String, Object>();
        params.put("action", "send_message");
        params.put("instanceId", "feishu.test");
        params.put("targetId", "user-123");
        params.put("content", "你好");
        ToolInput input = buildInput(params);

        // when
        ToolResult result = executor.execute(input);

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("发送消息");
    }

    @Test
    void 下游异常_update_message_应返回ToolResult_error() {
        // given
        setupInstanceMock("feishu.test");
        when(operationDispatcher.execute(eq(instance), any(ChannelRuntimeOperationRequest.class)))
                .thenThrow(new RuntimeException("网络超时"));

        var params = new LinkedHashMap<String, Object>();
        params.put("action", "update_message");
        params.put("instanceId", "feishu.test");
        params.put("messageId", "msg-fail");
        params.put("content", "更新内容");
        ToolInput input = buildInput(params);

        // when
        ToolResult result = executor.execute(input);

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("更新消息");
    }

    @Test
    void 不支持的action_应返回ToolResult_error() {
        // given
        var params = new LinkedHashMap<String, Object>();
        params.put("action", "unknown_action");
        params.put("instanceId", "feishu.test");
        ToolInput input = buildInput(params);

        // when
        ToolResult result = executor.execute(input);

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("不支持的操作");
    }

    @Test
    void 缺少action参数_应返回ToolResult_error() {
        // given
        var params = new LinkedHashMap<String, Object>();
        params.put("instanceId", "feishu.test");
        ToolInput input = buildInput(params);

        // when
        ToolResult result = executor.execute(input);

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("action");
    }

    // ─── reply_message 测试 ─────────────────────────

    @Test
    void reply_message_应通过deliveryDispatcher投递回复() {
        // given
        setupInstanceMock("feishu.test");
        var deliveryContent = new ChannelRuntimeDeliveryRequest.Content(
                "text", "收到", Map.of("text", "收到"));
        when(deliveryDispatcher.buildContent(any())).thenReturn(deliveryContent);

        var params = new LinkedHashMap<String, Object>();
        params.put("action", "reply_message");
        params.put("instanceId", "feishu.test");
        params.put("messageId", "msg-parent-001");
        params.put("content", "收到");
        ToolInput input = buildInput(params);

        // when
        ToolResult result = executor.execute(input);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsEntry("status", "sent");

        ArgumentCaptor<ChannelRuntimeDeliveryRequest> captor =
                ArgumentCaptor.forClass(ChannelRuntimeDeliveryRequest.class);
        verify(deliveryDispatcher).deliver(eq(instance), captor.capture());
        ChannelRuntimeDeliveryRequest delivery = captor.getValue();
        assertThat(delivery.metadata()).containsEntry("parentMessageId", "msg-parent-001");
    }

    // ─── 通用渠道能力验证 ──────────────────────────

    @Test
    void 自定义操作描述_应正确注册和路由() {
        // given — 模拟一个最简单的自定义操作
        var customOp = new ChannelOperationDescriptor(
                "custom_action", "自定义操作", "执行自定义操作",
                Map.of("param1", Map.of("type", "string", "description", "参数1")),
                List.of("param1"),
                RiskLevel.LOW, false, "custom_op_type",
                null
        );
        var customExecutor = new ChannelActionDispatchExecutor(
                operationDispatcher, deliveryDispatcher, channelInstanceService, "custom", List.of(customOp));

        assertThat(customExecutor.actions()).containsExactly("custom_action");
        assertThat(customExecutor.metadataOf("custom_action")).isPresent();
        assertThat(customExecutor.metadataOf("custom_action").get().riskLevel()).isEqualTo(RiskLevel.LOW);
    }

    // ─── 辅助方法 ───────────────────────────────────

    private void setupInstanceMock(String instanceId) {
        when(channelInstanceService.find(instanceId)).thenReturn(Optional.of(instance));
    }

    private ToolInput buildInput(Map<String, Object> params) {
        return new ToolInput("channel_tool", params, PERMISSIVE_SCHEMA, null, null);
    }
}
