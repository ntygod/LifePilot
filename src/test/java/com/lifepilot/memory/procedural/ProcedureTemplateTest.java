package com.lifepilot.memory.procedural;

import com.lifepilot.memory.store.procedural.ProcedureTemplate;
import com.lifepilot.memory.store.procedural.TemplateStep;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProcedureTemplate record 单元测试。
 *
 * @author zsg
 * @since 2026-03-01
 */
class ProcedureTemplateTest {

    private ProcedureTemplate createTemplate(float successRate, int useCount, Instant lastUsedAt) {
        return new ProcedureTemplate(
                "tpl-001", "测试模板", "测试描述", "安排会议",
                List.of(), Map.of(), successRate, useCount, lastUsedAt,
                List.of("trace-1"), Instant.now(), Instant.now(),
                null, null);
    }

    @Test
    void isReliable_成功率和使用次数均达标_返回true() {
        var tpl = createTemplate(0.8f, 3, Instant.now());
        assertTrue(tpl.isReliable(0.7f, 2));
    }

    @Test
    void isReliable_成功率不足_返回false() {
        var tpl = createTemplate(0.5f, 5, Instant.now());
        assertFalse(tpl.isReliable(0.7f, 2));
    }

    @Test
    void isReliable_使用次数不足_返回false() {
        var tpl = createTemplate(0.9f, 1, Instant.now());
        assertFalse(tpl.isReliable(0.7f, 2));
    }

    @Test
    void isReliable_边界值_恰好等于阈值_返回true() {
        var tpl = createTemplate(0.7f, 2, Instant.now());
        assertTrue(tpl.isReliable(0.7f, 2));
    }

    @Test
    void isStale_超过过时天数_返回true() {
        var oldTime = Instant.now().minus(Duration.ofDays(100));
        var tpl = createTemplate(0.8f, 3, oldTime);
        assertTrue(tpl.isStale(90));
    }

    @Test
    void isStale_未超过过时天数_返回false() {
        var recentTime = Instant.now().minus(Duration.ofDays(10));
        var tpl = createTemplate(0.8f, 3, recentTime);
        assertFalse(tpl.isStale(90));
    }

    @Test
    void isStale_lastUsedAt为null_返回false() {
        var tpl = createTemplate(0.8f, 3, null);
        assertFalse(tpl.isStale(90));
    }

    @Test
    void compactConstructor_集合字段不可变() {
        var step = new TemplateStep(1, "tool-1", "execute", Map.of(), "步骤", false);
        var steps = new ArrayList<>(List.of(step));
        var variables = new HashMap<String, String>();
        variables.put("name", "张三");
        var sourceTraceIds = new ArrayList<>(List.of("trace-1"));

        var tpl = new ProcedureTemplate(
                "tpl-002", "模板", "描述", "意图",
                steps, variables, 0.5f, 1, null, sourceTraceIds,
                Instant.now(), Instant.now(),
                null, null);

        steps.clear();
        variables.clear();
        sourceTraceIds.clear();

        assertEquals(1, tpl.steps().size());
        assertEquals("张三", tpl.variables().get("name"));
        assertEquals(List.of("trace-1"), tpl.sourceTraceIds());
        assertThrows(UnsupportedOperationException.class, () -> tpl.steps().add(step));
        assertThrows(UnsupportedOperationException.class, () -> tpl.variables().put("new", "value"));
        assertThrows(UnsupportedOperationException.class, () -> tpl.sourceTraceIds().add("trace-2"));
    }
}
