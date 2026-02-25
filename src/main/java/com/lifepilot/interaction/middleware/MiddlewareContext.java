package com.lifepilot.interaction.middleware;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中间件共享上下文，基于 {@link ConcurrentHashMap} 的类型安全属性包。
 *
 * <p>在中间件管道执行过程中，各中间件通过此上下文传递数据，
 * 无需直接依赖即可共享信息（如认证结果、限流剩余量、路由决策等）。
 *
 * <p>每次请求创建一个新的 {@code MiddlewareContext} 实例，保证请求间隔离。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class MiddlewareContext {

    /** 认证结果键 */
    public static final String KEY_AUTH_RESULT = "auth.result";

    /** 信任等级键 */
    public static final String KEY_TRUST_LEVEL = "auth.trustLevel";

    /** 限流剩余量键 */
    public static final String KEY_RATE_LIMIT_REMAINING = "rateLimit.remaining";

    /** 安全检查结果键 */
    public static final String KEY_SECURITY_CHECK_RESULT = "security.checkResult";

    /** 路由决策键 */
    public static final String KEY_ROUTE_DECISION = "router.decision";

    /** Agent 响应键 */
    public static final String KEY_AGENT_RESPONSE = "execution.agentResponse";

    /** Token 消耗键 */
    public static final String KEY_TOKEN_USAGE = "execution.tokenUsage";

    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 设置属性值。
     *
     * @param key   属性键
     * @param value 属性值
     */
    public void set(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * 类型安全地获取属性值。
     *
     * <p>如果属性不存在或类型不匹配，返回 {@link Optional#empty()}。
     *
     * @param key  属性键
     * @param type 期望的值类型
     * @param <T>  值类型
     * @return 包含属性值的 Optional，不存在或类型不匹配时返回 empty
     */
    public <T> Optional<T> get(String key, Class<T> type) {
        var value = attributes.get(key);
        if (value == null || !type.isInstance(value)) {
            return Optional.empty();
        }
        return Optional.of(type.cast(value));
    }

    /**
     * 获取必需的属性值，不存在或类型不匹配时抛出异常。
     *
     * @param key  属性键
     * @param type 期望的值类型
     * @param <T>  值类型
     * @return 属性值
     * @throws IllegalStateException 如果属性不存在或类型不匹配
     */
    public <T> T require(String key, Class<T> type) {
        var value = attributes.get(key);
        if (value == null) {
            throw new IllegalStateException("中间件上下文缺少必需属性: " + key);
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException(
                    "中间件上下文属性类型不匹配: key=%s, 期望=%s, 实际=%s".formatted(
                            key, type.getName(), value.getClass().getName()));
        }
        return type.cast(value);
    }

    /**
     * 检查属性是否存在。
     *
     * @param key 属性键
     * @return 如果属性存在则返回 true
     */
    public boolean has(String key) {
        return attributes.containsKey(key);
    }

    /**
     * 移除属性。
     *
     * @param key 属性键
     */
    public void remove(String key) {
        attributes.remove(key);
    }

    /**
     * 返回所有属性的不可变快照，用于审计日志等场景。
     *
     * @return 不可变的属性映射
     */
    public Map<String, Object> snapshot() {
        return Map.copyOf(attributes);
    }
}
