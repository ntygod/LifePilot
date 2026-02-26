package com.lifepilot.sync.connector.caldav;

import com.lifepilot.skill.builtin.schedule.ScheduleItem;
import com.lifepilot.skill.builtin.todo.TodoItem;
import com.lifepilot.sync.mapping.FieldMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CalDavFieldMapping 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class CalDavFieldMappingTest {

    private FieldMapping<ScheduleItem, String> eventMapping;
    private FieldMapping<TodoItem, String> todoMapping;

    @BeforeEach
    void setUp() {
        eventMapping = CalDavFieldMapping.eventMapping();
        todoMapping = CalDavFieldMapping.todoMapping();
    }

    // ==================== 日期时间格式转换 ====================

    @Nested
    class 日期时间格式转换 {

        @Test
        void ISO8601转iCalendar紧凑格式() {
            assertEquals("20260301T090000Z", CalDavFieldMapping.toICalDateTime("2026-03-01T09:00:00Z"));
        }

        @Test
        void iCalendar紧凑格式转ISO8601() {
            assertEquals("2026-03-01T09:00:00Z", CalDavFieldMapping.toIsoDateTime("20260301T090000Z"));
        }

        @Test
        void 纯日期格式转换() {
            assertEquals("20260301", CalDavFieldMapping.toICalDateTime("2026-03-01"));
            assertEquals("2026-03-01", CalDavFieldMapping.toIsoDateTime("20260301"));
        }

        @Test
        void null和空字符串返回空() {
            assertEquals("", CalDavFieldMapping.toICalDateTime(null));
            assertEquals("", CalDavFieldMapping.toICalDateTime(""));
            assertEquals("", CalDavFieldMapping.toIsoDateTime(null));
            assertEquals("", CalDavFieldMapping.toIsoDateTime(""));
        }
    }

    // ==================== 优先级映射 ====================

    @Nested
    class 优先级映射 {

        @Test
        void HIGH映射为1() {
            assertEquals("1", CalDavFieldMapping.priorityToICal(TodoItem.Priority.HIGH));
        }

        @Test
        void MEDIUM映射为5() {
            assertEquals("5", CalDavFieldMapping.priorityToICal(TodoItem.Priority.MEDIUM));
        }

        @Test
        void LOW映射为9() {
            assertEquals("9", CalDavFieldMapping.priorityToICal(TodoItem.Priority.LOW));
        }

        @Test
        void iCal值1映射为HIGH() {
            assertEquals(TodoItem.Priority.HIGH, CalDavFieldMapping.priorityFromICal("1"));
        }

        @Test
        void iCal值5映射为MEDIUM() {
            assertEquals(TodoItem.Priority.MEDIUM, CalDavFieldMapping.priorityFromICal("5"));
        }

        @Test
        void iCal值9映射为LOW() {
            assertEquals(TodoItem.Priority.LOW, CalDavFieldMapping.priorityFromICal("9"));
        }

        @Test
        void iCal值0和null默认为MEDIUM() {
            assertEquals(TodoItem.Priority.MEDIUM, CalDavFieldMapping.priorityFromICal("0"));
            assertEquals(TodoItem.Priority.MEDIUM, CalDavFieldMapping.priorityFromICal(null));
            assertEquals(TodoItem.Priority.MEDIUM, CalDavFieldMapping.priorityFromICal(""));
        }

        @Test
        void iCal值1到4映射为HIGH() {
            for (int i = 1; i <= 4; i++) {
                assertEquals(TodoItem.Priority.HIGH, CalDavFieldMapping.priorityFromICal(String.valueOf(i)));
            }
        }

        @Test
        void iCal值6到9映射为LOW() {
            for (int i = 6; i <= 9; i++) {
                assertEquals(TodoItem.Priority.LOW, CalDavFieldMapping.priorityFromICal(String.valueOf(i)));
            }
        }
    }

    // ==================== 状态映射 ====================

    @Nested
    class 状态映射 {

        @Test
        void PENDING映射为NEEDS_ACTION() {
            assertEquals("NEEDS-ACTION", CalDavFieldMapping.statusToICal(TodoItem.Status.PENDING));
        }

        @Test
        void IN_PROGRESS映射为IN_PROCESS() {
            assertEquals("IN-PROCESS", CalDavFieldMapping.statusToICal(TodoItem.Status.IN_PROGRESS));
        }

        @Test
        void COMPLETED映射为COMPLETED() {
            assertEquals("COMPLETED", CalDavFieldMapping.statusToICal(TodoItem.Status.COMPLETED));
        }

        @Test
        void NEEDS_ACTION映射为PENDING() {
            assertEquals(TodoItem.Status.PENDING, CalDavFieldMapping.statusFromICal("NEEDS-ACTION"));
        }

        @Test
        void IN_PROCESS映射为IN_PROGRESS() {
            assertEquals(TodoItem.Status.IN_PROGRESS, CalDavFieldMapping.statusFromICal("IN-PROCESS"));
        }

        @Test
        void COMPLETED映射为COMPLETED_status() {
            assertEquals(TodoItem.Status.COMPLETED, CalDavFieldMapping.statusFromICal("COMPLETED"));
        }

        @Test
        void null和空字符串默认为PENDING() {
            assertEquals(TodoItem.Status.PENDING, CalDavFieldMapping.statusFromICal(null));
            assertEquals(TodoItem.Status.PENDING, CalDavFieldMapping.statusFromICal(""));
        }

        @Test
        void 大小写不敏感() {
            assertEquals(TodoItem.Status.COMPLETED, CalDavFieldMapping.statusFromICal("completed"));
            assertEquals(TodoItem.Status.IN_PROGRESS, CalDavFieldMapping.statusFromICal("in-process"));
        }
    }

    // ==================== VEVENT 往返转换 ====================

    @Nested
    class VEVENT往返转换 {

        @Test
        void 完整ScheduleItem往返转换_关键字段保持一致() {
            var now = Instant.now().toString();
            var original = new ScheduleItem(
                    "test-id-001",
                    "团队周会",
                    "2026-03-01T09:00:00Z",
                    "2026-03-01T10:00:00Z",
                    "会议室A",
                    "讨论本周进度",
                    now, now
            );

            var icalText = eventMapping.toRemote(original);
            var restored = eventMapping.toLocal(icalText);

            assertEquals(original.id(), restored.id());
            assertEquals(original.title(), restored.title());
            assertEquals(original.startTime(), restored.startTime());
            assertEquals(original.endTime(), restored.endTime());
            assertEquals(original.location(), restored.location());
            assertEquals(original.notes(), restored.notes());
        }

        @Test
        void 可选字段为null时_不包含在iCalendar输出中() {
            var now = Instant.now().toString();
            var item = new ScheduleItem(
                    "test-id-002", "简单事件",
                    "2026-03-01T09:00:00Z", "2026-03-01T10:00:00Z",
                    null, null, now, now
            );

            var icalText = eventMapping.toRemote(item);
            assertFalse(icalText.contains("LOCATION"));
            assertFalse(icalText.contains("DESCRIPTION"));
        }

        @Test
        void iCalendar文本包含VCALENDAR和VEVENT结构() {
            var now = Instant.now().toString();
            var item = new ScheduleItem(
                    "test-id-003", "测试事件",
                    "2026-03-01T09:00:00Z", "2026-03-01T10:00:00Z",
                    null, null, now, now
            );

            var icalText = eventMapping.toRemote(item);
            assertTrue(icalText.contains("BEGIN:VCALENDAR"));
            assertTrue(icalText.contains("BEGIN:VEVENT"));
            assertTrue(icalText.contains("END:VEVENT"));
            assertTrue(icalText.contains("END:VCALENDAR"));
            assertTrue(icalText.contains("SUMMARY:测试事件"));
        }
    }

    // ==================== VTODO 往返转换 ====================

    @Nested
    class VTODO往返转换 {

        @Test
        void 完整TodoItem往返转换_关键字段保持一致() {
            var now = Instant.now().toString();
            var original = new TodoItem(
                    "todo-id-001",
                    "买牛奶",
                    "去超市买两盒牛奶",
                    TodoItem.Priority.HIGH,
                    TodoItem.Status.PENDING,
                    "2026-03-01T23:59:59Z",
                    List.of("购物", "日常"),
                    now, now
            );

            var icalText = todoMapping.toRemote(original);
            var restored = todoMapping.toLocal(icalText);

            assertEquals(original.id(), restored.id());
            assertEquals(original.title(), restored.title());
            assertEquals(original.description(), restored.description());
            assertEquals(original.priority(), restored.priority());
            assertEquals(original.status(), restored.status());
            assertEquals(original.dueDate(), restored.dueDate());
        }

        @Test
        void 各优先级往返转换一致() {
            var now = Instant.now().toString();
            for (var priority : TodoItem.Priority.values()) {
                var item = new TodoItem(
                        "todo-" + priority.name(), "测试", null,
                        priority, TodoItem.Status.PENDING, null, null, now, now
                );
                var restored = todoMapping.toLocal(todoMapping.toRemote(item));
                assertEquals(priority, restored.priority(),
                        "优先级 " + priority + " 往返转换不一致");
            }
        }

        @Test
        void 各状态往返转换一致() {
            var now = Instant.now().toString();
            for (var status : TodoItem.Status.values()) {
                var item = new TodoItem(
                        "todo-" + status.name(), "测试", null,
                        TodoItem.Priority.MEDIUM, status, null, null, now, now
                );
                var restored = todoMapping.toLocal(todoMapping.toRemote(item));
                assertEquals(status, restored.status(),
                        "状态 " + status + " 往返转换不一致");
            }
        }

        @Test
        void 可选字段为null时_不包含在iCalendar输出中() {
            var now = Instant.now().toString();
            var item = new TodoItem(
                    "todo-id-002", "简单任务", null,
                    TodoItem.Priority.LOW, TodoItem.Status.PENDING,
                    null, null, now, now
            );

            var icalText = todoMapping.toRemote(item);
            assertFalse(icalText.contains("DESCRIPTION"));
            assertFalse(icalText.contains("DUE"));
        }

        @Test
        void iCalendar文本包含VCALENDAR和VTODO结构() {
            var now = Instant.now().toString();
            var item = new TodoItem(
                    "todo-id-003", "测试任务", null,
                    TodoItem.Priority.MEDIUM, TodoItem.Status.IN_PROGRESS,
                    null, null, now, now
            );

            var icalText = todoMapping.toRemote(item);
            assertTrue(icalText.contains("BEGIN:VCALENDAR"));
            assertTrue(icalText.contains("BEGIN:VTODO"));
            assertTrue(icalText.contains("END:VTODO"));
            assertTrue(icalText.contains("END:VCALENDAR"));
            assertTrue(icalText.contains("STATUS:IN-PROCESS"));
        }
    }

    // ==================== extractConflictFields ====================

    @Nested
    class 冲突字段提取 {

        @Test
        void VEVENT_相同字段_返回空Map() {
            var now = Instant.now().toString();
            var item = new ScheduleItem(
                    "id-001", "会议", "2026-03-01T09:00:00Z", "2026-03-01T10:00:00Z",
                    "会议室", "备注", now, now
            );
            var icalText = eventMapping.toRemote(item);
            var diffs = eventMapping.extractConflictFields(item, icalText);
            assertTrue(diffs.isEmpty());
        }

        @Test
        void VEVENT_不同字段_返回差异Map() {
            var now = Instant.now().toString();
            var local = new ScheduleItem(
                    "id-001", "本地会议", "2026-03-01T09:00:00Z", "2026-03-01T10:00:00Z",
                    "会议室A", null, now, now
            );
            var remote = new ScheduleItem(
                    "id-001", "远程会议", "2026-03-01T09:00:00Z", "2026-03-01T11:00:00Z",
                    "会议室B", "远程备注", now, now
            );
            var remoteIcal = eventMapping.toRemote(remote);
            var diffs = eventMapping.extractConflictFields(local, remoteIcal);

            assertTrue(diffs.containsKey("title"));
            assertTrue(diffs.containsKey("endTime"));
            assertTrue(diffs.containsKey("location"));
            assertTrue(diffs.containsKey("notes"));
            assertFalse(diffs.containsKey("startTime"));
        }

        @Test
        void VTODO_相同字段_返回空Map() {
            var now = Instant.now().toString();
            var item = new TodoItem(
                    "todo-001", "任务", "描述",
                    TodoItem.Priority.HIGH, TodoItem.Status.PENDING,
                    "2026-03-01T23:59:59Z", null, now, now
            );
            var icalText = todoMapping.toRemote(item);
            var diffs = todoMapping.extractConflictFields(item, icalText);
            assertTrue(diffs.isEmpty());
        }

        @Test
        void VTODO_不同字段_返回差异Map() {
            var now = Instant.now().toString();
            var local = new TodoItem(
                    "todo-001", "本地任务", null,
                    TodoItem.Priority.HIGH, TodoItem.Status.PENDING,
                    null, null, now, now
            );
            var remote = new TodoItem(
                    "todo-001", "远程任务", "远程描述",
                    TodoItem.Priority.LOW, TodoItem.Status.COMPLETED,
                    "2026-03-15T23:59:59Z", null, now, now
            );
            var remoteIcal = todoMapping.toRemote(remote);
            var diffs = todoMapping.extractConflictFields(local, remoteIcal);

            assertTrue(diffs.containsKey("title"));
            assertTrue(diffs.containsKey("description"));
            assertTrue(diffs.containsKey("priority"));
            assertTrue(diffs.containsKey("status"));
            assertTrue(diffs.containsKey("dueDate"));
        }
    }
}
