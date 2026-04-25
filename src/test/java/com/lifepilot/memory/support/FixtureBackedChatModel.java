package com.lifepilot.memory.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 测试用 Spring AI {@link ChatModel} 替身：从 {@link LlmFixture} 读取预录响应，构造带
 * {@code tool_calls} 的 {@link ChatResponse}。
 *
 * <p>为什么需要这个类：B2 产出的 {@link FixtureBackedGenerationRouter} 只覆盖了
 * {@link com.lifepilot.generation.router.GenerationRouter#call} 路径；但 ReactAgentLoop
 * 的主循环走的是 {@code generationRouter.getChatModelWithInfo(...)} → Spring AI
 * {@link ChatModel#call(Prompt)}，该路径绕开 {@code GenerationRouter.call}，因此
 * 场景 E2E 测试必须在 ChatModel 层也提供 fixture 替身。</p>
 *
 * <p>匹配策略：
 * <ol>
 *   <li>从 {@link Prompt} 里取最后一条 {@link UserMessage}（优先 {@link Prompt#getUserMessage()}，
 *       兜底到 {@link Prompt#getInstructions()} 倒序扫描）</li>
 *   <li>将文本交给 {@link LlmFixture#matchAndRender(String)} 获取响应</li>
 *   <li>若 {@code tool_calls} JSON 非空 → 构造带 {@link AssistantMessage.ToolCall} 的 AssistantMessage</li>
 *   <li>否则 → 构造只含 {@code final} 文本的 AssistantMessage</li>
 * </ol>
 *
 * <p>tool_calls JSON 约定格式（与 fixture 定义一致）：
 * <pre>[
 *   { "id": "call_1", "name": "file.read", "arguments": "{\"path\":\"...\"}" }
 * ]</pre>
 * 其中 {@code id} 若缺失会自动生成 {@code fixture-call-{index}}，{@code arguments}
 * 允许是字符串或对象（对象会序列化为 JSON 字符串传给 ReactAgentLoop）。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class FixtureBackedChatModel implements ChatModel {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String TOOL_CALL_TYPE = "function";

    private final LlmFixture fixture;

    /**
     * 构造 fixture-backed ChatModel。
     *
     * @param fixture 已 {@link LlmFixture#load(String) load} 过的 fixture 实例
     */
    public FixtureBackedChatModel(LlmFixture fixture) {
        this.fixture = java.util.Objects.requireNonNull(fixture, "fixture");
    }

    /**
     * 非流式调用：从 fixture 查响应 → 构造 {@link ChatResponse}。
     *
     * <p>ReactAgentLoop 只依赖本方法，不走 streaming 路径（便捷入口 run() 固定使用
     * {@link com.lifepilot.agent.callback.NonStreamingCallback}）。若后续扩展需要流式
     * fixture，可重写 {@link #stream(Prompt)}。</p>
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        String lastUserText = extractLastUserText(prompt);
        LlmFixture.FixtureResponse response = fixture.matchAndRender(lastUserText);

        List<AssistantMessage.ToolCall> toolCalls = parseToolCalls(response.toolCallsJson());
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content(response.finalText() != null ? response.finalText() : "")
                .toolCalls(toolCalls)
                .build();

        return new ChatResponse(List.of(new Generation(assistantMessage)));
    }

    /**
     * 从 {@link Prompt} 中提取最后一条用户消息文本，作为 fixture 匹配的输入。
     *
     * <p>Spring AI 1.1.3 的 {@link Prompt#getUserMessage()} 在 {@link Prompt#getInstructions()}
     * 含用户消息时返回非空；系统消息与历史助手消息会被忽略，只看最新的用户输入。</p>
     */
    private String extractLastUserText(Prompt prompt) {
        UserMessage userMessage = prompt.getUserMessage();
        if (userMessage != null && userMessage.getText() != null) {
            return userMessage.getText();
        }
        // 兜底：倒序扫 instructions，命中第一个 UserMessage 文本
        List<Message> instructions = prompt.getInstructions();
        if (instructions != null) {
            for (int i = instructions.size() - 1; i >= 0; i--) {
                if (instructions.get(i) instanceof UserMessage um && um.getText() != null) {
                    return um.getText();
                }
            }
        }
        return "";
    }

    /**
     * 解析 fixture 中的 {@code tool_calls} JSON 字符串为 Spring AI
     * {@link AssistantMessage.ToolCall} 列表。
     *
     * <p>空字符串、{@code null} 或 {@code []} 均返回空列表，表示本轮是纯文本终止。</p>
     *
     * @param toolCallsJson LlmFixture 渲染后的 tool_calls JSON 数组字符串
     * @return ToolCall 列表；空表示无工具调用
     * @throws IllegalStateException JSON 无法解析或结构不符合预期
     */
    private List<AssistantMessage.ToolCall> parseToolCalls(String toolCallsJson) {
        if (toolCallsJson == null || toolCallsJson.isBlank() || "[]".equals(toolCallsJson.trim())) {
            return List.of();
        }
        try {
            JsonNode arr = OM.readTree(toolCallsJson);
            if (!arr.isArray() || arr.isEmpty()) {
                return List.of();
            }
            var result = new ArrayList<AssistantMessage.ToolCall>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                JsonNode node = arr.get(i);
                String id = node.has("id") && !node.get("id").isNull()
                        ? node.get("id").asText()
                        : "fixture-call-" + i;
                String name = node.get("name").asText();
                String arguments = extractArguments(node.get("arguments"));
                result.add(new AssistantMessage.ToolCall(id, TOOL_CALL_TYPE, name, arguments));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "fixture tool_calls JSON 解析失败: " + toolCallsJson, e);
        }
    }

    /**
     * 从 {@code arguments} 节点提取 JSON 字符串。
     *
     * <p>兼容两种写法：字符串字面量（如 {@code "arguments": "{\"k\":1}"}）与嵌套对象
     * （如 {@code "arguments": {"k": 1}}）。后者会被序列化为紧凑 JSON 字符串，以对齐
     * Spring AI 工具执行器对 {@code arguments} 字段的字符串期望。</p>
     */
    private String extractArguments(JsonNode argsNode) {
        if (argsNode == null || argsNode.isNull()) {
            return "{}";
        }
        if (argsNode.isTextual()) {
            return argsNode.asText();
        }
        // 嵌套对象/数组：序列化为紧凑 JSON
        try {
            if (argsNode.isObject()) {
                Map<String, Object> map = OM.convertValue(argsNode, new TypeReference<>() {});
                return OM.writeValueAsString(map);
            }
            return OM.writeValueAsString(argsNode);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "fixture tool_calls.arguments 序列化失败: " + argsNode, e);
        }
    }
}
