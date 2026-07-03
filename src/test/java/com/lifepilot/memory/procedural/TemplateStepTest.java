package com.lifepilot.memory.procedural;

import com.lifepilot.memory.store.procedural.TemplateStep;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TemplateStep record 单元测试。
 *
 * @author zsg
 * @since 2026-03-01
 */
class TemplateStepTest {

    private TemplateStep createStep(Map<String, String> parameterTemplate) {
        return new TemplateStep(1, "tool-1", "execute", parameterTemplate, "测试步骤", false);
    }

    @Test
    void resolveParameters_替换单个占位符() {
        var step = createStep(Map.of("title", "${meetingTitle}"));
        var result = step.resolveParameters(Map.of("meetingTitle", "周会"));
        assertEquals("周会", result.get("title"));
    }

    @Test
    void resolveParameters_替换多个不同占位符() {
        var step = createStep(Map.of(
                "title", "${meetingTitle}",
                "time", "${startTime}"
        ));
        var result = step.resolveParameters(Map.of(
                "meetingTitle", "周会",
                "startTime", "14:00"
        ));
        assertEquals("周会", result.get("title"));
        assertEquals("14:00", result.get("time"));
    }

    @Test
    void resolveParameters_同一值中多个占位符() {
        var step = createStep(Map.of("desc", "${name}在${time}开会"));
        var result = step.resolveParameters(Map.of("name", "张三", "time", "下午三点"));
        assertEquals("张三在下午三点开会", result.get("desc"));
    }

    @Test
    void resolveParameters_未定义变量保持原样() {
        var step = createStep(Map.of("title", "${undefined}"));
        var result = step.resolveParameters(Map.of("other", "value"));
        assertEquals("${undefined}", result.get("title"));
    }

    @Test
    void resolveParameters_部分变量已定义_部分未定义() {
        var step = createStep(Map.of("desc", "${name}在${unknown}开会"));
        var result = step.resolveParameters(Map.of("name", "李四"));
        assertEquals("李四在${unknown}开会", result.get("desc"));
    }

    @Test
    void resolveParameters_无占位符的值不受影响() {
        var step = createStep(Map.of("fixed", "固定值"));
        var result = step.resolveParameters(Map.of("name", "张三"));
        assertEquals("固定值", result.get("fixed"));
    }

    @Test
    void resolveParameters_空变量映射_返回原始模板() {
        var step = createStep(Map.of("title", "${name}"));
        var result = step.resolveParameters(Map.of());
        assertEquals("${name}", result.get("title"));
    }

    @Test
    void resolveParameters_null变量映射_返回原始模板() {
        var step = createStep(Map.of("title", "${name}"));
        var result = step.resolveParameters(null);
        assertEquals("${name}", result.get("title"));
    }

    @Test
    void resolveParameters_返回不可变Map() {
        var step = createStep(Map.of("title", "${name}"));
        var result = step.resolveParameters(Map.of("name", "测试"));
        assertThrows(UnsupportedOperationException.class, () -> result.put("new", "value"));
    }

    @Test
    void compactConstructor_parameterTemplate不可变() {
        var parameterTemplate = new HashMap<String, String>();
        parameterTemplate.put("title", "${name}");

        var step = new TemplateStep(1, "tool-1", "execute", parameterTemplate, "描述", false);
        parameterTemplate.clear();

        assertEquals("${name}", step.parameterTemplate().get("title"));
        assertThrows(UnsupportedOperationException.class,
                () -> step.parameterTemplate().put("new", "value"));
    }
}
