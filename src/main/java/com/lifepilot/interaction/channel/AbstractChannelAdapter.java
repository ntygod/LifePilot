package com.lifepilot.interaction.channel;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.gateway.MessageGateway;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通道适配器抽象基类，封装状态机管理、指数退避重连、失败消息队列等通用逻辑。
 *
 * <p>子类只需实现 {@link #doStart()}、{@link #doStop()}、{@link #doSendResponse(String, GatewayResponse)}
 * 三个模板方法，基类负责状态转换和异常处理。
 *
 * @author zsg
 * @since 2026-02-26
 */
public abstract class AbstractChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(AbstractChannelAdapter.class);

    protected final AtomicReference<ChannelState> state = new AtomicReference<>(ChannelState.CREATED);
    protected final ConcurrentLinkedQueue<FailedMessage> failedMessages = new ConcurrentLinkedQueue<>();
    protected final MessageGateway gateway;
    protected final GatewayProperties properties;

    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("channel-reconnect-", 0).factory()
    );

    /**
     * 构造通道适配器基类。
     *
     * @param gateway    消息网关
     * @param properties 网关配置
     */
    protected AbstractChannelAdapter(MessageGateway gateway, GatewayProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    // ── 模板方法：子类实现 ──────────────────────────────────────

    /** 子类执行通道特定的启动逻辑。 */
    protected abstract void doStart();

    /** 子类执行通道特定的停止逻辑。 */
    protected abstract void doStop();

    /**
     * 子类执行通道特定的响应发送逻辑。
     *
     * @param userId   目标用户标识
     * @param response 网关响应
     */
    protected abstract void doSendResponse(String userId, GatewayResponse response);

    // ── 生命周期管理 ──────────────────────────────────────────

    @Override
    public final void start() {
        if (!state.compareAndSet(ChannelState.CREATED, ChannelState.STARTING)
                && !state.compareAndSet(ChannelState.ERROR, ChannelState.STARTING)) {
            log.warn("通道启动失败，当前状态不允许启动: channel={}, state={}",
                    channelType(), state.get());
            return;
        }
        try {
            log.info("通道启动中: channel={}", channelType());
            doStart();
            state.set(ChannelState.RUNNING);
            reconnectAttempts.set(0);
            log.info("通道启动成功: channel={}", channelType());
        } catch (Exception e) {
            log.error("通道启动失败: channel={}", channelType(), e);
            state.set(ChannelState.ERROR);
            scheduleReconnect();
        }
    }

    @Override
    public final void stop() {
        var current = state.get();
        if (current == ChannelState.STOPPED || current == ChannelState.STOPPING) {
            return;
        }
        state.set(ChannelState.STOPPING);
        try {
            log.info("通道停止中: channel={}", channelType());
            doStop();
            scheduler.shutdownNow();
            state.set(ChannelState.STOPPED);
            log.info("通道已停止: channel={}", channelType());
        } catch (Exception e) {
            log.error("通道停止异常: channel={}", channelType(), e);
            state.set(ChannelState.STOPPED);
        }
    }

    @Override
    public final void sendResponse(String userId, GatewayResponse response) {
        try {
            doSendResponse(userId, response);
        } catch (Exception e) {
            log.warn("响应发送失败，加入重试队列: channel={}, userId={}", channelType(), userId, e);
            failedMessages.offer(new FailedMessage(userId, response, 0, e.getMessage()));
        }
    }

    // ── 消息提交 ──────────────────────────────────────────────

    /**
     * 异步提交消息到 Gateway（企微/飞书使用）。
     *
     * <p>在 Virtual Thread 上执行 Gateway 处理并发送响应，
     * Webhook 回调可立即返回。
     *
     * @param message 标准化后的网关消息
     */
    protected void submitAsync(GatewayMessage message) {
        Thread.ofVirtual()
                .name("channel-async-" + channelType().value())
                .start(() -> {
                    try {
                        var response = gateway.process(message);
                        sendResponse(message.userId(), response);
                    } catch (Exception e) {
                        log.error("异步处理消息失败: channel={}, userId={}",
                                channelType(), message.userId(), e);
                    }
                });
    }

    /**
     * 同步提交消息到 Gateway（钉钉使用）。
     *
     * @param message 标准化后的网关消息
     * @return 网关响应
     */
    protected GatewayResponse submitSync(GatewayMessage message) {
        return gateway.process(message);
    }

    // ── 指数退避重连 ──────────────────────────────────────────

    /**
     * 调度指数退避重连。
     *
     * <p>延迟计算公式：{@code min(initialDelayMs * multiplier^(attempt-1), maxDelayMs)}。
     */
    private void scheduleReconnect() {
        var reconnect = properties.reconnect();
        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > reconnect.maxAttempts()) {
            log.error("通道重连次数超过上限，停止重连: channel={}, maxAttempts={}",
                    channelType(), reconnect.maxAttempts());
            return;
        }
        long delay = calculateDelay(attempt, reconnect);
        log.info("通道将在 {}ms 后重连: channel={}, attempt={}/{}",
                delay, channelType(), attempt, reconnect.maxAttempts());
        scheduler.schedule(this::start, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 计算指数退避延迟。
     *
     * @param attempt   当前尝试次数（从 1 开始）
     * @param reconnect 重连配置
     * @return 延迟毫秒数
     */
    static long calculateDelay(int attempt, GatewayProperties.ReconnectProperties reconnect) {
        double delay = reconnect.initialDelayMs() * Math.pow(reconnect.multiplier(), attempt - 1);
        return Math.min((long) delay, reconnect.maxDelayMs());
    }

    // ── 状态查询 ──────────────────────────────────────────────

    /**
     * 获取当前通道状态。
     *
     * @return 通道状态
     */
    public ChannelState getState() {
        return state.get();
    }

    /**
     * 获取失败消息队列（用于重试调度器）。
     *
     * @return 失败消息队列
     */
    public ConcurrentLinkedQueue<FailedMessage> getFailedMessages() {
        return failedMessages;
    }

    // ── 失败消息记录 ──────────────────────────────────────────

    /**
     * 失败消息记录。
     *
     * @param userId     目标用户标识
     * @param response   网关响应
     * @param retryCount 已重试次数
     * @param error      错误信息
     */
    public record FailedMessage(String userId, GatewayResponse response, int retryCount, String error) {}
}
