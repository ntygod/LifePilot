package com.lifepilot.meta.infra.channel;

import com.lifepilot.interaction.config.BuiltinChannelCatalog;
import com.lifepilot.interaction.model.ChannelOperationDescriptor;
import com.lifepilot.interaction.model.ChannelPluginDescriptor;
import com.lifepilot.interaction.model.ConnectorMode;
import com.lifepilot.interaction.registry.ChannelRegistry;
import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import com.lifepilot.interaction.runtime.ChannelOperationDispatcher;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChannelToolProvider} 通用渠道工具提供者测试。
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class ChannelToolProvider_通用渠道工具提供者测试 {

    @Mock
    private ChannelOperationDispatcher operationDispatcher;

    @Mock
    private ChannelDeliveryDispatcher deliveryDispatcher;

    @Mock
    private ChannelInstanceService channelInstanceService;

    private ChannelRegistry channelRegistry;
    private ChannelToolProvider toolProvider;

    @BeforeEach
    void 初始化() {
        channelRegistry = new ChannelRegistry();
        toolProvider = new ChannelToolProvider(
                channelRegistry, operationDispatcher, deliveryDispatcher, channelInstanceService);
    }

    @Test
    void 无操作描述的插件_不生成工具() {
        // Web UI 插件无 operationDescriptors
        channelRegistry.register(BuiltinChannelCatalog.webuiPlugin());

        List<BuiltinTool> tools = toolProvider.buildChannelTools();

        assertThat(tools).isEmpty();
    }

    @Test
    void 有操作描述的插件_生成对应工具() {
        var feishuPlugin = new ChannelPluginDescriptor(
                "feishu", "飞书", "1.0.0", "zhiwei", "feishu",
                ConnectorMode.EXTERNAL, null,
                List.of("receive", "send"),
                Map.of(), List.of(), null, null,
                BuiltinChannelCatalog.feishuOperationDescriptors()
        );
        channelRegistry.register(feishuPlugin);

        List<BuiltinTool> tools = toolProvider.buildChannelTools();

        assertThat(tools).hasSize(1);
        BuiltinTool tool = tools.getFirst();
        assertThat(tool.id()).isEqualTo("channel.feishu");
        assertThat(tool.name()).contains("飞书");
        // tags 英文化后包含 channel + platform + 通用同义词
        assertThat(tool.tags()).contains("channel", "feishu");
    }

    @Test
    void 工具描述包含所有action名称() {
        var feishuPlugin = new ChannelPluginDescriptor(
                "feishu", "飞书", "1.0.0", "zhiwei", "feishu",
                ConnectorMode.EXTERNAL, null,
                List.of("receive", "send"),
                Map.of(), List.of(), null, null,
                BuiltinChannelCatalog.feishuOperationDescriptors()
        );
        channelRegistry.register(feishuPlugin);

        List<BuiltinTool> tools = toolProvider.buildChannelTools();
        String description = tools.getFirst().description();

        assertThat(description).contains("send_message", "send_card", "reply_message",
                "update_message", "recall_message", "create_task");
    }

    @Test
    void inputSchema包含action和instanceId必填参数() {
        var feishuPlugin = new ChannelPluginDescriptor(
                "feishu", "飞书", "1.0.0", "zhiwei", "feishu",
                ConnectorMode.EXTERNAL, null,
                List.of("receive", "send"),
                Map.of(), List.of(), null, null,
                BuiltinChannelCatalog.feishuOperationDescriptors()
        );
        channelRegistry.register(feishuPlugin);

        List<BuiltinTool> tools = toolProvider.buildChannelTools();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tools.getFirst().inputSchema().toMap();

        assertThat(schema).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).containsExactly("action", "instanceId");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("action");
        assertThat(props).containsKey("instanceId");
        assertThat(props).containsKey("targetId");
        assertThat(props).containsKey("content");
    }

    @Test
    void 风险等级取所有操作中最高值() {
        // create_group 和 manage_members 是 HIGH，其余 MEDIUM/LOW
        var feishuPlugin = new ChannelPluginDescriptor(
                "feishu", "飞书", "1.0.0", "zhiwei", "feishu",
                ConnectorMode.EXTERNAL, null,
                List.of("receive", "send"),
                Map.of(), List.of(), null, null,
                BuiltinChannelCatalog.feishuOperationDescriptors()
        );
        channelRegistry.register(feishuPlugin);

        List<BuiltinTool> tools = toolProvider.buildChannelTools();

        assertThat(tools.getFirst().riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void 多个插件_各自生成独立工具() {
        var feishuPlugin = new ChannelPluginDescriptor(
                "feishu", "飞书", "1.0.0", "zhiwei", "feishu",
                ConnectorMode.EXTERNAL, null,
                List.of("receive", "send"),
                Map.of(), List.of(), null, null,
                BuiltinChannelCatalog.feishuOperationDescriptors()
        );
        var customOp = new ChannelOperationDescriptor(
                "send_message", "发送消息", "发送消息到钉钉",
                Map.of("targetId", Map.of("type", "string")),
                List.of("targetId"),
                RiskLevel.MEDIUM, true, null, null
        );
        var dingtalkPlugin = new ChannelPluginDescriptor(
                "dingtalk", "钉钉", "1.0.0", "zhiwei", "dingtalk",
                ConnectorMode.EXTERNAL, null,
                List.of("receive", "send"),
                Map.of(), List.of(), null, null,
                List.of(customOp)
        );
        channelRegistry.register(feishuPlugin);
        channelRegistry.register(dingtalkPlugin);

        List<BuiltinTool> tools = toolProvider.buildChannelTools();

        assertThat(tools).hasSize(2);
        assertThat(tools.stream().map(BuiltinTool::id).toList())
                .containsExactlyInAnyOrder("channel.feishu", "channel.dingtalk");
    }
}
