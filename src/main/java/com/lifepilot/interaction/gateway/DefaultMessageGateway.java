package com.lifepilot.interaction.gateway;

import com.lifepilot.interaction.channel.ChannelAdapter;
import com.lifepilot.interaction.middleware.MiddlewarePipeline;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认消息网关实现，管理通道注册表和生命周期，将消息推入中间件管道处理。
 *
 * <p>使用 {@link ConcurrentHashMap} 存储通道注册表，{@link AtomicBoolean} 管理运行状态，
 * 保证线程安全。单个通道的启动/停止失败不影响其他通道和网关整体运行。
 *
 * @author zsg
 * @since 2026-02-25
 */
@Slf4j
public class DefaultMessageGateway implements MessageGateway {

    private final ConcurrentHashMap<ChannelType, ChannelAdapter> channels = new ConcurrentHashMap<>();
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
    public void registerChannel(ChannelAdapter adapter) {
        var type = adapter.channelType();
        var existing = channels.putIfAbsent(type, adapter);
        if (existing != null) {
            throw new IllegalStateException("通道类型已注册: " + type);
        }

        if (running.get()) {
            try {
                adapter.start();
            } catch (Exception e) {
                log.warn("通道适配器启动失败，已从注册表移除: type={}", type, e);
                channels.remove(type);
                return;
            }
        }

        log.info("通道适配器注册成功: type={}", type);
    }

    @Override
    public void unregisterChannel(ChannelType type) {
        channels.remove(type);
    }

    @Override
    public Optional<ChannelAdapter> getChannel(ChannelType type) {
        return Optional.ofNullable(channels.get(type));
    }

    @Override
    public List<ChannelAdapter> getAllChannels() {
        return List.copyOf(channels.values());
    }

    @Override
    public void start() {
        running.set(true);
        for (var entry : channels.entrySet()) {
            try {
                entry.getValue().start();
            } catch (Exception e) {
                log.error("通道启动失败: type={}", entry.getKey(), e);
            }
        }
        log.info("消息网关已启动，已注册通道数: {}", channels.size());
    }

    @Override
    public void stop() {
        running.set(false);
        for (var entry : channels.entrySet()) {
            try {
                entry.getValue().stop();
            } catch (Exception e) {
                log.error("通道停止失败: type={}", entry.getKey(), e);
            }
        }
        log.info("消息网关已停止");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

}
