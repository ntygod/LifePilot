package com.lifepilot.meta.infra.channel;

import com.lifepilot.interaction.model.ChannelOperationDescriptor;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import com.lifepilot.interaction.runtime.ChannelOperationDispatcher;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 通用渠道工具提供者。
 *
 * <p>遍历 {@link ChannelRegistry} 中所有已注册插件，为每个声明了
 * {@link ChannelOperationDescriptor} 的插件动态生成一个 {@link BuiltinTool}。</p>
 *
 * <p>工具 ID 格式：{@code channel.{platform}}，如 {@code channel.feishu}、{@code channel.dingtalk}。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
public class ChannelToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ChannelToolProvider.class);

    private final ChannelRegistry channelRegistry;
    private final ChannelOperationDispatcher operationDispatcher;
    private final ChannelDeliveryDispatcher deliveryDispatcher;
    private final ChannelInstanceService channelInstanceService;

    public ChannelToolProvider(ChannelRegistry channelRegistry,
                               ChannelOperationDispatcher operationDispatcher,
                               ChannelDeliveryDispatcher deliveryDispatcher,
                               ChannelInstanceService channelInstanceService) {
        this.channelRegistry = channelRegistry;
        this.operationDispatcher = operationDispatcher;
        this.deliveryDispatcher = deliveryDispatcher;
        this.channelInstanceService = channelInstanceService;
    }

    /**
     * 为所有声明了操作描述的渠道插件构建工具列表。
     *
     * @return 渠道工具列表
     */
    public List<BuiltinTool> buildChannelTools() {
        List<BuiltinTool> tools = new ArrayList<>();
        for (var plugin : channelRegistry.listAll()) {
            List<ChannelOperationDescriptor> ops = plugin.operationDescriptors();
            if (ops == null || ops.isEmpty()) {
                log.debug("渠道插件 {} 未声明操作描述，跳过工具生成", plugin.pluginId());
                continue;
            }
            tools.add(buildChannelTool(plugin, ops));
            log.debug("已为渠道插件 {} 生成工具，操作数: {}", plugin.pluginId(), ops.size());
        }
        return List.copyOf(tools);
    }

    /**
     * 为单个渠道插件构建工具。
     */
    private BuiltinTool buildChannelTool(ChannelPluginDescriptor plugin,
                                          List<ChannelOperationDescriptor> ops) {
        String platform = plugin.platform();
        String toolId = "channel." + platform;
        List<String> tags = List.of("渠道", "消息", "发送", "通信", platform, "channel", "message");

        var executor = new ChannelActionDispatchExecutor(
                operationDispatcher, deliveryDispatcher, channelInstanceService, platform, ops);

        return BuiltinTool.builder()
                .id(toolId)
                .category(ToolCategory.ACTION)
                .name(plugin.name() + "渠道操作")
                .description(buildDescription(plugin, ops))
                .inputSchema(JsonSchema.of(buildInputSchema(ops)))
                .riskLevel(computeMaxRiskLevel(ops))
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.SEQUENTIAL))
                .tags(tags)
                .actionMetadataFrom(executor)
                .executor(executor)
                .build();
    }

    /**
     * 构建工具描述（含操作列表）。
     */
    private String buildDescription(ChannelPluginDescriptor plugin,
                                     List<ChannelOperationDescriptor> ops) {
        var sb = new StringBuilder();
        sb.append("操作 ").append(plugin.platform()).append(" 渠道，支持的 action：");

        var joiner = new StringJoiner(", ");
        for (var op : ops) {
            joiner.add(op.action());
        }
        sb.append(joiner);
        sb.append(".");
        return sb.toString();
    }

    /**
     * 从操作描述动态拼装 inputSchema。
     *
     * <p>所有操作共享 action 和 instanceId 参数，其余参数从各操作的 parameterSchema 中收集。</p>
     */
    private Map<String, Object> buildInputSchema(List<ChannelOperationDescriptor> ops) {
        // 收集所有 action enum
        List<String> actionEnums = ops.stream().map(ChannelOperationDescriptor::action).toList();

        // 构建 properties
        var properties = new LinkedHashMap<String, Object>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", actionEnums,
                "description", "操作类型"
        ));
        properties.put("instanceId", Map.of(
                "type", "string",
                "description", "渠道实例 ID"
        ));

        // 从每个操作的 parameterSchema 中收集参数（同名参数以首次出现的为准）
        for (var op : ops) {
            for (var entry : op.parameterSchema().entrySet()) {
                properties.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        return Map.of(
                "type", "object",
                "required", List.of("action", "instanceId"),
                "properties", Map.copyOf(properties)
        );
    }

    /**
     * 取所有操作中最高的风险等级作为工具默认风险等级。
     */
    private RiskLevel computeMaxRiskLevel(List<ChannelOperationDescriptor> ops) {
        RiskLevel max = RiskLevel.LOW;
        for (var op : ops) {
            if (op.riskLevel().ordinal() > max.ordinal()) {
                max = op.riskLevel();
            }
        }
        return max;
    }
}
