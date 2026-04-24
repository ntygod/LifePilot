package com.lifepilot.meta.infra.web;

/**
 * SSRF 防护拦截异常 — 当目标 URL 命中黑名单（内网、link-local、云 metadata、非 http(s) 协议等）时抛出。
 *
 * <p>由 {@link SsrfGuard#check(String)} 在 HTTP 请求发出前触发，
 * 调用方应捕获此异常并将 {@link #getReason()} 作为错误详情返回给工具执行结果，
 * 避免将防御信息混入通用的 HTTP 请求失败路径。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
public class SsrfBlockedException extends RuntimeException {

    /** 被拦截的具体原因，例如 "IPv4 loopback: 127.0.0.1" 或 "云 metadata 域名: metadata.google.internal"。 */
    private final String reason;

    public SsrfBlockedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
