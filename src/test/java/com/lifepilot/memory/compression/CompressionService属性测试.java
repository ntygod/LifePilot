package com.lifepilot.memory.compression;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.consumption.compression.CompressionService;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.prompt.PromptRegistry;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * CompressionService 属性测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class CompressionService属性测试 {

    @Property(tries = 100)
    void pinned消息在压缩映射中保持原样(@ForAll("messageListsWithPinned") List<MessageRecord> messages) {
        var service = new CompressionService(
                mock(GenerationRouter.class),
                mock(EpisodicMemory.class),
                mock(PromptRegistry.class),
                buildProperties()
        );

        var pinnedIds = messages.stream()
                .filter(MessageRecord::isPinned)
                .map(MessageRecord::id)
                .collect(java.util.stream.Collectors.toSet());

        var nonPinned = messages.stream().filter(message -> !message.isPinned()).toList();
        var windows = service.partitionSlidingWindows(
                nonPinned,
                buildProperties().getCompression().getWindowSize(),
                buildProperties().getCompression().getWindowOverlap()
        );

        var coveredIds = windows.stream()
                .flatMap(List::stream)
                .map(MessageRecord::id)
                .collect(java.util.stream.Collectors.toSet());

        for (String pinnedId : pinnedIds) {
            assertFalse(coveredIds.contains(pinnedId));
        }
    }

    @Property(tries = 100)
    void 滑动窗口覆盖所有非最后窗口消息(@ForAll("largeMessageLists") List<MessageRecord> messages) {
        var properties = buildProperties();
        var service = new CompressionService(
                mock(GenerationRouter.class),
                mock(EpisodicMemory.class),
                mock(PromptRegistry.class),
                properties
        );

        int windowSize = properties.getCompression().getWindowSize();
        int windowOverlap = properties.getCompression().getWindowOverlap();
        var windows = service.partitionSlidingWindows(messages, windowSize, windowOverlap);

        assertTrue(windows.size() >= 2);

        var coveredMessages = new HashSet<String>();
        for (int i = 0; i < windows.size() - 1; i++) {
            for (var message : windows.get(i)) {
                coveredMessages.add(message.id());
            }
        }

        int step = Math.max(1, windowSize - windowOverlap);
        int lastWindowStart = (windows.size() - 1) * step;
        for (int i = 0; i < Math.min(lastWindowStart, messages.size()); i++) {
            assertTrue(coveredMessages.contains(messages.get(i).id()));
        }

        assertFalse(windows.getLast().isEmpty());
    }

    @Provide
    Arbitrary<List<MessageRecord>> messageListsWithPinned() {
        return Arbitraries.integers().between(25, 60).flatMap(size -> {
            var messageArb = Combinators.combine(
                    Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(30),
                    Arbitraries.of("user", "assistant"),
                    Arbitraries.integers().between(10, 200),
                    Arbitraries.frequency(
                            Tuple.of(4, false),
                            Tuple.of(1, true)
                    )
            ).as((content, role, tokens, pinned) -> new MessageRecord(
                    UUID.randomUUID().toString(),
                    "conv-test",
                    role,
                    content,
                    null,
                    CompressionLevel.ORIGINAL,
                    pinned,
                    null,
                    tokens,
                    Instant.now()
            ));
            return messageArb.list().ofSize(size);
        });
    }

    @Provide
    Arbitrary<List<MessageRecord>> largeMessageLists() {
        return Arbitraries.integers().between(25, 80).flatMap(size -> {
            var messageArb = Combinators.combine(
                    Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(30),
                    Arbitraries.of("user", "assistant"),
                    Arbitraries.integers().between(10, 200)
            ).as((content, role, tokens) -> new MessageRecord(
                    UUID.randomUUID().toString(),
                    "conv-test",
                    role,
                    content,
                    null,
                    CompressionLevel.ORIGINAL,
                    false,
                    null,
                    tokens,
                    Instant.now()
            ));
            return messageArb.list().ofSize(size);
        });
    }

    private MemoryProperties buildProperties() {
        var properties = new MemoryProperties();
        properties.getCompression().setWindowSize(20);
        properties.getCompression().setWindowOverlap(2);
        properties.setCompressionThresholdTokens(4000);
        return properties;
    }
}
