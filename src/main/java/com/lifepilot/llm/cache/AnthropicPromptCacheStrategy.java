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
 * Anthropic Claude 显式 prompt 缓存策略。
 *
 * <p>Anthropic Messages API 支持在 {@code system} / {@code tools} / {@code messages[].content[]}
 * 任意 block 上打 {@code cache_control: {type: ephemeral}}, 命中按原单价 10% 计费,
 * 缓存写入一次性收取 125% 的标准单价, TTL 5 分钟。本策略仅对最高影响面 —
 * 请求根的 {@code system} field 注入标记, 覆盖绝大多数长 system prompt 场景。</p>
 *
 * <p>body 改写规则:</p>
 * <ul>
 *   <li>{@code system} 为字符串: 转成
 *       {@code [{"type":"text","text":"...","cache_control":{"type":"ephemeral"}}]};</li>
 *   <li>{@code system} 为数组: 给最后一个 block 加 {@code cache_control: ephemeral}
 *       (若已存在则跳过, 避免覆盖调用方显式设置);</li>
 *   <li>没有 {@code system} field 或结构不识别: pass-through 不改。</li>
 * </ul>
 *
 * <p>两端覆盖:</p>
 * <ul>
 *   <li>{@link #restClientInterceptor()} — 非流式调用 ({@code AnthropicChatModel.call});</li>
 *   <li>{@link #webClientFilter()} — 流式调用 ({@code AnthropicChatModel.stream})。</li>
 * </ul>
 *
 * <p>响应命中信息 ({@code usage.cache_read_input_tokens} /
 * {@code usage.cache_creation_input_tokens}) 由 {@code StreamingCallback.extractCachedTokens}
 * 反射读取, 无需本策略处理。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public final class AnthropicPromptCacheStrategy implements PromptCacheStrategy {

    private static final Logger log = LoggerFactory.getLogger(AnthropicPromptCacheStrategy.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "anthropic";
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
                if (Thread.currentThread().isInterrupted()
                        || e instanceof java.io.InterruptedIOException
                        || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IOException("请求被中断 (外层超时/取消)", e);
                }
                log.warn("Anthropic cache_control 注入失败, 回退原始请求: error={}", e.getMessage());
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
                                        log.warn("Anthropic cache_control 注入失败, 原样发送: {}",
                                                e.getMessage());
                                    }
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
     * 解析 JSON 请求体, 对根级 {@code system} field 注入 {@code cache_control: ephemeral}。
     *
     * @param body 原始请求体
     * @return 改写后的请求体, 或 {@code null} 表示无需改动 (非预期结构 / 已标 / 无 system)
     */
    @Nullable
    private static byte[] injectCacheControl(byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            return null;
        }
        JsonNode root = MAPPER.readTree(body);
        if (!root.isObject()) {
            return null;
        }
        ObjectNode rootObj = (ObjectNode) root;
        JsonNode system = rootObj.get("system");
        if (system == null || system.isNull()) {
            return null;
        }

        boolean mutated = false;

        if (system.isTextual()) {
            // 字符串形态: 转成 array-of-one + cache_control
            String text = system.asText();
            if (text.isEmpty()) {
                return null;
            }
            ArrayNode arr = MAPPER.createArrayNode();
            ObjectNode block = MAPPER.createObjectNode();
            block.put("type", "text");
            block.put("text", text);
            ObjectNode ctrl = MAPPER.createObjectNode();
            ctrl.put("type", "ephemeral");
            block.set("cache_control", ctrl);
            arr.add(block);
            rootObj.set("system", arr);
            mutated = true;
        } else if (system.isArray() && !system.isEmpty()) {
            // 数组形态: 给最后一个 block 加 cache_control (若未设置)
            ArrayNode arr = (ArrayNode) system;
            JsonNode last = arr.get(arr.size() - 1);
            if (last != null && last.isObject()) {
                ObjectNode lastBlock = (ObjectNode) last;
                if (!lastBlock.has("cache_control")) {
                    ObjectNode ctrl = MAPPER.createObjectNode();
                    ctrl.put("type", "ephemeral");
                    lastBlock.set("cache_control", ctrl);
                    mutated = true;
                }
            }
        }
        return mutated ? MAPPER.writeValueAsBytes(root) : null;
    }
}
