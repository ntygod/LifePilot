package com.lifepilot.memory.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.query.MemoryQueryApi;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.support.MemoryProjectionTestSupport;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.entity.VersionMerger;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 场景 S1：取消定时任务后不再提醒 — L3 GOAL 从 ACTIVE 转 CANCELLED。
 *
 * <p>验证目标：用户"每周一 10 点提醒我做汇报"落入系统后形成一条 {@link EntityType#GOAL}
 * 实体；过几天改主意说"那个定时汇报别做了"后，应沿
 * {@link LifecycleState#ACTIVE} → {@link LifecycleState#CANCELLED} 状态机转换，
 * 活跃召回列表不再返回它。</p>
 *
 * <p><b>降级说明</b>：
 * <ol>
 *   <li>未走 fixture + {@code 模拟用户说(...)} 路径 — 场景测试基类
 *       {@code lifepilot.meta.enabled=false} 导致 {@code MemoryToolProvider} 未注册，
 *       {@code memory} 工具不进入 {@code DynamicToolRegistry}，ReactAgentLoop 拿不到
 *       对应 ToolCallback；此外场景测试基类通过 ComponentScan 会拾到
 *       {@code SkillTestSupport.semanticMemory()} 的 mock，破坏真实写入。</li>
 *   <li>改走 {@link MockitoExtension} + 手动装配 {@link SemanticMemory} 文件 SQLite 范式
 *       （同 {@code updateDescription版本化_单元测试}），直接调
 *       {@link SemanticMemory#updateLifecycleState(String, LifecycleState, String, ChangeSource)}
 *       模拟 {@code MemoryToolProvider.executeCancelSingle} 的效果 —— 状态转换、DB
 *       UPDATE、快照与事件发布与工具路径完全等价；
 *       Scheduler / Proactive 提醒链路未包含在断言中（plan 允许：降级后仅断言 L3）。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("场景 S1 取消定时任务后不再提醒")
class 取消定时任务后不再提醒_场景测试 {

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
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-scenario-s1-" + dbId + ".db");
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

        // 冲突检测阶段的向量匹配统一返回空命中，避免误触发语义冲突裁决路径
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
    @DisplayName("用户发起定时目标后取消 —— GOAL 应从 ACTIVE 转 CANCELLED")
    void 取消后GOAL应转CANCELLED() {
        // 1. 用户"每周一 10 点提醒我做汇报"落入系统 → 建立 GOAL 实体
        var goal = 构造ACTIVE实体(EntityType.GOAL, "每周一汇报", "每周一上午 10 点做汇报");
        var created = semanticMemory.upsertWithConflictDetection(
                goal,
                "scenario-session-s1",
                MemoryWriteContext.conversation("scenario-session-s1"));

        assertThat(created).as("upsert 应返回持久化实体").isNotNull();
        assertThat(created.lifecycleState())
                .as("新建 GOAL 应进入 ACTIVE")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(created.type()).isEqualTo(EntityType.GOAL);

        // 2. 过几天用户再次对话"那个定时汇报别做了"
        //    对应 MemoryToolProvider.executeCancelSingle：ACTIVE → CANCELLED
        semanticMemory.updateLifecycleState(
                created.id(), LifecycleState.CANCELLED, "user-cancel", ChangeSource.TOOL_EXPLICIT);

        // 3. L3 已转 CANCELLED，lifecycleReason 落盘
        var after = queryApi.findById(created.id()).orElseThrow();
        assertThat(after.lifecycleState())
                .as("取消后 GOAL 应标为 CANCELLED")
                .isEqualTo(LifecycleState.CANCELLED);
        assertThat(after.lifecycleReason())
                .as("lifecycleReason 应记录取消来源")
                .isEqualTo("user-cancel");

        // 4. 活跃 GOAL 列表不再包含已取消实体 —— 下轮"每周一"定时到达时不会再召回
        var activeGoals = queryApi.findActiveByType(EntityType.GOAL.name());
        assertThat(activeGoals)
                .as("CANCELLED 的 GOAL 不应出现在 findActiveByType 结果")
                .extracting(TemporalEntity::id)
                .doesNotContain(created.id());
    }

    /** 构造一条 ACTIVE + PERSISTENT 的基础实体（由 upsert 路径生成 UUID）。 */
    private TemporalEntity 构造ACTIVE实体(EntityType type, String name, String description) {
        var now = Instant.now();
        return new TemporalEntity(
                /* id */ null,
                type,
                name,
                description,
                /* properties */ Map.of(),
                /* version */ 1,
                /* isCurrent */ true,
                /* validFrom */ now,
                /* validTo */ null,
                /* sourceConversationId */ "scenario-session-s1",
                /* extractionConfidence */ 1.0f,
                /* importanceScore */ 0.6f,
                /* accessCount */ 0,
                /* lastAccessedAt */ null,
                /* createdAt */ now,
                /* updatedAt */ now);
    }
}
