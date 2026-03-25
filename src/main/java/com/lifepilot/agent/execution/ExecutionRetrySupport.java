package com.lifepilot.agent.execution;

import org.springframework.lang.Nullable;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 主执行链路自动重试辅助方法。
 *
 * @author zsg
 * @since 2026-03-25
 */
public final class ExecutionRetrySupport {

    private ExecutionRetrySupport() {
    }

    /**
     * 判断失败是否属于可安全自动重试的瞬时异常。
     */
    public static boolean isTransientFailure(@Nullable Throwable throwable, @Nullable String... messageHints) {
        List<String> normalizedHints = new ArrayList<>();
        if (messageHints != null) {
            for (String messageHint : messageHints) {
                if (messageHint != null && !messageHint.isBlank()) {
                    normalizedHints.add(messageHint.toLowerCase(Locale.ROOT));
                }
            }
        }

        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof HttpConnectTimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException) {
                return true;
            }
            if (current instanceof IOException && current.getClass() != IOException.class) {
                return true;
            }

            String className = current.getClass().getName().toLowerCase(Locale.ROOT);
            String message = current.getMessage() != null
                    ? current.getMessage().toLowerCase(Locale.ROOT)
                    : "";
            if (containsTransientKeyword(className) || containsTransientKeyword(message)) {
                return true;
            }
            current = current.getCause();
        }

        for (String hint : normalizedHints) {
            if (containsTransientKeyword(hint)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTransientKeyword(String value) {
        return value.contains("timeout")
                || value.contains("超时")
                || value.contains("timed out")
                || value.contains("connection")
                || value.contains("connect")
                || value.contains("连接")
                || value.contains("temporary")
                || value.contains("temporarily")
                || value.contains("暂时")
                || value.contains("transient")
                || value.contains("retryable")
                || value.contains("429")
                || value.contains("rate limit")
                || value.contains("too many requests")
                || value.contains("service unavailable")
                || value.contains("bad gateway")
                || value.contains("gateway timeout");
    }
}
