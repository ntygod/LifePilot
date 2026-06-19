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
import com.lifepilot.memory.store.scope.MemoryWriteContext;
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
 * GraphReasoner 关系质量门集成测试（relation-quality-gate Property 2/3，Flyway V1-V5 + SQLite）。
 *
 * @author zsg
 * @since 2026-06-07
 */
@DisplayName("GraphReasoner 关系质量门集成测试")
class GraphReasoner_关系质量门_集成测试 {

    private static final String SPACE_ID = "memory-space-personal-default";
    private static final String NOW = "2026-06-07T00:00:00Z";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-rel-quality-" + dbId + ".db");
        Files.deleteIfExists(dbPath);
        String jdbcUrl = "jdbc:sqlite:" + dbPath.toString().replace("\\", "/");
        Flyway.configure().dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration").load().migrate();
        dataSource = new SingleConnectionDataSource(jdbcUrl, true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");
        semanticMemory = new SemanticMemory(jdbcTemplate, mock(ConflictDetector.class),
                new VersionMerger(), mock(VectorSearcher.class));
    }

    @AfterEach
    void 清理() throws Exception {
        if (dataSource != null) dataSource.destroy();
        if (dbPath != null) Files.deleteIfExists(dbPath);
    }

    @Test
    void 关系质量字段读写往返一致() {
        插入实体("张三", EntityType.PERSON);
        插入实体("阿里", EntityType.ORGANIZATION);
        var rel = 关系("张三", "阿里", "就职于")
                .withQuality(MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.EXPLICIT, 0.88f);
        semanticMemory.addRelation(rel, relationContext());

        Float trust = jdbcTemplate.queryForObject(
                "SELECT trust_score FROM temporal_relations WHERE id = ?", Float.class, rel.id());
        String kind = jdbcTemplate.queryForObject(
                "SELECT evidence_kind FROM temporal_relations WHERE id = ?", String.class, rel.id());
        assertThat(trust).isEqualTo(0.88f);
        assertThat(kind).isEqualTo("USER_CONFIRMED");
    }

    @Test
    void 门控滤掉低可信桥边() {
        插入实体("张三", EntityType.PERSON);
        插入实体("阿里", EntityType.ORGANIZATION);
        插入实体("杭州", EntityType.PLACE);
        // 张三->阿里 高可信；阿里->杭州 低可信(0.2)
        semanticMemory.addRelation(
                关系("张三", "阿里", "就职于")
                        .withQuality(MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.EXPLICIT, 0.9f),
                relationContext());
        semanticMemory.addRelation(
                关系("阿里", "杭州", "位于")
                        .withQuality(MemoryEvidenceKind.CHAT_INFERRED, MemoryTrustLevel.INFERRED, 0.2f),
                relationContext());

        // 阈值 0.5：低可信桥边被滤 → 不可达杭州
        var gated = new GraphReasoner(jdbcTemplate, 25, 0.5f);
        assertThat(gated.connectionOpportunities("张三", null))
                .noneMatch(o -> o.toId().equals("杭州"));

        // 阈值 0.0：不过滤 → 可达杭州
        var open = new GraphReasoner(jdbcTemplate, 25, 0.0f);
        assertThat(open.connectionOpportunities("张三", null))
                .anyMatch(o -> o.toId().equals("杭州"));
    }

    @Test
    void 历史NULL可信关系恒放行() {
        插入实体("张三", EntityType.PERSON);
        插入实体("阿里", EntityType.ORGANIZATION);
        插入实体("杭州", EntityType.PLACE);
        // 阿里->杭州 用便捷构造器（trust_score 默认写 0），手动改为 NULL 模拟历史数据
        semanticMemory.addRelation(
                关系("张三", "阿里", "就职于")
                        .withQuality(MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.EXPLICIT, 0.9f),
                relationContext());
        var historical = 关系("阿里", "杭州", "位于");
        semanticMemory.addRelation(historical, relationContext());
        jdbcTemplate.update("UPDATE memory_relations SET trust_score = NULL WHERE id = ?", historical.id());

        // 阈值 0.5：历史 NULL 边仍放行 → 可达杭州
        var gated = new GraphReasoner(jdbcTemplate, 25, 0.5f);
        assertThat(gated.connectionOpportunities("张三", null))
                .anyMatch(o -> o.toId().equals("杭州"));
    }

    private TemporalRelation 关系(String src, String tgt, String type) {
        var now = Instant.parse(NOW);
        return new TemporalRelation(UUID.randomUUID().toString(), src, tgt, type, 0.8f, null, now, null, "test", now);
    }

    private MemoryWriteContext relationContext() {
        return MemoryWriteContext.unknown("test-relation");
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
