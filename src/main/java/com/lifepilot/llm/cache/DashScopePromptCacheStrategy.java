package com.lifepilot.llm.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.io.IOException;

/**
 * DashScope (百炼) 显式 prompt 缓存策略。
 *
 * <p>DashScope 对 qwen 系列支持显式 prompt caching: 需要把 OpenAI 兼容协议里
 * {@code messages[role=system].content} 从纯字符串改成结构化内容数组
 * {@code [{"type":"text","text":"...","cache_control":{"type":"ephemeral"}}]},
 * 百炼看到 {@code cache_control} 标记就对该块强制缓存, 命中按标准单价 10% 计费, TTL 5 分钟。
 * Spring AI 的 {@code SystemMessage(String)} 默认发纯字符串, 不触发显式缓存,
 * 本策略在 HTTP 边界改写 request body。</p>
 *
 * <p>两端覆盖:</p>
 * <ul>
 *   <li>{@link #restClientInterceptor()} — 非流式调用 ({@code ChatModel.call}) 走 RestClient;</li>
 *   <li>{@link #webClientFilter()} — 流式调用 ({@code ChatModel.stream}) 走 WebClient。</li>
 * </ul>
 *
 * <p>DashScope 限制单请求最多 4 个 cache_control marker, 且最小缓存块 1024 tokens —
 * 短 prompt 标了也不会触发, 不影响正确性。通常只有一条 system message, 标完即退。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public final class DashScopePromptCacheStrategy implements PromptCacheStrategy {

    private static final Logger log = LoggerFactory.getLogger(DashScopePromptCacheStrategy.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "dashscope";
    }

    @Override
    public ClientHttpRequestInterceptor restClientInterceptor() {
        return (request, body, execution) -> {
            if (body.length == 0) {
                return execution.execute(request, body);
            }
            byte[] modifiedBody;
            try {
                byte[] injected = injectCacheControl(body);
                if (injected == null) {
                    return execution.execute(request, body);
                }
                modifiedBody = injected;
            } catch (Exception e) {
                // 线程被外层 timeout / cancel 打断时也会落到这里 — 通过 interrupted 状态区分,
                // 避免把"外层超时"误报成"注入失败"
                if (Thread.currentThread().isInterrupted()
                        || e instanceof java.io.InterruptedIOException
                        || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IOException("请求被中断 (外层超时/取消)", e);
                }
                log.warn("DashScope cache_control 注入失败, 回退原始请求: error={}", e.getMessage());
                return execution.execute(request, body);
            }
            return execution.execute(request, modifiedBody);
        };
    }

    @Override
    public ExchangeFilterFunction webClientFilter() {
        return (ClientRequest request, ExchangeFunction next) -> {
            @SuppressWarnings({"rawtypes", "unchecked"})
            org.springframework.web.reactive.function.BodyInserter<?, ? super org.springframework.http.client.reactive.ClientHttpRequest> originalBody =
                    (org.springframework.web.reactive.function.BodyInserter) request.body();

            ClientRequest modifiedRequest = ClientRequest.from(request)
                    .body((outputMessage, context) -> {
                        DataBufferFactory bufferFactory = outputMessage.bufferFactory();
                        CapturingClientHttpRequest capture =
                                new CapturingClientHttpRequest(outputMessage, bufferFactory);
                        return originalBody.insert(capture, context)
                                .then(Mono.defer(() -> {
                                    byte[] originalBytes = capture.getCapturedBytes();
                                    byte[] bytesToWrite = originalBytes;
                                    try {
                                        byte[] modified = injectCacheControl(originalBytes);
                                        if (modified != null) {
                                            bytesToWrite = modified;
                                        }
                                    } catch (Exception e) {
                                        log.warn("DashScope cache_control 注入失败, 原样发送: {}",
                                                e.getMessage());
                                    }
                                    // 修正 Content-Length header 避免长度不一致
                                    HttpHeaders realHeaders = outputMessage.getHeaders();
                                    realHeaders.setContentLength(bytesToWrite.length);
                                    DataBuffer buffer = bufferFactory.wrap(bytesToWrite);
                                    return outputMessage.writeWith(Mono.just(buffer));
                                }));
                    })
                    .build();

            return next.exchange(modifiedRequest);
        };
    }

    /**
     * 解析 JSON 请求体, 把第一条 role=system message 的 content 从 String 改写为结构化数组
     * 含 cache_control ephemeral 标记。返回 {@code null} 表示无需改动 (非预期结构 / 已是数组 / 无 system)。
     *
     * @param body 原始请求体
     * @return 改写后的请求体, 或 {@code null} 表示跳过
     */
    @Nullable
    private static byte[] injectCacheControl(byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            return null;
        }
        JsonNode root = MAPPER.readTree(body);
        if (!root.isObject() || !root.has("messages")) {
            return null;
        }
        JsonNode messages = root.get("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            return null;
        }
        boolean mutated = false;
        for (JsonNode msg : (ArrayNode) messages) {
            if (!msg.isObject()) continue;
            JsonNode role = msg.get("role");
            if (role == null || !"system".equals(role.asText())) continue;
            JsonNode content = msg.get("content");
            if (content == null || !content.isTextual()) continue;
            String text = content.asText();
            if (text.isEmpty()) continue;

            ObjectNode msgObj = (ObjectNode) msg;
            ArrayNode contentArr = MAPPER.createArrayNode();
            ObjectNode block = MAPPER.createObjectNode();
            block.put("type", "text");
            block.put("text", text);
            ObjectNode ctrl = MAPPER.createObjectNode();
            ctrl.put("type", "ephemeral");
            block.set("cache_control", ctrl);
            contentArr.add(block);
            msgObj.set("content", contentArr);
            mutated = true;
            // 通常只有一条 system message, 标完即退; 多条理论可以各自打, 但 DashScope 限制
            // 单请求最多 4 个 cache_control marker, 保守只标第一条
            break;
        }
        return mutated ? MAPPER.writeValueAsBytes(root) : null;
    }
}
