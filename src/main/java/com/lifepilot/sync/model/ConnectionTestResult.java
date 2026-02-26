package com.lifepilot.sync.model;

import org.springframework.lang.Nullable;

/**
 * 连接测试结果 record。
 *
 * <p>包含连接测试的成功标志、响应时间和可选的错误消息。
 *
 * @param success        连接测试是否成功
 * @param responseTimeMs 响应时间（毫秒）
 * @param errorMessage   测试失败时的错误消息，成功时为 null
 * @author zsg
 * @since 2026-02-26
 */
public record ConnectionTestResult(
        boolean success,
        long responseTimeMs,
        @Nullable String errorMessage
) {
}
