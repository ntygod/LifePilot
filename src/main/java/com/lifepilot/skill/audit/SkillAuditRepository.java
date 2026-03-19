package com.lifepilot.skill.audit;

import com.lifepilot.skill.event.SkillLifecycleEvent;
import com.lifepilot.skill.event.SkillRegistryEvent;
import com.lifepilot.skill.model.SkillSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Skill 审计仓库 — 持久化 Skill 生命周期事件到 SQLite。
 *
 * <p>通过 Spring Event 异步监听 {@link SkillRegistryEvent} 和 {@link SkillLifecycleEvent}，
 * 不阻塞主流程。写入失败时记录 ERROR 日志，不影响 Skill 注册/激活主流程。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SkillAuditRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillAuditRepository.class);

    private static final String INSERT_SQL = """
            INSERT INTO skill_audit_logs (id, skill_id, event_type, event_detail_json, source_type, operator, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_SKILL_ID_SQL = """
            SELECT id, skill_id, event_type, event_detail_json, source_type, operator, created_at
            FROM skill_audit_logs WHERE skill_id = ? ORDER BY created_at DESC
            """;

    private static final String FIND_BY_TIME_RANGE_SQL = """
            SELECT id, skill_id, event_type, event_detail_json, source_type, operator, created_at
            FROM skill_audit_logs WHERE created_at >= ? AND created_at <= ? ORDER BY created_at DESC
            """;

    private static final RowMapper<SkillAuditEvent> ROW_MAPPER = (rs, rowNum) -> new SkillAuditEvent(
            rs.getString("id"),
            rs.getString("skill_id"),
            SkillAuditEventType.valueOf(rs.getString("event_type")),
            rs.getString("event_detail_json"),
            rs.getString("source_type"),
            rs.getString("operator"),
            rs.getString("created_at")
    );

    private final JdbcTemplate jdbcTemplate;

    public SkillAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记录审计事件。
     *
     * @param event 审计事件
     */
    public void record(SkillAuditEvent event) {
        try {
            jdbcTemplate.update(INSERT_SQL,
                    event.id(),
                    event.skillId(),
                    event.eventType().name(),
                    event.eventDetail(),
                    event.sourceType(),
                    event.operator(),
                    event.createdAt());
        } catch (Exception e) {
            log.error("审计事件写入失败: skillId={}, eventType={}, error={}",
                    event.skillId(), event.eventType(), e.getMessage());
        }
    }

    /**
     * 按 Skill ID 查询审计事件，按 created_at 降序。
     *
     * @param skillId Skill ID
     * @return 审计事件列表
     */
    public List<SkillAuditEvent> findBySkillId(String skillId) {
        return jdbcTemplate.query(FIND_BY_SKILL_ID_SQL, ROW_MAPPER, skillId);
    }

    /**
     * 按时间范围查询审计事件。
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 审计事件列表
     */
    public List<SkillAuditEvent> findByTimeRange(Instant start, Instant end) {
        return jdbcTemplate.query(FIND_BY_TIME_RANGE_SQL, ROW_MAPPER,
                start.toString(), end.toString());
    }

    /**
     * 监听 Skill 注册事件，记录 REGISTERED 审计事件。
     */
    @EventListener
    public void onSkillRegistered(SkillRegistryEvent.SkillRegistered event) {
        var def = event.definition();
        var detail = """
                {"name":"%s","description":"%s","version":"%s","suggestedToolsCount":%d,"source":"%s"}"""
                .formatted(def.name(), escapeJson(def.description()), def.version(),
                        def.suggestedTools().size(), sourceTypeName(def.source()));
        record(new SkillAuditEvent(
                UUID.randomUUID().toString(),
                def.id(),
                SkillAuditEventType.REGISTERED,
                detail,
                sourceTypeName(def.source()),
                "system",
                Instant.now().toString()
        ));
    }

    /**
     * 监听 Skill 注销事件，记录 UNREGISTERED 审计事件。
     */
    @EventListener
    public void onSkillUnregistered(SkillRegistryEvent.SkillUnregistered event) {
        record(new SkillAuditEvent(
                UUID.randomUUID().toString(),
                event.skillId(),
                SkillAuditEventType.UNREGISTERED,
                null,
                "UNKNOWN",
                "system",
                Instant.now().toString()
        ));
    }

    /**
     * 监听 Skill 激活事件，记录 ACTIVATED 审计事件。
     */
    @EventListener
    public void onSkillActivated(SkillLifecycleEvent.Activated event) {
        record(new SkillAuditEvent(
                UUID.randomUUID().toString(),
                event.skillId(),
                SkillAuditEventType.ACTIVATED,
                "{}",
                "UNKNOWN",
                "system",
                Instant.now().toString()
        ));
    }

    /**
     * 将 SkillSource 转换为来源类型名称。
     */
    private static String sourceTypeName(SkillSource source) {
        return switch (source) {
            case SkillSource.UserDefined _ -> "USER_DEFINED";
            case SkillSource.AutoGenerated _ -> "AUTO_GENERATED";
            case SkillSource.Marketplace _ -> "MARKETPLACE";
        };
    }

    /**
     * 简单 JSON 转义（双引号和反斜杠）。
     */
    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
