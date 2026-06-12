package com.lifepilot.memory.semantic;

import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.VersionMerger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link SemanticMemory} 图遍历集成测试。
 *
 * @author zsg
 * @since 2026-05-05
 */
@DisplayName("SemanticMemory 图遍历集成测试")
class SemanticMemory_图遍历集成测试 {

    private static final String SPACE_ID = "memory-space-personal-default";
    private static final String NOW = "2026-05-05T00:00:00Z";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-sm-graph-" + dbId + ".db");
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

        semanticMemory = new SemanticMemory(
                jdbcTemplate,
                mock(ConflictDetector.class),
                new VersionMerger(),
                mock(VectorSearcher.class));
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
    void 反向一跳关系应返回源实体且保留质量字段() {
        插入实体("entity-a", EntityType.PERSON, "源实体", LifecycleState.ACTIVE);
        插入实体("entity-b", EntityType.PROJECT, "目标实体", LifecycleState.ACTIVE);
        插入关系("rel-a-b", "entity-a", "entity-b");

        var related = semanticMemory.findRelated("entity-b", 1);

        assertThat(related).singleElement().satisfies(entity -> {
            assertThat(entity.id()).isEqualTo("entity-a");
            assertThat(entity.evidenceKind()).isEqualTo(MemoryEvidenceKind.USER_CONFIRMED);
            assertThat(entity.trustLevel()).isEqualTo(MemoryTrustLevel.EXPLICIT);
            assertThat(entity.trustScore()).isEqualTo(0.9f);
        });
    }

    @Test
    void 图遍历不应返回不可召回生命周期实体() {
        插入实体("entity-a", EntityType.PERSON, "源实体", LifecycleState.ACTIVE);
        插入实体("entity-cancelled", EntityType.PROJECT, "已取消项目", LifecycleState.CANCELLED);
        插入关系("rel-a-cancelled", "entity-a", "entity-cancelled");

        var related = semanticMemory.findRelated("entity-a", 1);

        assertThat(related).isEmpty();
    }

    @Test
    void 关系根记录归档后不应参与当前图读取() {
        插入实体("entity-a", EntityType.PERSON, "源实体", LifecycleState.ACTIVE);
        插入实体("entity-b", EntityType.PROJECT, "目标实体", LifecycleState.ACTIVE);
        插入关系("rel-archived", "entity-a", "entity-b");
        jdbcTemplate.update("UPDATE memory_relations SET status = 'ARCHIVED' WHERE id = ?", "rel-archived");

        assertThat(semanticMemory.findRelated("entity-a", 1)).isEmpty();
        assertThat(semanticMemory.findRelationsByEntityId("entity-a")).isEmpty();
        assertThat(semanticMemory.countCurrentRelations()).isZero();
    }

    private void 插入实体(String id, EntityType type, String name, LifecycleState lifecycleState) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count, first_seen_at, last_seen_at,
                    created_at, updated_at, lifecycle_state, temporality,
                    evidence_kind, trust_level, trust_score, evidence_count
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id,
                SPACE_ID,
                "USER_FACT",
                type.name(),
                name,
                name.toLowerCase(),
                "UNKNOWN",
                "ACTIVE",
                0,
                NOW,
                NOW,
                NOW,
                NOW,
                lifecycleState.name(),
                Temporality.PERSISTENT.name(),
                MemoryEvidenceKind.USER_CONFIRMED.name(),
                MemoryTrustLevel.EXPLICIT.name(),
                0.9f,
                1);
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_versions(
                    id, entity_id, version_no, description, properties_json,
                    extraction_confidence, importance_score, is_current,
                    valid_from, valid_to, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id + "-v1",
                id,
                1,
                name + "描述",
                "{}",
                0.9f,
                0.8f,
                1,
                NOW,
                null,
                NOW,
                NOW);
    }

    private void 插入关系(String id, String sourceEntityId, String targetEntityId) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_relations(
                    id, space_id, source_entity_id, target_entity_id, relation_type,
                    reality_type, status, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?)
                """,
                id,
                SPACE_ID,
                sourceEntityId,
                targetEntityId,
                "RELATED_TO",
                "UNKNOWN",
                "ACTIVE",
                NOW,
                NOW);
        jdbcTemplate.update(
                """
                INSERT INTO memory_relation_versions(
                    id, relation_id, version_no, strength, properties_json,
                    is_current, valid_from, valid_to, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,
                id + "-v1",
                id,
                1,
                0.8f,
                "{}",
                1,
                NOW,
                null,
                NOW,
                NOW);
    }
}
