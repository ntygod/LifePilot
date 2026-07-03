package com.lifepilot.memory.consumption;

import com.lifepilot.memory.consumption.config.MemoryConsumptionProperties;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EpisodicCleanupJob 单元测试。
 *
 * @author zsg
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension.class)
class EpisodicCleanupJob_单元测试 {

    @Test
    void 构造依赖为空时应直接失败() {
        var episodicMemory = mock(EpisodicMemory.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        var properties = new MemoryConsumptionProperties();

        assertThatThrownBy(() -> new EpisodicCleanupJob(null, jdbcTemplate, properties))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("EpisodicMemory 不能为空");
        assertThatThrownBy(() -> new EpisodicCleanupJob(episodicMemory, null, properties))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("JdbcTemplate 不能为空");
        assertThatThrownBy(() -> new EpisodicCleanupJob(episodicMemory, jdbcTemplate, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("MemoryConsumptionProperties 不能为空");
    }

    @Test
    void 查询过期会话失败时应直接暴露错误() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var job = new EpisodicCleanupJob(
                mock(EpisodicMemory.class),
                new JdbcTemplate(dataSource),
                new MemoryConsumptionProperties());

        assertThatThrownBy(job::cleanup)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("session_store");

        dataSource.destroy();
    }

    @Test
    void 删除过期会话失败时应直接暴露错误() {
        var episodicMemory = mock(EpisodicMemory.class);
        var jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(List.of("session-expired"));
        when(episodicMemory.delete("session-expired"))
                .thenThrow(new IllegalStateException("删除失败"));
        var job = new EpisodicCleanupJob(
                episodicMemory,
                jdbcTemplate,
                new MemoryConsumptionProperties());

        assertThatThrownBy(job::cleanup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("删除失败");
        verify(episodicMemory).delete("session-expired");
    }
}
