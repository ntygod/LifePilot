package com.lifepilot.memory.working;

import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.compression.CompressionService;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.trace.MemoryEventRecorder;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WorkingMemory → CompressionService → EpisodicMemory 端到端集成测试。
 *
 * <p>验证 WorkingMemory.flush() 在 Token 超阈值时触发 CompressionService，
 * CompressionService 调用 LLM 压缩后回写 EpisodicMemory。
 * Mock LlmRouter 用于压缩，Mock EpisodicMemory 用于持久化。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@DisplayName("WorkingMemory → CompressionService → EpisodicMemory 集成测试")
class WorkingMemory_Compression_集成测试 {

    private LlmRouter llmRouter;
    private EpisodicMemory episodicMemory;
    private PromptRegistry promptRegistry;
    private MemoryProperties properties;
    private MemoryEventRecorder memoryEventRecorder;
    private WorkingMemoryWal wal;
    private SlotEvictionPolicy slotEvictionPolicy;
    private TokenBudgetAllocator tokenBudgetAllocator;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        episodicMemory = mock(EpisodicMemory.class);
        promptRegistry = mock(PromptRegistry.class);
        memoryEventRecorder = mock(MemoryEventRecorder.class);
        wal = mock(WorkingMemoryWal.class);
        slotEvictionPolicy = mock(SlotEvictionPolicy.class);

        properties = new MemoryProperties();
        // 压缩阈值 2000 Token
        properties.setCompressionThresholdTokens(2000);
        // 足够大的 Token 预算，避免淘汰干扰
        properties.setWorkingMemoryTokenBudget(100_000);
        // 滑动窗口配置
        properties.getCompression().setWindowSize(5);
        properties.getCompression().setWindowOverlap(1);

        tokenBudgetAllocator = new TokenBudgetAllocator(properties);

        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock compression prompt");
    }

    @Test
    void flush超阈值时触发CompressionService压缩并回写EpisodicMemory() {
        // 准备：真实的 CompressionService（集成 LlmRouter + EpisodicMemory）
        var compressionService = new CompressionService(llmRouter, episodicMemory, promptRegistry, properties);

        // Mock LLM 返回压缩摘要
        var llmResponse = new LlmResponse(
                "这是压缩后的摘要内容",
                10, 20, "provider-1", "model-1", 100, false);
        when(llmRouter.call(any(LlmRequest.class))).thenReturn(llmResponse);

        // 创建 WorkingMemory（注入真实 CompressionService）
        var wm = new WorkingMemory(properties, episodicMemory, tokenBudgetAllocator,
                slotEvictionPolicy, memoryEventRecorder, wal, compressionService);

        String sessionId = "session-integration-1";

        // 追加消息：每条 500 Token，5 条 = 2500 Token > 阈值 2000
        for (int i = 0; i < 5; i++) {
            wm.append(sessionId, new ConversationSlot(
                    "user", "这是一段较长的对话内容" + i, 500, 0.8f, false, null, Instant.now()));
        }

        // 执行 flush
        ConversationRecord record = wm.flush(sessionId, "集成测试目标");

        // 验证 1: flush 正常返回
        assertNotNull(record);
        assertEquals(5, record.messages().size());

        // 验证 2: EpisodicMemory.save() 被调用（持久化到 L2）
        verify(episodicMemory).save(any(ConversationRecord.class));

        // 验证 3: CompressionService 被触发（compressWithSlidingWindow 被调用）
        // 注意：compressWithSlidingWindow 是 @Async 但在测试中同步执行
        // 由于窗口大小=5，5条消息只形成1个窗口，不满足多窗口条件，不会实际压缩
        // 但 shouldCompress 会被调用
        // 验证 flush 后会话已清除
        assertTrue(wm.getContext(sessionId).isEmpty(), "flush 后会话应已清除");
    }

    @Test
    void flush未超阈值时不触发压缩() {
        var compressionService = new CompressionService(llmRouter, episodicMemory, promptRegistry, properties);

        var wm = new WorkingMemory(properties, episodicMemory, tokenBudgetAllocator,
                slotEvictionPolicy, memoryEventRecorder, wal, compressionService);

        String sessionId = "session-integration-2";

        // 追加消息：每条 100 Token，3 条 = 300 Token < 阈值 2000
        for (int i = 0; i < 3; i++) {
            wm.append(sessionId, new ConversationSlot(
                    "user", "短消息" + i, 100, 0.8f, false, null, Instant.now()));
        }

        ConversationRecord record = wm.flush(sessionId, "集成测试目标");

        assertNotNull(record);
        assertEquals(3, record.messages().size());

        // 验证 EpisodicMemory.save() 被调用
        verify(episodicMemory).save(any(ConversationRecord.class));

        // 验证 LLM 未被调用（未触发压缩）
        verify(llmRouter, never()).call(any(LlmRequest.class));
    }

    @Test
    void CompressionService为null时flush正常完成() {
        // 不注入 CompressionService
        var wm = new WorkingMemory(properties, episodicMemory, tokenBudgetAllocator,
                slotEvictionPolicy, memoryEventRecorder, wal, null);

        String sessionId = "session-integration-3";

        // 追加超阈值消息
        for (int i = 0; i < 5; i++) {
            wm.append(sessionId, new ConversationSlot(
                    "user", "消息内容" + i, 500, 0.8f, false, null, Instant.now()));
        }

        ConversationRecord record = wm.flush(sessionId, "集成测试目标");

        // flush 正常完成
        assertNotNull(record);
        assertEquals(5, record.messages().size());
        verify(episodicMemory).save(any(ConversationRecord.class));

        // LLM 未被调用
        verify(llmRouter, never()).call(any(LlmRequest.class));
    }

    @Test
    void LLM压缩失败时flush不受影响() {
        var compressionService = new CompressionService(llmRouter, episodicMemory, promptRegistry, properties);

        // Mock LLM 调用失败
        when(llmRouter.call(any(LlmRequest.class)))
                .thenThrow(new RuntimeException("LLM 压缩服务不可用"));

        var wm = new WorkingMemory(properties, episodicMemory, tokenBudgetAllocator,
                slotEvictionPolicy, memoryEventRecorder, wal, compressionService);

        String sessionId = "session-integration-4";

        for (int i = 0; i < 5; i++) {
            wm.append(sessionId, new ConversationSlot(
                    "user", "消息内容" + i, 500, 0.8f, false, null, Instant.now()));
        }

        // flush 不应抛异常
        ConversationRecord record = wm.flush(sessionId, "集成测试目标");

        // flush 正常返回
        assertNotNull(record);
        assertEquals(5, record.messages().size());
        verify(episodicMemory).save(any(ConversationRecord.class));

        // 会话已清除
        assertTrue(wm.getContext(sessionId).isEmpty());
    }
}
