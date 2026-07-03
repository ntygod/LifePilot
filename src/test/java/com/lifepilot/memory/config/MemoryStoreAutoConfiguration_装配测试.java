package com.lifepilot.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.store.config.MemoryStoreAutoConfiguration;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MemoryStoreAutoConfiguration 装配测试。
 *
 * @author zsg
 * @since 2026-06-22
 */
class MemoryStoreAutoConfiguration_装配测试 {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MemoryStoreAutoConfiguration.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(EmbeddingRouter.class, () -> mock(EmbeddingRouter.class))
            .withBean(GenerationRouter.class, () -> mock(GenerationRouter.class))
            .withBean(PromptRegistry.class, () -> mock(PromptRegistry.class))
            .withPropertyValues(
                    "lifepilot.memory.enabled=true",
                    "lifepilot.memory.workspace.enabled=false",
                    "lifepilot.memory.store.vector-db-url=" + vectorDbUrl());

    @Test
    void 启用记忆存储时应注册轮次记忆快照仓库() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(ChatTurnMemorySnapshotRepository.class));
    }

    private static String vectorDbUrl() {
        String fileName = "lifepilot-memory-store-autoconfig-"
                + UUID.randomUUID().toString().substring(0, 8)
                + ".db";
        return "jdbc:sqlite:" + Path.of(System.getProperty("java.io.tmpdir"), fileName)
                .toString()
                .replace("\\", "/");
    }
}
