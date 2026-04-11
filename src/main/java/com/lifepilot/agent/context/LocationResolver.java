package com.lifepilot.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用户位置解析器 — 优先使用配置覆盖，否则通过 IP 地理定位自动检测。
 *
 * <p>检测结果缓存在内存中，整个应用生命周期只发一次 HTTP 请求。</p>
 *
 * @author zsg
 * @since 2026-04-09
 */
public class LocationResolver {

    private static final Logger log = LoggerFactory.getLogger(LocationResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final AgentConfigProperties config;
    private final AtomicReference<String> cached = new AtomicReference<>();
    /** 标记是否已尝试过检测，失败后不再重试。 */
    private volatile boolean detected;

    public LocationResolver(AgentConfigProperties config) {
        this.config = config;
    }

    /**
     * 获取用户位置。
     *
     * <p>优先级：配置手动覆盖 → IP 自动检测缓存 → "未设置"。</p>
     *
     * @return 位置描述（如"北京"）
     */
    public String resolve() {
        // 1. 配置覆盖
        String manual = config.getLocation();
        if (manual != null && !manual.isBlank()) {
            return manual;
        }

        // 2. 缓存命中
        String hit = cached.get();
        if (hit != null) {
            return hit;
        }

        // 3. IP 自动检测（只尝试一次，失败后不再重试；未配置 URL 时跳过）
        String ipApiUrl = config.getIpApiUrl();
        if (!detected && ipApiUrl != null && !ipApiUrl.isBlank()) {
            detected = true;
            String result = detectByIp();
            if (result != null) {
                cached.set(result);
                return result;
            }
        }

        return "未设置";
    }

    /** 通过 IP 地理定位 API 检测城市。 */
    private String detectByIp() {
        try (var client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getIpApiUrl()))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = MAPPER.readTree(response.body());
                String city = json.path("city").asText("");
                String region = json.path("regionName").asText("");
                String result = !city.isBlank() ? city : region;
                if (!result.isBlank()) {
                    log.info("IP 地理定位成功: {}", result);
                    return result;
                }
            }
        } catch (Exception e) {
            log.debug("IP 地理定位失败（不影响功能）: {}", e.getMessage());
        }
        return null;
    }
}
