package com.lifepilot.sync.connector.obsidian;

import com.lifepilot.skill.builtin.habit.HabitItem;
import com.lifepilot.skill.builtin.schedule.ScheduleItem;
import com.lifepilot.skill.builtin.todo.TodoItem;
import com.lifepilot.sync.mapping.FieldMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ObsidianFieldMapping 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class ObsidianFieldMappingTest {

    // ==================== TodoItem 映射测试 ====================

    @Nested
    class TodoMapping测试 {

        private FieldMapping<TodoItem, String> mapping;

        @BeforeEach
        void setUp() {
            mapping = ObsidianFieldMapping.todoMapping();
        }

        @Test
        void toRemote_完整字段_生成正确的Markdown() {
            var todo = new TodoItem(
                    "uuid-001", "买牛奶", "去超市买两盒牛奶",
                    TodoItem.Priority.HIGH, TodoItem.Status.PENDING,
                    "2026-03-01", List.of("购物", "日常"),
                    "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var markdown = mapping.toRemote(todo);

            assertTrue(markdown.contains("type: todo"));
            assertTrue(markdown.contains("title: 买牛奶"));
            assertTrue(markdown.contains("priority: HIGH"));
            assertTrue(markdown.contains("status: PENDING"));
            assertTrue(markdown.contains("dueDate:"));
            assertTrue(markdown.contains("2026-03-01"));
            assertTrue(markdown.contains("tags: [购物, 日常]"));
            assertTrue(markdown.contains("zhiwei_id:"));
            assertTrue(markdown.contains("uuid-001"));
            assertTrue(markdown.contains("去超市买两盒牛奶"));
        }

        @Test
        void toRemote_toLocal_往返转换_关键字段一致() {
            var todo = new TodoItem(
                    "uuid-002", "写报告", "季度总结报告",
                    TodoItem.Priority.MEDIUM, TodoItem.Status.IN_PROGRESS,
                    "2026-04-01", List.of("工作"),
                    "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var markdown = mapping.toRemote(todo);
            var restored = mapping.toLocal(markdown);

            assertEquals(todo.id(), restored.id());
            assertEquals(todo.title(), restored.title());
            assertEquals(todo.description(), restored.description());
            assertEquals(todo.priority(), restored.priority());
            assertEquals(todo.status(), restored.status());
            assertEquals(todo.dueDate(), restored.dueDate());
            assertEquals(todo.tags(), restored.tags());
        }

        @Test
        void toLocal_缺失字段_使用默认值() {
            var markdown = """
                    ---
                    type: todo
                    title: 简单任务
                    zhiwei_id: "uuid-003"
                    ---
                    """;

            var todo = mapping.toLocal(markdown);

            assertEquals("uuid-003", todo.id());
            assertEquals("简单任务", todo.title());
            assertNull(todo.description());
            assertEquals(TodoItem.Priority.LOW, todo.priority());
            assertEquals(TodoItem.Status.PENDING, todo.status());
            assertNull(todo.dueDate());
            assertNull(todo.tags());
        }

        @Test
        void toLocal_无zhiwei_id_自动生成UUID() {
            var markdown = """
                    ---
                    type: todo
                    title: 无ID任务
                    ---
                    """;

            var todo = mapping.toLocal(markdown);

            assertNotNull(todo.id());
            assertFalse(todo.id().isEmpty());
            assertEquals("无ID任务", todo.title());
        }

        @Test
        void toRemote_toLocal_空描述和空标签_正确处理() {
            var todo = new TodoItem(
                    "uuid-004", "无描述任务", null,
                    TodoItem.Priority.LOW, TodoItem.Status.COMPLETED,
                    null, null,
                    "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var markdown = mapping.toRemote(todo);
            var restored = mapping.toLocal(markdown);

            assertEquals(todo.id(), restored.id());
            assertEquals(todo.title(), restored.title());
            assertNull(restored.description());
            assertEquals(todo.priority(), restored.priority());
            assertEquals(todo.status(), restored.status());
            assertNull(restored.dueDate());
            assertNull(restored.tags());
        }

        @Test
        void extractConflictFields_有差异_返回差异字段() {
            var local = new TodoItem(
                    "uuid-005", "本地标题", "本地描述",
                    TodoItem.Priority.HIGH, TodoItem.Status.PENDING,
                    "2026-03-01", List.of("标签A"),
                    "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var remoteMarkdown = """
                    ---
                    type: todo
                    title: 远程标题
                    priority: LOW
                    status: COMPLETED
                    zhiwei_id: "uuid-005"
                    ---
                    远程描述
                    """;

            var diffs = mapping.extractConflictFields(local, remoteMarkdown);

            assertTrue(diffs.containsKey("title"));
            assertTrue(diffs.containsKey("description"));
            assertTrue(diffs.containsKey("priority"));
            assertTrue(diffs.containsKey("status"));
        }

        @Test
        void extractConflictFields_无差异_返回空Map() {
            var todo = new TodoItem(
                    "uuid-006", "相同标题", null,
                    TodoItem.Priority.MEDIUM, TodoItem.Status.PENDING,
                    null, null,
                    "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var markdown = mapping.toRemote(todo);
            var diffs = mapping.extractConflictFields(todo, markdown);

            assertTrue(diffs.isEmpty());
        }
    }

    // ==================== ScheduleItem 映射测试 ====================

    @Nested
    class ScheduleMapping测试 {

        private FieldMapping<ScheduleItem, String> mapping;

        @BeforeEach
        void setUp() {
            mapping = ObsidianFieldMapping.scheduleMapping();
        }

        @Test
        void toRemote_完整字段_生成正确的Markdown() {
            var schedule = new ScheduleItem(
                    "uuid-101", "团队会议", "2026-03-01T09:00:00Z", "2026-03-01T10:00:00Z",
                    "会议室A", "讨论Q1计划",
                    "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var markdown = mapping.toRemote(schedule);

            assertTrue(markdown.contains("type: schedule"));
            assertTrue(markdown.contains("title: 团队会议"));
            assertTrue(markdown.contains("startTime:"));
            assertTrue(markdown.contains("endTime:"));
            assertTrue(markdown.contains("location: 会议室A"));
            assertTrue(markdown.contains("zhiwei_id:"));
            assertTrue(markdown.contains("讨论Q1计划"));
        }

        @Test
        void toRemote_toLocal_往返转换_关键字段一致() {
            var schedule = new ScheduleItem(
                    "uuid-102", "午餐", "2026-03-01T12:00:00Z", "2026-03-01T13:00:00Z",
                    "食堂", "和同事一起",
                    "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var markdown = mapping.toRemote(schedule);
            var restored = mapping.toLocal(markdown);

            assertEquals(schedule.id(), restored.id());
            assertEquals(schedule.title(), restored.title());
            assertEquals(schedule.startTime(), restored.startTime());
            assertEquals(schedule.endTime(), restored.endTime());
            assertEquals(schedule.location(), restored.location());
            assertEquals(schedule.notes(), restored.notes());
        }

        @Test
        void toRemote_toLocal_无可选字段_正确处理() {
            var schedule = new ScheduleItem(
                    "uuid-103", "独立工作", "2026-03-01T14:00:00Z", "2026-03-01T16:00:00Z",
                    null, null,
                    "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var markdown = mapping.toRemote(schedule);
            var restored = mapping.toLocal(markdown);

            assertEquals(schedule.id(), restored.id());
            assertEquals(schedule.title(), restored.title());
            assertNull(restored.location());
            assertNull(restored.notes());
        }

        @Test
        void toLocal_缺失字段_使用默认值() {
            var markdown = """
                    ---
                    type: schedule
                    title: 简单日程
                    zhiwei_id: "uuid-104"
                    ---
                    """;

            var schedule = mapping.toLocal(markdown);

            assertEquals("uuid-104", schedule.id());
            assertEquals("简单日程", schedule.title());
            assertEquals("", schedule.startTime());
            assertEquals("", schedule.endTime());
            assertNull(schedule.location());
            assertNull(schedule.notes());
        }

        @Test
        void extractConflictFields_有差异_返回差异字段() {
            var local = new ScheduleItem(
                    "uuid-105", "本地会议", "2026-03-01T09:00:00Z", "2026-03-01T10:00:00Z",
                    "会议室A", "本地备注",
                    "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var remoteMarkdown = """
                    ---
                    type: schedule
                    title: 远程会议
                    startTime: "2026-03-01T10:00:00Z"
                    endTime: "2026-03-01T11:00:00Z"
                    location: 会议室B
                    zhiwei_id: "uuid-105"
                    ---
                    远程备注
                    """;

            var diffs = mapping.extractConflictFields(local, remoteMarkdown);

            assertTrue(diffs.containsKey("title"));
            assertTrue(diffs.containsKey("startTime"));
            assertTrue(diffs.containsKey("endTime"));
            assertTrue(diffs.containsKey("location"));
            assertTrue(diffs.containsKey("notes"));
        }
    }

    // ==================== HabitItem 映射测试 ====================

    @Nested
    class HabitMapping测试 {

        private FieldMapping<HabitItem, String> mapping;

        @BeforeEach
        void setUp() {
            mapping = ObsidianFieldMapping.habitMapping();
        }

        @Test
        void toRemote_完整字段_生成正确的Markdown() {
            var habit = new HabitItem(
                    "uuid-201", "晨跑", HabitItem.Frequency.DAILY, "07:00",
                    15, "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var markdown = mapping.toRemote(habit);

            assertTrue(markdown.contains("type: habit"));
            assertTrue(markdown.contains("name: 晨跑"));
            assertTrue(markdown.contains("frequency: DAILY"));
            // YamlFrontmatterParser 会对含冒号的值加引号
            assertTrue(markdown.contains("targetTime:") && markdown.contains("07:00"));
            assertTrue(markdown.contains("currentStreak: 15"));
            assertTrue(markdown.contains("zhiwei_id:"));
            assertTrue(markdown.contains("uuid-201"));
        }

        @Test
        void toRemote_toLocal_往返转换_关键字段一致() {
            var habit = new HabitItem(
                    "uuid-202", "阅读", HabitItem.Frequency.WEEKLY, "21:00",
                    7, "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var markdown = mapping.toRemote(habit);
            var restored = mapping.toLocal(markdown);

            assertEquals(habit.id(), restored.id());
            assertEquals(habit.name(), restored.name());
            assertEquals(habit.frequency(), restored.frequency());
            assertEquals(habit.targetTime(), restored.targetTime());
            assertEquals(habit.currentStreak(), restored.currentStreak());
        }

        @Test
        void toRemote_toLocal_无可选字段_正确处理() {
            var habit = new HabitItem(
                    "uuid-203", "冥想", HabitItem.Frequency.DAILY, null,
                    0, "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var markdown = mapping.toRemote(habit);
            var restored = mapping.toLocal(markdown);

            assertEquals(habit.id(), restored.id());
            assertEquals(habit.name(), restored.name());
            assertEquals(habit.frequency(), restored.frequency());
            assertNull(restored.targetTime());
            assertEquals(0, restored.currentStreak());
        }

        @Test
        void toLocal_缺失字段_使用默认值() {
            var markdown = """
                    ---
                    type: habit
                    name: 简单习惯
                    zhiwei_id: "uuid-204"
                    ---
                    """;

            var habit = mapping.toLocal(markdown);

            assertEquals("uuid-204", habit.id());
            assertEquals("简单习惯", habit.name());
            assertEquals(HabitItem.Frequency.DAILY, habit.frequency());
            assertNull(habit.targetTime());
            assertEquals(0, habit.currentStreak());
        }

        @Test
        void toLocal_无效频率_使用默认DAILY() {
            var markdown = """
                    ---
                    type: habit
                    name: 测试习惯
                    frequency: INVALID
                    zhiwei_id: "uuid-205"
                    ---
                    """;

            var habit = mapping.toLocal(markdown);

            assertEquals(HabitItem.Frequency.DAILY, habit.frequency());
        }

        @Test
        void extractConflictFields_有差异_返回差异字段() {
            var local = new HabitItem(
                    "uuid-206", "本地习惯", HabitItem.Frequency.DAILY, "08:00",
                    10, "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var remoteMarkdown = """
                    ---
                    type: habit
                    name: 远程习惯
                    frequency: WEEKLY
                    targetTime: "09:00"
                    currentStreak: 5
                    zhiwei_id: "uuid-206"
                    ---
                    """;

            var diffs = mapping.extractConflictFields(local, remoteMarkdown);

            assertTrue(diffs.containsKey("name"));
            assertTrue(diffs.containsKey("frequency"));
            assertTrue(diffs.containsKey("targetTime"));
            assertTrue(diffs.containsKey("currentStreak"));
        }

        @Test
        void extractConflictFields_无差异_返回空Map() {
            var habit = new HabitItem(
                    "uuid-207", "相同习惯", HabitItem.Frequency.WEEKLY, null,
                    0, "2026-02-26T10:00:00Z", "2026-02-26T10:00:00Z"
            );

            var markdown = mapping.toRemote(habit);
            var diffs = mapping.extractConflictFields(habit, markdown);

            assertTrue(diffs.isEmpty());
        }
    }
}
