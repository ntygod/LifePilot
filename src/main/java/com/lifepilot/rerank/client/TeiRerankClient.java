package com.lifepilot.rerank.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TEI 原生精排客户端。
 *
 * <p>兼容常见的 TEI `/rerank` 返回格式：数组根节点或 `results` 包装对象。
 *
 * @author zsg
 * @since 2026-03-24
 */
public class TeiRerankClient implements RerankServiceClient {

    private final ModelServiceEntity service;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TeiRerankClient(ModelServiceEntity service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(service.apiUrl())
                .build();
    }

    @Override
    public List<RerankScore> rerank(String query,
                                    List<String> documents,
                                    int topK,
                                    @Nullable Duration timeoutOverride) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("texts", documents);
        body.put("top_n", topK);
        body.put("return_text", false);
        String response = restClient.post()
                .uri(resolveUri())
                .body(body)
                .retrieve()
                .body(String.class);
        return parseResponse(response);
    }

    private String resolveUri() {
        String base = service.apiUrl();
        if (base.endsWith("/rerank")) {
            return "";
        }
        return "/rerank";
    }

    private List<RerankScore> parseResponse(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> array = objectMapper.readValue(body, new TypeReference<>() {});
            return array.stream().map(this::toScore).toList();
        } catch (Exception ignored) {
            // 继续尝试 results 包装格式
        }
        try {
            Map<String, Object> map = objectMapper.readValue(body, new TypeReference<>() {});
            Object results = map.get("results");
            if (results instanceof List<?> list) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .map(this::toScore)
                        .toList();
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 TEI 精排响应失败: " + e.getMessage(), e);
        }
        return List.of();
    }

    private RerankScore toScore(Map<String, Object> item) {
        int index = ((Number) item.getOrDefault("index", -1)).intValue();
        Object rawScore = item.containsKey("score")
                ? item.get("score")
                : item.getOrDefault("relevance_score", 0.0);
        double score = rawScore instanceof Number number ? number.doubleValue() : 0.0;
        return new RerankScore(index, score);
    }
}
