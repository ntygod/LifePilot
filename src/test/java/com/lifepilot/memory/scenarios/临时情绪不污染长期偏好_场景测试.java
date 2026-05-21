package com.lifepilot.memory.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.Temporality;
import com.lifepilot.memory.lifecycle.query.MemoryQueryApi;
import com.lifepilot.memory.lifecycle.scanner.ExpirationScanner;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.support.MemoryProjectionTestSupport;
import com.lifepilot.memory.store.entity.ConflictDetector;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.entity.VersionMerger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
 * 场景 S5：临时情绪不污染长期偏好 — EPHEMERAL 到期后被 Cron 转 EXPIRED。
 *
 * <p>验证目标：用户抱怨"最近工作忙，不想写代码"落入系统后，
 * {@code RealtimeExtractor} 会将其标记为 {@link Temporality#EPHEMERAL}（或 SHORT_TERM）
 * 并设置 {@code expires_at = now + EPHEMERAL_TTL (7天)}；TTL 到期后
 * {@link ExpirationScanner} 扫描将其沿
 * {@link LifecycleState#ACTIVE} → {@link LifecycleState#EXPIRED} 转换，
 * 后续"写代码偏好"相关检索不会再受该情绪污染。</p>
 *
 * <p>项目适配：
 * <ul>
 *   <li>{@code EntityType} 无 STATE 枚举值 — 情绪/体验用 {@link EntityType#EXPERIENCE} 承载；</li>
 *   <li>{@code MemoryToolProvider.executeCreate} 当前不接受 {@code temporality}
 *       参数（schema 未暴露）→ fixture → 工具路径无法注入 EPHEMERAL；
 *       改走 {@code RealtimeExtractor} 会自动推断 temporality 但耦合 LLM fixture；
 *       此场景绕过两条路径，直接调 SemanticMemory 注入 EPHEMERAL 实体。</li>
 * </ul></p>
 *
 * <p><b>降级说明</b>：
 * <ol>
 *   <li>未走 fixture → 工具 → create 路径，原因同 S1/S3（meta.enabled=false +
 *       create schema 不含 temporality 字段）；</li>
 *   <li>改为：构造 {@link TemporalEntity} 带 EPHEMERAL + expiresAt=now+7d，走
 *       {@link SemanticMemory#upsertWithConflictDetection} 标准路径，确保
 *       V15 schema 的 {@code expires_at / temporality} 列写入；</li>
 *   <li>用 {@link ExpirationScanner#scanNow()}（测试友好入口）同步触发扫描；</li>
 *   <li>{@link Clock} 注入固定时刻，scanNow 内部 {@code clock.instant()} 取的时间
 *       大于 {@code expires_at} 从而命中 {@code findExpiredActive} 过滤；</li>
 *   <li>无需启 {@code @SpringBootTest}，手动装配最小 SemanticMemory + ExpirationScanner。</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("场景 S5 临时情绪 EPHEMERAL 过期")
class 临时情绪不污染长期偏好_场景测试 {

    @Mock
    private VectorSearcher vectorSearcher;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SemanticMemory semanticMemory;
    private MemoryQueryApi queryApi;
    private ExpirationScanner expirationScanner;
    private TestClock clock;
    private Path dbPath;

    private static final Instant BASE_TIME = Instant.parse("2026-04-23T10:00:00Z");

    @BeforeEach
    void 初始化() throws Exception {
        String dbId = UUID.randomUUID().toString().substring(0, 8);
        dbPath = Path.of(System.getProperty("java.io.tmpdir"), "lifepilot-scenario-s5-" + dbId + ".db");
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

        when(vectorSearcher.searchEntities(any(), any(Integer.class), any(Float.class)))
                .thenReturn(List.of());

        var conflictDetector = new ConflictDetector(jdbcTemplate, vectorSearcher, null, 0.92f, null);
        semanticMemory = new SemanticMemory(jdbcTemplate, conflictDetector, new VersionMerger(), vectorSearcher);
        MemoryProjectionTestSupport.attach(semanticMemory, jdbcTemplate, vectorSearcher);
        queryApi = new MemoryQueryApi(semanticMemory, new MemoryProvenanceRepository(jdbcTemplate), jdbcTemplate);

        clock = new TestClock(BASE_TIME);
        expirationScanner = new ExpirationScanner(semanticMemory, clock);
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
    @DisplayName("EPHEMERAL 情绪 7 天后应被 ExpirationScanner 扫到转 EXPIRED")
    void EPHEMERAL情绪应在7天后过Cron扫描转EXPIRED() {
        // 1. 用户抱怨"最近工作忙，不想写代码" → 注入 EXPERIENCE + EPHEMERAL + expiresAt=now+7d
        var ephemeralExperience = 构造EPHEMERAL体验(
                "近期工作负荷", "最近工作忙，不想写代码",
                BASE_TIME, BASE_TIME.plus(Duration.ofDays(7)));
        var created = semanticMemory.upsertWithConflictDetection(ephemeralExperience, "scenario-session-s5");
        assertThat(created).as("upsert 应返回持久化实体").isNotNull();

        var loaded = queryApi.findById(created.id()).orElseThrow();
        assertThat(loaded.temporality())
                .as("temporality 应落库为 EPHEMERAL")
                .isEqualTo(Temporality.EPHEMERAL);
        assertThat(loaded.expiresAt())
                .as("expiresAt 应落库")
                .isEqualTo(BASE_TIME.plus(Duration.ofDays(7)));
        assertThat(loaded.lifecycleState())
                .as("初始应为 ACTIVE")
                .isEqualTo(LifecycleState.ACTIVE);

        // 2. 时间推进 8 天（超过 7 天 TTL）
        clock.advance(Duration.ofDays(8));

        // 3. 触发 ExpirationScanner 扫描
        expirationScanner.scanNow();

        // 4. 断言实体已转 EXPIRED，lifecycleReason 为 Cron 的 ttl-reached
        var after = queryApi.findById(created.id()).orElseThrow();
        assertThat(after.lifecycleState())
                .as("过期后应转 EXPIRED")
                .isEqualTo(LifecycleState.EXPIRED);
        assertThat(after.lifecycleReason())
                .as("lifecycleReason 应记录 ttl-reached")
                .isEqualTo("ttl-reached");
    }

    @Test
    @DisplayName("PERSISTENT 偏好不应被 ExpirationScanner 误伤")
    void PERSISTENT偏好即使共存也不应被扫EXPIRED() {
        // 一条 EPHEMERAL 情绪 + 一条长期 PERSISTENT 偏好共存
        var ephemeral = 构造EPHEMERAL体验("临时情绪", null,
                BASE_TIME, BASE_TIME.plus(Duration.ofDays(7)));
        var persistent = 构造ACTIVE偏好("长期偏好_编程语言", "Kotlin");
        semanticMemory.upsertWithConflictDetection(ephemeral, "scenario-session-s5");
        var persistedPref = semanticMemory.upsertWithConflictDetection(persistent, "scenario-session-s5");

        clock.advance(Duration.ofDays(30));
        expirationScanner.scanNow();

        var prefAfter = queryApi.findById(persistedPref.id()).orElseThrow();
        assertThat(prefAfter.lifecycleState())
                .as("PERSISTENT 偏好应保持 ACTIVE")
                .isEqualTo(LifecycleState.ACTIVE);
        assertThat(prefAfter.temporality())
                .isEqualTo(Temporality.PERSISTENT);
    }

    /** 构造一条带 EPHEMERAL + expiresAt 的 EXPERIENCE 实体（按 23 参 canonical constructor）。 */
    private TemporalEntity 构造EPHEMERAL体验(String name, String description,
                                        Instant now, Instant expiresAt) {
        return new TemporalEntity(
                /* id */ null,
                EntityType.EXPERIENCE,
                name,
                description,
                /* properties */ Map.of(),
                /* version */ 1,
                /* isCurrent */ true,
                /* validFrom */ now,
                /* validTo */ null,
                /* sourceConversationId */ "scenario-session-s5",
                /* extractionConfidence */ 0.8f,
                /* importanceScore */ 0.3f,
                /* accessCount */ 0,
                /* lastAccessedAt */ null,
                /* createdAt */ now,
                /* updatedAt */ now,
                LifecycleState.ACTIVE,
                /* lifecycleReason */ null,
                expiresAt,
                Temporality.EPHEMERAL,
                /* succeededBy */ null,
                /* isDerived */ false,
                /* derivationSources */ List.of());
    }

    /** 构造一条 ACTIVE + PERSISTENT 偏好，用于对照不过期。 */
    private TemporalEntity 构造ACTIVE偏好(String name, String value) {
        var now = BASE_TIME;
        return new TemporalEntity(
                null, EntityType.PREFERENCE, name, "偏好值=" + value,
                Map.of("value", value), 1, true, now, null,
                "scenario-session-s5",
                0.9f, 0.5f, 0, null, now, now);
    }

    /**
     * 可推进测试时钟 — 场景测试基类的 {@code MutableClock} 绑定到
     * {@code ScenarioTestConfiguration @Primary} bean，本测试不走 Spring 装配，
     * 因此内联一个最小实现。
     */
    private static class TestClock extends Clock {
        private Instant now;
        TestClock(Instant initial) { this.now = initial; }
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
