package com.lifepilot.memory.consolidation;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.experience.ExperienceRecord;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ExperienceMerger 单元测试 — 经验合并器。
 *
 * <p>覆盖正常合并流程、功能关闭时跳过、经验数量不足、无候选对、
 * LLM 不可用降级、maxMergesPerRun 上限、已合并 ID 防双重合并、
 * 合并统计结果返回、持久化归档等关键路径。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExperienceMerger 单元测试")
class ExperienceMerger_单元测试 {

    @Mock
    private SemanticMemory semanticMemory;
    @Mock
    private VectorSearcher vectorSearcher;
    @Mock
    private GenerationRouter generationRouter;
    @Mock
    private PromptRegistry promptRegistry;

    private MemoryProperties memoryProperties;
    private MemoryProperties.Experience.Merge mergeConfig;
    private ExperienceMerger merger;

    @BeforeEach
    void 初始化() {
        memoryProperties = new MemoryProperties();
        mergeConfig = memoryProperties.getExperience().getMerge();
        // 默认开启、阈值 0.85、最大合并 10、超时 30 秒
        mergeConfig.setEnabled(true);
        mergeConfig.setSimilarityThreshold(0.85f);
        mergeConfig.setMaxMergesPerRun(10);
        mergeConfig.setLlmTimeoutSeconds(30);

        merger = new ExperienceMerger(
                semanticMemory, vectorSearcher, generationRouter,
                promptRegistry, memoryProperties);
    }

    // ========== 辅助方法 ==========

    /** 创建带有 success 属性的 TemporalEntity 经验。 */
    private TemporalEntity 创建经验(String id, String name, boolean success) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("success", success);
        props.put("lessons", List.of("教训1"));
        props.put("toolsUsed", List.of("工具A"));
        var now = Instant.now();
        return new TemporalEntity(
                id, EntityType.EXPERIENCE, name, "策略描述",
                props, 1, true, now, null, null,
                0.8f, 0.7f, 0, null, now, now);
    }

    /** 创建不带 description 的 TemporalEntity，用于测试 null description 分支。 */
    private TemporalEntity 创建经验无描述(String id, String name, boolean success) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("success", success);
        var now = Instant.now();
        return new TemporalEntity(
                id, EntityType.EXPERIENCE, name, null,
                props, 1, true, now, null, null,
                0.8f, 0.5f, 0, null, now, now);
    }

    /** 构造标准的 LLM 合并响应 JSON。 */
    private String 合并响应JSON(String scenario, String strategy, boolean success) {
        return """
                {
                    "scenario": "%s",
                    "strategy": "%s",
                    "lessons": ["合并教训"],
                    "applicableConditions": ["条件1"],
                    "toolsUsed": ["工具A"],
                    "success": %s,
                    "failureAttribution": null,
                    "effectivenessScore": 0.8,
                    "injectionCount": 5,
                    "positiveOutcomes": 3,
                    "negativeOutcomes": 1
                }
                """.formatted(scenario, strategy, success);
    }

    /** 模拟 promptRegistry.render() 返回固定提示词。 */
    private void 模拟提示词渲染() {
        when(promptRegistry.render(eq("memory/experience-merge"), anyMap()))
                .thenReturn("合并提示词");
    }

    /** 模拟 generationRouter.call() 返回指定 JSON 内容的 LlmResponse。 */
    private void 模拟LLM调用(String jsonContent) {
        when(generationRouter.call(
                eq("experience-merge"),
                anyString(),
                isNull(),
                isNull(),
                isNull(),
                eq(GenerationCapability.CHAT),
                any(Duration.class)))
                .thenReturn(new LlmResponse(jsonContent, 100, 200, "openai", "gpt-4", 500, false));
    }

    /** 模拟 generationRouter.call() 抛出异常。 */
    private void 模拟LLM调用异常(RuntimeException exception) {
        when(generationRouter.call(
                eq("experience-merge"),
                anyString(),
                isNull(),
                isNull(),
                isNull(),
                eq(GenerationCapability.CHAT),
                any(Duration.class)))
                .thenThrow(exception);
    }

    // ========== 功能开关 ==========

    @Nested
    @DisplayName("功能开关")
    class 功能开关 {

        @Test
        void 功能关闭时直接返回空统计() {
            mergeConfig.setEnabled(false);

            var stats = merger.merge();

            assertThat(stats.candidatesFound()).isZero();
            assertThat(stats.merged()).isZero();
            assertThat(stats.skipped()).isZero();
            verifyNoInteractions(semanticMemory, vectorSearcher, generationRouter);
        }
    }

    // ========== 经验数量不足 ==========

    @Nested
    @DisplayName("经验数量不足")
    class 经验数量不足 {

        @Test
        void 空经验列表时返回空统计() {
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(Collections.emptyList());

            var stats = merger.merge();

            assertThat(stats.candidatesFound()).isZero();
            assertThat(stats.merged()).isZero();
            assertThat(stats.skipped()).isZero();
            verifyNoInteractions(vectorSearcher, generationRouter);
        }

        @Test
        void 仅一条经验时返回空统计() {
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(创建经验("exp-1", "场景1", true)));

            var stats = merger.merge();

            assertThat(stats.candidatesFound()).isZero();
            assertThat(stats.merged()).isZero();
            assertThat(stats.skipped()).isZero();
            verifyNoInteractions(vectorSearcher, generationRouter);
        }
    }

    // ========== 候选对检测 ==========

    @Nested
    @DisplayName("候选对检测")
    class 候选对检测 {

        @Test
        void 无相似经验时跳过合并() {
            var expA = 创建经验("exp-1", "场景1", true);
            var expB = 创建经验("exp-2", "场景2", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));
            // 向量搜索不返回对方 ID — 无候选对
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenReturn(Collections.emptyList());

            var stats = merger.merge();

            assertThat(stats.candidatesFound()).isZero();
            assertThat(stats.merged()).isZero();
            verifyNoInteractions(generationRouter);
        }

        @Test
        void success标志不同的经验不形成候选对() {
            var expA = 创建经验("exp-1", "场景1", true);
            var expB = 创建经验("exp-2", "场景2", false);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));

            // A 搜索到 B，但 success 不同
            when(vectorSearcher.searchEntities(contains("[经验]"), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景1")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.90f));
                    });

            var stats = merger.merge();

            assertThat(stats.candidatesFound()).isZero();
            verifyNoInteractions(generationRouter);
        }

        @Test
        void 向量搜索返回自身ID时被排除() {
            var expA = 创建经验("exp-1", "场景1", true);
            var expB = 创建经验("exp-2", "场景2", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));

            // 只返回自身 ID
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景1")) {
                            return List.of(new VectorSearchResult("exp-1", 1.0f));
                        }
                        return List.of(new VectorSearchResult("exp-2", 1.0f));
                    });

            var stats = merger.merge();

            assertThat(stats.candidatesFound()).isZero();
            verifyNoInteractions(generationRouter);
        }

        @Test
        void 向量搜索返回的entityId不在经验列表中时被忽略() {
            var expA = 创建经验("exp-1", "场景1", true);
            var expB = 创建经验("exp-2", "场景2", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));

            // 返回一个不存在于经验列表的 ID
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenReturn(List.of(new VectorSearchResult("exp-unknown", 0.90f)));

            var stats = merger.merge();

            assertThat(stats.candidatesFound()).isZero();
            verifyNoInteractions(generationRouter);
        }

        @Test
        void 候选对去重_同一对只出现一次() {
            var expA = 创建经验("exp-1", "场景1", true);
            var expB = 创建经验("exp-2", "场景2", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));

            // A 搜到 B，B 搜到 A — 应只产生一个候选对
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景1")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.90f));
                    });

            模拟提示词渲染();
            模拟LLM调用(合并响应JSON("合并场景", "合并策略", true));

            var stats = merger.merge();

            // 只发现 1 个候选对，不是 2 个
            assertThat(stats.candidatesFound()).isEqualTo(1);
            assertThat(stats.merged()).isEqualTo(1);
        }
    }

    // ========== 正常合并流程 ==========

    @Nested
    @DisplayName("正常合并流程")
    class 正常合并流程 {

        @Test
        void 两条相似成功经验合并为元经验() {
            var expA = 创建经验("exp-1", "搜索优化场景", true);
            var expB = 创建经验("exp-2", "检索优化场景", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("搜索优化")) {
                            return List.of(new VectorSearchResult("exp-2", 0.92f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.92f));
                    });

            模拟提示词渲染();
            模拟LLM调用(合并响应JSON("搜索与检索优化", "统一检索策略", true));

            var stats = merger.merge();

            assertThat(stats.candidatesFound()).isEqualTo(1);
            assertThat(stats.merged()).isEqualTo(1);
            assertThat(stats.skipped()).isZero();

            // 验证 upsertWithConflictDetection 写入了合并后的元经验
            ArgumentCaptor<TemporalEntity> entityCaptor = ArgumentCaptor.forClass(TemporalEntity.class);
            verify(semanticMemory).upsertWithConflictDetection(entityCaptor.capture(), eq("experience-merge"));
            var mergedEntity = entityCaptor.getValue();
            assertThat(mergedEntity.type()).isEqualTo(EntityType.EXPERIENCE);
            assertThat(mergedEntity.name()).isEqualTo("搜索与检索优化");
            assertThat(mergedEntity.description()).isEqualTo("统一检索策略");
            assertThat(mergedEntity.properties().get("mergedFrom")).isEqualTo(List.of("exp-1", "exp-2"));
            assertThat(mergedEntity.properties().get("success")).isEqualTo(true);
            assertThat(mergedEntity.isCurrent()).isTrue();
            assertThat(mergedEntity.extractionConfidence()).isEqualTo(0.8f);
            // importanceScore 取两者最大值
            assertThat(mergedEntity.importanceScore()).isEqualTo(0.7f);

            // 验证向量写入
            verify(vectorSearcher).upsertEntityVector(eq(mergedEntity.id()), anyString());

            // 验证原始经验被归档
            verify(semanticMemory).archive(expA);
            verify(semanticMemory).archive(expB);
        }

        @Test
        void 两条相似失败经验同样可以合并() {
            var expA = 创建经验("exp-1", "失败场景A", false);
            var expB = 创建经验("exp-2", "失败场景B", false);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("失败场景A")) {
                            return List.of(new VectorSearchResult("exp-2", 0.88f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.88f));
                    });

            模拟提示词渲染();
            模拟LLM调用(合并响应JSON("通用失败场景", "失败策略", false));

            var stats = merger.merge();

            assertThat(stats.merged()).isEqualTo(1);
            verify(semanticMemory).archive(expA);
            verify(semanticMemory).archive(expB);
        }

        @Test
        void 合并后的importanceScore取两者最大值() {
            // expA.importanceScore = 0.7, expB 通过自定义创建设为 0.9
            var expA = 创建经验("exp-1", "场景A", true);
            Map<String, Object> propsB = new LinkedHashMap<>();
            propsB.put("success", true);
            propsB.put("lessons", List.of("教训B"));
            propsB.put("toolsUsed", List.of("工具B"));
            var now = Instant.now();
            var expB = new TemporalEntity(
                    "exp-2", EntityType.EXPERIENCE, "场景B", "高重要性描述",
                    propsB, 1, true, now, null, null,
                    0.8f, 0.9f, 0, null, now, now);

            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景A")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.90f));
                    });

            模拟提示词渲染();
            模拟LLM调用(合并响应JSON("合并场景", "合并策略", true));

            merger.merge();

            ArgumentCaptor<TemporalEntity> captor = ArgumentCaptor.forClass(TemporalEntity.class);
            verify(semanticMemory).upsertWithConflictDetection(captor.capture(), eq("experience-merge"));
            // 取 max(0.7, 0.9) = 0.9
            assertThat(captor.getValue().importanceScore()).isEqualTo(0.9f);
        }

        @Test
        void scenario超过100字符时被截断() {
            var expA = 创建经验("exp-1", "场景A", true);
            var expB = 创建经验("exp-2", "场景B", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景A")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.90f));
                    });

            // 构造超过 100 字符的 scenario
            String longScenario = "非".repeat(120);
            模拟提示词渲染();
            模拟LLM调用(合并响应JSON(longScenario, "策略", true));

            merger.merge();

            ArgumentCaptor<TemporalEntity> captor = ArgumentCaptor.forClass(TemporalEntity.class);
            verify(semanticMemory).upsertWithConflictDetection(captor.capture(), eq("experience-merge"));
            // name 被截断到 100 字符
            assertThat(captor.getValue().name()).hasSize(100);
            // properties 中的 scenario 保留原始长度
            assertThat(captor.getValue().properties().get("scenario")).isEqualTo(longScenario);
        }

        @Test
        void description为null的经验正常合并() {
            var expA = 创建经验无描述("exp-1", "场景A", true);
            var expB = 创建经验无描述("exp-2", "场景B", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景A")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.90f));
                    });

            模拟提示词渲染();
            模拟LLM调用(合并响应JSON("合并场景", "合并策略", true));

            var stats = merger.merge();

            assertThat(stats.merged()).isEqualTo(1);
        }
    }

    // ========== LLM 不可用降级 ==========

    @Nested
    @DisplayName("LLM 不可用降级")
    class LLM不可用降级 {

        @Test
        void LLM调用异常时跳过该候选对() {
            var expA = 创建经验("exp-1", "场景1", true);
            var expB = 创建经验("exp-2", "场景2", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景1")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.90f));
                    });

            模拟提示词渲染();
            模拟LLM调用异常(new LlmUnavailableException("无可用服务", "experience-merge", List.of()));

            var stats = merger.merge();

            assertThat(stats.candidatesFound()).isEqualTo(1);
            assertThat(stats.merged()).isZero();
            assertThat(stats.skipped()).isEqualTo(1);
            // 确保未写入、未归档
            verify(semanticMemory, never()).upsertWithConflictDetection(any(), anyString());
            verify(semanticMemory, never()).archive(any());
        }

        @Test
        void LLM返回无法解析的JSON时跳过该候选对() {
            var expA = 创建经验("exp-1", "场景1", true);
            var expB = 创建经验("exp-2", "场景2", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景1")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.90f));
                    });

            模拟提示词渲染();
            // 返回非法 JSON
            模拟LLM调用("{不合法的JSON内容}}}");

            var stats = merger.merge();

            // callLlmMerge 内部 catch 住异常 → 返回 null → skipped
            assertThat(stats.candidatesFound()).isEqualTo(1);
            assertThat(stats.merged()).isZero();
            assertThat(stats.skipped()).isEqualTo(1);
        }

        @Test
        void promptRegistry渲染异常时跳过该候选对() {
            var expA = 创建经验("exp-1", "场景1", true);
            var expB = 创建经验("exp-2", "场景2", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景1")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.90f));
                    });

            // 模板渲染失败
            when(promptRegistry.render(eq("memory/experience-merge"), anyMap()))
                    .thenThrow(new RuntimeException("模板缺失"));

            var stats = merger.merge();

            assertThat(stats.candidatesFound()).isEqualTo(1);
            assertThat(stats.merged()).isZero();
            assertThat(stats.skipped()).isEqualTo(1);
        }
    }

    // ========== 合并上限与防双重合并 ==========

    @Nested
    @DisplayName("合并上限与防双重合并")
    class 合并上限与防双重合并 {

        @Test
        void 达到maxMergesPerRun后停止合并() {
            mergeConfig.setMaxMergesPerRun(1);

            // 创建 3 条经验，能形成 2 个候选对
            var expA = 创建经验("exp-1", "场景A", true);
            var expB = 创建经验("exp-2", "场景B", true);
            var expC = 创建经验("exp-3", "场景C", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB, expC));

            // A→B 和 C→(无匹配) — 但让 A 同时搜到 B 和 C，C 搜到 A
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景A")) {
                            return List.of(
                                    new VectorSearchResult("exp-2", 0.92f),
                                    new VectorSearchResult("exp-3", 0.88f));
                        }
                        if (query.contains("场景B")) {
                            return List.of(new VectorSearchResult("exp-1", 0.92f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.88f));
                    });

            模拟提示词渲染();
            模拟LLM调用(合并响应JSON("合并场景", "合并策略", true));

            var stats = merger.merge();

            // 发现 2 个候选对，但只合并 1 个，剩余 1 个跳过
            assertThat(stats.candidatesFound()).isEqualTo(2);
            assertThat(stats.merged()).isEqualTo(1);
            assertThat(stats.skipped()).isEqualTo(1);
        }

        @Test
        void 已合并的实体ID不参与后续合并() {
            mergeConfig.setMaxMergesPerRun(10);

            // 3 条经验：A-B 相似、A-C 相似，A 合并到 B 后 A-C 候选对应跳过
            var expA = 创建经验("exp-1", "场景A", true);
            var expB = 创建经验("exp-2", "场景B", true);
            var expC = 创建经验("exp-3", "场景C", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB, expC));

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景A")) {
                            return List.of(
                                    new VectorSearchResult("exp-2", 0.92f),
                                    new VectorSearchResult("exp-3", 0.88f));
                        }
                        if (query.contains("场景B")) {
                            return List.of(new VectorSearchResult("exp-1", 0.92f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.88f));
                    });

            模拟提示词渲染();
            模拟LLM调用(合并响应JSON("合并场景", "合并策略", true));

            var stats = merger.merge();

            // 2 个候选对 (A-B, A-C)，A-B 合并成功后 A 进入 mergedIds，A-C 被 skip
            assertThat(stats.candidatesFound()).isEqualTo(2);
            assertThat(stats.merged()).isEqualTo(1);
            assertThat(stats.skipped()).isEqualTo(1);

            // 只归档了 A 和 B
            verify(semanticMemory, times(1)).archive(expA);
            verify(semanticMemory, times(1)).archive(expB);
            verify(semanticMemory, never()).archive(expC);
        }
    }

    // ========== 合并统计 ==========

    @Nested
    @DisplayName("合并统计")
    class 合并统计 {

        @Test
        void MergeStats_record各字段正确() {
            var stats = new ExperienceMerger.MergeStats(5, 3, 2);

            assertThat(stats.candidatesFound()).isEqualTo(5);
            assertThat(stats.merged()).isEqualTo(3);
            assertThat(stats.skipped()).isEqualTo(2);
        }

        @Test
        void 多对成功合并时统计准确() {
            mergeConfig.setMaxMergesPerRun(10);

            // 4 条经验：A-B 相似、C-D 相似
            var expA = 创建经验("exp-1", "场景A", true);
            var expB = 创建经验("exp-2", "场景B", true);
            var expC = 创建经验("exp-3", "场景C", false);
            var expD = 创建经验("exp-4", "场景D", false);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB, expC, expD));

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景A")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        if (query.contains("场景B")) {
                            return List.of(new VectorSearchResult("exp-1", 0.90f));
                        }
                        if (query.contains("场景C")) {
                            return List.of(new VectorSearchResult("exp-4", 0.91f));
                        }
                        return List.of(new VectorSearchResult("exp-3", 0.91f));
                    });

            模拟提示词渲染();
            模拟LLM调用(合并响应JSON("合并结果", "合并策略", true));

            var stats = merger.merge();

            assertThat(stats.candidatesFound()).isEqualTo(2);
            assertThat(stats.merged()).isEqualTo(2);
            assertThat(stats.skipped()).isZero();

            // 4 条原始经验全部归档
            verify(semanticMemory, times(4)).archive(any(TemporalEntity.class));
        }
    }

    // ========== 持久化细节 ==========

    @Nested
    @DisplayName("持久化细节")
    class 持久化细节 {

        @Test
        void 合并后的元经验属性完整() {
            var expA = 创建经验("exp-1", "场景A", true);
            var expB = 创建经验("exp-2", "场景B", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景A")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.90f));
                    });

            模拟提示词渲染();
            String json = """
                    {
                        "scenario": "综合场景",
                        "strategy": "综合策略",
                        "lessons": ["教训1", "教训2"],
                        "applicableConditions": ["条件X"],
                        "toolsUsed": ["工具Y"],
                        "success": true,
                        "failureAttribution": null,
                        "effectivenessScore": 0.75,
                        "injectionCount": 10,
                        "positiveOutcomes": 7,
                        "negativeOutcomes": 2
                    }
                    """;
            模拟LLM调用(json);

            merger.merge();

            ArgumentCaptor<TemporalEntity> captor = ArgumentCaptor.forClass(TemporalEntity.class);
            verify(semanticMemory).upsertWithConflictDetection(captor.capture(), eq("experience-merge"));
            var entity = captor.getValue();
            var props = entity.properties();

            assertThat(props.get("scenario")).isEqualTo("综合场景");
            assertThat(props.get("strategy")).isEqualTo("综合策略");
            assertThat(props.get("lessons")).isEqualTo(List.of("教训1", "教训2"));
            assertThat(props.get("applicableConditions")).isEqualTo(List.of("条件X"));
            assertThat(props.get("toolsUsed")).isEqualTo(List.of("工具Y"));
            assertThat(props.get("success")).isEqualTo(true);
            assertThat(props.get("effectivenessScore")).isEqualTo(0.75f);
            assertThat(props.get("injectionCount")).isEqualTo(10);
            assertThat(props.get("positiveOutcomes")).isEqualTo(7);
            assertThat(props.get("negativeOutcomes")).isEqualTo(2);
            assertThat(props.get("mergedFrom")).isEqualTo(List.of("exp-1", "exp-2"));

            // 元实体基本属性
            assertThat(entity.version()).isEqualTo(1);
            assertThat(entity.isCurrent()).isTrue();
            assertThat(entity.extractionConfidence()).isEqualTo(0.8f);
            assertThat(entity.accessCount()).isZero();
        }

        @Test
        void LLM返回null字段时使用默认空列表() {
            var expA = 创建经验("exp-1", "场景A", true);
            var expB = 创建经验("exp-2", "场景B", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景A")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.90f));
                    });

            模拟提示词渲染();
            // JSON 中 lessons、applicableConditions、toolsUsed 为 null
            String json = """
                    {
                        "scenario": "场景",
                        "strategy": "策略",
                        "lessons": null,
                        "applicableConditions": null,
                        "toolsUsed": null,
                        "success": true,
                        "effectivenessScore": 0.5,
                        "injectionCount": 0,
                        "positiveOutcomes": 0,
                        "negativeOutcomes": 0
                    }
                    """;
            模拟LLM调用(json);

            merger.merge();

            ArgumentCaptor<TemporalEntity> captor = ArgumentCaptor.forClass(TemporalEntity.class);
            verify(semanticMemory).upsertWithConflictDetection(captor.capture(), eq("experience-merge"));
            var props = captor.getValue().properties();
            // null 字段降级为空列表
            assertThat(props.get("lessons")).isEqualTo(List.of());
            assertThat(props.get("applicableConditions")).isEqualTo(List.of());
            assertThat(props.get("toolsUsed")).isEqualTo(List.of());
        }
    }

    // ========== 相似度阈值边界 ==========

    @Nested
    @DisplayName("相似度阈值边界")
    class 相似度阈值边界 {

        @Test
        void 自定义高阈值时传递给向量搜索() {
            mergeConfig.setSimilarityThreshold(0.95f);

            var expA = 创建经验("exp-1", "场景1", true);
            var expB = 创建经验("exp-2", "场景2", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.95f)))
                    .thenReturn(Collections.emptyList());

            var stats = merger.merge();

            assertThat(stats.candidatesFound()).isZero();
            // 确认使用的是自定义阈值
            verify(vectorSearcher, atLeastOnce())
                    .searchEntities(anyString(), eq(5), eq(0.95f));
        }
    }

    // ========== LLM 调用参数验证 ==========

    @Nested
    @DisplayName("LLM 调用参数验证")
    class LLM调用参数验证 {

        @Test
        void 调用GenerationRouter时使用正确的超时时间() {
            mergeConfig.setLlmTimeoutSeconds(60);

            var expA = 创建经验("exp-1", "场景A", true);
            var expB = 创建经验("exp-2", "场景B", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景A")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.90f));
                    });

            模拟提示词渲染();
            模拟LLM调用(合并响应JSON("合并场景", "合并策略", true));

            merger.merge();

            ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(generationRouter).call(
                    eq("experience-merge"),
                    anyString(),
                    isNull(),
                    isNull(),
                    isNull(),
                    eq(GenerationCapability.CHAT),
                    timeoutCaptor.capture());
            assertThat(timeoutCaptor.getValue()).isEqualTo(Duration.ofSeconds(60));
        }

        @Test
        void LLM超时设为零时至少使用1秒() {
            mergeConfig.setLlmTimeoutSeconds(0);

            var expA = 创建经验("exp-1", "场景A", true);
            var expB = 创建经验("exp-2", "场景B", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("场景A")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.90f));
                    });

            模拟提示词渲染();
            模拟LLM调用(合并响应JSON("合并场景", "合并策略", true));

            merger.merge();

            ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(generationRouter).call(
                    eq("experience-merge"),
                    anyString(),
                    isNull(),
                    isNull(),
                    isNull(),
                    eq(GenerationCapability.CHAT),
                    timeoutCaptor.capture());
            // Math.max(1, 0) = 1
            assertThat(timeoutCaptor.getValue()).isEqualTo(Duration.ofSeconds(1));
        }

        @Test
        void 提示词模板变量包含正确的经验字段() {
            var expA = 创建经验("exp-1", "搜索场景", true);
            var expB = 创建经验("exp-2", "检索场景", true);
            when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE))
                    .thenReturn(List.of(expA, expB));
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.85f)))
                    .thenAnswer(invocation -> {
                        String query = invocation.getArgument(0);
                        if (query.contains("搜索场景")) {
                            return List.of(new VectorSearchResult("exp-2", 0.90f));
                        }
                        return List.of(new VectorSearchResult("exp-1", 0.90f));
                    });

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> varsCaptor = ArgumentCaptor.forClass(Map.class);
            when(promptRegistry.render(eq("memory/experience-merge"), varsCaptor.capture()))
                    .thenReturn("提示词");
            模拟LLM调用(合并响应JSON("合并场景", "策略", true));

            merger.merge();

            var vars = varsCaptor.getValue();
            // 验证 8 个模板变量键存在
            assertThat(vars).containsKeys(
                    "experienceA_scenario", "experienceA_strategy",
                    "experienceA_lessons", "experienceA_tools",
                    "experienceB_scenario", "experienceB_strategy",
                    "experienceB_lessons", "experienceB_tools");
            // 验证 scenario 和 strategy 内容
            assertThat(vars.get("experienceA_scenario")).isEqualTo("搜索场景");
            assertThat(vars.get("experienceA_strategy")).isEqualTo("策略描述");
        }
    }
}
