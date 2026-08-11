package com.lifepilot.memory.store.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.projection.MemoryProjectionOutboxProcessor;
import com.lifepilot.memory.store.projection.MemoryProjectionOutboxRepository;
import com.lifepilot.memory.store.projection.MemoryProjectionService;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;

/**
 * SemanticMemory 测试装配工具。
 *
 * @author zsg
 * @since 2026-06-26
 */
public final class SemanticMemoryTestSupport {

    private SemanticMemoryTestSupport() {
    }

    public static MemorySpaceRepository memorySpaceRepository(JdbcTemplate jdbcTemplate) {
        return new MemorySpaceRepository(jdbcTemplate, new ObjectMapper());
    }

    public static MemoryProjectionService projectionService() {
        return mock(MemoryProjectionService.class);
    }

    public static MemoryProjectionService projectionService(JdbcTemplate jdbcTemplate,
                                                            VectorSearcher vectorSearcher) {
        var objectMapper = new ObjectMapper();
        var repository = new MemoryProjectionOutboxRepository(jdbcTemplate, objectMapper);
        var processor = new MemoryProjectionOutboxProcessor(repository, vectorSearcher, objectMapper);
        return new MemoryProjectionService(repository, processor);
    }
}
