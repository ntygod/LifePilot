package com.lifepilot.interaction.model;

import com.lifepilot.observability.guardrail.RiskLevel;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 渠道操作描述。
 *
 * <p>每个渠道插件可声明自己支持的操作列表，包含操作标识、名称、描述、
 * 参数 schema、风险等级、是否属于投递类操作等元信息。</p>
 *
 * <p>投递类操作（{@code deliveryAction=true}）通过 {@code ChannelDeliveryDispatcher} 投递，
 * 非投递类操作通过 {@code ChannelOperationDispatcher} 执行。</p>
 *
 * @param action          操作标识，如 "send_message"
 * @param name            操作名称，如 "发送消息"
 * @param description     操作描述，给 Agent 看的
 * @param parameterSchema JSON Schema 格式的参数定义（每个参数的 type/description/enum 等）
 * @param requiredParams  必填参数列表
 * @param riskLevel       风险等级
 * @param deliveryAction  true=通过 DeliveryDispatcher 投递, false=通过 OperationDispatcher 执行
 * @param operationType   非投递类操作的 operationType（传给 ChannelRuntimeOperationRequest），
 *                        投递类操作可为 null
 * @param deliveryMeta    投递类操作的元数据映射规则（参数名 -> metadata key），可为 null
 * @author zsg
 * @since 2026-04-03
 */
public record ChannelOperationDescriptor(
        String action,
        String name,
        String description,
        Map<String, Object> parameterSchema,
        List<String> requiredParams,
        RiskLevel riskLevel,
        boolean deliveryAction,
        @Nullable String operationType,
        @Nullable Map<String, String> deliveryMeta
) {

    public ChannelOperationDescriptor {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("操作标识 action 不能为空");
        }
        parameterSchema = parameterSchema != null ? Map.copyOf(parameterSchema) : Map.of();
        requiredParams = requiredParams != null ? List.copyOf(requiredParams) : List.of();
        riskLevel = riskLevel != null ? riskLevel : RiskLevel.MEDIUM;
        deliveryMeta = deliveryMeta != null ? Map.copyOf(deliveryMeta) : null;
    }
}
