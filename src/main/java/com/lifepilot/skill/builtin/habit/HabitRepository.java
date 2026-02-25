package com.lifepilot.skill.builtin.habit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 习惯仓储 — 基于 JdbcTemplate 操作 SQLite habits / habit_logs 表。
 *
 * <p>提供习惯的 CRUD 操作、打卡记录、连续打卡天数计算和完成率统计。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class HabitRepository {

    private static final Logger log = LoggerFactory.getLogger(HabitRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<HabitItem> habitRowMapper;
    private final RowMapper<HabitLog> logRowMapper;

    public HabitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.habitRowMapper = this::mapHabitRow;
        this.logRowMapper = this::mapLogRow;
    }

    /**
     * 创建习惯。
     *
     * @param item 习惯（id、createdAt、updatedAt 由系统生成）
     * @return 生成的习惯 ID
     */
    public String create(HabitItem item) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        jdbcTemplate.update("""
                INSERT INTO habits (id, name, frequency, target_time, current_streak, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, ?, ?)
                """,
                id, item.name(), item.frequency().name(),
                item.targetTime(), now, now);

        log.info("习惯创建成功: id={}, name={}", id, item.name());
        return id;
    }

    /**
     * 查询所有习惯，按创建时间降序排列。
     *
     * @return 习惯列表
     */
    public List<HabitItem> list() {
        return jdbcTemplate.query(
                "SELECT * FROM habits ORDER BY created_at DESC",
                habitRowMapper);
    }

    /**
     * 根据 ID 查找习惯。
     *
     * @param id 习惯 ID
     * @return 习惯 Optional，不存在时返回 empty
     */
    public Optional<HabitItem> findById(String id) {
        List<HabitItem> results = jdbcTemplate.query(
                "SELECT * FROM habits WHERE id = ?", habitRowMapper, id);
        return results.stream().findFirst();
    }

    /**
     * 更新习惯。
     *
     * <p>更新 name、frequency、targetTime、updatedAt。</p>
     *
     * @param id      习惯 ID
     * @param updated 更新后的习惯数据
     * @return 是否更新成功
     */
    public boolean update(String id, HabitItem updated) {
        String now = Instant.now().toString();

        int rows = jdbcTemplate.update("""
                UPDATE habits SET name = ?, frequency = ?, target_time = ?, updated_at = ?
                WHERE id = ?
                """,
                updated.name(), updated.frequency().name(),
                updated.targetTime(), now, id);

        if (rows > 0) {
            log.info("习惯更新成功: id={}", id);
        } else {
            log.warn("习惯不存在，无法更新: id={}", id);
        }
        return rows > 0;
    }

    /**
     * 打卡 — 创建 HabitLog 记录并更新连续打卡天数。
     *
     * @param habitId 习惯 ID
     * @return 生成的打卡记录 ID
     * @throws IllegalArgumentException 习惯不存在时抛出
     */
    public String checkin(String habitId) {
        // 校验习惯存在
        Optional<HabitItem> existing = findById(habitId);
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("习惯不存在: id=" + habitId);
        }

        String logId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        // 创建打卡记录
        jdbcTemplate.update("""
                INSERT INTO habit_logs (id, habit_id, checked_at, created_at)
                VALUES (?, ?, ?, ?)
                """,
                logId, habitId, now, now);

        // 重新计算 streak 并更新
        int streak = calculateStreak(habitId);
        jdbcTemplate.update(
                "UPDATE habits SET current_streak = ?, updated_at = ? WHERE id = ?",
                streak, now, habitId);

        log.info("习惯打卡成功: habitId={}, logId={}, streak={}", habitId, logId, streak);
        return logId;
    }

    /**
     * 计算连续打卡天数。
     *
     * <p>从今天开始往前数，每天必须有至少一条打卡记录才算连续。
     * 如果今天没有打卡，则从昨天开始计算。</p>
     *
     * @param habitId 习惯 ID
     * @return 连续打卡天数
     */
    public int calculateStreak(String habitId) {
        // 查询所有打卡记录，按 checked_at 降序
        List<HabitLog> logs = jdbcTemplate.query(
                "SELECT * FROM habit_logs WHERE habit_id = ? ORDER BY checked_at DESC",
                logRowMapper, habitId);

        if (logs.isEmpty()) {
            return 0;
        }

        // 将打卡时间转换为 LocalDate 并去重
        LocalDate today = LocalDate.now();
        List<LocalDate> checkinDates = logs.stream()
                .map(l -> Instant.parse(l.checkedAt()).atZone(ZoneId.systemDefault()).toLocalDate())
                .distinct()
                .sorted((a, b) -> b.compareTo(a)) // 降序
                .toList();

        if (checkinDates.isEmpty()) {
            return 0;
        }

        // 确定起始日期：如果今天有打卡从今天开始，否则从昨天开始
        LocalDate startDate = checkinDates.getFirst().equals(today) ? today : today.minusDays(1);

        // 如果起始日期没有打卡记录，streak 为 0
        if (!checkinDates.contains(startDate)) {
            return 0;
        }

        // 从起始日期往前数连续天数
        int streak = 0;
        LocalDate expectedDate = startDate;
        for (LocalDate date : checkinDates) {
            if (date.isAfter(expectedDate)) {
                // 跳过未来的日期（比如今天有打卡但我们从昨天开始算）
                continue;
            }
            if (date.equals(expectedDate)) {
                streak++;
                expectedDate = expectedDate.minusDays(1);
            } else {
                // 日期不连续，中断
                break;
            }
        }

        return streak;
    }

    /**
     * 计算指定时间范围内的完成率。
     *
     * <p>完成率 = 有打卡的天数 / 总天数。如果总天数为 0，返回 0.0。</p>
     *
     * @param habitId 习惯 ID
     * @param from    起始时间（含）
     * @param to      结束时间（含）
     * @return 完成率（0.0 - 1.0）
     */
    public double calculateCompletionRate(String habitId, Instant from, Instant to) {
        LocalDate fromDate = from.atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate toDate = to.atZone(ZoneId.systemDefault()).toLocalDate();

        long totalDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        if (totalDays <= 0) {
            return 0.0;
        }

        // 查询范围内有打卡的不同天数
        String fromStr = from.toString();
        String toStr = to.toString();

        // 使用 SQL 统计不同打卡日期数
        List<String> checkinDates = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT substr(checked_at, 1, 10) AS check_date
                FROM habit_logs
                WHERE habit_id = ? AND checked_at >= ? AND checked_at <= ?
                """,
                String.class, habitId, fromStr, toStr);

        long checkinDays = checkinDates.size();
        return (double) checkinDays / totalDays;
    }

    // ---- 内部方法 ----

    /** RowMapper：将 ResultSet 行映射为 HabitItem record。 */
    private HabitItem mapHabitRow(ResultSet rs, int rowNum) throws SQLException {
        return new HabitItem(
                rs.getString("id"),
                rs.getString("name"),
                HabitItem.Frequency.valueOf(rs.getString("frequency")),
                rs.getString("target_time"),
                rs.getInt("current_streak"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }

    /** RowMapper：将 ResultSet 行映射为 HabitLog record。 */
    private HabitLog mapLogRow(ResultSet rs, int rowNum) throws SQLException {
        return new HabitLog(
                rs.getString("id"),
                rs.getString("habit_id"),
                rs.getString("checked_at"),
                rs.getString("created_at")
        );
    }
}
