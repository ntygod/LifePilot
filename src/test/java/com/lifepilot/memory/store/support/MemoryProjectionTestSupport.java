package com.lifepilot.memory.store.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.store.projection.MemoryProjectionOutboxProcessor;
import com.lifepilot.memory.store.projection.MemoryProjectionOutboxRepository;
import com.lifepilot.memory.store.projection.MemoryProjectionService;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.memory.store.entity.SemanticMemory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 记忆投影测试装配工具。
 *
 * @author zsg
 * @since 2026-05-05
 */
public final class MemoryProjectionTestSupport {

    private MemoryProjectionTestSupport() {
    }

    public static MemoryProjectionService attach(SemanticMemory semanticMemory,
                                                 JdbcTemplate jdbcTemplate,
                                                 VectorSearcher vectorSearcher) {
        var objectMapper = new ObjectMapper();
        var repository = new MemoryProjectionOutboxRepository(jdbcTemplate, objectMapper);
        var processor = new MemoryProjectionOutboxProcessor(repository, vectorSearcher, objectMapper);
        var projectionService = new MemoryProjectionService(repository, processor);
        semanticMemory.setProjectionService(projectionService);
        return projectionService;
    }
}
