package com.lifepilot.memory.lifecycle.query;

import com.lifepilot.memory.lifecycle.SourceType;
import com.lifepilot.memory.repository.MemoryEntityProvenanceRepository;
import com.lifepilot.memory.repository.MemoryEntityRepository;
import com.lifepilot.memory.semantic.MemoryEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 记忆只读查询接口（测试专用）。
 *
 * <p>生命周期闭环场景测试的统一断言入口，封装 {@link MemoryEntityRepository} 等底层仓库，
 * 只暴露 {@code findXxx} 只读方法；任何 mutator 请走 {@code SemanticMemory} 或对应 Repository。</p>
 *
 * <p>L4 相关查询（preference_rules / procedure_templates）当前仅占位：
 * 待 Task 15 {@code L4SyncListener} 引入 PreferenceRuleRepository / ProcedureTemplateRepository 后再接入。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Service
public class MemoryQueryApi {

    private final MemoryEntityRepository entityRepo;
    private final MemoryEntityProvenanceRepository provenanceRepo;

    public MemoryQueryApi(MemoryEntityRepository entityRepo,
                          MemoryEntityProvenanceRepository provenanceRepo) {
        this.entityRepo = entityRepo;
        this.provenanceRepo = provenanceRepo;
    }

    // ========== 实体查询 ==========

    /** 按 ID 查找实体。 */
    public Optional<MemoryEntity> findById(String id) {
        return entityRepo.findById(id);
    }

    /** 按类型找最新一条（按 {@code created_at} 降序）。 */
    public Optional<MemoryEntity> findLatestByType(String type) {
        return entityRepo.findLatestByType(type);
    }

    /** 语法糖：{@code findLatestByType("GOAL")} 的常用别名。必须存在，否则抛 {@code NoSuchElementException}。 */
    public String findLatestGoalId() {
        return entityRepo.findLatestByType("GOAL")
                .map(MemoryEntity::id)
                .orElseThrow();
    }

    /** 按类型找所有 ACTIVE 的实体。 */
    public List<MemoryEntity> findActiveByType(String type) {
        return entityRepo.findActiveByType(type);
    }

    // ========== Provenance 查询 ==========

    /** 查找指定来源对象关联的所有实体 ID（去重）。 */
    public List<String> findEntityIdsBySource(SourceType sourceType, String sourceId) {
        return provenanceRepo.findEntityIdsBySource(sourceType, sourceId);
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
}
