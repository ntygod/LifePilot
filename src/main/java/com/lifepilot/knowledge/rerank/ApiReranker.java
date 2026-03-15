package com.lifepilot.knowledge.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.ScoreBreakdown;
import org.slf4j.Logger;
import org.springframework.lang.Nullable;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * API 精排器 — 调用外部 Reranker API（Jina / Cohere）进行精排。
 *
 * <p>通过 {@code apiProvider} 配置切换 jina / cohere，
 * HTTP 错误、超时、响应解析失败时降级返回原始候选列表。
 *
 * @author zsg
 * @since 2026-02-25
 */
public final class ApiReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(ApiReranker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RerankerConfigProvider configProvider;
    private final HttpClient httpClient;

    public ApiReranker(RerankerConfigProvider configProvider) {
        this.configProvider = configProvider;
        var config = configProvider.getConfig();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.apiTimeoutMs()))
                .build();
        log.info("ApiReranker 初始化: provider={}, model={}, endpoint={}",
                config.apiProvider(), config.model(), config.apiEndpoint());
    }

    @Override
    public List<DocumentSearchResult> rerank(String query, List<DocumentSearchResult> candidates, int topK) {
        return rerank(query, candidates, topK, null);
    }

    @Override
    public List<DocumentSearchResult> rerank(String query, List<DocumentSearchResult> candidates,
                                              int topK, @Nullable String modelName) {
        if (candidates.isEmpty()) return candidates;
        var config = configProvider.getConfig();
        String effectiveModel = resolveModel(modelName, config);
        try {
            return switch (config.apiProvider()) {
                case "jina" -> rerankWithJina(query, candidates, topK, effectiveModel, config);
                case "cohere" -> rerankWithCohere(query, candidates, topK, effectiveModel, config);
                default -> {
                    log.warn("未知 Reranker provider: {}, 跳过精排", config.apiProvider());
                    yield candidates.stream().limit(topK).toList();
                }
            };
        } catch (Exception e) {
            log.warn("ApiReranker 调用失败，降级返回原始结果: {}", e.getMessage());
            return candidates.stream().limit(topK).toList();
        }
    }

    @Override
    public List<RerankCandidate> rerankGeneric(String query, List<RerankCandidate> candidates, int topK) {
        if (candidates.isEmpty()) return candidates;
        try {
            var documents = candidates.stream().map(RerankCandidate::content).toList();
            var config = configProvider.getConfig();
            var apiResults = callApiGeneric(query, documents, topK, config);
            return apiResults.stream()
                    .filter(r -> r.index() >= 0 && r.index() < candidates.size())
                    .map(r -> new RerankCandidate(
                            candidates.get(r.index()).id(),
                            candidates.get(r.index()).content(),
                            r.relevanceScore()))
                    .toList();
        } catch (Exception e) {
            log.warn("ApiReranker rerankGeneric 失败，降级返回原始结果: {}", e.getMessage());
            return candidates.stream()
                    .sorted(Comparator.comparingDouble(RerankCandidate::score).reversed())
                    .limit(topK)
                    .toList();
        }
    }

    /**
     * 通用 API 精排调用 — 接受纯文本文档列表。
     */
    private List<RerankResult> callApiGeneric(String query, List<String> documents, int topK,
                                                KnowledgeBaseProperties.Reranker config) throws Exception {
        String effectiveModel = resolveModel(null, config);
        String endpoint = switch (config.apiProvider()) {
            case "jina" -> config.apiEndpoint().isBlank()
                    ? "https://api.jina.ai/v1/rerank" : config.apiEndpoint();
            case "cohere" -> config.apiEndpoint().isBlank()
                    ? "https://api.cohere.com/v2/rerank" : config.apiEndpoint();
            default -> throw new RuntimeException("未知 Reranker provider: " + config.apiProvider());
        };
        var requestBody = Map.of(
                "model", effectiveModel,
                "query", query,
                "documents", documents,
                "top_n", topK);
        String json = MAPPER.writeValueAsString(requestBody);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .timeout(Duration.ofMillis(config.apiTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Reranker API 错误: HTTP " + response.statusCode());
        }
        var apiResponse = MAPPER.readValue(response.body(), RerankResponse.class);
        if (apiResponse.results() == null || apiResponse.results().isEmpty()) {
            return List.of();
        }
        return apiResponse.results();
    }

    /**
     * 解析有效模型名：modelName 参数 → config.model() → provider 默认值。
     */
    private String resolveModel(@Nullable String modelName, KnowledgeBaseProperties.Reranker config) {
        if (modelName != null && !modelName.isBlank()) return modelName;
        if (!config.model().isBlank()) return config.model();
        return switch (config.apiProvider()) {
            case "jina" -> "jina-reranker-v2-base-multilingual";
            case "cohere" -> "rerank-v3.5";
            default -> "";
        };
    }

    /**
     * 调用 Jina Reranker API。
     */
    private List<DocumentSearchResult> rerankWithJina(String query,
                                                       List<DocumentSearchResult> candidates,
                                                       int topK,
                                                       String effectiveModel,
                                                       KnowledgeBaseProperties.Reranker config) throws Exception {
        String endpoint = config.apiEndpoint().isBlank()
                ? "https://api.jina.ai/v1/rerank" : config.apiEndpoint();
        var documents = candidates.stream().map(DocumentSearchResult::content).toList();
        var requestBody = Map.of(
                "model", effectiveModel,
                "query", query,
                "documents", documents,
                "top_n", topK);
        return callApi(endpoint, requestBody, candidates, config);
    }

    /**
     * 调用 Cohere Rerank API。
     */
    private List<DocumentSearchResult> rerankWithCohere(String query,
                                                         List<DocumentSearchResult> candidates,
                                                         int topK,
                                                         String effectiveModel,
                                                         KnowledgeBaseProperties.Reranker config) throws Exception {
        String endpoint = config.apiEndpoint().isBlank()
                ? "https://api.cohere.com/v2/rerank" : config.apiEndpoint();
        var documents = candidates.stream().map(DocumentSearchResult::content).toList();
        var requestBody = Map.of(
                "model", effectiveModel,
                "query", query,
                "documents", documents,
                "top_n", topK);
        return callApi(endpoint, requestBody, candidates, config);
    }

    /**
     * 通用 API 调用逻辑 — Jina 和 Cohere 响应格式兼容。
     */
    private List<DocumentSearchResult> callApi(String endpoint, Map<String, Object> requestBody,
                                                List<DocumentSearchResult> candidates,
                                                KnowledgeBaseProperties.Reranker config) throws Exception {
        String json = MAPPER.writeValueAsString(requestBody);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .timeout(Duration.ofMillis(config.apiTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Reranker API 返回非 200: status={}, body={}", response.statusCode(), response.body());
            throw new RuntimeException("Reranker API 错误: HTTP " + response.statusCode());
        }

        var apiResponse = MAPPER.readValue(response.body(), RerankResponse.class);
        if (apiResponse.results() == null || apiResponse.results().isEmpty()) {
            log.warn("Reranker API 返回空结果");
            return candidates;
        }

        // 按 API 返回的 relevance_score 降序排列
        var result = new ArrayList<DocumentSearchResult>();
        for (var item : apiResponse.results()) {
            if (item.index() >= 0 && item.index() < candidates.size()) {
                var original = candidates.get(item.index());
                var breakdown = original.scoreBreakdown()
                        .map(b -> new ScoreBreakdown(b.vectorScore(), b.ftsScore(),
                                b.rrfFusedScore(), Optional.of(item.relevanceScore())))
                        .orElse(new ScoreBreakdown(0.0, 0.0, 0.0, Optional.of(item.relevanceScore())));
                result.add(new DocumentSearchResult(
                        original.chunkId(), original.documentId(), original.knowledgeBaseId(),
                        original.content(), original.contextPrefix(), original.headingHierarchy(),
                        item.relevanceScore(), "reranked", original.metadata(),
                        Optional.of(breakdown), original.expandedContent()));
            }
        }
        log.debug("ApiReranker 精排完成: provider={}, input={}, output={}",
                config.apiProvider(), candidates.size(), result.size());
        return result;
    }

    /** API 响应结构（Jina / Cohere 兼容）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RerankResponse(
            @JsonProperty("results") List<RerankResult> results
    ) {}

    /** 单条精排结果。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RerankResult(
            @JsonProperty("index") int index,
            @JsonProperty("relevance_score") double relevanceScore
    ) {}
}
