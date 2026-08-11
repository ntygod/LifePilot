package com.lifepilot.agent.learning.forgetting;

import com.lifepilot.interaction.web.model.ForgettingLogDto;
import com.lifepilot.interaction.web.model.PageResult;
import jakarta.annotation.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Objects;

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
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate 不能为空");
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
        if (page < 0) {
            throw new IllegalArgumentException("page 不能小于 0");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size 必须大于 0");
        }

        var conditions = new ArrayList<String>();
        var params = new ArrayList<Object>();

        if (timeFrom != null) {
            conditions.add("created_at >= ?");
            params.add(requireIsoInstant(timeFrom, "timeFrom"));
        }
        if (timeTo != null) {
            conditions.add("created_at <= ?");
            params.add(requireIsoInstant(timeTo, "timeTo"));
        }
        if (strategy != null) {
            conditions.add("strategy = ?");
            params.add(requireCleanText(strategy, "strategy"));
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

    private static String requireIsoInstant(String value, String field) {
        String cleanValue = requireCleanText(value, field);
        try {
            Instant.parse(cleanValue);
            return cleanValue;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + "必须是 ISO 8601 时间: " + value, e);
        }
    }

    private static String requireCleanText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(field + "不能包含首尾空白: " + value);
        }
        return value;
    }
}
