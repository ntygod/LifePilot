package com.lifepilot.workflow.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkflowContext 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class WorkflowContextTest {

    @Test
    void get_单层路径_返回对应值() {
        var ctx = new WorkflowContext();
        ctx.set("name", "test");
        assertEquals("test", ctx.get("name").orElse(null));
    }

    @Test
    void get_嵌套路径_返回深层值() {
        var ctx = new WorkflowContext();
        ctx.set("steps.step1.output.result", "hello");
        assertEquals("hello", ctx.get("steps.step1.output.result").orElse(null));
    }

    @Test
    void get_不存在的路径_返回empty() {
        var ctx = new WorkflowContext();
        assertTrue(ctx.get("nonexistent.path").isEmpty());
    }

    @Test
    void get_null路径_返回empty() {
        var ctx = new WorkflowContext();
        assertTrue(ctx.get(null).isEmpty());
    }

    @Test
    void get_空白路径_返回empty() {
        var ctx = new WorkflowContext();
        assertTrue(ctx.get("  ").isEmpty());
    }

    @Test
    void set_null路径_不抛异常() {
        var ctx = new WorkflowContext();
        assertDoesNotThrow(() -> ctx.set(null, "value"));
    }

    @Test
    void set_空白路径_不抛异常() {
        var ctx = new WorkflowContext();
        assertDoesNotThrow(() -> ctx.set("  ", "value"));
    }

    @Test
    void set_覆盖已有值() {
        var ctx = new WorkflowContext();
        ctx.set("key", "old");
        ctx.set("key", "new");
        assertEquals("new", ctx.get("key").orElse(null));
    }

    @Test
    void set_中间路径非Map时_自动创建新Map() {
        var ctx = new WorkflowContext();
        ctx.set("a", "scalar");
        ctx.set("a.b", "nested");
        assertEquals("nested", ctx.get("a.b").orElse(null));
    }

    @Test
    void getData_返回不可变副本() {
        var ctx = new WorkflowContext();
        ctx.set("key", "value");
        var data = ctx.getData();
        assertThrows(UnsupportedOperationException.class, () -> data.put("new", "val"));
    }

    @Test
    void getData_包含所有设置的值() {
        var ctx = new WorkflowContext();
        ctx.set("a", 1);
        ctx.set("b", "two");
        var data = ctx.getData();
        assertEquals(1, data.get("a"));
        assertEquals("two", data.get("b"));
    }

    @Test
    void toJson_序列化为有效JSON() {
        var ctx = new WorkflowContext();
        ctx.set("name", "test");
        ctx.set("count", 42);
        String json = ctx.toJson();
        assertNotNull(json);
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("\"test\""));
        assertTrue(json.contains("42"));
    }

    @Test
    void toJson_空上下文_返回空对象() {
        var ctx = new WorkflowContext();
        assertEquals("{}", ctx.toJson());
    }

    @Test
    void fromJson_反序列化恢复数据() {
        var ctx = new WorkflowContext();
        ctx.set("inputs.userId", "u123");
        ctx.set("steps.fetch.output.count", 5);
        String json = ctx.toJson();

        var restored = WorkflowContext.fromJson(json);
        assertEquals("u123", restored.get("inputs.userId").orElse(null));
        assertEquals(5, restored.get("steps.fetch.output.count").orElse(null));
    }

    @Test
    void fromJson_null输入_抛出IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowContext.fromJson(null));
    }

    @Test
    void fromJson_空白输入_抛出IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowContext.fromJson("  "));
    }

    @Test
    void fromJson_无效JSON_抛出IllegalStateException() {
        assertThrows(IllegalStateException.class, () -> WorkflowContext.fromJson("{invalid}"));
    }

    @Test
    void toJson_fromJson_roundTrip_嵌套结构() {
        var ctx = new WorkflowContext();
        ctx.set("steps.step1.output.items", List.of("a", "b", "c"));
        ctx.set("inputs.name", "workflow1");
        ctx.set("count", 100);

        String json = ctx.toJson();
        var restored = WorkflowContext.fromJson(json);

        assertEquals(ctx.get("inputs.name").orElse(null),
                restored.get("inputs.name").orElse(null));
        assertEquals(ctx.get("count").orElse(null),
                restored.get("count").orElse(null));
        // List 经过 JSON round-trip 后仍然可访问
        assertTrue(restored.get("steps.step1.output.items").isPresent());
    }

    @Test
    void loopVar绑定_通过set设置循环变量和索引() {
        var ctx = new WorkflowContext();
        var items = List.of("apple", "banana", "cherry");

        // 模拟 LoopStep 第 1 次迭代（index=0）
        ctx.set("item", items.get(0));
        ctx.set("item_index", 0);
        assertEquals("apple", ctx.get("item").orElse(null));
        assertEquals(0, ctx.get("item_index").orElse(null));

        // 模拟 LoopStep 第 2 次迭代（index=1）
        ctx.set("item", items.get(1));
        ctx.set("item_index", 1);
        assertEquals("banana", ctx.get("item").orElse(null));
        assertEquals(1, ctx.get("item_index").orElse(null));
    }

    @Test
    void 构造函数_防御性拷贝() {
        var original = new java.util.HashMap<String, Object>();
        original.put("key", "value");
        var ctx = new WorkflowContext(original);

        // 修改原始 Map 不影响 context
        original.put("key", "modified");
        assertEquals("value", ctx.get("key").orElse(null));
    }
}
