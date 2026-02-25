package com.lifepilot.skill.action;

import com.lifepilot.skill.config.SkillConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * HTTP 动作执行器 — 使用 Spring RestClient 执行 HTTP 请求。
 *
 * <p>安全特性：
 * <ul>
 *   <li>SSRF 防护：拒绝访问内网地址（localhost、127.x、10.x、172.16-31.x、192.168.x）</li>
 *   <li>超时限制：从 SkillConfigProperties.httpAction 读取</li>
 *   <li>环境变量替换：${env.XXX} 从系统环境变量读取</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class HttpActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpActionExecutor.class);

    private final RestClient restClient;
    private final VariableResolver variableResolver;
    private final SkillConfigProperties config;

    /** SSRF 防护：内网地址正则模式。 */
    static final List<Pattern> SSRF_PATTERNS = List.of(
            Pattern.compile("^https?://localhost([:/].*)?$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^https?://127\\..*"),
            Pattern.compile("^https?://10\\..*"),
            Pattern.compile("^https?://172\\.(1[6-9]|2\\d|3[01])\\..*"),
            Pattern.compile("^https?://192\\.168\\..*")
    );

    /**
     * 构造 HTTP 动作执行器。
     *
     * @param restClient       Spring RestClient 实例
     * @param variableResolver 变量替换引擎
     * @param config           Skill 配置属性
     */
    public HttpActionExecutor(RestClient restClient,
                              VariableResolver variableResolver,
                              SkillConfigProperties config) {
        this.restClient = restClient;
        this.variableResolver = variableResolver;
        this.config = config;
    }

    /**
     * 执行 HTTP 请求。
     *
     * @param action HTTP 动作定义
     * @param params 输入参数（用于变量替换）
     * @return 执行结果
     */
    public ActionResult execute(SkillAction.HttpAction action, Map<String, Object> params) {
        // 1. 变量替换 URL
        String resolvedUrl = variableResolver.resolve(action.url(), params, null);

        // 2. SSRF 防护检查
        if (config.getHttpAction().isSsrfProtectionEnabled() && isSsrfTarget(resolvedUrl)) {
            log.warn("SSRF 防护拦截: url={}", resolvedUrl);
            return ActionResult.error("SSRF 防护: 拒绝访问内网地址 " + resolvedUrl);
        }

        // 3. 变量替换请求头
        Map<String, String> resolvedHeaders = new HashMap<>();
        for (var entry : action.headers().entrySet()) {
            resolvedHeaders.put(entry.getKey(),
                    variableResolver.resolve(entry.getValue(), params, null));
        }

        // 4. 变量替换查询参数
        Map<String, String> resolvedQuery = new HashMap<>();
        for (var entry : action.query().entrySet()) {
            resolvedQuery.put(entry.getKey(),
                    variableResolver.resolve(entry.getValue(), params, null));
        }

        // 5. 变量替换请求体
        Object resolvedBody = action.body();
        if (resolvedBody instanceof String bodyStr) {
            resolvedBody = variableResolver.resolve(bodyStr, params, null);
        }

        // 6. 构建并执行请求
        try {
            HttpMethod httpMethod = HttpMethod.valueOf(action.method().toUpperCase());

            // 构建带查询参数的 URI
            var uriBuilder = URI.create(resolvedUrl).toString();
            if (!resolvedQuery.isEmpty()) {
                var queryString = resolvedQuery.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .reduce((a, b) -> a + "&" + b)
                        .orElse("");
                uriBuilder = resolvedUrl + (resolvedUrl.contains("?") ? "&" : "?") + queryString;
            }

            String finalUri = uriBuilder;
            Object finalBody = resolvedBody;

            var requestSpec = restClient.method(httpMethod)
                    .uri(finalUri);

            // 设置请求头
            for (var entry : resolvedHeaders.entrySet()) {
                requestSpec = requestSpec.header(entry.getKey(), entry.getValue());
            }

            // 设置请求体（POST/PUT）
            if (finalBody != null && (httpMethod == HttpMethod.POST || httpMethod == HttpMethod.PUT)) {
                requestSpec.body(finalBody);
            }

            String responseBody = requestSpec
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (request, response) -> {
                                String errorBody = new String(response.getBody().readAllBytes());
                                throw new HttpActionException(response.getStatusCode().value(), errorBody);
                            })
                    .body(String.class);

            return ActionResult.success(responseBody != null ? responseBody : "");

        } catch (HttpActionException e) {
            log.warn("HTTP 请求错误响应: url={}, status={}", resolvedUrl, e.statusCode);
            return ActionResult.error("HTTP " + e.statusCode + ": " + e.responseBody);
        } catch (ResourceAccessException e) {
            // RestClient 超时或连接异常
            if (isTimeoutException(e)) {
                log.warn("HTTP 请求超时: url={}", resolvedUrl);
                return ActionResult.error("HTTP 请求超时: " + resolvedUrl);
            }
            log.warn("HTTP 请求连接异常: url={}, error={}", resolvedUrl, e.getMessage());
            return ActionResult.error("HTTP 请求失败: " + e.getMessage());
        } catch (Exception e) {
            log.warn("HTTP 请求异常: url={}, error={}", resolvedUrl, e.getMessage());
            return ActionResult.error("HTTP 请求失败: " + e.getMessage());
        }
    }

    /**
     * 检查 URL 是否为内网地址（SSRF 目标）。
     *
     * @param url 待检查的 URL
     * @return true 表示为内网地址，应拒绝访问
     */
    boolean isSsrfTarget(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lowerUrl = url.toLowerCase();
        return SSRF_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(lowerUrl).matches());
    }

    /** 判断异常是否为超时异常。 */
    private boolean isTimeoutException(ResourceAccessException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            String msg = cause.getClass().getSimpleName().toLowerCase();
            if (msg.contains("timeout")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * HTTP 动作异常 — 用于在 onStatus 回调中传递状态码和响应体。
     */
    private static class HttpActionException extends RuntimeException {
        final int statusCode;
        final String responseBody;

        HttpActionException(int statusCode, String responseBody) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
    }
}
