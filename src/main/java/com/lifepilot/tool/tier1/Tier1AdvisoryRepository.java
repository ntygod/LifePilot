package com.lifepilot.tool.tier1;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

/**
 * Tier 1 晋升建议持久化（SQLite）。
 *
 * @author zsg
 * @since 2026-04-23
 */
public class Tier1AdvisoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public Tier1AdvisoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存一条建议记录，返回生成的主键。
     */
    public Long save(Tier1Advisory adv) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO tier1_advisory (tool_id, advised_at, window_days, coverage_ratio, status) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, adv.toolId());
            ps.setString(2, adv.advisedAt().toString());
            ps.setInt(3, adv.windowDays());
            ps.setDouble(4, adv.coverageRatio());
            ps.setString(5, adv.status().name());
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    /** 查询所有待审批记录（按时间倒序）。 */
    public List<Tier1Advisory> findPending() {
        return jdbcTemplate.query(
                "SELECT id, tool_id, advised_at, window_days, coverage_ratio, status, reviewed_by, reviewed_at "
                        + "FROM tier1_advisory WHERE status = 'PENDING' ORDER BY advised_at DESC",
                (rs, i) -> new Tier1Advisory(
                        rs.getLong("id"),
                        rs.getString("tool_id"),
                        Instant.parse(rs.getString("advised_at")),
                        rs.getInt("window_days"),
                        rs.getDouble("coverage_ratio"),
                        Tier1AdvisoryStatus.valueOf(rs.getString("status")),
                        rs.getString("reviewed_by"),
                        rs.getString("reviewed_at") == null ? null : Instant.parse(rs.getString("reviewed_at"))
                ));
    }

    /** 查询所有已批准（APPROVED）的 toolId 列表 — 供 Tier1Service 聚合。 */
    public List<String> findApprovedToolIds() {
        return jdbcTemplate.queryForList(
                "SELECT tool_id FROM tier1_advisory WHERE status = 'APPROVED'",
                String.class);
    }

    /** 批准一条建议。 */
    public void approve(Long id, String reviewer) {
        jdbcTemplate.update(
                "UPDATE tier1_advisory SET status = 'APPROVED', reviewed_by = ?, reviewed_at = ? WHERE id = ?",
                reviewer, Instant.now().toString(), id);
    }

    /** 驳回一条建议。 */
    public void reject(Long id, String reviewer) {
        jdbcTemplate.update(
                "UPDATE tier1_advisory SET status = 'REJECTED', reviewed_by = ?, reviewed_at = ? WHERE id = ?",
                reviewer, Instant.now().toString(), id);
    }
}
