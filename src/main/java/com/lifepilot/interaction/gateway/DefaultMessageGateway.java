package com.lifepilot.interaction.gateway;

import com.lifepilot.interaction.middleware.MiddlewarePipeline;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认消息网关实现，将消息推入中间件管道处理并管理运行状态。
 *
 * <p>使用 {@link AtomicBoolean} 管理运行状态，保证线程安全。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class DefaultMessageGateway implements MessageGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultMessageGateway.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final MiddlewarePipeline pipeline;

    /**
     * 构造默认消息网关。
     *
     * @param pipeline 中间件管道
     */
    public DefaultMessageGateway(MiddlewarePipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public GatewayResponse process(GatewayMessage message) {
        if (!running.get()) {
            return GatewayResponse.error(message.channelType(), "网关未运行", 503);
        }

        // 将 messageId 注入 MDC，使整条中间件链路的日志可关联
        MDC.put("messageId", message.messageId());
        log.debug("处理入站消息: messageId={}, channel={}", message.messageId(), message.channelType());
        var start = Instant.now();

        try {
            var response = pipeline.execute(message);
            var latency = Duration.between(start, Instant.now());
            var result = response.toBuilder().latency(latency).build();
            log.info("消息处理完成: messageId={}, statusCode={}, latency={}ms",
                    message.messageId(), result.statusCode(), latency.toMillis());
            return result;
        } catch (Exception e) {
            log.error("中间件管道处理异常: messageId={}", message.messageId(), e);
            var latency = Duration.between(start, Instant.now());
            var errorResponse = GatewayResponse.error(message.channelType(), "内部处理错误", 500)
                    .toBuilder().latency(latency).build();
            log.info("消息处理完成: messageId={}, statusCode={}, latency={}ms",
                    message.messageId(), errorResponse.statusCode(), latency.toMillis());
            return errorResponse;
        } finally {
            org.slf4j.MDC.remove("messageId");
        }
    }

    @Override
    public void start() {
        running.set(true);
        log.info("消息网关已启动");
    }

    @Override
    public void stop() {
        running.set(false);
        log.info("消息网关已停止");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

}
