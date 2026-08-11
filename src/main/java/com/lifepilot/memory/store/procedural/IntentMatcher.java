package com.lifepilot.memory.store.procedural;

import com.lifepilot.memory.store.config.MemoryStoreProperties;
import com.lifepilot.memory.retrieval.SQLiteFtsQueryNormalizer;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 意图匹配器，结合向量检索与 FTS 检索为用户意图匹配最合适的操作模板。
 *
 * @author zsg
 * @since 2026-03-24
 */
public class IntentMatcher {

    private static final Logger log = LoggerFactory.getLogger(IntentMatcher.class);
    private static final float SEMANTIC_WEIGHT = 0.7f;
    private static final float KEYWORD_WEIGHT = 0.3f;
    private static final int SEARCH_TOP_K = 10;
    private static final long SEARCH_TIMEOUT_SECONDS = 5;

    private final ProceduralMemory proceduralMemory;
    private final VectorSearcher vectorSearcher;
    private final JdbcTemplate jdbcTemplate;
    private final MemoryStoreProperties properties;
    private final ExecutorService executor;

    public IntentMatcher(ProceduralMemory proceduralMemory,
                         VectorSearcher vectorSearcher,
                         JdbcTemplate jdbcTemplate,
                         MemoryStoreProperties properties) {
        this.proceduralMemory = Objects.requireNonNull(proceduralMemory, "ProceduralMemory 不能为空");
        this.vectorSearcher = Objects.requireNonNull(vectorSearcher, "VectorSearcher 不能为空");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate 不能为空");
        this.properties = Objects.requireNonNull(properties, "MemoryStoreProperties 不能为空");
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public Optional<TemplateMatch> match(String intentText) {
        if (intentText == null || intentText.isBlank()) {
            throw new IllegalArgumentException("意图文本不能为空");
        }
        String normalizedIntent = intentText.trim();

        var config = Objects.requireNonNull(properties.getProcedural(), "L4 程序记忆配置不能为空");
        validateConfig(config);
        var vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorSearcher.searchEntities(normalizedIntent, SEARCH_TOP_K, 0.0f), executor);
        var ftsFuture = CompletableFuture.supplyAsync(
                () -> searchFts(normalizedIntent, SEARCH_TOP_K), executor);

        List<VectorSearchResult> vectorResults = getRequired(vectorFuture, "向量检索");
        List<FtsResult> ftsResults = getRequired(ftsFuture, "FTS 检索");

        Map<String, Float> vectorScores = new HashMap<>();
        for (var vectorResult : vectorResults) {
            vectorScores.put(vectorResult.entityId(), vectorResult.similarity());
        }

        Map<String, Float> ftsScores = new HashMap<>();
        for (var ftsResult : ftsResults) {
            ftsScores.put(ftsResult.templateId(), ftsResult.score());
        }

        Set<String> candidateIds = new HashSet<>();
        candidateIds.addAll(vectorScores.keySet());
        candidateIds.addAll(ftsScores.keySet());

        if (candidateIds.isEmpty()) {
            log.debug("意图匹配未命中任何候选模板: intent={}", normalizedIntent);
            return Optional.empty();
        }

        Optional<TemplateMatch> best = Optional.empty();
        float bestRawScore = 0.0f;
        String bestRawId = null;
        for (String candidateId : candidateIds) {
            float semanticScore = vectorScores.getOrDefault(candidateId, 0.0f);
            float keywordScore = ftsScores.getOrDefault(candidateId, 0.0f);
            float fusedScore = SEMANTIC_WEIGHT * semanticScore + KEYWORD_WEIGHT * keywordScore;

            if (fusedScore > bestRawScore) {
                bestRawScore = fusedScore;
                bestRawId = candidateId;
            }

            if (fusedScore < config.getMatchThreshold()) {
                continue;
            }

            var templateOpt = proceduralMemory.findById(candidateId);
            if (templateOpt.isEmpty()) {
                log.debug("意图匹配跳过不存在或已失活的候选模板: templateId={}", candidateId);
                continue;
            }

            var template = templateOpt.get();
            if (!template.isReliable(config.getMinReliability(), config.getMinUseCount())) {
                continue;
            }

            if (best.isEmpty() || fusedScore > best.get().score()) {
                best = Optional.of(new TemplateMatch(template, fusedScore));
            }
        }

        if (best.isEmpty()) {
            log.debug("意图匹配无合格模板: intent={}, 候选数={}, 最高融合分={}, 最高候选={}, 阈值={}",
                    normalizedIntent, candidateIds.size(), bestRawScore, bestRawId, config.getMatchThreshold());
        }
        best.ifPresent(match -> log.info("意图匹配命中模板: templateId={}, name={}, score={}",
                match.template().templateId(), match.template().name(), match.score()));
        return best;
    }

    private List<FtsResult> searchFts(String query, int topK) {
        String normalizedQuery = SQLiteFtsQueryNormalizer.normalize(query);
        if (normalizedQuery.isBlank()) {
            return List.of();
        }
        return requireResults(jdbcTemplate.query(
                """
                SELECT pt.template_id, -bm25(procedure_templates_fts) AS score
                FROM procedure_templates_fts
                JOIN procedure_templates pt ON procedure_templates_fts.rowid = pt.rowid
                WHERE procedure_templates_fts MATCH ?
                  AND pt.deactivated_reason IS NULL
                ORDER BY score DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new FtsResult(
                        rs.getString("template_id"),
                        rs.getFloat("score")),
                normalizedQuery,
                topK), "意图匹配 FTS 查询结果");
    }

    private <T> List<T> getRequired(CompletableFuture<List<T>> future, String label) {
        try {
            return requireResults(
                    future.get(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "意图匹配" + label + "结果");
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("意图匹配超时: " + label, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("意图匹配被中断: " + label, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("意图匹配执行失败: " + label, cause);
        }
    }

    private <T> List<T> requireResults(List<T> results, String label) {
        if (results == null) {
            throw new IllegalStateException(label + "不能为空");
        }
        if (results.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException(label + "包含 null 条目");
        }
        return results;
    }

    private void validateConfig(MemoryStoreProperties.Procedural config) {
        probability(config.getMatchThreshold(), "L4 匹配阈值");
        probability(config.getMinReliability(), "L4 最低可靠度");
        if (config.getMinUseCount() < 0) {
            throw new IllegalArgumentException("L4 最小使用次数不能为负数: " + config.getMinUseCount());
        }
    }

    private void probability(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f || value > 1.0f) {
            throw new IllegalArgumentException(name + "必须在 [0,1] 范围内: " + value);
        }
    }

    private record FtsResult(String templateId, float score) {
        FtsResult {
            if (templateId == null || templateId.isBlank()) {
                throw new IllegalStateException("意图匹配 FTS 返回空模板 ID");
            }
            if (!templateId.equals(templateId.trim())) {
                throw new IllegalStateException("意图匹配 FTS 模板 ID 包含首尾空白: " + templateId);
            }
            if (!Float.isFinite(score) || score < 0.0f) {
                throw new IllegalStateException("意图匹配 FTS 返回非法分数: " + score);
            }
        }
    }

    public record TemplateMatch(ProcedureTemplate template, float score) {
    }
}
