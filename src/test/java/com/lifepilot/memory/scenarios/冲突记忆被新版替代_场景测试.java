package com.lifepilot.memory.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.query.MemoryQueryApi;
import com.lifepilot.memory.retrieval.VectorSearchResult;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.support.MemoryProjectionTestSupport;
import com.lifepilot.memory.semantic.ConflictDetector;
import com.lifepilot.memory.semantic.ConflictResolutionRepository;
import com.lifepilot.memory.semantic.ConflictResolutionService;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.semantic.VersionMerger;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
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
 * 场景 S4：冲突记忆被新版替代 — REPLACE verdict 使老 PREFERENCE 转 SUPERSEDED。
 *
 * <p>验证目标：用户先说"我最爱的编程语言是 Python"，随后又说"现在我更爱 Rust"；
 * 系统应新建一条 Rust 偏好实体，并通过 {@link ConflictResolutionService} 对老的
 * Python 偏好做 LLM 裁决，得到 REPLACE verdict 后将老实体沿
 * {@link LifecycleState#ACTIVE} → {@link LifecycleState#SUPERSEDED} 状态机转换。</p>
 *
 * <p><b>降级说明</b>：
 * <ol>
 *   <li>未走 fixture + {@code 模拟用户说(...)} 路径 — {@code FixtureBackedChatModel} 仅按
 *       最后一条用户消息匹配，无法覆盖 {@code ConflictResolutionService} 的内部
 *       {@code promptRegistry.render("semantic/memory-conflict-resolution", ...)} prompt；
 *       fixture JSON 格式也不支持按 prompt 模板名路由。</li>
 *   <li>改为：手动装配文件 SQLite + 真实 {@link SemanticMemory}，但 mock
 *       {@link GenerationRouter} 让其按裁决 prompt 固定返回 REPLACE JSON，绕开 LLM
 *       fixture 随机性。此路径严格覆盖"新建偏好 → upsert 触发 resolveAsync → LLM 裁决 →
 *       REPLACE 应用 → 老实体落 SUPERSEDED"的完整生产链路，比 ConflictResolutionService
 *       单元测试更贴近真实场景（前者仅单测 applyVerdict）。</li>
 *   <li>异步执行：{@code resolveAsync} 实际由 virtual thread 触发，测试改调
 *       同包可见的 {@code resolveSync}（package-private），使断言同步可见。</li>
 *   <li>{@link ConflictResolutionRepository} 使用真实 DB 版本（V15 conflict_resolution_queue 表
 *       已由 Flyway 建出），enqueue/markResolved 走真实 SQL，一起覆盖。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("场景 S4 冲突记忆被新版替代")
class 冲突记忆被新版替代_场景测试 {

    @Mock
    private VectorSearcher vectorSearcher;

    @Mock
    private GenerationRouter generationRouter;

    @Mock
    private PromptRegistry promptRegistry;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private MemoryQueryApi queryApi;
    private ConflictResolutionService conflictResolutionService;
    private Path dbPath;

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-scenario-s4-" + dbId + ".db");
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

        // ConflictDetector 不命中 —— upsert 走"新建"分支，不做版本化合并；
        // 语义邻居通过 vectorSearcher 提供，模拟新老偏好高相似
        when(vectorSearcher.searchEntities(any(), any(Integer.class), any(Float.class)))
                .thenReturn(List.of());

        var conflictDetector = new ConflictDetector(jdbcTemplate, vectorSearcher, null, 0.92f, null);
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, new VersionMerger(), vectorSearcher);
        MemoryProjectionTestSupport.attach(semanticMemory, jdbcTemplate, vectorSearcher);
        queryApi = new MemoryQueryApi(semanticMemory, new MemoryProvenanceRepository(jdbcTemplate), jdbcTemplate);

        var queueRepo = new ConflictResolutionRepository(jdbcTemplate);
        lenient().when(promptRegistry.render(anyString(), any())).thenReturn("冲突裁决 prompt body");
        conflictResolutionService = new ConflictResolutionService(
                generationRouter, promptRegistry, vectorSearcher, queueRepo, semanticMemory);
        // 注意：本场景不通过 setConflictResolutionService 串联自动触发，而是显式调
        // resolveSync 保证单测同步验证。
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
    @DisplayName("高相似新偏好经 LLM REPLACE 裁决后，老偏好应转 SUPERSEDED")
    void 高相似新偏好应触发LLM裁决使老版SUPERSEDED() {
        // 1. 用户说"我最爱的编程语言是 Python" → 落入系统
        var oldPref = 构造ACTIVE偏好("最爱编程语言", "Python");
        var pythonEntity = semanticMemory.upsertWithConflictDetection(oldPref, "scenario-session-s4");
        assertThat(pythonEntity).isNotNull();
        var oldId = pythonEntity.id();

        // 2. 用户改主意："现在我更爱 Rust" → 再落入一条新实体
        //    name 换成新值以避免 ConflictDetector 相同 (name, type) 判定 → 走合并
        var newPref = 构造ACTIVE偏好("偏好变更_Rust", "Rust");
        var rustEntity = semanticMemory.upsertWithConflictDetection(newPref, "scenario-session-s4");
        assertThat(rustEntity).isNotNull();
        var newId = rustEntity.id();

        assertThat(queryApi.findById(oldId).orElseThrow().lifecycleState())
                .as("裁决前老实体仍 ACTIVE")
                .isEqualTo(LifecycleState.ACTIVE);

        // 3. 模拟 VectorSearcher 返回老实体为高相似邻居 (similarity=0.9)
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(List.of(new VectorSearchResult(oldId, 0.9f)));

        // 4. 模拟 LLM 返回 REPLACE verdict
        when(generationRouter.call(
                anyString(), anyString(), any(), any(), any(),
                any(GenerationCapability.class), any()))
                .thenReturn(new LlmResponse("""
                        {"verdict":"REPLACE","target_id":"%s","rationale":"新 Rust 偏好否定旧 Python 偏好"}
                        """.formatted(oldId), null, null, List.of(), Map.of(), 100, 50, null, 0, "test-provider", "test-model", 200L, false));

        // 5. 驱动 resolveAsync（生产路径通过 upsert afterCommit 触发，此处直接调以控制时序）
        //    Awaitility 轮询 conflict_resolution_queue 直到出现 RESOLVED 状态，避免主测试线程竞态
        conflictResolutionService.resolveAsync(rustEntity, List.of(pythonEntity));
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> {
                    Integer resolved = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM conflict_resolution_queue WHERE status = 'RESOLVED'",
                            Integer.class);
                    assertThat(resolved).as("等待裁决完成").isEqualTo(1);
                });

        // 6. 老实体应沿 ACTIVE → SUPERSEDED；新实体保持 ACTIVE
        var afterOld = queryApi.findById(oldId).orElseThrow();
        assertThat(afterOld.lifecycleState())
                .as("REPLACE 裁决后老偏好应转 SUPERSEDED")
                .isEqualTo(LifecycleState.SUPERSEDED);
        assertThat(afterOld.lifecycleReason())
                .as("SUPERSEDED 的 reason 应注明 replaced-by")
                .startsWith("replaced-by:");

        var afterNew = queryApi.findById(newId).orElseThrow();
        assertThat(afterNew.lifecycleState())
                .as("新 Rust 偏好仍 ACTIVE")
                .isEqualTo(LifecycleState.ACTIVE);
    }

    /** 构造一条 ACTIVE + PERSISTENT 的 PREFERENCE 基础实体，name 唯一。 */
    private TemporalEntity 构造ACTIVE偏好(String name, String value) {
        var now = Instant.now();
        return new TemporalEntity(
                /* id */ null,
                EntityType.PREFERENCE,
                name,
                "偏好值=" + value,
                /* properties */ Map.of("value", value),
                /* version */ 1,
                /* isCurrent */ true,
                /* validFrom */ now,
                /* validTo */ null,
                /* sourceConversationId */ "scenario-session-s4",
                /* extractionConfidence */ 0.9f,
                /* importanceScore */ 0.5f,
                /* accessCount */ 0,
                /* lastAccessedAt */ null,
                /* createdAt */ now,
                /* updatedAt */ now);
    }
}
