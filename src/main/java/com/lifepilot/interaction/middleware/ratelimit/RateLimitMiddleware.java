package com.lifepilot.interaction.middleware.ratelimit;

import java.util.concurrent.ConcurrentHashMap;

import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.middleware.GatewayMiddleware;
import com.lifepilot.interaction.middleware.MiddlewareChain;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 限流控制中间件，按请求频率限流。
 *
 * <p>使用 {@link SlidingWindowCounter} 按每分钟请求数限流（maxRequestsPerMinute）。
 * 个人助手场景下不做 Token 配额限流，费用由用户自行承担。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class RateLimitMiddleware implements GatewayMiddleware {

    private static final Logger log = LoggerFactory.getLogger(RateLimitMiddleware.class);

    /** 按用户隔离的滑动窗口计数器 */
    private final ConcurrentHashMap<String, SlidingWindowCounter> windowCounters;

    /** Gateway 配置属性 */
    private final GatewayProperties properties;

    /**
     * 创建限流控制中间件。
     *
     * @param properties Gateway 配置属性
     */
    public RateLimitMiddleware(GatewayProperties properties) {
        this.properties = properties;
        this.windowCounters = new ConcurrentHashMap<>();
    }

    @Override
    public GatewayResponse process(GatewayMessage message, MiddlewareChain chain) {
        String userId = message.userId();

        // 检查请求频率（SlidingWindowCounter）
        var counter = windowCounters.computeIfAbsent(userId, k ->
                new SlidingWindowCounter(
                        properties.rateLimit().maxRequestsPerMinute(),
                        60_000L));
        if (!counter.tryAcquire()) {
            log.warn("请求频率超限: userId={}, 当前窗口请求数={}", userId, counter.currentCount());
            return GatewayResponse.error(message.channelType(), "请求频率超限", 429);
        }

        log.debug("限流检查通过: userId={}", userId);
        return chain.next(message);
    }

    @Override
    public int order() {
        return properties.middleware().rateLimit().order();
    }

    @Override
    public String name() {
        return "rateLimit";
    }

    @Override
    public boolean enabled() {
        return properties.middleware().rateLimit().enabled();
    }
}
