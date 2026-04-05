package com.lifepilot.generation.router;

import com.lifepilot.generation.client.GenerationClientFactory;
import com.lifepilot.generation.client.GenerationServiceClient;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.cache.CacheEntry;
import com.lifepilot.llm.cache.SemanticCache;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.config.ProviderType;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.modelservice.model.GenerationSettingsEntity;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.registry.ModelServiceRegistry;
import com.lifepilot.modelservice.repository.GenerationSettingsRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GenerationRouter 单元测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class GenerationRouterTest {

    @Test
    void 显式服务优先于场景绑定() {
        var registry = mock(ModelServiceRegistry.class);
        var settingsRepository = mock(GenerationSettingsRepository.class);
        var clientFactory = mock(GenerationClientFactory.class);
        var circuitBreakerManager = mock(CircuitBreakerManager.class);
        var client = mock(GenerationServiceClient.class);

        var explicitService = generationService(
                "chat-explicit",
                "qwen-explicit",
                List.of(),
                Set.of(GenerationCapability.CHAT, GenerationCapability.STREAMING)
        );
        var sceneService = generationService(
                "chat-scene",
                "qwen-scene",
                List.of("agent_chat"),
                Set.of(GenerationCapability.CHAT, GenerationCapability.STREAMING)
        );

        when(registry.findEnabledById(ModelServiceKind.GENERATION, "chat-explicit"))
                .thenReturn(Optional.of(explicitService));
        when(registry.findEnabledByModelName(ModelServiceKind.GENERATION, null))
                .thenReturn(List.of());
        when(settingsRepository.findDefault())
                .thenReturn(Optional.of(new GenerationSettingsEntity(
                        GenerationSettingsRepository.DEFAULT_ID,
                        "chat-default",
                        Map.of("agent_chat", "chat-scene")
                )));
        when(registry.findEnabledById(ModelServiceKind.GENERATION, "chat-scene"))
                .thenReturn(Optional.of(sceneService));
        when(registry.findEnabledById(ModelServiceKind.GENERATION, "chat-default"))
                .thenReturn(Optional.empty());
        when(registry.findGenerationCandidates("agent_chat", GenerationCapability.CHAT))
                .thenReturn(List.of(sceneService));
        when(registry.findEnabledByKind(ModelServiceKind.GENERATION))
                .thenReturn(List.of(explicitService, sceneService));
        when(circuitBreakerManager.isCallPermitted("chat-explicit", GenerationCapability.CHAT.name()))
                .thenReturn(true);
        when(clientFactory.getOrCreate(explicitService)).thenReturn(client);
        var expected = new LlmResponse("ok", 10, 20, "chat-explicit", "qwen-explicit", 120, false);
        when(client.call("你好", null, Duration.ofSeconds(8))).thenReturn(expected);

        var router = new GenerationRouter(registry, settingsRepository, clientFactory, circuitBreakerManager);
        var actual = router.call(
                "agent_chat",
                "你好",
                null,
                "chat-explicit",
                null,
                GenerationCapability.CHAT,
                Duration.ofSeconds(8)
        );

        assertSame(expected, actual);
        verify(clientFactory).getOrCreate(explicitService);
        verify(clientFactory, never()).getOrCreate(sceneService);
    }

    @Test
    void 首个候选失败时回退到后续服务() {
        var registry = mock(ModelServiceRegistry.class);
        var settingsRepository = mock(GenerationSettingsRepository.class);
        var clientFactory = mock(GenerationClientFactory.class);
        var circuitBreakerManager = mock(CircuitBreakerManager.class);
        var firstClient = mock(GenerationServiceClient.class);
        var secondClient = mock(GenerationServiceClient.class);

        var first = generationService(
                "chat-first",
                "qwen-a",
                List.of("agent_chat"),
                Set.of(GenerationCapability.CHAT, GenerationCapability.STRUCTURED_OUTPUT)
        );
        var second = generationService(
                "chat-second",
                "qwen-b",
                List.of(),
                Set.of(GenerationCapability.CHAT, GenerationCapability.STRUCTURED_OUTPUT)
        );

        when(registry.findEnabledById(ModelServiceKind.GENERATION, null)).thenReturn(Optional.empty());
        when(registry.findEnabledByModelName(ModelServiceKind.GENERATION, null)).thenReturn(List.of());
        when(settingsRepository.findDefault())
                .thenReturn(Optional.of(new GenerationSettingsEntity(
                        GenerationSettingsRepository.DEFAULT_ID,
                        null,
                        Map.of()
                )));
        when(registry.findGenerationCandidates("memory_extract", GenerationCapability.STRUCTURED_OUTPUT))
                .thenReturn(List.of(first, second));
        when(registry.findEnabledByKind(ModelServiceKind.GENERATION))
                .thenReturn(List.of(first, second));
        when(circuitBreakerManager.isCallPermitted(any(), eq(GenerationCapability.STRUCTURED_OUTPUT.name())))
                .thenReturn(true);
        when(clientFactory.getOrCreate(first)).thenReturn(firstClient);
        when(clientFactory.getOrCreate(second)).thenReturn(secondClient);
        when(firstClient.callEntity(eq("prompt"), eq(String.class), any()))
                .thenThrow(new IllegalStateException("first failed"));
        when(secondClient.callEntity(eq("prompt"), eq(String.class), any())).thenReturn("done");

        var router = new GenerationRouter(registry, settingsRepository, clientFactory, circuitBreakerManager);
        var actual = router.callEntity("memory_extract", "prompt", String.class, null, null, null);

        assertEquals("done", actual);
        verify(circuitBreakerManager).recordFailure("chat-first", GenerationCapability.STRUCTURED_OUTPUT.name());
        verify(circuitBreakerManager).recordSuccess("chat-second", GenerationCapability.STRUCTURED_OUTPUT.name());
    }

    @Test
    void 缓存命中时直接返回缓存结果() {
        var registry = mock(ModelServiceRegistry.class);
        var settingsRepository = mock(GenerationSettingsRepository.class);
        var clientFactory = mock(GenerationClientFactory.class);
        var circuitBreakerManager = mock(CircuitBreakerManager.class);
        var semanticCache = mock(SemanticCache.class);

        var router = new GenerationRouter(registry, settingsRepository, clientFactory, circuitBreakerManager);
        router.setSemanticCache(semanticCache);

        when(semanticCache.lookup("agent_chat", null, "text", "你好"))
                .thenReturn(Optional.of(new CacheEntry(
                        "cache-1",
                        "缓存回答",
                        "agent_chat",
                        null,
                        "text",
                        "cached-model",
                        1.0f,
                        1,
                        Instant.now(),
                        Instant.now()
                )));

        var actual = router.call("agent_chat", "你好", null, null, null, GenerationCapability.CHAT, null);

        assertEquals("缓存回答", actual.content());
        assertEquals("cache", actual.providerId());
        verifyNoInteractions(clientFactory);
    }

    private ModelServiceEntity generationService(String id,
                                                 String modelName,
                                                 List<String> supportedScenes,
                                                 Set<GenerationCapability> capabilities) {
        return new ModelServiceEntity(
                id,
                ModelServiceKind.GENERATION,
                ProviderType.OPENAI_COMPATIBLE,
                "http://localhost:8080",
                null,
                modelName,
                30,
                0,
                true,
                supportedScenes,
                capabilities,
                Map.of(),
                id,
                id + " description"
        );
    }
}
