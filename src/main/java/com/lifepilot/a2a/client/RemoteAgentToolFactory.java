package com.lifepilot.a2a.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.a2a.model.*;
import com.lifepilot.agent.suspend.event.A2aTaskCompletedEvent;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

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
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public RemoteAgentToolFactory(A2aClientService clientService,
                                   DynamicToolRegistry toolRegistry,
                                   ApplicationEventPublisher eventPublisher,
                                   ObjectMapper objectMapper) {
        this.clientService = clientService;
        this.toolRegistry = toolRegistry;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
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
                .executionSemantics(ToolExecutionSemantics.generic(ToolSchedulingMode.SEQUENTIAL))
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

                    // 发布 A2aTaskCompletedEvent 以恢复挂起的 Agent
                    if (result.status().state().isTerminal()) {
                        publishTaskCompletedEvent(result);
                    }

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

    /** 发布远程 Task 完成事件，用于恢复挂起等待结果的 Agent。 */
    private void publishTaskCompletedEvent(A2aTask result) {
        try {
            String resultJson = objectMapper.writeValueAsString(result);
            eventPublisher.publishEvent(new A2aTaskCompletedEvent(result.id(), resultJson));
            log.debug("A2A 远程任务完成事件已发布: taskId={}", result.id());
        } catch (Exception e) {
            log.warn("A2A 远程任务完成事件发布失败: taskId={}, error={}", result.id(), e.getMessage());
        }
    }

    /* visible for testing — 从 A2aTask 的 Artifact 中提取文本内容（支持 Text / File / Data 全部 Part 类型）。 */
    String extractArtifactText(A2aTask task) {
        if (task.artifacts() == null || task.artifacts().isEmpty()) {
            // 尝试从 status message 提取
            if (task.status().message() != null && !task.status().message().parts().isEmpty()) {
                return extractPartsText(task.status().message().parts());
            }
            return "无输出";
        }
        return task.artifacts().stream()
                .flatMap(a -> a.parts().stream())
                .map(this::partToText)
                .filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("无输出");
    }

    /** 从 Parts 列表中提取第一个有效文本。 */
    private String extractPartsText(java.util.List<A2aPart> parts) {
        return parts.stream()
                .map(this::partToText)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse("无输出");
    }

    /** 将单个 Part 转换为文本表示。 */
    private String partToText(A2aPart part) {
        return switch (part) {
            case A2aPart.Text text -> text.text();
            case A2aPart.File file -> {
                var fc = file.file();
                yield fc != null && fc.name() != null ? "[文件: " + fc.name() + "]" : "[文件]";
            }
            case A2aPart.Data data -> {
                try {
                    yield objectMapper.writeValueAsString(data.data());
                } catch (Exception e) {
                    yield "[结构化数据]";
                }
            }
        };
    }
}
