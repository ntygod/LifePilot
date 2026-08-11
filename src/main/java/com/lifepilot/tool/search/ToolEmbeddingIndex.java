package com.lifepilot.tool.search;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 工具 description 向量索引 — 首次语义搜索懒构建，工具注销事件清理已存在向量。
 *
 * <p>冷启动不调用向量服务；索引构建成功后，搜索时只 embed query 一次并本地算余弦相似度。</p>
 *
 * @author zsg
 * @since 2026-05-02
 */
public class ToolEmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(ToolEmbeddingIndex.class);
    private static final long FAILURE_COOLDOWN_MILLIS = 300_000L;

    private final DynamicToolRegistry registry;
    @Nullable
    private final EmbeddingRouter embeddingRouter;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Object buildMonitor = new Object();
    private volatile Map<String, float[]> index = Map.of();
    private volatile boolean initialBuildCompleted;
    private volatile long temporarilyUnavailableUntilMillis;

    public ToolEmbeddingIndex(DynamicToolRegistry registry,
                               @Nullable EmbeddingRouter embeddingRouter) {
        this.registry = registry;
        this.embeddingRouter = embeddingRouter;
    }

    /** 全量重建索引。主要用于运维修复；正常路径由首次语义搜索懒构建。 */
    public void buildAll() {
        if (embeddingRouter == null) return;
        synchronized (buildMonitor) {
            rebuildIndex(true);
        }
    }

    /**
     * 语义搜索：embed query → 与索引中所有向量计算余弦相似度 → top-K。
     *
     * @return 按相似度降序排列的 (toolId, score) 列表
     */
    public List<SemanticHit> search(String query, @Nullable String category, int topK) {
        if (embeddingRouter == null || query == null || query.isBlank() || topK <= 0) return List.of();
        if (isTemporarilyUnavailable() || !ensureIndexReady()) return List.of();
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
            markTemporarilyUnavailable("工具语义搜索失败", e, false);
            return List.of();
        }
    }

    /** 增量刷新单个工具（注册新工具或更新时调用）。 */
    public void refreshOne(String toolId, String description) {
        if (embeddingRouter == null) return;
        if (description == null || description.isBlank()) {
            remove(toolId);
            return;
        }
        if (!initialBuildCompleted || isTemporarilyUnavailable()) return;
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
            markTemporarilyUnavailable("增量刷新工具 embedding 失败: id=" + toolId, e, false);
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

    private boolean ensureIndexReady() {
        if (initialBuildCompleted) return !index.isEmpty();
        if (isTemporarilyUnavailable()) return false;
        synchronized (buildMonitor) {
            if (initialBuildCompleted) return !index.isEmpty();
            if (isTemporarilyUnavailable()) return false;
            return rebuildIndex(false);
        }
    }

    private boolean rebuildIndex(boolean explicit) {
        if (embeddingRouter == null) return false;
        List<IndexEntry> entries = registry.getAllTools().stream()
                .filter(tool -> tool.description() != null && !tool.description().isBlank())
                .map(tool -> new IndexEntry(tool.id(), tool.description()))
                .toList();
        if (entries.isEmpty()) {
            replaceIndex(Map.of());
            initialBuildCompleted = true;
            if (explicit) {
                log.info("工具 embedding 索引构建完成: count=0");
            }
            return false;
        }

        try {
            if (explicit) {
                log.info("开始构建工具 embedding 索引...");
            }
            float[][] vectors = embeddingRouter.embedBatch(
                    entries.stream().map(IndexEntry::description).toList(),
                    EmbeddingUseCase.DEFAULT,
                    null,
                    null);
            var newIndex = new ConcurrentHashMap<String, float[]>();
            int vectorCount = vectors == null ? 0 : Math.min(entries.size(), vectors.length);
            for (int i = 0; i < vectorCount; i++) {
                float[] vector = vectors[i];
                if (vector != null && vector.length > 0) {
                    newIndex.put(entries.get(i).toolId(), vector);
                }
            }
            replaceIndex(Map.copyOf(newIndex));
            initialBuildCompleted = true;
            temporarilyUnavailableUntilMillis = 0L;
            if (explicit) {
                log.info("工具 embedding 索引构建完成: count={}", newIndex.size());
            } else {
                log.debug("工具 embedding 索引懒构建完成: count={}", newIndex.size());
            }
            return !newIndex.isEmpty();
        } catch (Exception e) {
            markTemporarilyUnavailable("工具 embedding 索引构建失败", e, explicit);
            return false;
        }
    }

    private void replaceIndex(Map<String, float[]> newIndex) {
        lock.writeLock().lock();
        try {
            index = Map.copyOf(newIndex);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean isTemporarilyUnavailable() {
        long unavailableUntil = temporarilyUnavailableUntilMillis;
        return unavailableUntil > 0 && unavailableUntil > System.currentTimeMillis();
    }

    private void markTemporarilyUnavailable(String message, Exception e, boolean warn) {
        temporarilyUnavailableUntilMillis = System.currentTimeMillis() + FAILURE_COOLDOWN_MILLIS;
        long cooldownSeconds = FAILURE_COOLDOWN_MILLIS / 1_000;
        if (warn) {
            log.warn("{}，{} 秒后重试: error={}", message, cooldownSeconds, e.getMessage());
        } else {
            log.debug("{}，{} 秒后重试: error={}", message, cooldownSeconds, e.getMessage());
        }
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

    private record IndexEntry(String toolId, String description) {}

    public record SemanticHit(String toolId, double score) {}
}
