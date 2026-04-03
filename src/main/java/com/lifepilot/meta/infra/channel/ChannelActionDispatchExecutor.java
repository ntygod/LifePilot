package com.lifepilot.meta.infra.channel;

import com.lifepilot.interaction.model.ChannelInstance;
import com.lifepilot.interaction.model.ChannelInstanceStatus;
import com.lifepilot.interaction.model.ChannelOperationDescriptor;
import com.lifepilot.interaction.model.DeliveryMode;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import com.lifepilot.interaction.runtime.ChannelOperationDispatcher;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeDeliveryRequest;
import com.lifepilot.interaction.runtime.model.ChannelRuntimeOperationRequest;
import com.lifepilot.interaction.service.ChannelInstanceService;
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
 * 通用渠道工具 action 路由执行器。
 *
 * <p>根据 {@link ChannelOperationDescriptor} 列表动态注册 action 处理器，
 * 投递类操作交给 {@link ChannelDeliveryDispatcher}，
 * 非投递类操作交给 {@link ChannelOperationDispatcher}。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
public class ChannelActionDispatchExecutor extends ActionDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChannelActionDispatchExecutor.class);

    private final ChannelOperationDispatcher operationDispatcher;
    private final ChannelDeliveryDispatcher deliveryDispatcher;
    private final ChannelInstanceService channelInstanceService;
    private final String platform;

    public ChannelActionDispatchExecutor(ChannelOperationDispatcher operationDispatcher,
                                         ChannelDeliveryDispatcher deliveryDispatcher,
                                         ChannelInstanceService channelInstanceService,
                                         String platform,
                                         List<ChannelOperationDescriptor> operationDescriptors) {
        this.operationDispatcher = operationDispatcher;
        this.deliveryDispatcher = deliveryDispatcher;
        this.channelInstanceService = channelInstanceService;
        this.platform = platform;

        for (var descriptor : operationDescriptors) {
            var schedulingMode = descriptor.deliveryAction()
                    ? ToolSchedulingMode.SEQUENTIAL
                    : resolveSchedulingMode(descriptor);

            register(descriptor.action(), descriptor.riskLevel(),
                    ToolExecutionSemantics.of(
                            PermissionActionType.GENERIC_TOOL_OPERATION,
                            schedulingMode,
                            ToolScopeResolvers.none()
                    ),
                    input -> descriptor.deliveryAction()
                            ? handleDeliveryAction(descriptor, input)
                            : handleOperationAction(descriptor, input));
        }
    }

    // ─────────────────────────────────────────────
    //  投递类操作处理
    // ─────────────────────────────────────────────

    /**
     * 处理投递类操作（send_message、send_card、reply_message 等）。
     *
     * <p>从 input 中提取 targetId、content、msgType 等参数，构建
     * {@link ChannelRuntimeDeliveryRequest} 交给 deliveryDispatcher 投递。</p>
     */
    private ToolResult handleDeliveryAction(ChannelOperationDescriptor descriptor, ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String responseId = generateResponseId();

            // 从 input 提取投递目标参数
            String targetId = input.getOptionalParam("targetId", String.class).orElse(null);
            String content = input.getOptionalParam("content", String.class).orElse(null);
            String msgType = input.getOptionalParam("msgType", String.class).orElse("text");

            // 检查必填参数
            for (String required : descriptor.requiredParams()) {
                String value = input.getOptionalParam(required, String.class).orElse(null);
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                            "%s 操作要求 %s 参数".formatted(descriptor.action(), required));
                }
            }

            // 构建投递内容
            ChannelRuntimeDeliveryRequest.Content deliveryContent;
            if ("interactive".equals(msgType) || descriptor.action().contains("card")) {
                String cardJson = input.getOptionalParam("cardJson", String.class).orElse(content);
                deliveryContent = new ChannelRuntimeDeliveryRequest.Content(
                        "interactive", cardJson, Map.of("cardJson", cardJson != null ? cardJson : ""));
            } else if (content != null) {
                var responseContent = new ResponseContent.TextContent(content);
                deliveryContent = deliveryDispatcher.buildContent(responseContent);
            } else {
                deliveryContent = new ChannelRuntimeDeliveryRequest.Content("text", "", Map.of());
            }

            // 构建目标 attributes
            var targetAttributes = new LinkedHashMap<String, Object>();
            input.getOptionalParam("receiveIdType", String.class)
                    .ifPresent(v -> targetAttributes.put("receiveIdType", v));
            targetAttributes.put("msgType", msgType);

            var target = new ChannelRuntimeDeliveryRequest.Target(
                    targetId, null, Map.copyOf(targetAttributes));

            // 构建 metadata（从 deliveryMeta 映射规则和 input 中提取）
            var metadata = new LinkedHashMap<String, Object>();
            if (descriptor.deliveryMeta() != null) {
                for (var entry : descriptor.deliveryMeta().entrySet()) {
                    String paramName = entry.getKey();
                    String metaKey = entry.getValue();
                    input.getOptionalParam(paramName, Object.class)
                            .ifPresent(v -> metadata.put(metaKey, v));
                }
            }

            var deliveryRequest = new ChannelRuntimeDeliveryRequest(
                    instance.instanceId(), responseId, DeliveryMode.ASYNC_PUSH,
                    target, deliveryContent, List.of(), Map.copyOf(metadata));

            deliveryDispatcher.deliver(instance, deliveryRequest);
            log.info("渠道消息已发送: platform={}, instanceId={}, action={}",
                    platform, instance.instanceId(), descriptor.action());
            return ToolResult.success(Map.of("responseId", responseId, "status", "sent"));
        } catch (Exception e) {
            log.warn("渠道投递操作失败: platform={}, action={}, error={}",
                    platform, descriptor.action(), e.getMessage());
            return ToolResult.error("%s %s 失败: %s".formatted(
                    platform, descriptor.name(), e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────
    //  非投递类操作处理
    // ─────────────────────────────────────────────

    /**
     * 处理非投递类操作（update_message、recall_message、upload_file 等）。
     *
     * <p>从 input 中提取操作参数，构建
     * {@link ChannelRuntimeOperationRequest} 交给 operationDispatcher 执行。</p>
     */
    private ToolResult handleOperationAction(ChannelOperationDescriptor descriptor, ToolInput input) {
        try {
            var instance = resolveInstance(input);
            String operationId = generateOperationId();

            // 收集操作参数：从 descriptor 声明的参数 schema 中提取
            var params = new LinkedHashMap<String, Object>();
            for (String paramName : descriptor.parameterSchema().keySet()) {
                input.getOptionalParam(paramName, Object.class)
                        .ifPresent(v -> params.put(paramName, v));
            }

            // 检查必填参数
            for (String required : descriptor.requiredParams()) {
                if (!params.containsKey(required) || params.get(required) == null) {
                    throw new IllegalArgumentException(
                            "%s 操作要求 %s 参数".formatted(descriptor.action(), required));
                }
                if (params.get(required) instanceof String s && s.isBlank()) {
                    throw new IllegalArgumentException(
                            "%s 操作要求 %s 参数".formatted(descriptor.action(), required));
                }
            }

            String operationType = descriptor.operationType() != null
                    ? descriptor.operationType()
                    : descriptor.action();

            var request = new ChannelRuntimeOperationRequest(
                    instance.instanceId(), operationId,
                    operationType, Map.copyOf(params), null);

            var response = operationDispatcher.execute(instance, request);
            log.info("渠道操作已执行: platform={}, instanceId={}, action={}, operationType={}",
                    platform, instance.instanceId(), descriptor.action(), operationType);
            return ToolResult.success(Map.of(
                    "operationId", response.operationId(),
                    "success", response.success(),
                    "result", response.result()
            ));
        } catch (Exception e) {
            log.warn("渠道操作执行失败: platform={}, action={}, error={}",
                    platform, descriptor.action(), e.getMessage());
            return ToolResult.error("%s %s 失败: %s".formatted(
                    platform, descriptor.name(), e.getMessage()));
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
     * @throws IllegalStateException    实例未启用或未运行
     */
    private ChannelInstance resolveInstance(ToolInput input) {
        String instanceId = input.getParam("instanceId", String.class);
        ChannelInstance instance = channelInstanceService.find(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("渠道实例不存在: " + instanceId));
        if (!instance.enabled()) {
            throw new IllegalStateException("渠道实例未启用: " + instanceId);
        }
        if (instance.status() == ChannelInstanceStatus.STOPPED
                || instance.status() == ChannelInstanceStatus.STOPPING) {
            throw new IllegalStateException("渠道实例未运行: " + instanceId
                    + ", 当前状态: " + instance.status());
        }
        return instance;
    }

    /**
     * 根据操作描述推导调度模式。
     *
     * <p>只读类操作（如 download_file）可并行调度，其余默认串行。</p>
     */
    private ToolSchedulingMode resolveSchedulingMode(ChannelOperationDescriptor descriptor) {
        String action = descriptor.action();
        if (action.startsWith("download_") || action.startsWith("get_") || action.startsWith("list_")) {
            return ToolSchedulingMode.PARALLEL_SAFE;
        }
        return ToolSchedulingMode.SEQUENTIAL;
    }

    /** 生成唯一响应 ID。 */
    private String generateResponseId() {
        return platform + "-resp-" + UUID.randomUUID();
    }

    /** 生成唯一操作 ID。 */
    private String generateOperationId() {
        return platform + "-op-" + UUID.randomUUID();
    }
}
