package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.reminder.ReminderFeedbackRepository;
import com.lifepilot.memory.store.entity.SemanticMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TrustUpgradeService_单元测试 {

    private TrustUpgradeService service;
    private AutonomyRepository repo;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE proactive_behavior_autonomy (
                    user_id TEXT NOT NULL, behavior_name TEXT NOT NULL,
                    autonomy_level TEXT NOT NULL DEFAULT 'A',
                    consecutive_positive INTEGER NOT NULL DEFAULT 0,
                    consecutive_negative INTEGER NOT NULL DEFAULT 0,
                    upgrade_suggested INTEGER NOT NULL DEFAULT 0,
                    cooldown_until TEXT, updated_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, behavior_name)
                )""");
        repo = new AutonomyRepository(jdbc);
        service = new TrustUpgradeService(repo, new AgentConfigProperties(),
                mock(ReminderFeedbackRepository.class), mock(SemanticMemory.class));
    }

    @AfterEach
    void tearDown() { if (dataSource != null) dataSource.destroy(); }

    @Test
    void 默认自主度为A() {
        assertThat(service.getLevel("u1", "reminder")).isEqualTo(AutonomyLevel.A);
    }

    @Test
    void 记忆关注默认自主度为A() {
        assertThat(service.getLevel("u1", "memory-attention")).isEqualTo(AutonomyLevel.A);
    }

    @Test
    void 连续5次正反馈触发升级建议() {
        for (int i = 0; i < 5; i++) {
            service.recordPositiveFeedback("u1", "reminder");
        }
        var config = repo.findByUserAndBehavior("u1", "reminder");
        assertThat(config).isNotNull();
        assertThat(config.upgradeSuggested()).isTrue();
        assertThat(config.consecutivePositive()).isEqualTo(5);
    }

    @Test
    void 负反馈重置正反馈计数() {
        service.recordPositiveFeedback("u1", "reminder");
        service.recordPositiveFeedback("u1", "reminder");
        service.recordNegativeFeedback("u1", "reminder", null);
        var config = repo.findByUserAndBehavior("u1", "reminder");
        assertThat(config.consecutivePositive()).isZero();
        assertThat(config.consecutiveNegative()).isEqualTo(1);
    }

    @Test
    void 连续3次负反馈降级() {
        // 先升到 B
        repo.upsert(AutonomyConfig.defaultFor("u1", "test", AutonomyLevel.B));
        for (int i = 0; i < 3; i++) {
            service.recordNegativeFeedback("u1", "test", null);
        }
        assertThat(service.getLevel("u1", "test")).isEqualTo(AutonomyLevel.A);
    }

    @Test
    void 确认升级从A到B() {
        for (int i = 0; i < 5; i++) {
            service.recordPositiveFeedback("u1", "reminder");
        }
        boolean upgraded = service.confirmUpgrade("u1", "reminder");
        assertThat(upgraded).isTrue();
        assertThat(service.getLevel("u1", "reminder")).isEqualTo(AutonomyLevel.B);
    }

    @Test
    void 确认升级从B到C() {
        repo.upsert(new AutonomyConfig("u1", "report", AutonomyLevel.B, 5, 0, true, null, java.time.Instant.now()));
        boolean upgraded = service.confirmUpgrade("u1", "report");
        assertThat(upgraded).isTrue();
        assertThat(service.getLevel("u1", "report")).isEqualTo(AutonomyLevel.C);
    }

    @Test
    void C级不能再升级() {
        repo.upsert(new AutonomyConfig("u1", "report", AutonomyLevel.C, 0, 0, true, null, java.time.Instant.now()));
        boolean upgraded = service.confirmUpgrade("u1", "report");
        assertThat(upgraded).isFalse();
    }

    @Test
    void 未建议时确认升级返回false() {
        service.recordPositiveFeedback("u1", "reminder"); // 只 1 次
        boolean upgraded = service.confirmUpgrade("u1", "reminder");
        assertThat(upgraded).isFalse();
    }

    @Test
    void 冷却期内正反馈不触发升级建议() {
        // given — 模拟降级后的状态：等级 A，冷却期截止 7 天后
        Instant futureDeadline = Instant.now().plus(Duration.ofDays(7));
        repo.upsert(new AutonomyConfig("u1", "reminder", AutonomyLevel.A,
                0, 3, false, futureDeadline, Instant.now()));

        // when — 冷却期内连续 5 次正反馈
        for (int i = 0; i < 5; i++) {
            service.recordPositiveFeedback("u1", "reminder");
        }

        // then — 冷却期未过，不应触发升级建议
        var config = repo.findByUserAndBehavior("u1", "reminder");
        assertThat(config).isNotNull();
        assertThat(config.upgradeSuggested()).isFalse();
        assertThat(config.consecutivePositive()).isEqualTo(5);
    }

    @Test
    void 冷却期过后正反馈触发升级建议() {
        // given — 冷却期已在 1 秒前到期
        Instant expiredDeadline = Instant.now().minus(Duration.ofSeconds(1));
        repo.upsert(new AutonomyConfig("u1", "reminder", AutonomyLevel.A,
                0, 0, false, expiredDeadline, Instant.now()));

        // when — 冷却期已过，连续 5 次正反馈
        for (int i = 0; i < 5; i++) {
            service.recordPositiveFeedback("u1", "reminder");
        }

        // then — 冷却期已过期，应触发升级建议
        var config = repo.findByUserAndBehavior("u1", "reminder");
        assertThat(config).isNotNull();
        assertThat(config.upgradeSuggested()).isTrue();
        assertThat(config.consecutivePositive()).isEqualTo(5);
    }
}
