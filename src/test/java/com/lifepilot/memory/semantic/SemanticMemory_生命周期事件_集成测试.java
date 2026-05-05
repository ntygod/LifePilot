package com.lifepilot.memory.semantic;

import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.Temporality;
import com.lifepilot.memory.lifecycle.events.EntityLifecycleChanged;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.support.MemoryProjectionTestSupport;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * {@link SemanticMemory} 生命周期事件发布集成测试（Task 12 修补 C）。
 *
 * <p>覆盖三条发布路径：
 * <ul>
 *   <li>{@code archive(entity)} → ARCHIVED 事件（source=UI_EDIT）</li>
 *   <li>{@code upsertWithConflictDetection} 新建分支 → ACTIVE 事件（source 由 provenance 推断）</li>
 *   <li>{@code updateLifecycleState} 2-arg 兼容 + 4-arg 重载 → 对应 newState 事件</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticMemory 生命周期事件集成测试")
class SemanticMemory_生命周期事件_集成测试 {

    @Mock
    private VectorSearcher vectorSearcher;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private List<Object> captured;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-sm-lifecycle-evt-" + dbId + ".db");
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

        lenient().when(vectorSearcher.searchEntities(any(), any(Integer.class), any(Float.class)))
                .thenReturn(List.of());

        var conflictDetector = new ConflictDetector(jdbcTemplate, vectorSearcher, null, 0.92f, null);
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, new VersionMerger(), vectorSearcher);
        MemoryProjectionTestSupport.attach(semanticMemory, jdbcTemplate, vectorSearcher);

        captured = new ArrayList<>();
        semanticMemory.setEventPublisher(event -> captured.add(event));
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
    void archive应发布ARCHIVED事件且source为UI_EDIT() {
        var entity = 构造ACTIVE实体("entity-归档-1", EntityType.GOAL);
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null);
        captured.clear();   // 忽略 upsert 产生的 ACTIVE 事件

        semanticMemory.archive(persisted);

        var lifecycleEvents = captured.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .toList();
        assertThat(lifecycleEvents).hasSize(1);
        var event = lifecycleEvents.getFirst();
        assertThat(event.entityId()).isEqualTo(persisted.id());
        assertThat(event.entityType()).isEqualTo("GOAL");
        assertThat(event.oldState()).isEqualTo(LifecycleState.ACTIVE);
        assertThat(event.newState()).isEqualTo(LifecycleState.ARCHIVED);
        assertThat(event.source()).isEqualTo(ChangeSource.UI_EDIT);
    }

    @Test
    void archive带ChangeSource参数应透传到事件() {
        var entity = 构造ACTIVE实体("entity-归档-2", EntityType.EXPERIENCE);
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null);
        captured.clear();

        // 3-arg 重载：模拟 ForgettingEngine 的 CRON_EXPIRE 归档
        semanticMemory.archive(persisted, ChangeSource.CRON_EXPIRE);

        var lifecycleEvents = captured.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .toList();
        assertThat(lifecycleEvents).hasSize(1);
        var event = lifecycleEvents.getFirst();
        assertThat(event.entityId()).isEqualTo(persisted.id());
        assertThat(event.newState()).isEqualTo(LifecycleState.ARCHIVED);
        assertThat(event.source())
                .as("archive 的 3-arg 重载必须把 ChangeSource 透传到 LifecycleChanged 事件")
                .isEqualTo(ChangeSource.CRON_EXPIRE);
    }

    @Test
    void upsertWithConflictDetection新建分支应发布ACTIVE事件且oldState为null() {
        var entity = 构造ACTIVE实体("entity-新建-1", EntityType.PREFERENCE);

        var persisted = semanticMemory.upsertWithConflictDetection(entity, null);

        var lifecycleEvents = captured.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .toList();
        assertThat(lifecycleEvents).hasSize(1);
        var event = lifecycleEvents.getFirst();
        assertThat(event.entityId()).isEqualTo(persisted.id());
        assertThat(event.entityType()).isEqualTo("PREFERENCE");
        assertThat(event.oldState()).isNull();
        assertThat(event.newState()).isEqualTo(LifecycleState.ACTIVE);
        assertThat(event.source()).isEqualTo(ChangeSource.LLM_SEMANTIC);
    }

    @Test
    void upsert合并分支不应发布LifecycleChanged事件() {
        var entity = 构造ACTIVE实体("entity-合并-1", EntityType.GOAL);
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null);
        captured.clear();

        // 触发合并：描述变长，VersionMerger 判定 isNewVersion=true
        var longer = new TemporalEntity(
                persisted.id(), persisted.type(), persisted.name(),
                "更长的新描述用于触发 VersionMerger 认定有变化", persisted.properties(),
                persisted.version(), true, persisted.validFrom(), null,
                null, persisted.extractionConfidence(), persisted.importanceScore(),
                persisted.accessCount(), persisted.lastAccessedAt(),
                persisted.createdAt(), persisted.updatedAt(),
                persisted.lifecycleState(), persisted.lifecycleReason(), persisted.expiresAt(),
                persisted.temporality(), persisted.succeededBy(),
                persisted.isDerived(), persisted.derivationSources());
        semanticMemory.upsertWithConflictDetection(longer, null);

        assertThat(captured)
                .filteredOn(e -> e instanceof EntityLifecycleChanged)
                .as("合并分支不应产生生命周期事件")
                .isEmpty();
    }

    @Test
    void updateLifecycleState_2arg重载应发布事件且source默认TOOL_EXPLICIT() {
        var entity = 构造ACTIVE实体("entity-state-1", EntityType.GOAL);
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null);
        captured.clear();

        semanticMemory.updateLifecycleState(persisted.id(), LifecycleState.COMPLETED, "goal-done");

        var lifecycleEvents = captured.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .toList();
        assertThat(lifecycleEvents).hasSize(1);
        var event = lifecycleEvents.getFirst();
        assertThat(event.entityId()).isEqualTo(persisted.id());
        assertThat(event.oldState()).isEqualTo(LifecycleState.ACTIVE);
        assertThat(event.newState()).isEqualTo(LifecycleState.COMPLETED);
        assertThat(event.reason()).isEqualTo("goal-done");
        assertThat(event.source()).isEqualTo(ChangeSource.TOOL_EXPLICIT);
    }

    @Test
    void updateLifecycleState_4arg重载应透传自定义source() {
        var entity = 构造ACTIVE实体("entity-state-2", EntityType.EXPERIENCE);
        var persisted = semanticMemory.upsertWithConflictDetection(entity, null);
        captured.clear();

        semanticMemory.updateLifecycleState(
                persisted.id(), LifecycleState.SUPERSEDED, "replaced by e-2", ChangeSource.NEGATIVE_FEEDBACK);

        var lifecycleEvents = captured.stream()
                .filter(e -> e instanceof EntityLifecycleChanged)
                .map(e -> (EntityLifecycleChanged) e)
                .toList();
        assertThat(lifecycleEvents).hasSize(1);
        var event = lifecycleEvents.getFirst();
        assertThat(event.newState()).isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(event.source()).isEqualTo(ChangeSource.NEGATIVE_FEEDBACK);
        assertThat(event.reason()).isEqualTo("replaced by e-2");
    }

    @Test
    void updateLifecycleState未命中时不应发事件() {
        captured.clear();

        semanticMemory.updateLifecycleState("ghost-id-不存在", LifecycleState.CANCELLED, null);

        assertThat(captured).filteredOn(e -> e instanceof EntityLifecycleChanged).isEmpty();
    }

    private TemporalEntity 构造ACTIVE实体(String id, EntityType type) {
        var now = Instant.now();
        return new TemporalEntity(
                id,
                type,
                "测试实体-" + id,
                "测试描述",
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
