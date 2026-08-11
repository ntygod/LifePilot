package com.lifepilot.memory.store.procedural;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * L4 操作模板数据访问仓库 — 专供 {@code L4SyncListener} 使用的失活路径。
 *
 * <p>与 {@link PreferenceRuleRepository} 对称，本仓库以
 * {@code deactivated_reason IS NULL} 判定模板仍在生效。</p>
 *
 * <p>{@link ProceduralMemory} 的 CRUD/删除路径保留原样 —— 本仓库只为生命周期联动提供
 * 精准失活通道，不干扰巩固与检索原有逻辑。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Repository
public class ProceduralMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(ProceduralMemoryRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public ProceduralMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 将指定源实体关联的仍处于活跃状态（{@code deactivated_reason IS NULL}）的操作模板
     * 批量失活，写入理由。
     *
     * <p>{@code source_entity_id} 为空的模板不受影响；此方法不涉及向量清理 ——
     * {@code VectorListener} 会独立处理 L3 实体向量。</p>
     *
     * @param sourceEntityId 来源实体 ID
     * @param reason         失活理由
     * @return 实际被失活的记录数（0 表示无对应活跃模板，无需告警）
     */
    public int deactivateBySourceEntity(String sourceEntityId, String reason) {
        int affected = jdbcTemplate.update(
                """
                UPDATE procedure_templates
                   SET deactivated_reason = ?
                 WHERE source_entity_id = ?
                   AND deactivated_reason IS NULL
                """,
                reason, sourceEntityId);
        if (affected > 0) {
            log.debug("L4 操作模板失活: sourceEntityId={}, reason={}, affected={}",
                    sourceEntityId, reason, affected);
        }
        return affected;
    }

    /**
     * 测试辅助：列出指定源实体对应的操作模板 ID。
     *
     * @param sourceEntityId 来源实体 ID
     * @return 模板 ID 列表（可能为空）
     */
    public List<String> findTemplateIdsBySourceEntity(String sourceEntityId) {
        return jdbcTemplate.queryForList(
                "SELECT template_id FROM procedure_templates WHERE source_entity_id = ?",
                String.class, sourceEntityId);
    }
}
