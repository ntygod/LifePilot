package com.lifepilot.modelservice.probe;

import com.jayway.jsonpath.JsonPath;
import com.lifepilot.llm.profile.ModelDiscoveryEndpoint;
import com.lifepilot.llm.profile.ProviderProfile;
import com.lifepilot.llm.profile.ProviderProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 模型探测服务 — 按 {@link ProviderProfile#modelDiscovery()} 配置发 GET 请求拉模型清单。
 *
 * <p>两类调用方：
 * <ol>
 *   <li>{@code POST /api/model-services/probe-models} 端点：前端"挑 profile → 填 baseUrl/key →
 *       拉 model 清单"配置流程</li>
 *   <li>{@link com.lifepilot.llm.adapter.AbstractProviderAdapter#healthCheck()}：把
 *       原本走 chat ping（推理模型 23s+ 超时被 cancel 误判 unhealthy）的健康检查改为
 *       毫秒级 /v1/models GET，仅当探测端点失败时才回退 chat ping</li>
 * </ol>
 *
 * <p>请求模板：
 * <pre>
 *   GET {baseUrl}{endpoint.path}
 *   Header: {endpoint.authHeaderName}: {endpoint.authHeaderFormat 用 apiKey 替换 ${apiKey}}
 * </pre>
 *
 * <p>响应解析：用 {@link JsonPath} 按 {@link ModelDiscoveryEndpoint#responseModelsJsonPath()}
 * 提取 id 列表；当前 {@link ProbeModelsResponse.ModelInfo#name()} 与 id 一致，预留
 * displayName 扩展点（后续若 provider 返回 description 等，可加 responseModelNameJsonPath）。
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

    private final ProviderProfileRegistry registry;

    public ProbeModelsService(ProviderProfileRegistry registry) {
        this.registry = registry;
    }

    /**
     * 探测 provider 的可用模型清单。
     *
     * @param req 探测入参（profileId + baseUrl + apiKey）
     * @return 解析后的模型清单
     * @throws RuntimeException 网络失败 / 非 2xx 响应 / jsonPath 解析失败时抛出，
     *                          调用方按需 catch（healthCheck 路径会 catch 后回退 chat ping）
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

        try {
            HttpResponse<String> response = HTTP.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "探测失败: HTTP " + response.statusCode() + ", body=" + truncate(response.body()));
            }
            String body = response.body();
            List<String> ids = JsonPath.read(body, endpoint.responseModelsJsonPath());
            List<ProbeModelsResponse.ModelInfo> models = ids.stream()
                    .map(id -> new ProbeModelsResponse.ModelInfo(id, id))
                    .toList();
            log.info("模型探测成功: profileId={}, count={}", req.profileId(), models.size());
            return new ProbeModelsResponse(models);
        } catch (RuntimeException e) {
            // 已是 RuntimeException 直接抛，避免被下面的 catch 二次包装丢失原始堆栈
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("探测端点请求失败: " + e.getMessage(), e);
        }
    }

    /** 截断响应体便于日志/异常输出，避免长 body 污染日志。 */
    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
