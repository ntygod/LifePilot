package com.lifepilot.llm.cache;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.config.LlmConfigProperties.CacheConfigEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语义缓存 — 基于 sqlite-vec 向量相似度匹配的 LLM 响应缓存。
 *
 * <p>按 scene + agentPhase 维度隔离，TTL + LRU 混合淘汰。
 * 所有数据库操作异常 catch 后降级（lookup 返回 empty，putAsync 静默跳过）。
 *
 * @author zsg
 * @since 2026-03-07
 */
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    private final CacheConfigEntry config;
    private final LlmRouter llmRouter;
    private final JdbcTemplate jdbcTemplate;

    /** vec0 表是否已成功初始化（volatile 支持延迟初始化）。 */
    private volatile boolean vecAvailable;
    /** 延迟初始化同步锁。 */
    private final Object vecInitLock = new Object();

    // 统计计数器
    private final AtomicLong totalQueries = new AtomicLong();
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong estimatedSavedTokens = new AtomicLong();

    /**
     * 构造 SemanticCache。
     *
     * @param config      缓存配置
     * @param llmRouter   LLM 路由器（用于 embed）
     * @param jdbcTemplate JDBC 模板
     */
    public SemanticCache(CacheConfigEntry config, LlmRouter llmRouter, JdbcTemplate jdbcTemplate) {
        this.config = config;
        this.llmRouter = llmRouter;
        this.jdbcTemplate = jdbcTemplate;
        // 延迟初始化：构造阶段 EMBEDDING Provider 可能尚未注册，
        // 由 ensureVecInitialized() 在首次 lookup/putAsync 时完成初始化
        this.vecAvailable = false;
        log.debug("语义缓存: 已创建，将在首次使用时初始化 vec0 表");
    }

    /** 程序化创建 semantic_cache_vec 虚拟表。 */
    private boolean initVec0Table() {
        try {
            // 探测 Embedding 维度
            float[] probe = llmRouter.embed("probe");
            int dimensions = probe.length;
            jdbcTemplate.execute(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS semantic_cache_vec USING vec0(" +
                    "cache_id TEXT PRIMARY KEY, " +
                    "embedding FLOAT[" + dimensions + "]" +
                    ")");
            log.debug("语义缓存: semantic_cache_vec 表初始化完成, dimensions={}", dimensions);
            return true;
        } catch (Exception e) {
            log.warn("语义缓存: vec0 表创建失败，缓存降级为不可用, error={}", e.getMessage());
            return false;
        }
    }

    /**
     * 确保 vec0 表已初始化（线程安全的延迟初始化）。
     *
     * <p>使用 double-checked locking：如果 {@code vecAvailable} 已为 {@code true}，
     * 直接返回（快速路径）；否则在同步块内重试 {@code initVec0Table()}。
     * 每次调用失败后仍允许下次重试，因为 Provider 可能稍后就绪。</p>
     *
     * @return vec0 表是否可用
     */
    private boolean ensureVecInitialized() {
        if (vecAvailable) {
            return true;
        }
        synchronized (vecInitLock) {
            if (vecAvailable) {
                return true;
            }
            boolean result = initVec0Table();
            if (result) {
                vecAvailable = true;
                log.info("语义缓存: 延迟初始化成功, similarityThreshold={}, ttlSeconds={}, maxEntries={}",
                        config.getSimilarityThreshold(), config.getTtlSeconds(), config.getMaxEntries());
            }
            return result;
        }
    }

    /**
     * 查询缓存：计算 prompt 的 Embedding，在指定 scene + phase 维度内执行向量相似度搜索。
     *
     * @param scene  LLM 场景
     * @param phase  Agent 阶段（可为 null，null 时不限制阶段）
     * @param prompt 请求 Prompt
     * @return 命中的缓存条目，未命中返回 Optional.empty()
     */
    public Optional<CacheEntry> lookup(String scene, @Nullable String phase, String prompt) {
        totalQueries.incrementAndGet();

        if (!ensureVecInitialized()) {
            missCount.incrementAndGet();
            return Optional.empty();
        }

        try {
            // 1. 计算 Embedding
            float[] queryVec = llmRouter.embed(prompt);
            byte[] vectorBytes = floatArrayToBytes(queryVec);

            // 2. KNN 搜索 top-5 候选
            var candidates = jdbcTemplate.query(
                    "SELECT cache_id, vec_distance_cosine(embedding, ?) AS distance " +
                    "FROM semantic_cache_vec ORDER BY distance LIMIT 5",
                    (rs, rowNum) -> new VecCandidate(
                            rs.getString("cache_id"),
                            rs.getFloat("distance")),
                    (Object) vectorBytes);

            if (candidates.isEmpty()) {
                missCount.incrementAndGet();
                return Optional.empty();
            }

            // 3. JOIN semantic_cache 表，过滤 scene + phase + TTL + 相似度阈值
            var cutoff = Instant.now().minus(config.getTtlSeconds(), ChronoUnit.SECONDS);
            String cutoffStr = DateTimeFormatter.ISO_INSTANT.format(cutoff);

            for (var candidate : candidates) {
                float similarity = 1.0f - candidate.distance() / 2.0f;
                if (similarity < config.getSimilarityThreshold()) continue;

                var rows = jdbcTemplate.query(
                        "SELECT id, response_text, scene, agent_phase, model_name, " +
                        "similarity_score, hit_count, created_at, last_accessed_at " +
                        "FROM semantic_cache WHERE id = ? AND scene = ?" +
                        (phase != null ? " AND agent_phase = ?" : " AND agent_phase IS NULL") +
                        " AND created_at > ?",
                        (rs, rowNum) -> new CacheEntry(
                                rs.getString("id"),
                                rs.getString("response_text"),
                                rs.getString("scene"),
                                rs.getString("agent_phase"),
                                rs.getString("model_name"),
                                similarity,
                                rs.getInt("hit_count"),
                                Instant.parse(rs.getString("created_at")),
                                Instant.parse(rs.getString("last_accessed_at"))),
                        phase != null
                                ? new Object[]{candidate.cacheId(), scene, phase, cutoffStr}
                                : new Object[]{candidate.cacheId(), scene, cutoffStr});

                if (!rows.isEmpty()) {
                    var entry = rows.getFirst();
                    // 更新 hit_count 和 last_accessed_at
                    jdbcTemplate.update(
                            "UPDATE semantic_cache SET hit_count = hit_count + 1, " +
                            "last_accessed_at = ? WHERE id = ?",
                            DateTimeFormatter.ISO_INSTANT.format(Instant.now()), entry.id());

                    hitCount.incrementAndGet();
                    // 估算节省 Token：按响应长度 / 4 粗略估算
                    estimatedSavedTokens.addAndGet(entry.responseText().length() / 4);
                    log.debug("语义缓存命中: id={}, similarity={}, scene={}", entry.id(), similarity, scene);
                    return Optional.of(entry);
                }
            }

            missCount.incrementAndGet();
            return Optional.empty();

        } catch (Exception e) {
            missCount.incrementAndGet();
            log.warn("语义缓存查询异常，降级跳过: error={}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 异步写入缓存。
     *
     * @param scene     LLM 场景
     * @param phase     Agent 阶段（可为 null）
     * @param prompt    请求 Prompt
     * @param response  LLM 响应文本
     * @param modelName 生成响应的模型名称
     */
    public void putAsync(String scene, @Nullable String phase, String prompt,
                         String response, String modelName) {
        if (!ensureVecInitialized()) return;

        CompletableFuture.runAsync(() -> {
            try {
                float[] embedding = llmRouter.embed(prompt);
                byte[] vectorBytes = floatArrayToBytes(embedding);
                String id = UUID.randomUUID().toString();
                String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

                // 写入 semantic_cache 关系表
                jdbcTemplate.update(
                        "INSERT INTO semantic_cache (id, query_embedding, response_text, scene, " +
                        "agent_phase, model_name, similarity_score, hit_count, created_at, last_accessed_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 1.0, 0, ?, ?)",
                        id, vectorBytes, response, scene, phase, modelName, now, now);

                // 写入 semantic_cache_vec 向量表
                jdbcTemplate.update(
                        "INSERT INTO semantic_cache_vec (cache_id, embedding) VALUES (?, ?)",
                        id, vectorBytes);

                log.debug("语义缓存写入: id={}, scene={}, phase={}", id, scene, phase);
            } catch (Exception e) {
                log.warn("语义缓存写入异常，静默跳过: error={}", e.getMessage());
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * 按 scene 维度批量清除缓存。
     *
     * @param scene LLM 场景
     */
    public void invalidateByScene(String scene) {
        try {
            // 先查出要删除的 id 列表
            var ids = jdbcTemplate.queryForList(
                    "SELECT id FROM semantic_cache WHERE scene = ?", String.class, scene);
            if (ids.isEmpty()) return;

            // 删除向量表
            for (var id : ids) {
                jdbcTemplate.update("DELETE FROM semantic_cache_vec WHERE cache_id = ?", id);
            }
            // 删除关系表
            jdbcTemplate.update("DELETE FROM semantic_cache WHERE scene = ?", scene);
            log.info("语义缓存清除: scene={}, count={}", scene, ids.size());
        } catch (Exception e) {
            log.warn("语义缓存清除异常: scene={}, error={}", scene, e.getMessage());
        }
    }

    /**
     * 执行淘汰：清除过期条目 + LRU 淘汰超限条目。
     */
    public void evict() {
        try {
            // 1. TTL 过期清除
            var cutoff = Instant.now().minus(config.getTtlSeconds(), ChronoUnit.SECONDS);
            String cutoffStr = DateTimeFormatter.ISO_INSTANT.format(cutoff);

            var expiredIds = jdbcTemplate.queryForList(
                    "SELECT id FROM semantic_cache WHERE created_at <= ?", String.class, cutoffStr);
            for (var id : expiredIds) {
                jdbcTemplate.update("DELETE FROM semantic_cache_vec WHERE cache_id = ?", id);
            }
            if (!expiredIds.isEmpty()) {
                jdbcTemplate.update("DELETE FROM semantic_cache WHERE created_at <= ?", cutoffStr);
                log.debug("语义缓存淘汰: TTL 过期清除 {} 条", expiredIds.size());
            }

            // 2. LRU 淘汰超限条目
            Integer currentCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM semantic_cache", Integer.class);
            if (currentCount != null && currentCount > config.getMaxEntries()) {
                int excess = currentCount - config.getMaxEntries();
                var lruIds = jdbcTemplate.queryForList(
                        "SELECT id FROM semantic_cache ORDER BY last_accessed_at ASC LIMIT ?",
                        String.class, excess);
                for (var id : lruIds) {
                    jdbcTemplate.update("DELETE FROM semantic_cache_vec WHERE cache_id = ?", id);
                }
                if (!lruIds.isEmpty()) {
                    // 批量删除关系表中 LRU 条目
                    for (var id : lruIds) {
                        jdbcTemplate.update("DELETE FROM semantic_cache WHERE id = ?", id);
                    }
                    log.debug("语义缓存淘汰: LRU 清除 {} 条", lruIds.size());
                }
            }
        } catch (Exception e) {
            log.warn("语义缓存淘汰异常: error={}", e.getMessage());
        }
    }

    /**
     * 获取当前缓存统计快照。
     *
     * @return 缓存统计
     */
    public CacheStats getStats() {
        int entryCount = 0;
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM semantic_cache", Integer.class);
            if (count != null) entryCount = count;
        } catch (Exception e) {
            log.warn("语义缓存统计查询异常: error={}", e.getMessage());
        }
        return new CacheStats(
                totalQueries.get(),
                hitCount.get(),
                missCount.get(),
                entryCount,
                estimatedSavedTokens.get());
    }

    // --- 工具方法 ---

    /** float[] 转 byte[]（小端序，sqlite-vec 要求）。 */
    private byte[] floatArrayToBytes(float[] floats) {
        var buffer = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    /** KNN 搜索候选结果。 */
    private record VecCandidate(String cacheId, float distance) {}
}
