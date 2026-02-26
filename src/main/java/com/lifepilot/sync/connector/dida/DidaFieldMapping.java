package com.lifepilot.sync.connector.dida;

import com.lifepilot.skill.builtin.habit.HabitItem;
import com.lifepilot.skill.builtin.todo.TodoItem;
import com.lifepilot.sync.mapping.FieldMapping;
import com.lifepilot.sync.model.FieldDiff;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.*;

/**
 * 滴答清单字段映射，提供 TodoItem↔滴答清单 task JSON 和 HabitItem↔滴答清单 habit JSON 的双向转换。
 *
 * <p>任务字段映射关系：
 * <ul>
 *   <li>title ↔ title</li>
 *   <li>description ↔ content（nullable）</li>
 *   <li>priority ↔ priority（HIGH→5, MEDIUM→3, LOW→1, 默认→0）</li>
 *   <li>status ↔ status（COMPLETED→2, IN_PROGRESS→1, PENDING→0）</li>
 *   <li>dueDate ↔ dueDate（ISO 日期字符串）</li>
 *   <li>tags ↔ tags（List&lt;String&gt;）</li>
 *   <li>id ↔ id</li>
 * </ul>
 *
 * <p>习惯字段映射关系：
 * <ul>
 *   <li>name ↔ name</li>
 *   <li>frequency ↔ repeatRule（DAILY→"RRULE:FREQ=DAILY", WEEKLY→"RRULE:FREQ=WEEKLY"）</li>
 *   <li>targetTime ↔ targetTime（nullable）</li>
 *   <li>id ↔ id</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class DidaFieldMapping {

    private DidaFieldMapping() {
        // 工具类，禁止实例化
    }

    /**
     * 获取 TodoItem↔滴答清单 task 字段映射实例。
     *
     * @return TodoItem↔Map 的字段映射
     */
    public static FieldMapping<TodoItem, Map<String, Object>> taskMapping() {
        return new TaskFieldMapping();
    }

    /**
     * 获取 HabitItem↔滴答清单 habit 字段映射实例。
     *
     * @return HabitItem↔Map 的字段映射
     */
    public static FieldMapping<HabitItem, Map<String, Object>> habitMapping() {
        return new HabitFieldMapping();
    }

    // ==================== 任务优先级映射 ====================

    /** TodoItem.Priority → 滴答清单 priority 值（HIGH→5, MEDIUM→3, LOW→1）。 */
    private static final Map<TodoItem.Priority, Integer> PRIORITY_TO_DIDA = Map.of(
            TodoItem.Priority.HIGH, 5,
            TodoItem.Priority.MEDIUM, 3,
            TodoItem.Priority.LOW, 1
    );

    /** 滴答清单 priority 值 → TodoItem.Priority（5→HIGH, 3→MEDIUM, 1→LOW, 0→LOW）。 */
    private static final Map<Integer, TodoItem.Priority> DIDA_TO_PRIORITY = Map.of(
            5, TodoItem.Priority.HIGH,
            3, TodoItem.Priority.MEDIUM,
            1, TodoItem.Priority.LOW,
            0, TodoItem.Priority.LOW
    );

    /**
     * 将 TodoItem.Priority 转换为滴答清单 priority 值。
     *
     * @param priority 本地优先级
     * @return 滴答清单 priority 整数值
     */
    static int priorityToDida(@Nullable TodoItem.Priority priority) {
        if (priority == null) {
            return 0;
        }
        return PRIORITY_TO_DIDA.getOrDefault(priority, 0);
    }

    /**
     * 将滴答清单 priority 值转换为 TodoItem.Priority。
     *
     * @param didaPriority 滴答清单 priority 整数值
     * @return 本地优先级
     */
    static TodoItem.Priority priorityFromDida(int didaPriority) {
        return DIDA_TO_PRIORITY.getOrDefault(didaPriority, TodoItem.Priority.LOW);
    }

    // ==================== 任务状态映射 ====================

    /** TodoItem.Status → 滴答清单 status 值（COMPLETED→2, IN_PROGRESS→1, PENDING→0）。 */
    private static final Map<TodoItem.Status, Integer> STATUS_TO_DIDA = Map.of(
            TodoItem.Status.COMPLETED, 2,
            TodoItem.Status.IN_PROGRESS, 1,
            TodoItem.Status.PENDING, 0
    );

    /** 滴答清单 status 值 → TodoItem.Status（2→COMPLETED, 1→IN_PROGRESS, 0→PENDING）。 */
    private static final Map<Integer, TodoItem.Status> DIDA_TO_STATUS = Map.of(
            2, TodoItem.Status.COMPLETED,
            1, TodoItem.Status.IN_PROGRESS,
            0, TodoItem.Status.PENDING
    );

    /**
     * 将 TodoItem.Status 转换为滴答清单 status 值。
     *
     * @param status 本地状态
     * @return 滴答清单 status 整数值
     */
    static int statusToDida(TodoItem.Status status) {
        return STATUS_TO_DIDA.getOrDefault(status, 0);
    }

    /**
     * 将滴答清单 status 值转换为 TodoItem.Status。
     *
     * @param didaStatus 滴答清单 status 整数值
     * @return 本地状态
     */
    static TodoItem.Status statusFromDida(int didaStatus) {
        return DIDA_TO_STATUS.getOrDefault(didaStatus, TodoItem.Status.PENDING);
    }

    // ==================== 习惯频率映射 ====================

    /** HabitItem.Frequency → 滴答清单 repeatRule（RRULE 格式）。 */
    private static final Map<HabitItem.Frequency, String> FREQUENCY_TO_RRULE = Map.of(
            HabitItem.Frequency.DAILY, "RRULE:FREQ=DAILY",
            HabitItem.Frequency.WEEKLY, "RRULE:FREQ=WEEKLY"
    );

    /**
     * 将 HabitItem.Frequency 转换为滴答清单 repeatRule（RRULE 格式）。
     *
     * @param frequency 本地频率
     * @return RRULE 字符串
     */
    static String frequencyToRrule(HabitItem.Frequency frequency) {
        return FREQUENCY_TO_RRULE.getOrDefault(frequency, "RRULE:FREQ=DAILY");
    }

    /**
     * 将滴答清单 repeatRule（RRULE 格式）转换为 HabitItem.Frequency。
     *
     * @param rrule RRULE 字符串，可为 null
     * @return 本地频率，无法识别时默认 DAILY
     */
    static HabitItem.Frequency frequencyFromRrule(@Nullable String rrule) {
        if (rrule != null && rrule.toUpperCase().contains("FREQ=WEEKLY")) {
            return HabitItem.Frequency.WEEKLY;
        }
        return HabitItem.Frequency.DAILY;
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 Map 中安全提取 tags 列表。
     *
     * @param tags 远程 tags 值（List 或 null）
     * @return 标签列表，null 或空时返回空列表
     */
    static List<String> extractTags(@Nullable Object tags) {
        if (tags instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }
        return List.of();
    }

    // ==================== TaskFieldMapping 内部类 ====================

    /**
     * TodoItem↔滴答清单 task JSON 字段映射实现。
     */
    static final class TaskFieldMapping implements FieldMapping<TodoItem, Map<String, Object>> {

        @Override
        public Map<String, Object> toRemote(TodoItem local) {
            var remote = new LinkedHashMap<String, Object>();
            remote.put("id", local.id());
            remote.put("title", local.title());
            remote.put("content", local.description() != null ? local.description() : "");
            remote.put("priority", priorityToDida(local.priority()));
            remote.put("status", statusToDida(local.status()));

            if (local.dueDate() != null && !local.dueDate().isBlank()) {
                remote.put("dueDate", local.dueDate());
            }

            remote.put("tags", local.tags() != null ? List.copyOf(local.tags()) : List.of());

            return Map.copyOf(remote);
        }

        @Override
        public TodoItem toLocal(Map<String, Object> remote) {
            var id = remote.getOrDefault("id", UUID.randomUUID().toString()).toString();
            var title = remote.getOrDefault("title", "").toString();

            // content → description（null/blank → null）
            var content = remote.get("content");
            var description = (content != null && !content.toString().isBlank())
                    ? content.toString() : null;

            // 优先级转换
            var priorityObj = remote.get("priority");
            int priorityInt = 0;
            if (priorityObj instanceof Number num) {
                priorityInt = num.intValue();
            }
            var priority = priorityFromDida(priorityInt);

            // 状态转换
            var statusObj = remote.get("status");
            int statusInt = 0;
            if (statusObj instanceof Number num) {
                statusInt = num.intValue();
            }
            var status = statusFromDida(statusInt);

            // dueDate 提取
            var dueDateObj = remote.get("dueDate");
            var dueDate = (dueDateObj != null && !dueDateObj.toString().isBlank())
                    ? dueDateObj.toString() : null;

            // tags 提取
            var tags = extractTags(remote.get("tags"));
            var tagsList = tags.isEmpty() ? null : tags;

            var now = Instant.now().toString();
            return new TodoItem(id, title, description, priority, status, dueDate, tagsList, now, now);
        }

        @Override
        public Map<String, FieldDiff> extractConflictFields(TodoItem local, Map<String, Object> remote) {
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
    }

    // ==================== HabitFieldMapping 内部类 ====================

    /**
     * HabitItem↔滴答清单 habit JSON 字段映射实现。
     */
    static final class HabitFieldMapping implements FieldMapping<HabitItem, Map<String, Object>> {

        @Override
        public Map<String, Object> toRemote(HabitItem local) {
            var remote = new LinkedHashMap<String, Object>();
            remote.put("id", local.id());
            remote.put("name", local.name());
            remote.put("repeatRule", frequencyToRrule(local.frequency()));

            if (local.targetTime() != null && !local.targetTime().isBlank()) {
                remote.put("targetTime", local.targetTime());
            }

            return Map.copyOf(remote);
        }

        @Override
        public HabitItem toLocal(Map<String, Object> remote) {
            var id = remote.getOrDefault("id", UUID.randomUUID().toString()).toString();
            var name = remote.getOrDefault("name", "").toString();

            // repeatRule → frequency
            var repeatRule = remote.get("repeatRule");
            var frequency = frequencyFromRrule(repeatRule != null ? repeatRule.toString() : null);

            // targetTime 提取
            var targetTimeObj = remote.get("targetTime");
            var targetTime = (targetTimeObj != null && !targetTimeObj.toString().isBlank())
                    ? targetTimeObj.toString() : null;

            // currentStreak 从 completedCount 或默认 0
            var completedCountObj = remote.get("completedCount");
            int currentStreak = 0;
            if (completedCountObj instanceof Number num) {
                currentStreak = num.intValue();
            }

            var now = Instant.now().toString();
            return new HabitItem(id, name, frequency, targetTime, currentStreak, now, now);
        }

        @Override
        public Map<String, FieldDiff> extractConflictFields(HabitItem local, Map<String, Object> remote) {
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

            return Map.copyOf(diffs);
        }
    }
}
