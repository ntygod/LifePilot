package com.lifepilot.skill.builtin.todo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 待办事项仓储 — 基于 JdbcTemplate 操作 SQLite todos 表。
 *
 * <p>提供待办事项的 CRUD 操作，支持按优先级降序、截止日期升序排列，
 * 以及 PENDING → IN_PROGRESS → COMPLETED 的状态转换校验。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class TodoRepository {

    private static final Logger log = LoggerFactory.getLogger(TodoRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<TodoItem> rowMapper;

    public TodoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = this::mapRow;
    }

    /**
     * 创建待办事项。
     *
     * @param item 待办事项（id、createdAt、updatedAt 由系统生成）
     * @return 生成的待办 ID
     */
    public String create(TodoItem item) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String tagsJson = serializeTags(item.tags());

        jdbcTemplate.update("""
                INSERT INTO todos (id, title, description, priority, status, due_date, tags_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, item.title(), item.description(),
                item.priority().name(), item.status().name(),
                item.dueDate(), tagsJson, now, now);

        log.info("待办创建成功: id={}, title={}", id, item.title());
        return id;
    }

    /**
     * 查询待办列表，支持按状态和优先级过滤。
     *
     * <p>默认按优先级降序（HIGH > MEDIUM > LOW）、截止日期升序（NULLS LAST）排列。</p>
     *
     * @param status   状态过滤（可选）
     * @param priority 优先级过滤（可选）
     * @return 待办列表
     */
    public List<TodoItem> list(@Nullable String status, @Nullable String priority) {
        var sql = new StringBuilder("SELECT * FROM todos WHERE 1=1");
        var params = new ArrayList<>();

        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        if (priority != null && !priority.isBlank()) {
            sql.append(" AND priority = ?");
            params.add(priority);
        }

        // 按优先级降序（HIGH=1 > MEDIUM=2 > LOW=3），截止日期升序（NULLS LAST）
        sql.append("""
                 ORDER BY CASE priority
                     WHEN 'HIGH' THEN 1
                     WHEN 'MEDIUM' THEN 2
                     WHEN 'LOW' THEN 3
                     ELSE 4
                 END ASC,
                 CASE WHEN due_date IS NULL THEN 1 ELSE 0 END ASC,
                 due_date ASC
                """);

        return jdbcTemplate.query(sql.toString(), rowMapper, params.toArray());
    }

    /**
     * 根据 ID 查找待办事项。
     *
     * @param id 待办 ID
     * @return 待办 Optional，不存在时返回 empty
     */
    public Optional<TodoItem> findById(String id) {
        List<TodoItem> results = jdbcTemplate.query(
                "SELECT * FROM todos WHERE id = ?", rowMapper, id);
        return results.stream().findFirst();
    }

    /**
     * 更新待办事项。
     *
     * <p>更新 title、description、priority、status、dueDate、tags、updatedAt。
     * 如果状态发生变化，会校验状态转换合法性。</p>
     *
     * @param id      待办 ID
     * @param updated 更新后的待办数据
     * @return 是否更新成功
     * @throws IllegalStateException 状态转换不合法时抛出
     */
    public boolean update(String id, TodoItem updated) {
        // 查找现有记录，校验状态转换
        Optional<TodoItem> existing = findById(id);
        if (existing.isEmpty()) {
            log.warn("待办不存在，无法更新: id={}", id);
            return false;
        }

        TodoItem current = existing.get();
        if (current.status() != updated.status() && !current.canTransitionTo(updated.status())) {
            throw new IllegalStateException(
                    "非法状态转换: %s → %s".formatted(current.status(), updated.status()));
        }

        String now = Instant.now().toString();
        String tagsJson = serializeTags(updated.tags());

        int rows = jdbcTemplate.update("""
                UPDATE todos SET title = ?, description = ?, priority = ?, status = ?,
                    due_date = ?, tags_json = ?, updated_at = ?
                WHERE id = ?
                """,
                updated.title(), updated.description(),
                updated.priority().name(), updated.status().name(),
                updated.dueDate(), tagsJson, now, id);

        if (rows > 0) {
            log.info("待办更新成功: id={}", id);
        }
        return rows > 0;
    }

    /**
     * 删除待办事项。
     *
     * @param id 待办 ID
     * @return 是否删除成功
     */
    public boolean delete(String id) {
        int rows = jdbcTemplate.update("DELETE FROM todos WHERE id = ?", id);
        if (rows > 0) {
            log.info("待办删除成功: id={}", id);
        }
        return rows > 0;
    }

    /**
     * 完成待办事项 — 将状态转换为 COMPLETED。
     *
     * @param id 待办 ID
     * @return 是否完成成功
     * @throws IllegalStateException 状态转换不合法时抛出
     */
    public boolean complete(String id) {
        Optional<TodoItem> existing = findById(id);
        if (existing.isEmpty()) {
            log.warn("待办不存在，无法完成: id={}", id);
            return false;
        }

        TodoItem current = existing.get();
        if (!current.canTransitionTo(TodoItem.Status.COMPLETED)) {
            throw new IllegalStateException(
                    "非法状态转换: %s → COMPLETED".formatted(current.status()));
        }

        String now = Instant.now().toString();
        int rows = jdbcTemplate.update(
                "UPDATE todos SET status = 'COMPLETED', updated_at = ? WHERE id = ?",
                now, id);

        if (rows > 0) {
            log.info("待办完成: id={}", id);
        }
        return rows > 0;
    }

    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 TodoItem record。 */
    private TodoItem mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TodoItem(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("description"),
                TodoItem.Priority.valueOf(rs.getString("priority")),
                TodoItem.Status.valueOf(rs.getString("status")),
                rs.getString("due_date"),
                deserializeTags(rs.getString("tags_json")),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }

    /** 将 tags 列表序列化为 JSON 字符串。 */
    @Nullable
    private String serializeTags(@Nullable List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        // 简单 JSON 数组序列化：["tag1","tag2"]
        return "[" + String.join(",", tags.stream()
                .map(t -> "\"" + t.replace("\"", "\\\"") + "\"")
                .toList()) + "]";
    }

    /** 将 JSON 字符串反序列化为 tags 列表。 */
    @Nullable
    private List<String> deserializeTags(@Nullable String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return null;
        }
        // 简单 JSON 数组解析：去掉首尾 []，按 , 分割，去掉引号
        String content = json.substring(1, json.length() - 1);
        return Arrays.stream(content.split(","))
                .map(String::trim)
                .map(s -> s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s)
                .map(s -> s.replace("\\\"", "\""))
                .toList();
    }
}
