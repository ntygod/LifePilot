package com.lifepilot.memory.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.projection.MemoryProjectionOutboxProcessor;
import com.lifepilot.memory.projection.MemoryProjectionOutboxRepository;
import com.lifepilot.memory.projection.MemoryProjectionService;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.scope.MemoryOriginType;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryRealityType;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.memory.scope.MemoryWriteContext;
import com.lifepilot.memory.semantic.AudnDecision;
import com.lifepilot.memory.semantic.AudnOperation;
import com.lifepilot.memory.semantic.ConflictDetector;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.MemoryExtractionCandidateRepository;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.VersionMerger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆治理契约集成测试。
 *
 * <p>覆盖项目 overlay、提取候选、投影 outbox 三条数据治理边界。</p>
 *
 * @author zsg
 * @since 2026-05-05
 */
class MemoryGovernanceContract_集成测试 {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcTemplate;
    private DataSource dataSource;
    private SemanticMemory semanticMemory;
    private VectorSearcher vectorSearcher;
    private MemoryProjectionOutboxRepository outboxRepository;
    private MemoryProjectionOutboxProcessor outboxProcessor;
    private MemoryExtractionCandidateRepository candidateRepository;
    private MemorySpaceRepository memorySpaceRepository;
    private SingleConnectionDataSource singleConnectionDataSource;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("memory-governance.db");
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toString().replace("\\", "/");
        Flyway.configure()
                .dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        singleConnectionDataSource = new SingleConnectionDataSource(jdbcUrl, true);
        dataSource = singleConnectionDataSource;
        jdbcTemplate = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper();
        vectorSearcher = mock(VectorSearcher.class);
        ConflictDetector conflictDetector = mock(ConflictDetector.class);
        when(conflictDetector.detectConflict(any(), any())).thenReturn(Optional.empty());

        memorySpaceRepository = new MemorySpaceRepository(jdbcTemplate, objectMapper);
        outboxRepository = new MemoryProjectionOutboxRepository(jdbcTemplate, objectMapper);
        outboxProcessor = new MemoryProjectionOutboxProcessor(outboxRepository, vectorSearcher, objectMapper);
        var projectionService = new MemoryProjectionService(outboxRepository, outboxProcessor);
        semanticMemory = new SemanticMemory(
                jdbcTemplate,
                conflictDetector,
                new VersionMerger(),
                vectorSearcher,
                memorySpaceRepository);
        semanticMemory.setProjectionService(projectionService);
        candidateRepository = new MemoryExtractionCandidateRepository(jdbcTemplate, objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (singleConnectionDataSource != null) {
            singleConnectionDataSource.destroy();
        }
    }

    @Test
    void 隔离项目更新继承记忆_创建overlay并遮蔽base() {
        String personalSpaceId = memorySpaceRepository.ensureDefaultPersonalSpace().id();
        String projectSpaceId = memorySpaceRepository.ensureProjectSpace("project-overlay").id();
        TemporalEntity base = semanticMemory.upsertWithConflictDetection(
                entity("base-pref", EntityType.PREFERENCE, "咖啡偏好", "喜欢拿铁"),
                "manual-edit",
                writeContext(personalSpaceId, MemoryScope.USER_PROFILE));

        TemporalEntity overlay = semanticMemory.upsertProjectOverlay(
                entity(null, EntityType.PREFERENCE, "咖啡偏好", "项目里改喝美式"),
                base,
                "tool-update",
                writeContext(projectSpaceId, null));

        Integer overlayRows = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM memory_entity_overlays
                WHERE overlay_entity_id = ?
                  AND base_entity_id = ?
                  AND overlay_space_id = ?
                """,
                Integer.class,
                overlay.id(),
                base.id(),
                projectSpaceId);
        assertThat(overlayRows).isEqualTo(1);

        var projectView = semanticMemory.findAllCurrent(
                MemoryReadFilter.of(List.of(projectSpaceId, personalSpaceId), List.of(MemoryScope.USER_PROFILE)));
        assertThat(projectView).extracting(TemporalEntity::id).contains(overlay.id());
        assertThat(projectView).extracting(TemporalEntity::id).doesNotContain(base.id());

        var baseAfterOverlay = semanticMemory.findById(base.id()).orElseThrow();
        assertThat(baseAfterOverlay.description()).isEqualTo("喜欢拿铁");
    }

    @Test
    void 投影outbox_主库事务回滚时不产生任务也不写向量() {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.execute(status -> {
            semanticMemory.upsertWithConflictDetection(
                    entity("rollback-entity", EntityType.PREFERENCE, "回滚偏好", "不会提交"),
                    "manual-edit",
                    writeContext(memorySpaceRepository.ensureDefaultPersonalSpace().id(), MemoryScope.USER_PROFILE));
            status.setRollbackOnly();
            return null;
        });

        assertThat(outboxRepository.countByStatus("PENDING")).isZero();
        assertThat(outboxRepository.countByStatus("PROCESSED")).isZero();
        verify(vectorSearcher, never()).upsertEntityVector(anyString(), anyString());
    }

    @Test
    void 投影outbox_消费失败时标记FAILED并保留错误() {
        String outboxId = outboxRepository.enqueue(
                "MEMORY_ENTITY",
                "entity-bad-vector",
                "VECTOR",
                "UPSERT",
                Map.of("entityId", "entity-bad-vector"));

        outboxProcessor.processOne(outboxId);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT status, attempt_count, last_error
                FROM memory_projection_outbox
                WHERE id = ?
                """,
                outboxId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("text");
    }

    @Test
    void 投影outbox_程序模板向量通过独立投影类型消费() {
        String upsertId = outboxRepository.enqueue(
                "PROCEDURE_TEMPLATE",
                "tpl-reminder",
                "PROCEDURE_TEMPLATE_VECTOR",
                "UPSERT",
                Map.of("entityId", "tpl-reminder", "text", "帮我创建提醒"));
        String deleteId = outboxRepository.enqueue(
                "PROCEDURE_TEMPLATE",
                "tpl-reminder",
                "PROCEDURE_TEMPLATE_VECTOR",
                "DELETE",
                Map.of("entityId", "tpl-reminder"));

        outboxProcessor.processOne(upsertId);
        outboxProcessor.processOne(deleteId);

        verify(vectorSearcher).upsertEntityVector("tpl-reminder", "帮我创建提醒");
        verify(vectorSearcher).deleteEntityVector("tpl-reminder");
        assertThat(outboxRepository.countByStatus("PROCESSED")).isEqualTo(2);
    }

    @Test
    void 提取候选_可记录并回写应用结果() {
        var decision = new AudnDecision(
                AudnOperation.ADD,
                "喜欢早起",
                EntityType.HABIT,
                "用户倾向早起处理重要任务",
                Map.of("time", "morning"),
                0.9f,
                0.7f);
        var ctx = writeContext(memorySpaceRepository.ensureDefaultPersonalSpace().id(), MemoryScope.USER_PROFILE);

        String candidateId = candidateRepository.recordValidated("session-candidate", ctx, decision);
        assertThat(candidateId).isNotBlank();

        candidateRepository.markApplied(candidateId, "entity-1", "base-1");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT candidate_status, persisted_entity_id, base_entity_id
                FROM memory_extraction_candidates
                WHERE id = ?
                """,
                candidateId);
        assertThat(row.get("candidate_status")).isEqualTo("APPLIED");
        assertThat(row.get("persisted_entity_id")).isEqualTo("entity-1");
        assertThat(row.get("base_entity_id")).isEqualTo("base-1");
    }

    @Test
    void 提取候选_质量门控拒绝时保留审计记录() {
        var decision = new AudnDecision(
                AudnOperation.ADD,
                "",
                EntityType.PERSON,
                null,
                Map.of(),
                0.1f,
                0.3f);
        var ctx = writeContext(memorySpaceRepository.ensureDefaultPersonalSpace().id(), MemoryScope.USER_PROFILE);

        String candidateId = candidateRepository.recordRejected(
                "session-rejected",
                ctx,
                decision,
                "INVALID_ENTITY_NAME");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT candidate_status, validation_status, rejection_reason
                FROM memory_extraction_candidates
                WHERE id = ?
                """,
                candidateId);
        assertThat(row.get("candidate_status")).isEqualTo("REJECTED");
        assertThat(row.get("validation_status")).isEqualTo("REJECTED");
        assertThat(row.get("rejection_reason")).isEqualTo("INVALID_ENTITY_NAME");
    }

    private TemporalEntity entity(String id, EntityType type, String name, String description) {
        Instant now = Instant.now();
        return new TemporalEntity(
                id,
                type,
                name,
                description,
                Map.of(),
                1,
                true,
                now,
                null,
                null,
                1.0f,
                0.6f,
                0,
                null,
                now,
                now);
    }

    private MemoryWriteContext writeContext(String spaceId, MemoryScope scope) {
        return new MemoryWriteContext(
                spaceId,
                scope,
                MemoryOriginType.MANUAL,
                MemoryRealityType.UNKNOWN,
                "manual-edit",
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
