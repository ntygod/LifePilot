package com.lifepilot.llm.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * 通用 JSON 请求体改写策略模板 —— 抽走 DashScope / Anthropic 两个策略共享的两端拦截骨架。
 *
 * <p>子类只需实现 {@link #rewriteBody(byte[])} 定义具体的 JSON 改写逻辑 (例如注入
 * {@code cache_control: ephemeral} 标记), 本类负责:</p>
 * <ul>
 *   <li>{@link #restClientInterceptor()} —— 非流式调用 ({@code ChatModel.call}) 走 RestClient,
 *       中断 (外层 timeout / cancel) 自动传播, 其它异常回退原始 body;</li>
 *   <li>{@link #webClientFilter()} —— 流式调用 ({@code ChatModel.stream}) 走 WebClient,
 *       通过 {@link CapturingClientHttpRequest} 抓取原 body, 改写失败时 pass-through 原样发送;</li>
 *   <li>统一 {@link HttpHeaders#setContentLength} 修正, 避免改写前后 Content-Length 不一致。</li>
 * </ul>
 *
 * <p>{@link #rewriteBody(byte[])} 返回 {@code null} 表示无需改动 (如非预期 JSON 结构 / 已标记),
 * 拦截器此时直接放行原始字节。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public abstract class AbstractJsonBodyRewritingStrategy implements PromptCacheStrategy {

    /** 共享 ObjectMapper —— Jackson 线程安全, 子类复用避免重复构造。 */
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * 子类实现具体的 JSON 改写逻辑。
     *
     * @param body 原始请求体字节 (保证非空)
     * @return 改写后的字节; 返回 {@code null} 表示无需改动, 调用方会放行原始 body
     * @throws IOException Jackson 解析 / 序列化失败
     */
    @Nullable
    protected abstract byte[] rewriteBody(byte[] body) throws IOException;

    @Override
    public final ClientHttpRequestInterceptor restClientInterceptor() {
        return (request, body, execution) -> {
            if (body.length == 0) {
                return execution.execute(request, body);
            }
            byte[] modifiedBody;
            try {
                byte[] injected = rewriteBody(body);
                if (injected == null) {
                    return execution.execute(request, body);
                }
                modifiedBody = injected;
            } catch (Exception e) {
                // 线程被外层 timeout / cancel 打断时也会落到这里 —— 通过 interrupted 状态区分,
                // 避免把"外层超时"误报成"注入失败"
                if (Thread.currentThread().isInterrupted()
                        || e instanceof InterruptedIOException
                        || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IOException("请求被中断 (外层超时/取消)", e);
                }
                log.warn("{} cache_control 注入失败, 回退原始请求: error={}", name(), e.getMessage());
                return execution.execute(request, body);
            }
            return execution.execute(request, modifiedBody);
        };
    }

    @Override
    public final ExchangeFilterFunction webClientFilter() {
        return (ClientRequest request, ExchangeFunction next) -> {
            @SuppressWarnings({"rawtypes", "unchecked"})
            BodyInserter<?, ? super ClientHttpRequest> originalBody =
                    (BodyInserter) request.body();

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
                                        byte[] modified = rewriteBody(originalBytes);
                                        if (modified != null) {
                                            bytesToWrite = modified;
                                        }
                                    } catch (Exception e) {
                                        log.warn("{} cache_control 注入失败, 原样发送: {}",
                                                name(), e.getMessage());
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
}
