package com.lifepilot.memory.consumption.attention;

import com.lifepilot.memory.consumption.config.MemoryConsumptionProperties;
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
import org.springframework.lang.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 记忆注意力端到端集成测试（Flyway + SQLite + 真实 SemanticMemory + GraphReasoner + MemoryAttentionService）。
 *
 * <p>覆盖：SemanticMemory 时间查询（临近到期 / 停滞高价值）+ 注意力聚合四类信号。</p>
 *
 * @author zsg
 * @since 2026-06-07
 */
@DisplayName("记忆注意力集成测试")
class 记忆注意力_集成测试 {

    private static final String SPACE_ID = "memory-space-personal-default";
    private static final Instant NOW = Instant.parse("2026-06-07T00:00:00Z");
    private static final String NOW_S = NOW.toString();
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private GraphReasoner reasoner;
    private MemoryAttentionService attentionService;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-attention-" + dbId + ".db");
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
        attentionService = new MemoryAttentionService(
                semanticMemory, reasoner, new MemoryConsumptionProperties(), CLOCK);
    }

    @AfterEach
    void 清理() throws Exception {
        if (dataSource != null) dataSource.destroy();
        if (dbPath != null) Files.deleteIfExists(dbPath);
    }

    @Test
    void findApproachingExpiry只返回窗口内到期实体() {
        插入实体("g-violin", "学小提琴", EntityType.GOAL, 0.7f, NOW.plus(Duration.ofDays(3)), NOW);
        插入实体("g-far", "远期目标", EntityType.GOAL, 0.7f, NOW.plus(Duration.ofDays(60)), NOW);
        插入实体("g-none", "无期限目标", EntityType.GOAL, 0.7f, null, NOW);

        var list = semanticMemory.findApproachingExpiry(
                NOW.plus(Duration.ofDays(14)), EnumSet.of(EntityType.GOAL), null);

        assertThat(list).extracting(e -> e.id()).containsExactly("g-violin");
    }

    @Test
    void findNeglected只返回停滞高价值实体() {
        插入实体("p-rec", "推荐系统项目", EntityType.PROJECT, 0.85f, null, NOW.minus(Duration.ofDays(60)));
        插入实体("p-active", "活跃项目", EntityType.PROJECT, 0.85f, null, NOW.minus(Duration.ofDays(2)));
        插入实体("p-lowimp", "低价值老项目", EntityType.PROJECT, 0.3f, null, NOW.minus(Duration.ofDays(90)));

        var list = semanticMemory.findNeglected(
                EnumSet.of(EntityType.PROJECT), NOW.minus(Duration.ofDays(30)), 0.6f, null);

        assertThat(list).extracting(e -> e.id()).containsExactly("p-rec");
    }

    @Test
    void computeAttention聚合四类信号() {
        // EXPIRING
        插入实体("g-violin", "学小提琴", EntityType.GOAL, 0.7f, NOW.plus(Duration.ofDays(3)), NOW);
        // NEGLECTED + 作为连接种子
        插入实体("p-rec", "推荐系统项目", EntityType.PROJECT, 0.85f, null, NOW.minus(Duration.ofDays(60)));
        // 连接链：推荐系统项目 -相关-> 机器学习 -相关-> 深度学习（深度学习为连接机会）
        插入实体("ml", "机器学习", EntityType.TOPIC, 0.5f, null, NOW);
        插入实体("dl", "深度学习", EntityType.TOPIC, 0.5f, null, NOW);
        semanticMemory.addRelation(关系("p-rec", "ml", "相关"));
        semanticMemory.addRelation(关系("ml", "dl", "相关"));

        var items = attentionService.computeAttention(null, 20);

        assertThat(items).anyMatch(i -> i.kind() == MemoryAttentionService.AttentionKind.EXPIRING
                && i.entityId().equals("g-violin"));
        assertThat(items).anyMatch(i -> i.kind() == MemoryAttentionService.AttentionKind.NEGLECTED
                && i.entityId().equals("p-rec"));
        assertThat(items).anyMatch(i -> i.kind() == MemoryAttentionService.AttentionKind.CONNECTION
                && i.entityId().equals("dl"));
    }

    private TemporalRelation 关系(String src, String tgt, String type) {
        return new TemporalRelation(UUID.randomUUID().toString(), src, tgt, type, 0.8f, null, NOW, null, "test", NOW);
    }

    private void 插入实体(String id, String name, EntityType type, float importance,
                          @Nullable Instant expiresAt, @Nullable Instant lastAccessed) {
        jdbcTemplate.update(
                """
                INSERT INTO memory_entities(
                    id, space_id, memory_scope, entity_type, canonical_name, normalized_name,
                    reality_type, status, access_count, last_accessed_at, first_seen_at, last_seen_at,
                    created_at, updated_at, lifecycle_state, expires_at, temporality,
                    evidence_kind, trust_level, trust_score, evidence_count
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id, SPACE_ID, "USER_FACT", type.name(), name, name.toLowerCase(),
                "UNKNOWN", "ACTIVE", 0,
                lastAccessed != null ? lastAccessed.toString() : null,
                NOW_S, NOW_S, NOW_S, NOW_S,
                LifecycleState.ACTIVE.name(),
                expiresAt != null ? expiresAt.toString() : null,
                Temporality.PERSISTENT.name(),
                MemoryEvidenceKind.USER_CONFIRMED.name(), MemoryTrustLevel.EXPLICIT.name(), 0.9f, 1);
        jdbcTemplate.update(
                """
                INSERT INTO memory_entity_versions(
                    id, entity_id, version_no, description, properties_json,
                    extraction_confidence, importance_score, is_current,
                    valid_from, valid_to, created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id + "-v1", id, 1, name + "描述", "{}", 0.9f, importance, 1, NOW_S, null, NOW_S, NOW_S);
    }
}
