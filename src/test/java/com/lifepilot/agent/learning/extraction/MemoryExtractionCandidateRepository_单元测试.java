package com.lifepilot.agent.learning.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.semantic.AudnDecision;
import com.lifepilot.memory.semantic.AudnOperation;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.scope.MemoryOriginType;
import com.lifepilot.memory.store.scope.MemoryRealityType;
import com.lifepilot.memory.store.scope.MemoryScope;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemoryExtractionCandidateRepository 单元测试。
 *
 * @author zsg
 * @since 2026-06-23
 */
class MemoryExtractionCandidateRepository_单元测试 {

    @Test
    void 构造依赖为空时应直接失败() {
        assertThatThrownBy(() -> new MemoryExtractionCandidateRepository(null, new ObjectMapper()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("JdbcTemplate 不能为空");
        assertThatThrownBy(() -> new MemoryExtractionCandidateRepository(mock(JdbcTemplate.class), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ObjectMapper 不能为空");
    }

    @Test
    void decisionJson无法序列化时不插入候选() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("序列化失败") {});
        var repository = new MemoryExtractionCandidateRepository(jdbcTemplate, objectMapper);
        var decision = new AudnDecision(
                AudnOperation.ADD,
                "咖啡偏好",
                EntityType.PREFERENCE,
                "喜欢拿铁",
                Map.of("drink", "latte"),
                0.9f,
                0.7f,
                null,
                null,
                "USER_EXPLICIT",
                "用户说喜欢拿铁"
        );
        var writeContext = new MemoryWriteContext(
                "space-1",
                MemoryScope.USER_PROFILE,
                MemoryOriginType.CHAT,
                MemoryRealityType.UNKNOWN,
                "session-1",
                "conversation-1",
                "session-1",
                "turn-1",
                "entry-1",
                null,
                null,
                "用户说喜欢拿铁"
        );

        assertThatThrownBy(() -> repository.recordValidated("session-1", writeContext, decision))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decision_json 序列化失败")
                .hasMessageContaining("咖啡偏好");
        verify(jdbcTemplate, never()).update(contains("INSERT INTO memory_extraction_candidates"), any(Object[].class));
    }

    @Test
    void 拒绝候选质量未达标时保留审计记录() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        var repository = new MemoryExtractionCandidateRepository(jdbcTemplate, objectMapper);
        var decision = new AudnDecision(
                AudnOperation.ADD,
                "咖啡偏好",
                EntityType.PREFERENCE,
                "喜欢拿铁",
                Map.of("drink", "latte"),
                0.2f,
                0.7f,
                null,
                null,
                "USER_EXPLICIT",
                "用户说喜欢拿铁"
        );
        var writeContext = new MemoryWriteContext(
                "space-1",
                MemoryScope.USER_PROFILE,
                MemoryOriginType.CHAT,
                MemoryRealityType.UNKNOWN,
                "session-1",
                "conversation-1",
                "session-1",
                "turn-1",
                "entry-1",
                null,
                null,
                "用户说喜欢拿铁"
        );

        repository.recordRejected("session-1", writeContext, decision, "LOW_CONFIDENCE");

        verify(jdbcTemplate).update(
                contains("INSERT INTO memory_extraction_candidates"),
                any(), eq("session-1"), eq("turn-1"), eq("entry-1"), eq("space-1"),
                eq("ADD"), eq("咖啡偏好"), eq("PREFERENCE"), any(),
                eq("REJECTED"), eq("REJECTED"), eq("LOW_CONFIDENCE"),
                eq("UNKNOWN"), eq("UNVERIFIED"), eq(0.0f), eq("用户说喜欢拿铁"),
                any(), any());
    }

    @Test
    void 拒绝候选缺少实体字段时应直接失败() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        var repository = new MemoryExtractionCandidateRepository(jdbcTemplate, new ObjectMapper());
        var decision = new AudnDecision(
                AudnOperation.ADD,
                "",
                EntityType.PREFERENCE,
                "喜欢拿铁",
                Map.of("drink", "latte"),
                0.2f,
                0.7f,
                null,
                null,
                "USER_EXPLICIT",
                "用户说喜欢拿铁"
        );
        var writeContext = new MemoryWriteContext(
                "space-1",
                MemoryScope.USER_PROFILE,
                MemoryOriginType.CHAT,
                MemoryRealityType.UNKNOWN,
                "session-1",
                "conversation-1",
                "session-1",
                "turn-1",
                "entry-1",
                null,
                null,
                "用户说喜欢拿铁"
        );

        assertThatThrownBy(() -> repository.recordRejected("session-1", writeContext, decision, "LOW_CONFIDENCE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUDN 实体名称不能为空");
        verify(jdbcTemplate, never()).update(contains("INSERT INTO memory_extraction_candidates"), any(Object[].class));
    }

    @Test
    void 标记应用未命中候选时应直接失败() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        var repository = new MemoryExtractionCandidateRepository(jdbcTemplate, new ObjectMapper());

        assertThatThrownBy(() -> repository.markApplied("missing-candidate", "entity-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("记忆提取候选状态更新失败")
                .hasMessageContaining("missing-candidate")
                .hasMessageContaining("APPLIED");
    }

    @Test
    void 标记失败未命中候选时应直接失败() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        var repository = new MemoryExtractionCandidateRepository(jdbcTemplate, new ObjectMapper());

        assertThatThrownBy(() -> repository.markFailed("missing-candidate", "执行失败"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("记忆提取候选状态更新失败")
                .hasMessageContaining("missing-candidate")
                .hasMessageContaining("FAILED");
    }

    @Test
    void 轮次记忆抽取开始时记录运行状态() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        var repository = new MemoryExtractionCandidateRepository(jdbcTemplate, new ObjectMapper());

        repository.markTurnRunning("session-1", "turn-1");

        verify(jdbcTemplate).update(
                contains("INSERT INTO memory_extraction_turn_status"),
                eq("turn-1"), eq("session-1"), eq("RUNNING"), isNull(),
                any(), isNull(), any());
    }

    @Test
    void 轮次记忆抽取完成时记录终态时间() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        var repository = new MemoryExtractionCandidateRepository(jdbcTemplate, new ObjectMapper());

        repository.markTurnCompleted("session-1", "turn-1");

        verify(jdbcTemplate).update(
                contains("INSERT INTO memory_extraction_turn_status"),
                eq("turn-1"), eq("session-1"), eq("COMPLETED"), isNull(),
                any(), any(), any());
    }

    @Test
    void 轮次记忆抽取失败时截断过长原因() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        var repository = new MemoryExtractionCandidateRepository(jdbcTemplate, new ObjectMapper());
        String reason = "错".repeat(600);

        repository.markTurnFailed("session-1", "turn-1", reason);

        verify(jdbcTemplate).update(
                contains("INSERT INTO memory_extraction_turn_status"),
                eq("turn-1"), eq("session-1"), eq("FAILED"), eq("错".repeat(500)),
                any(), any(), any());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void 查询已应用记忆变更时包含忘记操作() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        var repository = new MemoryExtractionCandidateRepository(jdbcTemplate, new ObjectMapper());

        repository.findAppliedMemoryChangesByTurnId("turn-1", 5);

        verify(jdbcTemplate).query(
                contains("operation IN ('ADD', 'UPDATE', 'DELETE')"),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class));
    }

    @Test
    void 删除会话候选时应同时清理轮次状态() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(contains("DELETE FROM memory_extraction_turn_status"), eq("session-1")))
                .thenReturn(2);
        when(jdbcTemplate.update(contains("DELETE FROM memory_extraction_candidates"), eq("session-1")))
                .thenReturn(3);
        var repository = new MemoryExtractionCandidateRepository(jdbcTemplate, new ObjectMapper());

        int deleted = repository.deleteBySessionId("session-1");

        assertThat(deleted).isEqualTo(5);
        verify(jdbcTemplate).update(contains("DELETE FROM memory_extraction_turn_status"), eq("session-1"));
        verify(jdbcTemplate).update(contains("DELETE FROM memory_extraction_candidates"), eq("session-1"));
    }
}
