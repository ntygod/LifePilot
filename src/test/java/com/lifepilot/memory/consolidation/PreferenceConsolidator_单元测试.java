package com.lifepilot.memory.consolidation;

import com.lifepilot.memory.procedural.PreferenceRule;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.quality.MemoryEvidenceKind;
import com.lifepilot.memory.quality.MemoryTrustLevel;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PreferenceConsolidator 单元测试 — 覆盖 L3→L4 偏好同步的所有路径。
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
class PreferenceConsolidator_单元测试 {

    @Mock
    private SemanticMemory semanticMemory;

    @Mock
    private ProceduralMemory proceduralMemory;

    private PreferenceConsolidator consolidator;

    @BeforeEach
    void 初始化() {
        consolidator = new PreferenceConsolidator(semanticMemory, proceduralMemory);
    }

    // ==================== 辅助方法 ====================

    /** 构建最小有效的 PREFERENCE 类型 TemporalEntity。 */
    private TemporalEntity 偏好实体(String name, String description) {
        var now = Instant.now();
        return new TemporalEntity(
                "entity-" + name, EntityType.PREFERENCE, name, description,
                Map.of(), 1, true, now, null, null,
                0.9f, 0.5f, 0, null, now, now)
                .withQuality(MemoryEvidenceKind.USER_EXPLICIT, MemoryTrustLevel.EXPLICIT,
                        0.9f, 1, now);
    }

    /** 构建最小有效的 PREFERENCE 类型 TemporalEntity（无 description）。 */
    private TemporalEntity 偏好实体无描述(String name) {
        var now = Instant.now();
        return new TemporalEntity(
                "entity-" + name, EntityType.PREFERENCE, name, null,
                Map.of(), 1, true, now, null, null,
                0.9f, 0.5f, 0, null, now, now)
                .withQuality(MemoryEvidenceKind.USER_EXPLICIT, MemoryTrustLevel.EXPLICIT,
                        0.9f, 1, now);
    }

    /** 构建对应的 L4 PreferenceRule。 */
    private PreferenceRule 已有规则(String key) {
        var now = Instant.now();
        return new PreferenceRule(
                "rule-" + key, "user-preference", key, "已有描述",
                0.6f, "consolidation", 3, now, now, null, null);
    }

    // ==================== 新建偏好 ====================

    @Nested
    class 新建偏好 {

        @Test
        void L3有L4无时创建新偏好规则() {
            // given
            var entity = 偏好实体("喜欢深色主题", "用户偏好使用深色模式");
            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of(entity));
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of());

            // when
            var stats = consolidator.consolidate();

            // then
            assertThat(stats.created()).isEqualTo(1);
            assertThat(stats.reinforced()).isZero();
            assertThat(stats.deleted()).isZero();

            var captor = ArgumentCaptor.forClass(PreferenceRule.class);
            verify(proceduralMemory).savePreference(captor.capture());

            var rule = captor.getValue();
            assertThat(rule.category()).isEqualTo("user-preference");
            assertThat(rule.key()).isEqualTo("喜欢深色主题");
            assertThat(rule.value()).isEqualTo("用户偏好使用深色模式");
            assertThat(rule.confidence()).isEqualTo(0.5f);
            assertThat(rule.learnedFrom()).isEqualTo("consolidation");
            assertThat(rule.observationCount()).isEqualTo(1);
            assertThat(rule.ruleId()).isNotBlank();
            assertThat(rule.createdAt()).isNotNull();
            assertThat(rule.updatedAt()).isNotNull();
        }

        @Test
        void 实体描述为null时规则value设为空字符串() {
            // given
            var entity = 偏好实体无描述("偏好简洁回复");
            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of(entity));
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of());

            // when
            consolidator.consolidate();

            // then
            var captor = ArgumentCaptor.forClass(PreferenceRule.class);
            verify(proceduralMemory).savePreference(captor.capture());
            assertThat(captor.getValue().value()).isEmpty();
        }

        @Test
        void 多个新偏好实体逐一创建规则() {
            // given
            var entity1 = 偏好实体("偏好A", "描述A");
            var entity2 = 偏好实体("偏好B", "描述B");
            var entity3 = 偏好实体("偏好C", "描述C");
            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of(entity1, entity2, entity3));
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of());

            // when
            var stats = consolidator.consolidate();

            // then
            assertThat(stats.created()).isEqualTo(3);
            verify(proceduralMemory, times(3)).savePreference(any(PreferenceRule.class));
        }
    }

    // ==================== 强化已有偏好 ====================

    @Nested
    class 强化已有偏好 {

        @Test
        void L3有L4有时强化已有偏好规则() {
            // given
            var entity = 偏好实体("喜欢深色主题", "用户偏好使用深色模式");
            var existingRule = 已有规则("喜欢深色主题");
            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of(entity));
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of(existingRule));

            // when
            var stats = consolidator.consolidate();

            // then
            assertThat(stats.reinforced()).isEqualTo(1);
            assertThat(stats.created()).isZero();
            assertThat(stats.deleted()).isZero();
            verify(proceduralMemory).reinforcePreference(existingRule.ruleId());
            verify(proceduralMemory, never()).savePreference(any());
        }

        @Test
        void 多个已有偏好实体逐一强化() {
            // given
            var entity1 = 偏好实体("偏好X", "描述X");
            var entity2 = 偏好实体("偏好Y", "描述Y");
            var rule1 = 已有规则("偏好X");
            var rule2 = 已有规则("偏好Y");
            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of(entity1, entity2));
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of(rule1, rule2));

            // when
            var stats = consolidator.consolidate();

            // then
            assertThat(stats.reinforced()).isEqualTo(2);
            verify(proceduralMemory).reinforcePreference(rule1.ruleId());
            verify(proceduralMemory).reinforcePreference(rule2.ruleId());
        }
    }

    // ==================== 删除过期偏好 ====================

    @Nested
    class 删除过期偏好 {

        @Test
        void L3归档L4有时删除偏好规则() {
            // given — L3 没有此实体了，但 L4 仍有规则
            var orphanRule = 已有规则("已过期的偏好");
            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of());
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of(orphanRule));

            // when
            var stats = consolidator.consolidate();

            // then
            assertThat(stats.deleted()).isEqualTo(1);
            assertThat(stats.created()).isZero();
            assertThat(stats.reinforced()).isZero();
            verify(proceduralMemory).deletePreference(orphanRule.ruleId());
        }

        @Test
        void 多个过期规则全部删除() {
            // given
            var orphan1 = 已有规则("过期偏好A");
            var orphan2 = 已有规则("过期偏好B");
            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of());
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of(orphan1, orphan2));

            // when
            var stats = consolidator.consolidate();

            // then
            assertThat(stats.deleted()).isEqualTo(2);
            verify(proceduralMemory).deletePreference(orphan1.ruleId());
            verify(proceduralMemory).deletePreference(orphan2.ruleId());
        }

        @Test
        void L3仍有的实体对应规则不被删除() {
            // given — L3 有 A 实体，L4 有 A 和 B 两条规则，B 应被删除
            var entityA = 偏好实体("偏好A", "描述A");
            var ruleA = 已有规则("偏好A");
            var ruleB = 已有规则("偏好B");
            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of(entityA));
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of(ruleA, ruleB));

            // when
            var stats = consolidator.consolidate();

            // then
            assertThat(stats.reinforced()).isEqualTo(1);
            assertThat(stats.deleted()).isEqualTo(1);
            verify(proceduralMemory).reinforcePreference(ruleA.ruleId());
            verify(proceduralMemory).deletePreference(ruleB.ruleId());
        }
    }

    // ==================== 无偏好实体 ====================

    @Nested
    class 无偏好实体 {

        @Test
        void L3和L4均无数据时统计全零() {
            // given
            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of());
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of());

            // when
            var stats = consolidator.consolidate();

            // then
            assertThat(stats.created()).isZero();
            assertThat(stats.reinforced()).isZero();
            assertThat(stats.deleted()).isZero();
            verify(proceduralMemory, never()).savePreference(any());
            verify(proceduralMemory, never()).reinforcePreference(any());
            verify(proceduralMemory, never()).deletePreference(any());
        }
    }

    // ==================== 混合场景 ====================

    @Nested
    class 混合场景 {

        @Test
        void 同时包含新建强化删除的综合场景() {
            // given
            // L3 有: 新偏好、已有偏好A
            // L4 有: 已有偏好A、过期偏好B
            var newEntity = 偏好实体("全新偏好", "新描述");
            var existingEntity = 偏好实体("已有偏好A", "已有描述A");
            var ruleA = 已有规则("已有偏好A");
            var ruleB = 已有规则("过期偏好B");

            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of(newEntity, existingEntity));
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of(ruleA, ruleB));

            // when
            var stats = consolidator.consolidate();

            // then
            assertThat(stats.created()).isEqualTo(1);
            assertThat(stats.reinforced()).isEqualTo(1);
            assertThat(stats.deleted()).isEqualTo(1);

            verify(proceduralMemory).savePreference(any(PreferenceRule.class));
            verify(proceduralMemory).reinforcePreference(ruleA.ruleId());
            verify(proceduralMemory).deletePreference(ruleB.ruleId());
        }
    }

    // ==================== 异常容错 ====================

    @Nested
    class 异常容错 {

        @Test
        void 单条新建失败不影响后续处理() {
            // given
            var entity1 = 偏好实体("失败偏好", "会抛异常");
            var entity2 = 偏好实体("成功偏好", "正常处理");

            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of(entity1, entity2));
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of());

            // 第一次调用 savePreference 抛异常，第二次正常
            doThrow(new RuntimeException("数据库写入失败"))
                    .doNothing()
                    .when(proceduralMemory).savePreference(any());

            // when
            var stats = consolidator.consolidate();

            // then — 第一条因异常未计入 created，第二条成功
            assertThat(stats.created()).isEqualTo(1);
            verify(proceduralMemory, times(2)).savePreference(any());
        }

        @Test
        void 单条强化失败不影响后续处理() {
            // given
            var entity1 = 偏好实体("强化失败", "描述");
            var entity2 = 偏好实体("强化成功", "描述");
            var rule1 = 已有规则("强化失败");
            var rule2 = 已有规则("强化成功");

            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of(entity1, entity2));
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of(rule1, rule2));

            doThrow(new RuntimeException("强化操作失败"))
                    .when(proceduralMemory).reinforcePreference(eq(rule1.ruleId()));

            // when
            var stats = consolidator.consolidate();

            // then — 第一条因异常未计入 reinforced
            assertThat(stats.reinforced()).isEqualTo(1);
            verify(proceduralMemory).reinforcePreference(rule1.ruleId());
            verify(proceduralMemory).reinforcePreference(rule2.ruleId());
        }

        @Test
        void 单条删除失败不影响后续删除() {
            // given
            var orphan1 = 已有规则("删除失败规则");
            var orphan2 = 已有规则("删除成功规则");

            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of());
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of(orphan1, orphan2));

            doThrow(new RuntimeException("删除失败"))
                    .when(proceduralMemory).deletePreference(eq(orphan1.ruleId()));

            // when
            var stats = consolidator.consolidate();

            // then — 第一条因异常未计入 deleted
            assertThat(stats.deleted()).isEqualTo(1);
            verify(proceduralMemory).deletePreference(orphan1.ruleId());
            verify(proceduralMemory).deletePreference(orphan2.ruleId());
        }
    }

    // ==================== 同步统计准确性 ====================

    @Nested
    class 同步统计准确性 {

        @Test
        void 统计结果与实际操作严格一致() {
            // given — 2 新建 + 1 强化 + 1 删除
            var newA = 偏好实体("新A", "描述");
            var newB = 偏好实体("新B", "描述");
            var existEntity = 偏好实体("已有", "描述");
            var existRule = 已有规则("已有");
            var orphanRule = 已有规则("过期");

            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of(newA, newB, existEntity));
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of(existRule, orphanRule));

            // when
            var stats = consolidator.consolidate();

            // then
            assertThat(stats.created()).isEqualTo(2);
            assertThat(stats.reinforced()).isEqualTo(1);
            assertThat(stats.deleted()).isEqualTo(1);
        }

        @Test
        void PreferenceSyncStats为record类型可正确比较() {
            // given / when
            var stats1 = new PreferenceSyncStats(1, 2, 3);
            var stats2 = new PreferenceSyncStats(1, 2, 3);

            // then
            assertThat(stats1).isEqualTo(stats2);
            assertThat(stats1.created()).isEqualTo(1);
            assertThat(stats1.reinforced()).isEqualTo(2);
            assertThat(stats1.deleted()).isEqualTo(3);
        }
    }

    // ==================== L4 重复 key 去重 ====================

    @Nested
    class L4重复key去重 {

        @Test
        void L4存在重复key规则时仅保留第一条用于匹配() {
            // given — L4 有两条同 key 的规则，Collectors.toMap 的 merge function 保留第一条
            var now = Instant.now();
            var rule1 = new PreferenceRule(
                    "rule-dup-1", "user-preference", "重复key", "值1",
                    0.6f, "consolidation", 3, now, now, null, null);
            var rule2 = new PreferenceRule(
                    "rule-dup-2", "user-preference", "重复key", "值2",
                    0.7f, "consolidation", 5, now, now, null, null);
            var entity = 偏好实体("重复key", "描述");

            when(semanticMemory.findCurrentByType(EntityType.PREFERENCE))
                    .thenReturn(List.of(entity));
            when(proceduralMemory.getPreferences("user-preference"))
                    .thenReturn(List.of(rule1, rule2));

            // when
            var stats = consolidator.consolidate();

            // then — 匹配到第一条规则进行强化，第二条不在 currentEntityNames 中但 key 相同不触发删除
            assertThat(stats.reinforced()).isEqualTo(1);
            verify(proceduralMemory).reinforcePreference("rule-dup-1");
            // rule2 的 key 等于 "重复key"，也在 currentEntityNames 中，所以不会被删除
            verify(proceduralMemory, never()).deletePreference(any());
        }
    }
}
