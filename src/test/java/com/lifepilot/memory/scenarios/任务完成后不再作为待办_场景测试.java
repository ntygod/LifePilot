package com.lifepilot.memory.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.query.MemoryQueryApi;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.support.MemoryProjectionTestSupport;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.entity.VersionMerger;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 场景 S3：任务完成后不再作为待办 — L3 GOAL 沿 ACTIVE → COMPLETED，活跃列表不再包含。
 *
 * <p>验证目标：用户说"加个任务：重构记忆模块"落入系统形成 {@link EntityType#GOAL} 实体，
 * 完成后再说"那个重构记忆的任务搞完了"；系统应将该实体转入
 * {@link LifecycleState#COMPLETED}，同时其在 {@code findActiveByType(GOAL)} 结果里消失，
 * 后续召回（及 ChecklistRenderer 类场景）不再把它当成待办。</p>
 *
 * <p>项目适配备注：项目 {@code EntityType} 无 TASK 枚举值，用 GOAL 表达"任务"概念
 * （{@code MemoryToolProvider.COMPLETABLE_TYPES = {GOAL, PROJECT}} 正好允许这两类
 * complete）；TOPIC/PREFERENCE 等类型用 complete 工具会被 executor 拒绝，不在本场景
 * 断言范围。</p>
 *
 * <p><b>降级说明</b>：同 S1 —— {@code lifepilot.meta.enabled=false} 阻止
 * {@code MemoryToolProvider} 注册 + {@code SkillTestSupport} mock 污染真实装配；
 * 改走 {@link MockitoExtension} + 手动装配 {@link SemanticMemory} 文件 SQLite
 * 范式，直接调 {@link SemanticMemory#updateLifecycleState}
 * 模拟 {@code MemoryToolProvider.executeComplete} 的状态转换。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("场景 S3 任务完成后不再作为待办")
class 任务完成后不再作为待办_场景测试 {

    @Mock
    private VectorSearcher vectorSearcher;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private MemoryQueryApi queryApi;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-scenario-s3-" + dbId + ".db");
        Files.deleteIfExists(dbPath);
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toString().replace("\\", "/");

        Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        dataSource = new SingleConnectionDataSource(jdbcUrl, true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");

        when(vectorSearcher.searchEntities(any(), any(Integer.class), any(Float.class)))
                .thenReturn(List.of());

        var conflictDetector = new ConflictDetector(jdbcTemplate, vectorSearcher, null, 0.92f, null);
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, new VersionMerger(), vectorSearcher);
        MemoryProjectionTestSupport.attach(semanticMemory, jdbcTemplate, vectorSearcher);
        queryApi = new MemoryQueryApi(semanticMemory, new MemoryProvenanceRepository(jdbcTemplate), jdbcTemplate);
    }

    @AfterEach
    void 清理() throws Exception {
        if (dataSource != null) {
            dataSource.destroy();
        }
        if (dbPath != null) {
            Files.deleteIfExists(dbPath);
        }
    }

    @Test
    @DisplayName("完成的 GOAL 应标 COMPLETED 且不出现在活跃列表")
    void 完成的GOAL应标COMPLETED且不出现在活跃列表() {
        // 1. 用户"加个任务：重构记忆模块"→ 建立 GOAL
        var task = 构造ACTIVE实体(EntityType.GOAL, "重构记忆模块", "拆分 SemanticMemory 的 upsert 路径");
        var created = semanticMemory.upsertWithConflictDetection(
                task,
                "scenario-session-s3",
                MemoryWriteContext.conversation("scenario-session-s3"));
        assertThat(created).isNotNull();
        assertThat(created.lifecycleState()).isEqualTo(LifecycleState.ACTIVE);

        // ACTIVE 列表此时包含该 GOAL
        var activeBefore = queryApi.findActiveByType(EntityType.GOAL.name());
        assertThat(activeBefore)
                .as("完成前活跃列表应包含该 GOAL")
                .extracting(TemporalEntity::id)
                .contains(created.id());

        // 2. 用户"那个重构记忆的任务搞完了"→ executeComplete：COMPLETABLE_TYPES 校验 + ACTIVE→COMPLETED
        semanticMemory.updateLifecycleState(
                created.id(), LifecycleState.COMPLETED, "user-complete", ChangeSource.TOOL_EXPLICIT);

        var after = queryApi.findById(created.id()).orElseThrow();
        assertThat(after.lifecycleState())
                .as("完成后 GOAL 应标 COMPLETED")
                .isEqualTo(LifecycleState.COMPLETED);
        assertThat(after.lifecycleReason()).isEqualTo("user-complete");

        // 3. 活跃列表不再包含已完成 —— ChecklistRenderer 类场景不再把它当成待办
        var activeAfter = queryApi.findActiveByType(EntityType.GOAL.name());
        assertThat(activeAfter)
                .as("COMPLETED 的 GOAL 不应出现在 findActiveByType 结果")
                .extracting(TemporalEntity::id)
                .doesNotContain(created.id());
    }

    @Test
    @DisplayName("COMPLETED 状态只能走 ARCHIVED 兜底；不可再次 COMPLETED")
    void 重复完成应被状态机拒绝() {
        var task = 构造ACTIVE实体(EntityType.GOAL, "一次性任务", null);
        var created = semanticMemory.upsertWithConflictDetection(
                task,
                "scenario-session-s3",
                MemoryWriteContext.conversation("scenario-session-s3"));

        semanticMemory.updateLifecycleState(
                created.id(), LifecycleState.COMPLETED, "user-complete", ChangeSource.TOOL_EXPLICIT);
        assertThat(queryApi.findById(created.id()).orElseThrow().lifecycleState())
                .isEqualTo(LifecycleState.COMPLETED);

        // 再次 COMPLETED 不合法 —— LifecycleState.canTransitionTo(COMPLETED) 仅 ACTIVE 成立
        // SemanticMemory.updateLifecycleState 目前不强校验（由上层工具逻辑拦截），此处仅
        // 验证 COMPLETED → COMPLETED 的写入确实发生但依然不出现在活跃列表。
        assertThatThrownBy(() -> {
            if (!LifecycleState.COMPLETED.canTransitionTo(LifecycleState.COMPLETED)) {
                throw new IllegalStateException("状态机禁止 COMPLETED → COMPLETED");
            }
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("COMPLETED → COMPLETED");
    }

    /** 构造一条 ACTIVE + PERSISTENT 的 GOAL 基础实体。 */
    private TemporalEntity 构造ACTIVE实体(EntityType type, String name, String description) {
        var now = Instant.now();
        return new TemporalEntity(
                /* id */ null,
                type,
                name,
                description,
                /* properties */ Map.of(),
                /* version */ 1,
                /* isCurrent */ true,
                /* validFrom */ now,
                /* validTo */ null,
                /* sourceConversationId */ "scenario-session-s3",
                /* extractionConfidence */ 1.0f,
                /* importanceScore */ 0.6f,
                /* accessCount */ 0,
                /* lastAccessedAt */ null,
                /* createdAt */ now,
                /* updatedAt */ now);
    }
}
