package com.lifepilot.agent.task.proactive.preference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class PreferenceRepository_集成测试 {

    private PreferenceRepository repo;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE proactive_user_preferences (
                    user_id TEXT NOT NULL, dimension TEXT NOT NULL, preference_key TEXT NOT NULL,
                    preference_value REAL NOT NULL DEFAULT 0.5, observation_count INTEGER NOT NULL DEFAULT 0,
                    last_observed_at TEXT, updated_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, dimension, preference_key)
                )""");
        repo = new PreferenceRepository(jdbc);
    }

    @AfterEach
    void tearDown() { if (dataSource != null) dataSource.destroy(); }

    @Test
    void 首次观察创建记录() {
        repo.observe("u1", PreferenceDimension.TIMING, "morning", 0.8f);

        var prefs = repo.findByDimension("u1", PreferenceDimension.TIMING);
        assertThat(prefs).hasSize(1);
        assertThat(prefs.getFirst().preferenceValue()).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(prefs.getFirst().observationCount()).isEqualTo(1);
    }

    @Test
    void 多次观察加权平均() {
        repo.observe("u1", PreferenceDimension.DOMAIN, "reminder", 0.8f);
        repo.observe("u1", PreferenceDimension.DOMAIN, "reminder", 0.2f);

        var prefs = repo.findByDimension("u1", PreferenceDimension.DOMAIN);
        assertThat(prefs.getFirst().preferenceValue()).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(prefs.getFirst().observationCount()).isEqualTo(2);
    }

    @Test
    void 按维度查询() {
        repo.observe("u1", PreferenceDimension.TIMING, "morning", 0.9f);
        repo.observe("u1", PreferenceDimension.TIMING, "evening", 0.3f);
        repo.observe("u1", PreferenceDimension.DOMAIN, "insight", 0.7f);

        var timing = repo.findByDimension("u1", PreferenceDimension.TIMING);
        assertThat(timing).hasSize(2);
        // 按 preference_value DESC 排序
        assertThat(timing.getFirst().preferenceKey()).isEqualTo("morning");
    }

    @Test
    void 查询全部偏好() {
        repo.observe("u1", PreferenceDimension.TIMING, "morning", 0.9f);
        repo.observe("u1", PreferenceDimension.STYLE, "NOTIFY", 0.7f);

        var all = repo.findAllByUserId("u1");
        assertThat(all).hasSize(2);
    }
}
