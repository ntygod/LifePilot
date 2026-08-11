package com.lifepilot.agent.learning.extraction;

import com.lifepilot.agent.learning.extraction.ExtractionValidator;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.semantic.AudnDecision;
import com.lifepilot.memory.semantic.AudnOperation;
import com.lifepilot.memory.store.entity.EntityType;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.FloatRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ExtractionValidator 提取质量门控单元测试。
 *
 * <p>覆盖 AUDN 决策验证的全部规则：名称校验、描述校验、评分契约、
 * 置信度阈值、数量限制、NOOP/DELETE 特殊处理，以及各类边界条件。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@DisplayName("ExtractionValidator 提取质量门控")
class ExtractionValidator_单元测试 {

    /** 默认配置：maxEntities=10, minConfidence=0.3, maxNameLength=100, minDescLength=2 */
    private ExtractionValidator validator;

    /** 构建默认配置的 AgentLearningProperties */
    private static AgentLearningProperties defaultProperties() {
        return new AgentLearningProperties();
    }

    /** 构建自定义配置的 AgentLearningProperties */
    private static AgentLearningProperties customProperties(int maxEntities, float minConfidence,
                                                      int maxNameLength, int minDescLength) {
        var props = new AgentLearningProperties();
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
                description, Map.of(), confidence, 0.8f, "PERSISTENT", null, "USER_EXPLICIT", evidence(name));
    }

    private static String evidence(String name) {
        return name != null && !name.isBlank() ? name : "用户明确陈述";
    }

    /** 构建一条 UPDATE 决策 */
    private static AudnDecision updateDecision(String name, String description, Float confidence) {
        return new AudnDecision(AudnOperation.UPDATE, name, EntityType.PERSON,
                description, Map.of(), confidence, 0.8f, "PERSISTENT", null, "USER_EXPLICIT", evidence(name));
    }

    /** 构建一条 DELETE 决策 */
    private static AudnDecision deleteDecision(String name) {
        return new AudnDecision(AudnOperation.DELETE, name, EntityType.PERSON,
                null, null, 0.9f, 0.5f, "PERSISTENT", null, "USER_EXPLICIT", evidence(name));
    }

    /** 构建一条 NOOP 决策 */
    private static AudnDecision noopDecision(String name) {
        return new AudnDecision(AudnOperation.NOOP, name, EntityType.PERSON,
                "描述", null, 0.9f, 0.5f, null, null, null, null);
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
        void null输入应直接失败() {
            assertThatThrownBy(() -> validator.validate(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("AUDN 决策列表不能为空");
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
        void 名称为null时应直接失败() {
            // given
            var decisions = List.of(addDecision(null, "有效描述", 0.8f));

            assertThatThrownBy(() -> validator.validate(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策实体名称不能为空");
        }

        @Test
        void 名称为空字符串时应直接失败() {
            // given
            var decisions = List.of(addDecision("", "有效描述", 0.8f));

            assertThatThrownBy(() -> validator.validate(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策实体名称不能为空");
        }

        @Test
        void 名称为纯空白字符时应直接失败() {
            // given
            var decisions = List.of(addDecision("   \t\n", "有效描述", 0.8f));

            assertThatThrownBy(() -> validator.validate(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策实体名称不能为空");
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
        void 名称长度超过最大长度时应直接失败() {
            // given — 默认 maxEntityNameLength = 100
            String name = "张".repeat(101);
            var decisions = List.of(addDecision(name, "有效描述", 0.8f));

            assertThatThrownBy(() -> validator.validate(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策实体名称超长")
                    .hasMessageContaining("max=100");
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
        void ADD操作_描述为null时应直接失败() {
            // given
            var decisions = List.of(addDecision("张三", null, 0.8f));

            assertThatThrownBy(() -> validator.validate(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN ADD 决策描述不能为空");
        }

        @Test
        void ADD操作_描述为空字符串时应直接失败() {
            // given
            var decisions = List.of(addDecision("张三", "", 0.8f));

            assertThatThrownBy(() -> validator.validate(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN ADD 决策描述不能为空");
        }

        @Test
        void ADD操作_描述为纯空白时应直接失败() {
            // given
            var decisions = List.of(addDecision("张三", "  \t", 0.8f));

            assertThatThrownBy(() -> validator.validate(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN ADD 决策描述不能为空");
        }

        @Test
        void ADD操作_描述长度低于最小值时应直接失败() {
            // given — 默认 minDescriptionLength = 2，长度 1 不满足
            var decisions = List.of(addDecision("张三", "X", 0.8f));

            assertThatThrownBy(() -> validator.validate(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN ADD 决策描述不能为空且长度不能低于 2");
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
    @DisplayName("证据门槛")
    class 证据门槛 {

        @Test
        void ADD缺少evidenceKind时应直接失败() {
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "有效描述", Map.of(), 0.8f, 0.8f, "PERSISTENT", null, null, "张三");

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 evidenceKind 不能为空");
        }

        @Test
        void UNKNOWN证据不写主库() {
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "有效描述", Map.of(), 0.8f, 0.8f, "PERSISTENT", null, "UNKNOWN", "张三");

            var result = validator.validateWithResult(List.of(decision));

            assertThat(result.validDecisions()).isEmpty();
            assertThat(result.rejectedDecisions().getFirst().reason()).isEqualTo("UNKNOWN_EVIDENCE");
        }

        @Test
        void USER_EXPLICIT缺少证据片段时应直接失败() {
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "有效描述", Map.of(), 0.8f, 0.8f, "PERSISTENT", null, "USER_EXPLICIT", null);

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 evidenceExcerpt 不能为空");
        }

        @Test
        void CHAT_INFERRED低于可信阈值时拒绝() {
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "有效描述", Map.of(), 0.59f, 0.8f, "PERSISTENT", null, "CHAT_INFERRED", "张三");

            var result = validator.validateWithResult(List.of(decision));

            assertThat(result.validDecisions()).isEmpty();
            assertThat(result.rejectedDecisions().getFirst().reason()).isEqualTo("INFERRED_LOW_TRUST");
        }
    }

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
        void 置信度为null时应直接失败() {
            // given
            var decision = addDecision("张三", "有效描述", null);

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 extractionConfidence 不能为空");
        }

        @Test
        void 置信度为负数时应直接失败() {
            // given
            var decision = addDecision("张三", "有效描述", -0.1f);

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 extractionConfidence 越界");
        }

        @Test
        void 置信度大于1时应直接失败() {
            // given
            var decision = addDecision("张三", "有效描述", 1.5f);

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 extractionConfidence 越界");
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
            // given — 1.0 >= 0.3，且在 [0,1] 范围内
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
        void 置信度null时优先按契约缺失失败() {
            // given — null 不参与阈值比较，直接按契约缺失拒绝
            var customValidator = new ExtractionValidator(
                    customProperties(10, 0.6f, 100, 2));
            var decision = addDecision("张三", "有效描述", null);

            assertThatThrownBy(() -> customValidator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 extractionConfidence 不能为空");
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
        void DELETE操作缺少名称时应直接失败() {
            // given — DELETE 必须有明确删除目标
            var decision = new AudnDecision(AudnOperation.DELETE, null, EntityType.PERSON,
                    null, null, 0.9f, 0.5f, "PERSISTENT", null, "USER_EXPLICIT", "用户明确陈述");

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策实体名称不能为空");
        }

        @Test
        void DELETE操作不要求描述() {
            // given
            var decision = new AudnDecision(AudnOperation.DELETE, "待删除实体", EntityType.PERSON,
                    null, null, 0.9f, 0.5f, "PERSISTENT", null, "USER_EXPLICIT", "我不再学 Rust 了");

            // when
            var result = validator.validate(List.of(decision));

            // then
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().operation()).isEqualTo(AudnOperation.DELETE);
        }

        @Test
        void DELETE操作遵守置信度阈值() {
            // given — DELETE 的置信度为 0，低于阈值
            var decision = new AudnDecision(AudnOperation.DELETE, "待删除实体", EntityType.PERSON,
                    null, null, 0.0f, 0.5f, "PERSISTENT", null, "USER_EXPLICIT", "我不再学 Rust 了");

            // when
            var result = validator.validateWithResult(List.of(decision));

            // then
            assertThat(result.validDecisions()).isEmpty();
            assertThat(result.rejectedDecisions())
                    .singleElement()
                    .satisfies(rejected -> assertThat(rejected.reason()).isEqualTo("LOW_CONFIDENCE"));
        }

        @Test
        void DELETE操作缺少证据片段时应直接失败() {
            // given
            var decision = new AudnDecision(AudnOperation.DELETE, "待删除实体", EntityType.PERSON,
                    null, null, 0.9f, 0.5f, "PERSISTENT", null, "USER_EXPLICIT", null);

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 evidenceExcerpt 不能为空");
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
        void 截断前遇到缺失置信度应直接失败() {
            // given — 上限为 2，结构坏输出不参与质量门控截断
            var customValidator = new ExtractionValidator(
                    customProperties(2, 0.1f, 100, 2));
            var decisions = List.of(
                    addDecision("高置信", "描述A", 0.9f),
                    addDecision("空置信", "描述B", null),
                    addDecision("最高置信", "描述C", 0.95f)
            );

            assertThatThrownBy(() -> customValidator.validateWithResult(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 extractionConfidence 不能为空");
        }
    }

    // ------------------------------------------------------------------
    // 评分契约
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("评分契约")
    class 评分契约 {

        @Test
        void 合法评分通过验证且保留原始对象() {
            // given
            var decision = addDecision("张三", "有效描述", 0.8f);

            // when
            var result = validator.validateWithResult(List.of(decision));

            // then
            assertThat(result.rejectedDecisions()).isEmpty();
            assertThat(result.validDecisions()).containsExactly(decision);
        }

        @Test
        void importance为null时应直接失败() {
            // given
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "描述", Map.of(), 0.8f, null, "PERSISTENT", null, "USER_EXPLICIT", "张三");

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 importanceScore 不能为空");
        }

        @Test
        void 两个分数同时为null时优先报告置信度缺失并失败() {
            // given
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "描述", Map.of(), null, null, "PERSISTENT", null, "USER_EXPLICIT", "张三");

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 extractionConfidence 不能为空");
        }

        @Test
        void importance负数时应直接失败() {
            // given
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "描述", Map.of(), 0.8f, -1.0f, "PERSISTENT", null, "USER_EXPLICIT", "张三");

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 importanceScore 越界");
        }

        @Test
        void importance超过1时应直接失败() {
            // given
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "描述", Map.of(), 0.8f, 2.0f, "PERSISTENT", null, "USER_EXPLICIT", "张三");

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 importanceScore 越界");
        }

        @Test
        void 评分边界值0和1按范围契约通过() {
            // given
            var rangeValidator = new ExtractionValidator(customProperties(10, 0.0f, 100, 2));
            var decision = new AudnDecision(AudnOperation.ADD, "张三", EntityType.PERSON,
                    "描述", Map.of(), 0.0f, 1.0f, "PERSISTENT", null, "USER_EXPLICIT", "张三");

            // when
            var result = rangeValidator.validateWithResult(List.of(decision));

            // then
            assertThat(result.rejectedDecisions()).isEmpty();
            assertThat(result.validDecisions()).containsExactly(decision);
        }

        @Test
        void 非法temporality应直接失败() {
            var decision = new AudnDecision(AudnOperation.ADD, "临时状态", EntityType.GOAL,
                    "描述足够长以通过基础校验", Map.of(), 0.9f, 0.5f,
                    "TEMP", null, "USER_EXPLICIT", "用户明确陈述");

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 temporality 非法");
        }

        @Test
        void 缺少temporality应直接失败() {
            var decision = new AudnDecision(AudnOperation.ADD, "临时状态", EntityType.GOAL,
                    "描述足够长以通过基础校验", Map.of(), 0.9f, 0.5f,
                    null, null, "USER_EXPLICIT", "用户明确陈述");

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 temporality 不能为空");
        }

        @Test
        void temporality包含首尾空白应直接失败() {
            var decision = new AudnDecision(AudnOperation.ADD, "临时状态", EntityType.GOAL,
                    "描述足够长以通过基础校验", Map.of(), 0.9f, 0.5f,
                    " PERSISTENT ", null, "USER_EXPLICIT", "用户明确陈述");

            assertThatThrownBy(() -> validator.validateWithResult(List.of(decision)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策 temporality 不能包含首尾空白");
        }
    }

    // ------------------------------------------------------------------
    // 综合场景
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("综合场景")
    class 综合场景 {

        @Test
        void 混合多种操作类型_包含结构坏输出时直接失败() {
            // given
            var decisions = List.of(
                    addDecision("有效ADD", "足够长的描述", 0.8f),     // 通过
                    addDecision("", "有效描述", 0.8f),                // 名称空 → 过滤
                    updateDecision("有效UPDATE", null, 0.5f),         // UPDATE 不检查描述 → 通过
                    deleteDecision("有效DELETE"),                      // DELETE 目标、评分和证据完整
                    noopDecision("跳过的NOOP"),                       // NOOP → 跳过
                    addDecision("低置信度ADD", "有效描述", 0.1f)      // 置信度 < 0.3 → 过滤
            );

            assertThatThrownBy(() -> validator.validate(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策实体名称不能为空");
        }

        @Test
        void 全部为质量拒绝或NOOP时返回空列表并记录拒绝() {
            // given
            var decisions = List.of(
                    addDecision("名称2", "有效描述", 0.1f),    // 置信度太低
                    noopDecision("跳过")                        // NOOP
            );

            // when
            var result = validator.validateWithResult(decisions);

            // then
            assertThat(result.validDecisions()).isEmpty();
            assertThat(result.rejectedDecisions())
                    .singleElement()
                    .satisfies(rejected -> assertThat(rejected.reason()).isEqualTo("LOW_CONFIDENCE"));
        }

        @Test
        void 验证顺序_名称校验先于描述校验先于置信度校验并失败() {
            // given — 名称为空的 ADD 决策，描述也无效，置信度也低
            // 应在名称校验阶段就失败
            var decisions = List.of(addDecision("", "", 0.1f));

            assertThatThrownBy(() -> validator.validate(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策实体名称不能为空");
        }

        @Test
        void 截断前包含结构坏输出时直接失败() {
            // given — 上限 2，结构坏输出不再被过滤后继续截断
            var customValidator = new ExtractionValidator(
                    customProperties(2, 0.1f, 100, 2));
            var decisions = List.of(
                    addDecision("高", "描述", 0.9f),          // 有效
                    addDecision(null, "描述", 0.95f),          // 名称 null → 过滤
                    addDecision("中", "描述", 0.5f),           // 有效
                    noopDecision("跳过"),                       // NOOP → 跳过
                    addDecision("低", "描述", 0.3f)            // 有效
            );

            assertThatThrownBy(() -> customValidator.validate(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策实体名称不能为空");
        }

        @Test
        void 自定义短名称限制_超过限制长度的名称直接失败() {
            // given — maxEntityNameLength = 20，名称超过 20 时源码用 substring(0,20) 记日志
            var customValidator = new ExtractionValidator(
                    customProperties(10, 0.1f, 20, 2));
            String shortName = "张三";                                     // 长度 2 ≤ 20 → 通过
            String longName = "这是一个非常非常非常长的实体名称超过了限制应该会被过滤掉"; // 长度 > 20 → 过滤
            var decisions = List.of(
                    addDecision(shortName, "有效描述", 0.8f),
                    addDecision(longName, "有效描述", 0.8f)
            );

            assertThatThrownBy(() -> customValidator.validate(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN 决策实体名称超长")
                    .hasMessageContaining("max=20");
        }

        @Test
        void 自定义高描述最小长度_短描述的ADD直接失败() {
            // given
            var customValidator = new ExtractionValidator(
                    customProperties(10, 0.1f, 100, 10));
            var decisions = List.of(
                    addDecision("张三", "这是一段足够长的描述信息", 0.8f),  // 长度 > 10 → 通过
                    addDecision("李四", "短描述", 0.8f)                     // 长度 3 < 10 → 过滤
            );

            assertThatThrownBy(() -> customValidator.validate(decisions))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDN ADD 决策描述不能为空且长度不能低于 10");
        }

        @Test
        void DELETE按完整契约验证后计入截断总数() {
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
    void 合法范围内的评分不触发契约拒绝(
            @ForAll @FloatRange(min = 0.0f, max = 1.0f) float confidence,
            @ForAll @FloatRange(min = 0.0f, max = 1.0f) float importance) {
        // given — jqwik 不执行 @BeforeEach，手动构造 validator
        var propValidator = new ExtractionValidator(customProperties(10, 0.0f, 100, 2));
        var decision = new AudnDecision(AudnOperation.ADD, "测试实体", EntityType.PERSON,
                "有效描述", Map.of(), confidence, importance, "PERSISTENT", null, "USER_EXPLICIT", "测试实体");

        // when
        var result = propValidator.validateWithResult(List.of(decision));

        // then
        assertThat(result.rejectedDecisions()).isEmpty();
        assertThat(result.validDecisions()).containsExactly(decision);
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
