package com.lifepilot.memory.compression;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.util.Map;

/**
 * CompressionService 单元测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class CompressionService测试 {

    private GenerationRouter generationRouter;
    private EpisodicMemory episodicMemory;
    private PromptRegistry promptRegistry;
    private MemoryProperties properties;
    private CompressionService service;

    @BeforeEach
    void setUp() {
        generationRouter = mock(GenerationRouter.class);
        episodicMemory = mock(EpisodicMemory.class);
        promptRegistry = mock(PromptRegistry.class);
        properties = new MemoryProperties();
        properties.setCompressionThresholdTokens(4000);
        properties.getCompression().setWindowSize(20);
        properties.getCompression().setWindowOverlap(2);

        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");

        service = new CompressionService(generationRouter, episodicMemory, promptRegistry, properties);
    }

    @Test
    void 低于阈值时不触发压缩() {
        assertFalse(service.shouldCompress(3999));
        assertFalse(service.shouldCompress(4000));
    }

    @Test
    void 超过阈值时触发压缩() {
        assertTrue(service.shouldCompress(4001));
        assertTrue(service.shouldCompress(10000));
    }

    @Test
    void 空消息列表不会触发生成调用() {
        assertDoesNotThrow(() -> service.compressWithSlidingWindow("conv-1", List.of(), CompressionLevel.SUMMARY));
        verifyNoInteractions(generationRouter);
        verify(episodicMemory, never()).compress(anyString(), any(), anyMap());
    }

    @Test
    void 生成路由器缺失时跳过压缩且不写回() {
        var serviceWithoutRouter = new CompressionService(null, episodicMemory, promptRegistry, properties);
        var messages = new ArrayList<MessageRecord>();
        for (int i = 0; i < 25; i++) {
            messages.add(createMessage("msg-" + i, false, 100));
        }

        assertDoesNotThrow(() ->
                serviceWithoutRouter.compressWithSlidingWindow("conv-no-router", messages, CompressionLevel.SUMMARY));

        verify(promptRegistry, never()).render(anyString(), anyMap());
        verify(episodicMemory, never()).compress(anyString(), any(), anyMap());
    }

    @Test
    void 全部Pinned消息不会参与压缩() {
        var messages = new ArrayList<MessageRecord>();
        for (int i = 0; i < 25; i++) {
            messages.add(createMessage("msg-" + i, true, 100));
        }

        service.compressWithSlidingWindow("conv-1", messages, CompressionLevel.SUMMARY);

        verifyNoInteractions(generationRouter);
        verify(episodicMemory, never()).compress(anyString(), any(), anyMap());
    }

    @Test
    void 混合消息时压缩映射中不会包含Pinned消息() {
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new LlmResponse("摘要", null, null, List.of(), Map.of(), 100, 50, null, 0, "test", "model", 100, false));

        var messages = new ArrayList<MessageRecord>();
        var pinnedIds = new ArrayList<String>();
        for (int i = 0; i < 30; i++) {
            boolean pinned = i < 5;
            String id = "msg-" + i;
            if (pinned) {
                pinnedIds.add(id);
            }
            messages.add(createMessage(id, pinned, 100));
        }

        service.compressWithSlidingWindow("conv-1", messages, CompressionLevel.SUMMARY);

        verify(episodicMemory, atLeastOnce()).compress(eq("conv-1"), any(CompressionLevel.class), org.mockito.ArgumentMatchers.argThat(map -> {
            for (String pinnedId : pinnedIds) {
                if (map.containsKey(pinnedId)) {
                    return false;
                }
            }
            return true;
        }));
    }

    @Test
    void 压缩后仍超过阈值时继续触发Keypoints压缩() {
        properties.setCompressionThresholdTokens(100);
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new LlmResponse("这是一段较长的压缩摘要文本用于测试二级压缩", null, null, List.of(), Map.of(), 100, 50, null, 0, "test", "model", 100, false));

        var messages = new ArrayList<MessageRecord>();
        for (int i = 0; i < 25; i++) {
            messages.add(createMessage("msg-" + i, false, 200));
        }

        service.compressWithSlidingWindow("conv-1", messages, CompressionLevel.SUMMARY);

        verify(episodicMemory, atLeastOnce()).compress(eq("conv-1"), eq(CompressionLevel.KEYPOINTS), anyMap());
    }

    @Test
    void 压缩后低于阈值时不会触发Keypoints压缩() {
        properties.setCompressionThresholdTokens(100000);
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new LlmResponse("短摘要", null, null, List.of(), Map.of(), 100, 50, null, 0, "test", "model", 100, false));

        var messages = new ArrayList<MessageRecord>();
        for (int i = 0; i < 25; i++) {
            messages.add(createMessage("msg-" + i, false, 100));
        }

        service.compressWithSlidingWindow("conv-1", messages, CompressionLevel.SUMMARY);

        verify(episodicMemory, atLeastOnce()).compress(eq("conv-1"), eq(CompressionLevel.SUMMARY), anyMap());
        verify(episodicMemory, never()).compress(eq("conv-1"), eq(CompressionLevel.KEYPOINTS), anyMap());
    }

    @Test
    void 生成调用失败时保留原内容且不抛异常() {
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new LlmUnavailableException("模拟生成服务不可用", "test", List.of()));

        var messages = new ArrayList<MessageRecord>();
        for (int i = 0; i < 25; i++) {
            messages.add(createMessage("msg-" + i, false, 100));
        }

        assertDoesNotThrow(() -> service.compressWithSlidingWindow("conv-1", messages, CompressionLevel.SUMMARY));
        verify(episodicMemory, never()).compress(anyString(), any(), anyMap());
    }

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
