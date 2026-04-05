package com.lifepilot.marketplace.clawhub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lifepilot.marketplace.config.MarketplaceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;

/**
 * ClawHub REST API 客户端 — 搜索、查询详情和下载 Skill。
 *
 * <p>直接通过 HTTP 调用 ClawHub 公开 API（无需认证）：
 * <ul>
 *   <li>{@code GET /api/v1/search?q={query}} — 语义搜索</li>
 *   <li>{@code GET /api/v1/skills/{slug}} — 获取 Skill 详情</li>
 *   <li>{@code GET /api/v1/download?slug={slug}} — 下载 Skill zip 包</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-03
 */
public class ClawHubClient {

    private static final Logger log = LoggerFactory.getLogger(ClawHubClient.class);

    private static final TypeReference<List<ClawHubSearchResult>> SEARCH_RESULT_LIST_TYPE = new TypeReference<>() {};

    private final RestClient restClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    /**
     * 构造 ClawHub 客户端。
     *
     * @param restClientBuilder RestClient 构建器
     * @param properties        市场配置属性
     */
    public ClawHubClient(RestClient.Builder restClientBuilder,
                         MarketplaceProperties properties) {
        this.baseUrl = properties.getClawHub().getBaseUrl();
        this.restClient = restClientBuilder
                .baseUrl(this.baseUrl)
                .build();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
    }

    /**
     * 搜索 Skill。
     *
     * @param query 搜索关键词
     * @return 搜索结果列表
     * @throws IOException 网络请求或解析失败时抛出
     */
    public List<ClawHubSearchResult> search(String query) throws IOException {
        log.debug("ClawHub 搜索: query={}", query);
        try {
            String json = restClient.get()
                    .uri("/search?q={query}", query)
                    .retrieve()
                    .body(String.class);

            if (json == null || json.isBlank()) {
                return List.of();
            }

            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                return List.of();
            }

            List<ClawHubSearchResult> list = objectMapper.convertValue(results, SEARCH_RESULT_LIST_TYPE);
            log.debug("ClawHub 搜索完成: query={}, count={}", query, list.size());
            return list;
        } catch (Exception e) {
            throw new IOException("ClawHub 搜索失败: query=" + query, e);
        }
    }

    /**
     * 获取 Skill 详情。
     *
     * @param slug Skill 唯一标识
     * @return Skill 详情
     * @throws IOException 网络请求或解析失败时抛出
     */
    public ClawHubSkillDetail getSkillDetail(String slug) throws IOException {
        log.debug("ClawHub 获取详情: slug={}", slug);
        try {
            String json = restClient.get()
                    .uri("/skills/{slug}", slug)
                    .retrieve()
                    .body(String.class);

            if (json == null || json.isBlank()) {
                throw new IOException("ClawHub 返回空响应: slug=" + slug);
            }

            var detail = objectMapper.readValue(json, ClawHubSkillDetail.class);
            log.debug("ClawHub 详情获取成功: slug={}, version={}", slug, detail.version());
            return detail;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("ClawHub 获取详情失败: slug=" + slug, e);
        }
    }

    /**
     * 下载 Skill zip 包。
     *
     * @param slug Skill 唯一标识
     * @return zip 文件字节数组
     * @throws IOException 下载失败时抛出
     */
    public byte[] downloadZip(String slug) throws IOException {
        log.info("ClawHub 下载 Skill: slug={}", slug);
        try {
            byte[] zipBytes = restClient.get()
                    .uri("/download?slug={slug}", slug)
                    .retrieve()
                    .body(byte[].class);

            if (zipBytes == null || zipBytes.length == 0) {
                throw new IOException("ClawHub 返回空 zip: slug=" + slug);
            }

            log.info("ClawHub 下载完成: slug={}, size={}KB", slug, zipBytes.length / 1024);
            return zipBytes;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("ClawHub 下载失败: slug=" + slug, e);
        }
    }
}
