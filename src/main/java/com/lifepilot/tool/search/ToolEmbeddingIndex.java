package com.lifepilot.tool.search;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 工具 description 向量索引 — 启动时全量预计算，工具变更时增量刷新。
 *
 * <p>搜索时只 embed query 一次，然后本地算余弦相似度，避免每次搜索 N 次 embedding API 调用。</p>
 *
 * @author zsg
 * @since 2026-05-02
 */
public class ToolEmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(ToolEmbeddingIndex.class);

    private final DynamicToolRegistry registry;
    @Nullable
    private final EmbeddingRouter embeddingRouter;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile Map<String, float[]> index = Map.of();

    public ToolEmbeddingIndex(DynamicToolRegistry registry,
                               @Nullable EmbeddingRouter embeddingRouter) {
        this.registry = registry;
        this.embeddingRouter = embeddingRouter;
    }

    /** 启动时全量构建索引。 */
    public void buildAll() {
        if (embeddingRouter == null) return;
        log.info("开始构建工具 embedding 索引...");
        var tools = registry.getAllTools();
        var newIndex = new ConcurrentHashMap<String, float[]>();
        int count = 0;
        for (ToolContract tool : tools) {
            if (tool.description() == null || tool.description().isBlank()) continue;
            try {
                float[] vec = embeddingRouter.embed(tool.description(), EmbeddingUseCase.DEFAULT, null, null);
                if (vec != null && vec.length > 0) {
                    newIndex.put(tool.id(), vec);
                    count++;
                }
            } catch (Exception e) {
                log.warn("embed 工具失败: id={}, error={}", tool.id(), e.getMessage());
            }
        }
        lock.writeLock().lock();
        try {
            index = Map.copyOf(newIndex);
        } finally {
            lock.writeLock().unlock();
        }
        log.info("工具 embedding 索引构建完成: count={}", count);
    }

    /**
     * 语义搜索：embed query → 与索引中所有向量计算余弦相似度 → top-K。
     *
     * @return 按相似度降序排列的 (toolId, score) 列表
     */
    public List<SemanticHit> search(String query, @Nullable String category, int topK) {
        if (embeddingRouter == null) return List.of();
        try {
            float[] queryVec = embeddingRouter.embed(query, EmbeddingUseCase.DEFAULT, null, null);
            if (queryVec == null || queryVec.length == 0) return List.of();

            lock.readLock().lock();
            Map<String, float[]> snapshot;
            try {
                snapshot = index;
            } finally {
                lock.readLock().unlock();
            }

            return snapshot.entrySet().stream()
                    .filter(e -> {
                        if (category == null) return true;
                        var tool = registry.resolve(e.getKey());
                        return tool.map(t -> t.category().name().equalsIgnoreCase(category)).orElse(false);
                    })
                    .map(e -> new SemanticHit(e.getKey(), cosineSimilarity(queryVec, e.getValue())))
                    .sorted(Comparator.comparingDouble(SemanticHit::score).reversed())
                    .limit(topK)
                    .toList();
        } catch (Exception e) {
            log.debug("语义搜索失败: error={}", e.getMessage());
            return List.of();
        }
    }

    /** 增量刷新单个工具（注册新工具或更新时调用）。 */
    public void refreshOne(String toolId, String description) {
        if (embeddingRouter == null || description == null || description.isBlank()) return;
        try {
            float[] vec = embeddingRouter.embed(description, EmbeddingUseCase.DEFAULT, null, null);
            if (vec != null && vec.length > 0) {
                lock.writeLock().lock();
                try {
                    var mutable = new ConcurrentHashMap<>(index);
                    mutable.put(toolId, vec);
                    index = Map.copyOf(mutable);
                } finally {
                    lock.writeLock().unlock();
                }
            }
        } catch (Exception e) {
            log.warn("增量刷新 embedding 失败: id={}, error={}", toolId, e.getMessage());
        }
    }

    /** 从索引中移除工具。 */
    public void remove(String toolId) {
        lock.writeLock().lock();
        try {
            var mutable = new ConcurrentHashMap<>(index);
            mutable.remove(toolId);
            index = Map.copyOf(mutable);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        return index.size();
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public record SemanticHit(String toolId, double score) {}
}
