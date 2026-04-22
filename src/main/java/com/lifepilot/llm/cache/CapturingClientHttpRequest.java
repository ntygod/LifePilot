package com.lifepilot.llm.cache;

import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.function.Supplier;

/**
 * 最小化 {@link ClientHttpRequest} 实现, 通过 {@link ClientHttpRequestDecorator} 继承大部分方法,
 * 只拦截 {@code writeWith} / {@code writeAndFlushWith} 把 body 抓到内存 {@link ByteArrayOutputStream}。
 *
 * <p>不实际向底层网络写, 所以必须等 {@link BodyInserter} 的写入 Mono 结束后再调用
 * {@link #getCapturedBytes()} 才有效。设计为 WebClient filter 中改写请求体的辅助工具,
 * 由 DashScope / Anthropic 等 PromptCacheStrategy 共享复用。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public final class CapturingClientHttpRequest extends ClientHttpRequestDecorator {

    private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    private final DataBufferFactory bufferFactory;
    private final URI uri;
    private final HttpMethod method;
    private final HttpHeaders headers = new HttpHeaders();
    private final MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();

    public CapturingClientHttpRequest(ClientHttpRequest delegate, DataBufferFactory bufferFactory) {
        super(delegate);
        this.bufferFactory = bufferFactory;
        this.uri = delegate.getURI();
        this.method = delegate.getMethod();
    }

    @Override
    public DataBufferFactory bufferFactory() {
        return bufferFactory;
    }

    @Override
    public HttpMethod getMethod() {
        return method;
    }

    @Override
    public URI getURI() {
        return uri;
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public MultiValueMap<String, HttpCookie> getCookies() {
        return cookies;
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        return Flux.from(body)
                .doOnNext(this::drainToBaos)
                .then();
    }

    @Override
    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        return Flux.from(body)
                .flatMap(inner -> Flux.from(inner).doOnNext(this::drainToBaos))
                .then();
    }

    @Override
    public Mono<Void> setComplete() {
        return Mono.empty();
    }

    @Override
    public void beforeCommit(Supplier<? extends Mono<Void>> action) {
        // no-op for capture
    }

    @Override
    public boolean isCommitted() {
        return false;
    }

    /**
     * 获取已捕获的请求体字节。必须在 {@link BodyInserter} 写入 Mono 结束后再调用。
     *
     * @return 请求体字节数组
     */
    public byte[] getCapturedBytes() {
        return baos.toByteArray();
    }

    private void drainToBaos(DataBuffer buffer) {
        try {
            byte[] tmp = new byte[buffer.readableByteCount()];
            buffer.read(tmp);
            baos.write(tmp);
        } catch (IOException e) {
            // ByteArrayOutputStream 不会抛 IOException, 但编译需要
            throw new IllegalStateException("capture write 失败", e);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }
}
