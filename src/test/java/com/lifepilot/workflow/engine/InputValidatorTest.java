package com.lifepilot.workflow.engine;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.lifepilot.workflow.model.WorkflowInputParam;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InputValidator 单元测试，覆盖正确性属性 P1-P4。
 *
 * @author zsg
 * @since 2026-03-11
 */
class InputValidatorTest {

    // ── P1: 必填参数完整性 ──────────────────────────────

    @Test
    void P1_缺少必填参数_校验失败() {
        var paramDefs = Map.of(
                "topic", new WorkflowInputParam("topic", "string", true, null, "研究主题", null, null, null, null, null, null)
        );

        var result = InputValidator.validate(paramDefs, Map.of());

        assertFalse(result.valid());
        assertEquals(1, result.missingParams().size());
        assertTrue(result.missingParams().contains("topic"));
    }

    @Test
    void P1_缺少多个必填参数_全部列出() {
        var paramDefs = Map.of(
                "topic", new WorkflowInputParam("topic", "string", true, null, null, null, null, null, null, null, null),
                "depth", new WorkflowInputParam("depth", "string", true, null, null, null, null, null, null, null, null)
        );

        var result = InputValidator.validate(paramDefs, Map.of());

        assertFalse(result.valid());
        assertEquals(2, result.missingParams().size());
        assertTrue(result.missingParams().contains("topic"));
        assertTrue(result.missingParams().contains("depth"));
    }

    @Test
    void P1_必填参数已提供_校验通过() {
        var paramDefs = Map.of(
                "topic", new WorkflowInputParam("topic", "string", true, null, null, null, null, null, null, null, null)
        );

        var result = InputValidator.validate(paramDefs, Map.of("topic", "AI"));

        assertTrue(result.valid());
        assertTrue(result.missingParams().isEmpty());
        assertEquals("AI", result.mergedInputs().get("topic"));
    }

    @Test
    void P1_必填参数有默认值且用户未提供_校验通过() {
        var paramDefs = Map.of(
                "topic", new WorkflowInputParam("topic", "string", true, "默认主题", null, null, null, null, null, null, null)
        );

        var result = InputValidator.validate(paramDefs, Map.of());

        assertTrue(result.valid());
        assertTrue(result.missingParams().isEmpty());
        assertEquals("默认主题", result.mergedInputs().get("topic"));
    }

    // ── P2: 默认值填充幂等性 ──────────────────────────────

    @Test
    void P2_可选参数未提供且有默认值_填入默认值() {
        var paramDefs = Map.of(
                "depth", new WorkflowInputParam("depth", "string", false, "standard", "搜索深度", null, null, null, null, null, null)
        );

        var result = InputValidator.validate(paramDefs, Map.of());

        assertTrue(result.valid());
        assertEquals("standard", result.mergedInputs().get("depth"));
    }

    @Test
    void P2_可选参数已提供_保留用户值() {
        var paramDefs = Map.of(
                "depth", new WorkflowInputParam("depth", "string", false, "standard", null, null, null, null, null, null, null)
        );

        var result = InputValidator.validate(paramDefs, Map.of("depth", "deep"));

        assertTrue(result.valid());
        assertEquals("deep", result.mergedInputs().get("depth"));
    }

    @Test
    void P2_可选参数未提供且无默认值_不出现在mergedInputs() {
        var paramDefs = Map.of(
                "note", new WorkflowInputParam("note", "string", false, null, null, null, null, null, null, null, null)
        );

        var result = InputValidator.validate(paramDefs, Map.of());

        assertTrue(result.valid());
        assertFalse(result.mergedInputs().containsKey("note"));
    }

    // ── P3: 空定义透传 ──────────────────────────────────

    @Test
    void P3_空paramDefs_直接通过() {
        var result = InputValidator.validate(Map.of(), Map.of("extra", "value"));

        assertTrue(result.valid());
        assertTrue(result.missingParams().isEmpty());
        assertEquals("value", result.mergedInputs().get("extra"));
    }

    @Test
    void P3_null_paramDefs_直接通过() {
        var result = InputValidator.validate(null, Map.of("key", "val"));

        assertTrue(result.valid());
        assertEquals("val", result.mergedInputs().get("key"));
    }

    @Test
    void P3_null_userInputs_空定义_通过() {
        var result = InputValidator.validate(Map.of(), null);

        assertTrue(result.valid());
        assertTrue(result.mergedInputs().isEmpty());
    }

    // ── P4: 多余参数忽略 ──────────────────────────────────

    @Test
    void P4_多余参数保留在mergedInputs中() {
        var paramDefs = Map.of(
                "topic", new WorkflowInputParam("topic", "string", true, null, null, null, null, null, null, null, null)
        );
        var userInputs = new HashMap<String, Object>();
        userInputs.put("topic", "AI");
        userInputs.put("extraParam", "should-be-kept");

        var result = InputValidator.validate(paramDefs, userInputs);

        assertTrue(result.valid());
        assertEquals("AI", result.mergedInputs().get("topic"));
        assertEquals("should-be-kept", result.mergedInputs().get("extraParam"));
    }

    @Test
    void P4_多余参数不影响校验结果() {
        var paramDefs = Map.of(
                "topic", new WorkflowInputParam("topic", "string", true, null, null, null, null, null, null, null, null)
        );
        var userInputs = new HashMap<String, Object>();
        userInputs.put("unknownField", 42);

        var result = InputValidator.validate(paramDefs, userInputs);

        assertFalse(result.valid());
        assertTrue(result.missingParams().contains("topic"));
        assertEquals(42, result.mergedInputs().get("unknownField"));
    }

    // ── P5: 正则校验 ──────────────────────────────────────

    @Test
    void P5_字符串值匹配正则_校验通过() {
        var paramDefs = Map.of(
                "email", new WorkflowInputParam("email", "string", true, null, null, null, null, null, null, "^[\\w.]+@[\\w.]+$", "邮箱格式不正确")
        );

        var result = InputValidator.validate(paramDefs, Map.of("email", "test@example.com"));

        assertTrue(result.valid());
        assertTrue(result.validationErrors().isEmpty());
    }

    @Test
    void P5_字符串值不匹配正则_校验失败并返回validationMessage() {
        var paramDefs = Map.of(
                "email", new WorkflowInputParam("email", "string", true, null, null, null, null, null, null, "^[\\w.]+@[\\w.]+$", "邮箱格式不正确")
        );

        var result = InputValidator.validate(paramDefs, Map.of("email", "not-an-email"));

        assertFalse(result.valid());
        assertEquals(1, result.validationErrors().size());
        assertEquals("邮箱格式不正确", result.validationErrors().getFirst());
        assertTrue(result.missingParams().isEmpty());
    }

    @Test
    void P5_validationMessage为null时_使用默认错误消息() {
        var paramDefs = Map.of(
                "code", new WorkflowInputParam("code", "string", true, null, null, null, null, null, null, "^\\d{6}$", null)
        );

        var result = InputValidator.validate(paramDefs, Map.of("code", "abc"));

        assertFalse(result.valid());
        assertEquals(1, result.validationErrors().size());
        assertTrue(result.validationErrors().getFirst().contains("code"));
    }

    @Test
    void P5_非字符串值_跳过正则校验() {
        var paramDefs = Map.of(
                "count", new WorkflowInputParam("count", "number", true, null, null, null, null, null, null, "^\\d+$", "必须为数字")
        );

        var result = InputValidator.validate(paramDefs, Map.of("count", 42));

        assertTrue(result.valid());
        assertTrue(result.validationErrors().isEmpty());
    }

    @Test
    void P5_validationPattern为null_跳过正则校验() {
        var paramDefs = Map.of(
                "name", new WorkflowInputParam("name", "string", true, null, null, null, null, null, null, null, null)
        );

        var result = InputValidator.validate(paramDefs, Map.of("name", "任意值"));

        assertTrue(result.valid());
        assertTrue(result.validationErrors().isEmpty());
    }

    @Test
    void P5_缺少必填参数且正则校验失败_两种错误同时返回() {
        var paramDefs = Map.of(
                "topic", new WorkflowInputParam("topic", "string", true, null, null, null, null, null, null, null, null),
                "email", new WorkflowInputParam("email", "string", true, null, null, null, null, null, null, "^[\\w.]+@[\\w.]+$", "邮箱格式不正确")
        );
        var userInputs = new HashMap<String, Object>();
        userInputs.put("email", "bad-email");

        var result = InputValidator.validate(paramDefs, userInputs);

        assertFalse(result.valid());
        assertEquals(1, result.missingParams().size());
        assertTrue(result.missingParams().contains("topic"));
        assertEquals(1, result.validationErrors().size());
        assertEquals("邮箱格式不正确", result.validationErrors().getFirst());
    }

    @Test
    void P5_默认值也参与正则校验() {
        var paramDefs = Map.of(
                "code", new WorkflowInputParam("code", "string", false, "invalid", null, null, null, null, null, "^\\d{6}$", "验证码必须为6位数字")
        );

        var result = InputValidator.validate(paramDefs, Map.of());

        assertFalse(result.valid());
        assertEquals("验证码必须为6位数字", result.validationErrors().getFirst());
    }

    // ── 综合场景 ──────────────────────────────────────────

    @Test
    void 混合场景_必填加可选加多余() {
        var paramDefs = Map.of(
                "topic", new WorkflowInputParam("topic", "string", true, null, null, null, null, null, null, null, null),
                "depth", new WorkflowInputParam("depth", "string", false, "standard", null, null, null, null, null, null, null),
                "format", new WorkflowInputParam("format", "string", true, null, null, null, null, null, null, null, null)
        );
        var userInputs = new HashMap<String, Object>();
        userInputs.put("topic", "量子计算");
        userInputs.put("extra", "bonus");

        var result = InputValidator.validate(paramDefs, userInputs);

        assertFalse(result.valid());
        assertEquals(1, result.missingParams().size());
        assertTrue(result.missingParams().contains("format"));
        assertEquals("量子计算", result.mergedInputs().get("topic"));
        assertEquals("standard", result.mergedInputs().get("depth"));
        assertEquals("bonus", result.mergedInputs().get("extra"));
    }
}
