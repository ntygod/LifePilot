package com.lifepilot.memory.lifecycle.query;

import com.lifepilot.interaction.web.model.EntityProvenanceDto;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.SourceType;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 记忆只读查询接口（测试专用）。
 *
 * <p>生命周期闭环场景测试的统一断言入口，封装 {@link SemanticMemory} 与
 * {@link MemoryProvenanceRepository}，只暴露 {@code findXxx} 只读方法；任何 mutator
 * 请走 {@link SemanticMemory} 本身或 {@link MemoryProvenanceRepository}。</p>
 *
 * <p>L4 相关查询（preference_rules / procedure_templates）当前仅占位：
 * 待 Task 15 {@code L4SyncListener} 引入 PreferenceRuleRepository / ProcedureTemplateRepository 后再接入。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Service
public class MemoryQueryApi {

    private final SemanticMemory semanticMemory;
    private final MemoryProvenanceRepository provenanceRepository;

    public MemoryQueryApi(SemanticMemory semanticMemory,
                          MemoryProvenanceRepository provenanceRepository) {
        this.semanticMemory = semanticMemory;
        this.provenanceRepository = provenanceRepository;
    }

    // ========== 实体查询 ==========

    /** 按 ID 查找实体。 */
    public Optional<TemporalEntity> findById(String id) {
        return semanticMemory.findById(id);
    }

    /**
     * 按类型查找最新一条当前实体（按 {@code updatedAt} 降序）。
     *
     * <p>仅在当前有效版本（{@code is_current = 1}）范围内筛选，排除已归档实体。</p>
     */
    public Optional<TemporalEntity> findLatestByType(String typeName) {
        EntityType type = parseEntityType(typeName);
        if (type == null) {
            return Optional.empty();
        }
        return semanticMemory.findCurrentByType(type).stream()
                .max((a, b) -> a.updatedAt().compareTo(b.updatedAt()));
    }

    /** 语法糖：{@code findLatestByType("GOAL")} 的常用别名。必须存在，否则抛 {@code NoSuchElementException}。 */
    public String findLatestGoalId() {
        return findLatestByType(EntityType.GOAL.name())
                .map(TemporalEntity::id)
                .orElseThrow();
    }

    /** 按类型找所有生命周期处于 {@code ACTIVE} 的当前实体（按重要度降序）。 */
    public List<TemporalEntity> findActiveByType(String typeName) {
        EntityType type = parseEntityType(typeName);
        if (type == null) {
            return List.of();
        }
        return semanticMemory.findCurrentByType(type).stream()
                .filter(e -> e.lifecycleState() == LifecycleState.ACTIVE)
                .toList();
    }

    // ========== Provenance 查询 ==========

    /** 查找指定来源对象关联的所有实体 ID（去重）。 */
    public List<String> findEntityIdsBySource(SourceType sourceType, String sourceId) {
        return provenanceRepository.findEntityIdsBySource(sourceType, sourceId);
    }

    /**
     * 查询指定实体的所有 provenance 明细（按创建时间降序）。
     *
     * @param entityId 实体 ID
     * @return provenance 明细列表
     */
    public List<EntityProvenanceDto> findProvenancesByEntityId(String entityId) {
        return provenanceRepository.findEntityProvenances(entityId, null, null, null, null);
    }

    // ========== L4 查询（占位） ==========

    /**
     * 按来源实体 ID 查找 L4 偏好规则 —— 占位实现。
     *
     * @throws UnsupportedOperationException 等 Task 15 {@code L4SyncListener} 接入 PreferenceRuleRepository
     */
    public Object findRuleBySourceEntity(String sourceEntityId) {
        throw new UnsupportedOperationException(
                "L4 偏好规则查询将在 Task 15 L4SyncListener 接入 PreferenceRuleRepository 后实现");
    }

    /**
     * 按来源实体 ID 查找 L4 程序模板 —— 占位实现。
     *
     * @throws UnsupportedOperationException 等 Task 15 {@code L4SyncListener} 接入 ProcedureTemplateRepository
     */
    public Object findProcedureBySourceEntity(String sourceEntityId) {
        throw new UnsupportedOperationException(
                "L4 程序模板查询将在 Task 15 L4SyncListener 接入 ProcedureTemplateRepository 后实现");
    }

    // ========== 内部辅助 ==========

    /** 尝试解析实体类型字符串为枚举；非法值返回 null（调用方视作空结果）。 */
    private static EntityType parseEntityType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        try {
            return EntityType.valueOf(typeName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
