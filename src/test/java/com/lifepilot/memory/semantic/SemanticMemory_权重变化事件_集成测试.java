package com.lifepilot.memory.semantic;

import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.governance.lifecycle.WeightSource;
import com.lifepilot.memory.governance.lifecycle.events.EntityWeightChanged;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.*;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.memory.store.support.MemoryProjectionTestSupport;
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
 * {@link SemanticMemory#updateImportanceScore} 事件发布集成测试（Task 11 修补 B）。
 *
 * <p>验证：扩展签名 {@code (entityId, newScore, source)} 在更新完成后向 Spring
 * ApplicationEventPublisher 发布 {@link EntityWeightChanged}，且 delta 取
 * {@code newScore - oldScore}，source 透传。</p>
 *
 * <p>装配方式复用 {@code SemanticMemory_生命周期字段_集成测试}：文件 SQLite + 真跑
 * Flyway 迁移（V1 → V16），手动装配 SemanticMemory，通过 {@code setEventPublisher}
 * 注入一个 capturing 事件发布器，避免启动完整 Spring 上下文带来的 mock 污染。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticMemory 权重变化事件集成测试")
class SemanticMemory_权重变化事件_集成测试 {

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
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-sm-weight-" + dbId + ".db");
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
        // 手动装配 ApplicationEventPublisher — 捕获所有事件用于断言
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
    void updateImportanceScore应发布EntityWeightChanged事件_承载source与delta() {
        var entity = 构造ACTIVE实体("entity-权重-1", EntityType.EXPERIENCE, 0.5f);
        semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.unknown(null));
        captured.clear();   // 忽略 upsert 本身可能产生的生命周期事件

        semanticMemory.updateImportanceScore(entity.id(), 0.2f, WeightSource.USER_FEEDBACK);

        var weightEvents = captured.stream()
                .filter(e -> e instanceof EntityWeightChanged)
                .map(e -> (EntityWeightChanged) e)
                .toList();
        assertThat(weightEvents).hasSize(1);
        var event = weightEvents.getFirst();
        assertThat(event.entityId()).isEqualTo(entity.id());
        assertThat(event.source()).isEqualTo(WeightSource.USER_FEEDBACK);
        assertThat(event.cumulativeScore()).isCloseTo(0.2d, org.assertj.core.data.Offset.offset(1e-6d));
        assertThat(event.delta()).isCloseTo(-0.3d, org.assertj.core.data.Offset.offset(1e-6d));
    }

    @Test
    void 未命中任何当前版本时不应发事件() {
        // given — 不预先 upsert，直接对不存在的实体调用
        captured.clear();

        semanticMemory.updateImportanceScore("不存在-123", 0.7f, WeightSource.EFFECTIVENESS);

        assertThat(captured).filteredOn(e -> e instanceof EntityWeightChanged).isEmpty();
    }

    @Test
    void EFFECTIVENESS来源应原样透传() {
        var entity = 构造ACTIVE实体("entity-权重-3", EntityType.EXPERIENCE, 0.5f);
        semanticMemory.upsertWithConflictDetection(entity, null, MemoryWriteContext.unknown(null));
        captured.clear();

        semanticMemory.updateImportanceScore(entity.id(), 0.75f, WeightSource.EFFECTIVENESS);

        assertThat(captured)
                .filteredOn(e -> e instanceof EntityWeightChanged)
                .extracting(e -> ((EntityWeightChanged) e).source())
                .containsExactly(WeightSource.EFFECTIVENESS);
    }

    private TemporalEntity 构造ACTIVE实体(String id, EntityType type, float importance) {
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
                importance,
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
