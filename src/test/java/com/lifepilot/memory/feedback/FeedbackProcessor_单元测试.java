package com.lifepilot.memory.feedback;

import com.lifepilot.agent.learning.feedback.FeedbackProcessor;
import com.lifepilot.interaction.web.repository.MessageFeedbackRepository;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.lifecycle.WeightSource;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.FloatRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FeedbackProcessor 反馈处理器单元测试 — 覆盖正面/负面反馈对 importanceScore 的调整、
 * delta 计算逻辑、无关联实体跳过、多实体批量调整、score 边界裁剪、重复反馈跳过、
 * 反馈类型翻转时的回滚+重新应用等关键行为。
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class FeedbackProcessor_单元测试 {

    @Mock
    private InjectionRecordRepository injectionRecordRepository;
    @Mock
    private SemanticMemory semanticMemory;
    @Mock
    private MessageFeedbackRepository feedbackRepository;

    private AgentLearningProperties properties;
    private AgentLearningProperties.Feedback feedbackConfig;
    private FeedbackProcessor processor;

    /** 默认 likeBoost=0.1, dislikePenalty=0.05 */
    @BeforeEach
    void 初始化() {
        properties = new AgentLearningProperties();
        feedbackConfig = new AgentLearningProperties.Feedback();
        feedbackConfig.setLikeBoost(0.1f);
        feedbackConfig.setDislikePenalty(0.05f);
        properties.setFeedback(feedbackConfig);

        processor = new FeedbackProcessor(
                injectionRecordRepository, semanticMemory, feedbackRepository, properties);
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造指定 importanceScore 的 TemporalEntity。
     */
    private TemporalEntity 构造实体(String id, float importanceScore) {
        var now = Instant.now();
        return new TemporalEntity(
                id, EntityType.TOPIC, "测试实体-" + id, "描述",
                Map.of(), 1, true,
                now, null, null,
                0.9f, importanceScore,
                0, null, now, now);
    }

    /**
     * 模拟首次反馈场景（无历史反馈记录）。
     */
    private void 模拟首次反馈(String entryId, List<String> entityIds) {
        when(injectionRecordRepository.findEntityIdsBySourceEntryId(entryId))
                .thenReturn(entityIds);
        when(feedbackRepository.findByEntryId(entryId))
                .thenReturn(List.of());
    }

    // ==================== 无关联实体跳过 ====================

    @Nested
    class 无关联实体时跳过 {

        @Test
        void 无注入记录时直接返回_不查询反馈也不更新分数() {
            // given
            String entryId = "entry-no-injection";
            when(injectionRecordRepository.findEntityIdsBySourceEntryId(entryId))
                    .thenReturn(List.of());

            // when
            processor.processFeedbackForEntry(entryId, "like");

            // then
            verify(injectionRecordRepository).findEntityIdsBySourceEntryId(entryId);
            verifyNoInteractions(feedbackRepository);
            verifyNoInteractions(semanticMemory);
        }
    }

    // ==================== 正面反馈（like）增加 importanceScore ====================

    @Nested
    class 正面反馈增加分数 {

        @Test
        void like反馈_单个实体_分数增加likeBoost() {
            // given
            String entryId = "entry-like-1";
            String entityId = "entity-1";
            模拟首次反馈(entryId, List.of(entityId));

            var entity = 构造实体(entityId, 0.5f);
            when(semanticMemory.findByIds(List.of(entityId)))
                    .thenReturn(Map.of(entityId, entity));

            // when
            processor.processFeedbackForEntry(entryId, "like");

            // then — 0.5 + 0.1 = 0.6
            verify(semanticMemory).updateImportanceScore(entityId, 0.6f, WeightSource.USER_FEEDBACK);
        }

        @Test
        void like反馈_多个关联实体_全部增加相同delta() {
            // given
            String entryId = "entry-like-multi";
            List<String> entityIds = List.of("e-1", "e-2", "e-3");
            模拟首次反馈(entryId, entityIds);

            var e1 = 构造实体("e-1", 0.3f);
            var e2 = 构造实体("e-2", 0.5f);
            var e3 = 构造实体("e-3", 0.7f);
            when(semanticMemory.findByIds(entityIds))
                    .thenReturn(Map.of("e-1", e1, "e-2", e2, "e-3", e3));

            // when
            processor.processFeedbackForEntry(entryId, "like");

            // then
            verify(semanticMemory).updateImportanceScore("e-1", 0.4f, WeightSource.USER_FEEDBACK);
            verify(semanticMemory).updateImportanceScore("e-2", 0.6f, WeightSource.USER_FEEDBACK);
            verify(semanticMemory).updateImportanceScore("e-3", 0.8f, WeightSource.USER_FEEDBACK);
        }
    }

    // ==================== 负面反馈（dislike）降低 importanceScore ====================

    @Nested
    class 负面反馈降低分数 {

        @Test
        void dislike反馈_单个实体_分数降低dislikePenalty() {
            // given
            String entryId = "entry-dislike-1";
            String entityId = "entity-d1";
            模拟首次反馈(entryId, List.of(entityId));

            var entity = 构造实体(entityId, 0.5f);
            when(semanticMemory.findByIds(List.of(entityId)))
                    .thenReturn(Map.of(entityId, entity));

            // when
            processor.processFeedbackForEntry(entryId, "dislike");

            // then — 0.5 - 0.05 = 0.45
            verify(semanticMemory).updateImportanceScore(entityId, 0.45f, WeightSource.USER_FEEDBACK);
        }

        @Test
        void dislike反馈_多个实体_全部降低() {
            // given
            String entryId = "entry-dislike-multi";
            List<String> entityIds = List.of("d-1", "d-2");
            模拟首次反馈(entryId, entityIds);

            var d1 = 构造实体("d-1", 0.8f);
            var d2 = 构造实体("d-2", 0.2f);
            when(semanticMemory.findByIds(entityIds))
                    .thenReturn(Map.of("d-1", d1, "d-2", d2));

            // when
            processor.processFeedbackForEntry(entryId, "dislike");

            // then
            verify(semanticMemory).updateImportanceScore("d-1", 0.75f, WeightSource.USER_FEEDBACK);
            verify(semanticMemory).updateImportanceScore("d-2", 0.15f, WeightSource.USER_FEEDBACK);
        }
    }

    // ==================== score 边界裁剪 ====================

    @Nested
    class 分数边界裁剪 {

        @Test
        void like反馈_分数接近上限时裁剪为1点0() {
            // given
            String entryId = "entry-cap-high";
            String entityId = "cap-high";
            模拟首次反馈(entryId, List.of(entityId));

            var entity = 构造实体(entityId, 0.95f);
            when(semanticMemory.findByIds(List.of(entityId)))
                    .thenReturn(Map.of(entityId, entity));

            // when
            processor.processFeedbackForEntry(entryId, "like");

            // then — 0.95 + 0.1 = 1.05 → 裁剪为 1.0
            verify(semanticMemory).updateImportanceScore(entityId, 1.0f, WeightSource.USER_FEEDBACK);
        }

        @Test
        void like反馈_分数恰好为1点0时保持不变() {
            // given
            String entryId = "entry-cap-exact";
            String entityId = "cap-exact";
            模拟首次反馈(entryId, List.of(entityId));

            var entity = 构造实体(entityId, 1.0f);
            when(semanticMemory.findByIds(List.of(entityId)))
                    .thenReturn(Map.of(entityId, entity));

            // when
            processor.processFeedbackForEntry(entryId, "like");

            // then — min(1.0, 1.0+0.1) = 1.0
            verify(semanticMemory).updateImportanceScore(entityId, 1.0f, WeightSource.USER_FEEDBACK);
        }

        @Test
        void dislike反馈_分数接近下限时裁剪为0() {
            // given
            String entryId = "entry-cap-low";
            String entityId = "cap-low";
            模拟首次反馈(entryId, List.of(entityId));

            var entity = 构造实体(entityId, 0.02f);
            when(semanticMemory.findByIds(List.of(entityId)))
                    .thenReturn(Map.of(entityId, entity));

            // when
            processor.processFeedbackForEntry(entryId, "dislike");

            // then — 0.02 - 0.05 = -0.03 → 裁剪为 0.0
            verify(semanticMemory).updateImportanceScore(entityId, 0.0f, WeightSource.USER_FEEDBACK);
        }

        @Test
        void dislike反馈_分数恰好为0时保持不变() {
            // given
            String entryId = "entry-cap-zero";
            String entityId = "cap-zero";
            模拟首次反馈(entryId, List.of(entityId));

            var entity = 构造实体(entityId, 0.0f);
            when(semanticMemory.findByIds(List.of(entityId)))
                    .thenReturn(Map.of(entityId, entity));

            // when
            processor.processFeedbackForEntry(entryId, "dislike");

            // then — max(0.0, 0.0-0.05) = 0.0
            verify(semanticMemory).updateImportanceScore(entityId, 0.0f, WeightSource.USER_FEEDBACK);
        }

        @Test
        void 多实体混合_部分触发上限裁剪_部分正常增加() {
            // given
            String entryId = "entry-mix-cap";
            List<String> entityIds = List.of("normal", "overflow");
            模拟首次反馈(entryId, entityIds);

            var normal = 构造实体("normal", 0.5f);
            var overflow = 构造实体("overflow", 0.98f);
            when(semanticMemory.findByIds(entityIds))
                    .thenReturn(Map.of("normal", normal, "overflow", overflow));

            // when
            processor.processFeedbackForEntry(entryId, "like");

            // then
            verify(semanticMemory).updateImportanceScore("normal", 0.6f, WeightSource.USER_FEEDBACK);
            verify(semanticMemory).updateImportanceScore("overflow", 1.0f, WeightSource.USER_FEEDBACK);
        }
    }

    // ==================== 实体不存在时跳过 ====================

    @Nested
    class 实体不存在时跳过 {

        @Test
        void findByIds返回不包含某实体_该实体被跳过_其余正常处理() {
            // given
            String entryId = "entry-partial";
            List<String> entityIds = List.of("exists", "missing");
            模拟首次反馈(entryId, entityIds);

            var exists = 构造实体("exists", 0.5f);
            // "missing" 不在返回的 map 中
            when(semanticMemory.findByIds(entityIds))
                    .thenReturn(Map.of("exists", exists));

            // when
            processor.processFeedbackForEntry(entryId, "like");

            // then — 仅更新存在的实体
            verify(semanticMemory).updateImportanceScore("exists", 0.6f, WeightSource.USER_FEEDBACK);
            verify(semanticMemory, never()).updateImportanceScore(eq("missing"), anyFloat(), any(WeightSource.class));
        }

        @Test
        void findByIds返回空map_所有实体都被跳过() {
            // given
            String entryId = "entry-all-missing";
            List<String> entityIds = List.of("ghost-1", "ghost-2");
            模拟首次反馈(entryId, entityIds);

            when(semanticMemory.findByIds(entityIds))
                    .thenReturn(Map.of());

            // when
            processor.processFeedbackForEntry(entryId, "like");

            // then
            verify(semanticMemory, never()).updateImportanceScore(anyString(), anyFloat(), any(WeightSource.class));
        }
    }

    // ==================== 重复反馈（同类型） ====================

    @Nested
    class 同类型重复反馈跳过 {

        @Test
        void 连续两次like_第二次被跳过_不更新分数() {
            // given
            String entryId = "entry-dup-like";
            List<String> entityIds = List.of("e-dup");
            when(injectionRecordRepository.findEntityIdsBySourceEntryId(entryId))
                    .thenReturn(entityIds);
            // 已有两条反馈，最后两条都是 like
            when(feedbackRepository.findByEntryId(entryId))
                    .thenReturn(List.of(
                            Map.of("type", "like", "created_at", "2026-04-01T00:00:00Z"),
                            Map.of("type", "like", "created_at", "2026-04-02T00:00:00Z")
                    ));

            // when
            processor.processFeedbackForEntry(entryId, "like");

            // then — 同类型重复，直接跳过
            verifyNoInteractions(semanticMemory);
        }

        @Test
        void 连续两次dislike_第二次被跳过() {
            // given
            String entryId = "entry-dup-dislike";
            List<String> entityIds = List.of("e-dup2");
            when(injectionRecordRepository.findEntityIdsBySourceEntryId(entryId))
                    .thenReturn(entityIds);
            when(feedbackRepository.findByEntryId(entryId))
                    .thenReturn(List.of(
                            Map.of("type", "dislike", "created_at", "2026-04-01T00:00:00Z"),
                            Map.of("type", "dislike", "created_at", "2026-04-02T00:00:00Z")
                    ));

            // when
            processor.processFeedbackForEntry(entryId, "dislike");

            // then
            verifyNoInteractions(semanticMemory);
        }
    }

    // ==================== 反馈翻转（like → dislike 或 dislike → like） ====================

    @Nested
    class 反馈翻转时回滚再重新应用 {

        @Test
        void like翻转为dislike_先回滚like的正向delta_再应用dislike的负向delta() {
            // given
            String entryId = "entry-flip-l2d";
            List<String> entityIds = List.of("e-flip");
            when(injectionRecordRepository.findEntityIdsBySourceEntryId(entryId))
                    .thenReturn(entityIds);
            // 两条反馈：第一条 like，第二条 dislike（当前提交的）
            when(feedbackRepository.findByEntryId(entryId))
                    .thenReturn(List.of(
                            Map.of("type", "like", "created_at", "2026-04-01T00:00:00Z"),
                            Map.of("type", "dislike", "created_at", "2026-04-02T00:00:00Z")
                    ));

            var entity = 构造实体("e-flip", 0.6f);
            // findByIds 被调用两次：一次用于回滚，一次用于应用新 delta
            when(semanticMemory.findByIds(entityIds))
                    .thenReturn(Map.of("e-flip", entity));

            // when
            processor.processFeedbackForEntry(entryId, "dislike");

            // then — 回滚 like: 0.6 + (-0.1) = 0.5, 应用 dislike: 0.6 + (-0.05) = 0.55
            // 注意：两次 applyDelta 各自独立从 findByIds 获取实体，
            // 因为 mock 返回的是同一个实体（0.6），所以：
            // 第一次（回滚）: 0.6 - 0.1 = 0.5
            // 第二次（应用）: 0.6 - 0.05 = 0.55
            ArgumentCaptor<Float> scoreCaptor = ArgumentCaptor.forClass(Float.class);
            verify(semanticMemory, times(2)).updateImportanceScore(eq("e-flip"), scoreCaptor.capture(), any(WeightSource.class));
            List<Float> scores = scoreCaptor.getAllValues();
            assertEquals(0.5f, scores.get(0), 1e-6f, "回滚 like delta 后分数");
            assertEquals(0.55f, scores.get(1), 1e-6f, "应用 dislike delta 后分数");
        }

        @Test
        void dislike翻转为like_先回滚dislike的负向delta_再应用like的正向delta() {
            // given
            String entryId = "entry-flip-d2l";
            List<String> entityIds = List.of("e-flip2");
            when(injectionRecordRepository.findEntityIdsBySourceEntryId(entryId))
                    .thenReturn(entityIds);
            when(feedbackRepository.findByEntryId(entryId))
                    .thenReturn(List.of(
                            Map.of("type", "dislike", "created_at", "2026-04-01T00:00:00Z"),
                            Map.of("type", "like", "created_at", "2026-04-02T00:00:00Z")
                    ));

            var entity = 构造实体("e-flip2", 0.4f);
            when(semanticMemory.findByIds(entityIds))
                    .thenReturn(Map.of("e-flip2", entity));

            // when
            processor.processFeedbackForEntry(entryId, "like");

            // then
            // 回滚 dislike: computeDelta("dislike")=-0.05, rollbackDelta=0.05 → 0.4+0.05=0.45
            // 应用 like: computeDelta("like")=0.1 → 0.4+0.1=0.5
            ArgumentCaptor<Float> scoreCaptor = ArgumentCaptor.forClass(Float.class);
            verify(semanticMemory, times(2)).updateImportanceScore(eq("e-flip2"), scoreCaptor.capture(), any(WeightSource.class));
            List<Float> scores = scoreCaptor.getAllValues();
            assertEquals(0.45f, scores.get(0), 1e-6f, "回滚 dislike delta 后分数");
            assertEquals(0.5f, scores.get(1), 1e-6f, "应用 like delta 后分数");
        }

        @Test
        void 翻转时多个实体_回滚和应用均作用于所有实体() {
            // given
            String entryId = "entry-flip-multi";
            List<String> entityIds = List.of("fm-1", "fm-2");
            when(injectionRecordRepository.findEntityIdsBySourceEntryId(entryId))
                    .thenReturn(entityIds);
            when(feedbackRepository.findByEntryId(entryId))
                    .thenReturn(List.of(
                            Map.of("type", "like", "created_at", "2026-04-01T00:00:00Z"),
                            Map.of("type", "dislike", "created_at", "2026-04-02T00:00:00Z")
                    ));

            var fm1 = 构造实体("fm-1", 0.5f);
            var fm2 = 构造实体("fm-2", 0.8f);
            when(semanticMemory.findByIds(entityIds))
                    .thenReturn(Map.of("fm-1", fm1, "fm-2", fm2));

            // when
            processor.processFeedbackForEntry(entryId, "dislike");

            // then — 每个实体被更新两次（回滚 + 应用）
            verify(semanticMemory, times(4)).updateImportanceScore(anyString(), anyFloat(), any(WeightSource.class));

            // fm-1: 回滚 like → 0.5-0.1=0.4, 应用 dislike → 0.5-0.05=0.45
            ArgumentCaptor<Float> fm1Captor = ArgumentCaptor.forClass(Float.class);
            verify(semanticMemory, times(2)).updateImportanceScore(eq("fm-1"), fm1Captor.capture(), any(WeightSource.class));
            assertEquals(0.4f, fm1Captor.getAllValues().get(0), 1e-6f);
            assertEquals(0.45f, fm1Captor.getAllValues().get(1), 1e-6f);

            // fm-2: 回滚 like → 0.8-0.1=0.7, 应用 dislike → 0.8-0.05=0.75
            ArgumentCaptor<Float> fm2Captor = ArgumentCaptor.forClass(Float.class);
            verify(semanticMemory, times(2)).updateImportanceScore(eq("fm-2"), fm2Captor.capture(), any(WeightSource.class));
            assertEquals(0.7f, fm2Captor.getAllValues().get(0), 1e-6f);
            assertEquals(0.75f, fm2Captor.getAllValues().get(1), 1e-6f);
        }
    }

    // ==================== 首次反馈（只有一条或无历史记录） ====================

    @Nested
    class 首次反馈无历史记录 {

        @Test
        void 仅一条历史反馈记录_不触发回滚_直接应用delta() {
            // given — feedbackRepository 返回仅 1 条记录（size <= 1），不进入回滚分支
            String entryId = "entry-single-history";
            List<String> entityIds = List.of("e-single");
            when(injectionRecordRepository.findEntityIdsBySourceEntryId(entryId))
                    .thenReturn(entityIds);
            when(feedbackRepository.findByEntryId(entryId))
                    .thenReturn(List.of(
                            Map.of("type", "like", "created_at", "2026-04-01T00:00:00Z")
                    ));

            var entity = 构造实体("e-single", 0.5f);
            when(semanticMemory.findByIds(entityIds))
                    .thenReturn(Map.of("e-single", entity));

            // when
            processor.processFeedbackForEntry(entryId, "like");

            // then — 仅一次更新，无回滚
            verify(semanticMemory, times(1)).updateImportanceScore("e-single", 0.6f, WeightSource.USER_FEEDBACK);
        }
    }

    // ==================== 自定义配置值 ====================

    @Nested
    class 自定义配置值 {

        @Test
        void 自定义较大的likeBoost值_delta相应增大() {
            // given
            feedbackConfig.setLikeBoost(0.3f);
            String entryId = "entry-custom-like";
            String entityId = "e-custom";
            模拟首次反馈(entryId, List.of(entityId));

            var entity = 构造实体(entityId, 0.5f);
            when(semanticMemory.findByIds(List.of(entityId)))
                    .thenReturn(Map.of(entityId, entity));

            // when
            processor.processFeedbackForEntry(entryId, "like");

            // then — 0.5 + 0.3 = 0.8
            verify(semanticMemory).updateImportanceScore(entityId, 0.8f, WeightSource.USER_FEEDBACK);
        }

        @Test
        void 自定义较大的dislikePenalty值_delta相应增大() {
            // given
            feedbackConfig.setDislikePenalty(0.2f);
            String entryId = "entry-custom-dislike";
            String entityId = "e-custom-d";
            模拟首次反馈(entryId, List.of(entityId));

            var entity = 构造实体(entityId, 0.5f);
            when(semanticMemory.findByIds(List.of(entityId)))
                    .thenReturn(Map.of(entityId, entity));

            // when
            processor.processFeedbackForEntry(entryId, "dislike");

            // then — 0.5 - 0.2 = 0.3
            verify(semanticMemory).updateImportanceScore(entityId, 0.3f, WeightSource.USER_FEEDBACK);
        }
    }

    // ==================== 翻转 + 边界裁剪联合场景 ====================

    @Nested
    class 翻转与边界裁剪联合 {

        @Test
        void 翻转回滚导致分数超过上限时裁剪为1() {
            // given — dislike→like 翻转，回滚 dislike 相当于 +0.05
            String entryId = "entry-flip-cap-high";
            List<String> entityIds = List.of("e-cap");
            when(injectionRecordRepository.findEntityIdsBySourceEntryId(entryId))
                    .thenReturn(entityIds);
            when(feedbackRepository.findByEntryId(entryId))
                    .thenReturn(List.of(
                            Map.of("type", "dislike", "created_at", "2026-04-01T00:00:00Z"),
                            Map.of("type", "like", "created_at", "2026-04-02T00:00:00Z")
                    ));

            var entity = 构造实体("e-cap", 0.98f);
            when(semanticMemory.findByIds(entityIds))
                    .thenReturn(Map.of("e-cap", entity));

            // when
            processor.processFeedbackForEntry(entryId, "like");

            // then
            // 回滚 dislike: 0.98 + 0.05 = 1.03 → 1.0
            // 应用 like: 0.98 + 0.1 = 1.08 → 1.0
            ArgumentCaptor<Float> captor = ArgumentCaptor.forClass(Float.class);
            verify(semanticMemory, times(2)).updateImportanceScore(eq("e-cap"), captor.capture(), any(WeightSource.class));
            assertEquals(1.0f, captor.getAllValues().get(0), 1e-6f, "回滚 dislike 后裁剪为 1.0");
            assertEquals(1.0f, captor.getAllValues().get(1), 1e-6f, "应用 like 后裁剪为 1.0");
        }

        @Test
        void 翻转回滚导致分数低于下限时裁剪为0() {
            // given — like→dislike 翻转，回滚 like 相当于 -0.1
            String entryId = "entry-flip-cap-low";
            List<String> entityIds = List.of("e-cap-low");
            when(injectionRecordRepository.findEntityIdsBySourceEntryId(entryId))
                    .thenReturn(entityIds);
            when(feedbackRepository.findByEntryId(entryId))
                    .thenReturn(List.of(
                            Map.of("type", "like", "created_at", "2026-04-01T00:00:00Z"),
                            Map.of("type", "dislike", "created_at", "2026-04-02T00:00:00Z")
                    ));

            var entity = 构造实体("e-cap-low", 0.03f);
            when(semanticMemory.findByIds(entityIds))
                    .thenReturn(Map.of("e-cap-low", entity));

            // when
            processor.processFeedbackForEntry(entryId, "dislike");

            // then
            // 回滚 like: 0.03 - 0.1 = -0.07 → 0.0
            // 应用 dislike: 0.03 - 0.05 = -0.02 → 0.0
            ArgumentCaptor<Float> captor = ArgumentCaptor.forClass(Float.class);
            verify(semanticMemory, times(2)).updateImportanceScore(eq("e-cap-low"), captor.capture(), any(WeightSource.class));
            assertEquals(0.0f, captor.getAllValues().get(0), 1e-6f, "回滚 like 后裁剪为 0.0");
            assertEquals(0.0f, captor.getAllValues().get(1), 1e-6f, "应用 dislike 后裁剪为 0.0");
        }
    }

    // ==================== jqwik 属性测试 ====================

    /**
     * 属性测试：对于任意 importanceScore 和 likeBoost，
     * like 反馈后的新分数必须在 [0.0, 1.0] 区间内。
     */
    @Property
    void like反馈后分数始终在零到一之间(
            @ForAll @FloatRange(min = 0.0f, max = 1.0f) float originalScore,
            @ForAll @FloatRange(min = 0.0f, max = 1.0f) float likeBoost) {
        float newScore = Math.max(0.0f, Math.min(1.0f, originalScore + likeBoost));
        assertTrue(newScore >= 0.0f && newScore <= 1.0f,
                "分数 " + newScore + " 超出 [0.0, 1.0] 范围 (原始=" + originalScore + ", boost=" + likeBoost + ")");
    }

    /**
     * 属性测试：对于任意 importanceScore 和 dislikePenalty，
     * dislike 反馈后的新分数必须在 [0.0, 1.0] 区间内。
     */
    @Property
    void dislike反馈后分数始终在零到一之间(
            @ForAll @FloatRange(min = 0.0f, max = 1.0f) float originalScore,
            @ForAll @FloatRange(min = 0.0f, max = 1.0f) float penalty) {
        float newScore = Math.max(0.0f, Math.min(1.0f, originalScore - penalty));
        assertTrue(newScore >= 0.0f && newScore <= 1.0f,
                "分数 " + newScore + " 超出 [0.0, 1.0] 范围 (原始=" + originalScore + ", penalty=" + penalty + ")");
    }

    /**
     * 属性测试：like 的 delta 始终为正值。
     */
    @Property
    void like的delta始终为正值(@ForAll @FloatRange(min = 0.01f, max = 1.0f) float likeBoost) {
        assertTrue(likeBoost > 0.0f, "likeBoost 应始终为正值");
    }

    /**
     * 属性测试：dislike 的 computeDelta 结果始终为负值（因为 -penalty）。
     */
    @Property
    void dislike的computeDelta结果始终为负值(@ForAll @FloatRange(min = 0.01f, max = 1.0f) float penalty) {
        float delta = -penalty;
        assertTrue(delta < 0.0f, "dislike delta 应始终为负值");
    }
}
