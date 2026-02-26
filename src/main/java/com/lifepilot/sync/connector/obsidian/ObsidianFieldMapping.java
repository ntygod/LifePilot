package com.lifepilot.sync.connector.obsidian;

import com.lifepilot.skill.builtin.habit.HabitItem;
import com.lifepilot.skill.builtin.schedule.ScheduleItem;
import com.lifepilot.skill.builtin.todo.TodoItem;
import com.lifepilot.sync.mapping.FieldMapping;
import com.lifepilot.sync.model.FieldDiff;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Obsidian 字段映射，提供 TodoItem / ScheduleItem / HabitItem 与 Markdown + YAML frontmatter 的双向转换。
 *
 * <p>Obsidian Vault 中的文件格式约定：
 * <pre>
 * ---
 * type: todo
 * title: 买牛奶
 * priority: HIGH
 * status: PENDING
 * dueDate: "2026-03-01"
 * tags: [购物, 日常]
 * lifepilot_id: "uuid-xxx"
 * ---
 * 任务描述正文...
 * </pre>
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class ObsidianFieldMapping {

    private ObsidianFieldMapping() {
        // 工具类，禁止实例化
    }

    /**
     * 获取 TodoItem 字段映射实例。
     *
     * @return TodoItem↔Markdown YAML frontmatter 的字段映射
     */
    public static FieldMapping<TodoItem, String> todoMapping() {
        return new TodoFieldMapping();
    }

    /**
     * 获取 ScheduleItem 字段映射实例。
     *
     * @return ScheduleItem↔Markdown YAML frontmatter 的字段映射
     */
    public static FieldMapping<ScheduleItem, String> scheduleMapping() {
        return new ScheduleFieldMapping();
    }

    /**
     * 获取 HabitItem 字段映射实例。
     *
     * @return HabitItem↔Markdown YAML frontmatter 的字段映射
     */
    public static FieldMapping<HabitItem, String> habitMapping() {
        return new HabitFieldMapping();
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 frontmatter 中安全获取字符串值。
     */
    private static String getString(Map<String, Object> fm, String key, String defaultValue) {
        var value = fm.get(key);
        if (value == null) {
            return defaultValue;
        }
        return String.valueOf(value);
    }

    /**
     * 从 frontmatter 中安全获取可空字符串值。
     */
    private static String getNullableString(Map<String, Object> fm, String key) {
        var value = fm.get(key);
        if (value == null) {
            return null;
        }
        var str = String.valueOf(value);
        return str.isBlank() ? null : str;
    }

    /**
     * 从 frontmatter 中安全获取整数值。
     */
    private static int getInt(Map<String, Object> fm, String key, int defaultValue) {
        var value = fm.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 从 frontmatter 中获取标签列表。
     */
    @SuppressWarnings("unchecked")
    private static List<String> getTags(Map<String, Object> fm, String key) {
        var value = fm.get(key);
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return null;
            }
            return List.copyOf((List<String>) list);
        }
        return null;
    }

    /**
     * 获取当前时间戳（ISO 8601）。
     */
    private static String now() {
        return Instant.now().toString();
    }

    // ==================== TodoItem 字段映射 ====================

    /**
     * TodoItem↔Markdown YAML frontmatter 字段映射。
     *
     * <p>映射关系：
     * <ul>
     *   <li>id ↔ lifepilot_id</li>
     *   <li>title ↔ title</li>
     *   <li>description ↔ body（正文）</li>
     *   <li>priority ↔ priority（枚举名称）</li>
     *   <li>status ↔ status（枚举名称）</li>
     *   <li>dueDate ↔ dueDate</li>
     *   <li>tags ↔ tags（列表）</li>
     * </ul>
     */
    static final class TodoFieldMapping implements FieldMapping<TodoItem, String> {

        @Override
        public String toRemote(TodoItem local) {
            var fm = new LinkedHashMap<String, Object>();
            fm.put("type", "todo");
            fm.put("title", local.title());
            fm.put("priority", local.priority().name());
            fm.put("status", local.status().name());
            if (local.dueDate() != null) {
                fm.put("dueDate", local.dueDate());
            }
            if (local.tags() != null && !local.tags().isEmpty()) {
                fm.put("tags", local.tags());
            }
            fm.put("lifepilot_id", local.id());

            var body = local.description() != null ? local.description() : "";
            return YamlFrontmatterParser.format(fm, body);
        }

        @Override
        public TodoItem toLocal(String remote) {
            var result = YamlFrontmatterParser.parse(remote);
            var fm = result.frontmatter();
            var body = result.body();

            var id = getString(fm, "lifepilot_id", UUID.randomUUID().toString());
            var title = getString(fm, "title", "");
            var description = (body == null || body.isBlank()) ? null : body;
            var priority = parsePriority(getNullableString(fm, "priority"));
            var status = parseStatus(getNullableString(fm, "status"));
            var dueDate = getNullableString(fm, "dueDate");
            var tags = getTags(fm, "tags");
            var timestamp = now();

            return new TodoItem(id, title, description, priority, status, dueDate, tags, timestamp, timestamp);
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
            if (local.priority() != remoteItem.priority()) {
                diffs.put("priority", new FieldDiff("priority", local.priority(), remoteItem.priority()));
            }
            if (local.status() != remoteItem.status()) {
                diffs.put("status", new FieldDiff("status", local.status(), remoteItem.status()));
            }
            if (!Objects.equals(local.dueDate(), remoteItem.dueDate())) {
                diffs.put("dueDate", new FieldDiff("dueDate", local.dueDate(), remoteItem.dueDate()));
            }
            if (!Objects.equals(local.tags(), remoteItem.tags())) {
                diffs.put("tags", new FieldDiff("tags", local.tags(), remoteItem.tags()));
            }

            return Map.copyOf(diffs);
        }

        private static TodoItem.Priority parsePriority(String value) {
            if (value == null || value.isBlank()) {
                return TodoItem.Priority.LOW;
            }
            try {
                return TodoItem.Priority.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return TodoItem.Priority.LOW;
            }
        }

        private static TodoItem.Status parseStatus(String value) {
            if (value == null || value.isBlank()) {
                return TodoItem.Status.PENDING;
            }
            try {
                return TodoItem.Status.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return TodoItem.Status.PENDING;
            }
        }
    }

    // ==================== ScheduleItem 字段映射 ====================

    /**
     * ScheduleItem↔Markdown YAML frontmatter 字段映射。
     *
     * <p>映射关系：
     * <ul>
     *   <li>id ↔ lifepilot_id</li>
     *   <li>title ↔ title</li>
     *   <li>startTime ↔ startTime</li>
     *   <li>endTime ↔ endTime</li>
     *   <li>location ↔ location</li>
     *   <li>notes ↔ body（正文）</li>
     * </ul>
     */
    static final class ScheduleFieldMapping implements FieldMapping<ScheduleItem, String> {

        @Override
        public String toRemote(ScheduleItem local) {
            var fm = new LinkedHashMap<String, Object>();
            fm.put("type", "schedule");
            fm.put("title", local.title());
            fm.put("startTime", local.startTime());
            fm.put("endTime", local.endTime());
            if (local.location() != null) {
                fm.put("location", local.location());
            }
            fm.put("lifepilot_id", local.id());

            var body = local.notes() != null ? local.notes() : "";
            return YamlFrontmatterParser.format(fm, body);
        }

        @Override
        public ScheduleItem toLocal(String remote) {
            var result = YamlFrontmatterParser.parse(remote);
            var fm = result.frontmatter();
            var body = result.body();

            var id = getString(fm, "lifepilot_id", UUID.randomUUID().toString());
            var title = getString(fm, "title", "");
            var startTime = getString(fm, "startTime", "");
            var endTime = getString(fm, "endTime", "");
            var location = getNullableString(fm, "location");
            var notes = (body == null || body.isBlank()) ? null : body;
            var timestamp = now();

            return new ScheduleItem(id, title, startTime, endTime, location, notes, timestamp, timestamp);
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

    // ==================== HabitItem 字段映射 ====================

    /**
     * HabitItem↔Markdown YAML frontmatter 字段映射。
     *
     * <p>映射关系：
     * <ul>
     *   <li>id ↔ lifepilot_id</li>
     *   <li>name ↔ name</li>
     *   <li>frequency ↔ frequency（枚举名称）</li>
     *   <li>targetTime ↔ targetTime</li>
     *   <li>currentStreak ↔ currentStreak</li>
     * </ul>
     */
    static final class HabitFieldMapping implements FieldMapping<HabitItem, String> {

        @Override
        public String toRemote(HabitItem local) {
            var fm = new LinkedHashMap<String, Object>();
            fm.put("type", "habit");
            fm.put("name", local.name());
            fm.put("frequency", local.frequency().name());
            if (local.targetTime() != null) {
                fm.put("targetTime", local.targetTime());
            }
            fm.put("currentStreak", local.currentStreak());
            fm.put("lifepilot_id", local.id());

            return YamlFrontmatterParser.format(fm, "");
        }

        @Override
        public HabitItem toLocal(String remote) {
            var result = YamlFrontmatterParser.parse(remote);
            var fm = result.frontmatter();

            var id = getString(fm, "lifepilot_id", UUID.randomUUID().toString());
            var name = getString(fm, "name", "");
            var frequency = parseFrequency(getNullableString(fm, "frequency"));
            var targetTime = getNullableString(fm, "targetTime");
            var currentStreak = getInt(fm, "currentStreak", 0);
            var timestamp = now();

            return new HabitItem(id, name, frequency, targetTime, currentStreak, timestamp, timestamp);
        }

        @Override
        public Map<String, FieldDiff> extractConflictFields(HabitItem local, String remote) {
            var remoteItem = toLocal(remote);
            var diffs = new LinkedHashMap<String, FieldDiff>();

            if (!Objects.equals(local.name(), remoteItem.name())) {
                diffs.put("name", new FieldDiff("name", local.name(), remoteItem.name()));
            }
            if (local.frequency() != remoteItem.frequency()) {
                diffs.put("frequency", new FieldDiff("frequency", local.frequency(), remoteItem.frequency()));
            }
            if (!Objects.equals(local.targetTime(), remoteItem.targetTime())) {
                diffs.put("targetTime", new FieldDiff("targetTime", local.targetTime(), remoteItem.targetTime()));
            }
            if (local.currentStreak() != remoteItem.currentStreak()) {
                diffs.put("currentStreak", new FieldDiff("currentStreak", local.currentStreak(), remoteItem.currentStreak()));
            }

            return Map.copyOf(diffs);
        }

        private static HabitItem.Frequency parseFrequency(String value) {
            if (value == null || value.isBlank()) {
                return HabitItem.Frequency.DAILY;
            }
            try {
                return HabitItem.Frequency.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return HabitItem.Frequency.DAILY;
            }
        }
    }
}
