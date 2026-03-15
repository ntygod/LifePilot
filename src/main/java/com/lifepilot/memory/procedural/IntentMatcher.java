package com.lifepilot.memory.procedural;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 意图匹配器 — 基于 sqlite-vec 向量语义 + FTS5 关键词的双路并行匹配。
 *
 * <p>为用户意图找到最佳操作模板。匹配流程：
 * <ol>
 *   <li>并行执行 sqlite-vec 向量搜索（权重 0.7）+ FTS5 关键词搜索（权重 0.3）</li>
 *   <li>加权融合评分</li>
 *   <li>过滤 isReliable() 为 true 的候选</li>
 *   <li>返回最高分且 ≥ matchThreshold 的模板</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class IntentMatcher {

    private static final Logger log = LoggerFactory.getLogger(IntentMatcher.class);

    /** 向量语义匹配权重。 */
    private static final float SEMANTIC_WEIGHT = 0.7f;

    /** FTS5 关键词匹配权重。 */
    private static final float KEYWORD_WEIGHT = 0.3f;

    /** 向量搜索候选数量。 */
    private static final int SEARCH_TOP_K = 10;

    private final ProceduralMemory proceduralMemory;
    private final VectorSearcher vectorSearcher;
    private final JdbcTemplate jdbcTemplate;
    @SuppressWarnings("unused") // 保留用于 AutoConfiguration Bean 注册签名一致性
    private final LlmRouter llmRouter;
    private final MemoryProperties properties;
    private final ExecutorService executor;

    /**
     * 构造 IntentMatcher。
     *
     * @param proceduralMemory 程序记忆服务
     * @param vectorSearcher   向量检索器
     * @param jdbcTemplate     JDBC 模板（用于直接查询 procedure_templates_fts）
     * @param llmRouter        LLM 路由器（用于 embed）
     * @param properties       记忆系统配置
     */
    public IntentMatcher(ProceduralMemory proceduralMemory,
                         VectorSearcher vectorSearcher,
                         JdbcTemplate jdbcTemplate,
                         LlmRouter llmRouter,
                         MemoryProperties properties) {
        this.proceduralMemory = proceduralMemory;
        this.vectorSearcher = vectorSearcher;
        this.jdbcTemplate = jdbcTemplate;
        this.llmRouter = llmRouter;
        this.properties = properties;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 双路并行匹配：sqlite-vec 向量语义（权重 0.7）+ FTS5 关键词（权重 0.3）。
     *
     * <p>仅返回 isReliable() 为 true 的模板。</p>
     *
     * @param intentText 用户意图文本
     * @return 融合评分最高且 ≥ 配置阈值的模板，无匹配返回 Optional.empty()
     */
    public Optional<TemplateMatch> match(String intentText) {
        if (intentText == null || intentText.isBlank()) {
            return Optional.empty();
        }

        var config = properties.getProcedural();

        // 并行执行向量搜索 + FTS5 搜索
        var vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorSearcher.searchEntities(intentText, SEARCH_TOP_K, 0.0f), executor);
        var ftsFuture = CompletableFuture.supplyAsync(
                () -> searchFts(intentText, SEARCH_TOP_K), executor);

        List<VectorSearchResult> vectorResults = safeGet(vectorFuture, "向量搜索");
        List<FtsResult> ftsResults = safeGet(ftsFuture, "FTS5 搜索");

        // 构建 entityId → score 映射
        Map<String, Float> vectorScores = new HashMap<>();
        for (var vr : vectorResults) {
            vectorScores.put(vr.entityId(), vr.similarity());
        }

        Map<String, Float> ftsScores = new HashMap<>();
        for (var fr : ftsResults) {
            ftsScores.put(fr.templateId(), fr.score());
        }

        // 合并所有候选 ID
        Set<String> allIds = new HashSet<>();
        allIds.addAll(vectorScores.keySet());
        allIds.addAll(ftsScores.keySet());

        if (allIds.isEmpty()) {
            log.debug("意图匹配: 无候选模板, intentText={}", intentText);
            return Optional.empty();
        }

        // 加权融合 + 过滤
        Optional<TemplateMatch> best = Optional.empty();
        for (String id : allIds) {
            float semantic = vectorScores.getOrDefault(id, 0.0f);
            float keyword = ftsScores.getOrDefault(id, 0.0f);
            float fused = SEMANTIC_WEIGHT * semantic + KEYWORD_WEIGHT * keyword;

            if (fused < config.getMatchThreshold()) {
                continue;
            }

            var templateOpt = proceduralMemory.findById(id);
            if (templateOpt.isEmpty()) {
                continue;
            }
            var template = templateOpt.get();

            // 仅返回可靠模板
            if (!template.isReliable(config.getMinReliability(), config.getMinUseCount())) {
                continue;
            }

            if (best.isEmpty() || fused > best.get().score()) {
                best = Optional.of(new TemplateMatch(template, fused));
            }
        }

        best.ifPresent(m -> log.info("意图匹配: 命中模板, templateId={}, name={}, score={}",
                m.template().templateId(), m.template().name(), m.score()));

        return best;
    }

    // ========== 内部方法 ==========

    /** FTS5 搜索结果。 */
    private record FtsResult(String templateId, float score) {}

    /**
     * 查询 procedure_templates_fts 全文索引。
     *
     * @param query 查询文本
     * @param topK  返回前 K 个结果
     * @return FTS5 搜索结果列表
     */
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
                    escaped, topK);
        } catch (Exception e) {
            log.warn("意图匹配: FTS5 搜索失败, query={}, error={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 转义 FTS5 特殊字符，防止语法错误。
     * 与 FtsSearcher 保持一致的转义逻辑。
     */
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
                .replace("'", " ");

        String[] tokens = cleaned.split("\\s+");
        var sb = new StringBuilder();
        for (String token : tokens) {
            String upper = token.toUpperCase();
            if (upper.equals("AND") || upper.equals("OR")
                    || upper.equals("NOT") || upper.equals("NEAR")) {
                continue;
            }
            if (!token.isBlank()) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(token);
            }
        }
        return sb.toString().trim();
    }

    /**
     * 安全获取 CompletableFuture 结果，异常时返回空列表。
     */
    private <T> List<T> safeGet(CompletableFuture<List<T>> future, String label) {
        try {
            return future.join();
        } catch (Exception e) {
            log.warn("意图匹配: {} 异步执行失败, error={}", label, e.getMessage());
            return List.of();
        }
    }

    /**
     * 模板匹配结果 — 包含匹配到的模板和融合评分。
     *
     * @param template 匹配到的操作模板
     * @param score    融合评分（0.7 × 语义 + 0.3 × 关键词）
     */
    public record TemplateMatch(ProcedureTemplate template, float score) {}
}
