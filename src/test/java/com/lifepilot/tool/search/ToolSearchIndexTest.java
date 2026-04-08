package com.lifepilot.tool.search;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ToolSearchIndex 工具搜索索引测试。
 *
 * @author zsg
 * @since 2026-04-09
 */
@ExtendWith(MockitoExtension.class)
class ToolSearchIndexTest {

    @Mock
    private EmbeddingRouter embeddingRouter;

    // ──────────────── 辅助方法 ────────────────

    /** 创建测试用 BuiltinTool。 */
    private static BuiltinTool 创建工具(String id, String name, String description) {
        return BuiltinTool.builder()
                .id(id)
                .name(name)
                .description(description)
                .executionSemantics(ToolExecutionSemantics.generic())
                .executor(input -> { throw new UnsupportedOperationException("测试桩"); })
                .build();
    }

    /** 创建指定分类的测试用 BuiltinTool。 */
    private static BuiltinTool 创建工具(String id, String name, String description, ToolCategory category) {
        return BuiltinTool.builder()
                .id(id)
                .name(name)
                .description(description)
                .category(category)
                .executionSemantics(ToolExecutionSemantics.generic())
                .executor(input -> { throw new UnsupportedOperationException("测试桩"); })
                .build();
    }

    /** 生成指定方向的单位向量（用于控制余弦相似度）。 */
    private static float[] 单位向量(int dimension, int nonZeroIndex) {
        float[] vec = new float[dimension];
        vec[nonZeroIndex] = 1.0f;
        return vec;
    }

    // ──────────────── 索引生命周期 ────────────────

    @Nested
    class 索引生命周期 {

        @Test
        void 构建前_isBuilt返回false() {
            var index = new ToolSearchIndex(null);
            assertFalse(index.isBuilt());
        }

        @Test
        void 构建后_isBuilt返回true() {
            var index = new ToolSearchIndex(null);
            index.buildIndex(List.of(创建工具("t1", "工具1", "描述1")));
            assertTrue(index.isBuilt());
        }

        @Test
        void invalidate后_isBuilt返回false() {
            var index = new ToolSearchIndex(null);
            index.buildIndex(List.of(创建工具("t1", "工具1", "描述1")));
            assertTrue(index.isBuilt());

            index.invalidate();
            assertFalse(index.isBuilt());
        }

        @Test
        void 索引未构建时搜索返回空列表() {
            var index = new ToolSearchIndex(null);
            List<ToolSearchIndex.SearchResult> results = index.search("任意查询", 10, 0, Set.of());
            assertTrue(results.isEmpty());
        }

        @Test
        void invalidate后搜索返回空列表() {
            var index = new ToolSearchIndex(null);
            index.buildIndex(List.of(创建工具("t1", "工具1", "描述1")));
            index.invalidate();

            List<ToolSearchIndex.SearchResult> results = index.search("工具1", 10, 0, Set.of());
            assertTrue(results.isEmpty());
        }
    }

    // ──────────────── 无 EmbeddingRouter 时的子串匹配 ────────────────

    @Nested
    class 子串匹配模式 {

        private ToolSearchIndex index;

        @BeforeEach
        void 初始化() {
            // 无 EmbeddingRouter，回退到子串匹配
            index = new ToolSearchIndex(null);
            index.buildIndex(List.of(
                    创建工具("web.search", "Web搜索", "在互联网上搜索信息", ToolCategory.PERCEPTION),
                    创建工具("file.read", "文件读取", "从本地文件系统读取文件内容", ToolCategory.PERCEPTION),
                    创建工具("shell.exec", "Shell执行", "在终端执行命令", ToolCategory.ACTION),
                    创建工具("data.store", "数据存储", "持久化结构化数据", ToolCategory.STORAGE)
            ));
        }

        @Test
        void 完整子串匹配_命中工具名() {
            var results = index.search("Web搜索", 10, 0, Set.of());

            assertFalse(results.isEmpty());
            assertEquals("web.search", results.getFirst().toolId());
            assertTrue(results.getFirst().score() > 0);
        }

        @Test
        void 完整子串匹配_命中工具描述() {
            var results = index.search("互联网", 10, 0, Set.of());

            assertFalse(results.isEmpty());
            assertEquals("web.search", results.getFirst().toolId());
        }

        @Test
        void 完整子串匹配_命中工具ID() {
            var results = index.search("shell", 10, 0, Set.of());

            assertFalse(results.isEmpty());
            assertEquals("shell.exec", results.getFirst().toolId());
        }

        @Test
        void 分词匹配_多个token命中不同工具() {
            // "文件 读取" 分词后 "文件" 和 "读取" 都命中 file.read
            var results = index.search("文件 读取", 10, 0, Set.of());

            assertFalse(results.isEmpty());
            assertEquals("file.read", results.getFirst().toolId());
        }

        @Test
        void 大小写不敏感() {
            var results = index.search("WEB搜索", 10, 0, Set.of());
            assertFalse(results.isEmpty());
            assertEquals("web.search", results.getFirst().toolId());
        }

        @Test
        void 无匹配时返回空列表() {
            var results = index.search("完全不相关的查询xyzzy", 10, 0, Set.of());
            assertTrue(results.isEmpty());
        }

        @Test
        void 分数归一化不超过1() {
            // 查询同时触发完整匹配和多个分词匹配，分数应该被限制在 1.0
            var results = index.search("Web搜索", 10, 0, Set.of());

            assertFalse(results.isEmpty());
            assertTrue(results.getFirst().score() <= 1.0,
                    "分数应该被归一化到 [0, 1] 范围");
        }

        @Test
        void 结果包含正确的元数据() {
            var results = index.search("数据存储", 10, 0, Set.of());

            assertFalse(results.isEmpty());
            var result = results.getFirst();
            assertEquals("data.store", result.toolId());
            assertEquals("数据存储", result.name());
            assertEquals("持久化结构化数据", result.description());
            assertEquals("存储", result.category()); // ToolCategory.STORAGE.displayName()
        }
    }

    // ──────────────── 向量搜索模式 ────────────────

    @Nested
    class 向量搜索模式 {

        private ToolSearchIndex index;

        @BeforeEach
        void 初始化() {
            index = new ToolSearchIndex(embeddingRouter);

            List<ToolContract> tools = List.of(
                    创建工具("tool.a", "工具A", "描述A"),
                    创建工具("tool.b", "工具B", "描述B"),
                    创建工具("tool.c", "工具C", "描述C")
            );

            // buildIndex 时 embedBatch 返回三个不同方向的向量
            when(embeddingRouter.embedBatch(anyList(), eq(EmbeddingUseCase.DEFAULT), isNull(), isNull()))
                    .thenReturn(new float[][]{
                            单位向量(4, 0),  // tool.a -> [1,0,0,0]
                            单位向量(4, 1),  // tool.b -> [0,1,0,0]
                            单位向量(4, 2)   // tool.c -> [0,0,1,0]
                    });

            index.buildIndex(tools);
        }

        @Test
        void 查询向量与工具A完全匹配_排在首位() {
            // 查询向量与 tool.a 同方向
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.DEFAULT), isNull(), isNull()))
                    .thenReturn(单位向量(4, 0));

            var results = index.search("查询", 10, 0, Set.of());

            assertFalse(results.isEmpty());
            assertEquals("tool.a", results.getFirst().toolId());
            assertEquals(1.0, results.getFirst().score(), 1e-6);
        }

        @Test
        void 余弦相似度排序_高相似度在前() {
            // 查询向量偏向 tool.b 方向但也有 tool.a 分量
            float[] queryVec = new float[]{0.3f, 0.9f, 0.1f, 0.0f};
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.DEFAULT), isNull(), isNull()))
                    .thenReturn(queryVec);

            var results = index.search("查询", 10, 0, Set.of());

            assertEquals(3, results.size());
            assertEquals("tool.b", results.get(0).toolId(), "tool.b 应排在第一位");
            assertEquals("tool.a", results.get(1).toolId(), "tool.a 应排在第二位");
            assertEquals("tool.c", results.get(2).toolId(), "tool.c 应排在第三位");
        }

        @Test
        void 查询向量化失败时回退到子串匹配() {
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.DEFAULT), isNull(), isNull()))
                    .thenThrow(new RuntimeException("向量化服务不可用"));

            // 子串匹配 "工具A" 应该能命中
            var results = index.search("工具A", 10, 0, Set.of());

            assertFalse(results.isEmpty());
            assertEquals("tool.a", results.getFirst().toolId());
        }

        @Test
        void embedBatch失败时_整体回退到子串匹配() {
            // 重新构建索引，这次 embedBatch 抛异常
            var failIndex = new ToolSearchIndex(embeddingRouter);

            // 第一次调用（上面 @BeforeEach）已消耗，重新设置 embedBatch 抛异常
            when(embeddingRouter.embedBatch(anyList(), eq(EmbeddingUseCase.DEFAULT), isNull(), isNull()))
                    .thenThrow(new RuntimeException("批量向量化失败"));

            failIndex.buildIndex(List.of(创建工具("tool.x", "特殊工具", "用于测试")));

            // 应该回退到子串匹配
            var results = failIndex.search("特殊工具", 10, 0, Set.of());
            assertFalse(results.isEmpty());
            assertEquals("tool.x", results.getFirst().toolId());
        }
    }

    // ──────────────── excludeIds 排除逻辑 ────────────────

    @Nested
    class 排除逻辑 {

        @Test
        void 子串匹配模式下排除指定工具() {
            var index = new ToolSearchIndex(null);
            index.buildIndex(List.of(
                    创建工具("tool.a", "工具A", "描述A"),
                    创建工具("tool.b", "工具B", "描述B")
            ));

            var results = index.search("工具", 10, 0, Set.of("tool.a"));

            assertEquals(1, results.size());
            assertEquals("tool.b", results.getFirst().toolId());
        }

        @Test
        void 向量搜索模式下排除指定工具() {
            var index = new ToolSearchIndex(embeddingRouter);

            when(embeddingRouter.embedBatch(anyList(), eq(EmbeddingUseCase.DEFAULT), isNull(), isNull()))
                    .thenReturn(new float[][]{
                            单位向量(3, 0),
                            单位向量(3, 0)  // 两个工具同方向，都会匹配
                    });

            index.buildIndex(List.of(
                    创建工具("tool.a", "工具A", "描述A"),
                    创建工具("tool.b", "工具B", "描述B")
            ));

            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.DEFAULT), isNull(), isNull()))
                    .thenReturn(单位向量(3, 0));

            var results = index.search("查询", 10, 0, Set.of("tool.a"));

            assertEquals(1, results.size());
            assertEquals("tool.b", results.getFirst().toolId());
        }

        @Test
        void 排除全部工具时返回空() {
            var index = new ToolSearchIndex(null);
            index.buildIndex(List.of(
                    创建工具("tool.a", "工具A", "描述A"),
                    创建工具("tool.b", "工具B", "描述B")
            ));

            var results = index.search("工具", 10, 0, Set.of("tool.a", "tool.b"));
            assertTrue(results.isEmpty());
        }
    }

    // ──────────────── maxResults 限制 ────────────────

    @Nested
    class 结果数量限制 {

        @Test
        void 子串匹配模式下限制返回数量() {
            var index = new ToolSearchIndex(null);
            index.buildIndex(List.of(
                    创建工具("tool.a", "搜索工具A", "搜索描述A"),
                    创建工具("tool.b", "搜索工具B", "搜索描述B"),
                    创建工具("tool.c", "搜索工具C", "搜索描述C")
            ));

            var results = index.search("搜索", 2, 0, Set.of());

            assertEquals(2, results.size());
        }

        @Test
        void maxResults为1时只返回最佳匹配() {
            var index = new ToolSearchIndex(embeddingRouter);

            when(embeddingRouter.embedBatch(anyList(), eq(EmbeddingUseCase.DEFAULT), isNull(), isNull()))
                    .thenReturn(new float[][]{
                            单位向量(3, 0),
                            单位向量(3, 1)
                    });

            index.buildIndex(List.of(
                    创建工具("tool.a", "工具A", "描述A"),
                    创建工具("tool.b", "工具B", "描述B")
            ));

            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.DEFAULT), isNull(), isNull()))
                    .thenReturn(单位向量(3, 0));

            var results = index.search("查询", 1, 0, Set.of());

            assertEquals(1, results.size());
            assertEquals("tool.a", results.getFirst().toolId());
        }
    }

    // ──────────────── minScore 阈值过滤 ────────────────

    @Nested
    class 分数阈值过滤 {

        @Test
        void 向量搜索_低于阈值的结果被过滤() {
            var index = new ToolSearchIndex(embeddingRouter);

            when(embeddingRouter.embedBatch(anyList(), eq(EmbeddingUseCase.DEFAULT), isNull(), isNull()))
                    .thenReturn(new float[][]{
                            单位向量(4, 0),  // tool.a 与查询同方向 -> cosine = 1.0
                            单位向量(4, 1),  // tool.b 正交 -> cosine = 0.0
                            单位向量(4, 2)   // tool.c 正交 -> cosine = 0.0
                    });

            index.buildIndex(List.of(
                    创建工具("tool.a", "工具A", "描述A"),
                    创建工具("tool.b", "工具B", "描述B"),
                    创建工具("tool.c", "工具C", "描述C")
            ));

            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.DEFAULT), isNull(), isNull()))
                    .thenReturn(单位向量(4, 0));

            // minScore = 0.5，只有 tool.a 的 cosine = 1.0 满足
            var results = index.search("查询", 10, 0.5, Set.of());

            assertEquals(1, results.size());
            assertEquals("tool.a", results.getFirst().toolId());
        }

        @Test
        void 向量搜索_所有结果低于阈值时返回空() {
            var index = new ToolSearchIndex(embeddingRouter);

            when(embeddingRouter.embedBatch(anyList(), eq(EmbeddingUseCase.DEFAULT), isNull(), isNull()))
                    .thenReturn(new float[][]{
                            单位向量(4, 1)   // 与查询正交 -> cosine = 0.0
                    });

            index.buildIndex(List.of(创建工具("tool.a", "工具A", "描述A")));

            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.DEFAULT), isNull(), isNull()))
                    .thenReturn(单位向量(4, 0));

            var results = index.search("查询", 10, 0.5, Set.of());

            assertTrue(results.isEmpty());
        }
    }

    // ──────────────── cosineSimilarity 静态方法 ────────────────

    @Nested
    class 余弦相似度计算 {

        @Test
        void 相同方向向量_相似度为1() {
            float[] a = {1.0f, 0.0f, 0.0f};
            float[] b = {2.0f, 0.0f, 0.0f}; // 同方向不同长度

            assertEquals(1.0, ToolSearchIndex.cosineSimilarity(a, b), 1e-6);
        }

        @Test
        void 正交向量_相似度为0() {
            float[] a = {1.0f, 0.0f};
            float[] b = {0.0f, 1.0f};

            assertEquals(0.0, ToolSearchIndex.cosineSimilarity(a, b), 1e-6);
        }

        @Test
        void 反方向向量_相似度为负1() {
            float[] a = {1.0f, 0.0f};
            float[] b = {-1.0f, 0.0f};

            assertEquals(-1.0, ToolSearchIndex.cosineSimilarity(a, b), 1e-6);
        }

        @Test
        void 第一个参数为null_返回0() {
            assertEquals(0.0, ToolSearchIndex.cosineSimilarity(null, new float[]{1.0f}));
        }

        @Test
        void 第二个参数为null_返回0() {
            assertEquals(0.0, ToolSearchIndex.cosineSimilarity(new float[]{1.0f}, null));
        }

        @Test
        void 两个参数都为null_返回0() {
            assertEquals(0.0, ToolSearchIndex.cosineSimilarity(null, null));
        }

        @Test
        void 长度不一致_返回0() {
            float[] a = {1.0f, 0.0f};
            float[] b = {1.0f, 0.0f, 0.0f};

            assertEquals(0.0, ToolSearchIndex.cosineSimilarity(a, b));
        }

        @Test
        void 零向量_返回0() {
            float[] a = {0.0f, 0.0f, 0.0f};
            float[] b = {1.0f, 0.0f, 0.0f};

            assertEquals(0.0, ToolSearchIndex.cosineSimilarity(a, b));
        }

        @Test
        void 空数组_返回0() {
            assertEquals(0.0, ToolSearchIndex.cosineSimilarity(new float[0], new float[0]));
        }
    }

    // ──────────────── 空索引边界 ────────────────

    @Nested
    class 空索引边界 {

        @Test
        void 构建空工具列表后搜索返回空() {
            var index = new ToolSearchIndex(null);
            index.buildIndex(List.of());

            var results = index.search("任意", 10, 0, Set.of());
            assertTrue(results.isEmpty());
        }

        @Test
        void 构建空工具列表后isBuilt返回true() {
            var index = new ToolSearchIndex(null);
            index.buildIndex(List.of());

            // indexedTools 被赋值（空列表），isBuilt 基于 null 判断
            assertTrue(index.isBuilt());
        }
    }
}
