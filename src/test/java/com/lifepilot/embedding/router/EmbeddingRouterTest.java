package com.lifepilot.embedding.router;

import com.lifepilot.embedding.client.EmbeddingClientFactory;
import com.lifepilot.embedding.client.EmbeddingServiceClient;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.config.ProviderType;
import com.lifepilot.modelservice.model.EmbeddingSettingsEntity;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.registry.ModelServiceRegistry;
import com.lifepilot.modelservice.repository.EmbeddingSettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EmbeddingRouter 单元测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class EmbeddingRouterTest {

    @Test
    void 按用例优先命中绑定服务() {
        var registry = mock(ModelServiceRegistry.class);
        var settingsRepository = mock(EmbeddingSettingsRepository.class);
        var clientFactory = mock(EmbeddingClientFactory.class);
        var circuitBreakerManager = mock(CircuitBreakerManager.class);
        var client = mock(EmbeddingServiceClient.class);

        var memoryService = embeddingService("embedding-memory", "bge-memory");
        var defaultService = embeddingService("embedding-default", "bge-default");

        when(registry.findEnabledById(ModelServiceKind.EMBEDDING, null)).thenReturn(Optional.empty());
        when(registry.findEnabledByModelName(ModelServiceKind.EMBEDDING, null)).thenReturn(List.of());
        when(settingsRepository.findDefault())
                .thenReturn(Optional.of(new EmbeddingSettingsEntity(
                        EmbeddingSettingsRepository.DEFAULT_ID,
                        "embedding-default",
                        null,
                        "embedding-memory"
                )));
        when(registry.findEnabledById(ModelServiceKind.EMBEDDING, "embedding-memory"))
                .thenReturn(Optional.of(memoryService));
        when(registry.findEnabledById(ModelServiceKind.EMBEDDING, "embedding-default"))
                .thenReturn(Optional.of(defaultService));
        when(registry.findEnabledByKind(ModelServiceKind.EMBEDDING))
                .thenReturn(List.of(memoryService, defaultService));
        when(circuitBreakerManager.isCallPermitted("embedding-memory", "EMBEDDING")).thenReturn(true);
        when(clientFactory.getOrCreate(memoryService)).thenReturn(client);
        when(client.embed("记忆查询")).thenReturn(new float[] {0.1f, 0.2f});

        var router = new EmbeddingRouter(registry, settingsRepository, clientFactory, circuitBreakerManager);
        var actual = router.embed("记忆查询", EmbeddingUseCase.MEMORY, null, null);

        assertArrayEquals(new float[] {0.1f, 0.2f}, actual);
        verify(clientFactory).getOrCreate(memoryService);
        verify(clientFactory, never()).getOrCreate(defaultService);
    }

    @Test
    void 显式模型名优先于默认绑定() {
        var registry = mock(ModelServiceRegistry.class);
        var settingsRepository = mock(EmbeddingSettingsRepository.class);
        var clientFactory = mock(EmbeddingClientFactory.class);
        var circuitBreakerManager = mock(CircuitBreakerManager.class);
        var client = mock(EmbeddingServiceClient.class);

        var explicitModelService = embeddingService("embedding-explicit", "bge-reranker");
        var defaultService = embeddingService("embedding-default", "bge-default");

        when(registry.findEnabledById(ModelServiceKind.EMBEDDING, null)).thenReturn(Optional.empty());
        when(registry.findEnabledByModelName(ModelServiceKind.EMBEDDING, "bge-reranker"))
                .thenReturn(List.of(explicitModelService));
        when(settingsRepository.findDefault())
                .thenReturn(Optional.of(new EmbeddingSettingsEntity(
                        EmbeddingSettingsRepository.DEFAULT_ID,
                        "embedding-default",
                        null,
                        null
                )));
        when(registry.findEnabledById(ModelServiceKind.EMBEDDING, "embedding-default"))
                .thenReturn(Optional.of(defaultService));
        when(registry.findEnabledByKind(ModelServiceKind.EMBEDDING))
                .thenReturn(List.of(explicitModelService, defaultService));
        when(circuitBreakerManager.isCallPermitted("embedding-explicit", "EMBEDDING")).thenReturn(true);
        when(clientFactory.getOrCreate(explicitModelService)).thenReturn(client);
        when(client.embedBatch(List.of("a", "b"))).thenReturn(new float[][] {{1.0f}, {2.0f}});

        var router = new EmbeddingRouter(registry, settingsRepository, clientFactory, circuitBreakerManager);
        var actual = router.embedBatch(List.of("a", "b"), EmbeddingUseCase.DEFAULT, null, "bge-reranker");

        assertArrayEquals(new float[] {1.0f}, actual[0]);
        assertArrayEquals(new float[] {2.0f}, actual[1]);
        verify(clientFactory).getOrCreate(explicitModelService);
        verify(clientFactory, never()).getOrCreate(defaultService);
    }

    private ModelServiceEntity embeddingService(String id, String modelName) {
        return new ModelServiceEntity(
                id,
                ModelServiceKind.EMBEDDING,
                ProviderType.TEI,
                "http://localhost:8082",
                null,
                modelName,
                30,
                0,
                true,
                List.of(),
                Set.of(),
                Map.of(),
                id,
                id + " description"
        );
    }
}
