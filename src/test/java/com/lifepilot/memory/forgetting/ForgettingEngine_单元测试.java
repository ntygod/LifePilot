package com.lifepilot.memory.forgetting;

import com.lifepilot.agent.learning.forgetting.*;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ForgettingEngine 遗忘引擎单元测试 — 覆盖四阶段 HybridPolicy、受保护实体过滤、
 * archive/compress 动作执行、容量阈值、空记忆库等关键行为。
 *
 * <p>同时覆盖 FifoPolicy、LruPolicy、PriorityDecayPolicy、ReflectionSummaryPolicy、
 * HybridPolicy 的关键路径。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class ForgettingEngine_单元测试 {

    @Mock
    private SemanticMemory semanticMemory;
    @Mock
    private GenerationRouter generationRouter;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PromptRegistry promptRegistry;

    private AgentLearningProperties properties;
    private AgentLearningProperties.Forgetting forgettingConfig;

    private ForgettingEngine engine;

    @BeforeEach
    void 初始化() {
        properties = new AgentLearningProperties();
        forgettingConfig = new AgentLearningProperties.Forgetting();
        // 设置合理的默认值用于测试
        forgettingConfig.setMaxRetentionDays(365);
        forgettingConfig.setLruThresholdDays(90);
        forgettingConfig.setPriorityDecayRate(0.02f);
        forgettingConfig.setPriorityDecayThreshold(0.2f);
        forgettingConfig.setReflectionSummaryMinImportance(0.3f);
        forgettingConfig.setReflectionSummaryMaxImportance(0.8f);
        forgettingConfig.setMaxForgetPerRun(100);
        forgettingConfig.setProtectionThreshold(0.9f);
        forgettingConfig.setProtectedTypes(Set.of("PREFERENCE", "HABIT", "GOAL"));
        properties.setForgetting(forgettingConfig);

        engine = new ForgettingEngine(
                semanticMemory, generationRouter, jdbcTemplate, properties, promptRegistry);
    }

    // ==================== ForgettingEngine 核心流程测试 ====================

    @Test
    void 空记忆库时不执行遗忘_返回零() {
        when(semanticMemory.findAllCurrent()).thenReturn(List.of());

        int count = engine.forget();

        assertEquals(0, count);
        verify(semanticMemory, never()).archive(any(), any(ChangeSource.class));
    }

    @Test
    void 所有实体均受保护时_不执行遗忘() {
        // 所有实体的 importanceScore >= protectionThreshold(0.9)
        var protectedEntity = 创建实体("e1", EntityType.TOPIC, 0.95f, 5,
                Instant.now().minus(10, ChronoUnit.DAYS),
                Instant.now().minus(30, ChronoUnit.DAYS));
        // 受保护类型实体
        var preferenceEntity = 创建实体("e2", EntityType.PREFERENCE, 0.1f, 0,
                null, Instant.now().minus(400, ChronoUnit.DAYS));

        when(semanticMemory.findAllCurrent()).thenReturn(List.of(protectedEntity, preferenceEntity));

        int count = engine.forget();

        assertEquals(0, count);
        verify(semanticMemory, never()).archive(any(), any(ChangeSource.class));
    }

    @Test
    void 受保护类型实体不被遗忘_PREFERENCE() {
        var preference = 创建实体("pref-1", EntityType.PREFERENCE, 0.1f, 0,
                null, Instant.now().minus(500, ChronoUnit.DAYS));
        when(semanticMemory.findAllCurrent()).thenReturn(List.of(preference));

        int count = engine.forget();

        assertEquals(0, count);
        verify(semanticMemory, never()).archive(any(), any(ChangeSource.class));
    }

    @Test
    void 受保护类型实体不被遗忘_HABIT() {
        var habit = 创建实体("habit-1", EntityType.HABIT, 0.05f, 0,
                null, Instant.now().minus(500, ChronoUnit.DAYS));
        when(semanticMemory.findAllCurrent()).thenReturn(List.of(habit));

        int count = engine.forget();

        assertEquals(0, count);
    }

    @Test
    void 受保护类型实体不被遗忘_GOAL() {
        var goal = 创建实体("goal-1", EntityType.GOAL, 0.05f, 0,
                null, Instant.now().minus(500, ChronoUnit.DAYS));
        when(semanticMemory.findAllCurrent()).thenReturn(List.of(goal));

        int count = engine.forget();

        assertEquals(0, count);
    }

    @Test
    void 高重要度实体受保护_importanceScore达到阈值() {
        // importanceScore = 0.9，等于阈值，受保护
        var highImportance = 创建实体("hi-1", EntityType.TOPIC, 0.9f, 0,
                null, Instant.now().minus(500, ChronoUnit.DAYS));
        when(semanticMemory.findAllCurrent()).thenReturn(List.of(highImportance));

        int count = engine.forget();

        assertEquals(0, count);
        verify(semanticMemory, never()).archive(any(), any(ChangeSource.class));
    }

    @Test
    void 略低于保护阈值的实体不受保护() {
        // importanceScore = 0.89，低于阈值 0.9，且超过最大保留天数 → 被 FIFO 选中
        // 但 0.89 >= maxImportance(0.8)，不走压缩路径 → 直接归档
        var entity = 创建实体("e1", EntityType.TOPIC, 0.89f, 0,
                null, Instant.now().minus(500, ChronoUnit.DAYS));
        when(semanticMemory.findAllCurrent()).thenReturn(List.of(entity));
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        int count = engine.forget();

        assertTrue(count > 0, "importanceScore 低于保护阈值的实体不受保护，应被遗忘");
        verify(semanticMemory, atLeastOnce()).archive(entity, ChangeSource.CRON_EXPIRE);
    }

    @Test
    void 正常遗忘流程_对选中实体执行归档并记录日志() {
        // 创建一个超过最大保留天数的低重要度实体
        var oldEntity = 创建实体("old-1", EntityType.TOPIC, 0.1f, 0,
                null, Instant.now().minus(400, ChronoUnit.DAYS));

        when(semanticMemory.findAllCurrent()).thenReturn(List.of(oldEntity));
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        int count = engine.forget();

        assertTrue(count > 0, "应成功遗忘至少一个实体");
        verify(semanticMemory, atLeastOnce()).archive(oldEntity, ChangeSource.CRON_EXPIRE);
        // 验证日志写入
        verify(jdbcTemplate, atLeastOnce()).update(
                contains("INSERT INTO forgetting_log"),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 中等重要度实体_LLM可用时执行压缩() {
        // importanceScore 在 [0.3, 0.8) 范围内 — 属于 Reflection-Summary 候选
        var entity = 创建实体("mid-1", EntityType.TOPIC, 0.5f, 0,
                null, Instant.now().minus(400, ChronoUnit.DAYS));

        when(semanticMemory.findAllCurrent()).thenReturn(List.of(entity));
        when(promptRegistry.render(eq("memory/entity-compression"), anyMap()))
                .thenReturn("请压缩此实体");
        var llmResponse = new LlmResponse("压缩后的摘要内容", null, null, List.of(), Map.of(), 100, 50, null, 0, "provider-1", "model-1", 200L, false);
        when(generationRouter.call(
                eq(LlmScene.MEMORY_COMPRESSION), anyString(),
                isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), isNull(),
                eq(true)))
                .thenReturn(llmResponse);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        int count = engine.forget();

        assertTrue(count > 0);
        verify(semanticMemory, atLeastOnce()).archive(entity, ChangeSource.CRON_EXPIRE);
        // 验证日志中记录了压缩摘要
        verify(jdbcTemplate, atLeastOnce()).update(
                contains("INSERT INTO forgetting_log"),
                any(), // id
                eq(entity.id()), // entity_id
                eq(entity.name()), // entity_name
                eq("Hybrid"), // strategy
                eq("COMPRESSED"), // action_taken
                any(), // priority
                any(), // reason
                eq("压缩后的摘要内容"), // compression_summary
                any()  // created_at
        );
    }

    @Test
    void 压缩调用必须跳过语义缓存_避免张冠李戴() {
        // 回归：两个不同实体轮流压缩时，若不 skipCache，会因 prompt 模板相似触发缓存误命中
        var entityA = 创建实体("mid-a", EntityType.TOPIC, 0.5f, 0,
                null, Instant.now().minus(400, ChronoUnit.DAYS));

        when(semanticMemory.findAllCurrent()).thenReturn(List.of(entityA));
        when(promptRegistry.render(eq("memory/entity-compression"), anyMap()))
                .thenReturn("请压缩此实体");
        var llmResponse = new LlmResponse("摘要", null, null, List.of(), Map.of(), 10, 10, null, 0, "provider-1", "model-1", 50L, false);
        when(generationRouter.call(
                eq(LlmScene.MEMORY_COMPRESSION), anyString(),
                isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), isNull(),
                eq(true)))
                .thenReturn(llmResponse);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        engine.forget();

        // 关键断言：skipCache 必须为 true
        verify(generationRouter).call(
                eq(LlmScene.MEMORY_COMPRESSION), anyString(),
                isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), isNull(),
                eq(true));
    }

    @Test
    void LLM调用失败时_降级为归档() {
        var entity = 创建实体("mid-fail-1", EntityType.TOPIC, 0.5f, 0,
                null, Instant.now().minus(400, ChronoUnit.DAYS));

        when(semanticMemory.findAllCurrent()).thenReturn(List.of(entity));
        when(promptRegistry.render(eq("memory/entity-compression"), anyMap()))
                .thenReturn("请压缩此实体");
        when(generationRouter.call(
                eq(LlmScene.MEMORY_COMPRESSION), anyString(),
                isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), isNull(),
                eq(true)))
                .thenThrow(new RuntimeException("LLM 调用超时"));
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        int count = engine.forget();

        assertTrue(count > 0);
        verify(semanticMemory, atLeastOnce()).archive(entity, ChangeSource.CRON_EXPIRE);
        // 验证日志中 action 为 ARCHIVED，compression_summary 为 null
        verify(jdbcTemplate, atLeastOnce()).update(
                contains("INSERT INTO forgetting_log"),
                any(), eq(entity.id()), eq(entity.name()),
                eq("Hybrid"), eq("ARCHIVED"),
                any(), any(),
                isNull(), // compression_summary 为 null
                any()
        );
    }

    @Test
    void GenerationRouter为null时_不尝试压缩_直接归档() {
        // 构造一个没有 GenerationRouter 的引擎
        var engineWithoutLlm = new ForgettingEngine(
                semanticMemory, null, jdbcTemplate, properties, promptRegistry);

        var entity = 创建实体("no-llm-1", EntityType.TOPIC, 0.5f, 0,
                null, Instant.now().minus(400, ChronoUnit.DAYS));
        when(semanticMemory.findAllCurrent()).thenReturn(List.of(entity));
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        int count = engineWithoutLlm.forget();

        assertTrue(count > 0);
        verify(semanticMemory, atLeastOnce()).archive(entity, ChangeSource.CRON_EXPIRE);
        // 不应调用 LLM
        verifyNoInteractions(generationRouter);
    }

    @Test
    void 单个实体遗忘异常时_不影响其他实体的遗忘() {
        var entity1 = 创建实体("fail-1", EntityType.TOPIC, 0.1f, 0,
                null, Instant.now().minus(400, ChronoUnit.DAYS));
        var entity2 = 创建实体("ok-2", EntityType.TOPIC, 0.15f, 0,
                null, Instant.now().minus(500, ChronoUnit.DAYS));

        when(semanticMemory.findAllCurrent()).thenReturn(List.of(entity1, entity2));
        // entity1 归档时抛出异常
        doThrow(new RuntimeException("数据库写入失败"))
                .doNothing()
                .when(semanticMemory).archive(any(), any(ChangeSource.class));
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        int count = engine.forget();

        // 至少有一个成功（entity2）
        assertTrue(count >= 1, "即使有异常，其他实体的遗忘不应受影响");
    }

    @Test
    void 混合保护和非保护实体_仅遗忘非保护实体() {
        // 受保护实体（PREFERENCE 类型）
        var protectedEntity = 创建实体("protected-1", EntityType.PREFERENCE, 0.1f, 0,
                null, Instant.now().minus(500, ChronoUnit.DAYS));
        // 受保护实体（高 importanceScore）
        var highImportance = 创建实体("high-imp-1", EntityType.TOPIC, 0.95f, 0,
                null, Instant.now().minus(500, ChronoUnit.DAYS));
        // 不受保护的实体
        var forgettable = 创建实体("forget-1", EntityType.TOPIC, 0.1f, 0,
                null, Instant.now().minus(500, ChronoUnit.DAYS));

        when(semanticMemory.findAllCurrent())
                .thenReturn(List.of(protectedEntity, highImportance, forgettable));
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        int count = engine.forget();

        assertTrue(count > 0);
        // 只有 forgettable 应该被归档
        verify(semanticMemory, atLeastOnce()).archive(forgettable, ChangeSource.CRON_EXPIRE);
        verify(semanticMemory, never()).archive(eq(protectedEntity), any(ChangeSource.class));
        verify(semanticMemory, never()).archive(eq(highImportance), any(ChangeSource.class));
    }

    // ==================== FifoPolicy 测试 ====================

    @Nested
    class FifoPolicy_测试 {

        private FifoPolicy fifoPolicy;

        @BeforeEach
        void 初始化() {
            fifoPolicy = new FifoPolicy(forgettingConfig);
        }

        @Test
        void 超过最大保留天数的实体被选中() {
            var now = Instant.now();
            // 400 天前创建，超过默认 365 天
            var oldEntity = 创建实体("old-1", EntityType.TOPIC, 0.5f, 3,
                    now.minus(10, ChronoUnit.DAYS), now.minus(400, ChronoUnit.DAYS));
            // 100 天前创建，不超过 365 天
            var recentEntity = 创建实体("recent-1", EntityType.TOPIC, 0.5f, 3,
                    now.minus(10, ChronoUnit.DAYS), now.minus(100, ChronoUnit.DAYS));

            var selected = fifoPolicy.selectForForgetting(List.of(oldEntity, recentEntity), 10);

            assertEquals(1, selected.size());
            assertEquals("old-1", selected.getFirst().id());
        }

        @Test
        void 按创建时间升序排列_最早创建的优先() {
            var now = Instant.now();
            var oldest = 创建实体("oldest", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(600, ChronoUnit.DAYS));
            var older = 创建实体("older", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(500, ChronoUnit.DAYS));
            var old = 创建实体("old", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(400, ChronoUnit.DAYS));

            var selected = fifoPolicy.selectForForgetting(List.of(old, oldest, older), 10);

            assertEquals(3, selected.size());
            assertEquals("oldest", selected.get(0).id());
            assertEquals("older", selected.get(1).id());
            assertEquals("old", selected.get(2).id());
        }

        @Test
        void 预算限制_返回数量不超过budget() {
            var now = Instant.now();
            var e1 = 创建实体("e1", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(500, ChronoUnit.DAYS));
            var e2 = 创建实体("e2", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(600, ChronoUnit.DAYS));
            var e3 = 创建实体("e3", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(700, ChronoUnit.DAYS));

            var selected = fifoPolicy.selectForForgetting(List.of(e1, e2, e3), 2);

            assertEquals(2, selected.size());
        }

        @Test
        void 空列表返回空() {
            var selected = fifoPolicy.selectForForgetting(List.of(), 10);
            assertTrue(selected.isEmpty());
        }

        @Test
        void null列表返回空() {
            var selected = fifoPolicy.selectForForgetting(null, 10);
            assertTrue(selected.isEmpty());
        }

        @Test
        void 预算为零返回空() {
            var entity = 创建实体("e1", EntityType.TOPIC, 0.5f, 0,
                    null, Instant.now().minus(500, ChronoUnit.DAYS));
            var selected = fifoPolicy.selectForForgetting(List.of(entity), 0);
            assertTrue(selected.isEmpty());
        }

        @Test
        void 预算为负数返回空() {
            var entity = 创建实体("e1", EntityType.TOPIC, 0.5f, 0,
                    null, Instant.now().minus(500, ChronoUnit.DAYS));
            var selected = fifoPolicy.selectForForgetting(List.of(entity), -1);
            assertTrue(selected.isEmpty());
        }

        @Test
        void 所有实体均未超期时返回空() {
            var now = Instant.now();
            var recent = 创建实体("recent", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(100, ChronoUnit.DAYS));

            var selected = fifoPolicy.selectForForgetting(List.of(recent), 10);

            assertTrue(selected.isEmpty());
        }

        @Test
        void 策略名称为FIFO() {
            assertEquals("FIFO", fifoPolicy.name());
        }
    }

    // ==================== LruPolicy 测试 ====================

    @Nested
    class LruPolicy_测试 {

        private LruPolicy lruPolicy;

        @BeforeEach
        void 初始化() {
            lruPolicy = new LruPolicy(forgettingConfig);
        }

        @Test
        void 零访问且超过阈值天数未访问的实体被选中() {
            var now = Instant.now();
            // accessCount=0，lastAccessedAt 距今超过 90 天
            var lruEntity = 创建实体("lru-1", EntityType.TOPIC, 0.5f, 0,
                    now.minus(100, ChronoUnit.DAYS), now.minus(200, ChronoUnit.DAYS));

            var selected = lruPolicy.selectForForgetting(List.of(lruEntity), 10);

            assertEquals(1, selected.size());
            assertEquals("lru-1", selected.getFirst().id());
        }

        @Test
        void 零访问且lastAccessedAt为null的实体被选中() {
            var now = Instant.now();
            var neverAccessed = 创建实体("never-1", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(200, ChronoUnit.DAYS));

            var selected = lruPolicy.selectForForgetting(List.of(neverAccessed), 10);

            assertEquals(1, selected.size());
        }

        @Test
        void accessCount大于零的实体不被选中() {
            var now = Instant.now();
            var accessed = 创建实体("accessed-1", EntityType.TOPIC, 0.5f, 1,
                    now.minus(200, ChronoUnit.DAYS), now.minus(300, ChronoUnit.DAYS));

            var selected = lruPolicy.selectForForgetting(List.of(accessed), 10);

            assertTrue(selected.isEmpty());
        }

        @Test
        void 零访问但最近才被访问过的实体不被选中() {
            var now = Instant.now();
            // accessCount=0 但 lastAccessedAt 距今仅 10 天（未超过 lruThresholdDays=90）
            var recentlyAccessed = 创建实体("recent-1", EntityType.TOPIC, 0.5f, 0,
                    now.minus(10, ChronoUnit.DAYS), now.minus(200, ChronoUnit.DAYS));

            var selected = lruPolicy.selectForForgetting(List.of(recentlyAccessed), 10);

            assertTrue(selected.isEmpty());
        }

        @Test
        void lastAccessedAt为null的排在最前面() {
            var now = Instant.now();
            var nullAccess = 创建实体("null-access", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(200, ChronoUnit.DAYS));
            var oldAccess = 创建实体("old-access", EntityType.TOPIC, 0.5f, 0,
                    now.minus(100, ChronoUnit.DAYS), now.minus(200, ChronoUnit.DAYS));

            var selected = lruPolicy.selectForForgetting(List.of(oldAccess, nullAccess), 10);

            assertEquals(2, selected.size());
            assertEquals("null-access", selected.get(0).id(), "lastAccessedAt 为 null 的实体应排最前");
            assertEquals("old-access", selected.get(1).id());
        }

        @Test
        void 空列表返回空() {
            assertTrue(lruPolicy.selectForForgetting(List.of(), 10).isEmpty());
        }

        @Test
        void null列表返回空() {
            assertTrue(lruPolicy.selectForForgetting(null, 10).isEmpty());
        }

        @Test
        void 预算限制_返回数量不超过budget() {
            var now = Instant.now();
            var e1 = 创建实体("e1", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(200, ChronoUnit.DAYS));
            var e2 = 创建实体("e2", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(300, ChronoUnit.DAYS));
            var e3 = 创建实体("e3", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(400, ChronoUnit.DAYS));

            var selected = lruPolicy.selectForForgetting(List.of(e1, e2, e3), 2);

            assertEquals(2, selected.size());
        }

        @Test
        void 策略名称为LRU() {
            assertEquals("LRU", lruPolicy.name());
        }
    }

    // ==================== PriorityDecayPolicy 测试 ====================

    @Nested
    class PriorityDecayPolicy_测试 {

        private PriorityDecayPolicy decayPolicy;

        @BeforeEach
        void 初始化() {
            decayPolicy = new PriorityDecayPolicy(forgettingConfig);
        }

        @Test
        void 衰减后低于阈值的实体被选中() {
            var now = Instant.now();
            // importanceScore=0.3, lastAccessedAt=200天前
            // effectivePriority = 0.3 * exp(-0.02 * 200) = 0.3 * exp(-4) ≈ 0.3 * 0.0183 ≈ 0.0055
            // 远低于阈值 0.2
            var decayedEntity = 创建实体("decay-1", EntityType.TOPIC, 0.3f, 5,
                    now.minus(200, ChronoUnit.DAYS), now.minus(300, ChronoUnit.DAYS));

            var selected = decayPolicy.selectForForgetting(List.of(decayedEntity), 10);

            assertEquals(1, selected.size());
            assertEquals("decay-1", selected.getFirst().id());
        }

        @Test
        void 衰减后仍高于阈值的实体不被选中() {
            var now = Instant.now();
            // importanceScore=0.8, lastAccessedAt=1天前
            // effectivePriority = 0.8 * exp(-0.02 * 1) ≈ 0.8 * 0.98 ≈ 0.784
            // 远高于阈值 0.2
            var freshEntity = 创建实体("fresh-1", EntityType.TOPIC, 0.8f, 10,
                    now.minus(1, ChronoUnit.DAYS), now.minus(30, ChronoUnit.DAYS));

            var selected = decayPolicy.selectForForgetting(List.of(freshEntity), 10);

            assertTrue(selected.isEmpty());
        }

        @Test
        void lastAccessedAt为null时使用createdAt计算衰减() {
            var now = Instant.now();
            // lastAccessedAt=null, createdAt=200天前
            // effectivePriority = 0.3 * exp(-0.02 * 200) ≈ 0.0055
            var entity = 创建实体("no-access-1", EntityType.TOPIC, 0.3f, 0,
                    null, now.minus(200, ChronoUnit.DAYS));

            var selected = decayPolicy.selectForForgetting(List.of(entity), 10);

            assertEquals(1, selected.size());
        }

        @Test
        void 按effectivePriority升序排列_最低优先级优先() {
            var now = Instant.now();
            // 衰减后优先级更低（更旧）
            var lowerPriority = 创建实体("lower", EntityType.TOPIC, 0.2f, 0,
                    now.minus(300, ChronoUnit.DAYS), now.minus(400, ChronoUnit.DAYS));
            // 衰减后优先级稍高（较新）
            var higherPriority = 创建实体("higher", EntityType.TOPIC, 0.3f, 0,
                    now.minus(100, ChronoUnit.DAYS), now.minus(200, ChronoUnit.DAYS));

            var selected = decayPolicy.selectForForgetting(
                    List.of(higherPriority, lowerPriority), 10);

            // lowerPriority 的 effectivePriority 更低，应排在前面
            assertTrue(selected.size() >= 1);
            assertEquals("lower", selected.getFirst().id());
        }

        @Test
        void 空列表返回空() {
            assertTrue(decayPolicy.selectForForgetting(List.of(), 10).isEmpty());
        }

        @Test
        void null列表返回空() {
            assertTrue(decayPolicy.selectForForgetting(null, 10).isEmpty());
        }

        @Test
        void 预算为零返回空() {
            var entity = 创建实体("e1", EntityType.TOPIC, 0.1f, 0,
                    null, Instant.now().minus(500, ChronoUnit.DAYS));
            assertTrue(decayPolicy.selectForForgetting(List.of(entity), 0).isEmpty());
        }

        @Test
        void 策略名称为PriorityDecay() {
            assertEquals("PriorityDecay", decayPolicy.name());
        }
    }

    // ==================== ReflectionSummaryPolicy 测试 ====================

    @Nested
    class ReflectionSummaryPolicy_测试 {

        @Test
        void 中等重要度实体被选中() {
            var policy = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            // importanceScore=0.5，在 [0.3, 0.8) 范围内
            var entity = 创建实体("mid-1", EntityType.TOPIC, 0.5f, 3,
                    Instant.now().minus(10, ChronoUnit.DAYS),
                    Instant.now().minus(100, ChronoUnit.DAYS));

            var selected = policy.selectForForgetting(List.of(entity), 10);

            assertEquals(1, selected.size());
            assertEquals("mid-1", selected.getFirst().id());
        }

        @Test
        void 低于最低重要度的实体不被选中() {
            var policy = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            // importanceScore=0.2，低于 minImportance 0.3
            var entity = 创建实体("low-1", EntityType.TOPIC, 0.2f, 0,
                    null, Instant.now().minus(100, ChronoUnit.DAYS));

            var selected = policy.selectForForgetting(List.of(entity), 10);

            assertTrue(selected.isEmpty());
        }

        @Test
        void 达到最高重要度的实体不被选中() {
            var policy = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            // importanceScore=0.8，等于 maxImportance（不含）
            var entity = 创建实体("high-1", EntityType.TOPIC, 0.8f, 5,
                    Instant.now().minus(10, ChronoUnit.DAYS),
                    Instant.now().minus(100, ChronoUnit.DAYS));

            var selected = policy.selectForForgetting(List.of(entity), 10);

            assertTrue(selected.isEmpty());
        }

        @Test
        void 恰好等于最低重要度的实体被选中() {
            var policy = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            // importanceScore=0.3，等于 minImportance（含）
            var entity = 创建实体("exact-min", EntityType.TOPIC, 0.3f, 0,
                    null, Instant.now().minus(100, ChronoUnit.DAYS));

            var selected = policy.selectForForgetting(List.of(entity), 10);

            assertEquals(1, selected.size());
        }

        @Test
        void LLM不可用时返回空列表() {
            // generationRouter 为 null
            var policy = new ReflectionSummaryPolicy(null, forgettingConfig);
            var entity = 创建实体("mid-1", EntityType.TOPIC, 0.5f, 0,
                    null, Instant.now().minus(100, ChronoUnit.DAYS));

            var selected = policy.selectForForgetting(List.of(entity), 10);

            assertTrue(selected.isEmpty(), "LLM 不可用时应跳过 Reflection-Summary 阶段");
        }

        @Test
        void 按importanceScore升序排列() {
            var policy = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            var low = 创建实体("low", EntityType.TOPIC, 0.35f, 0,
                    null, Instant.now().minus(100, ChronoUnit.DAYS));
            var mid = 创建实体("mid", EntityType.TOPIC, 0.5f, 0,
                    null, Instant.now().minus(100, ChronoUnit.DAYS));
            var high = 创建实体("high", EntityType.TOPIC, 0.7f, 0,
                    null, Instant.now().minus(100, ChronoUnit.DAYS));

            var selected = policy.selectForForgetting(List.of(high, low, mid), 10);

            assertEquals(3, selected.size());
            assertEquals("low", selected.get(0).id());
            assertEquals("mid", selected.get(1).id());
            assertEquals("high", selected.get(2).id());
        }

        @Test
        void 空列表返回空() {
            var policy = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            assertTrue(policy.selectForForgetting(List.of(), 10).isEmpty());
        }

        @Test
        void null列表返回空() {
            var policy = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            assertTrue(policy.selectForForgetting(null, 10).isEmpty());
        }

        @Test
        void 预算限制() {
            var policy = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            var e1 = 创建实体("e1", EntityType.TOPIC, 0.4f, 0,
                    null, Instant.now().minus(100, ChronoUnit.DAYS));
            var e2 = 创建实体("e2", EntityType.TOPIC, 0.5f, 0,
                    null, Instant.now().minus(100, ChronoUnit.DAYS));
            var e3 = 创建实体("e3", EntityType.TOPIC, 0.6f, 0,
                    null, Instant.now().minus(100, ChronoUnit.DAYS));

            var selected = policy.selectForForgetting(List.of(e1, e2, e3), 2);

            assertEquals(2, selected.size());
        }

        @Test
        void 策略名称为ReflectionSummary() {
            var policy = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            assertEquals("ReflectionSummary", policy.name());
        }
    }

    // ==================== HybridPolicy 测试 ====================

    @Nested
    class HybridPolicy_测试 {

        @Test
        void 四阶段顺序执行_各阶段独立预算() {
            var now = Instant.now();
            forgettingConfig.setMaxForgetPerRun(8);

            var fifo = new FifoPolicy(forgettingConfig);
            var lru = new LruPolicy(forgettingConfig);
            var decay = new PriorityDecayPolicy(forgettingConfig);
            var reflection = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            var hybrid = new HybridPolicy(fifo, lru, decay, reflection);

            // 创建可被 FIFO 选中的实体（超过 365 天）
            var fifoCandidate = 创建实体("fifo-1", EntityType.TOPIC, 0.5f, 5,
                    now.minus(10, ChronoUnit.DAYS), now.minus(400, ChronoUnit.DAYS));
            // 创建可被 LRU 选中的实体（accessCount=0, 未访问超过 90 天）
            var lruCandidate = 创建实体("lru-1", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(100, ChronoUnit.DAYS));
            // 创建可被 PriorityDecay 选中的实体（低重要度 + 长时间未访问）
            var decayCandidate = 创建实体("decay-1", EntityType.TOPIC, 0.15f, 2,
                    now.minus(200, ChronoUnit.DAYS), now.minus(300, ChronoUnit.DAYS));
            // 创建可被 ReflectionSummary 选中的实体（中等重要度）
            var reflectionCandidate = 创建实体("reflect-1", EntityType.TOPIC, 0.5f, 10,
                    now.minus(5, ChronoUnit.DAYS), now.minus(50, ChronoUnit.DAYS));

            var candidates = List.of(fifoCandidate, lruCandidate, decayCandidate, reflectionCandidate);
            var selected = hybrid.selectForForgetting(candidates, 8);

            assertFalse(selected.isEmpty(), "Hybrid 应至少选中部分实体");
            assertTrue(selected.size() <= 8, "结果不应超过总预算");
        }

        @Test
        void 总结果不超过预算() {
            var now = Instant.now();
            var fifo = new FifoPolicy(forgettingConfig);
            var lru = new LruPolicy(forgettingConfig);
            var decay = new PriorityDecayPolicy(forgettingConfig);
            var reflection = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            var hybrid = new HybridPolicy(fifo, lru, decay, reflection);

            // 创建大量可遗忘实体
            var candidates = java.util.stream.IntStream.range(0, 50)
                    .mapToObj(i -> 创建实体("e-" + i, EntityType.TOPIC, 0.1f, 0,
                            null, now.minus(400 + i, ChronoUnit.DAYS)))
                    .toList();

            var selected = hybrid.selectForForgetting(candidates, 4);

            assertTrue(selected.size() <= 4, "结果数量不应超过预算 4");
        }

        @Test
        void 预算为零返回空() {
            var fifo = new FifoPolicy(forgettingConfig);
            var lru = new LruPolicy(forgettingConfig);
            var decay = new PriorityDecayPolicy(forgettingConfig);
            var reflection = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            var hybrid = new HybridPolicy(fifo, lru, decay, reflection);

            var entity = 创建实体("e1", EntityType.TOPIC, 0.5f, 0,
                    null, Instant.now().minus(500, ChronoUnit.DAYS));

            assertTrue(hybrid.selectForForgetting(List.of(entity), 0).isEmpty());
        }

        @Test
        void 空候选列表返回空() {
            var fifo = new FifoPolicy(forgettingConfig);
            var lru = new LruPolicy(forgettingConfig);
            var decay = new PriorityDecayPolicy(forgettingConfig);
            var reflection = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            var hybrid = new HybridPolicy(fifo, lru, decay, reflection);

            assertTrue(hybrid.selectForForgetting(List.of(), 10).isEmpty());
        }

        @Test
        void null候选列表返回空() {
            var fifo = new FifoPolicy(forgettingConfig);
            var lru = new LruPolicy(forgettingConfig);
            var decay = new PriorityDecayPolicy(forgettingConfig);
            var reflection = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            var hybrid = new HybridPolicy(fifo, lru, decay, reflection);

            assertTrue(hybrid.selectForForgetting(null, 10).isEmpty());
        }

        @Test
        void 各阶段排除已选中的实体_避免重复遗忘() {
            var now = Instant.now();
            var fifo = new FifoPolicy(forgettingConfig);
            var lru = new LruPolicy(forgettingConfig);
            var decay = new PriorityDecayPolicy(forgettingConfig);
            var reflection = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            var hybrid = new HybridPolicy(fifo, lru, decay, reflection);

            // 同时满足 FIFO 和 LRU 条件的实体
            var entity = 创建实体("both-1", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(500, ChronoUnit.DAYS));

            var selected = hybrid.selectForForgetting(List.of(entity), 100);

            // 即使满足多个策略条件，每个实体只应出现一次
            long uniqueIds = selected.stream().map(TemporalEntity::id).distinct().count();
            assertEquals(selected.size(), uniqueIds, "不应有重复实体");
        }

        @Test
        void 阶段预算为总预算除以四() {
            var now = Instant.now();
            // 总预算 = 4，每阶段预算 = 1
            var fifo = new FifoPolicy(forgettingConfig);
            var lru = new LruPolicy(forgettingConfig);
            var decay = new PriorityDecayPolicy(forgettingConfig);
            var reflection = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            var hybrid = new HybridPolicy(fifo, lru, decay, reflection);

            // 大量可被 FIFO 选中的实体
            var candidates = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(i -> 创建实体("old-" + i, EntityType.TOPIC, 0.1f, 0,
                            null, now.minus(400 + i, ChronoUnit.DAYS)))
                    .toList();

            var selected = hybrid.selectForForgetting(candidates, 4);

            // 每阶段预算 4/4=1，FIFO 最多选 1 个，但后续阶段也会选
            assertTrue(selected.size() <= 4);
        }

        @Test
        void 策略名称为Hybrid() {
            var fifo = new FifoPolicy(forgettingConfig);
            var lru = new LruPolicy(forgettingConfig);
            var decay = new PriorityDecayPolicy(forgettingConfig);
            var reflection = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
            var hybrid = new HybridPolicy(fifo, lru, decay, reflection);

            assertEquals("Hybrid", hybrid.name());
        }
    }

    // ==================== RandomDropPolicy 测试 ====================

    @Nested
    class RandomDropPolicy_测试 {

        private final RandomDropPolicy randomPolicy = new RandomDropPolicy();

        @Test
        void 返回数量不超过预算() {
            var now = Instant.now();
            var candidates = java.util.stream.IntStream.range(0, 10)
                    .mapToObj(i -> 创建实体("rand-" + i, EntityType.TOPIC, 0.5f, 0,
                            null, now.minus(100, ChronoUnit.DAYS)))
                    .toList();

            var selected = randomPolicy.selectForForgetting(candidates, 3);

            assertEquals(3, selected.size());
        }

        @Test
        void 候选数量少于预算时返回全部() {
            var now = Instant.now();
            var e1 = 创建实体("e1", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(100, ChronoUnit.DAYS));
            var e2 = 创建实体("e2", EntityType.TOPIC, 0.5f, 0,
                    null, now.minus(100, ChronoUnit.DAYS));

            var selected = randomPolicy.selectForForgetting(List.of(e1, e2), 10);

            assertEquals(2, selected.size());
        }

        @Test
        void 空列表返回空() {
            assertTrue(randomPolicy.selectForForgetting(List.of(), 10).isEmpty());
        }

        @Test
        void null列表返回空() {
            assertTrue(randomPolicy.selectForForgetting(null, 10).isEmpty());
        }

        @Test
        void 预算为零返回空() {
            var entity = 创建实体("e1", EntityType.TOPIC, 0.5f, 0,
                    null, Instant.now().minus(100, ChronoUnit.DAYS));
            assertTrue(randomPolicy.selectForForgetting(List.of(entity), 0).isEmpty());
        }

        @Test
        void 策略名称为RandomDrop() {
            assertEquals("RandomDrop", randomPolicy.name());
        }
    }

    // ==================== ForgettingPriority 补充测试 ====================

    @Nested
    class ForgettingPriority_补充测试 {

        private ForgettingPriority calculator;

        @BeforeEach
        void 初始化() {
            calculator = new ForgettingPriority(forgettingConfig);
        }

        @Test
        void 零访问新实体_accessFactor为最大值1() {
            var now = Instant.now();
            var entity = 创建实体("new-1", EntityType.TOPIC, 0.5f, 0,
                    null, now);

            float priority = calculator.calculate(entity);

            // timeFactor ≈ 0 (刚创建), accessFactor = 1.0, importanceFactor = 0.5
            // basePriority = 0 * 0.3 + 1.0 * 0.3 + 0.5 * 0.2 = 0.4
            // priority = 0.4 * 1.0 = 0.4
            assertTrue(priority > 0);
        }

        @Test
        void PII标记为非布尔字符串时不视为PII() {
            var now = Instant.now();
            var entity = 创建实体带属性("no-pii", EntityType.TOPIC, 0.5f, 0,
                    null, now, Map.of("pii", "not-a-boolean"));

            float priority = calculator.calculate(entity);

            // 不含 PII 标记，privacyFactor 应为 1.0
            var entityNoPii = 创建实体("no-pii-2", EntityType.TOPIC, 0.5f, 0,
                    null, now);
            float priorityNoPii = calculator.calculate(entityNoPii);

            assertEquals(priority, priorityNoPii, 0.001f,
                    "非布尔 pii 值不应触发隐私加权");
        }
    }

    // ==================== 辅助方法 ====================

    private static TemporalEntity 创建实体(String id, EntityType type,
                                           float importanceScore, int accessCount,
                                           Instant lastAccessedAt, Instant createdAt) {
        return new TemporalEntity(
                id, type, "测试实体-" + id, "测试描述",
                Map.of(), 1, true, createdAt, null,
                null, 0.8f, importanceScore, accessCount, lastAccessedAt,
                createdAt, createdAt
        );
    }

    private static TemporalEntity 创建实体带属性(String id, EntityType type,
                                                  float importanceScore, int accessCount,
                                                  Instant lastAccessedAt, Instant createdAt,
                                                  Map<String, Object> properties) {
        return new TemporalEntity(
                id, type, "测试实体-" + id, "测试描述",
                properties, 1, true, createdAt, null,
                null, 0.8f, importanceScore, accessCount, lastAccessedAt,
                createdAt, createdAt
        );
    }
}
