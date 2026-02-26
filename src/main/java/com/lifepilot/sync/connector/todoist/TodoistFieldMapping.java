package com.lifepilot.sync.connector.todoist;

import com.lifepilot.skill.builtin.todo.TodoItem;
import com.lifepilot.sync.mapping.FieldMapping;
import com.lifepilot.sync.model.FieldDiff;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.*;

/**
 * Todoist 字段映射，提供 TodoItem↔Todoist task JSON（Map）的双向转换。
 *
 * <p>字段映射关系：
 * <ul>
 *   <li>title ↔ content</li>
 *   <li>description ↔ description</li>
 *   <li>priority ↔ priority（值反转：HIGH→4, MEDIUM→3, LOW→2, 默认→1）</li>
 *   <li>dueDate ↔ due.date（嵌套 Map）</li>
 *   <li>tags ↔ labels</li>
 *   <li>status ↔ checked（COMPLETED→true, 其他→false）</li>
 *   <li>id ↔ id</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public final class TodoistFieldMapping {

    private TodoistFieldMapping() {
        // 工具类，禁止实例化
    }

    /**
     * 获取 TodoItem↔Todoist task 字段映射实例。
     *
     * @return TodoItem↔Map 的字段映射
     */
    public static FieldMapping<TodoItem, Map<String, Object>> taskMapping() {
        return new TaskFieldMapping();
    }

    // ==================== 优先级映射（值反转） ====================

    /** TodoItem.Priority → Todoist priority 值（HIGH→4, MEDIUM→3, LOW→2）。 */
    private static final Map<TodoItem.Priority, Integer> PRIORITY_TO_TODOIST = Map.of(
            TodoItem.Priority.HIGH, 4,
            TodoItem.Priority.MEDIUM, 3,
            TodoItem.Priority.LOW, 2
    );

    /** Todoist priority 值 → TodoItem.Priority（4→HIGH, 3→MEDIUM, 2→LOW, 1→LOW）。 */
    private static final Map<Integer, TodoItem.Priority> TODOIST_TO_PRIORITY = Map.of(
            4, TodoItem.Priority.HIGH,
            3, TodoItem.Priority.MEDIUM,
            2, TodoItem.Priority.LOW,
            1, TodoItem.Priority.LOW
    );

    /**
     * 将 TodoItem.Priority 转换为 Todoist priority 值。
     *
     * @param priority 本地优先级
     * @return Todoist priority 整数值
     */
    static int priorityToTodoist(@Nullable TodoItem.Priority priority) {
        if (priority == null) {
            return 1;
        }
        return PRIORITY_TO_TODOIST.getOrDefault(priority, 1);
    }

    /**
     * 将 Todoist priority 值转换为 TodoItem.Priority。
     *
     * @param todoistPriority Todoist priority 整数值
     * @return 本地优先级
     */
    static TodoItem.Priority priorityFromTodoist(int todoistPriority) {
        return TODOIST_TO_PRIORITY.getOrDefault(todoistPriority, TodoItem.Priority.LOW);
    }

    // ==================== 状态映射 ====================

    /**
     * 将 TodoItem.Status 转换为 Todoist checked 布尔值。
     *
     * @param status 本地状态
     * @return COMPLETED→true, 其他→false
     */
    static boolean statusToChecked(TodoItem.Status status) {
        return status == TodoItem.Status.COMPLETED;
    }

    /**
     * 将 Todoist checked 布尔值转换为 TodoItem.Status。
     *
     * @param checked Todoist 完成标记
     * @return true→COMPLETED, false→PENDING
     */
    static TodoItem.Status statusFromChecked(boolean checked) {
        return checked ? TodoItem.Status.COMPLETED : TodoItem.Status.PENDING;
    }

    // ==================== due date 嵌套 Map 处理 ====================

    /**
     * 将 dueDate 字符串转换为 Todoist due 嵌套 Map。
     *
     * @param dueDate ISO 日期字符串（如 "2026-01-15"），可为 null
     * @return 嵌套 Map {date: "2026-01-15"}，dueDate 为 null 时返回 null
     */
    @Nullable
    static Map<String, Object> dueDateToTodoist(@Nullable String dueDate) {
        if (dueDate == null || dueDate.isBlank()) {
            return null;
        }
        return Map.of("date", dueDate);
    }

    /**
     * 从 Todoist due 嵌套 Map 中提取 dueDate。
     *
     * @param dueMap Todoist due 对象（Map 或 null）
     * @return ISO 日期字符串，无 due 信息时返回 null
     */
    @Nullable
    static String dueDateFromTodoist(@Nullable Object dueMap) {
        if (dueMap instanceof Map<?, ?> map) {
            var date = map.get("date");
            return date != null ? date.toString() : null;
        }
        return null;
    }

    /**
     * 从 Map 中安全提取 labels 列表。
     *
     * @param labels 远程 labels 值（List 或 null）
     * @return 标签列表，null 时返回空列表
     */
    static List<String> extractLabels(@Nullable Object labels) {
        if (labels instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }
        return List.of();
    }

    // ==================== TaskFieldMapping 内部类 ====================

    /**
     * TodoItem↔Todoist task JSON 字段映射实现。
     */
    static final class TaskFieldMapping implements FieldMapping<TodoItem, Map<String, Object>> {

        @Override
        public Map<String, Object> toRemote(TodoItem local) {
            var remote = new LinkedHashMap<String, Object>();
            remote.put("id", local.id());
            remote.put("content", local.title());
            remote.put("description", local.description() != null ? local.description() : "");
            remote.put("priority", priorityToTodoist(local.priority()));
            remote.put("checked", statusToChecked(local.status()));

            var dueMap = dueDateToTodoist(local.dueDate());
            if (dueMap != null) {
                remote.put("due", dueMap);
            }

            remote.put("labels", local.tags() != null ? List.copyOf(local.tags()) : List.of());

            return Map.copyOf(remote);
        }

        @Override
        public TodoItem toLocal(Map<String, Object> remote) {
            var id = remote.getOrDefault("id", UUID.randomUUID().toString()).toString();
            var title = remote.getOrDefault("content", "").toString();
            var description = remote.get("description");
            var descStr = (description != null && !description.toString().isBlank())
                    ? description.toString() : null;

            // 优先级转换
            var priorityObj = remote.get("priority");
            int priorityInt = 1;
            if (priorityObj instanceof Number num) {
                priorityInt = num.intValue();
            }
            var priority = priorityFromTodoist(priorityInt);

            // 状态转换
            var checkedObj = remote.get("checked");
            boolean checked = checkedObj instanceof Boolean b && b;
            var status = statusFromChecked(checked);

            // due date 提取
            var dueDate = dueDateFromTodoist(remote.get("due"));

            // labels 提取
            var tags = extractLabels(remote.get("labels"));
            var tagsList = tags.isEmpty() ? null : tags;

            var now = Instant.now().toString();
            return new TodoItem(id, title, descStr, priority, status, dueDate, tagsList, now, now);
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
            if (!Objects.equals(local.dueDate(), remoteItem.dueDate())) {
                diffs.put("dueDate", new FieldDiff("dueDate", local.dueDate(), remoteItem.dueDate()));
            }
            if (!Objects.equals(local.tags(), remoteItem.tags())) {
                diffs.put("tags", new FieldDiff("tags", local.tags(), remoteItem.tags()));
            }

            return Map.copyOf(diffs);
        }
    }
}
