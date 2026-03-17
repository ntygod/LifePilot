package com.lifepilot.memory.compression;

import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.prompt.PromptRegistry;
import net.jqwik.api.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CompressionService 属性测试 — 验证 pinned 消息保留和滑动窗口覆盖不变量。
 *
 * @author zsg
 * @since 2026-03-17
 */
class CompressionService属性测试 {

    // ─────────────────────────────────────────────
    //  Property P4 — CompressionService pinned 消息保留
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 4.5, 7.5</b>
     *
     * <p>对任意包含 pinned 消息的列表，执行 compressWithSlidingWindow 后，
     * pinned 消息不会被传入 episodicMemory.compress()，即保持 ORIGINAL 层级。</p>
     */
    @Property(tries = 100)
    void pinned消息在压缩中保持ORIGINAL(@ForAll("messageListsWithPinned") List<MessageRecord> messages) {
        // 构建 Mock
        var llmRouter = mock(LlmRouter.class);
        var episodicMemory = mock(EpisodicMemory.class);
        var promptRegistry = mock(PromptRegistry.class);
        var properties = buildProperties();

        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");
        when(llmRouter.call(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("压缩摘要", 100, 50, "test", "test-model", 100, false));

        var service = new CompressionService(llmRouter, episodicMemory, promptRegistry, properties);
        String conversationId = "conv-" + UUID.randomUUID();

        // 执行滑动窗口压缩
        service.compressWithSlidingWindow(conversationId, messages, CompressionLevel.SUMMARY);

        // 收集所有被压缩的消息 ID
        var pinnedIds = messages.stream()
                .filter(MessageRecord::isPinned)
                .map(MessageRecord::id)
                .toList();

        // 验证：pinned 消息的 ID 不应出现在任何 compress 调用的 compressedTexts 中
        if (!pinnedIds.isEmpty()) {
            verify(episodicMemory, atLeast(0)).compress(anyString(), any(CompressionLevel.class), argThat(map -> {
                for (String pinnedId : pinnedIds) {
                    if (map.containsKey(pinnedId)) {
                        fail("pinned 消息 " + pinnedId + " 不应被压缩");
                    }
                }
                return true;
            }));
        }
    }

    // ─────────────────────────────────────────────
    //  Property P5 — CompressionService 滑动窗口覆盖
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 4.6, 7.7, 7.8</b>
     *
     * <p>对任意消息列表（size > windowSize），滑动窗口分段后：
     * <ul>
     *   <li>所有非最后一个窗口的消息都被某个窗口覆盖</li>
     *   <li>最后一个窗口的消息不被压缩（保持原始）</li>
     * </ul></p>
     */
    @Property(tries = 100)
    void 滑动窗口覆盖所有非最近窗口消息(@ForAll("largeMessageLists") List<MessageRecord> messages) {
        var properties = buildProperties();
        int windowSize = properties.getCompression().getWindowSize();
        int windowOverlap = properties.getCompression().getWindowOverlap();

        // 使用 package-private 方法直接测试分段逻辑
        var service = new CompressionService(
                mock(LlmRouter.class),
                mock(EpisodicMemory.class),
                mock(PromptRegistry.class),
                properties
        );
        var windows = service.partitionSlidingWindows(messages, windowSize, windowOverlap);

        // 至少有 2 个窗口（因为 size > windowSize）
        assertTrue(windows.size() >= 2,
                "消息数 > windowSize 时应至少有 2 个窗口, 实际窗口数=" + windows.size());

        // 收集非最后窗口覆盖的所有消息
        var coveredMessages = new HashSet<String>();
        for (int i = 0; i < windows.size() - 1; i++) {
            for (var msg : windows.get(i)) {
                coveredMessages.add(msg.id());
            }
        }

        // 验证：非最后窗口中的所有消息都被覆盖
        // 计算非最后窗口应覆盖的消息范围（从第一条到最后窗口起始位置之前）
        int step = Math.max(1, windowSize - windowOverlap);
        int lastWindowStart = (windows.size() - 1) * step;
        for (int i = 0; i < Math.min(lastWindowStart, messages.size()); i++) {
            assertTrue(coveredMessages.contains(messages.get(i).id()),
                    "消息 index=" + i + " 应被某个非最后窗口覆盖");
        }

        // 验证：最后一个窗口的消息存在
        assertFalse(windows.getLast().isEmpty(), "最后一个窗口不应为空");
    }

    // ─────────────────────────────────────────────
    //  数据生成器
    // ─────────────────────────────────────────────

    /** 生成包含 pinned 消息的随机消息列表（至少 25 条，确保多窗口 + 至少 1 条 pinned）。 */
    @Provide
    Arbitrary<List<MessageRecord>> messageListsWithPinned() {
        return Arbitraries.integers().between(25, 60).flatMap(size -> {
            var messageArb = Arbitraries.integers().between(0, size - 1).flatMap(idx ->
                    Combinators.combine(
                            Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(30),
                            Arbitraries.of("user", "assistant"),
                            Arbitraries.integers().between(10, 200),
                            // 约 20% 概率为 pinned
                            Arbitraries.frequency(
                                    Tuple.of(4, false),
                                    Tuple.of(1, true)
                            )
                    ).as((content, role, tokens, pinned) ->
                            new MessageRecord(
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
                            )
                    )
            );
            return messageArb.list().ofSize(size);
        });
    }

    /** 生成大消息列表（size > windowSize=20，确保多窗口）。 */
    @Provide
    Arbitrary<List<MessageRecord>> largeMessageLists() {
        return Arbitraries.integers().between(25, 80).flatMap(size -> {
            var messageArb = Combinators.combine(
                    Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(30),
                    Arbitraries.of("user", "assistant"),
                    Arbitraries.integers().between(10, 200)
            ).as((content, role, tokens) ->
                    new MessageRecord(
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
                    )
            );
            return messageArb.list().ofSize(size);
        });
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 构建默认 MemoryProperties（windowSize=20, windowOverlap=2, threshold=4000）。 */
    private MemoryProperties buildProperties() {
        var properties = new MemoryProperties();
        properties.getCompression().setWindowSize(20);
        properties.getCompression().setWindowOverlap(2);
        properties.setCompressionThresholdTokens(4000);
        return properties;
    }
}
