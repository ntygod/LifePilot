package com.lifepilot.memory.governance.lifecycle.feedback;

import com.lifepilot.memory.governance.lifecycle.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 生命周期反馈队列仓储单元测试。
 *
 * @author zsg
 * @since 2026-07-02
 */
class LifecycleQueueRepository_单元测试 {

    private JdbcTemplate jdbcTemplate;
    private RevalidationQueueRepository revalidationQueueRepository;
    private RegenerationQueueRepository regenerationQueueRepository;

    @BeforeEach
    void 初始化() {
        jdbcTemplate = mock(JdbcTemplate.class);
        revalidationQueueRepository = new RevalidationQueueRepository(jdbcTemplate);
        regenerationQueueRepository = new RegenerationQueueRepository(jdbcTemplate);
    }

    @Test
    void 再验证入队遇到脏实体ID应失败且不写入() {
        assertThatThrownBy(() -> revalidationQueueRepository.enqueue(
                " entity-1",
                SourceType.DOCUMENT,
                "doc-1",
                Instant.parse("2026-07-02T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId 不能包含首尾空白");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void 再验证计数遇到脏来源ID应失败且不查询() {
        assertThatThrownBy(() -> revalidationQueueRepository.countPendingBySource(
                SourceType.DOCUMENT,
                "doc-1 "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceId 不能包含首尾空白");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void 派生重算入队遇到脏实体ID应失败且不写入() {
        assertThatThrownBy(() -> regenerationQueueRepository.enqueue(
                "derived-1 ",
                "source-1",
                Instant.parse("2026-07-02T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derivedEntityId 不能包含首尾空白");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void 派生重算状态更新遇到脏队列ID应失败且不写入() {
        assertThatThrownBy(() -> regenerationQueueRepository.markDone(" queue-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id 不能包含首尾空白");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void 派生队列项映射遇到脏ID应失败() {
        assertThatThrownBy(() -> new RegenerationQueueRepository.QueueItem(
                "queue-1",
                " derived-1",
                "source-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("队列项 derivedEntityId 不能包含首尾空白");
    }
}
