package com.lifepilot.llm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.history.ChatHistoryAssembler;
import com.lifepilot.llm.profile.MultiTurnHistoryRules;
import com.lifepilot.llm.profile.ProviderProfileRegistry;
import com.lifepilot.llm.thinking.AnthropicThinkingProtocol;
import com.lifepilot.llm.thinking.DeepSeekThinkingProtocol;
import com.lifepilot.llm.thinking.NoopThinkingProtocol;
import com.lifepilot.llm.thinking.OpenAiReasoningEffortProtocol;
import com.lifepilot.llm.thinking.QwenThinkingProtocol;
import com.lifepilot.llm.thinking.RequestBuilder;
import com.lifepilot.llm.thinking.ThinkingMode;
import com.lifepilot.llm.thinking.ThinkingProtocol;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeepSeek Provider 协议契约集成测试（Phase 10）。
 *
 * <p>覆盖三块契约：
 * <ol>
 *   <li>{@link DeepSeekThinkingProtocol#applyToRequest} 三态 — 已由
 *       {@code DeepSeekThinkingProtocol_协议契约测试} 单元覆盖；本类补一个集成口径
 *       从 ENABLED → extra_body.thinking.type=enabled 的端到端断言；</li>
 *   <li>{@link DeepSeekThinkingProtocol#extractReasoning} 同步响应 message.reasoning_content
 *       提取（已实现）；</li>
 *   <li>{@link ChatHistoryAssembler} 在 DeepSeek 多轮规则下把 payload_json 的
 *       reasoning_content 注入到 ProviderMessage（多轮回传契约）。</li>
 * </ol>
 *
 * <p><b>已知未实现的契约口径</b>（{@code @Disabled} 标 placeholder）：
 *
 * <ul>
 *   <li>同步 {@code adapter.call(...)} 端到端 LlmResponse.reasoningContent 解析 —
 *       需要 raw HTTP 旁路获取 reasoning_content（Spring AI ChatResponse 抽象层不暴露
 *       原始 JSON）。Phase 4 chunkToEvents 简化版 + Phase 10 SSE 旁路这部分留作
 *       未来扩展，本测试以 {@code @Disabled} 标注，避免假阳。</li>
 *   <li>thinking_mode 经 RequestBuilder → ChatOptions / 出站 HTTP body 的实际下发 —
 *       当前 {@code ProviderChatOptionsFactory} 未读 thinkingProtocol，request body 中
 *       不会出现 extra_body.thinking 字段，同样以 {@code @Disabled} 标 placeholder。</li>
 * </ul>
 *
 * <p>这两条 placeholder 让 Phase 10 不假装"全打通"，把真正的工程缺口暴露在测试里，
 * 后续接入实测时按需补完。
 *
 * <p>其他 4 个 provider（Qwen / OpenAI / Anthropic / Ollama）的协议契约已由
 * Phase 2 的 {@code *ThinkingProtocol_协议契约测试} 在单元层覆盖（thinking_mode
 * 三态请求字段位 + 同步响应解析 + 多轮 history 注入），未单独提升到集成层 —
 * 边际收益低，留待实测接入时按需补。
 *
 * @author zsg
 * @since 2026-04-27
 */
@DisplayName("DeepSeek Provider 协议契约（集成）")
class DeepSeekProviderContractTest {

    /** 本地 mock HTTP 服务，每个测试启动随机端口。 */
    private HttpServer server;

    /** 最近一次请求的路径与 header 快照。 */
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final ConcurrentHashMap<String, String> lastHeaders = new ConcurrentHashMap<>();

    private ProviderAdapterFactory factory;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void 启动mock服务器并初始化工厂() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();

        ProviderProfileRegistry registry = new ProviderProfileRegistry();
        registry.init();
        List<ThinkingProtocol> protocols = List.of(
                new NoopThinkingProtocol(),
                new DeepSeekThinkingProtocol(),
                new QwenThinkingProtocol(),
                new OpenAiReasoningEffortProtocol(),
                new AnthropicThinkingProtocol()
        );
        factory = new ProviderAdapterFactory(List.of(), null, registry, protocols, null);
    }

    @AfterEach
    void 关闭mock服务器() {
        if (server != null) {
            server.stop(0);
        }
        lastHeaders.clear();
        lastPath.set(null);
        lastBody.set(null);
    }

    // ====== 协议契约（已实现，非 Disabled） ======

    @Test
    void DeepSeekThinkingProtocol_ENABLED_注入_extra_body_thinking_enabled() {
        var protocol = new DeepSeekThinkingProtocol();
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.ENABLED);

        // ENABLED → extra_body.thinking = {"type": "enabled"}
        assertThat(builder.extraBodyFields())
                .containsEntry("thinking", Map.of("type", "enabled"));
    }

    @Test
    void DeepSeekThinkingProtocol_DISABLED_注入_extra_body_thinking_disabled() {
        var protocol = new DeepSeekThinkingProtocol();
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.DISABLED);

        assertThat(builder.extraBodyFields())
                .containsEntry("thinking", Map.of("type", "disabled"));
    }

    @Test
    void DeepSeekThinkingProtocol_AUTO_不下发_thinking_字段_使用provider默认() {
        var protocol = new DeepSeekThinkingProtocol();
        var builder = new RequestBuilder();
        protocol.applyToRequest(builder, ThinkingMode.AUTO);

        // AUTO 等价"不下发，用 provider 默认"（DeepSeek 默认 enabled）
        assertThat(builder.extraBodyFields()).isEmpty();
    }

    @Test
    void DeepSeek_同步响应_message_reasoning_content_提取() throws Exception {
        var protocol = new DeepSeekThinkingProtocol();
        // 模拟 DeepSeek V4 同步响应：reasoning_content 平级于 content
        JsonNode response = mapper.readTree("""
                {
                  "choices": [{
                    "message": {
                      "content": "答案是 42",
                      "reasoning_content": "分析问题：先理解题意，再演绎得出答案"
                    }
                  }],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 5}
                }
                """);
        String reasoning = protocol.extractReasoning(response);
        assertThat(reasoning).isEqualTo("分析问题：先理解题意，再演绎得出答案");
    }

    @Test
    void DeepSeek_流式_chunk_delta_reasoning_content_提取() throws Exception {
        var protocol = new DeepSeekThinkingProtocol();
        JsonNode chunk = mapper.readTree("""
                {"choices":[{"delta":{"reasoning_content":"思考片段"}}]}
                """);
        assertThat(protocol.extractReasoning(chunk)).isEqualTo("思考片段");
    }

    @Test
    void 多轮history含reasoning_content时ChatHistoryAssembler输出ProviderMessage含字段() {
        var assembler = new ChatHistoryAssembler();
        var rules = MultiTurnHistoryRules.contentOnlyReasoning();
        var protocol = new DeepSeekThinkingProtocol();

        // 上一轮 assistant payload（对应 ChatTurnService.buildAssistantPayload 输出）
        var prevAssistant = Map.<String, Object>of(
                "role", "assistant",
                "content", "上一轮答案",
                "reasoning_content", "上一轮的思考过程"
        );
        var currentUser = Map.<String, Object>of(
                "role", "user",
                "content", "继续追问"
        );

        var msgs = assembler.assemble(List.of(prevAssistant, currentUser), rules, protocol);

        assertThat(msgs).hasSize(2);
        var assistantMsg = msgs.get(0);
        assertThat(assistantMsg.role()).isEqualTo("assistant");
        assertThat(assistantMsg.content()).isEqualTo("上一轮答案");
        // 多轮回传契约：assistant 消息必须携带上一轮的 reasoning_content
        // 否则 DeepSeek V4 在第二轮会返回 400: "reasoning_content must be passed back"
        assertThat(assistantMsg.reasoningContent()).isEqualTo("上一轮的思考过程");
    }

    @Test
    void 多轮history缺reasoning_content时补dummy占位避免400() {
        var assembler = new ChatHistoryAssembler();
        var rules = MultiTurnHistoryRules.contentOnlyReasoning();
        var protocol = new DeepSeekThinkingProtocol();

        // 旧消息（thinking_mode=disabled 下产出的）payload 不含 reasoning_content
        var prevAssistant = Map.<String, Object>of(
                "role", "assistant",
                "content", "纯答案"
        );

        var msgs = assembler.assemble(List.of(prevAssistant), rules, protocol);

        // 缺 reasoning_content 时补空字符串占位 — DeepSeek 多轮契约要求字段必须存在
        assertThat(msgs.get(0).reasoningContent()).isEqualTo("");
    }

    @Test
    void DeepSeekProviderAdapter_工厂路由可发起HTTP调用_baseUrl与apiKey注入到OpenAI兼容请求() throws Exception {
        // 起 mock /v1/chat/completions 接收 POST，吞下请求 body 给后续断言
        server.createContext("/v1/chat/completions", exchange -> {
            捕获请求快照(exchange);
            String body = """
                    {
                      "id": "chatcmpl-test",
                      "object": "chat.completion",
                      "model": "deepseek-v4-pro",
                      "choices": [{
                        "index": 0,
                        "message": {"role": "assistant", "content": "ok"},
                        "finish_reason": "stop"
                      }],
                      "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
                    }
                    """;
            写响应(exchange, 200, body);
        });

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ProviderConfig config = new ProviderConfig(
                "deepseek-test",
                "deepseek-official",
                baseUrl,
                "sk-deepseek-test",
                "deepseek-v4-pro",
                30,
                0,
                List.of("default"),
                Set.of(ProviderCapability.CHAT),
                true,
                0,
                0,
                0,
                null,
                true,
                true,            // isReasoning
                ThinkingMode.ENABLED
        );

        var adapter = factory.create(config);
        // call 走 OpenAI 兼容路径 → 实际 HTTP POST 到 mock /v1/chat/completions
        var response = adapter.call("ping", null, Duration.ofSeconds(10));

        assertThat(response.content()).isEqualTo("ok");
        assertThat(lastPath.get()).isEqualTo("/v1/chat/completions");
        assertThat(lastHeaders.get("Authorization")).isEqualTo("Bearer sk-deepseek-test");
        // 请求 body 必含 model 字段（OpenAI 兼容协议基础契约）
        JsonNode reqBody = mapper.readTree(lastBody.get());
        assertThat(reqBody.path("model").asText()).isEqualTo("deepseek-v4-pro");
        assertThat(reqBody.path("messages").isArray()).isTrue();
    }

    // ====== Placeholder：未实现路径标 @Disabled ======

    @Test
    @Disabled("Phase 10 简化：thinking_mode 经 ChatOptions/HTTP body 实际下发链路未接 — "
            + "ProviderChatOptionsFactory 当前未读 ThinkingProtocol，需 RestClient 拦截器（"
            + "复用 AbstractJsonBodyRewritingStrategy 模式）合并 RequestBuilder.extraBodyFields 到出站 JSON body。"
            + "留作未来扩展，目前协议层契约已由 DeepSeekThinkingProtocol_协议契约测试 + 本类前置 case 覆盖")
    void thinking_mode_ENABLED时实际HTTP_body含extra_body_thinking_type_enabled() throws Exception {
        // 期望：adapter.call() 实际发出的 HTTP body 含 {"thinking":{"type":"enabled"}} 或 extra_body
        // 实现：需要在 OpenAiApi.Builder 加 webClientBuilder/restClientBuilder 拦截器
        //       从 RequestBuilder.extraBodyFields() 取字段合并到出站 JSON
    }

    @Test
    @Disabled("Phase 10 简化：同步 LlmResponse.reasoningContent 端到端解析未实现 — "
            + "AbstractProviderAdapter.toLlmResponse 通过 Spring AI ChatResponse.getResult().getOutput().getText() "
            + "拿不到原始 reasoning_content 字段，需要 SSE/raw HTTP 旁路（参考 Open WebUI 的 merge_reasoning_content_in_choices）。"
            + "持久化链路已通：ChatTurnService.buildAssistantPayload + Phase 10 follow-up 暴露 MessageInfo.reasoningContent")
    void 同步响应path_LlmResponse_reasoningContent_字段填充() {
        // 期望：mock 返回 message.reasoning_content → adapter.call() 返回的 LlmResponse.reasoningContent 非空
        // 实现策略候选：（a）OpenAiApi 加 webClientBuilder filter 旁路解析响应 body；
        //            （b）放弃 Spring AI 路径，对推理模型走自定义 OpenAI HTTP 客户端
    }

    // ====== Helpers ======

    private void 捕获请求快照(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().getPath());
        lastHeaders.clear();
        exchange.getRequestHeaders().forEach((k, v) -> {
            if (!v.isEmpty()) {
                lastHeaders.put(k, v.getFirst());
            }
        });
        try (var in = exchange.getRequestBody();
             var buffer = new ByteArrayOutputStream()) {
            in.transferTo(buffer);
            lastBody.set(buffer.toString(StandardCharsets.UTF_8));
        }
    }

    private void 写响应(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
            os.flush();
        }
    }
}
