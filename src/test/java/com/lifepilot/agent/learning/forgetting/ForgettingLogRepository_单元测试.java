package com.lifepilot.agent.learning.forgetting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ForgettingLogRepository 单元测试。
 *
 * @author zsg
 * @since 2026-07-02
 */
class ForgettingLogRepository_单元测试 {

    private JdbcTemplate jdbcTemplate;
    private ForgettingLogRepository repository;

    @BeforeEach
    void 初始化() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new ForgettingLogRepository(jdbcTemplate);
    }

    @Test
    void 空白过滤条件应失败且不查询() {
        assertThatThrownBy(() -> repository.findPaginated(null, null, " ", 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy不能为空");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void 首尾空白时间应失败且不查询() {
        assertThatThrownBy(() -> repository.findPaginated(" 2026-07-02T00:00:00Z", null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeFrom不能包含首尾空白");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void 非法时间格式应失败且不查询() {
        assertThatThrownBy(() -> repository.findPaginated("2026-07-02", null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeFrom必须是 ISO 8601 时间");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void 非法分页参数应失败且不查询() {
        assertThatThrownBy(() -> repository.findPaginated(null, null, null, -1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page 不能小于 0");

        assertThatThrownBy(() -> repository.findPaginated(null, null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size 必须大于 0");

        verifyNoInteractions(jdbcTemplate);
    }
}
