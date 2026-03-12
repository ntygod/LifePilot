package com.lifepilot.llm;

import com.lifepilot.llm.adapter.SpringAiProviderAdapter;
import com.lifepilot.llm.cache.SemanticCache;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderConfig;
import com.lifepilot.llm.config.ProviderType;
import com.lifepilot.llm.registry.ProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmRouterTest {

    @Mock
    private ProviderRegistry providerRegistry;

    @Mock
    private CircuitBreakerManager circuitBreakerManager;

    @Mock
    private SpringAiProviderAdapter adapter;

    @Mock
    private SemanticCache semanticCache;

    private LlmRouter router;

    @BeforeEach
    void setUp() {
        router = new LlmRouter(providerRegistry, circuitBreakerManager, null);
    }

    @Test
    void call_chatRequestRoutesWithoutRecursion() {
        ProviderConfig provider = provider("chat-provider", "chat", Set.of(ProviderCapability.CHAT));
        LlmResponse expected = new LlmResponse("ok", 10, 5, "chat-provider", "chat-model", 20, false);

        when(providerRegistry.findByScene("chat")).thenReturn(List.of(provider));
        when(circuitBreakerManager.isCallPermitted("chat-provider", "CHAT")).thenReturn(true);
        when(providerRegistry.getAdapter("chat-provider")).thenReturn(adapter);
        when(adapter.call("hello", null, Duration.ofSeconds(30))).thenReturn(expected);

        LlmResponse actual = router.call("chat", "hello", null);

        assertSame(expected, actual);
        verify(adapter).call("hello", null, Duration.ofSeconds(30));
        verify(circuitBreakerManager).recordSuccess("chat-provider", "CHAT");
    }

    @Test
    void call_requiredCapabilityUsesCapabilityCandidates() {
        ProviderConfig provider = provider("vision-provider", "vision_summary", Set.of(ProviderCapability.VISION));
        LlmResponse expected = new LlmResponse("done", 8, 4, "vision-provider", "vision-model", 12, false);

        when(providerRegistry.findByScene("vision_summary")).thenReturn(List.of(provider));
        when(circuitBreakerManager.isCallPermitted("vision-provider", "VISION")).thenReturn(true);
        when(providerRegistry.getAdapter("vision-provider")).thenReturn(adapter);
        when(adapter.call("describe", null, Duration.ofSeconds(30))).thenReturn(expected);

        LlmResponse actual = router.call(
                "vision_summary",
                ProviderCapability.VISION,
                "describe",
                null,
                null,
                null
        );

        assertSame(expected, actual);
        verify(adapter).call(eq("describe"), eq(null), eq(Duration.ofSeconds(30)));
        verify(circuitBreakerManager).recordSuccess("vision-provider", "VISION");
    }

    @Test
    void call_schemaAwareCacheKeySeparatesStructuredOutputs() {
        ProviderConfig provider = provider("chat-provider", "knowledge_extraction", Set.of(ProviderCapability.CHAT));
        LlmResponse expected = new LlmResponse("{\"answer\":\"ok\"}", 10, 5, "chat-provider", "chat-model", 20, false);
        String schema = "{\"type\":\"object\"}";

        router.setSemanticCache(semanticCache);
        when(semanticCache.lookup(eq("knowledge_extraction"), isNull(),
                argThat(key -> key != null && key.startsWith("json:") && !"text".equals(key)),
                eq("hello"))).thenReturn(Optional.empty());
        when(providerRegistry.findByScene("knowledge_extraction")).thenReturn(List.of(provider));
        when(circuitBreakerManager.isCallPermitted("chat-provider", "CHAT")).thenReturn(true);
        when(providerRegistry.getAdapter("chat-provider")).thenReturn(adapter);
        when(adapter.call("hello", schema, Duration.ofSeconds(30))).thenReturn(expected);

        LlmResponse actual = router.call("knowledge_extraction", "hello", schema);

        assertSame(expected, actual);
        verify(semanticCache).lookup(eq("knowledge_extraction"), isNull(),
                argThat(key -> key != null && key.startsWith("json:") && !"text".equals(key)),
                eq("hello"));
        verify(semanticCache).putAsync(eq("knowledge_extraction"), isNull(),
                argThat(key -> key != null && key.startsWith("json:") && !"text".equals(key)),
                eq("hello"), eq("{\"answer\":\"ok\"}"), eq("chat-model"));
    }

    @Test
    void call_structuredCapabilityAlsoUsesSchemaAwareCacheKey() {
        ProviderConfig provider = provider("structured-provider", "agent_reasoning",
                Set.of(ProviderCapability.STRUCTURED_OUTPUT));
        LlmResponse expected = new LlmResponse("{\"risk\":\"low\"}", 9, 4,
                "structured-provider", "structured-model", 15, false);
        String schema = "{\"type\":\"object\",\"properties\":{\"risk\":{\"type\":\"string\"}}}";

        router.setSemanticCache(semanticCache);
        when(semanticCache.lookup(eq("agent_reasoning"), isNull(),
                argThat(key -> key != null && key.startsWith("json:")),
                eq("analyze"))).thenReturn(Optional.empty());
        when(providerRegistry.findByScene("agent_reasoning")).thenReturn(List.of(provider));
        when(circuitBreakerManager.isCallPermitted("structured-provider", "STRUCTURED_OUTPUT")).thenReturn(true);
        when(providerRegistry.getAdapter("structured-provider")).thenReturn(adapter);
        when(adapter.call("analyze", schema, Duration.ofSeconds(30))).thenReturn(expected);

        LlmResponse actual = router.call(
                "agent_reasoning",
                ProviderCapability.STRUCTURED_OUTPUT,
                "analyze",
                schema,
                null,
                null
        );

        assertSame(expected, actual);
        verify(semanticCache).putAsync(eq("agent_reasoning"), isNull(),
                argThat(key -> key != null && key.startsWith("json:")),
                eq("analyze"), eq("{\"risk\":\"low\"}"), eq("structured-model"));
    }

    private ProviderConfig provider(String id, String scene, Set<ProviderCapability> capabilities) {
        return new ProviderConfig(
                id,
                ProviderType.OPENAI_COMPATIBLE,
                "https://example.com",
                null,
                id + "-model",
                30,
                1,
                List.of(scene),
                capabilities,
                true,
                0,
                0,
                8192,
                null,
                true
        );
    }
}
