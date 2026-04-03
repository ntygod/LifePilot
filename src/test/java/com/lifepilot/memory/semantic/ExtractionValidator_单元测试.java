package com.lifepilot.memory.semantic;

import com.lifepilot.memory.config.MemoryProperties;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.FloatRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExtractionValidator 提取质量门控单元测试。
 *
 * <p>覆盖 AUDN 决策验证的全部规则：名称校验、描述校验、置信度阈值、
 * 数量限制、分数修正、NOOP/DELETE 特殊处理，以及各类边界条件。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@DisplayName("ExtractionValidator 提取质量门控")
class ExtractionValidator_单元测试 {

    /** 默认配置：maxEntities=10, minConfidence=0.3, maxNameLength=100, minDescLength=2 */
    private ExtractionValidator validator;

    /** 构建默认配置的 MemoryProperties */
    private static MemoryProperties defaultProperties() {
        return new MemoryProperties();
    }

    /** 构建自定义配置的 MemoryProperties */
    private static MemoryProperties customProperties(int maxEntities, float minConfidence,
                                                      int maxNameLength, int minDescLength) {
        var props = new MemoryProperties();
        var ext = props.getExtraction();
        ext.setMaxEntitiesPerExtraction(maxEntities);
        ext.setMinExtractionConfidence(minConfidence);
        ext.setMaxEntityNameLength(maxNameLength);
        ext.setMinDescriptionLength(minDescLength);
        return props;
    }

    /** 构建一条 ADD 决策（带默认合法值） */
    private static AudnDecision addDecision(String name, String description, Float confidence) {
        return new AudnDecision(AudnOperation.ADD, name, EntityType.PERSON,
                description, Map.of(), confidence, 0.8f);
    }

    /** 构建一条 UPDATE 决策 */
    private static AudnDecision updateDecision(String name, String description, Float confidence) {
        return new AudnDecision(AudnOperation.UPDATE, name, EntityType.PERSON,
                description, Map.of(), confidence, 0.8f);
    }

    /** 构建一条 DELETE 决策 */
    private static AudnDecision deleteDecision(String name) {
        return new AudnDecision(AudnOperation.DELETE, name, EntityType.PERSON,
                null, null, 0.9f, 0.5f);
    }

    /** 构建一条 NOOP 决策 */
    private static AudnDecision noopDecision(String name) {
        return new AudnDecision(AudnOperation.NOOP, name, EntityType.PERSON,
                "描述", null, 0.9f, 0.5f);
    }

    @BeforeEach
    void 初始化() {
        validator = new ExtractionValidator(defaultProperties());
    }

    // ------------------------------------------------------------------
    // 空输入与基本通过
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("空输入与基本通过")
    class 空输入与基本通过 {

        @Test
        void null输入返回空列表() {
            // when
            var result = validator.validate(null);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void 空列表输入返回空列表() {
            // when
            var result = validator.validate(List.of());

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void 单条有效ADD决策通过验证() {
            // given
            var decisions = List.of(addDecision("张三", "产品经理，负责需求管理", 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().entityName()).isEqualTo("张三");
        }

        @Test
        void 多条有效决策全部通过() {
            // given
            var decisions = List.of(
                    addDecision("张三", "产品经理", 0.9f),
                    updateDecision("李四", "更新描述", 0.7f),
                    deleteDecision("王五")
            );

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(3);
        }

        @Test
        void 返回的列表是不可变的() {
            // given
            var decisions = List.of(addDecision("张三", "有效描述", 0.8f));

            // when
            var result = validator.validate(decisions);

            // then — List.copyOf 返回不可变列表
            assertThat(result).isUnmodifiable();
        }
    }

    // ------------------------------------------------------------------
    // 实体名称校验
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("实体名称校验")
    class 实体名称校验 {

        @Test
        void 名称为null时被过滤() {
            // given
            var decisions = List.of(addDecision(null, "有效描述", 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void 名称为空字符串时被过滤() {
            // given
            var decisions = List.of(addDecision("", "有效描述", 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void 名称为纯空白字符时被过滤() {
            // given
            var decisions = List.of(addDecision("   \t\n", "有效描述", 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void 名称长度恰好等于最大长度时通过() {
            // given — 默认 maxEntityNameLength = 100
            String name = "张".repeat(100);
            var decisions = List.of(addDecision(name, "有效描述", 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().entityName()).isEqualTo(name);
        }

        @Test
        void 名称长度超过最大长度时被过滤() {
            // given — 默认 maxEntityNameLength = 100
            String name = "张".repeat(101);
            var decisions = List.of(addDecision(name, "有效描述", 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void 名称长度为1时通过() {
            // given
            var decisions = List.of(addDecision("X", "有效描述", 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(1);
        }
    }

    // ------------------------------------------------------------------
    // 描述校验
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("描述校验")
    class 描述校验 {

        @Test
        void ADD操作_描述为null时被过滤() {
            // given
            var decisions = List.of(addDecision("张三", null, 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void ADD操作_描述为空字符串时被过滤() {
            // given
            var decisions = List.of(addDecision("张三", "", 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void ADD操作_描述为纯空白时被过滤() {
            // given
            var decisions = List.of(addDecision("张三", "  \t", 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void ADD操作_描述长度低于最小值时被过滤() {
            // given — 默认 minDescriptionLength = 2，长度 1 不满足
            var decisions = List.of(addDecision("张三", "X", 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void ADD操作_描述长度恰好等于最小值时通过() {
            // given — 默认 minDescriptionLength = 2
            var decisions = List.of(addDecision("张三", "描述", 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        void UPDATE操作_描述为null仍可通过() {
            // given — validateDescription 仅对 ADD 类型强制检查
            var decisions = List.of(updateDecision("张三", null, 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        void UPDATE操作_描述为空字符串仍可通过() {
            // given
            var decisions = List.of(updateDecision("张三", "", 0.8f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(1);
        }
    }

    // ------------------------------------------------------------------
    // 置信度校验
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("置信度校验")
    class 置信度校验 {

        @Test
        void 置信度低于阈值时被过滤() {
            // given — 默认 minExtractionConfidence = 0.3
            var decisions = List.of(addDecision("张三", "有效描述", 0.29f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void 置信度恰好等于阈值时通过() {
            // given — 默认 minExtractionConfidence = 0.3
            var decisions = List.of(addDecision("张三", "有效描述", 0.3f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        void 置信度为null时被修正为0_5后通过() {
            // given — null confidence → normalizeScores 修正为 0.5f，高于默认阈值 0.3
            var decisions = List.of(addDecision("张三", "有效描述", null));

            // when
            var result = validator.validate(decisions);

            // then — 0.5 >= 0.3，通过
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().extractionConfidence()).isEqualTo(0.5f);
        }

        @Test
        void 置信度为负数时被修正为0_5后通过() {
            // given
            var decisions = List.of(addDecision("张三", "有效描述", -0.1f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().extractionConfidence()).isEqualTo(0.5f);
        }

        @Test
        void 置信度大于1时被修正为0_5后通过() {
            // given
            var decisions = List.of(addDecision("张三", "有效描述", 1.5f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().extractionConfidence()).isEqualTo(0.5f);
        }

        @Test
        void 置信度恰好为0时低于默认阈值被过滤() {
            // given — 0.0 < 0.3
            var decisions = List.of(addDecision("张三", "有效描述", 0.0f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void 置信度恰好为1时通过() {
            // given — 1.0 >= 0.3，且在 [0,1] 范围内不被修正
            var decisions = List.of(addDecision("张三", "有效描述", 1.0f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().extractionConfidence()).isEqualTo(1.0f);
        }

        @Test
        void 自定义高阈值时原本合法的置信度被过滤() {
            // given — 设置 minExtractionConfidence = 0.8
            var customValidator = new ExtractionValidator(
                    customProperties(10, 0.8f, 100, 2));
            var decisions = List.of(addDecision("张三", "有效描述", 0.7f));

            // when
            var result = customValidator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void 置信度null修正为0_5后低于高阈值被过滤() {
            // given — 阈值 0.6，null → 0.5 < 0.6
            var customValidator = new ExtractionValidator(
                    customProperties(10, 0.6f, 100, 2));
            var decisions = List.of(addDecision("张三", "有效描述", null));

            // when
            var result = customValidator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // NOOP 操作
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("NOOP 操作处理")
    class NOOP操作处理 {

        @Test
        void NOOP操作被直接跳过() {
            // given
            var decisions = List.of(noopDecision("张三"));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void NOOP混合有效决策时仅保留非NOOP() {
            // given
            var decisions = List.of(
                    noopDecision("跳过实体"),
                    addDecision("张三", "产品经理", 0.8f),
                    noopDecision("又一个跳过"),
                    deleteDecision("李四")
            );

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(2);
            assertThat(result).extracting(AudnDecision::entityName)
                    .containsExactlyInAnyOrder("张三", "李四");
        }

        @Test
        void 全部为NOOP时返回空列表() {
            // given
            var decisions = List.of(
                    noopDecision("实体A"),
                    noopDecision("实体B"),
                    noopDecision("实体C")
            );

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // DELETE 操作
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("DELETE 操作处理")
    class DELETE操作处理 {

        @Test
        void DELETE操作跳过名称和描述校验() {
            // given — DELETE 的 entityName 为 null，description 也为 null
            var decision = new AudnDecision(AudnOperation.DELETE, null, EntityType.PERSON,
                    null, null, 0.9f, 0.5f);
            var decisions = List.of(decision);

            // when
            var result = validator.validate(decisions);

            // then — DELETE 直接放行
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().operation()).isEqualTo(AudnOperation.DELETE);
        }

        @Test
        void DELETE操作跳过置信度校验() {
            // given — DELETE 的置信度为 0，低于阈值
            var decision = new AudnDecision(AudnOperation.DELETE, "待删除实体", EntityType.PERSON,
                    null, null, 0.0f, 0.0f);
            var decisions = List.of(decision);

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(1);
        }
    }

    // ------------------------------------------------------------------
    // 数量限制
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("数量限制")
    class 数量限制 {

        @Test
        void 超过上限时按置信度降序截断() {
            // given — maxEntitiesPerExtraction = 3
            var customValidator = new ExtractionValidator(
                    customProperties(3, 0.1f, 100, 2));
            var decisions = List.of(
                    addDecision("低置信度", "描述A", 0.3f),
                    addDecision("高置信度", "描述B", 0.9f),
                    addDecision("中置信度", "描述C", 0.6f),
                    addDecision("最高置信度", "描述D", 0.95f),
                    addDecision("较低置信度", "描述E", 0.2f)
            );

            // when
            var result = customValidator.validate(decisions);

            // then — 只保留置信度最高的 3 条
            assertThat(result).hasSize(3);
            assertThat(result).extracting(AudnDecision::entityName)
                    .containsExactlyInAnyOrder("最高置信度", "高置信度", "中置信度");
        }

        @Test
        void 恰好等于上限时不截断() {
            // given
            var customValidator = new ExtractionValidator(
                    customProperties(3, 0.1f, 100, 2));
            var decisions = List.of(
                    addDecision("实体A", "描述A", 0.8f),
                    addDecision("实体B", "描述B", 0.7f),
                    addDecision("实体C", "描述C", 0.6f)
            );

            // when
            var result = customValidator.validate(decisions);

            // then
            assertThat(result).hasSize(3);
        }

        @Test
        void 未达上限时不截断() {
            // given
            var customValidator = new ExtractionValidator(
                    customProperties(10, 0.1f, 100, 2));
            var decisions = List.of(
                    addDecision("实体A", "描述A", 0.8f),
                    addDecision("实体B", "描述B", 0.7f)
            );

            // when
            var result = customValidator.validate(decisions);

            // then
            assertThat(result).hasSize(2);
        }

        @Test
        void 截断时置信度为null的条目排在末尾被丢弃() {
            // given — null confidence → 修正为 0.5f
            // 上限为 2，5 条中只保留最高 2 条
            var customValidator = new ExtractionValidator(
                    customProperties(2, 0.1f, 100, 2));
            var decisions = List.of(
                    addDecision("高置信", "描述A", 0.9f),
                    addDecision("空置信", "描述B", null),    // → 0.5f
                    addDecision("最高置信", "描述C", 0.95f)
            );

            // when
            var result = customValidator.validate(decisions);

            // then — 保留 0.95 和 0.9，0.5（修正后）被截断
            assertThat(result).hasSize(2);
            assertThat(result).extracting(AudnDecision::entityName)
                    .containsExactlyInAnyOrder("最高置信", "高置信");
        }
    }

    // ------------------------------------------------------------------
    // normalizeScores 分数修正
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("normalizeScores 分数修正")
    class 分数修正 {

        @Test
        void 合法分数不被修正() {
            // given
            var decision = addDecision("张三", "有效描述", 0.8f);

            // when
            var result = validator.normalizeScores(decision);

            // then — 原始值保持不变
            assertThat(result.extractionConfidence()).isEqualTo(0.8f);
            assertThat(result.importanceScore()).isEqualTo(0.8f);
            // 应返回原对象（needsFix = false）
            assertThat(result).isSameAs(decision);
        }

        @Test
        void confidence为null修正为0_5() {
            // given
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "描述", Map.of(), null, 0.8f);

            // when
            var result = validator.normalizeScores(decision);

            // then
            assertThat(result.extractionConfidence()).isEqualTo(0.5f);
            assertThat(result.importanceScore()).isEqualTo(0.8f);
        }

        @Test
        void importance为null修正为0_5() {
            // given
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "描述", Map.of(), 0.8f, null);

            // when
            var result = validator.normalizeScores(decision);

            // then
            assertThat(result.extractionConfidence()).isEqualTo(0.8f);
            assertThat(result.importanceScore()).isEqualTo(0.5f);
        }

        @Test
        void 两个分数同时为null时均修正为0_5() {
            // given
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "描述", Map.of(), null, null);

            // when
            var result = validator.normalizeScores(decision);

            // then
            assertThat(result.extractionConfidence()).isEqualTo(0.5f);
            assertThat(result.importanceScore()).isEqualTo(0.5f);
        }

        @Test
        void confidence负数修正为0_5() {
            // given
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "描述", Map.of(), -0.5f, 0.8f);

            // when
            var result = validator.normalizeScores(decision);

            // then
            assertThat(result.extractionConfidence()).isEqualTo(0.5f);
        }

        @Test
        void confidence超过1修正为0_5() {
            // given
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "描述", Map.of(), 1.1f, 0.8f);

            // when
            var result = validator.normalizeScores(decision);

            // then
            assertThat(result.extractionConfidence()).isEqualTo(0.5f);
        }

        @Test
        void importance负数修正为0_5() {
            // given
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "描述", Map.of(), 0.8f, -1.0f);

            // when
            var result = validator.normalizeScores(decision);

            // then
            assertThat(result.importanceScore()).isEqualTo(0.5f);
        }

        @Test
        void importance超过1修正为0_5() {
            // given
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "描述", Map.of(), 0.8f, 2.0f);

            // when
            var result = validator.normalizeScores(decision);

            // then
            assertThat(result.importanceScore()).isEqualTo(0.5f);
        }

        @Test
        void 边界值0和1不被修正() {
            // given
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "描述", Map.of(), 0.0f, 1.0f);

            // when
            var result = validator.normalizeScores(decision);

            // then — 0.0 和 1.0 在 [0,1] 范围内，不修正
            assertThat(result.extractionConfidence()).isEqualTo(0.0f);
            assertThat(result.importanceScore()).isEqualTo(1.0f);
            assertThat(result).isSameAs(decision);
        }

        @Test
        void 修正后保留其他字段不变() {
            // given
            var properties = Map.<String, Object>of("key", "value");
            var decision = new AudnDecision(AudnOperation.UPDATE, "实体名", EntityType.PROJECT,
                    "项目描述", properties, null, null);

            // when
            var result = validator.normalizeScores(decision);

            // then — 非分数字段保持原值
            assertThat(result.operation()).isEqualTo(AudnOperation.UPDATE);
            assertThat(result.entityName()).isEqualTo("实体名");
            assertThat(result.entityType()).isEqualTo(EntityType.PROJECT);
            assertThat(result.description()).isEqualTo("项目描述");
            assertThat(result.properties()).isEqualTo(properties);
        }
    }

    // ------------------------------------------------------------------
    // 综合场景
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("综合场景")
    class 综合场景 {

        @Test
        void 混合多种操作类型_仅无效的被过滤() {
            // given
            var decisions = List.of(
                    addDecision("有效ADD", "足够长的描述", 0.8f),     // 通过
                    addDecision("", "有效描述", 0.8f),                // 名称空 → 过滤
                    updateDecision("有效UPDATE", null, 0.5f),         // UPDATE 不检查描述 → 通过
                    deleteDecision("有效DELETE"),                      // DELETE 直接通过
                    noopDecision("跳过的NOOP"),                       // NOOP → 跳过
                    addDecision("低置信度ADD", "有效描述", 0.1f)      // 置信度 < 0.3 → 过滤
            );

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).hasSize(3);
            assertThat(result).extracting(AudnDecision::entityName)
                    .containsExactlyInAnyOrder("有效ADD", "有效UPDATE", "有效DELETE");
        }

        @Test
        void 全部无效决策返回空列表() {
            // given
            var decisions = List.of(
                    addDecision(null, "描述", 0.8f),           // 名称 null
                    addDecision("名称", "", 0.8f),             // ADD 描述空
                    addDecision("名称2", "有效描述", 0.1f),    // 置信度太低
                    noopDecision("跳过")                        // NOOP
            );

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void 验证顺序_名称校验先于描述校验先于置信度校验() {
            // given — 名称为空的 ADD 决策，描述也无效，置信度也低
            // 应在名称校验阶段就被过滤
            var decisions = List.of(addDecision("", "", 0.1f));

            // when
            var result = validator.validate(decisions);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        void 过滤后再截断_先过滤无效再按上限截断() {
            // given — 上限 2，5 条中 2 条无效，剩 3 条有效，截断为 2
            var customValidator = new ExtractionValidator(
                    customProperties(2, 0.1f, 100, 2));
            var decisions = List.of(
                    addDecision("高", "描述", 0.9f),          // 有效
                    addDecision(null, "描述", 0.95f),          // 名称 null → 过滤
                    addDecision("中", "描述", 0.5f),           // 有效
                    noopDecision("跳过"),                       // NOOP → 跳过
                    addDecision("低", "描述", 0.3f)            // 有效
            );

            // when
            var result = customValidator.validate(decisions);

            // then — 3 条有效，截断为 2，保留置信度最高的
            assertThat(result).hasSize(2);
            assertThat(result).extracting(AudnDecision::entityName)
                    .containsExactlyInAnyOrder("高", "中");
        }

        @Test
        void 自定义短名称限制_超过限制长度的名称被过滤() {
            // given — maxEntityNameLength = 20，名称超过 20 时源码用 substring(0,20) 记日志
            var customValidator = new ExtractionValidator(
                    customProperties(10, 0.1f, 20, 2));
            String shortName = "张三";                                     // 长度 2 ≤ 20 → 通过
            String longName = "这是一个非常非常非常长的实体名称超过了限制应该会被过滤掉"; // 长度 > 20 → 过滤
            var decisions = List.of(
                    addDecision(shortName, "有效描述", 0.8f),
                    addDecision(longName, "有效描述", 0.8f)
            );

            // when
            var result = customValidator.validate(decisions);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().entityName()).isEqualTo(shortName);
        }

        @Test
        void 自定义高描述最小长度_短描述的ADD被过滤() {
            // given
            var customValidator = new ExtractionValidator(
                    customProperties(10, 0.1f, 100, 10));
            var decisions = List.of(
                    addDecision("张三", "这是一段足够长的描述信息", 0.8f),  // 长度 > 10 → 通过
                    addDecision("李四", "短描述", 0.8f)                     // 长度 3 < 10 → 过滤
            );

            // when
            var result = customValidator.validate(decisions);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().entityName()).isEqualTo("张三");
        }

        @Test
        void DELETE不计入数量限制的验证规则_但计入截断总数() {
            // given — 上限 2
            var customValidator = new ExtractionValidator(
                    customProperties(2, 0.1f, 100, 2));
            var decisions = List.of(
                    deleteDecision("删除实体A"),
                    deleteDecision("删除实体B"),
                    addDecision("新增实体", "有效描述", 0.8f)
            );

            // when
            var result = customValidator.validate(decisions);

            // then — 3 条有效（2 DELETE + 1 ADD），截断为 2
            // DELETE 的 confidence 是 0.9f，ADD 的是 0.8f
            // 排序后 DELETE 的两条在前
            assertThat(result).hasSize(2);
        }
    }

    // ------------------------------------------------------------------
    // 属性测试 (jqwik)
    // ------------------------------------------------------------------

    @Property(tries = 100)
    void 合法范围内的置信度不被修正(
            @ForAll @FloatRange(min = 0.0f, max = 1.0f) float confidence,
            @ForAll @FloatRange(min = 0.0f, max = 1.0f) float importance) {
        // given — jqwik 不执行 @BeforeEach，手动构造 validator
        var propValidator = new ExtractionValidator(defaultProperties());
        var decision = new AudnDecision(AudnOperation.ADD, "测试实体", EntityType.PERSON,
                "有效描述", Map.of(), confidence, importance);

        // when
        var result = propValidator.normalizeScores(decision);

        // then — 合法范围内应返回原对象
        assertThat(result).isSameAs(decision);
    }

    @Property(tries = 50)
    void 验证结果数量不超过上限(
            @ForAll @FloatRange(min = 0.3f, max = 1.0f) float conf1,
            @ForAll @FloatRange(min = 0.3f, max = 1.0f) float conf2,
            @ForAll @FloatRange(min = 0.3f, max = 1.0f) float conf3,
            @ForAll @FloatRange(min = 0.3f, max = 1.0f) float conf4,
            @ForAll @FloatRange(min = 0.3f, max = 1.0f) float conf5) {
        // given — 上限设为 3，jqwik 不执行 @BeforeEach，手动构造
        var customValidator = new ExtractionValidator(
                customProperties(3, 0.1f, 100, 2));
        var decisions = List.of(
                addDecision("实体A", "描述A", conf1),
                addDecision("实体B", "描述B", conf2),
                addDecision("实体C", "描述C", conf3),
                addDecision("实体D", "描述D", conf4),
                addDecision("实体E", "描述E", conf5)
        );

        // when
        var result = customValidator.validate(decisions);

        // then
        assertThat(result).hasSizeLessThanOrEqualTo(3);
    }
}
