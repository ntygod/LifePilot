package com.lifepilot.memory.repository;

import com.lifepilot.memory.lifecycle.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * {@code memory_entity_provenances} 生命周期闭环专用访问层 —— 仅承载 V15 新增的失效标记列
 * （{@code status} / {@code invalidated_at}）与按来源查实体 ID 的辅助。
 *
 * <p>通用明细查询仍在 {@link com.lifepilot.interaction.web.repository.MemoryProvenanceRepository}
 * 中；这里服务于 {@code ProvenanceStaleListener} / {@code ReValidationListener} 等生命周期组件。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Repository
public class MemoryEntityProvenanceRepository {

    private static final Logger log = LoggerFactory.getLogger(MemoryEntityProvenanceRepository.class);

    private final JdbcTemplate jdbc;

    public MemoryEntityProvenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 将指定来源对象关联的 provenance 记录全部置为 STALE —— 源对象失效 / 删除时调用。
     *
     * @param type     来源对象类型
     * @param sourceId 来源对象主键
     * @param when     失效时刻
     */
    public void markStale(SourceType type, String sourceId, Instant when) {
        String column = sourceColumn(type);
        int affected = jdbc.update(
                "UPDATE memory_entity_provenances SET status = 'STALE', invalidated_at = ? WHERE "
                        + column + " = ?",
                when.toString(), sourceId);
        log.debug("记忆溯源仓库: markStale type={}, sourceId={}, affected={}", type, sourceId, affected);
    }

    /**
     * 查找所有由指定来源对象贡献过 provenance 的实体 ID（去重）。
     *
     * @param type     来源对象类型
     * @param sourceId 来源对象主键
     * @return 实体 ID 列表（可能为空）
     */
    public List<String> findEntityIdsBySource(SourceType type, String sourceId) {
        String column = sourceColumn(type);
        return jdbc.queryForList(
                "SELECT DISTINCT entity_id FROM memory_entity_provenances WHERE " + column + " = ?",
                String.class, sourceId);
    }

    private String sourceColumn(SourceType type) {
        return switch (type) {
            case DOCUMENT -> "source_document_id";
            case KNOWLEDGE_BASE -> "source_knowledge_base_id";
            case SESSION -> "source_conversation_id";
        };
    }
}
