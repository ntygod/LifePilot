package com.lifepilot.a2a.client;

import com.lifepilot.a2a.model.*;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 远程 A2A Agent 工具桥接工厂。
 *
 * <p>为每个已注册的远程 Agent 创建 BuiltinTool 实例，
 * 通过 DynamicToolRegistry 注册/注销。工具执行时调用
 * A2aClientService.sendMessage() 与远程 Agent 通信。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class RemoteAgentToolFactory {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentToolFactory.class);
    private static final String TOOL_ID_PREFIX = "a2a_remote_";

    private final A2aClientService clientService;
    private final DynamicToolRegistry toolRegistry;

    public RemoteAgentToolFactory(A2aClientService clientService, DynamicToolRegistry toolRegistry) {
        this.clientService = clientService;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 为远程 Agent 创建并注册 BuiltinTool。
     *
     * <p>工具 ID 格式：a2a_remote_{agentName}（agentName 转 snake_case）。
     * 执行时构建 A2aMessage 并调用 A2aClientService.sendMessage()。</p>
     *
     * @param agentUrl 远程 Agent 基础 URL
     * @param card     远程 Agent Card
     */
    public void registerRemoteTool(String agentUrl, A2aAgentCard card) {
        String toolId = toToolId(card.name());

        var inputSchema = JsonSchema.of(Map.of(
                "type", "object",
                "properties", Map.of(
                        "task", Map.of("type", "string", "description", "委托任务描述"),
                        "context", Map.of("type", "string", "description", "可选的额外上下文信息")
                ),
                "required", List.of("task")
        ));

        var tool = BuiltinTool.builder()
                .id(toolId)
                .name("远程 Agent: " + card.name())
                .description(card.description())
                .inputSchema(inputSchema)
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .tags(List.of("a2a", "remote"))
                .executor(input -> {
                    String task = input.getParam("task", String.class);
                    String context = input.getOptionalParam("context", String.class).orElse(null);

                    // 构建 A2aMessage
                    String textContent = context != null ? task + "\n\n上下文: " + context : task;
                    var message = new A2aMessage(
                            UUID.randomUUID().toString(),
                            A2aRole.USER,
                            List.of(new A2aPart.Text(textContent, null)),
                            null, null, null
                    );

                    // 调用远程 Agent
                    A2aTask result = clientService.sendMessage(agentUrl, message);

                    // 提取 Artifact 文本作为结果
                    String output = extractArtifactText(result);
                    if (result.status().state() == A2aTaskState.COMPLETED) {
                        return com.lifepilot.tool.model.ToolResult.success(Map.of("output", output));
                    } else {
                        return com.lifepilot.tool.model.ToolResult.error(
                                "远程 Agent 执行失败: " + output);
                    }
                })
                .build();

        toolRegistry.registerBuiltinTool(tool);
        log.info("远程 Agent 工具注册成功: toolId={}, agentUrl={}", toolId, agentUrl);
    }

    /**
     * 注销远程 Agent 对应的 BuiltinTool。
     *
     * @param agentName Agent Card 名称
     */
    public void unregisterRemoteTool(String agentName) {
        String toolId = toToolId(agentName);
        boolean removed = toolRegistry.unregisterBuiltinTool(toolId);
        if (removed) {
            log.info("远程 Agent 工具注销成功: toolId={}", toolId);
        } else {
            log.debug("远程 Agent 工具不存在，跳过注销: toolId={}", toolId);
        }
    }

    /**
     * 将 Agent Card name 转为 snake_case 工具 ID。
     *
     * <p>规则：空格/连字符替换为下划线，移除非字母数字下划线字符，
     * 大写字母前插入下划线，全部转小写，合并连续下划线。</p>
     *
     * @param agentName Agent 名称
     * @return 工具 ID（格式：a2a_remote_{snake_case_name}）
     */
    static String toToolId(String agentName) {
        // 空格和连字符替换为下划线
        String result = agentName.replaceAll("[\\s-]+", "_");
        // 大写字母前插入下划线（驼峰转 snake_case）
        result = result.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        // 移除非字母数字下划线字符
        result = result.replaceAll("[^a-zA-Z0-9_]", "");
        // 全部转小写
        result = result.toLowerCase();
        // 合并连续下划线
        result = result.replaceAll("_+", "_");
        // 移除首尾下划线
        result = result.replaceAll("^_|_$", "");
        return TOOL_ID_PREFIX + result;
    }

    /**
     * 从 A2aTask 的 Artifact 中提取文本内容。
     */
    private String extractArtifactText(A2aTask task) {
        if (task.artifacts() == null || task.artifacts().isEmpty()) {
            // 尝试从 status message 提取
            if (task.status().message() != null && !task.status().message().parts().isEmpty()) {
                return task.status().message().parts().stream()
                        .filter(p -> p instanceof A2aPart.Text)
                        .map(p -> ((A2aPart.Text) p).text())
                        .findFirst()
                        .orElse("无输出");
            }
            return "无输出";
        }
        return task.artifacts().stream()
                .flatMap(a -> a.parts().stream())
                .filter(p -> p instanceof A2aPart.Text)
                .map(p -> ((A2aPart.Text) p).text())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("无输出");
    }
}
