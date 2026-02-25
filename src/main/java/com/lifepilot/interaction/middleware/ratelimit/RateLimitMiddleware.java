package com.lifepilot.interaction.middleware.ratelimit;

import java.util.concurrent.ConcurrentHashMap;

import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.middleware.GatewayMiddleware;
import com.lifepilot.interaction.middleware.MiddlewareChain;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 限流控制中间件，使用双维度限流策略保护系统资源。
 *
 * <p>第一维度：{@link SlidingWindowCounter} 按请求频率限流（maxRequestsPerMinute）。
 * 第二维度：{@link TokenBucket} 按 LLM Token 配额限流（maxTokensPerHour）。
 *
 * <p>两项检查均通过后调用 {@code chain.next(message)}，响应返回后结算实际 Token 消耗：
 * <ul>
 *   <li>有 {@link TokenUsage} → refund 多预留的差额</li>
 *   <li>无 {@link TokenUsage}（快速路径）→ 全额 refund</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class RateLimitMiddleware implements GatewayMiddleware {

    private static final Logger log = LoggerFactory.getLogger(RateLimitMiddleware.class);

    /** 按用户隔离的令牌桶 */
    private final ConcurrentHashMap<String, TokenBucket> tokenBuckets;

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
        this.tokenBuckets = new ConcurrentHashMap<>();
        this.windowCounters = new ConcurrentHashMap<>();
    }

    @Override
    public GatewayResponse process(GatewayMessage message, MiddlewareChain chain) {
        String userId = message.userId();

        // 1. 检查请求频率（SlidingWindowCounter）
        var counter = windowCounters.computeIfAbsent(userId, k ->
                new SlidingWindowCounter(
                        properties.rateLimit().maxRequestsPerMinute(),
                        60_000L));
        if (!counter.tryAcquire()) {
            log.warn("请求频率超限: userId={}, 当前窗口请求数={}", userId, counter.currentCount());
            return GatewayResponse.error(message.channelType(), "请求频率超限", 429);
        }

        // 2. 检查 Token 配额（TokenBucket）
        var bucket = tokenBuckets.computeIfAbsent(userId, k ->
                new TokenBucket(properties.rateLimit().maxTokensPerHour()));
        int estimated = properties.rateLimit().estimatedTokensPerRequest();
        if (!bucket.tryConsume(estimated)) {
            log.warn("Token 配额不足: userId={}, 可用={}, 预估需要={}",
                    userId, bucket.availableTokens(), estimated);
            return GatewayResponse.error(message.channelType(), "Token 配额不足", 429);
        }

        log.debug("限流检查通过: userId={}, 预留Token={}", userId, estimated);

        // 3. 两项通过，调用下游中间件
        var response = chain.next(message);

        // 4. 结算实际 Token 消耗
        settleTokenUsage(bucket, estimated, response);
        return response;
    }

    /**
     * 结算实际 Token 消耗，退还多预留的差额。
     *
     * <p>结算规则：
     * <ul>
     *   <li>无 TokenUsage 或 TokenUsage.ZERO → 全额退还预留量</li>
     *   <li>实际消耗 &lt; 预估 → 退还差额</li>
     *   <li>实际消耗 ≥ 预估 → 不操作（已预留足够）</li>
     * </ul>
     *
     * @param bucket    用户的令牌桶
     * @param estimated 预估消耗量
     * @param response  下游响应
     */
    private void settleTokenUsage(TokenBucket bucket, int estimated, GatewayResponse response) {
        if (response.tokenUsage() == null || response.tokenUsage().equals(TokenUsage.ZERO)) {
            // 快速路径或无 Token 消耗，全额退还
            bucket.refund(estimated);
            log.debug("Token 全额退还: estimated={}", estimated);
        } else {
            int actual = response.tokenUsage().totalTokens();
            if (actual < estimated) {
                int refundAmount = estimated - actual;
                bucket.refund(refundAmount);
                log.debug("Token 差额退还: estimated={}, actual={}, refund={}", estimated, actual, refundAmount);
            }
            // actual >= estimated 时不额外扣减（已预留足够）
        }
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
