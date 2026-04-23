package com.lifepilot.memory.experience;

import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.lifecycle.WeightSource;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.floatThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * EffectivenessTracker 单元测试 — 覆盖注入记录、效果评估、分数调整与淘汰逻辑。
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class EffectivenessTracker_单元测试 {

    @Mock
    private SemanticMemory semanticMemory;

    @Mock
    private InjectionRecordRepository injectionRecordRepository;

    private MemoryProperties memoryProperties;
    private EffectivenessTracker tracker;

    @BeforeEach
    void 初始化() {
        memoryProperties = new MemoryProperties();
        // 使用默认配置：successRatioThreshold=0.5, positiveBoost=0.05, negativeDecay=0.03, evictionThreshold=0.1
        tracker = new EffectivenessTracker(semanticMemory, injectionRecordRepository, memoryProperties);
    }

    // ==================== recordInjection 测试 ====================

    @Nested
    class 记录注入事件 {

        @Test
        void 正常记录注入_应委托给仓储层保存() {
            // given
            var traceId = "trace-001";
            var entityIds = List.of("entity-a", "entity-b");

            // when
            tracker.recordInjection(traceId, entityIds);

            // then
            verify(injectionRecordRepository).saveWithType(traceId, traceId, entityIds, "EXPERIENCE");
        }

        @Test
        void 仓储层异常_应吞没异常不向外抛出() {
            // given
            var traceId = "trace-err";
            var entityIds = List.of("entity-x");
            doThrow(new RuntimeException("数据库写入失败"))
                    .when(injectionRecordRepository).saveWithType(any(), any(), any(), any());

            // when — 不应抛异常
            tracker.recordInjection(traceId, entityIds);

            // then — 方法正常返回，异常被 catch
            verify(injectionRecordRepository).saveWithType(traceId, traceId, entityIds, "EXPERIENCE");
        }
    }

    // ==================== evaluate 测试 ====================

    @Nested
    class 效果评估 {

        @Test
        void 无注入记录_应跳过评估() {
            // given
            var state = buildState(null, List.of(
                    new ReactStep.Observation("tool.a", "工具A", true, "ok", 10)
            ));
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of());

            // when
            tracker.evaluate(state, "trace-001");

            // then — 不应与 semanticMemory 交互
            verifyNoInteractions(semanticMemory);
        }

        @Test
        void 全部工具调用成功且无终止原因_判定有效_应提升分数() {
            // given
            var state = buildState(null, List.of(
                    new ReactStep.Observation("tool.a", "工具A", true, "ok", 10),
                    new ReactStep.Observation("tool.b", "工具B", true, "ok", 5)
            ));
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-1"));
            when(semanticMemory.findById("entity-1"))
                    .thenReturn(Optional.of(buildEntity("entity-1", 0.5f)));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 有效，分数从 0.5 提升 0.05 到 0.55
            verify(semanticMemory).updateImportanceScore("entity-1", 0.55f, WeightSource.EFFECTIVENESS);
        }

        @Test
        void 工具成功率低于阈值_判定无效_应衰减分数() {
            // given — 3 个 Observation：1 成功 2 失败，成功率 0.333 < 0.5
            var state = buildState(null, List.of(
                    new ReactStep.Observation("tool.a", "工具A", true, "ok", 10),
                    new ReactStep.Observation("tool.b", "工具B", false, "fail", 5),
                    new ReactStep.Observation("tool.c", "工具C", false, "fail", 5)
            ));
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-2"));
            when(semanticMemory.findById("entity-2"))
                    .thenReturn(Optional.of(buildEntity("entity-2", 0.5f)));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 无效，分数从 0.5 衰减 0.03 到 0.47
            verify(semanticMemory).updateImportanceScore("entity-2", 0.47f, WeightSource.EFFECTIVENESS);
        }

        @Test
        void 存在终止原因_即使工具全部成功也判定无效() {
            // given
            var state = buildState("超时终止", List.of(
                    new ReactStep.Observation("tool.a", "工具A", true, "ok", 10)
            ));
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-3"));
            when(semanticMemory.findById("entity-3"))
                    .thenReturn(Optional.of(buildEntity("entity-3", 0.5f)));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 无效（因 terminationReason 非 null），分数从 0.5 衰减 0.03 到 0.47
            verify(semanticMemory).updateImportanceScore("entity-3", 0.47f, WeightSource.EFFECTIVENESS);
        }

        @Test
        void 多个注入经验_应逐个调整分数() {
            // given
            var state = buildState(null, List.of(
                    new ReactStep.Observation("tool.a", "工具A", true, "ok", 10)
            ));
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-a", "entity-b", "entity-c"));
            when(semanticMemory.findById("entity-a"))
                    .thenReturn(Optional.of(buildEntity("entity-a", 0.6f)));
            when(semanticMemory.findById("entity-b"))
                    .thenReturn(Optional.of(buildEntity("entity-b", 0.3f)));
            when(semanticMemory.findById("entity-c"))
                    .thenReturn(Optional.of(buildEntity("entity-c", 0.9f)));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 有效，三个实体分别提升 0.05（float 精度容差）
            verify(semanticMemory).updateImportanceScore(eq("entity-a"),
                    floatThat(v -> Math.abs(v - 0.65f) < 0.001f), eq(WeightSource.EFFECTIVENESS));
            verify(semanticMemory).updateImportanceScore(eq("entity-b"),
                    floatThat(v -> Math.abs(v - 0.35f) < 0.001f), eq(WeightSource.EFFECTIVENESS));
            verify(semanticMemory).updateImportanceScore(eq("entity-c"),
                    floatThat(v -> Math.abs(v - 0.95f) < 0.001f), eq(WeightSource.EFFECTIVENESS));
        }

        @Test
        void 查询注入记录异常_应吞没异常不向外抛出() {
            // given
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-err", "EXPERIENCE"))
                    .thenThrow(new RuntimeException("查询失败"));

            // when — 不应抛异常
            tracker.evaluate(buildState(null, List.of()), "trace-err");

            // then
            verifyNoInteractions(semanticMemory);
        }

        @Test
        void 单个实体调整分数异常_应继续处理其余实体() {
            // given
            var state = buildState(null, List.of(
                    new ReactStep.Observation("tool.a", "工具A", true, "ok", 10)
            ));
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-err", "entity-ok"));
            when(semanticMemory.findById("entity-err"))
                    .thenThrow(new RuntimeException("查找失败"));
            when(semanticMemory.findById("entity-ok"))
                    .thenReturn(Optional.of(buildEntity("entity-ok", 0.7f)));

            // when
            tracker.evaluate(state, "trace-001");

            // then — entity-err 失败但 entity-ok 仍应被处理
            verify(semanticMemory).updateImportanceScore("entity-ok", 0.75f, WeightSource.EFFECTIVENESS);
        }

        @Test
        void 实体不存在_findById返回空_应跳过不报错() {
            // given
            var state = buildState(null, List.of(
                    new ReactStep.Observation("tool.a", "工具A", true, "ok", 10)
            ));
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-gone"));
            when(semanticMemory.findById("entity-gone"))
                    .thenReturn(Optional.empty());

            // when
            tracker.evaluate(state, "trace-001");

            // then — 不应调用 updateImportanceScore 或 archive
            verify(semanticMemory, never()).updateImportanceScore(any(), any(float.class), any(WeightSource.class));
            verify(semanticMemory, never()).archive(any());
        }
    }

    // ==================== 分数边界与淘汰逻辑 ====================

    @Nested
    class 分数边界与淘汰 {

        @Test
        void 有效提升后分数超过1_应裁剪到1() {
            // given — 当前分数 0.98，提升 0.05 → min(1.03, 1.0) = 1.0
            var state = buildState(null, List.of(
                    new ReactStep.Observation("tool.a", "工具A", true, "ok", 10)
            ));
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-high"));
            when(semanticMemory.findById("entity-high"))
                    .thenReturn(Optional.of(buildEntity("entity-high", 0.98f)));

            // when
            tracker.evaluate(state, "trace-001");

            // then
            verify(semanticMemory).updateImportanceScore("entity-high", 1.0f, WeightSource.EFFECTIVENESS);
        }

        @Test
        void 无效衰减后分数低于淘汰阈值_应归档经验() {
            // given — 当前分数 0.12，衰减 0.03 → 0.09 < evictionThreshold(0.1)
            var state = buildState(null, List.of(
                    new ReactStep.Observation("tool.a", "工具A", false, "fail", 5)
            ));
            var entity = buildEntity("entity-low", 0.12f);
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-low"));
            when(semanticMemory.findById("entity-low"))
                    .thenReturn(Optional.of(entity));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 应归档而非更新分数
            verify(semanticMemory).archive(entity);
            verify(semanticMemory, never()).updateImportanceScore(eq("entity-low"), any(float.class), any(WeightSource.class));
        }

        @Test
        void 无效衰减后分数仍高于淘汰阈值_不应归档() {
            // given — 当前分数 0.20，衰减 0.03 → 0.17 > evictionThreshold(0.1)，不触发淘汰
            var state = buildState(null, List.of(
                    new ReactStep.Observation("tool.a", "工具A", false, "fail", 5)
            ));
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-boundary"));
            when(semanticMemory.findById("entity-boundary"))
                    .thenReturn(Optional.of(buildEntity("entity-boundary", 0.20f)));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 分数 ~0.17 > 0.1 淘汰阈值，不应归档
            verify(semanticMemory).updateImportanceScore(eq("entity-boundary"),
                    floatThat(v -> Math.abs(v - 0.17f) < 0.001f), eq(WeightSource.EFFECTIVENESS));
            verify(semanticMemory, never()).archive(any());
        }

        @Test
        void 无效衰减后分数低于0_应裁剪到0并触发淘汰() {
            // given — 当前分数 0.01，衰减 0.03 → max(-0.02, 0.0) = 0.0 < 0.1 → 归档
            var state = buildState(null, List.of(
                    new ReactStep.Observation("tool.a", "工具A", false, "fail", 5)
            ));
            var entity = buildEntity("entity-zero", 0.01f);
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-zero"));
            when(semanticMemory.findById("entity-zero"))
                    .thenReturn(Optional.of(entity));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 分数 0.0 < 0.1 淘汰阈值，应归档
            verify(semanticMemory).archive(entity);
            verify(semanticMemory, never()).updateImportanceScore(eq("entity-zero"), any(float.class), any(WeightSource.class));
        }
    }

    // ==================== calcToolSuccessRatio 边界场景 ====================

    @Nested
    class 工具成功率计算 {

        @Test
        void 无步骤_成功率为0_判定无效() {
            // given — steps 为空列表
            var state = buildState(null, List.of());
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-empty"));
            when(semanticMemory.findById("entity-empty"))
                    .thenReturn(Optional.of(buildEntity("entity-empty", 0.5f)));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 成功率 0.0 < 0.5 阈值，判定无效，衰减
            verify(semanticMemory).updateImportanceScore("entity-empty", 0.47f, WeightSource.EFFECTIVENESS);
        }

        @Test
        void 仅含非Observation步骤_成功率为0_判定无效() {
            // given — 只有 Thought 和 Answer，无 Observation
            var state = buildState(null, List.of(
                    new ReactStep.Thought("让我思考一下"),
                    new ReactStep.Answer("这是最终答案")
            ));
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-no-obs"));
            when(semanticMemory.findById("entity-no-obs"))
                    .thenReturn(Optional.of(buildEntity("entity-no-obs", 0.5f)));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 无 Observation，totalObs=0，成功率 0.0 < 0.5 阈值
            verify(semanticMemory).updateImportanceScore("entity-no-obs", 0.47f, WeightSource.EFFECTIVENESS);
        }

        @Test
        void 混合步骤类型_仅统计Observation() {
            // given — Thought + ToolCall + Observation(成功) + Observation(失败)，成功率 = 1/2 = 0.5
            var state = buildState(null, List.of(
                    new ReactStep.Thought("分析问题"),
                    new ReactStep.ToolCall("tool.a", "工具A", "{}", 10),
                    new ReactStep.Observation("tool.a", "工具A", true, "ok", 10),
                    new ReactStep.ToolCall("tool.b", "工具B", "{}", 5),
                    new ReactStep.Observation("tool.b", "工具B", false, "fail", 5),
                    new ReactStep.Answer("完成")
            ));
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-mix"));
            when(semanticMemory.findById("entity-mix"))
                    .thenReturn(Optional.of(buildEntity("entity-mix", 0.5f)));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 成功率 0.5 >= 0.5 阈值，判定有效，提升
            verify(semanticMemory).updateImportanceScore("entity-mix", 0.55f, WeightSource.EFFECTIVENESS);
        }

        @Test
        void 成功率恰好等于阈值_判定有效() {
            // given — 1 成功 1 失败，成功率 0.5 == 阈值 0.5
            var state = buildState(null, List.of(
                    new ReactStep.Observation("tool.a", "工具A", true, "ok", 10),
                    new ReactStep.Observation("tool.b", "工具B", false, "fail", 5)
            ));
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-exact"));
            when(semanticMemory.findById("entity-exact"))
                    .thenReturn(Optional.of(buildEntity("entity-exact", 0.5f)));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 成功率 0.5 >= 0.5，有效 → 提升
            verify(semanticMemory).updateImportanceScore("entity-exact", 0.55f, WeightSource.EFFECTIVENESS);
        }
    }

    // ==================== 自定义配置测试 ====================

    @Nested
    class 自定义配置 {

        @Test
        void 自定义提升步长和衰减步长应生效() {
            // given — 设置较大提升步长
            memoryProperties.getExperience().getEffectiveness().setPositiveBoost(0.2f);
            memoryProperties.getExperience().getEffectiveness().setNegativeDecay(0.1f);
            // 重新创建 tracker 使配置生效
            tracker = new EffectivenessTracker(semanticMemory, injectionRecordRepository, memoryProperties);

            var state = buildState(null, List.of(
                    new ReactStep.Observation("tool.a", "工具A", true, "ok", 10)
            ));
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-custom"));
            when(semanticMemory.findById("entity-custom"))
                    .thenReturn(Optional.of(buildEntity("entity-custom", 0.5f)));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 使用自定义步长 0.2 提升，0.5 + 0.2 = 0.7
            verify(semanticMemory).updateImportanceScore("entity-custom", 0.7f, WeightSource.EFFECTIVENESS);
        }

        @Test
        void 自定义淘汰阈值_较高阈值使更多经验被淘汰() {
            // given — 淘汰阈值设为 0.5
            memoryProperties.getExperience().getEffectiveness().setEvictionThreshold(0.5f);
            tracker = new EffectivenessTracker(semanticMemory, injectionRecordRepository, memoryProperties);

            var state = buildState(null, List.of(
                    new ReactStep.Observation("tool.a", "工具A", false, "fail", 5)
            ));
            var entity = buildEntity("entity-evict", 0.5f);
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-evict"));
            when(semanticMemory.findById("entity-evict"))
                    .thenReturn(Optional.of(entity));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 衰减后 0.5 - 0.03 = 0.47 < 0.5 淘汰阈值，应归档
            verify(semanticMemory).archive(entity);
        }

        @Test
        void 自定义成功率阈值_更高阈值使更多场景判定无效() {
            // given — 成功率阈值设为 0.9
            memoryProperties.getExperience().getEffectiveness().setSuccessRatioThreshold(0.9f);
            tracker = new EffectivenessTracker(semanticMemory, injectionRecordRepository, memoryProperties);

            // 8 成功 2 失败，成功率 0.8 < 0.9
            var observations = List.<ReactStep>of(
                    new ReactStep.Observation("t1", null, true, "ok", 0),
                    new ReactStep.Observation("t2", null, true, "ok", 0),
                    new ReactStep.Observation("t3", null, true, "ok", 0),
                    new ReactStep.Observation("t4", null, true, "ok", 0),
                    new ReactStep.Observation("t5", null, true, "ok", 0),
                    new ReactStep.Observation("t6", null, true, "ok", 0),
                    new ReactStep.Observation("t7", null, true, "ok", 0),
                    new ReactStep.Observation("t8", null, true, "ok", 0),
                    new ReactStep.Observation("t9", null, false, "fail", 0),
                    new ReactStep.Observation("t10", null, false, "fail", 0)
            );
            var state = buildState(null, observations);
            when(injectionRecordRepository.findEntityIdsBySourceTraceIdAndType("trace-001", "EXPERIENCE"))
                    .thenReturn(List.of("entity-strict"));
            when(semanticMemory.findById("entity-strict"))
                    .thenReturn(Optional.of(buildEntity("entity-strict", 0.6f)));

            // when
            tracker.evaluate(state, "trace-001");

            // then — 成功率 0.8 < 0.9 阈值，判定无效，衰减（float 精度误差 0.6-0.03 ≈ 0.57000005）
            verify(semanticMemory).updateImportanceScore(eq("entity-strict"),
                    floatThat(v -> Math.abs(v - 0.57f) < 0.001f), eq(WeightSource.EFFECTIVENESS));
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建测试用 ReactAgentState。
     *
     * @param terminationReason 终止原因（null 表示正常完成）
     * @param steps             步骤列表
     */
    private ReactAgentState buildState(String terminationReason, List<ReactStep> steps) {
        return ReactAgentState.builder()
                .traceId("trace-001")
                .sessionId("session-001")
                .goal("测试任务")
                .channel("web")
                .steps(steps)
                .stepCount(steps.size())
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(1000)
                        .tokensUsed(0)
                        .tokensReserved(0)
                        .maxSteps(10)
                        .stepsUsed(0)
                        .maxDuration(Duration.ofMinutes(1))
                        .elapsed(Duration.ZERO)
                        .build())
                .depth(0)
                .done(false)
                .terminationReason(terminationReason)
                .completionMode(CompletionMode.NORMAL)
                .earlyStopRejectCount(0)
                .suspended(false)
                .build();
    }

    /**
     * 构建测试用 TemporalEntity。
     *
     * @param id              实体 ID
     * @param importanceScore 当前 importanceScore
     */
    private TemporalEntity buildEntity(String id, float importanceScore) {
        return new TemporalEntity(
                id,
                EntityType.EXPERIENCE,
                "测试经验-" + id,
                "测试描述",
                Map.of(),
                1,
                true,
                Instant.now(),
                null,
                "conv-001",
                0.8f,
                importanceScore,
                0,
                null,
                Instant.now(),
                Instant.now()
        );
    }
}
