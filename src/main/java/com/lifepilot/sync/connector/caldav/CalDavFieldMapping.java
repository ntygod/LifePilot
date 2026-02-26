package com.lifepilot.sync.connector.caldav;

import com.lifepilot.skill.builtin.schedule.ScheduleItem;
import com.lifepilot.skill.builtin.todo.TodoItem;
import com.lifepilot.sync.mapping.FieldMapping;
import com.lifepilot.sync.model.FieldDiff;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * CalDAV 字段映射，提供 ScheduleItem↔VEVENT 和 TodoItem↔VTODO 的双向转换。
 *
 * <p>日期时间格式转换：
 * <ul>
 *   <li>LifePilot 使用 ISO 8601（如 {@code 2026-03-01T09:00:00Z}）</li>
 *   <li>iCalendar 使用紧凑格式（如 {@code 20260301T090000Z}）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class CalDavFieldMapping {

    private CalDavFieldMapping() {
        // 工具类，禁止实例化
    }

    /**
     * 获取 VEVENT 字段映射实例。
     *
     * @return ScheduleItem↔iCalendar VEVENT 的字段映射
     */
    public static FieldMapping<ScheduleItem, String> eventMapping() {
        return new EventFieldMapping();
    }

    /**
     * 获取 VTODO 字段映射实例。
     *
     * @return TodoItem↔iCalendar VTODO 的字段映射
     */
    public static FieldMapping<TodoItem, String> todoMapping() {
        return new TodoFieldMapping();
    }

    // ==================== 日期时间格式转换 ====================

    /**
     * 将 ISO 8601 日期时间字符串转换为 iCalendar 紧凑格式。
     *
     * <p>示例：{@code 2026-03-01T09:00:00Z} → {@code 20260301T090000Z}
     *
     * @param isoDateTime ISO 8601 格式的日期时间字符串
     * @return iCalendar 紧凑格式字符串；输入为 null 或空时返回空字符串
     */
    static String toICalDateTime(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isBlank()) {
            return "";
        }
        // 移除 '-' 和 ':' 分隔符
        return isoDateTime.replace("-", "").replace(":", "");
    }

    /**
     * 将 iCalendar 紧凑格式日期时间字符串转换为 ISO 8601 格式。
     *
     * <p>示例：{@code 20260301T090000Z} → {@code 2026-03-01T09:00:00Z}
     *
     * @param icalDateTime iCalendar 紧凑格式的日期时间字符串
     * @return ISO 8601 格式字符串；输入为 null 或空时返回空字符串
     */
    static String toIsoDateTime(String icalDateTime) {
        if (icalDateTime == null || icalDateTime.isBlank()) {
            return "";
        }
        // 紧凑格式：20260301T090000Z → 2026-03-01T09:00:00Z
        // 也可能是纯日期：20260301 → 2026-03-01
        var trimmed = icalDateTime.trim();

        // 包含 'T' 的完整日期时间格式
        int tIndex = trimmed.indexOf('T');
        if (tIndex >= 0) {
            var datePart = trimmed.substring(0, tIndex);
            var timePart = trimmed.substring(tIndex + 1);
            return formatIsoDate(datePart) + "T" + formatIsoTime(timePart);
        }

        // 纯日期格式
        return formatIsoDate(trimmed);
    }

    /**
     * 将紧凑日期（如 20260301）转换为 ISO 日期（如 2026-03-01）。
     */
    private static String formatIsoDate(String compactDate) {
        if (compactDate.length() < 8) {
            return compactDate;
        }
        return compactDate.substring(0, 4) + "-"
                + compactDate.substring(4, 6) + "-"
                + compactDate.substring(6, 8);
    }

    /**
     * 将紧凑时间（如 090000Z）转换为 ISO 时间（如 09:00:00Z）。
     */
    private static String formatIsoTime(String compactTime) {
        if (compactTime.length() < 6) {
            return compactTime;
        }
        var time = compactTime.substring(0, 2) + ":"
                + compactTime.substring(2, 4) + ":"
                + compactTime.substring(4, 6);
        // 保留尾部的 'Z' 或时区标识
        if (compactTime.length() > 6) {
            time += compactTime.substring(6);
        }
        return time;
    }

    // ==================== 优先级映射 ====================

    /** TodoItem.Priority → iCalendar PRIORITY 值。 */
    private static final Map<TodoItem.Priority, String> PRIORITY_TO_ICAL = Map.of(
            TodoItem.Priority.HIGH, "1",
            TodoItem.Priority.MEDIUM, "5",
            TodoItem.Priority.LOW, "9"
    );

    /** iCalendar PRIORITY 值 → TodoItem.Priority。 */
    private static final Map<String, TodoItem.Priority> ICAL_TO_PRIORITY = Map.of(
            "1", TodoItem.Priority.HIGH,
            "2", TodoItem.Priority.HIGH,
            "3", TodoItem.Priority.HIGH,
            "4", TodoItem.Priority.HIGH,
            "5", TodoItem.Priority.MEDIUM,
            "6", TodoItem.Priority.LOW,
            "7", TodoItem.Priority.LOW,
            "8", TodoItem.Priority.LOW,
            "9", TodoItem.Priority.LOW
    );

    /**
     * 将 TodoItem.Priority 转换为 iCalendar PRIORITY 值。
     */
    static String priorityToICal(TodoItem.Priority priority) {
        return PRIORITY_TO_ICAL.getOrDefault(priority, "0");
    }

    /**
     * 将 iCalendar PRIORITY 值转换为 TodoItem.Priority。
     */
    static TodoItem.Priority priorityFromICal(String icalPriority) {
        if (icalPriority == null || icalPriority.isBlank() || "0".equals(icalPriority)) {
            return TodoItem.Priority.MEDIUM;
        }
        return ICAL_TO_PRIORITY.getOrDefault(icalPriority.trim(), TodoItem.Priority.MEDIUM);
    }

    // ==================== 状态映射 ====================

    /** TodoItem.Status → iCalendar STATUS 值。 */
    private static final Map<TodoItem.Status, String> STATUS_TO_ICAL = Map.of(
            TodoItem.Status.PENDING, "NEEDS-ACTION",
            TodoItem.Status.IN_PROGRESS, "IN-PROCESS",
            TodoItem.Status.COMPLETED, "COMPLETED"
    );

    /** iCalendar STATUS 值 → TodoItem.Status。 */
    private static final Map<String, TodoItem.Status> ICAL_TO_STATUS = Map.of(
            "NEEDS-ACTION", TodoItem.Status.PENDING,
            "IN-PROCESS", TodoItem.Status.IN_PROGRESS,
            "COMPLETED", TodoItem.Status.COMPLETED
    );

    /**
     * 将 TodoItem.Status 转换为 iCalendar STATUS 值。
     */
    static String statusToICal(TodoItem.Status status) {
        return STATUS_TO_ICAL.getOrDefault(status, "NEEDS-ACTION");
    }

    /**
     * 将 iCalendar STATUS 值转换为 TodoItem.Status。
     */
    static TodoItem.Status statusFromICal(String icalStatus) {
        if (icalStatus == null || icalStatus.isBlank()) {
            return TodoItem.Status.PENDING;
        }
        return ICAL_TO_STATUS.getOrDefault(icalStatus.trim().toUpperCase(), TodoItem.Status.PENDING);
    }

    // ==================== VEVENT 字段映射 ====================

    /**
     * ScheduleItem↔iCalendar VEVENT 字段映射。
     *
     * <p>映射关系：
     * <ul>
     *   <li>title ↔ SUMMARY</li>
     *   <li>startTime ↔ DTSTART</li>
     *   <li>endTime ↔ DTEND</li>
     *   <li>location ↔ LOCATION</li>
     *   <li>notes ↔ DESCRIPTION</li>
     * </ul>
     */
    static final class EventFieldMapping implements FieldMapping<ScheduleItem, String> {

        @Override
        public String toRemote(ScheduleItem local) {
            var properties = new LinkedHashMap<String, String>();
            properties.put("UID", local.id());
            properties.put("SUMMARY", local.title());
            properties.put("DTSTART", toICalDateTime(local.startTime()));
            properties.put("DTEND", toICalDateTime(local.endTime()));
            if (local.location() != null && !local.location().isBlank()) {
                properties.put("LOCATION", local.location());
            }
            if (local.notes() != null && !local.notes().isBlank()) {
                properties.put("DESCRIPTION", local.notes());
            }
            return ICalendarParser.formatVEvent(properties);
        }

        @Override
        public ScheduleItem toLocal(String remote) {
            var props = ICalendarParser.parseComponent(remote);
            var id = props.getOrDefault("UID", UUID.randomUUID().toString());
            var title = props.getOrDefault("SUMMARY", "");
            var startTime = toIsoDateTime(props.getOrDefault("DTSTART", ""));
            var endTime = toIsoDateTime(props.getOrDefault("DTEND", ""));
            var location = props.get("LOCATION");
            var notes = props.get("DESCRIPTION");
            var now = java.time.Instant.now().toString();
            return new ScheduleItem(id, title, startTime, endTime, location, notes, now, now);
        }

        @Override
        public Map<String, FieldDiff> extractConflictFields(ScheduleItem local, String remote) {
            var remoteItem = toLocal(remote);
            var diffs = new LinkedHashMap<String, FieldDiff>();

            if (!Objects.equals(local.title(), remoteItem.title())) {
                diffs.put("title", new FieldDiff("title", local.title(), remoteItem.title()));
            }
            if (!Objects.equals(local.startTime(), remoteItem.startTime())) {
                diffs.put("startTime", new FieldDiff("startTime", local.startTime(), remoteItem.startTime()));
            }
            if (!Objects.equals(local.endTime(), remoteItem.endTime())) {
                diffs.put("endTime", new FieldDiff("endTime", local.endTime(), remoteItem.endTime()));
            }
            if (!Objects.equals(local.location(), remoteItem.location())) {
                diffs.put("location", new FieldDiff("location", local.location(), remoteItem.location()));
            }
            if (!Objects.equals(local.notes(), remoteItem.notes())) {
                diffs.put("notes", new FieldDiff("notes", local.notes(), remoteItem.notes()));
            }

            return Map.copyOf(diffs);
        }
    }

    // ==================== VTODO 字段映射 ====================

    /**
     * TodoItem↔iCalendar VTODO 字段映射。
     *
     * <p>映射关系：
     * <ul>
     *   <li>title ↔ SUMMARY</li>
     *   <li>description ↔ DESCRIPTION</li>
     *   <li>dueDate ↔ DUE</li>
     *   <li>priority ↔ PRIORITY（HIGH→1, MEDIUM→5, LOW→9）</li>
     *   <li>status ↔ STATUS（PENDING→NEEDS-ACTION, IN_PROGRESS→IN-PROCESS, COMPLETED→COMPLETED）</li>
     * </ul>
     */
    static final class TodoFieldMapping implements FieldMapping<TodoItem, String> {

        @Override
        public String toRemote(TodoItem local) {
            var properties = new LinkedHashMap<String, String>();
            properties.put("UID", local.id());
            properties.put("SUMMARY", local.title());
            if (local.description() != null && !local.description().isBlank()) {
                properties.put("DESCRIPTION", local.description());
            }
            if (local.dueDate() != null && !local.dueDate().isBlank()) {
                properties.put("DUE", toICalDateTime(local.dueDate()));
            }
            properties.put("PRIORITY", priorityToICal(local.priority()));
            properties.put("STATUS", statusToICal(local.status()));
            return ICalendarParser.formatVTodo(properties);
        }

        @Override
        public TodoItem toLocal(String remote) {
            var props = ICalendarParser.parseComponent(remote);
            var id = props.getOrDefault("UID", UUID.randomUUID().toString());
            var title = props.getOrDefault("SUMMARY", "");
            var description = props.get("DESCRIPTION");
            var priority = priorityFromICal(props.get("PRIORITY"));
            var status = statusFromICal(props.get("STATUS"));
            var dueDate = props.containsKey("DUE") ? toIsoDateTime(props.get("DUE")) : null;
            var now = java.time.Instant.now().toString();
            return new TodoItem(id, title, description, priority, status, dueDate, null, now, now);
        }

        @Override
        public Map<String, FieldDiff> extractConflictFields(TodoItem local, String remote) {
            var remoteItem = toLocal(remote);
            var diffs = new LinkedHashMap<String, FieldDiff>();

            if (!Objects.equals(local.title(), remoteItem.title())) {
                diffs.put("title", new FieldDiff("title", local.title(), remoteItem.title()));
            }
            if (!Objects.equals(local.description(), remoteItem.description())) {
                diffs.put("description", new FieldDiff("description", local.description(), remoteItem.description()));
            }
            if (!Objects.equals(local.dueDate(), remoteItem.dueDate())) {
                diffs.put("dueDate", new FieldDiff("dueDate", local.dueDate(), remoteItem.dueDate()));
            }
            if (local.priority() != remoteItem.priority()) {
                diffs.put("priority", new FieldDiff("priority", local.priority(), remoteItem.priority()));
            }
            if (local.status() != remoteItem.status()) {
                diffs.put("status", new FieldDiff("status", local.status(), remoteItem.status()));
            }

            return Map.copyOf(diffs);
        }
    }
}
