package com.lifepilot.memory.scenarios;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.governance.lifecycle.query.MemoryQueryApi;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.support.MemoryProjectionTestSupport;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.entity.VersionMerger;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 场景 S16：{@code updateDescription} 走版本化路径（修补 A）。
 *
 * <p>验证手动编辑描述不再直接覆盖当前版本的 SQL，而是关闭当前版本 + 插入 version+1
 * 新版本；两次连续编辑应产生两条新的版本记录，加上原始 v1 共 3 条。</p>
 *
 * <p>装配方式复用 {@code SemanticMemory_生命周期字段_集成测试} 的文件 SQLite + 真跑
 * Flyway 迁移（V1 → V16）范式，手动装配 SemanticMemory 与 MemoryQueryApi，规避
 * {@code @SpringBootTest} 被 SkillTestSupport mock 污染。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("场景 S16 updateDescription 版本化")
class updateDescription版本化_单元测试 {

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
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-sm-upd-desc-" + dbId + ".db");
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

        // 冲突检测阶段的向量匹配统一返回空命中，避免误触发 LLM 消歧义路径
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
    void 两次修改描述应产生两条新版本记录() {
        // given：写入一条 GOAL 实体，初始 v1
        var initial = 构造ACTIVE实体("goal-版本化", EntityType.GOAL, "初始描述");
        var persisted = semanticMemory.upsertWithConflictDetection(initial, null, MemoryWriteContext.unknown(null));

        // when：连续两次修改描述
        semanticMemory.updateDescription(persisted.id(), "v2 描述");
        semanticMemory.updateDescription(persisted.id(), "v3 描述");

        // then：共三条版本记录（v1 初始 + v2 + v3），描述依次命中
        var versions = queryApi.findAllVersions(persisted.id());
        assertThat(versions)
                .as("版本数：v1 初始 + v2 + v3 = 3")
                .hasSize(3);
        assertThat(versions)
                .extracting(TemporalEntity::description)
                .containsExactly("初始描述", "v2 描述", "v3 描述");
        assertThat(versions)
                .extracting(TemporalEntity::version)
                .containsExactly(1, 2, 3);
        // 只有最新版本 is_current=1
        assertThat(versions)
                .filteredOn(TemporalEntity::isCurrent)
                .hasSize(1)
                .allSatisfy(e -> {
                    assertThat(e.version()).isEqualTo(3);
                    assertThat(e.description()).isEqualTo("v3 描述");
                });
        // 历史版本 valid_to 均非空
        assertThat(versions.subList(0, 2))
                .allSatisfy(e -> assertThat(e.validTo()).isNotNull());
    }

    @Test
    void 描述未变化时应短路不产生新版本() {
        var initial = 构造ACTIVE实体("goal-短路", EntityType.GOAL, "相同描述");
        var persisted = semanticMemory.upsertWithConflictDetection(initial, null, MemoryWriteContext.unknown(null));

        semanticMemory.updateDescription(persisted.id(), "相同描述");

        var versions = queryApi.findAllVersions(persisted.id());
        assertThat(versions).hasSize(1);
        assertThat(versions.getFirst().version()).isEqualTo(1);
    }

    @Test
    void 描述变短时仍应产生新版本() {
        // 覆盖 VersionMerger 取更长者启发式的反例
        var initial = 构造ACTIVE实体("goal-变短", EntityType.PREFERENCE, "这是一段很长的初始描述，用于验证缩短场景");
        var persisted = semanticMemory.upsertWithConflictDetection(initial, null, MemoryWriteContext.unknown(null));

        semanticMemory.updateDescription(persisted.id(), "短");

        var versions = queryApi.findAllVersions(persisted.id());
        assertThat(versions).hasSize(2);
        assertThat(versions.getLast().description()).isEqualTo("短");
        assertThat(versions.getLast().version()).isEqualTo(2);
        assertThat(versions.getLast().isCurrent()).isTrue();
    }

    /** 构造默认 ACTIVE + PERSISTENT 的基础实体，承载指定描述。 */
    private TemporalEntity 构造ACTIVE实体(String id, EntityType type, String description) {
        var now = Instant.now();
        return new TemporalEntity(
                id,
                type,
                "测试实体-" + id,
                description,
                Map.of(),
                1,
                true,
                now,
                null,
                null,
                1.0f,
                0.5f,
                0,
                null,
                now,
                now,
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                false,
                List.of()
        );
    }
}
