package com.lifepilot.memory.consumption.attention;

import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.semantic.TemporalRelation;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * GraphReasoner 集成测试（Flyway + SQLite）—— 多跳路径 + 连接机会。
 *
 * @author zsg
 * @since 2026-06-07
 */
@DisplayName("GraphReasoner 集成测试")
class GraphReasoner_集成测试 {

    private static final String SPACE_ID = "memory-space-personal-default";
    private static final String NOW = "2026-06-07T00:00:00Z";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private GraphReasoner reasoner;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-graph-reasoner-" + dbId + ".db");
        Files.deleteIfExists(dbPath);
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toString().replace("\\", "/");
        Flyway.configure().dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration").load().migrate();
        dataSource = new SingleConnectionDataSource(jdbcUrl, true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");
        semanticMemory = new SemanticMemory(jdbcTemplate, mock(ConflictDetector.class),
                new VersionMerger(), mock(VectorSearcher.class));
        reasoner = new GraphReasoner(jdbcTemplate, 25);
    }

    @AfterEach
    void 清理() throws Exception {
        if (dataSource != null) dataSource.destroy();
        if (dbPath != null) Files.deleteIfExists(dbPath);
    }

    @Test
    void 多跳路径应带关系类型() {
        插入实体("张三", EntityType.PERSON);
        插入实体("阿里", EntityType.ORGANIZATION);
        插入实体("杭州", EntityType.PLACE);
        semanticMemory.addRelation(关系("张三", "阿里", "就职于"));
        semanticMemory.addRelation(关系("阿里", "杭州", "位于"));

        var paths = reasoner.pathsFrom("张三", 2, null);

        // 1 跳：张三->阿里；2 跳：张三->阿里->杭州
        assertThat(paths).anySatisfy(p -> {
            assertThat(p.depth()).isEqualTo(1);
            assertThat(p.endId()).isEqualTo("阿里");
            assertThat(p.hops().getFirst().relationType()).isEqualTo("就职于");
        });
        assertThat(paths).anySatisfy(p -> {
            assertThat(p.depth()).isEqualTo(2);
            assertThat(p.endId()).isEqualTo("杭州");
        });
    }

    @Test
    void 连接机会应为两跳可达且无直接边() {
        插入实体("张三", EntityType.PERSON);
        插入实体("阿里", EntityType.ORGANIZATION);
        插入实体("杭州", EntityType.PLACE);
        semanticMemory.addRelation(关系("张三", "阿里", "就职于"));
        semanticMemory.addRelation(关系("阿里", "杭州", "位于"));

        var opps = reasoner.connectionOpportunities("张三", null);

        assertThat(opps).singleElement().satisfies(o -> {
            assertThat(o.toId()).isEqualTo("杭州");
            assertThat(o.bridgeId()).isEqualTo("阿里");
            assertThat(o.firstRelation()).isEqualTo("就职于");
            assertThat(o.secondRelation()).isEqualTo("位于");
        });
    }

    @Test
    void 已有直接边的实体不算连接机会() {
        插入实体("张三", EntityType.PERSON);
        插入实体("阿里", EntityType.ORGANIZATION);
        插入实体("杭州", EntityType.PLACE);
        semanticMemory.addRelation(关系("张三", "阿里", "就职于"));
        semanticMemory.addRelation(关系("阿里", "杭州", "位于"));
        semanticMemory.addRelation(关系("张三", "杭州", "居住于"));  // 直接边

        var opps = reasoner.connectionOpportunities("张三", null);

        assertThat(opps).noneMatch(o -> o.toId().equals("杭州"));
    }

    private TemporalRelation 关系(String src, String tgt, String type) {
        var now = Instant.parse(NOW);
        return new TemporalRelation(UUID.randomUUID().toString(), src, tgt, type, 0.8f, null, now, null, "test", now);
    }

    private void 插入实体(String id, EntityType type) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count, first_seen_at, last_seen_at,
                    created_at, updated_at, lifecycle_state, temporality,
                    evidence_kind, trust_level, trust_score, evidence_count
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id, SPACE_ID, "USER_FACT", type.name(), id, id.toLowerCase(),
                "UNKNOWN", "ACTIVE", 0, NOW, NOW, NOW, NOW,
                LifecycleState.ACTIVE.name(), Temporality.PERSISTENT.name(),
                MemoryEvidenceKind.USER_CONFIRMED.name(), MemoryTrustLevel.EXPLICIT.name(), 0.9f, 1);
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_versions(
                    id, entity_id, version_no, description, properties_json,
                    extraction_confidence, importance_score, is_current,
                    valid_from, valid_to, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id + "-v1", id, 1, id + "描述", "{}", 0.9f, 0.8f, 1, NOW, null, NOW, NOW);
    }
}
