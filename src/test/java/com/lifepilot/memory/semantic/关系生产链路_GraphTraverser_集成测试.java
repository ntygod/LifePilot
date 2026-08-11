package com.lifepilot.memory.semantic;

import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.store.support.SemanticMemoryTestSupport;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.retrieval.GraphTraverser;
import com.lifepilot.memory.retrieval.RankedItem;
import com.lifepilot.memory.retrieval.VectorSearcher;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 关系生产链路 → GraphTraverser 多跳召回集成测试。
 *
 * <p>验证：经 {@link SemanticMemory#addRelation} 真实写入关系后，
 * {@link GraphTraverser} 能完成 1~2 跳召回（depth=1 score=1.0 / depth=2 score=0.5），
 * 归档边不参与遍历；并覆盖新增的 {@code relationExists}/{@code existsCurrentById} 守卫。</p>
 *
 * @author zsg
 * @since 2026-06-06
 */
@DisplayName("关系生产链路 GraphTraverser 集成测试")
class 关系生产链路_GraphTraverser_集成测试 {

    private static final String SPACE_ID = "memory-space-personal-default";
    private static final String NOW = "2026-06-06T00:00:00Z";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private GraphTraverser graphTraverser;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-rel-graph-" + dbId + ".db");
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
                mock(VectorSearcher.class),
                SemanticMemoryTestSupport.memorySpaceRepository(jdbcTemplate), SemanticMemoryTestSupport.projectionService());
        graphTraverser = new GraphTraverser(jdbcTemplate);
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
    void addRelation写入后图遍历应返回1跳与2跳邻居() {
        插入实体("张三", EntityType.PERSON);
        插入实体("阿里", EntityType.ORGANIZATION);
        插入实体("杭州", EntityType.PLACE);

        semanticMemory.addRelation(关系("张三", "阿里", "就职于"), relationContext());
        semanticMemory.addRelation(关系("阿里", "杭州", "位于"), relationContext());

        var results = graphTraverser.traverse("张三", 10);

        assertThat(results).extracting(RankedItem::name).contains("阿里", "杭州");
        RankedItem 阿里 = results.stream().filter(r -> r.name().equals("阿里")).findFirst().orElseThrow();
        RankedItem 杭州 = results.stream().filter(r -> r.name().equals("杭州")).findFirst().orElseThrow();
        assertThat(阿里.score()).isEqualTo(1.0f);   // 1 跳
        assertThat(杭州.score()).isEqualTo(0.5f);   // 2 跳
    }

    @Test
    void 归档关系不参与图遍历() {
        插入实体("张三", EntityType.PERSON);
        插入实体("阿里", EntityType.ORGANIZATION);
        semanticMemory.addRelation(关系("张三", "阿里", "就职于"), relationContext());

        // 归档关系根记录
        jdbcTemplate.update("UPDATE memory_relations SET status = 'ARCHIVED' WHERE source_entity_id = ?", "张三");

        assertThat(graphTraverser.traverse("张三", 10)).isEmpty();
    }

    @Test
    void relationExists与existsCurrentById守卫应正确反映状态() {
        插入实体("张三", EntityType.PERSON);
        插入实体("阿里", EntityType.ORGANIZATION);

        assertThat(semanticMemory.existsCurrentById("张三")).isTrue();
        assertThat(semanticMemory.existsCurrentById("不存在")).isFalse();
        assertThat(semanticMemory.relationExists("张三", "阿里", "就职于")).isFalse();

        semanticMemory.addRelation(关系("张三", "阿里", "就职于"), relationContext());

        assertThat(semanticMemory.relationExists("张三", "阿里", "就职于")).isTrue();
        assertThat(semanticMemory.relationExists("张三", "阿里", "其他类型")).isFalse();
    }

    private TemporalRelation 关系(String src, String tgt, String type) {
        var now = Instant.parse(NOW);
        return new TemporalRelation(
                UUID.randomUUID().toString(), src, tgt, type, 0.8f, null, now, null, "test", now,
                MemoryEvidenceKind.USER_CONFIRMED, MemoryTrustLevel.EXPLICIT, 0.9f);
    }

    private MemoryWriteContext relationContext() {
        return MemoryWriteContext.consolidation("test-relation");
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
