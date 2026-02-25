package com.lifepilot.skill.builtin.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 日程仓储 — 基于 JdbcTemplate 操作 SQLite schedules 表。
 *
 * <p>提供日程的 CRUD 操作和时间冲突检测。冲突检测使用区间重叠算法：
 * 当 schedule.start_time &lt; queryEndTime AND schedule.end_time &gt; queryStartTime 时，
 * 认为该日程与查询时间段存在重叠。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ScheduleRepository {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ScheduleItem> rowMapper;

    public ScheduleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = this::mapRow;
    }

    /**
     * 创建日程。
     *
     * @param item 日程（id、createdAt、updatedAt 由系统生成）
     * @return 生成的日程 ID
     */
    public String create(ScheduleItem item) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        jdbcTemplate.update("""
                INSERT INTO schedules (id, title, start_time, end_time, location, notes, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, item.title(), item.startTime(), item.endTime(),
                item.location(), item.notes(), now, now);

        log.info("日程创建成功: id={}, title={}", id, item.title());
        return id;
    }

    /**
     * 查询所有日程，按开始时间升序排列。
     *
     * @return 日程列表
     */
    public List<ScheduleItem> list() {
        return jdbcTemplate.query(
                "SELECT * FROM schedules ORDER BY start_time ASC",
                rowMapper);
    }

    /**
     * 根据 ID 查找日程。
     *
     * @param id 日程 ID
     * @return 日程 Optional，不存在时返回 empty
     */
    public Optional<ScheduleItem> findById(String id) {
        List<ScheduleItem> results = jdbcTemplate.query(
                "SELECT * FROM schedules WHERE id = ?", rowMapper, id);
        return results.stream().findFirst();
    }

    /**
     * 更新日程。
     *
     * <p>更新 title、startTime、endTime、location、notes、updatedAt。</p>
     *
     * @param id      日程 ID
     * @param updated 更新后的日程数据
     * @return 是否更新成功
     */
    public boolean update(String id, ScheduleItem updated) {
        String now = Instant.now().toString();

        int rows = jdbcTemplate.update("""
                UPDATE schedules SET title = ?, start_time = ?, end_time = ?,
                    location = ?, notes = ?, updated_at = ?
                WHERE id = ?
                """,
                updated.title(), updated.startTime(), updated.endTime(),
                updated.location(), updated.notes(), now, id);

        if (rows > 0) {
            log.info("日程更新成功: id={}", id);
        } else {
            log.warn("日程不存在，无法更新: id={}", id);
        }
        return rows > 0;
    }

    /**
     * 删除日程。
     *
     * @param id 日程 ID
     * @return 是否删除成功
     */
    public boolean delete(String id) {
        int rows = jdbcTemplate.update("DELETE FROM schedules WHERE id = ?", id);
        if (rows > 0) {
            log.info("日程删除成功: id={}", id);
        }
        return rows > 0;
    }

    /**
     * 查询与指定时间段重叠的日程（冲突检测）。
     *
     * <p>重叠条件：schedule.start_time &lt; endTime AND schedule.end_time &gt; startTime。
     * 即查询时间段与日程时间段存在交集。</p>
     *
     * @param startTime 查询时间段的开始时间（ISO 8601）
     * @param endTime   查询时间段的结束时间（ISO 8601）
     * @return 与指定时间段重叠的日程列表
     */
    public List<ScheduleItem> findConflicts(String startTime, String endTime) {
        return jdbcTemplate.query("""
                SELECT * FROM schedules
                WHERE start_time < ? AND end_time > ?
                ORDER BY start_time ASC
                """,
                rowMapper, endTime, startTime);
    }

    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 ScheduleItem record。 */
    private ScheduleItem mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ScheduleItem(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("start_time"),
                rs.getString("end_time"),
                rs.getString("location"),
                rs.getString("notes"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }
}
