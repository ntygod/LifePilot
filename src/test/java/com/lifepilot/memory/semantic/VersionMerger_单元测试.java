package com.lifepilot.memory.semantic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VersionMerger 版本合并器单元测试。
 *
 * <p>覆盖属性合并（新增、冲突三种解决策略）、描述取长、importanceScore / extractionConfidence
 * 取大、无变化时复用已有版本等核心逻辑。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@DisplayName("VersionMerger 版本合并")
class VersionMerger_单元测试 {

    private VersionMerger merger;

    /** 基准时间 */
    private static final Instant BASE_TIME = Instant.parse("2026-03-01T00:00:00Z");

    @BeforeEach
    void 初始化() {
        merger = new VersionMerger();
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /** 构造 TemporalEntity，允许指定属性、confidence、importanceScore 和 description */
    private static TemporalEntity buildEntity(
            String id, String name, EntityType type, String description,
            Map<String, Object> properties, int version,
            float extractionConfidence, float importanceScore) {
        return new TemporalEntity(
                id, type, name, description,
                properties, version, true,
                BASE_TIME, null, null,
                extractionConfidence, importanceScore,
                5, BASE_TIME, BASE_TIME, BASE_TIME
        );
    }

    /** 快捷构造：默认 confidence=0.8, importance=0.5, version=1 */
    private static TemporalEntity buildEntity(String id, String name, String description,
                                              Map<String, Object> properties) {
        return buildEntity(id, name, EntityType.PERSON, description, properties, 1, 0.8f, 0.5f);
    }

    // ------------------------------------------------------------------
    // 无变化 → 复用已有版本
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("无变化时复用已有版本")
    class 无变化时复用 {

        @Test
        void 属性和描述完全相同时_isNewVersion为false() {
            // given
            var props = Map.<String, Object>of("role", "工程师", "team", "平台组");
            var existing = buildEntity("e1", "张三", "产品经理", props);
            var incoming = buildEntity("i1", "张三", "产品经理", props);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isFalse();
            assertThat(result.mergedEntity()).isSameAs(existing);
            assertThat(result.conflicts()).isEmpty();
        }

        @Test
        void incoming属性为空且描述较短时_isNewVersion为false() {
            // given
            var existing = buildEntity("e1", "张三", "资深产品经理", Map.of("role", "PM"));
            var incoming = buildEntity("i1", "张三", "PM", Map.of());

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isFalse();
            assertThat(result.mergedEntity()).isSameAs(existing);
        }

        @Test
        void incoming描述为null时_不触发变更() {
            // given
            var existing = buildEntity("e1", "张三", "产品经理", Map.of());
            var incoming = buildEntity(
                    "i1", "张三", EntityType.PERSON, null,
                    Map.of(), 1, 0.8f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // 新属性直接添加
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("新属性直接添加")
    class 新属性添加 {

        @Test
        void incoming带有existing不存在的属性时_直接添加并创建新版本() {
            // given
            var existing = buildEntity("e1", "张三", "产品经理", Map.of("role", "PM"));
            var incoming = buildEntity("i1", "张三", "产品经理",
                    Map.of("role", "PM", "email", "zhangsan@test.com"));

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isTrue();
            assertThat(result.mergedEntity().properties())
                    .containsEntry("role", "PM")
                    .containsEntry("email", "zhangsan@test.com");
            assertThat(result.conflicts()).isEmpty();
        }

        @Test
        void 多个新属性同时添加() {
            // given
            var existing = buildEntity("e1", "张三", "工程师", Map.of());
            var incoming = buildEntity("i1", "张三", "工程师",
                    Map.of("phone", "13800138000", "city", "北京"));

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isTrue();
            assertThat(result.mergedEntity().properties()).hasSize(2);
            assertThat(result.conflicts()).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 冲突属性解决
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("冲突属性按 extractionConfidence 解决")
    class 冲突属性解决 {

        @Test
        void incoming置信度高于existing时_KEEP_NEW_使用新值() {
            // given
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "工程师",
                    Map.of("role", "前端"), 1, 0.7f, 0.5f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON, "工程师",
                    Map.of("role", "全栈"), 1, 0.9f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isTrue();
            assertThat(result.mergedEntity().properties()).containsEntry("role", "全栈");
            assertThat(result.conflicts()).containsKey("role");

            var conflict = result.conflicts().get("role");
            assertThat(conflict.oldValue()).isEqualTo("前端");
            assertThat(conflict.newValue()).isEqualTo("全栈");
            assertThat(conflict.resolution()).isEqualTo(ConflictResolution.KEEP_NEW);
        }

        @Test
        void incoming置信度低于existing时_KEEP_OLD_保留旧值() {
            // given
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "工程师",
                    Map.of("role", "前端"), 1, 0.9f, 0.5f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON, "工程师",
                    Map.of("role", "后端"), 1, 0.6f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isTrue();
            assertThat(result.mergedEntity().properties()).containsEntry("role", "前端");

            var conflict = result.conflicts().get("role");
            assertThat(conflict.resolution()).isEqualTo(ConflictResolution.KEEP_OLD);
        }

        @Test
        void 置信度相同时_KEEP_BOTH_保留旧值() {
            // given
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "工程师",
                    Map.of("role", "前端"), 1, 0.8f, 0.5f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON, "工程师",
                    Map.of("role", "后端"), 1, 0.8f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isTrue();
            // KEEP_BOTH 策略下保留旧值
            assertThat(result.mergedEntity().properties()).containsEntry("role", "前端");

            var conflict = result.conflicts().get("role");
            assertThat(conflict.resolution()).isEqualTo(ConflictResolution.KEEP_BOTH);
        }

        @Test
        void 多个属性同时冲突_各自独立解决() {
            // given — incoming 置信度更高
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "工程师",
                    Map.of("role", "前端", "level", "P6"), 1, 0.7f, 0.5f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON, "工程师",
                    Map.of("role", "全栈", "level", "P7"), 1, 0.9f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.conflicts()).hasSize(2);
            assertThat(result.conflicts().get("role").resolution()).isEqualTo(ConflictResolution.KEEP_NEW);
            assertThat(result.conflicts().get("level").resolution()).isEqualTo(ConflictResolution.KEEP_NEW);
            assertThat(result.mergedEntity().properties())
                    .containsEntry("role", "全栈")
                    .containsEntry("level", "P7");
        }

        @Test
        void 相同值不视为冲突() {
            // given
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "工程师",
                    Map.of("role", "前端"), 1, 0.7f, 0.5f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON, "工程师",
                    Map.of("role", "前端"), 1, 0.9f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then — 值相同，无冲突，无变化
            assertThat(result.isNewVersion()).isFalse();
            assertThat(result.conflicts()).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 描述（description）合并
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("描述取更长者")
    class 描述合并 {

        @Test
        void incoming描述更长时_使用incoming描述() {
            // given
            var existing = buildEntity("e1", "张三", "产品经理", Map.of());
            var incoming = buildEntity("i1", "张三", "资深产品经理，负责AI方向产品线", Map.of());

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isTrue();
            assertThat(result.mergedEntity().description()).isEqualTo("资深产品经理，负责AI方向产品线");
        }

        @Test
        void existing描述更长时_保留existing描述() {
            // given
            var existing = buildEntity("e1", "张三", "资深产品经理，负责AI方向产品线", Map.of());
            var incoming = buildEntity("i1", "张三", "产品经理", Map.of());

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isFalse();
            assertThat(result.mergedEntity().description()).isEqualTo("资深产品经理，负责AI方向产品线");
        }

        @Test
        void existing描述为null_incoming非null时_使用incoming() {
            // given
            var existing = buildEntity(
                    "e1", "张三", EntityType.PERSON, null,
                    Map.of(), 1, 0.8f, 0.5f);
            var incoming = buildEntity("i1", "张三", "产品经理", Map.of());

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isTrue();
            assertThat(result.mergedEntity().description()).isEqualTo("产品经理");
        }

        @Test
        void 长度相同但内容不同时_不视为更长_保留existing() {
            // given
            var existing = buildEntity("e1", "张三", "产品经理A", Map.of());
            var incoming = buildEntity("i1", "张三", "产品经理B", Map.of());

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then — 长度相同，incoming 不 "更长"，保留 existing
            assertThat(result.isNewVersion()).isFalse();
            assertThat(result.mergedEntity().description()).isEqualTo("产品经理A");
        }

        @Test
        void incoming和existing描述都为null时_无变化() {
            // given
            var existing = buildEntity(
                    "e1", "张三", EntityType.PERSON, null,
                    Map.of(), 1, 0.8f, 0.5f);
            var incoming = buildEntity(
                    "i1", "张三", EntityType.PERSON, null,
                    Map.of(), 1, 0.8f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // importanceScore / extractionConfidence 合并策略
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("importanceScore 和 extractionConfidence 取较大值")
    class 分数合并 {

        @Test
        void extractionConfidence取两者最大值() {
            // given
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "PM",
                    Map.of(), 1, 0.7f, 0.5f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON,
                    "资深产品经理，负责AI方向", // 更长描述触发 hasChanges
                    Map.of(), 1, 0.9f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isTrue();
            assertThat(result.mergedEntity().extractionConfidence()).isEqualTo(0.9f);
        }

        @Test
        void importanceScore取两者最大值() {
            // given
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "PM",
                    Map.of(), 1, 0.8f, 0.3f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON,
                    "资深产品经理，负责AI方向产品线规划与落地",
                    Map.of(), 1, 0.8f, 0.9f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.isNewVersion()).isTrue();
            assertThat(result.mergedEntity().importanceScore()).isEqualTo(0.9f);
        }

        @Test
        void existing分数更高时_保留existing分数() {
            // given
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "PM",
                    Map.of(), 1, 0.95f, 0.8f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON,
                    "资深产品经理，带领团队交付多个核心项目",
                    Map.of(), 1, 0.6f, 0.4f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.mergedEntity().extractionConfidence()).isEqualTo(0.95f);
            assertThat(result.mergedEntity().importanceScore()).isEqualTo(0.8f);
        }
    }

    // ------------------------------------------------------------------
    // 新版本元数据验证
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("新版本元数据")
    class 新版本元数据 {

        @Test
        void 版本号递增() {
            // given
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "PM",
                    Map.of("role", "PM"), 3, 0.8f, 0.5f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON, "PM",
                    Map.of("role", "PM", "city", "北京"), 1, 0.8f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.mergedEntity().version()).isEqualTo(4);
        }

        @Test
        void 保留existing的id和基本信息() {
            // given
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "PM",
                    Map.of(), 1, 0.8f, 0.5f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON,
                    "资深产品经理，带领多个重要项目",
                    Map.of("新属性", "新值"), 1, 0.8f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-2");

            // then
            var merged = result.mergedEntity();
            assertThat(merged.id()).isEqualTo("e1");
            assertThat(merged.name()).isEqualTo("张三");
            assertThat(merged.type()).isEqualTo(EntityType.PERSON);
        }

        @Test
        void isCurrent设为true() {
            // given
            var existing = buildEntity("e1", "张三", "PM", Map.of());
            var incoming = buildEntity("i1", "张三", "资深产品经理，有十年管理经验", Map.of("新属性", "新值"));

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.mergedEntity().isCurrent()).isTrue();
        }

        @Test
        void validTo为null() {
            // given
            var existing = buildEntity("e1", "张三", "PM", Map.of());
            var incoming = buildEntity("i1", "张三", "资深产品经理，负责多条产品线的规划", Map.of("新属性", "新值"));

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.mergedEntity().validTo()).isNull();
        }

        @Test
        void sourceConversationId使用传入的conversationId() {
            // given
            var existing = buildEntity("e1", "张三", "PM", Map.of());
            var incoming = buildEntity("i1", "张三", "资深产品经理，拥有丰富的项目管理经验", Map.of("新属性", "新值"));

            // when
            var result = merger.merge(existing, incoming, "conv-42");

            // then
            assertThat(result.mergedEntity().sourceConversationId()).isEqualTo("conv-42");
        }

        @Test
        void 保留existing的accessCount和lastAccessedAt() {
            // given
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "PM",
                    Map.of(), 1, 0.8f, 0.5f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON,
                    "资深产品经理，带领团队完成核心业务升级",
                    Map.of("新属性", "新值"), 1, 0.8f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.mergedEntity().accessCount()).isEqualTo(existing.accessCount());
            assertThat(result.mergedEntity().lastAccessedAt()).isEqualTo(existing.lastAccessedAt());
        }

        @Test
        void 保留existing的createdAt() {
            // given
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "PM",
                    Map.of(), 1, 0.8f, 0.5f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON,
                    "资深产品经理，在多个知名互联网公司任职",
                    Map.of("新属性", "新值"), 1, 0.8f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.mergedEntity().createdAt()).isEqualTo(existing.createdAt());
        }

        @Test
        void validFrom和updatedAt设为当前时间() {
            // given
            var before = Instant.now();
            var existing = buildEntity("e1", "张三", "PM", Map.of());
            var incoming = buildEntity("i1", "张三", "资深产品经理，深耕产品领域超过八年",
                    Map.of("新属性", "新值"));

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            var after = Instant.now();
            assertThat(result.mergedEntity().validFrom())
                    .isAfterOrEqualTo(before)
                    .isBeforeOrEqualTo(after);
            assertThat(result.mergedEntity().updatedAt())
                    .isAfterOrEqualTo(before)
                    .isBeforeOrEqualTo(after);
        }
    }

    // ------------------------------------------------------------------
    // 混合场景
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("混合场景")
    class 混合场景 {

        @Test
        void 新属性加冲突属性加描述更长_全部正确合并() {
            // given
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "产品经理",
                    Map.of("role", "PM", "team", "平台组"), 2, 0.7f, 0.4f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON,
                    "资深产品经理，负责AI方向产品线规划与落地",
                    Map.of("role", "高级PM", "email", "zs@test.com"), 1, 0.9f, 0.8f);

            // when
            var result = merger.merge(existing, incoming, "conv-99");

            // then
            assertThat(result.isNewVersion()).isTrue();
            assertThat(result.mergedEntity().version()).isEqualTo(3);

            // 新属性 email 被添加
            assertThat(result.mergedEntity().properties()).containsEntry("email", "zs@test.com");
            // 保留的属性 team 不受影响
            assertThat(result.mergedEntity().properties()).containsEntry("team", "平台组");
            // 冲突属性 role → incoming 置信度高 → KEEP_NEW
            assertThat(result.mergedEntity().properties()).containsEntry("role", "高级PM");
            assertThat(result.conflicts()).containsKey("role");
            assertThat(result.conflicts().get("role").resolution()).isEqualTo(ConflictResolution.KEEP_NEW);

            // 描述取更长者
            assertThat(result.mergedEntity().description()).isEqualTo("资深产品经理，负责AI方向产品线规划与落地");

            // 分数取最大
            assertThat(result.mergedEntity().extractionConfidence()).isEqualTo(0.9f);
            assertThat(result.mergedEntity().importanceScore()).isEqualTo(0.8f);

            // conversationId
            assertThat(result.mergedEntity().sourceConversationId()).isEqualTo("conv-99");
        }

        @Test
        void 仅有冲突但全部KEEP_OLD_仍然isNewVersion为true() {
            // given — existing 置信度更高，所有冲突都 KEEP_OLD
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "工程师",
                    Map.of("role", "前端", "level", "P7"), 1, 0.95f, 0.5f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON, "工程师",
                    Map.of("role", "后端", "level", "P6"), 1, 0.5f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then — 有冲突就有变化 → isNewVersion=true
            assertThat(result.isNewVersion()).isTrue();
            assertThat(result.conflicts()).hasSize(2);
            // 但属性值保留旧的
            assertThat(result.mergedEntity().properties())
                    .containsEntry("role", "前端")
                    .containsEntry("level", "P7");
        }

        @Test
        void existing属性不在incoming中时_保持不变() {
            // given — existing 有 department 属性，incoming 没有
            var existing = buildEntity("e1", "张三", "工程师",
                    Map.of("role", "前端", "department", "技术部"));
            var incoming = buildEntity("i1", "张三", "工程师", Map.of("role", "前端"));

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.mergedEntity().properties()).containsEntry("department", "技术部");
        }

        @Test
        void incoming属性为空Map时_等同于无属性变更() {
            // given
            var existing = buildEntity("e1", "张三", "工程师", Map.of("role", "前端"));
            var incoming = buildEntity("i1", "张三", "工程师", Map.of());

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then — 无属性变更，描述也相同 → 不创建新版本
            assertThat(result.isNewVersion()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // ConflictDetail 记录验证
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("ConflictDetail 冲突记录")
    class 冲突记录验证 {

        @Test
        void 冲突记录包含正确的fieldName_oldValue_newValue() {
            // given
            var existing = buildEntity("e1", "张三", EntityType.PERSON, "工程师",
                    Map.of("city", "北京"), 1, 0.6f, 0.5f);
            var incoming = buildEntity("i1", "张三", EntityType.PERSON, "工程师",
                    Map.of("city", "上海"), 1, 0.9f, 0.5f);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            var conflict = result.conflicts().get("city");
            assertThat(conflict).isNotNull();
            assertThat(conflict.fieldName()).isEqualTo("city");
            assertThat(conflict.oldValue()).isEqualTo("北京");
            assertThat(conflict.newValue()).isEqualTo("上海");
            assertThat(conflict.resolution()).isEqualTo(ConflictResolution.KEEP_NEW);
        }

        @Test
        void 无冲突时conflicts为空Map() {
            // given — 仅新增属性，无冲突
            var existing = buildEntity("e1", "张三", "工程师", Map.of());
            var incoming = buildEntity("i1", "张三", "工程师", Map.of("email", "zs@test.com"));

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then
            assertThat(result.conflicts()).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 用户显式更新优先（USER_EXPLICIT / USER_CONFIRMED）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("用户显式更新优先覆盖旧值")
    class 用户显式更新优先 {

        /** 构造带 evidenceKind 的实体 */
        private static TemporalEntity buildWithEvidence(
                String id, String name, String description,
                Map<String, Object> properties,
                float confidence, float importance,
                com.lifepilot.memory.quality.MemoryEvidenceKind evidenceKind) {
            return new TemporalEntity(
                    id, EntityType.PREFERENCE, name, description,
                    properties, 1, true,
                    BASE_TIME, null, null,
                    confidence, importance, 5, BASE_TIME, BASE_TIME, BASE_TIME,
                    com.lifepilot.memory.lifecycle.LifecycleState.ACTIVE,
                    null, null, com.lifepilot.memory.lifecycle.Temporality.PERSISTENT,
                    null, false, java.util.List.of(),
                    evidenceKind,
                    com.lifepilot.memory.quality.MemoryTrustLevel.EXPLICIT,
                    0.8f, 1, null);
        }

        @Test
        void USER_EXPLICIT_incoming覆盖高置信度existing的属性() {
            // given — existing 置信度 0.95，incoming 只有 0.5 但是 USER_EXPLICIT
            var existing = buildWithEvidence("e1", "编程语言", "Java 22",
                    Map.of("lang", "Java"), 0.95f, 0.8f,
                    com.lifepilot.memory.quality.MemoryEvidenceKind.USER_EXPLICIT);
            var incoming = buildWithEvidence("i1", "编程语言", "Rust",
                    Map.of("lang", "Rust"), 0.5f, 0.7f,
                    com.lifepilot.memory.quality.MemoryEvidenceKind.USER_EXPLICIT);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then — USER_EXPLICIT 优先，即使 confidence 更低也覆盖
            assertThat(result.isNewVersion()).isTrue();
            assertThat(result.mergedEntity().properties()).containsEntry("lang", "Rust");
            assertThat(result.mergedEntity().description()).isEqualTo("Rust");
            assertThat(result.conflicts().get("lang").resolution()).isEqualTo(ConflictResolution.KEEP_NEW);
        }

        @Test
        void USER_CONFIRMED_incoming覆盖existing的描述() {
            // given — existing 描述更长，但 incoming 是 USER_CONFIRMED
            var existing = buildWithEvidence("e1", "编辑器", "用户使用 Neovim 编辑器，配置了大量插件",
                    Map.of(), 0.9f, 0.8f,
                    com.lifepilot.memory.quality.MemoryEvidenceKind.CHAT_INFERRED);
            var incoming = buildWithEvidence("i1", "编辑器", "Cursor",
                    Map.of(), 0.7f, 0.7f,
                    com.lifepilot.memory.quality.MemoryEvidenceKind.USER_CONFIRMED);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then — USER_CONFIRMED 覆盖更长的旧描述
            assertThat(result.isNewVersion()).isTrue();
            assertThat(result.mergedEntity().description()).isEqualTo("Cursor");
        }

        @Test
        void 非USER_EXPLICIT的incoming不覆盖高置信度existing() {
            // given — incoming 是 CHAT_INFERRED，置信度低于 existing
            var existing = buildWithEvidence("e1", "编程语言", "Java 22",
                    Map.of("lang", "Java"), 0.9f, 0.8f,
                    com.lifepilot.memory.quality.MemoryEvidenceKind.USER_EXPLICIT);
            var incoming = buildWithEvidence("i1", "编程语言", "Python",
                    Map.of("lang", "Python"), 0.5f, 0.5f,
                    com.lifepilot.memory.quality.MemoryEvidenceKind.CHAT_INFERRED);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then — CHAT_INFERRED 置信度低，走 KEEP_OLD
            assertThat(result.isNewVersion()).isTrue();
            assertThat(result.mergedEntity().properties()).containsEntry("lang", "Java");
            assertThat(result.conflicts().get("lang").resolution()).isEqualTo(ConflictResolution.KEEP_OLD);
        }

        @Test
        void USER_EXPLICIT_incoming描述为空时_保留existing描述() {
            // given — incoming 是 USER_EXPLICIT 但描述为空
            var existing = buildWithEvidence("e1", "城市", "用户住在北京市朝阳区",
                    Map.of(), 0.9f, 0.8f,
                    com.lifepilot.memory.quality.MemoryEvidenceKind.USER_EXPLICIT);
            var incoming = buildWithEvidence("i1", "城市", "",
                    Map.of(), 0.7f, 0.7f,
                    com.lifepilot.memory.quality.MemoryEvidenceKind.USER_EXPLICIT);

            // when
            var result = merger.merge(existing, incoming, "conv-1");

            // then — 空描述不覆盖
            assertThat(result.mergedEntity().description()).isEqualTo("用户住在北京市朝阳区");
        }
    }
}
