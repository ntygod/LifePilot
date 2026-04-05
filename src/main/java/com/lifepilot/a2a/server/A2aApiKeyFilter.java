package com.lifepilot.a2a.server;

import com.lifepilot.a2a.config.A2aProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * A2A API Key 认证过滤器。
 *
 * <p>拦截 /api/a2a/** 路径的请求，校验 X-API-Key Header。
 * /.well-known/agent.json 不拦截（公开发现端点）。
 * api-key 配置为空时不启用认证。
 * 使用恒定时间比较防止时序攻击。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class A2aApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final A2aProperties properties;

    public A2aApiKeyFilter(A2aProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String apiKey = properties.getServer().getApiKey();
        String providedKey = request.getHeader(API_KEY_HEADER);

        if (providedKey == null || !MessageDigest.isEqual(
                providedKey.getBytes(StandardCharsets.UTF_8),
                apiKey.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"error":{"code":401,"message":"API Key 无效或缺失"}}""");
            return;
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        // /.well-known/agent.json 不拦截
        if (path.equals("/.well-known/agent.json")) {
            return true;
        }
        // api-key 为空时不启用认证
        String apiKey = properties.getServer().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return true;
        }
        // 仅拦截 /api/a2a/** 路径
        return !path.startsWith("/api/a2a");
    }
}
