package com.lifepilot.sync.connector.todoist;

import com.lifepilot.skill.builtin.todo.TodoItem;
import com.lifepilot.sync.mapping.FieldMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TodoistFieldMapping 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class TodoistFieldMappingTest {

    private FieldMapping<TodoItem, Map<String, Object>> mapping;

    @BeforeEach
    void setUp() {
        mapping = TodoistFieldMapping.taskMapping();
    }

    // ==================== 优先级映射测试 ====================

    @Test
    void 优先级映射_HIGH转为4() {
        assertEquals(4, TodoistFieldMapping.priorityToTodoist(TodoItem.Priority.HIGH));
    }

    @Test
    void 优先级映射_MEDIUM转为3() {
        assertEquals(3, TodoistFieldMapping.priorityToTodoist(TodoItem.Priority.MEDIUM));
    }

    @Test
    void 优先级映射_LOW转为2() {
        assertEquals(2, TodoistFieldMapping.priorityToTodoist(TodoItem.Priority.LOW));
    }

    @Test
    void 优先级映射_null转为1() {
        assertEquals(1, TodoistFieldMapping.priorityToTodoist(null));
    }

    // ==================== 反向优先级映射测试 ====================

    @Test
    void 反向优先级_4转为HIGH() {
        assertEquals(TodoItem.Priority.HIGH, TodoistFieldMapping.priorityFromTodoist(4));
    }

    @Test
    void 反向优先级_3转为MEDIUM() {
        assertEquals(TodoItem.Priority.MEDIUM, TodoistFieldMapping.priorityFromTodoist(3));
    }

    @Test
    void 反向优先级_2转为LOW() {
        assertEquals(TodoItem.Priority.LOW, TodoistFieldMapping.priorityFromTodoist(2));
    }

    @Test
    void 反向优先级_1转为LOW() {
        assertEquals(TodoItem.Priority.LOW, TodoistFieldMapping.priorityFromTodoist(1));
    }

    // ==================== due date 嵌套 Map 测试 ====================

    @Test
    void dueDate转Todoist_正常日期() {
        var result = TodoistFieldMapping.dueDateToTodoist("2026-01-15");
        assertNotNull(result);
        assertEquals("2026-01-15", result.get("date"));
    }

    @Test
    void dueDate转Todoist_null返回null() {
        assertNull(TodoistFieldMapping.dueDateToTodoist(null));
    }

    @Test
    void dueDate转Todoist_空字符串返回null() {
        assertNull(TodoistFieldMapping.dueDateToTodoist(""));
    }

    @Test
    void dueDate从Todoist提取_嵌套Map() {
        Map<String, Object> dueMap = Map.of("date", "2026-03-01");
        assertEquals("2026-03-01", TodoistFieldMapping.dueDateFromTodoist(dueMap));
    }

    @Test
    void dueDate从Todoist提取_null返回null() {
        assertNull(TodoistFieldMapping.dueDateFromTodoist(null));
    }

    @Test
    void dueDate从Todoist提取_非Map返回null() {
        assertNull(TodoistFieldMapping.dueDateFromTodoist("not-a-map"));
    }

    // ==================== 状态映射测试 ====================

    @Test
    void 状态映射_COMPLETED转为checked_true() {
        assertTrue(TodoistFieldMapping.statusToChecked(TodoItem.Status.COMPLETED));
    }

    @Test
    void 状态映射_PENDING转为checked_false() {
        assertFalse(TodoistFieldMapping.statusToChecked(TodoItem.Status.PENDING));
    }

    @Test
    void 状态映射_IN_PROGRESS转为checked_false() {
        assertFalse(TodoistFieldMapping.statusToChecked(TodoItem.Status.IN_PROGRESS));
    }

    @Test
    void 反向状态_checked_true转为COMPLETED() {
        assertEquals(TodoItem.Status.COMPLETED, TodoistFieldMapping.statusFromChecked(true));
    }

    @Test
    void 反向状态_checked_false转为PENDING() {
        assertEquals(TodoItem.Status.PENDING, TodoistFieldMapping.statusFromChecked(false));
    }

    // ==================== tags/labels 映射测试 ====================

    @Test
    void labels提取_正常列表() {
        var labels = List.of("work", "urgent");
        assertEquals(List.of("work", "urgent"), TodoistFieldMapping.extractLabels(labels));
    }

    @Test
    void labels提取_null返回空列表() {
        assertEquals(List.of(), TodoistFieldMapping.extractLabels(null));
    }

    @Test
    void labels提取_非List返回空列表() {
        assertEquals(List.of(), TodoistFieldMapping.extractLabels("not-a-list"));
    }

    // ==================== toRemote 完整转换测试 ====================

    @Test
    void toRemote_完整TodoItem转换() {
        var now = Instant.now().toString();
        var item = new TodoItem("id-1", "买牛奶", "去超市买", TodoItem.Priority.HIGH,
                TodoItem.Status.PENDING, "2026-03-01", List.of("购物", "日常"), now, now);

        var remote = mapping.toRemote(item);

        assertEquals("id-1", remote.get("id"));
        assertEquals("买牛奶", remote.get("content"));
        assertEquals("去超市买", remote.get("description"));
        assertEquals(4, remote.get("priority"));
        assertEquals(false, remote.get("checked"));
        @SuppressWarnings("unchecked")
        var due = (Map<String, Object>) remote.get("due");
        assertEquals("2026-03-01", due.get("date"));
        assertEquals(List.of("购物", "日常"), remote.get("labels"));
    }

    @Test
    void toRemote_null字段处理() {
        var now = Instant.now().toString();
        var item = new TodoItem("id-2", "简单任务", null, TodoItem.Priority.LOW,
                TodoItem.Status.COMPLETED, null, null, now, now);

        var remote = mapping.toRemote(item);

        assertEquals("简单任务", remote.get("content"));
        assertEquals("", remote.get("description"));
        assertEquals(2, remote.get("priority"));
        assertEquals(true, remote.get("checked"));
        assertFalse(remote.containsKey("due"));
        assertEquals(List.of(), remote.get("labels"));
    }

    // ==================== toLocal 完整转换测试 ====================

    @Test
    void toLocal_完整远程数据转换() {
        var remote = new HashMap<String, Object>();
        remote.put("id", "remote-1");
        remote.put("content", "写报告");
        remote.put("description", "季度报告");
        remote.put("priority", 3);
        remote.put("checked", false);
        remote.put("due", Map.of("date", "2026-04-01"));
        remote.put("labels", List.of("工作"));

        var local = mapping.toLocal(remote);

        assertEquals("remote-1", local.id());
        assertEquals("写报告", local.title());
        assertEquals("季度报告", local.description());
        assertEquals(TodoItem.Priority.MEDIUM, local.priority());
        assertEquals(TodoItem.Status.PENDING, local.status());
        assertEquals("2026-04-01", local.dueDate());
        assertEquals(List.of("工作"), local.tags());
    }

    @Test
    void toLocal_null和缺失字段处理() {
        var remote = new HashMap<String, Object>();
        remote.put("id", "remote-2");
        remote.put("content", "快速任务");

        var local = mapping.toLocal(remote);

        assertEquals("remote-2", local.id());
        assertEquals("快速任务", local.title());
        assertNull(local.description());
        assertEquals(TodoItem.Priority.LOW, local.priority());
        assertEquals(TodoItem.Status.PENDING, local.status());
        assertNull(local.dueDate());
        assertNull(local.tags());
    }

    @Test
    void toLocal_空描述转为null() {
        var remote = new HashMap<String, Object>();
        remote.put("id", "remote-3");
        remote.put("content", "任务");
        remote.put("description", "  ");

        var local = mapping.toLocal(remote);
        assertNull(local.description());
    }

    @Test
    void toLocal_checked_true转为COMPLETED() {
        var remote = new HashMap<String, Object>();
        remote.put("id", "remote-4");
        remote.put("content", "已完成任务");
        remote.put("checked", true);

        var local = mapping.toLocal(remote);
        assertEquals(TodoItem.Status.COMPLETED, local.status());
    }

    // ==================== extractConflictFields 测试 ====================

    @Test
    void extractConflictFields_有差异时返回差异字段() {
        var now = Instant.now().toString();
        var local = new TodoItem("id-1", "本地标题", "本地描述", TodoItem.Priority.HIGH,
                TodoItem.Status.PENDING, "2026-03-01", List.of("标签A"), now, now);

        var remote = new HashMap<String, Object>();
        remote.put("id", "id-1");
        remote.put("content", "远程标题");
        remote.put("description", "远程描述");
        remote.put("priority", 2);
        remote.put("checked", false);
        remote.put("due", Map.of("date", "2026-04-01"));
        remote.put("labels", List.of("标签B"));

        var diffs = mapping.extractConflictFields(local, remote);

        assertTrue(diffs.containsKey("title"));
        assertTrue(diffs.containsKey("description"));
        assertTrue(diffs.containsKey("priority"));
        assertTrue(diffs.containsKey("dueDate"));
        assertTrue(diffs.containsKey("tags"));
        assertEquals(5, diffs.size());
    }

    @Test
    void extractConflictFields_无差异时返回空Map() {
        var now = Instant.now().toString();
        var local = new TodoItem("id-1", "相同标题", null, TodoItem.Priority.HIGH,
                TodoItem.Status.PENDING, "2026-03-01", List.of("标签"), now, now);

        var remote = new HashMap<String, Object>();
        remote.put("id", "id-1");
        remote.put("content", "相同标题");
        remote.put("priority", 4);
        remote.put("checked", false);
        remote.put("due", Map.of("date", "2026-03-01"));
        remote.put("labels", List.of("标签"));

        var diffs = mapping.extractConflictFields(local, remote);
        assertTrue(diffs.isEmpty());
    }

    // ==================== 往返一致性测试 ====================

    @Test
    void 往返一致性_关键字段保持不变() {
        var now = Instant.now().toString();
        var original = new TodoItem("id-rt", "往返测试", "描述文本", TodoItem.Priority.MEDIUM,
                TodoItem.Status.PENDING, "2026-06-15", List.of("测试", "往返"), now, now);

        var remote = mapping.toRemote(original);
        var roundTripped = mapping.toLocal(remote);

        assertEquals(original.id(), roundTripped.id());
        assertEquals(original.title(), roundTripped.title());
        assertEquals(original.description(), roundTripped.description());
        assertEquals(original.priority(), roundTripped.priority());
        assertEquals(original.dueDate(), roundTripped.dueDate());
        assertEquals(original.tags(), roundTripped.tags());
    }

    @Test
    void 往返一致性_所有优先级保持不变() {
        var now = Instant.now().toString();
        for (var priority : TodoItem.Priority.values()) {
            var item = new TodoItem("id-p", "优先级测试", null, priority,
                    TodoItem.Status.PENDING, null, null, now, now);
            var remote = mapping.toRemote(item);
            var roundTripped = mapping.toLocal(remote);
            assertEquals(priority, roundTripped.priority(),
                    "优先级 " + priority + " 往返后应保持不变");
        }
    }
}
