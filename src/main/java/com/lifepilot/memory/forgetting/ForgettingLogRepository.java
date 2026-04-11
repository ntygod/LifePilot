package com.lifepilot.memory.forgetting;

import com.lifepilot.interaction.web.model.ForgettingLogDto;
import com.lifepilot.interaction.web.model.PageResult;
import jakarta.annotation.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;

/**
 * 遗忘日志数据访问仓库 — 封装 {@code forgetting_log} 表的查询操作。
 *
 * @author zsg
 * @since 2026-04-11
 */
@Repository
public class ForgettingLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public ForgettingLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询遗忘日志总数。
     */
    public long countAll() {
        var count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM forgetting_log", Long.class);
        return count != null ? count : 0L;
    }

    /**
     * 获取最近一次遗忘时间。
     *
     * @return 最近一次遗忘时间的 ISO 8601 字符串，无记录时返回 {@code null}
     */
    @Nullable
    public String getLastForgettingTime() {
        var rows = jdbcTemplate.query(
                "SELECT created_at FROM forgetting_log ORDER BY created_at DESC LIMIT 1",
                (rs, rowNum) -> rs.getString("created_at"));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 分页查询遗忘日志，支持时间范围和策略过滤。
     *
     * @param timeFrom 起始时间（ISO 8601），可为空
     * @param timeTo   截止时间（ISO 8601），可为空
     * @param strategy 遗忘策略，可为空
     * @param page     页码（从 0 开始）
     * @param size     每页大小
     * @return 分页结果
     */
    public PageResult<ForgettingLogDto> findPaginated(@Nullable String timeFrom,
                                                       @Nullable String timeTo,
                                                       @Nullable String strategy,
                                                       int page,
                                                       int size) {
        // 构建动态 SQL
        var conditions = new ArrayList<String>();
        var params = new ArrayList<Object>();

        if (timeFrom != null && !timeFrom.isBlank()) {
            conditions.add("created_at >= ?");
            params.add(timeFrom);
        }
        if (timeTo != null && !timeTo.isBlank()) {
            conditions.add("created_at <= ?");
            params.add(timeTo);
        }
        if (strategy != null && !strategy.isBlank()) {
            conditions.add("strategy = ?");
            params.add(strategy);
        }

        String whereClause = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

        // 查询总数
        var countSql = "SELECT COUNT(*) FROM forgetting_log" + whereClause;
        var total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());

        // 查询分页数据
        var dataSql = "SELECT id, entity_id, entity_name, strategy, action_taken, forgetting_priority, reason, created_at" +
                " FROM forgetting_log" + whereClause + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        params.add(size);
        params.add(page * size);

        var items = jdbcTemplate.query(dataSql, (rs, rowNum) -> new ForgettingLogDto(
                rs.getString("id"),
                rs.getString("entity_id"),
                rs.getString("entity_name"),
                rs.getString("strategy"),
                rs.getString("action_taken"),
                rs.getFloat("forgetting_priority"),
                rs.getString("reason"),
                Instant.parse(rs.getString("created_at"))
        ), params.toArray());

        return new PageResult<>(items, page, size, total != null ? total : 0L);
    }
}
