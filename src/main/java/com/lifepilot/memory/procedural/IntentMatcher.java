package com.lifepilot.memory.procedural;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final ProceduralMemory proceduralMemory;
    private final VectorSearcher vectorSearcher;
    private final JdbcTemplate jdbcTemplate;
    private final MemoryProperties properties;
    private final ExecutorService executor;

    public IntentMatcher(ProceduralMemory proceduralMemory,
                         VectorSearcher vectorSearcher,
                         JdbcTemplate jdbcTemplate,
                         MemoryProperties properties) {
        this.proceduralMemory = proceduralMemory;
        this.vectorSearcher = vectorSearcher;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public Optional<TemplateMatch> match(String intentText) {
        if (intentText == null || intentText.isBlank()) {
            return Optional.empty();
        }

        var config = properties.getProcedural();
        var vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorSearcher.searchEntities(intentText, SEARCH_TOP_K, 0.0f), executor);
        var ftsFuture = CompletableFuture.supplyAsync(
                () -> searchFts(intentText, SEARCH_TOP_K), executor);

        List<VectorSearchResult> vectorResults = safeGet(vectorFuture, "向量检索");
        List<FtsResult> ftsResults = safeGet(ftsFuture, "FTS 检索");

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
            log.debug("意图匹配未命中任何候选模板: intent={}", intentText);
            return Optional.empty();
        }

        Optional<TemplateMatch> best = Optional.empty();
        for (String candidateId : candidateIds) {
            float semanticScore = vectorScores.getOrDefault(candidateId, 0.0f);
            float keywordScore = ftsScores.getOrDefault(candidateId, 0.0f);
            float fusedScore = SEMANTIC_WEIGHT * semanticScore + KEYWORD_WEIGHT * keywordScore;

            if (fusedScore < config.getMatchThreshold()) {
                continue;
            }

            var templateOpt = proceduralMemory.findById(candidateId);
            if (templateOpt.isEmpty()) {
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

        best.ifPresent(match -> log.info("意图匹配命中模板: templateId={}, name={}, score={}",
                match.template().templateId(), match.template().name(), match.score()));
        return best;
    }

    private List<FtsResult> searchFts(String query, int topK) {
        try {
            String escaped = escapeFts5Query(query);
            if (escaped.isBlank()) {
                return List.of();
            }
            return jdbcTemplate.query(
                    """
                    SELECT pt.template_id, -bm25(procedure_templates_fts) AS score
                    FROM procedure_templates_fts
                    JOIN procedure_templates pt ON procedure_templates_fts.rowid = pt.rowid
                    WHERE procedure_templates_fts MATCH ?
                    ORDER BY score DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> new FtsResult(
                            rs.getString("template_id"),
                            rs.getFloat("score")),
                    escaped,
                    topK);
        } catch (Exception e) {
            log.warn("意图匹配 FTS 检索失败: query={}, error={}", query, e.getMessage());
            return List.of();
        }
    }

    private String escapeFts5Query(String query) {
        String cleaned = query
                .replace("\"", " ")
                .replace("*", " ")
                .replace("^", " ")
                .replace("(", " ")
                .replace(")", " ")
                .replace("{", " ")
                .replace("}", " ")
                .replace("[", " ")
                .replace("]", " ")
                .replace(":", " ")
                .replace(",", " ")
                .replace(";", " ")
                .replace("!", " ")
                .replace("?", " ")
                .replace("+", " ")
                .replace("-", " ")
                .replace("~", " ")
                .replace("@", " ")
                .replace("#", " ")
                .replace("$", " ")
                .replace("%", " ")
                .replace("&", " ")
                .replace("=", " ")
                .replace("<", " ")
                .replace(">", " ")
                .replace("/", " ")
                .replace("\\", " ")
                .replace("|", " ")
                .replace("'", " ")
                .replace(".", " ");

        String[] tokens = cleaned.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String token : tokens) {
            String upper = token.toUpperCase();
            if (upper.equals("AND") || upper.equals("OR")
                    || upper.equals("NOT") || upper.equals("NEAR")) {
                continue;
            }
            if (!token.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append(" ");
                }
                builder.append(token);
            }
        }
        return builder.toString().trim();
    }

    private <T> List<T> safeGet(CompletableFuture<List<T>> future, String label) {
        try {
            return future.join();
        } catch (Exception e) {
            log.warn("意图匹配异步执行失败: label={}, error={}", label, e.getMessage());
            return List.of();
        }
    }

    private record FtsResult(String templateId, float score) {
    }

    public record TemplateMatch(ProcedureTemplate template, float score) {
    }
}
