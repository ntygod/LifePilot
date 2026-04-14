package com.lifepilot.memory.experience;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ContrastiveLearner 单元测试 — 验证对比学习器的核心行为：
 * 轨迹匹配、LLM 分析、洞察持久化、降级处理。
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class ContrastiveLearner_单元测试 {

    @Mock
    private SemanticMemory semanticMemory;

    @Mock
    private VectorSearcher vectorSearcher;

    @Mock
    private GenerationRouter generationRouter;

    @Mock
    private PromptRegistry promptRegistry;

    private MemoryProperties memoryProperties;
    private ContrastiveLearner learner;

    @BeforeEach
    void 初始化() {
        memoryProperties = new MemoryProperties();
        memoryProperties.getExperience().getContrastive().setEnabled(true);
        memoryProperties.getExperience().getContrastive().setSimilarityThreshold(0.80f);
        memoryProperties.getExperience().getContrastive().setInitialImportance(0.7f);
        memoryProperties.getExperience().getContrastive().setLlmTimeoutSeconds(30);

        learner = new ContrastiveLearner(
                semanticMemory, vectorSearcher, generationRouter, promptRegistry, memoryProperties);
    }

    // ────────────── 辅助方法 ──────────────

    /** 构造一个带 success 标志的经验实体。 */
    private TemporalEntity 构造经验实体(String id, String name, boolean success) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("success", success);
        props.put("lessons", List.of("教训一"));
        props.put("toolsUsed", List.of("web.search"));
        return new TemporalEntity(
                id, EntityType.EXPERIENCE, name, "策略描述", props,
                1, true, Instant.now(), null, null,
                0.8f, 0.5f, 0, null, Instant.now(), Instant.now());
    }

    /** 构造合法的对比洞察 JSON。 */
    private String 合法洞察JSON() {
        return """
                {
                  "failureReason": "未充分检查前置条件",
                  "successFactor": "逐步验证每一步输出",
                  "contrastiveLessons": ["应在每步后验证", "错误传播需要早期截断"]
                }
                """;
    }

    /** 构造空字段的洞察 JSON。 */
    private String 空字段洞察JSON() {
        return """
                {
                  "failureReason": "",
                  "successFactor": "",
                  "contrastiveLessons": []
                }
                """;
    }

    /** 构造 LlmResponse。 */
    private LlmResponse 构造LLM响应(String content) {
        return new LlmResponse(content, 100, 50, "provider", "model", 200, false);
    }

    // ────────────── 功能开关 ──────────────

    @Nested
    class 功能开关 {

        @Test
        void 对比学习关闭时直接返回_不调用任何依赖() {
            memoryProperties.getExperience().getContrastive().setEnabled(false);
            learner = new ContrastiveLearner(
                    semanticMemory, vectorSearcher, generationRouter, promptRegistry, memoryProperties);

            var entity = 构造经验实体("e1", "测试经验", true);
            learner.learn(entity);

            verifyNoInteractions(vectorSearcher);
            verifyNoInteractions(generationRouter);
            verifyNoInteractions(semanticMemory);
        }
    }

    // ────────────── 正常对比分析流程 ──────────────

    @Nested
    class 正常对比分析流程 {

        @Test
        void 新成功经验匹配到失败经验_增强成功经验的lessons() {
            // given — 新经验为成功
            var newExp = 构造经验实体("success-1", "成功场景", true);
            var failureExp = 构造经验实体("failure-1", "失败场景", false);

            // 向量搜索返回一个匹配（排除自身后第一个就是对比轨迹）
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("failure-1", 0.85f)));

            when(semanticMemory.findById("failure-1")).thenReturn(Optional.of(failureExp));

            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("分析提示词");

            when(generationRouter.call(
                    eq("contrastive-learning"),
                    eq("分析提示词"),
                    isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT),
                    eq(Duration.ofSeconds(30))))
                    .thenReturn(构造LLM响应(合法洞察JSON()));

            // when
            learner.learn(newExp);

            // then — 成功经验被增强写回
            var entityCaptor = ArgumentCaptor.forClass(TemporalEntity.class);
            verify(semanticMemory).upsertWithConflictDetection(entityCaptor.capture(), eq("contrastive-learning"));

            var enriched = entityCaptor.getValue();
            assertThat(enriched.id()).isEqualTo("success-1");
            assertThat(enriched.type()).isEqualTo(EntityType.EXPERIENCE);
            assertThat(enriched.name()).isEqualTo("成功场景");
            assertThat(enriched.properties().get("contrastiveEnriched")).isEqualTo(true);
            @SuppressWarnings("unchecked")
            var lessons = (List<String>) enriched.properties().get("lessons");
            assertThat(lessons).contains(
                    "教训一",
                    "[对比] 失败原因: 未充分检查前置条件",
                    "[对比] 成功因素: 逐步验证每一步输出");
            assertThat(enriched.properties().get("success")).isEqualTo(true);
        }

        @Test
        void 新失败经验匹配到成功经验_增强成功经验而非失败经验() {
            // given — 新经验为失败
            var newExp = 构造经验实体("failure-2", "失败场景B", false);
            var successExp = 构造经验实体("success-2", "成功场景B", true);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("success-2", 0.90f)));

            when(semanticMemory.findById("success-2")).thenReturn(Optional.of(successExp));

            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("分析提示词");

            when(generationRouter.call(
                    eq("contrastive-learning"),
                    eq("分析提示词"),
                    isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT),
                    eq(Duration.ofSeconds(30))))
                    .thenReturn(构造LLM响应(合法洞察JSON()));

            // when
            learner.learn(newExp);

            // then — 增强的是成功经验（success-2），而非新传入的失败经验
            var entityCaptor = ArgumentCaptor.forClass(TemporalEntity.class);
            verify(semanticMemory).upsertWithConflictDetection(entityCaptor.capture(), eq("contrastive-learning"));

            var enriched = entityCaptor.getValue();
            assertThat(enriched.id()).isEqualTo("success-2");
            assertThat(enriched.properties().get("contrastiveEnriched")).isEqualTo(true);
        }

        @Test
        void 提示词变量包含经验的所有关键字段() {
            // given
            var newExp = 构造经验实体("s1", "成功场景C", true);
            var failureExp = 构造经验实体("f1", "失败场景C", false);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("f1", 0.85f)));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> varsCaptor = ArgumentCaptor.forClass(Map.class);
            when(promptRegistry.render(eq("memory/contrastive-learning"), varsCaptor.capture()))
                    .thenReturn("prompt");

            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenReturn(构造LLM响应(合法洞察JSON()));

            // when
            learner.learn(newExp);

            // then — 提示词变量包含正确的成功/失败字段
            var vars = varsCaptor.getValue();
            assertThat(vars).containsKeys(
                    "successScenario", "successStrategy", "successLessons", "successTools",
                    "failureScenario", "failureStrategy", "failureLessons", "failureTools");
            assertThat(vars.get("successScenario")).isEqualTo("成功场景C");
            assertThat(vars.get("failureScenario")).isEqualTo("失败场景C");
        }
    }

    // ────────────── 无匹配轨迹 ──────────────

    @Nested
    class 无匹配轨迹 {

        @Test
        void 向量搜索返回空列表_不调用LLM() {
            var newExp = 构造经验实体("e1", "经验A", true);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of());

            learner.learn(newExp);

            verifyNoInteractions(generationRouter);
            verify(semanticMemory, never()).upsertWithConflictDetection(any(), anyString());
        }

        @Test
        void 搜索结果全部与新经验同为成功_无对比对() {
            var newExp = 构造经验实体("s1", "成功A", true);
            var anotherSuccess = 构造经验实体("s2", "成功B", true);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("s2", 0.88f)));
            when(semanticMemory.findById("s2")).thenReturn(Optional.of(anotherSuccess));

            learner.learn(newExp);

            verifyNoInteractions(generationRouter);
        }

        @Test
        void 搜索结果全部与新经验同为失败_无对比对() {
            var newExp = 构造经验实体("f1", "失败A", false);
            var anotherFailure = 构造经验实体("f2", "失败B", false);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("f2", 0.88f)));
            when(semanticMemory.findById("f2")).thenReturn(Optional.of(anotherFailure));

            learner.learn(newExp);

            verifyNoInteractions(generationRouter);
        }

        @Test
        void 搜索结果包含自身ID_应跳过自身() {
            var newExp = 构造经验实体("e1", "经验X", true);
            var failureExp = 构造经验实体("f1", "失败X", false);

            // 第一个结果是自身，第二个是对比轨迹
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(
                            new VectorSearchResult("e1", 1.0f),
                            new VectorSearchResult("f1", 0.85f)));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));
            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("prompt");
            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenReturn(构造LLM响应(合法洞察JSON()));

            learner.learn(newExp);

            // 自身被跳过，但仍找到匹配的 failure-1
            verify(semanticMemory).upsertWithConflictDetection(any(TemporalEntity.class), eq("contrastive-learning"));
        }

        @Test
        void 搜索结果实体在语义记忆中不存在_跳过该结果() {
            var newExp = 构造经验实体("s1", "成功Y", true);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("ghost-id", 0.90f)));
            when(semanticMemory.findById("ghost-id")).thenReturn(Optional.empty());

            learner.learn(newExp);

            verifyNoInteractions(generationRouter);
        }

        @Test
        void 搜索结果实体类型非EXPERIENCE_跳过该结果() {
            var newExp = 构造经验实体("s1", "成功Z", true);
            // 构造一个 TOPIC 类型实体
            var topicEntity = new TemporalEntity(
                    "t1", EntityType.TOPIC, "话题A", null, Map.of("success", false),
                    1, true, Instant.now(), null, null,
                    0.8f, 0.5f, 0, null, Instant.now(), Instant.now());

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("t1", 0.90f)));
            when(semanticMemory.findById("t1")).thenReturn(Optional.of(topicEntity));

            learner.learn(newExp);

            verifyNoInteractions(generationRouter);
        }
    }

    // ────────────── LLM 降级与异常处理 ──────────────

    @Nested
    class LLM降级与异常处理 {

        @Test
        void LLM调用抛出异常_analyzeContrast返回null_不持久化() {
            var newExp = 构造经验实体("s1", "成功E", true);
            var failureExp = 构造经验实体("f1", "失败E", false);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("f1", 0.85f)));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));
            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("prompt");
            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenThrow(new RuntimeException("LLM 服务不可用"));

            learner.learn(newExp);

            verify(semanticMemory, never()).upsertWithConflictDetection(any(), anyString());
        }

        @Test
        void LLM返回无法解析的JSON_analyzeContrast返回null_不持久化() {
            var newExp = 构造经验实体("s1", "成功F", true);
            var failureExp = 构造经验实体("f1", "失败F", false);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("f1", 0.85f)));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));
            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("prompt");
            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenReturn(构造LLM响应("这不是一个合法的JSON"));

            learner.learn(newExp);

            verify(semanticMemory, never()).upsertWithConflictDetection(any(), anyString());
        }

        @Test
        void 向量搜索阶段抛出异常_外层catch兜底_不崩溃() {
            var newExp = 构造经验实体("e1", "经验G", true);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenThrow(new RuntimeException("向量服务宕机"));

            // 不应抛出异常
            learner.learn(newExp);

            verifyNoInteractions(generationRouter);
        }

        @Test
        void 持久化阶段抛出异常_外层catch兜底_不崩溃() {
            var newExp = 构造经验实体("s1", "成功H", true);
            var failureExp = 构造经验实体("f1", "失败H", false);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("f1", 0.85f)));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));
            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("prompt");
            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenReturn(构造LLM响应(合法洞察JSON()));
            doThrow(new RuntimeException("数据库写入失败"))
                    .when(semanticMemory).upsertWithConflictDetection(any(), anyString());

            // 不应抛出异常
            learner.learn(newExp);
        }
    }

    // ────────────── 空洞察丢弃 ──────────────

    @Nested
    class 空洞察丢弃 {

        @Test
        void failureReason为空字符串_丢弃洞察() {
            var newExp = 构造经验实体("s1", "成功I", true);
            var failureExp = 构造经验实体("f1", "失败I", false);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("f1", 0.85f)));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));
            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("prompt");
            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenReturn(构造LLM响应(空字段洞察JSON()));

            learner.learn(newExp);

            verify(semanticMemory, never()).upsertWithConflictDetection(any(), anyString());
        }

        @Test
        void successFactor为空白字符_丢弃洞察() {
            var newExp = 构造经验实体("s1", "成功J", true);
            var failureExp = 构造经验实体("f1", "失败J", false);

            String blankSuccessJSON = """
                    {
                      "failureReason": "原因存在",
                      "successFactor": "   ",
                      "contrastiveLessons": []
                    }
                    """;
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("f1", 0.85f)));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));
            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("prompt");
            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenReturn(构造LLM响应(blankSuccessJSON));

            learner.learn(newExp);

            verify(semanticMemory, never()).upsertWithConflictDetection(any(), anyString());
        }

        @Test
        void failureReason为null_丢弃洞察() {
            var newExp = 构造经验实体("s1", "成功K", true);
            var failureExp = 构造经验实体("f1", "失败K", false);

            String nullFieldJSON = """
                    {
                      "failureReason": null,
                      "successFactor": "有效因素",
                      "contrastiveLessons": []
                    }
                    """;
            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("f1", 0.85f)));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));
            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("prompt");
            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenReturn(构造LLM响应(nullFieldJSON));

            learner.learn(newExp);

            verify(semanticMemory, never()).upsertWithConflictDetection(any(), anyString());
        }
    }

    // ────────────── 增强实体结构验证 ──────────────

    @Nested
    class 增强实体结构验证 {

        @Test
        void 增强后保留原始经验的name和description() {
            var newExp = 构造经验实体("s1", "成功L", true);
            var failureExp = 构造经验实体("f1", "失败L", false);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("f1", 0.85f)));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));
            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("prompt");
            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenReturn(构造LLM响应(合法洞察JSON()));

            learner.learn(newExp);

            var entityCaptor = ArgumentCaptor.forClass(TemporalEntity.class);
            verify(semanticMemory).upsertWithConflictDetection(entityCaptor.capture(), eq("contrastive-learning"));
            var enriched = entityCaptor.getValue();
            assertThat(enriched.name()).isEqualTo("成功L");
            assertThat(enriched.description()).isEqualTo("策略描述");
        }

        @Test
        void 源经验lessons为null时从空列表开始追加() {
            // 构造一个 lessons 为 null 的经验
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("success", true);
            props.put("toolsUsed", List.of("web.search"));
            // 不设置 lessons
            var newExp = new TemporalEntity(
                    "s1", EntityType.EXPERIENCE, "成功M", "策略描述", props,
                    1, true, Instant.now(), null, null,
                    0.8f, 0.5f, 0, null, Instant.now(), Instant.now());
            var failureExp = 构造经验实体("f1", "失败M", false);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("f1", 0.85f)));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));
            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("prompt");
            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenReturn(构造LLM响应(合法洞察JSON()));

            learner.learn(newExp);

            var entityCaptor = ArgumentCaptor.forClass(TemporalEntity.class);
            verify(semanticMemory).upsertWithConflictDetection(entityCaptor.capture(), eq("contrastive-learning"));
            @SuppressWarnings("unchecked")
            var lessons = (List<String>) entityCaptor.getValue().properties().get("lessons");
            assertThat(lessons).hasSize(2);
            assertThat(lessons.get(0)).startsWith("[对比] 失败原因:");
            assertThat(lessons.get(1)).startsWith("[对比] 成功因素:");
        }

        @Test
        void 增强后不调用vectorSearcher的upsertEntityVector() {
            var newExp = 构造经验实体("s1", "成功N", true);
            var failureExp = 构造经验实体("f1", "失败N", false);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("f1", 0.85f)));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));
            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("prompt");
            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenReturn(构造LLM响应(合法洞察JSON()));

            learner.learn(newExp);

            // 增强模式不创建新实体，不需要写入新向量
            verify(vectorSearcher, never()).upsertEntityVector(anyString(), anyString());
        }
    }

    // ────────────── 边界情况 ──────────────

    @Nested
    class 边界情况 {

        @Test
        void 经验无success属性_默认视为false() {
            // 不设置 success 属性
            var newExp = new TemporalEntity(
                    "e1", EntityType.EXPERIENCE, "无标志经验", "描述", Map.of(),
                    1, true, Instant.now(), null, null,
                    0.8f, 0.5f, 0, null, Instant.now(), Instant.now());

            var successExp = 构造经验实体("s1", "成功P", true);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("s1", 0.90f)));
            when(semanticMemory.findById("s1")).thenReturn(Optional.of(successExp));
            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("prompt");
            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenReturn(构造LLM响应(合法洞察JSON()));

            learner.learn(newExp);

            // 无 success 属性时 Boolean.TRUE.equals(null) == false，应与 success=true 的 s1 配对
            // 增强的是成功经验 s1
            var entityCaptor = ArgumentCaptor.forClass(TemporalEntity.class);
            verify(semanticMemory).upsertWithConflictDetection(entityCaptor.capture(), eq("contrastive-learning"));
            assertThat(entityCaptor.getValue().id()).isEqualTo("s1");
            assertThat(entityCaptor.getValue().properties().get("contrastiveEnriched")).isEqualTo(true);
        }

        @Test
        void 经验description为null时提示词变量使用空字符串() {
            var newExp = new TemporalEntity(
                    "s1", EntityType.EXPERIENCE, "成功Q", null,
                    Map.of("success", true, "lessons", List.of(), "toolsUsed", List.of()),
                    1, true, Instant.now(), null, null,
                    0.8f, 0.5f, 0, null, Instant.now(), Instant.now());
            var failureExp = new TemporalEntity(
                    "f1", EntityType.EXPERIENCE, "失败Q", null,
                    Map.of("success", false, "lessons", List.of(), "toolsUsed", List.of()),
                    1, true, Instant.now(), null, null,
                    0.8f, 0.5f, 0, null, Instant.now(), Instant.now());

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("f1", 0.85f)));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> varsCaptor = ArgumentCaptor.forClass(Map.class);
            when(promptRegistry.render(eq("memory/contrastive-learning"), varsCaptor.capture()))
                    .thenReturn("prompt");
            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenReturn(构造LLM响应(合法洞察JSON()));

            learner.learn(newExp);

            var vars = varsCaptor.getValue();
            assertThat(vars.get("successStrategy")).isEqualTo("");
            assertThat(vars.get("failureStrategy")).isEqualTo("");
        }

        @Test
        void 搜索结果中跳过多个不合格结果后找到合格对比轨迹() {
            var newExp = 构造经验实体("s1", "成功R", true);
            var sameSuccess = 构造经验实体("s2", "成功同类", true);
            var topicEntity = new TemporalEntity(
                    "t1", EntityType.TOPIC, "话题", null, Map.of("success", false),
                    1, true, Instant.now(), null, null,
                    0.8f, 0.5f, 0, null, Instant.now(), Instant.now());
            var failureExp = 构造经验实体("f1", "失败R", false);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(
                            new VectorSearchResult("s1", 1.0f),   // 自身 → 跳过
                            new VectorSearchResult("ghost", 0.95f), // 不存在 → 跳过
                            new VectorSearchResult("s2", 0.92f),   // 同为成功 → 跳过
                            new VectorSearchResult("t1", 0.88f),   // 非 EXPERIENCE → 跳过
                            new VectorSearchResult("f1", 0.82f)    // 匹配！
                    ));
            when(semanticMemory.findById("ghost")).thenReturn(Optional.empty());
            when(semanticMemory.findById("s2")).thenReturn(Optional.of(sameSuccess));
            when(semanticMemory.findById("t1")).thenReturn(Optional.of(topicEntity));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));
            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("prompt");
            when(generationRouter.call(anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any(Duration.class)))
                    .thenReturn(构造LLM响应(合法洞察JSON()));

            learner.learn(newExp);

            // 增强的是成功经验 s1
            var entityCaptor = ArgumentCaptor.forClass(TemporalEntity.class);
            verify(semanticMemory).upsertWithConflictDetection(entityCaptor.capture(), eq("contrastive-learning"));
            assertThat(entityCaptor.getValue().id()).isEqualTo("s1");
            assertThat(entityCaptor.getValue().properties().get("contrastiveEnriched")).isEqualTo(true);
        }

        @Test
        void llmTimeoutSeconds为零时被Math_max矫正为1秒() {
            memoryProperties.getExperience().getContrastive().setLlmTimeoutSeconds(0);
            learner = new ContrastiveLearner(
                    semanticMemory, vectorSearcher, generationRouter, promptRegistry, memoryProperties);

            var newExp = 构造经验实体("s1", "成功S", true);
            var failureExp = 构造经验实体("f1", "失败S", false);

            when(vectorSearcher.searchEntities(anyString(), eq(5), eq(0.80f)))
                    .thenReturn(List.of(new VectorSearchResult("f1", 0.85f)));
            when(semanticMemory.findById("f1")).thenReturn(Optional.of(failureExp));
            when(promptRegistry.render(eq("memory/contrastive-learning"), anyMap()))
                    .thenReturn("prompt");
            when(generationRouter.call(
                    eq("contrastive-learning"),
                    eq("prompt"),
                    isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT),
                    eq(Duration.ofSeconds(1))))   // Math.max(1, 0) == 1
                    .thenReturn(构造LLM响应(合法洞察JSON()));

            learner.learn(newExp);

            verify(generationRouter).call(
                    anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class),
                    eq(Duration.ofSeconds(1)));
        }
    }
}
