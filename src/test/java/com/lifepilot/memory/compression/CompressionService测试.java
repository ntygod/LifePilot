package com.lifepilot.memory.compression;

import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CompressionService 单元测试。
 *
 * @author zsg
 * @since 2026-03-17
 */
class CompressionService测试 {

    private LlmRouter llmRouter;
    private EpisodicMemory episodicMemory;
    private PromptRegistry promptRegistry;
    private MemoryProperties properties;
    private CompressionService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        episodicMemory = mock(EpisodicMemory.class);
        promptRegistry = mock(PromptRegistry.class);
        properties = new MemoryProperties();
        properties.setCompressionThresholdTokens(4000);
        properties.getCompression().setWindowSize(20);
        properties.getCompression().setWindowOverlap(2);

        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");

        service = new CompressionService(llmRouter, episodicMemory, promptRegistry, properties);
    }

    // ─────────────────────────────────────────────
    //  shouldCompress 阈值判断
    // ─────────────────────────────────────────────

    @Test
    void shouldCompress_低于阈值返回false() {
        assertFalse(service.shouldCompress(3999));
        assertFalse(service.shouldCompress(4000));
    }

    @Test
    void shouldCompress_超过阈值返回true() {
        assertTrue(service.shouldCompress(4001));
        assertTrue(service.shouldCompress(10000));
    }

    // ─────────────────────────────────────────────
    //  空消息列表处理
    // ─────────────────────────────────────────────

    @Test
    void compressWithSlidingWindow_空消息列表不抛异常() {
        assertDoesNotThrow(() ->
                service.compressWithSlidingWindow("conv-1", List.of(), CompressionLevel.SUMMARY));
        verifyNoInteractions(llmRouter);
        verify(episodicMemory, never()).compress(anyString(), any(), anyMap());
    }

    @Test
    void compressWithSlidingWindow_null消息列表不抛异常() {
        assertDoesNotThrow(() ->
                service.compressWithSlidingWindow("conv-1", null, CompressionLevel.SUMMARY));
        verifyNoInteractions(llmRouter);
    }

    // ─────────────────────────────────────────────
    //  pinned 消息跳过
    // ─────────────────────────────────────────────

    @Test
    void compressWithSlidingWindow_pinned消息不参与压缩() {
        // 构建 25 条消息，全部 pinned
        var messages = new ArrayList<MessageRecord>();
        for (int i = 0; i < 25; i++) {
            messages.add(createMessage("msg-" + i, true, 100));
        }

        service.compressWithSlidingWindow("conv-1", messages, CompressionLevel.SUMMARY);

        // 全部 pinned → 非 pinned 列表为空 → 不调用 LLM
        verifyNoInteractions(llmRouter);
        verify(episodicMemory, never()).compress(anyString(), any(), anyMap());
    }

    @Test
    void compressWithSlidingWindow_混合消息中pinned不被压缩() {
        when(llmRouter.call(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("摘要", 100, 50, "test", "model", 100, false));

        // 构建 25 条消息：5 条 pinned + 20 条非 pinned
        var messages = new ArrayList<MessageRecord>();
        var pinnedIds = new ArrayList<String>();
        for (int i = 0; i < 25; i++) {
            boolean pinned = i < 5;
            String id = "msg-" + i;
            if (pinned) pinnedIds.add(id);
            messages.add(createMessage(id, pinned, 100));
        }

        service.compressWithSlidingWindow("conv-1", messages, CompressionLevel.SUMMARY);

        // 验证 compress 调用中不包含 pinned 消息 ID
        verify(episodicMemory, atLeast(0)).compress(anyString(), any(CompressionLevel.class), argThat(map -> {
            for (String pinnedId : pinnedIds) {
                assertFalse(map.containsKey(pinnedId),
                        "pinned 消息 " + pinnedId + " 不应出现在压缩映射中");
            }
            return true;
        }));
    }

    // ─────────────────────────────────────────────
    //  SUMMARY → KEYPOINTS 两级递进
    // ─────────────────────────────────────────────

    @Test
    void compressWithSlidingWindow_超阈值150时触发KEYPOINTS压缩() {
        // 设置低阈值使 SUMMARY 后仍超 150%
        properties.setCompressionThresholdTokens(100);

        // 每条消息 200 token，25 条非 pinned → 总 5000 token
        // SUMMARY 压缩后估算 token 仍远超 100 * 1.5 = 150
        when(llmRouter.call(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("这是一段较长的压缩摘要文本用于测试两级递进压缩逻辑", 100, 50, "test", "model", 100, false));

        var messages = new ArrayList<MessageRecord>();
        for (int i = 0; i < 25; i++) {
            messages.add(createMessage("msg-" + i, false, 200));
        }

        service.compressWithSlidingWindow("conv-1", messages, CompressionLevel.SUMMARY);

        // 验证 KEYPOINTS 级别的 compress 被调用
        verify(episodicMemory, atLeastOnce()).compress(eq("conv-1"), eq(CompressionLevel.KEYPOINTS), anyMap());
    }

    @Test
    void compressWithSlidingWindow_未超阈值150不触发KEYPOINTS() {
        // 高阈值 → SUMMARY 后不超 150%
        properties.setCompressionThresholdTokens(100000);

        when(llmRouter.call(any(LlmRequest.class)))
                .thenReturn(new LlmResponse("短摘要", 100, 50, "test", "model", 100, false));

        var messages = new ArrayList<MessageRecord>();
        for (int i = 0; i < 25; i++) {
            messages.add(createMessage("msg-" + i, false, 100));
        }

        service.compressWithSlidingWindow("conv-1", messages, CompressionLevel.SUMMARY);

        // 验证只有 SUMMARY 级别的 compress 被调用，没有 KEYPOINTS
        verify(episodicMemory, atLeastOnce()).compress(eq("conv-1"), eq(CompressionLevel.SUMMARY), anyMap());
        verify(episodicMemory, never()).compress(eq("conv-1"), eq(CompressionLevel.KEYPOINTS), anyMap());
    }

    // ─────────────────────────────────────────────
    //  LLM 调用失败降级
    // ─────────────────────────────────────────────

    @Test
    void compressWithSlidingWindow_LLM失败保留原始内容不抛异常() {
        when(llmRouter.call(any(LlmRequest.class)))
                .thenThrow(new LlmUnavailableException("模拟 LLM 不可用", "test", List.of()));

        var messages = new ArrayList<MessageRecord>();
        for (int i = 0; i < 25; i++) {
            messages.add(createMessage("msg-" + i, false, 100));
        }

        // 不应抛出异常
        assertDoesNotThrow(() ->
                service.compressWithSlidingWindow("conv-1", messages, CompressionLevel.SUMMARY));

        // LLM 失败 → 不调用 episodicMemory.compress
        verify(episodicMemory, never()).compress(anyString(), any(), anyMap());
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    private MessageRecord createMessage(String id, boolean pinned, int tokenCount) {
        return new MessageRecord(
                id,
                "conv-test",
                "user",
                "测试消息内容 " + id,
                null,
                CompressionLevel.ORIGINAL,
                pinned,
                null,
                tokenCount,
                Instant.now()
        );
    }
}
