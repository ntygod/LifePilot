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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
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
                null,
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
}
