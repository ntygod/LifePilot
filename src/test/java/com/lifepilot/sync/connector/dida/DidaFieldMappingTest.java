package com.lifepilot.sync.connector.dida;

import com.lifepilot.skill.builtin.habit.HabitItem;
import com.lifepilot.skill.builtin.todo.TodoItem;
import com.lifepilot.sync.mapping.FieldMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DidaFieldMapping 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class DidaFieldMappingTest {

    // ==================== 任务映射测试 ====================

    @Nested
    class 任务映射 {

        private FieldMapping<TodoItem, Map<String, Object>> mapping;

        @BeforeEach
        void setUp() {
            mapping = DidaFieldMapping.taskMapping();
        }

        // ---- 优先级映射 ----

        @Test
        void 优先级映射_HIGH转为5() {
            assertEquals(5, DidaFieldMapping.priorityToDida(TodoItem.Priority.HIGH));
        }

        @Test
        void 优先级映射_MEDIUM转为3() {
            assertEquals(3, DidaFieldMapping.priorityToDida(TodoItem.Priority.MEDIUM));
        }

        @Test
        void 优先级映射_LOW转为1() {
            assertEquals(1, DidaFieldMapping.priorityToDida(TodoItem.Priority.LOW));
        }

        @Test
        void 优先级映射_null转为0() {
            assertEquals(0, DidaFieldMapping.priorityToDida(null));
        }

        // ---- 反向优先级映射 ----

        @Test
        void 反向优先级_5转为HIGH() {
            assertEquals(TodoItem.Priority.HIGH, DidaFieldMapping.priorityFromDida(5));
        }

        @Test
        void 反向优先级_3转为MEDIUM() {
            assertEquals(TodoItem.Priority.MEDIUM, DidaFieldMapping.priorityFromDida(3));
        }

        @Test
        void 反向优先级_1转为LOW() {
            assertEquals(TodoItem.Priority.LOW, DidaFieldMapping.priorityFromDida(1));
        }

        @Test
        void 反向优先级_0转为LOW() {
            assertEquals(TodoItem.Priority.LOW, DidaFieldMapping.priorityFromDida(0));
        }

        // ---- 状态映射 ----

        @Test
        void 状态映射_COMPLETED转为2() {
            assertEquals(2, DidaFieldMapping.statusToDida(TodoItem.Status.COMPLETED));
        }

        @Test
        void 状态映射_IN_PROGRESS转为1() {
            assertEquals(1, DidaFieldMapping.statusToDida(TodoItem.Status.IN_PROGRESS));
        }

        @Test
        void 状态映射_PENDING转为0() {
            assertEquals(0, DidaFieldMapping.statusToDida(TodoItem.Status.PENDING));
        }

        // ---- 反向状态映射 ----

        @Test
        void 反向状态_2转为COMPLETED() {
            assertEquals(TodoItem.Status.COMPLETED, DidaFieldMapping.statusFromDida(2));
        }

        @Test
        void 反向状态_1转为IN_PROGRESS() {
            assertEquals(TodoItem.Status.IN_PROGRESS, DidaFieldMapping.statusFromDida(1));
        }

        @Test
        void 反向状态_0转为PENDING() {
            assertEquals(TodoItem.Status.PENDING, DidaFieldMapping.statusFromDida(0));
        }

        // ---- toRemote 完整转换 ----

        @Test
        void toRemote_完整TodoItem转换() {
            var now = Instant.now().toString();
            var item = new TodoItem("id-1", "买牛奶", "去超市买", TodoItem.Priority.HIGH,
                    TodoItem.Status.PENDING, "2026-03-01", List.of("购物", "日常"), now, now);

            var remote = mapping.toRemote(item);

            assertEquals("id-1", remote.get("id"));
            assertEquals("买牛奶", remote.get("title"));
            assertEquals("去超市买", remote.get("content"));
            assertEquals(5, remote.get("priority"));
            assertEquals(0, remote.get("status"));
            assertEquals("2026-03-01", remote.get("dueDate"));
            assertEquals(List.of("购物", "日常"), remote.get("tags"));
        }

        @Test
        void toRemote_null字段处理() {
            var now = Instant.now().toString();
            var item = new TodoItem("id-2", "简单任务", null, TodoItem.Priority.LOW,
                    TodoItem.Status.COMPLETED, null, null, now, now);

            var remote = mapping.toRemote(item);

            assertEquals("简单任务", remote.get("title"));
            assertEquals("", remote.get("content"));
            assertEquals(1, remote.get("priority"));
            assertEquals(2, remote.get("status"));
            assertFalse(remote.containsKey("dueDate"));
            assertEquals(List.of(), remote.get("tags"));
        }

        // ---- toLocal 完整转换 ----

        @Test
        void toLocal_完整远程数据转换() {
            var remote = new HashMap<String, Object>();
            remote.put("id", "remote-1");
            remote.put("title", "写报告");
            remote.put("content", "季度报告");
            remote.put("priority", 3);
            remote.put("status", 1);
            remote.put("dueDate", "2026-04-01");
            remote.put("tags", List.of("工作"));

            var local = mapping.toLocal(remote);

            assertEquals("remote-1", local.id());
            assertEquals("写报告", local.title());
            assertEquals("季度报告", local.description());
            assertEquals(TodoItem.Priority.MEDIUM, local.priority());
            assertEquals(TodoItem.Status.IN_PROGRESS, local.status());
            assertEquals("2026-04-01", local.dueDate());
            assertEquals(List.of("工作"), local.tags());
        }

        @Test
        void toLocal_null和缺失字段处理() {
            var remote = new HashMap<String, Object>();
            remote.put("id", "remote-2");
            remote.put("title", "快速任务");

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
        void toLocal_空content转为null() {
            var remote = new HashMap<String, Object>();
            remote.put("id", "remote-3");
            remote.put("title", "任务");
            remote.put("content", "  ");

            var local = mapping.toLocal(remote);
            assertNull(local.description());
        }

        @Test
        void toLocal_空tags转为null() {
            var remote = new HashMap<String, Object>();
            remote.put("id", "remote-4");
            remote.put("title", "任务");
            remote.put("tags", List.of());

            var local = mapping.toLocal(remote);
            assertNull(local.tags());
        }

        // ---- extractConflictFields ----

        @Test
        void extractConflictFields_有差异时返回差异字段() {
            var now = Instant.now().toString();
            var local = new TodoItem("id-1", "本地标题", "本地描述", TodoItem.Priority.HIGH,
                    TodoItem.Status.PENDING, "2026-03-01", List.of("标签A"), now, now);

            var remote = new HashMap<String, Object>();
            remote.put("id", "id-1");
            remote.put("title", "远程标题");
            remote.put("content", "远程描述");
            remote.put("priority", 1);
            remote.put("status", 2);
            remote.put("dueDate", "2026-04-01");
            remote.put("tags", List.of("标签B"));

            var diffs = mapping.extractConflictFields(local, remote);

            assertTrue(diffs.containsKey("title"));
            assertTrue(diffs.containsKey("description"));
            assertTrue(diffs.containsKey("priority"));
            assertTrue(diffs.containsKey("status"));
            assertTrue(diffs.containsKey("dueDate"));
            assertTrue(diffs.containsKey("tags"));
            assertEquals(6, diffs.size());
        }

        @Test
        void extractConflictFields_无差异时返回空Map() {
            var now = Instant.now().toString();
            var local = new TodoItem("id-1", "相同标题", null, TodoItem.Priority.HIGH,
                    TodoItem.Status.PENDING, "2026-03-01", List.of("标签"), now, now);

            var remote = new HashMap<String, Object>();
            remote.put("id", "id-1");
            remote.put("title", "相同标题");
            remote.put("priority", 5);
            remote.put("status", 0);
            remote.put("dueDate", "2026-03-01");
            remote.put("tags", List.of("标签"));

            var diffs = mapping.extractConflictFields(local, remote);
            assertTrue(diffs.isEmpty());
        }

        // ---- 往返一致性 ----

        @Test
        void 往返一致性_关键字段保持不变() {
            var now = Instant.now().toString();
            var original = new TodoItem("id-rt", "往返测试", "描述文本", TodoItem.Priority.MEDIUM,
                    TodoItem.Status.IN_PROGRESS, "2026-06-15", List.of("测试", "往返"), now, now);

            var remote = mapping.toRemote(original);
            var roundTripped = mapping.toLocal(remote);

            assertEquals(original.id(), roundTripped.id());
            assertEquals(original.title(), roundTripped.title());
            assertEquals(original.description(), roundTripped.description());
            assertEquals(original.priority(), roundTripped.priority());
            assertEquals(original.status(), roundTripped.status());
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

        @Test
        void 往返一致性_所有状态保持不变() {
            var now = Instant.now().toString();
            for (var status : TodoItem.Status.values()) {
                var item = new TodoItem("id-s", "状态测试", null, TodoItem.Priority.LOW,
                        status, null, null, now, now);
                var remote = mapping.toRemote(item);
                var roundTripped = mapping.toLocal(remote);
                assertEquals(status, roundTripped.status(),
                        "状态 " + status + " 往返后应保持不变");
            }
        }
    }

    // ==================== 习惯映射测试 ====================

    @Nested
    class 习惯映射 {

        private FieldMapping<HabitItem, Map<String, Object>> mapping;

        @BeforeEach
        void setUp() {
            mapping = DidaFieldMapping.habitMapping();
        }

        // ---- 频率映射 ----

        @Test
        void 频率映射_DAILY转为RRULE() {
            assertEquals("RRULE:FREQ=DAILY", DidaFieldMapping.frequencyToRrule(HabitItem.Frequency.DAILY));
        }

        @Test
        void 频率映射_WEEKLY转为RRULE() {
            assertEquals("RRULE:FREQ=WEEKLY", DidaFieldMapping.frequencyToRrule(HabitItem.Frequency.WEEKLY));
        }

        // ---- 反向频率映射 ----

        @Test
        void 反向频率_DAILY_RRULE转为DAILY() {
            assertEquals(HabitItem.Frequency.DAILY, DidaFieldMapping.frequencyFromRrule("RRULE:FREQ=DAILY"));
        }

        @Test
        void 反向频率_WEEKLY_RRULE转为WEEKLY() {
            assertEquals(HabitItem.Frequency.WEEKLY, DidaFieldMapping.frequencyFromRrule("RRULE:FREQ=WEEKLY"));
        }

        @Test
        void 反向频率_null默认DAILY() {
            assertEquals(HabitItem.Frequency.DAILY, DidaFieldMapping.frequencyFromRrule(null));
        }

        @Test
        void 反向频率_无法识别默认DAILY() {
            assertEquals(HabitItem.Frequency.DAILY, DidaFieldMapping.frequencyFromRrule("RRULE:FREQ=MONTHLY"));
        }

        // ---- toRemote 完整转换 ----

        @Test
        void toRemote_完整HabitItem转换() {
            var now = Instant.now().toString();
            var item = new HabitItem("h-1", "晨跑", HabitItem.Frequency.DAILY, "07:00", 15, now, now);

            var remote = mapping.toRemote(item);

            assertEquals("h-1", remote.get("id"));
            assertEquals("晨跑", remote.get("name"));
            assertEquals("RRULE:FREQ=DAILY", remote.get("repeatRule"));
            assertEquals("07:00", remote.get("targetTime"));
        }

        @Test
        void toRemote_null_targetTime不包含在结果中() {
            var now = Instant.now().toString();
            var item = new HabitItem("h-2", "阅读", HabitItem.Frequency.WEEKLY, null, 3, now, now);

            var remote = mapping.toRemote(item);

            assertEquals("阅读", remote.get("name"));
            assertEquals("RRULE:FREQ=WEEKLY", remote.get("repeatRule"));
            assertFalse(remote.containsKey("targetTime"));
        }

        @Test
        void toRemote_currentStreak不映射到远程() {
            var now = Instant.now().toString();
            var item = new HabitItem("h-3", "冥想", HabitItem.Frequency.DAILY, "08:00", 30, now, now);

            var remote = mapping.toRemote(item);

            assertFalse(remote.containsKey("currentStreak"));
            assertFalse(remote.containsKey("completedCount"));
        }

        // ---- toLocal 完整转换 ----

        @Test
        void toLocal_完整远程数据转换() {
            var remote = new HashMap<String, Object>();
            remote.put("id", "rh-1");
            remote.put("name", "早起");
            remote.put("repeatRule", "RRULE:FREQ=DAILY");
            remote.put("targetTime", "06:30");
            remote.put("completedCount", 10);

            var local = mapping.toLocal(remote);

            assertEquals("rh-1", local.id());
            assertEquals("早起", local.name());
            assertEquals(HabitItem.Frequency.DAILY, local.frequency());
            assertEquals("06:30", local.targetTime());
            assertEquals(10, local.currentStreak());
        }

        @Test
        void toLocal_缺失字段处理() {
            var remote = new HashMap<String, Object>();
            remote.put("id", "rh-2");
            remote.put("name", "喝水");

            var local = mapping.toLocal(remote);

            assertEquals("rh-2", local.id());
            assertEquals("喝水", local.name());
            assertEquals(HabitItem.Frequency.DAILY, local.frequency());
            assertNull(local.targetTime());
            assertEquals(0, local.currentStreak());
        }

        @Test
        void toLocal_空targetTime转为null() {
            var remote = new HashMap<String, Object>();
            remote.put("id", "rh-3");
            remote.put("name", "习惯");
            remote.put("targetTime", "  ");

            var local = mapping.toLocal(remote);
            assertNull(local.targetTime());
        }

        // ---- extractConflictFields ----

        @Test
        void extractConflictFields_有差异时返回差异字段() {
            var now = Instant.now().toString();
            var local = new HabitItem("h-1", "本地习惯", HabitItem.Frequency.DAILY, "07:00", 5, now, now);

            var remote = new HashMap<String, Object>();
            remote.put("id", "h-1");
            remote.put("name", "远程习惯");
            remote.put("repeatRule", "RRULE:FREQ=WEEKLY");
            remote.put("targetTime", "08:00");

            var diffs = mapping.extractConflictFields(local, remote);

            assertTrue(diffs.containsKey("name"));
            assertTrue(diffs.containsKey("frequency"));
            assertTrue(diffs.containsKey("targetTime"));
            assertEquals(3, diffs.size());
        }

        @Test
        void extractConflictFields_无差异时返回空Map() {
            var now = Instant.now().toString();
            var local = new HabitItem("h-1", "相同习惯", HabitItem.Frequency.DAILY, "07:00", 5, now, now);

            var remote = new HashMap<String, Object>();
            remote.put("id", "h-1");
            remote.put("name", "相同习惯");
            remote.put("repeatRule", "RRULE:FREQ=DAILY");
            remote.put("targetTime", "07:00");

            var diffs = mapping.extractConflictFields(local, remote);
            assertTrue(diffs.isEmpty());
        }

        // ---- 往返一致性 ----

        @Test
        void 往返一致性_关键字段保持不变() {
            var now = Instant.now().toString();
            var original = new HabitItem("h-rt", "往返习惯", HabitItem.Frequency.WEEKLY, "09:00", 7, now, now);

            var remote = mapping.toRemote(original);
            var roundTripped = mapping.toLocal(remote);

            assertEquals(original.id(), roundTripped.id());
            assertEquals(original.name(), roundTripped.name());
            assertEquals(original.frequency(), roundTripped.frequency());
            assertEquals(original.targetTime(), roundTripped.targetTime());
        }

        @Test
        void 往返一致性_所有频率保持不变() {
            var now = Instant.now().toString();
            for (var freq : HabitItem.Frequency.values()) {
                var item = new HabitItem("h-f", "频率测试", freq, null, 0, now, now);
                var remote = mapping.toRemote(item);
                var roundTripped = mapping.toLocal(remote);
                assertEquals(freq, roundTripped.frequency(),
                        "频率 " + freq + " 往返后应保持不变");
            }
        }
    }
}
