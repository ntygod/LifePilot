package com.lifepilot.skill.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 腾讯 SkillHub 客户端 — 对接外部 Skill 市场，支持搜索和下载 Skill。
 *
 * <p>当本地 Skill 不满足需求时，Agent 可通过此客户端从 SkillHub 搜索并安装中文 Skill。
 * 作为 OpenClaw ClawHub 的中文替代方案。</p>
 *
 * @author zsg
 * @since 2026-03-22
 */
public class SkillHubClient {

    private static final Logger log = LoggerFactory.getLogger(SkillHubClient.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SkillHubClient(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 搜索 SkillHub 中的 Skill。
     *
     * @param query 搜索关键词
     * @param limit 最大返回数量
     * @return 搜索结果列表
     */
    public List<SkillHubEntry> search(String query, int limit) {
        try {
            String url = baseUrl + "/api/skills/search?q=" + encodeQuery(query) + "&limit=" + limit;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", "ZhiWei-Agent/1.0")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("SkillHub 搜索失败: statusCode={}, body={}", response.statusCode(), response.body());
                return List.of();
            }

            SearchResponse searchResponse = objectMapper.readValue(response.body(), SearchResponse.class);
            return searchResponse.items != null ? searchResponse.items : List.of();

        } catch (IOException | InterruptedException e) {
            log.warn("SkillHub 搜索异常: query={}, error={}", query, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    /**
     * 获取 Skill 的完整定义内容（SKILL.md）。
     *
     * @param skillId SkillHub 中的 Skill ID
     * @return Skill 内容（Markdown 格式），获取失败返回 null
     */
    public String fetchSkillContent(String skillId) {
        try {
            String url = baseUrl + "/api/skills/" + skillId + "/content";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "text/markdown")
                    .header("User-Agent", "ZhiWei-Agent/1.0")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("SkillHub 获取内容失败: skillId={}, statusCode={}", skillId, response.statusCode());
                return null;
            }

            // 校验响应是否为 Markdown 而非 HTML 页面
            String body = response.body();
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.contains("text/html") || (body != null && body.trim().startsWith("<!DOCTYPE"))) {
                log.warn("SkillHub 返回 HTML 而非 Markdown，API 端点可能不正确: skillId={}", skillId);
                return null;
            }

            return body;

        } catch (IOException | InterruptedException e) {
            log.warn("SkillHub 获取内容异常: skillId={}, error={}", skillId, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * 检查 SkillHub 服务是否可用。
     *
     * @return 可用返回 true
     */
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;

        } catch (Exception e) {
            return false;
        }
    }

    private String encodeQuery(String query) {
        return java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ─── 响应模型 ───

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<SkillHubEntry> items, int total) {}

    /**
     * SkillHub 搜索结果条目。
     *
     * @param id Skill 唯一标识
     * @param name 显示名称
     * @param description 描述
     * @param version 版本号
     * @param author 作者
     * @param downloads 下载次数
     * @param tags 标签列表
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillHubEntry(
            String id,
            String name,
            String description,
            String version,
            String author,
            int downloads,
            List<String> tags
    ) {}
}
