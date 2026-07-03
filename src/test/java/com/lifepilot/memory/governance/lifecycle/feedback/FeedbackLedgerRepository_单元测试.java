package com.lifepilot.memory.governance.lifecycle.feedback;

import com.lifepilot.memory.governance.lifecycle.WeightSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FeedbackLedgerRepository 单元测试。
 *
 * @author zsg
 * @since 2026-06-29
 */
class FeedbackLedgerRepository_单元测试 {

    private JdbcTemplate jdbcTemplate;
    private FeedbackLedgerRepository repository;

    @BeforeEach
    void 初始化() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new FeedbackLedgerRepository(jdbcTemplate);
    }

    @Test
    void append_写入0行应失败() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> repository.append(
                "entity-1",
                -0.2,
                0.3,
                WeightSource.USER_FEEDBACK,
                Instant.parse("2026-06-29T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("反馈账本入账影响行数必须为 1")
                .hasMessageContaining("entityId=entity-1")
                .hasMessageContaining("rows=0");
    }

    @Test
    void append_首尾空白entityId应失败且不写入() {
        assertThatThrownBy(() -> repository.append(
                " entity-1",
                -0.2,
                0.3,
                WeightSource.USER_FEEDBACK,
                Instant.parse("2026-06-29T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId 不能包含首尾空白");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void countNegative_查询结果为空应失败() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("entity-1")))
                .thenReturn(null);

        assertThatThrownBy(() -> repository.countNegative("entity-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("负反馈计数查询结果不能为空")
                .hasMessageContaining("entityId=entity-1");
    }

    @Test
    void countNegative_首尾空白entityId应失败且不查询() {
        assertThatThrownBy(() -> repository.countNegative("entity-1 "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId 不能包含首尾空白");

        verifyNoInteractions(jdbcTemplate);
    }
}
