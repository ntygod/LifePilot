package com.lifepilot.modelservice.probe;

import com.jayway.jsonpath.JsonPath;
import com.lifepilot.llm.profile.ModelDiscoveryEndpoint;
import com.lifepilot.llm.profile.ProviderProfile;
import com.lifepilot.llm.profile.ProviderProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

/**
 * 模型探测服务 — 按 {@link ProviderProfile#modelDiscovery()} 配置发 GET 请求拉模型清单。
 *
 * <p>两类调用方:
 * <ol>
 *   <li>{@code POST /api/model-services/probe-models} 端点：前端"挑 profile → 填 baseUrl/key →
 *       拉 model 清单"配置流程</li>
 *   <li>{@link com.lifepilot.llm.adapter.AbstractProviderAdapter#healthCheck()}：把
 *       原本走 chat ping（推理模型 23s+ 超时被 cancel 误判 unhealthy）的健康检查改为
 *       毫秒级 /v1/models GET，仅当探测端点失败时才回退 chat ping</li>
 * </ol>
 *
 * <p>请求模板:
 * <pre>
 *   GET {baseUrl}{endpoint.path}
 *   Header: {endpoint.authHeaderName}: {endpoint.authHeaderFormat 用 apiKey 替换 ${apiKey}}
 * </pre>
 *
 * <p>响应解析：用 {@link JsonPath} 按 {@link ModelDiscoveryEndpoint#responseModelsJsonPath()}
 * 提取 id 列表；当前 {@link ProbeModelsResponse.ModelInfo#name()} 与 id 一致，预留
 * displayName 扩展点（后续若 provider 返回 description 等，可加 responseModelNameJsonPath）。
 *
 * <p>失败时一律抛 {@link ResponseStatusException}，全局 {@code WebExceptionHandler}
 * 会保留状态码与 reason 透传给前端 toast：
 * <ul>
 *   <li>{@link ConnectException} → 503，提示连接被拒绝</li>
 *   <li>{@link HttpTimeoutException} → 504，提示连接超时</li>
 *   <li>provider 返回非 2xx → 透传原状态码（401/404/500 等），body 截断 500 字</li>
 *   <li>JsonPath 解析失败 → 422，表示响应结构不符</li>
 *   <li>其他网络 IO 异常 → 503，附带 cause message</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-27
 */
@Service
public class ProbeModelsService {

    private static final Logger log = LoggerFactory.getLogger(ProbeModelsService.class);

    /** 共享 HttpClient — 探测路径短小且全部短连接，复用底层连接池足够。 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 单次探测整体超时（用于慢响应降级），与 healthCheck 的虚拟线程超时形成两层保护。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** 错误 body 截断长度 — 太长污染日志/前端 toast，截到 500 字够定位问题。 */
    private static final int ERROR_BODY_SNIPPET_MAX = 500;

    /** JsonPath 解析失败时的 body 截断长度 — 比错误 body 小，因为通常是格式问题不需要全文。 */
    private static final int PARSE_BODY_SNIPPET_MAX = 300;

    private final ProviderProfileRegistry registry;

    public ProbeModelsService(ProviderProfileRegistry registry) {
        this.registry = registry;
    }

    /**
     * 探测 provider 的可用模型清单。
     *
     * @param req 探测入参（profileId + baseUrl + apiKey）
     * @return 解析后的模型清单
     * @throws ResponseStatusException 网络失败 / 非 2xx 响应 / jsonPath 解析失败时抛出，
     *                                 携带具体 HTTP 状态码与 reason，由 {@code WebExceptionHandler}
     *                                 透传给前端；healthCheck 路径会 catch 后回退 chat ping
     */
    public ProbeModelsResponse probe(ProbeModelsRequest req) {
        ProviderProfile profile = registry.get(req.profileId());
        ModelDiscoveryEndpoint endpoint = profile.modelDiscovery();

        // baseUrl 末尾若有 / 先剥掉，避免与 endpoint.path 拼成双斜杠
        String normalizedBaseUrl = req.baseUrl().replaceAll("/$", "");
        String url = normalizedBaseUrl + endpoint.path();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(REQUEST_TIMEOUT);
        // 仅当 apiKey 非空时才注入鉴权 header — Ollama 等无鉴权 provider 走 X-Unused
        // 占位 header，强制注入会让某些自部署服务反而 400
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            String headerValue = endpoint.authHeaderFormat()
                    .replace("${apiKey}", req.apiKey());
            builder.header(endpoint.authHeaderName(), headerValue);
        }

        HttpResponse<String> response;
        try {
            response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            // 连接被拒绝（端口未开 / 服务未启动 / 防火墙）→ 503，提示老板核对地址与服务状态
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "无法连接到 " + url + "（连接被拒绝，确认服务已启动且地址正确）");
        } catch (HttpTimeoutException e) {
            // 连接/读取超时 → 504，区分于普通连接失败便于定位是网络慢还是地址错
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "连接 " + url + " 超时（" + REQUEST_TIMEOUT.toSeconds() + " 秒未响应）");
        } catch (Exception e) {
            // 其他 IOException / InterruptedException 等兜底 → 503 + cause message
            // 把 root cause 也带上，TLS 错误 / DNS 解析失败这类容易在嵌套异常里
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "网络错误: " + detail);
        }

        int status = response.statusCode();
        if (status / 100 != 2) {
            // 4xx 透传 4xx，5xx 透传 5xx；不可识别状态码兜底 502（上游网关错误）
            HttpStatus mapped = HttpStatus.resolve(status);
            if (mapped == null) {
                mapped = HttpStatus.BAD_GATEWAY;
            }
            String snippet = truncate(response.body(), ERROR_BODY_SNIPPET_MAX);
            throw new ResponseStatusException(mapped,
                    "探测端点 " + url + " 返回 HTTP " + status + ": " + snippet);
        }

        String body = response.body();
        try {
            List<String> ids = JsonPath.read(body, endpoint.responseModelsJsonPath());
            List<ProbeModelsResponse.ModelInfo> models = ids.stream()
                    .map(id -> new ProbeModelsResponse.ModelInfo(id, id))
                    .toList();
            log.info("模型探测成功: profileId={}, count={}", req.profileId(), models.size());
            return new ProbeModelsResponse(models);
        } catch (Exception e) {
            // jsonpath 提取失败：响应结构不符（HTML 错误页 / provider 协议变更）→ 422
            String snippet = truncate(body, PARSE_BODY_SNIPPET_MAX);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "解析探测响应失败（jsonpath=" + endpoint.responseModelsJsonPath()
                            + "）: " + snippet);
        }
    }

    /** 按上限截断响应体便于日志/异常输出，避免长 body 污染 toast 与日志。 */
    private static String truncate(String body, int maxLen) {
        if (body == null) {
            return "";
        }
        return body.length() > maxLen ? body.substring(0, maxLen) + "..." : body;
    }
}
